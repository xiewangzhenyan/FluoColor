r"""验证重新生成的 PG-Grid V2.1 金标准是否与 Android 冻结资源一致。

该脚本只忽略定位 JSON 中不可复现的 ``referenceMetadata.generatedAtUtc``；坐标、
单应矩阵、定位来源、帧级 QC、PG-Quant 光度、SNR 和标志等其他字段必须完全一致。
任何新增/缺失文件也会直接失败，避免只比较部分样例而误判通过。

示例：
    python tools/pg_grid/verify_v2_1_goldens.py ^
      --expected-dir app/src/test/resources/pg_grid/v2_1 ^
      --actual-dir build/wp6-golden-regeneration
"""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    """读取预期目录和本次重生成目录。"""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-dir", type=Path, required=True)
    parser.add_argument("--actual-dir", type=Path, required=True)
    return parser.parse_args()


def json_files(directory: Path) -> dict[str, Path]:
    """返回目录下全部 JSON；目录不存在或没有 JSON 时明确失败。"""

    resolved = directory.resolve()
    if not resolved.is_dir():
        raise FileNotFoundError(f"金标准目录不存在：{resolved}")
    files = {path.name: path for path in sorted(resolved.glob("*.json"))}
    if not files:
        raise FileNotFoundError(f"金标准目录中没有 JSON：{resolved}")
    return files


def normalized(document: Any) -> Any:
    """深拷贝并删除唯一允许变化的生成时间字段。"""

    result = copy.deepcopy(document)
    if isinstance(result, dict):
        metadata = result.get("referenceMetadata")
        if isinstance(metadata, dict):
            metadata.pop("generatedAtUtc", None)
    return result


def first_difference(expected: Any, actual: Any, path: str = "$") -> str | None:
    """返回第一个结构差异的 JSON 路径，便于 CI 或人工快速定位漂移。"""

    if type(expected) is not type(actual):
        return f"{path}: 类型不同 {type(expected).__name__} != {type(actual).__name__}"
    if isinstance(expected, dict):
        if expected.keys() != actual.keys():
            missing = sorted(expected.keys() - actual.keys())
            extra = sorted(actual.keys() - expected.keys())
            return f"{path}: 字段不同，缺失={missing}，新增={extra}"
        for key in expected:
            difference = first_difference(expected[key], actual[key], f"{path}.{key}")
            if difference:
                return difference
        return None
    if isinstance(expected, list):
        if len(expected) != len(actual):
            return f"{path}: 数组长度不同 {len(expected)} != {len(actual)}"
        for index, (expected_item, actual_item) in enumerate(zip(expected, actual, strict=True)):
            difference = first_difference(expected_item, actual_item, f"{path}[{index}]")
            if difference:
                return difference
        return None
    if expected != actual:
        return f"{path}: {expected!r} != {actual!r}"
    return None


def main() -> None:
    """逐文件执行严格 JSON 对比并以非零退出码报告任何漂移。"""

    args = parse_args()
    expected_files = json_files(args.expected_dir)
    actual_files = json_files(args.actual_dir)
    if expected_files.keys() != actual_files.keys():
        missing = sorted(expected_files.keys() - actual_files.keys())
        extra = sorted(actual_files.keys() - expected_files.keys())
        raise AssertionError(f"金标准文件集合不同，缺失={missing}，新增={extra}")

    for name in expected_files:
        expected = normalized(json.loads(expected_files[name].read_text(encoding="utf-8")))
        actual = normalized(json.loads(actual_files[name].read_text(encoding="utf-8")))
        difference = first_difference(expected, actual)
        if difference:
            raise AssertionError(f"{name} 与冻结金标准不一致：{difference}")
        print(f"PASS {name}")

    print(f"全部 {len(expected_files)} 个 PG-Grid/PG-Quant 金标准一致（仅忽略 generatedAtUtc）")


if __name__ == "__main__":
    main()
