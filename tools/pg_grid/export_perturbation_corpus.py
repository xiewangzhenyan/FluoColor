r"""生成 Android/Python 共用的 PG-Grid 七扰动族验收语料。

首批语料覆盖 10×10 暗方块和 15×15 亮点两种主规格，并为旋转、透视、模糊、
光照梯度、遮挡、高光和噪声各冻结一个中等强度 case。脚本不实现另一套算法，图像
生成、扰动和 Python 期望结果全部直接调用用户的 ``pg_grid_remote_check`` 参考工程。

输出目录结构：

    manifest.json
    images/<case>.png
    expected_grid/<case>.json
    expected_quant/<case>.json

示例：

    python tools/pg_grid/export_perturbation_corpus.py ^
      --reference-dir "D:\A-lunwen\8多模态手机方案\pg_grid_remote_check" ^
      --output-dir "app\src\androidTest\assets\pg_grid\perturbation_v1"
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import sys
import tempfile
import zlib
from collections import Counter
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from export_v2_1_goldens import adapt_result, save_png, sha256_files


CORPUS_SCHEMA = "pg-grid-perturbation-corpus-v1"
ASSET_PREFIX = "pg_grid/perturbation_v1"

# 选用参考 benchmark 强度阶梯中的中间级别。首批 14 张图控制 APK 体积和仪器测试耗时；
# 完整强度退化曲线仍可由参考工程 pg_benchmark.run_benchmark 独立生成。
MEDIUM_LEVELS: dict[str, float] = {
    "rotation": 6.0,
    "perspective": 0.05,
    "blur": 3.0,
    "illumination": 0.4,
    "occlusion": 8.0,
    "glare": 2.0,
    "noise": 8.0,
}


def parse_args() -> argparse.Namespace:
    """解析显式输入输出路径，避免把实验室电脑目录写死进代码。"""

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
        help="Android instrumentation assets 中的语料输出目录",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=20260722,
        help="基础场景种子；扰动随机源还会混入 family 与 level",
    )
    return parser.parse_args()


def load_reference_modules(reference_dir: Path) -> tuple[Any, Any]:
    """从用户指定目录导入参考算法和 benchmark，禁止复制其内部实现。"""

    required = ["pg_grid.py", "pg_quant.py", "pg_benchmark.py", "pg_grid_eval.py"]
    missing = [name for name in required if not (reference_dir / name).is_file()]
    if missing:
        raise FileNotFoundError(f"参考工程缺少文件：{', '.join(missing)}")

    sys.path.insert(0, str(reference_dir.resolve()))
    try:
        # 生成器作为独立进程运行；显式 invalidate 可避免开发期间修改参考文件后仍读取
        # 旧的目录缓存。模块本身仍由 Python 正常缓存，单次生成不会重复导入。
        importlib.invalidate_caches()
        pg_grid = importlib.import_module("pg_grid")
        pg_benchmark = importlib.import_module("pg_benchmark")
        return pg_grid, pg_benchmark
    finally:
        sys.path.pop(0)


def stable_perturbation_rng(seed: int, family: str, level: float) -> np.random.Generator:
    """复用参考 benchmark 的确定性随机源规则，保证跨进程生成完全一致。"""

    family_code = zlib.crc32(family.encode("utf-8")) & 0xFFFF
    return np.random.default_rng([seed, family_code, int(level * 100)])


def sha256_path(path: Path) -> str:
    """计算单个输入图的 SHA-256，Android 日志可据此确认读取的是同一资产。"""

    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def original_pitch(points: np.ndarray, grid_size: int) -> float:
    """由扰动后的原图真值估计相邻间距，透视场景使用全部相邻边的中位数。"""

    lattice = np.asarray(points, dtype=np.float64).reshape(grid_size, grid_size, 2)
    horizontal = np.linalg.norm(np.diff(lattice, axis=1), axis=2).reshape(-1)
    vertical = np.linalg.norm(np.diff(lattice, axis=0), axis=2).reshape(-1)
    return float(np.median(np.concatenate([horizontal, vertical])))


def percentile(values: np.ndarray, ratio: float) -> float:
    """使用线性百分位，与 Python 审计报告中的 NumPy 口径保持一致。"""

    return float(np.percentile(np.asarray(values, dtype=np.float64), ratio * 100.0))


def spearman(first: np.ndarray, second: np.ndarray) -> float:
    """复用 benchmark 的无 SciPy 秩相关口径，避免额外引入测试依赖。"""

    a = np.asarray(first, dtype=np.float64)
    b = np.asarray(second, dtype=np.float64)
    if a.size < 3 or a.size != b.size:
        return 0.0
    rank_a = np.argsort(np.argsort(a)).astype(np.float64)
    rank_b = np.argsort(np.argsort(b)).astype(np.float64)
    if rank_a.std() < 1e-9 or rank_b.std() < 1e-9:
        return 0.0
    return float(
        np.mean((rank_a - rank_a.mean()) * (rank_b - rank_b.mean()))
        / (rank_a.std() * rank_b.std())
    )


def write_json(path: Path, document: Any) -> None:
    """统一写出换行结尾的 UTF-8 JSON，方便 Git 审阅和跨平台复现。"""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )


def build_python_metrics(
    adapted_grid: dict[str, Any],
    quant_result: dict[str, Any],
    frame_quality: dict[str, Any],
    truth_points: np.ndarray,
    truth_values: np.ndarray,
    grid_size: int,
) -> dict[str, Any]:
    """汇总 Python 对真值的定位、来源、QC 与光度指标，供 Android 基线日志对照。"""

    predicted_original = np.asarray(
        [[site["original"]["x"], site["original"]["y"]] for site in adapted_grid["sites"]],
        dtype=np.float64,
    )
    pitch = max(original_pitch(truth_points, grid_size), 1e-6)
    normalized_errors = np.linalg.norm(
        predicted_original - truth_points.astype(np.float64), axis=1
    ) / pitch

    units = quant_result["units"]
    polarity = -1.0 if grid_size < 15 else 1.0
    measured = np.asarray(
        [polarity * float(unit["corr_signal_gray"]) for unit in units],
        dtype=np.float64,
    )
    reliable = np.asarray([bool(unit["quant_reliable"]) for unit in units], dtype=bool)
    reliable_spearman = (
        spearman(truth_values[reliable], measured[reliable])
        if int(reliable.sum()) >= 3
        else 0.0
    )
    source_counts = Counter(str(site["source"]) for site in adapted_grid["sites"])
    frame_qc = adapted_grid["frameQc"]
    return {
        "truthPitchOriginalPx": round(pitch, 6),
        "meanErrorPitch": round(float(normalized_errors.mean()), 6),
        "p95ErrorPitch": round(percentile(normalized_errors, 0.95), 6),
        "maxErrorPitch": round(float(normalized_errors.max()), 6),
        "candidateSupportRatio": adapted_grid["geometry"]["candidateSupportRatio"],
        "trusted": bool(adapted_grid["geometry"]["trusted"]),
        "sourceCounts": dict(sorted(source_counts.items())),
        "frameQcCodes": [str(issue["code"]) for issue in frame_qc],
        "hasFailureQc": any(str(issue["severity"]) == "failure" for issue in frame_qc),
        "qualityStatus": str(frame_quality.get("status", "unknown")),
        "qualityScore": float(frame_quality.get("overall_score", 0.0)),
        "qualityReasons": [str(reason) for reason in frame_quality.get("reasons", [])],
        "exposureScore": float(frame_quality.get("exposure_score", 0.0)),
        "blurScore": float(frame_quality.get("blur_score", 0.0)),
        "laplacianVariance": float(frame_quality.get("laplacian_variance", 0.0)),
        "quantReliableRatio": round(float(reliable.mean()), 6),
        "quantSpearmanAll": round(spearman(truth_values, measured), 6),
        "quantSpearmanReliable": round(reliable_spearman, 6),
        "illuminationModel": str(quant_result.get("illumination_model", "unknown")),
        "illuminationUniformity": float(quant_result.get("illumination_uniformity", 0.0)),
    }


def export_case(
    pg_grid: Any,
    pg_benchmark: Any,
    output_dir: Path,
    reference_hash: str,
    grid_size: int,
    family: str,
    level: float,
    seed: int,
) -> dict[str, Any]:
    """生成单个扰动输入并冻结 Python 定位、光度、真值和摘要。"""

    polarity = "bright" if grid_size >= 15 else "dark"
    level_label = str(level).replace(".", "p")
    case_id = f"g{grid_size}_{family}_{level_label}_s{seed}"
    image_asset = f"{ASSET_PREFIX}/images/{case_id}.png"
    grid_asset = f"{ASSET_PREFIX}/expected_grid/{case_id}.json"
    quant_asset = f"{ASSET_PREFIX}/expected_quant/{case_id}.json"

    base, truth_points, truth_values = pg_benchmark.make_scene(grid_size, seed)
    rng = stable_perturbation_rng(seed, family, level)
    image, moved_truth = pg_benchmark.apply_perturbation(
        base,
        truth_points,
        family,
        float(level),
        rng,
    )

    image_path = output_dir / "images" / f"{case_id}.png"
    save_png(image_path, image)
    with tempfile.TemporaryDirectory(prefix=f"pg-grid-corpus-{case_id}-") as temp_dir:
        reference_result = pg_grid.process_image(
            image_path=image_path,
            grid_size=grid_size,
            output_dir=Path(temp_dir) / "out",
        )
        quant_path = Path(reference_result["outputs"]["quant_result_json"])
        if not quant_path.is_file():
            raise FileNotFoundError(f"参考流程没有生成定量结果：{quant_path}")
        quant_result = json.loads(quant_path.read_text(encoding="utf-8"))

    adapted_grid = adapt_result(
        reference_result=reference_result,
        rows=grid_size,
        columns=grid_size,
        polarity=polarity,
        reference_hash=reference_hash,
    )
    # 生成时间不是科学结果，删除后同一参考代码与种子可得到逐字节稳定的语料。
    adapted_grid.get("referenceMetadata", {}).pop("generatedAtUtc", None)
    write_json(output_dir / "expected_grid" / f"{case_id}.json", adapted_grid)
    write_json(output_dir / "expected_quant" / f"{case_id}.json", quant_result)

    truth_rows = [
        {
            "row": index // grid_size,
            "column": index % grid_size,
            "x": round(float(point[0]), 6),
            "y": round(float(point[1]), 6),
            "signalTruth": round(float(truth_values[index]), 6),
        }
        for index, point in enumerate(moved_truth)
    ]
    return {
        "id": case_id,
        "gridSize": grid_size,
        "rows": grid_size,
        "columns": grid_size,
        "targetPolarity": polarity,
        "family": family,
        "level": float(level),
        "seed": seed,
        "imageAsset": image_asset,
        "imageSha256": sha256_path(image_path),
        "pythonGridAsset": grid_asset,
        "pythonQuantAsset": quant_asset,
        "truth": truth_rows,
        "pythonMetrics": build_python_metrics(
            adapted_grid=adapted_grid,
            quant_result=quant_result,
            frame_quality=reference_result["quality"],
            truth_points=moved_truth,
            truth_values=truth_values,
            grid_size=grid_size,
        ),
    }


def main() -> None:
    """生成两种主规格共 14 个 case，并写出稳定排序的总清单。"""

    args = parse_args()
    reference_dir = args.reference_dir.resolve()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    pg_grid, pg_benchmark = load_reference_modules(reference_dir)
    reference_hash = sha256_files(
        [
            reference_dir / "pg_grid.py",
            reference_dir / "pg_quant.py",
            reference_dir / "pg_benchmark.py",
            reference_dir / "pg_grid_eval.py",
        ]
    )

    cases: list[dict[str, Any]] = []
    for grid_size in (10, 15):
        for family, level in MEDIUM_LEVELS.items():
            print(f"生成 {grid_size}×{grid_size} {family} level={level} ...")
            cases.append(
                export_case(
                    pg_grid=pg_grid,
                    pg_benchmark=pg_benchmark,
                    output_dir=output_dir,
                    reference_hash=reference_hash,
                    grid_size=grid_size,
                    family=family,
                    level=level,
                    seed=int(args.seed),
                )
            )

    manifest = {
        "schemaVersion": CORPUS_SCHEMA,
        "generator": "tools/pg_grid/export_perturbation_corpus.py",
        "referenceCoreSha256": reference_hash,
        "caseCount": len(cases),
        "gridSizes": [10, 15],
        "families": list(MEDIUM_LEVELS.keys()),
        "levels": MEDIUM_LEVELS,
        "seed": int(args.seed),
        "cases": cases,
    }
    write_json(output_dir / "manifest.json", manifest)
    print(f"已生成 {len(cases)} 个 PG-Grid 扰动 case：{output_dir}")


if __name__ == "__main__":
    main()
