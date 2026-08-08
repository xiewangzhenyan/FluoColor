r"""从已冻结的实拍原图派生"紧裁 + 降采样"回归语料。

为什么需要这组语料：首页导入的两条路径（相册、拍照）都会进入 uCrop，用户把四周背景
裁掉是完全正常的操作，却会把芯片占整图的比例从百分之十几推到百分之六七十以上。主区域
检测的阈值策略必须与该比例无关，而现有 6 张实拍语料**全部是未裁切原图**，覆盖不到这一
场景。本脚本沿检测到的芯片边界外扩固定比例裁切，模拟"贴着芯片裁"的最紧情形。

为什么要降采样：紧裁后的全分辨率 JPEG 合计约 10 MB，会让 androidTest APK 明显变大，
而定位链路本身在计算包围率时就把原图降到 1600 px 再检测（见 COVERAGE_DETECT_MAX_SIDE），
判据的容差是 0.35 个单元间距，1600 px 长边足以支撑。降采样只影响测试资产体积，不改变
被测算法。

脚本是确定性的：同样的输入原图与参数必定产出逐字节相同的 JPEG，因此可以随时重生成并与
仓库中冻结的 SHA-256 对照。

用法：

    python tools/pg_grid/export_cropped_real_corpus.py ^
      --reference-dir "<上游 pg_grid.py + 本地 pg_quant.py 的参考目录>" ^
      --corpus-dir "app\src\androidTest\assets\pg_grid\real_v1"
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import json
import sys
from pathlib import Path
from typing import Any

import cv2
import numpy as np

# 裁切框相对检测芯片边界的外扩比例。留出一圈面板边框：轴选择对网格起止位置有约束
# （起点须落在画幅 5%~30%、终点不超过 95%），裁到最外圈单元贴边会让合法晶格被误拒。
CROP_MARGIN_RATIO = 0.04

# 降采样后的长边上限。
DEFAULT_MAX_SIDE = 1600

# JPEG 质量。95 在肉眼无损与体积之间取平衡；固定值保证重生成结果逐字节一致。
JPEG_QUALITY = 95

CASE_SUFFIX = "_cropped"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-dir", type=Path, required=True, help="Python 参考工程根目录")
    parser.add_argument(
        "--corpus-dir",
        type=Path,
        required=True,
        help="real_v1 语料根目录（内含 images/ 与 manifest.json）",
    )
    parser.add_argument("--max-side", type=int, default=DEFAULT_MAX_SIDE, help="降采样后的长边上限")
    return parser.parse_args()


def load_reference_module(reference_dir: Path) -> Any:
    required = ["pg_grid.py", "pg_quant.py"]
    missing = [name for name in required if not (reference_dir / name).is_file()]
    if missing:
        raise FileNotFoundError(f"参考工程缺少文件：{', '.join(missing)}")
    sys.path.insert(0, str(reference_dir.resolve()))
    try:
        return importlib.import_module("pg_grid")
    finally:
        sys.path.pop(0)


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def crop_and_downscale(
    pg_grid: Any,
    image: np.ndarray,
    max_side: int,
) -> np.ndarray:
    """沿检测到的芯片边界紧裁，再按长边上限降采样。"""

    height, width = image.shape[:2]
    region = pg_grid.detect_chip_region(image)
    xs, ys = region.points[:, 0], region.points[:, 1]
    margin_x = float(xs.max() - xs.min()) * CROP_MARGIN_RATIO
    margin_y = float(ys.max() - ys.min()) * CROP_MARGIN_RATIO
    x0 = max(0, int(round(float(xs.min()) - margin_x)))
    y0 = max(0, int(round(float(ys.min()) - margin_y)))
    x1 = min(width, int(round(float(xs.max()) + margin_x)))
    y1 = min(height, int(round(float(ys.max()) + margin_y)))
    if x1 - x0 < 2 or y1 - y0 < 2:
        raise RuntimeError("裁切结果为空，检测到的芯片区域不可用")
    cropped = image[y0:y1, x0:x1]

    crop_h, crop_w = cropped.shape[:2]
    scale = min(1.0, float(max_side) / float(max(crop_h, crop_w)))
    if scale < 1.0:
        # INTER_AREA 是缩小图像的正确选择：它按面积平均，不会像双线性那样产生混叠，
        # 混叠会直接伪造/抹掉单元级结构，破坏语料的科学意义。
        cropped = cv2.resize(
            cropped,
            (max(1, int(round(crop_w * scale))), max(1, int(round(crop_h * scale)))),
            interpolation=cv2.INTER_AREA,
        )
    return cropped


def write_jpeg(path: Path, image: np.ndarray) -> None:
    ok, encoded = cv2.imencode(".jpg", image, [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY])
    if not ok:
        raise RuntimeError(f"无法编码 JPEG：{path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded.tofile(str(path))


def main() -> None:
    args = parse_args()
    pg_grid = load_reference_module(args.reference_dir.resolve())
    corpus_dir = args.corpus_dir.resolve()
    manifest_path = corpus_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    # 只从"未裁切原图"派生，避免二次裁切自我叠加。
    source_cases = [case for case in manifest["cases"] if not str(case["id"]).endswith(CASE_SUFFIX)]
    derived: list[dict[str, Any]] = []

    for case in source_cases:
        source_path = corpus_dir / case["image"]
        original = pg_grid.read_image_unicode(source_path)
        grid_size = int(case["gridSize"])

        cropped = crop_and_downscale(pg_grid, original, args.max_side)
        case_id = f"{case['id']}{CASE_SUFFIX}"
        relative = f"images/{case_id}.jpg"
        target = corpus_dir / relative
        write_jpeg(target, cropped)

        # 在**冻结下来的那张图**上跑参考实现，基线必须来自实际入库的字节，
        # 不能来自裁切前的中间结果。
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            result = pg_grid.process_image(target, grid_size, Path(tmp) / "out")
        lattice = result["lattice_consistency"]
        height, width = cropped.shape[:2]
        chip_points = np.asarray(result["chip_region"]["points"], dtype=np.float32)
        chip_fraction = float(cv2.contourArea(chip_points)) / float(width * height)

        derived.append(
            {
                "id": case_id,
                "image": relative,
                "sourceName": f"由 {case['id']} 沿检测芯片框外扩 {CROP_MARGIN_RATIO:.0%} 裁切，"
                f"并按长边 {args.max_side}px 降采样，用于覆盖 uCrop 紧裁场景",
                "sha256": sha256_of(target),
                "gridSize": grid_size,
                "pixelWidth": int(width),
                "pixelHeight": int(height),
                "chipAreaFraction": round(chip_fraction, 4),
                "pythonUnitPolarity": str(result["unit_polarity"]),
                "pythonChipRegionMethod": str(result["chip_region"]["method"]),
                "pythonCandidateSupportRatio": round(
                    float(lattice["candidate_support_ratio"]), 4
                ),
                "pythonGridCoverageRatio": round(float(lattice.get("grid_coverage_ratio", 0.0)), 4),
                "pythonTrusted": bool(lattice["trusted"]),
            }
        )
        print(
            f"{case_id}: {width}x{height} 芯片占比={chip_fraction:.1%} "
            f"极性={result['unit_polarity']} 区域={result['chip_region']['method']} "
            f"支撑={lattice['candidate_support_ratio']:.3f} "
            f"包围={lattice.get('grid_coverage_ratio', 0.0):.3f} "
            f"trusted={lattice['trusted']}"
        )

    manifest["cases"] = source_cases + derived
    manifest["croppedDerivation"] = {
        "marginRatio": CROP_MARGIN_RATIO,
        "maxSide": int(args.max_side),
        "jpegQuality": JPEG_QUALITY,
        "note": "紧裁语料由 tools/pg_grid/export_cropped_real_corpus.py 确定性派生，"
        "覆盖用户在 uCrop 中贴着芯片裁切后的定位鲁棒性。",
    }
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"\n已写入 {len(derived)} 个紧裁 case 并更新 {manifest_path}")


if __name__ == "__main__":
    main()
