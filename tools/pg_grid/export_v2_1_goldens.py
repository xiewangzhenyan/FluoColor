r"""把 pg_grid_remote_check 的当前行为冻结为 Android PG-Grid V2.1 金标准。

该脚本是“契约适配器”，不是另一套定位算法：10×10 和 15×15 必须调用用户已经
实现并通过 42 项测试的 Python 工程；4×4 使用本脚本生成的旧规格合成图，同样交给
参考工程处理。输出定位 JSON 增加独立 rows/columns、原图/矫正图双坐标、正逆单应
矩阵以及 Android 领域层需要的稳定枚举编码；同时原样冻结参考工程生成的
`pg-quant-v1` JSON，供 Android 对同图 ROI、背景、平场信号和 SNR 做数值验收。

示例：
    python tools/pg_grid/export_v2_1_goldens.py ^
      --reference-dir "D:\A-lunwen\8多模态手机方案\pg_grid_remote_check" ^
      --output-dir "app\src\test\resources\pg_grid\v2_1"
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import cv2
import numpy as np


SCHEMA_VERSION = "pg-grid-v2.1"


def parse_args() -> argparse.Namespace:
    """解析路径参数，禁止把实验室电脑的绝对路径写死到金标准文件。"""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--reference-dir",
        type=Path,
        required=True,
        help="pg_grid_remote_check 根目录",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Android JVM 测试资源输出目录",
    )
    parser.add_argument(
        "--fixture-output-dir",
        type=Path,
        help="可选：同时冻结 4×4 合成 PNG 的目录，供 Android 同图仪器测试使用",
    )
    return parser.parse_args()


def sha256_files(paths: list[Path]) -> str:
    """计算参考算法核心文件的组合哈希，便于发现 Python 基线发生变化。"""

    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda item: item.name):
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def save_png(path: Path, image: np.ndarray) -> None:
    """以兼容 Windows 中文目录的方式保存临时合成图。"""

    ok, encoded = cv2.imencode(".png", image)
    if not ok:
        raise RuntimeError(f"无法编码测试图片：{path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded.tofile(str(path))


def create_legacy_4x4_image(path: Path) -> None:
    """生成后台兼容的 4×4 暗方块阵列，仅用于冻结旧规格契约。"""

    image = np.full((720, 720, 3), 16, dtype=np.uint8)
    rectangle = ((360, 360), (500, 500), -2.0)
    panel = cv2.boxPoints(rectangle).astype(np.int32)
    cv2.fillConvexPoly(image, panel, (214, 214, 214))
    cv2.polylines(image, [panel], True, (145, 145, 145), 3)

    # 4×4 已不是普通界面的常用规格，但数据层与定位算法必须持续可回归。
    # 位点相对面板边缘保留与 10×10/15×15 样例相近的比例。旧版本曾把 4×4 只画在
    # 中央小区域，导致两端都只能依赖均分网格且 Android 产生低支撑 QC；该图必须真正
    # 验证 4×4 候选定位，而不是只验证“总能补出 16 个点”。
    xs = np.linspace(140, 580, 4)
    ys = np.linspace(140, 580, 4)
    for row, y in enumerate(ys):
        for column, x in enumerate(xs):
            cx = int(round(x + (column % 2) * 1.5))
            cy = int(round(y - (row % 2) * 1.2))
            cv2.rectangle(image, (cx - 15, cy - 15), (cx + 15, cy + 15), (70, 82, 94), -1)
    save_png(path, image)


def load_reference_module(reference_dir: Path) -> Any:
    """从显式目录导入参考模块，避免依赖调用者当前工作目录。"""

    required = ["pg_grid.py", "pg_quant.py", "pg_benchmark.py", "pg_grid_eval.py"]
    missing = [name for name in required if not (reference_dir / name).is_file()]
    if missing:
        raise FileNotFoundError(f"参考工程缺少文件：{', '.join(missing)}")

    sys.path.insert(0, str(reference_dir.resolve()))
    try:
        return importlib.import_module("pg_grid")
    finally:
        # 模块已进入 sys.modules，后续调用不再需要污染全局导入搜索路径。
        sys.path.pop(0)


def homographies(chip_points: list[list[float]], rectified_size: int) -> tuple[np.ndarray, np.ndarray]:
    """根据参考工程四角重建原图↔矫正图单应矩阵。"""

    source = np.asarray(chip_points, dtype=np.float32).reshape(4, 2)
    destination = np.asarray(
        [
            [0.0, 0.0],
            [rectified_size - 1.0, 0.0],
            [rectified_size - 1.0, rectified_size - 1.0],
            [0.0, rectified_size - 1.0],
        ],
        dtype=np.float32,
    )
    forward = cv2.getPerspectiveTransform(source, destination)
    inverse = cv2.getPerspectiveTransform(destination, source)
    return forward, inverse


def flatten_matrix(matrix: np.ndarray) -> list[float]:
    """将 OpenCV 3×3 矩阵转成 Android 约定的行主序 Double 列表。"""

    return [round(float(value), 12) for value in matrix.reshape(-1)]


def project_to_original(points: list[dict[str, Any]], inverse: np.ndarray) -> np.ndarray:
    """把参考工程的矫正图点位回投影到原始定量图坐标。"""

    rectified = np.asarray([[point["x"], point["y"]] for point in points], dtype=np.float32)
    return cv2.perspectiveTransform(rectified.reshape(1, -1, 2), inverse).reshape(-1, 2)


def map_frame_qc(result: dict[str, Any], site_count: int) -> list[dict[str, Any]]:
    """把 Python 诊断映射为 Android 稳定机器码，不写入用户展示文本。"""

    issues: list[dict[str, Any]] = []
    region = result["chip_region"]
    lattice = result["lattice_consistency"]
    quality = result["quality"]

    if region.get("method") == "fallback_center":
        issues.append({"code": "chip_region_fallback", "severity": "warning"})

    support = lattice.get("candidate_support_ratio")
    if support is not None and float(support) < 0.6:
        issues.append(
            {
                "code": "grid_support_low",
                "severity": "failure" if float(support) < 0.35 else "warning",
                "measuredValue": float(support),
                "threshold": 0.6,
            }
        )

    outlier_count = int(lattice.get("outlier_count", 0))
    imputed_ratio = outlier_count / max(site_count, 1)
    if imputed_ratio > 0.2:
        issues.append(
            {
                "code": "high_imputed_ratio",
                "severity": "warning",
                "measuredValue": imputed_ratio,
                "threshold": 0.2,
            }
        )

    # 参考工程 quality.reasons 当前可能为空；显式映射保证未来新增原因时不会混用文案。
    reason_map = {
        "over_exposed": ("over_exposed", "failure"),
        "under_exposed": ("under_exposed", "failure"),
        "blurred": ("blurred", "failure"),
        "illumination_non_uniform": ("illumination_non_uniform", "warning"),
    }
    for reason in quality.get("reasons", []):
        mapping = reason_map.get(str(reason))
        if mapping is not None:
            issues.append({"code": mapping[0], "severity": mapping[1]})
    return issues


def adapt_result(
    reference_result: dict[str, Any],
    rows: int,
    columns: int,
    polarity: str,
    reference_hash: str,
) -> dict[str, Any]:
    """把参考工程方阵输出转换为严格的 PG-Grid V2.1 JSON。"""

    if rows != columns:
        raise ValueError("当前 Python 参考结果仍为方阵；非方阵由 Android 纯数学测试冻结")
    expected = rows * columns
    grid_points = reference_result["grid_points"]
    if len(grid_points) != expected:
        raise ValueError(f"参考结果应包含 {expected} 个点，实际为 {len(grid_points)}")

    rectified_size = int(reference_result["rectified_size"])
    chip_points = reference_result["chip_region"]["points"]
    forward, inverse = homographies(chip_points, rectified_size)
    original_points = project_to_original(grid_points, inverse)
    lattice = reference_result["lattice_consistency"]

    sites: list[dict[str, Any]] = []
    for index, (point, original) in enumerate(zip(grid_points, original_points, strict=True)):
        source = str(point.get("source", "unadjusted"))
        flags = [str(flag) for flag in point.get("flags", [])]
        if source == "model_imputed" and "imputed_position" not in flags:
            flags.append("imputed_position")
        sites.append(
            {
                "key": {"rowIndex": index // columns, "columnIndex": index % columns},
                "siteIndex": index,
                "rectified": {"x": float(point["x"]), "y": float(point["y"])},
                "original": {"x": round(float(original[0]), 6), "y": round(float(original[1]), 6)},
                "confidence": float(point.get("confidence", 0.0)),
                "source": source,
                "flags": flags,
            }
        )

    observed_ratio = float(lattice.get("observed_ratio", 0.0))
    outlier_count = int(lattice.get("outlier_count", 0))
    inlier_count = max(0, min(expected - outlier_count, int(round(observed_ratio * expected))))
    support = lattice.get("candidate_support_ratio")
    geometry_rmse = lattice.get("inlier_rmse_px")

    return {
        "schemaVersion": SCHEMA_VERSION,
        "rows": rows,
        "columns": columns,
        "rectifiedWidth": rectified_size,
        "rectifiedHeight": rectified_size,
        "targetPolarity": polarity,
        "chipRegionMethod": str(reference_result["chip_region"]["method"]),
        "chipCorners": [{"x": float(x), "y": float(y)} for x, y in chip_points],
        "homography": {
            "forward": flatten_matrix(forward),
            "inverse": flatten_matrix(inverse),
        },
        "sites": sites,
        "geometry": {
            "model": str(lattice.get("model", "homography")),
            "candidateSupportRatio": None if support is None else float(support),
            "trusted": bool(lattice.get("trusted", False)),
            "observedRatio": observed_ratio,
            "geometryRmsePx": None if geometry_rmse is None else float(geometry_rmse),
            "inlierCount": inlier_count,
            "outlierCount": outlier_count,
            "meanConfidence": float(lattice.get("mean_confidence", 0.0)),
        },
        "frameQc": map_frame_qc(reference_result, expected),
        "locatorName": "PG-Grid Python reference",
        "locatorVersion": "2.0-adapted-to-2.1",
        "referenceMetadata": {
            "referenceCoreSha256": reference_hash,
            "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
            "algorithm": reference_result.get("algorithm"),
        },
    }


def export_fixture(
    pg_grid: Any,
    image_path: Path,
    grid_size: int,
    polarity: str,
    output_path: Path,
    quant_output_path: Path,
    reference_hash: str,
) -> None:
    """运行一次参考流程并写出定位与光度两份排序稳定、UTF-8 的金标准 JSON。"""

    with tempfile.TemporaryDirectory(prefix=f"pg-grid-{grid_size}x{grid_size}-") as work_dir:
        reference_result = pg_grid.process_image(
            image_path=image_path,
            grid_size=grid_size,
            output_dir=Path(work_dir),
        )
        # `process_image` 的量化文件位于临时目录，必须在上下文退出前读取；否则临时目录
        # 删除后只剩定位结果，Android 端无法完成计划要求的光度同图逐字段比较。
        quant_result_path = Path(reference_result["outputs"]["quant_result_json"])
        if not quant_result_path.is_file():
            raise FileNotFoundError(f"参考流程没有生成量化结果：{quant_result_path}")
        quant_result = json.loads(quant_result_path.read_text(encoding="utf-8"))
    adapted = adapt_result(
        reference_result=reference_result,
        rows=grid_size,
        columns=grid_size,
        polarity=polarity,
        reference_hash=reference_hash,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(adapted, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )
    quant_output_path.parent.mkdir(parents=True, exist_ok=True)
    quant_output_path.write_text(
        json.dumps(quant_result, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    """导出 10×10、15×15 和后台兼容 4×4 三组冻结契约。"""

    args = parse_args()
    reference_dir = args.reference_dir.resolve()
    output_dir = args.output_dir.resolve()
    pg_grid = load_reference_module(reference_dir)
    reference_hash = sha256_files(
        [
            reference_dir / "pg_grid.py",
            reference_dir / "pg_quant.py",
            reference_dir / "pg_benchmark.py",
            reference_dir / "pg_grid_eval.py",
        ]
    )

    export_fixture(
        pg_grid=pg_grid,
        image_path=reference_dir / "examples" / "synthetic_10x10_dark_squares.png",
        grid_size=10,
        polarity="dark",
        output_path=output_dir / "10x10-dark.json",
        quant_output_path=output_dir / "10x10-dark-quant.json",
        reference_hash=reference_hash,
    )
    export_fixture(
        pg_grid=pg_grid,
        image_path=reference_dir / "examples" / "synthetic_15x15_bright_points.png",
        grid_size=15,
        polarity="bright",
        output_path=output_dir / "15x15-bright.json",
        quant_output_path=output_dir / "15x15-bright-quant.json",
        reference_hash=reference_hash,
    )

    def export_legacy_fixture(legacy_image: Path) -> None:
        """用同一张确定性 4×4 PNG 同时生成定位与光度金标准。"""

        create_legacy_4x4_image(legacy_image)
        export_fixture(
            pg_grid=pg_grid,
            image_path=legacy_image,
            grid_size=4,
            polarity="dark",
            output_path=output_dir / "4x4-legacy.json",
            quant_output_path=output_dir / "4x4-legacy-quant.json",
            reference_hash=reference_hash,
        )

    if args.fixture_output_dir is not None:
        fixture_output_dir = args.fixture_output_dir.resolve()
        fixture_output_dir.mkdir(parents=True, exist_ok=True)
        export_legacy_fixture(fixture_output_dir / "synthetic_4x4_dark_squares.png")
    else:
        with tempfile.TemporaryDirectory(prefix="pg-grid-legacy-4x4-") as temp_dir:
            export_legacy_fixture(Path(temp_dir) / "synthetic_4x4_dark_squares.png")

    print(f"PG-Grid V2.1 定位与 PG-Quant 金标准已写入：{output_dir}")


if __name__ == "__main__":
    main()
