"""离线对比规则芯片定位；使用原始图及已知规格，真值只在定位结束后评分。

--reference-dir 指向 xiewangzhenyan/pg_grid 的 bc9b9ef 版本。不下载数据，不修改 Android 资产。
--suite multimodal 另测无罩/不透光罩/半透罩比色及荧光；在输出目录生成留出图，仅评分使用真值。
--suite controls 单独测无芯片负对照，以及实拍照片施加已知变换后的同孔号一致性（非准确率）。
--suite kmeans 复用双模态图像协议，在完全相同的连通域候选上对照坐标 K-means 和规则晶格。
结果包含逐图指标、原图坐标、同图叠加图库；不同前端协议、实拍与仿真不能合并为一个准确率。
"""

import argparse
import csv
import hashlib
import html
import importlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from time import perf_counter

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.spatial import cKDTree

from export_perturbation_corpus import original_pitch, sha256_path


REFERENCE_SHA = 'b87f87db0d340cac769ed087cd95d0925e7b7899502b24ef02b253aa3462ee7a'
ASSETS = Path(__file__).resolve().parents[2] / 'app/src/androidTest/assets/pg_grid'
METHODS = {'pg': 'PG-Grid 融合', 'uniform': '固定版距均分', 'projection': '行列投影峰值',
           'period': '自相关周期＋相位', 'components': '连通域质心＋晶格', 'hough': '霍夫圆＋晶格'}
GROUPS = {'perturbation': '七扰动仿真', 'fluo_challenge': '荧光仿真难例',
          'real_original': '实拍原图', 'real_crop': '同源紧裁'}
MULTIMODAL_METHODS = {'pg': METHODS['pg'], 'components': METHODS['components'], 'graph': '稳定斑点拓扑网格',
                      'verified': '双路证据复核／可拒绝'}
AXIS_METHODS = {'kmeans': '连通域＋坐标 K-means', 'components': METHODS['components']}
MULTIMODAL_GROUPS = {'color_bare': '比色·无罩', 'color_opaque': '比色·黑罩', 'color_translucent': '比色·半透罩',
                     'fluo_new': '荧光·留出', 'color_demo': '比色·示例', 'fluo_challenge': '荧光·旧难例',
                     'real_original': 'EL 背光实拍'}
HOLDOUT_SEEDS = (7321, 14891)
LIMIT = 0.20  # 研究用阈值：相对真值孔距，不是用于正式定量的可用性门槛。


@dataclass
class Case:
    name: str
    group: str
    path: Path
    size: int
    truth: np.ndarray | None
    sha: str


@dataclass
class Prediction:
    points: np.ndarray | None = None
    observed: np.ndarray | None = None  # 仅逐孔局部候选匹配才算直接支撑；行/列峰不算逐孔观测。
    reason: str = ''
    trusted: bool | None = None  # 只记录 PG 原生的可信标记，其余方法不伪造同等 QC。
    selected: str = ''
    evidence: float | None = None


def load_cases(reference_dir):
    synthetic = ASSETS / 'perturbation_v1/manifest.json'
    for item in json.loads(synthetic.read_text('utf-8'))['cases']:
        size = item['gridSize']
        if [(spot['row'], spot['column']) for spot in item['truth']] != list(np.ndindex(size, size)):
            raise ValueError(f"{item['id']} 真值不是完整的行优先序列")
        truth = np.array([[spot['x'], spot['y']] for spot in item['truth']])
        yield Case(item['id'], 'perturbation', ASSETS.parent / item['imageAsset'], size, truth, item['imageSha256'])
    real_dir = ASSETS / 'real_v1'
    for item in json.loads((real_dir / 'manifest.json').read_text('utf-8'))['cases']:
        group = 'real_crop' if item['id'].endswith('_cropped') else 'real_original'
        yield Case(item['id'], group, real_dir / item['image'], item['gridSize'], None, item['sha256'])
    for truth_file in sorted((reference_dir / 'examples/fluo').glob('*/ground_truth.json')):
        scene = json.loads(truth_file.read_text('utf-8'))
        if scene['schema'] != 'pg-fluoro-truth-v1':
            raise ValueError(f'未知荧光仿真格式：{truth_file}')
        path = truth_file.with_name('input.jpg')
        yield Case(path.parent.name, 'fluo_challenge', path, scene['grid_size'], np.array(scene['points']), sha256_path(path))


def multimodal_cases(reference_dir, output, selected, seeds=HOLDOUT_SEEDS):
    """用锁定的公开成像模型新生成留出图。两种孔数、两个种子、三档难度，各模态组合等量；
    原图和真值分开落盘，定位函数仅接收照片与行列数，不能根据模态/难度/真值挑方法。"""
    modules = {'pg_colori_sim': 'e8ed544739ce7a64e80186337f07c6cba4350fd738045b8b37cd10e8e783353d',
               'pg_fluoro_sim': '8629c589a884867b970358ad3134a49c025c153b007455b044628231f81c7cec'}
    for name, digest in modules.items():
        if hashlib.sha256((reference_dir / f'{name}.py').read_bytes().replace(b'\r\n', b'\n')).hexdigest() != digest:
            raise ValueError(f'仿真器版本不符：{name}')
    color, fluorescence = [importlib.import_module(name) for name in modules]
    inputs = output / 'inputs'
    inputs.mkdir()
    for size in (10, 15):
        for level_index, level in enumerate(('ideal', 'typical', 'hard')):
            for seed in seeds:
                actual_seed = seed + size * 100 + level_index * 10000
                random = np.random.default_rng(actual_seed)
                tilt = ((0.3, 1.7), (1.5, 4.0), (4.0, 8.0))[level_index]
                settings = dict(grid_size=size, image_size=1200, seed=actual_seed, panel_fill=random.uniform(0.46, 0.76),
                                well_fill=random.uniform(0.34, 0.48), rotation_deg=random.choice([-1, 1]) * random.uniform(*tilt))
                for group in ('color_bare', 'color_opaque', 'color_translucent', 'fluo_new'):
                    name = f'{group}_{level}_n{size}_s{seed}'
                    if selected and name not in selected:
                        continue
                    if group == 'fluo_new':
                        pattern = 'log_series' if seed == seeds[0] else ('checker' if level == 'hard' else 'random')
                        config = fluorescence.FluorescenceConfig(**(fluorescence.DIFFICULTY_PRESETS[level] | settings), intensity_pattern=pattern)
                        image, truth = fluorescence.render_fluorescence_scene(config)
                    else:
                        config = color.ColorimetricConfig(**(color.DIFFICULTY_PRESETS[level] | settings),
                            layout='bare' if group == 'color_bare' else 'masked', mask_transmittance=0.2 if group == 'color_translucent' else 0.002)
                        image, truth = color.render_colorimetric_scene(config)
                    path = inputs / (name + '.jpg')
                    ok, encoded = cv2.imencode('.jpg', image, [cv2.IMWRITE_JPEG_QUALITY, config.jpeg_quality or 95])
                    if not ok:
                        raise RuntimeError(f'输入编码失败：{name}')
                    path.write_bytes(encoded.tobytes())
                    (inputs / (name + '.json')).write_text(json.dumps(truth, ensure_ascii=False, allow_nan=False), 'utf-8')
                    yield Case(name, group, path, size, np.asarray(truth['points']), sha256_path(path))
    folder = reference_dir / 'examples/color'
    for name in ('plate', 'zero'):
        truth = json.loads((folder / f'{name}_truth.json').read_text('utf-8'))
        path = folder / f'{name}.jpg'
        yield Case('color_demo_' + name, 'color_demo', path, truth['grid_size'], np.asarray(truth['points']), sha256_path(path))
    for case in load_cases(reference_dir):
        if case.group in ('fluo_challenge', 'real_original'):
            yield case


def pg_geometry(reference, image, size, side, config):
    """逐项沿用锁定版本 process_image 的几何段；不把光度、磁盘写图时间算到定位。"""
    pitch = side * (1 - 2 * config.margin_ratio) / (size - 1)
    solutions, cache = [], {}
    for region in reference.iter_region_hypotheses(image):
        solution = reference._solve_with_region(image, region, size, side, config, pitch, cache)
        solutions.append(solution)
        support = solution['lattice_info']['candidate_support_ratio']
        if solution['coverage'] >= 0.95 and support is not None and support >= 0.90:
            break
    if not solutions:
        solutions.append(reference._solve_with_region(image, reference.detect_chip_region(image), size, side, config, pitch, cache))
    best = reference.select_region_solution(solutions)
    lattice = best['lattice_info']
    trusted = bool(lattice['trusted'] and not (best['coverage'] < 0.55 and lattice.get('support_check') != 'unavailable'))
    observed = np.array([spot['source'] == 'candidate_refined' for spot in best['point_meta']])
    return best, Prediction(best['points'], observed, trusted=trusted)


def periodic_axis(profile, count, nominal_pitch):
    """非循环自相关找一个孔距，再独立搜索首孔相位；周期性本身不能证明首行/首列正确。"""
    profile = profile.astype(np.float64)
    smooth = cv2.GaussianBlur(profile[None, :], (0, 0), nominal_pitch)[0]
    residual = profile - smooth
    power = np.abs(np.fft.rfft(residual, n=2 * len(residual))) ** 2
    correlation = np.fft.irfft(power, n=2 * len(residual))[:len(residual)]
    low, high = round(0.70 * nominal_pitch), round(1.25 * nominal_pitch)
    peaks = [lag for lag in range(max(2, low), min(high + 1, len(profile) - 1))
             if correlation[lag - 1] < correlation[lag] >= correlation[lag + 1]]
    if not peaks or correlation[0] <= 0:
        return None
    peak = max(peaks, key=correlation.__getitem__)
    if correlation[peak] < 0.12 * correlation[0]:
        return None
    left, middle, right = correlation[peak - 1:peak + 2]
    pitch = peak + 0.5 * (left - right) / (left - 2 * middle + right)
    offsets = np.arange(count) * pitch
    starts = np.arange(1, len(profile) - offsets[-1] - 1, 0.5)
    if not len(starts):
        return None
    trials = starts[:, None] + offsets
    # 用所有行/列的平滑响应选择起始位置，而非用真值或另一定位器的首孔定相。
    signal = cv2.GaussianBlur(residual[None, :], (0, 0), max(1.0, 0.08 * pitch))[0]
    scores = np.interp(trials, np.arange(len(profile)), signal).mean(axis=1)
    return trials[np.argmax(scores)]


def kmeans_grid(reference, candidates, size, side):
    """仅替换规则轴选择：候选数下限和残余旋转校正与对照晶格相同，不用真值初始化。"""
    if len(candidates) < 4 * size:
        return None
    center = (side / 2, side / 2)
    angle = reference._estimate_lattice_rotation(candidates[:, :2], side)
    aligned = reference._rotate_points(candidates[:, :2], angle, center)
    axes = []
    for coordinates in aligned.T:
        values = np.ascontiguousarray(coordinates[:, None], dtype=np.float32)
        if len(np.unique(values)) < size:
            return None
        # 每轴等权、固定种子；只按图内平方误差选初始化，不用孔距/真值挑解。
        # 收敛容差是矫正图上的 0.01 像素。等权版本并非旧 Android 加权迭代的复刻。
        cv2.setRNGSeed(37)
        _, _, centers = cv2.kmeans(values, size, None, (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 100, .01),
                                   10, cv2.KMEANS_PP_CENTERS)
        ordered = np.sort(centers[:, 0])
        if np.any(np.diff(ordered) <= 0):
            return None
        axes.append(ordered)
    # x 递增为列、y 递增为行；交点先是推算位置，有候选在门限内才记局部支撑。
    grid = np.stack(np.meshgrid(*axes), axis=-1).reshape(-1, 2)
    return reference._rotate_points(grid, -angle, center)


def candidate_grid(reference, candidates, size, side, *, use_kmeans=False):
    """两种局部检测共用晶格组装，避免把排序器差异归功于候选检测。证据不足不退回均分。"""
    candidates = np.asarray(candidates, dtype=np.float32).reshape(-1, 3)
    grid = kmeans_grid(reference, candidates, size, side) if use_kmeans else reference._fit_lattice_from_candidates(candidates, size, side, side)
    if grid is None:
        return Prediction(reason='候选不足或行列无法组网')
    return match_candidates(grid, candidates, size)


def match_candidates(grid, candidates, size):
    """同候选对照统一使用独占匹配和同一孔距门限，不把轴向插值直接算成观测。"""
    grid = np.asarray(grid).reshape(-1, 2).copy()
    # 每个候选只许支持最近的一个格点，同格多候选只取最近者；其余保留为推算点。
    distances, slots = cKDTree(grid).query(candidates[:, :2])
    gate = 0.32 * original_pitch(grid, size)
    observed = np.zeros(len(grid), dtype=bool)
    for index in np.argsort(distances, kind='stable'):
        slot = slots[index]
        if distances[index] <= gate and not observed[slot]:
            grid[slot] = candidates[index, :2]
            observed[slot] = True
    if not observed.any():
        return Prediction(reason='晶格无可配准的局部观测')
    return Prediction(grid, observed)


def baseline(reference, method, gray, size, polarity, margin):
    side = gray.shape[0]
    pitch = side * (1 - 2 * margin) / (size - 1)
    if method == 'uniform':
        return Prediction(reference.generate_grid_points(size, side, side, margin))
    if method == 'projection':
        if np.ptp(gray) == 0:
            return Prediction(reason='无图像对比度')
        points = reference._fit_axis_projection(gray, polarity, size, margin)
        return Prediction(points) if points is not None else Prediction(reason='行或列投影峰不足')
    if method == 'hough':
        circles = cv2.HoughCircles(cv2.GaussianBlur(gray, (5, 5), 1.2), cv2.HOUGH_GRADIENT,
                                   dp=1, minDist=0.6 * pitch, param1=100, param2=12,
                                   minRadius=max(2, round(0.10 * pitch)), maxRadius=round(0.45 * pitch))
        return candidate_grid(reference, [] if circles is None else circles[0], size, side)
    energy = gray if polarity == 'bright' else 255 - gray
    if method == 'period':
        columns = periodic_axis(energy.mean(axis=0), size, pitch)
        rows = periodic_axis(energy.mean(axis=1), size, pitch)
        if columns is None or rows is None:
            return Prediction(reason='行或列周期证据不足')
        return Prediction(np.stack(np.meshgrid(columns, rows), axis=-1).reshape(-1, 2))
    if method != 'components':
        raise ValueError(f'未知方法：{method}')
    return candidate_grid(reference, component_candidates(energy, pitch), size, side)


def component_candidates(energy, pitch):
    # 只取相对于局部背景的正响应，再用 Otsu 与连通域面积筛选方块/光斑；不读取已知孔中心。
    energy = energy.astype(np.float32)
    contrast = np.clip(energy - cv2.GaussianBlur(energy, (0, 0), 0.45 * pitch), 0, 255).astype(np.uint8)
    _, mask = cv2.threshold(contrast, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    _, _, stats, centers = cv2.connectedComponentsWithStats(mask, connectivity=8)
    candidates = []
    for stat, center in zip(stats[1:], centers[1:]):
        _, _, width, height, area = stat
        if 0.015 * pitch ** 2 <= area <= 0.75 * pitch ** 2 and max(width, height) <= 1.1 * pitch:
            candidates.append((*center, np.sqrt(area)))
    return np.asarray(candidates, dtype=np.float32).reshape(-1, 3)


def to_original(prediction, inverse, size):
    if prediction.points is not None:
        points = np.asarray(prediction.points, dtype=np.float32).reshape(-1, 1, 2)
        if len(points) != size ** 2 or not np.isfinite(points).all():
            raise ValueError('定位器返回了无效的格点数量或坐标')
        prediction.points = cv2.perspectiveTransform(points, inverse).reshape(-1, 2)
        if not np.isfinite(prediction.points).all():
            raise ValueError('原图坐标不是有限数值')
    return prediction


def locate(reference, image, size):
    """定位器只接收原图和用户声明的规格；单区域对照不读取 PG 胜出区域、极性或坐标。"""
    config = reference.PipelineConfig(grid_size=size)
    side = reference._default_rectified_size(size)
    start = perf_counter()
    best, pg = pg_geometry(reference, image, size, side, config)
    pg = to_original(pg, best['inverse_matrix'], size)
    result = {('auto', 'pg'): (pg, best['region'].method, best['polarity'], (perf_counter() - start) * 1000)}
    start = perf_counter()
    first = reference.detect_chip_region(image)
    rectified, _, inverse = reference.rectify_chip(image, first, side)
    first_gray = cv2.cvtColor(rectified, cv2.COLOR_BGR2GRAY)
    hint = reference._estimate_polarity_hint(first_gray)
    first_ms = (perf_counter() - start) * 1000
    protocols = [('auto', first_gray, inverse, first.method, hint, first_ms),
                 ('shared', cv2.cvtColor(best['rectified'], cv2.COLOR_BGR2GRAY), best['inverse_matrix'],
                  best['region'].method, best['polarity'], 0.0)]
    for protocol, gray, transform, region, polarity, overhead in protocols:
        for method in list(METHODS)[1:]:
            start = perf_counter()
            if region == 'fallback_center':
                pred = Prediction(reason='未检测到芯片区域，仅有中央兜底框')
            else:
                pred = to_original(baseline(reference, method, gray, size, polarity, config.margin_ratio), transform, size)
            result[protocol, method] = pred, region, polarity, overhead + (perf_counter() - start) * 1000
    return result, np.vstack([best['region'].points, first.points])


def choose_grid(pg, graph, size):
    """双路只在图像证据上竞争，不知道实验真值。分歧明显且证据不足时宁可不给孔号。

    复核得分衡量同一批高对比区域与网格的匹配，不是统计置信概率；它与点云方法共享
    候选，不能当作独立金标准。下述研究门槛在生成留出图前确定，不是临床/量产阈值。
    """
    if graph.points is None:
        choice = 'pg' if pg.trusted and pg.evidence >= 0.30 else None
    else:
        pitch = original_pitch(graph.points, size)
        disagreement = np.percentile(np.linalg.norm(pg.points - graph.points, axis=1), 90) / max(pitch, 1e-6)
        if max(pg.evidence, graph.evidence) < 0.32:
            choice = None
        elif disagreement > 0.45 and abs(pg.evidence - graph.evidence) < 0.12:
            choice = None
        elif graph.evidence > pg.evidence + 0.045 or (not pg.trusted and graph.evidence >= pg.evidence - 0.015):
            choice = 'graph'
        else:
            choice = 'pg' if pg.trusted or pg.evidence >= 0.50 else None
    if choice is None:
        return Prediction(reason='几何证据不足或两路编号分歧', selected='rejected')
    result = pg if choice == 'pg' else graph
    return Prediction(result.points, result.observed, selected=choice, evidence=result.evidence)


def locate_multimodal(reference, image, size):
    from spot_lattice import evidence_score, locate_spots
    config = reference.PipelineConfig(grid_size=size)
    side = reference._default_rectified_size(size)
    start = perf_counter()
    best, pg = pg_geometry(reference, image, size, side, config)
    pg = to_original(pg, best['inverse_matrix'], size)
    pg_ms = (perf_counter() - start) * 1000
    start = perf_counter()
    raw = locate_spots(image, size)
    graph = Prediction(raw.points, raw.observed, raw.reason)
    graph_ms = (perf_counter() - start) * 1000
    start = perf_counter()
    pg.evidence = evidence_score(pg.points, raw.candidates, size)
    graph.evidence = evidence_score(graph.points, raw.candidates, size)
    verified = choose_grid(pg, graph, size)
    verified_ms = pg_ms + graph_ms + (perf_counter() - start) * 1000
    results = {('auto', 'pg'): (pg, best['region'].method, best['polarity'], pg_ms),
               ('auto', 'graph'): (graph, '原图无外框', '双极性＋色差', graph_ms),
               ('auto', 'verified'): (verified, '双路决策', '按选中方法', verified_ms)}
    connected, panel = locate_component_methods(reference, image, size, ('components',))
    results.update(connected)
    crops = [panel, best['region'].points]
    if graph.points is not None:
        crops.append(graph.points)
    return results, np.vstack(crops)


def locate_component_methods(reference, image, size, methods=AXIS_METHODS):
    """两路只接收同一幅原图及规格，候选只提取一次；前端拒绝时都不回退到均匀布点。"""
    start = perf_counter()
    config = reference.PipelineConfig(grid_size=size)
    side = reference._default_rectified_size(size)
    region = reference.detect_chip_region(image)
    rectified, _, inverse = reference.rectify_chip(image, region, side)
    gray = cv2.cvtColor(rectified, cv2.COLOR_BGR2GRAY)
    polarity = reference._estimate_polarity_hint(gray)
    candidates = None if region.method == 'fallback_center' else component_candidates(
        gray if polarity == 'bright' else 255 - gray, side * (1 - 2 * config.margin_ratio) / (size - 1))
    front_ms = (perf_counter() - start) * 1000
    results = {}
    for method in methods:
        start = perf_counter()
        pred = Prediction(reason='未检测到芯片区域，仅有中央兜底框') if candidates is None else to_original(
            candidate_grid(reference, candidates, size, side, use_kmeans=method == 'kmeans'), inverse, size)
        results['auto', method] = pred, region.method, polarity, front_ms + (perf_counter() - start) * 1000
    return results, region.points


def score(prediction, truth, size):
    """必须按同名行列逐点比对；不可用最近邻重排掩盖整格错位。没有人工真值的实拍留空。"""
    if truth is None or prediction.points is None:
        return None, None, None
    if truth.shape != (size ** 2, 2) or not np.isfinite(truth).all():
        raise ValueError('真值数量或格式错误')
    errors = np.linalg.norm(prediction.points - truth, axis=1) / original_pitch(truth, size)
    return float(np.median(errors)), float(np.percentile(errors, 95)), float(np.mean(errors <= LIMIT))


def run_controls(reference, ref_dir, output):
    negative_rows, warp_rows = [], []
    empty = np.zeros((565, 690, 3), dtype=np.uint8)
    controls = [('black', empty), ('white', 255 - empty)] + [(f'noise_{seed}', np.random.default_rng(seed).integers(
        0, 255, size=empty.shape, dtype=np.uint8)) for seed in (37, 23, 59)]
    for name, image in controls:
        outputs, _ = locate_multimodal(reference, image, 10)
        for (_, method), (pred, _, _, _) in outputs.items():
            negative_rows.append(dict(case=name, method=method, output=int(pred.points is not None), pg_trusted=pred.trusted,
                                      selected=pred.selected, reason=pred.reason))
    for case in load_cases(ref_dir):
        if case.group != 'real_original':
            continue
        if sha256_path(case.path).lower() != case.sha.lower():
            raise ValueError(f'{case.name} 实拍图像指纹不符')
        image = reference.read_image_unicode(case.path)
        height, width = image.shape[:2]
        dims = np.array([width-1, height-1], dtype=np.float32)
        source = np.array([[0, 0], [1, 0], [1, 1], [0, 1]], np.float32) * dims
        target = np.array([[.018, .024], [.982, .065], [.963, .972], [.042, .944]], np.float32) * dims
        transform = cv2.getPerspectiveTransform(source, target)
        warped = cv2.warpPerspective(image, transform, (width, height), borderMode=cv2.BORDER_REPLICATE)
        before, _ = locate_multimodal(reference, image, case.size)
        after, _ = locate_multimodal(reference, warped, case.size)
        for method in MULTIMODAL_METHODS:
            first, second = before['auto', method][0], after['auto', method][0]
            # 原坐标经已知变换成为“应随图移动到的位置”，不是人工标注。两个结果同错一格也可能稳定。
            expected = None if first.points is None else cv2.perspectiveTransform(first.points.astype(np.float32)[None], transform)[0]
            _, p95, _ = score(second, expected, case.size)
            warp_rows.append(dict(case=case.name, sha256=case.sha.lower(), method=method,
                before_output=int(first.points is not None), after_output=int(second.points is not None),
                consistency_p95_pitch=p95, consistent=int(p95 is not None and p95 <= LIMIT),
                choice_before=first.selected, choice_after=second.selected, transform=json.dumps(transform.tolist())))
    for name, records in (('negative', negative_rows), ('real_warp', warp_rows)):
        with (output / f'{name}.csv').open('w', newline='', encoding='utf-8-sig') as handle:
            writer = csv.DictWriter(handle, fieldnames=records[0].keys())
            writer.writeheader()
            writer.writerows(records)


def make_sheet(image, case, outputs, corners, rows, path, font, methods=METHODS, caption='自动前端'):
    """多种方法同裁窗、同倍率；真值仅在此处叠加。图片只用于评阅，绝不回送定位或定量。"""
    height, width = image.shape[:2]
    extent = corners if case.truth is None else np.vstack([corners, case.truth])
    low, high = extent.min(axis=0), extent.max(axis=0)
    padding = 0.06 * max(high - low)
    left, top = np.maximum(0, np.floor(low - padding).astype(int))
    right, bottom = np.minimum([width, height], np.ceil(high + padding).astype(int) + 1)
    columns = 2 if len(methods) in (2, 4) else 3
    tile = 460 if columns == 2 else 355
    scale = min(tile / (right - left), tile / (bottom - top))
    base = Image.fromarray(cv2.cvtColor(image[top:bottom, left:right], cv2.COLOR_BGR2RGB))
    base = base.resize((max(1, round(base.width * scale)), max(1, round(base.height * scale))), Image.Resampling.LANCZOS)
    panel_height = tile + 64
    sheet = Image.new('RGB', (tile * columns, 71 + panel_height * ((len(methods) + columns - 1) // columns)), '#152335')
    draw = ImageDraw.Draw(sheet)
    draw.text((12, 6), f'{case.name}   |   {case.size} × {case.size}   |   {caption}', font=font, fill='white')
    draw.text((12, 36), '青色＋ 局部观测    紫色○ 轴线/几何推算    黄色× 仿真真值（实拍无真值）', font=font, fill='#d9e5ef')
    for index, (key, title) in enumerate(methods.items()):
        col, row = index % columns, index // columns
        origin_x, origin_y = col * tile, 71 + row * panel_height
        shift = np.array([origin_x + (tile - base.width) // 2, origin_y + 59 + (tile - base.height) // 2])
        sheet.paste(base, tuple(shift))
        draw.text((origin_x + 8, origin_y), title, font=font, fill='white')
        pred = outputs['auto', key][0]
        entry = rows[key]
        if pred.reason:
            info = pred.reason
        elif case.truth is not None:
            p95 = entry['p95_pitch']
            info = 'P95 <0.001 孔距' if p95 < 0.001 else f'P95 {p95:.3f} 孔距'
        elif pred.observed is not None:
            info = f'局部支撑 {pred.observed.sum()}/{case.size ** 2} · 无真值'
        else:
            info = '仅几何坐标 · 无真值'
        if key == 'pg' and not pred.trusted:
            info += ' · 需复核'
        if pred.selected in ('graph', 'pg'):
            info += ' · 采用' + ('拓扑' if pred.selected == 'graph' else 'PG')
        draw.text((origin_x + 8, origin_y + 27), info, font=font, fill='#ffdb9c')
        for points, is_truth in ((pred.points, False), (case.truth, True)):
            if points is None:
                continue
            pixels = np.rint((points - [left, top]) * scale + shift).astype(int)
            for spot, (pixel_x, pixel_y) in enumerate(pixels):
                if not (shift[0] + 3 <= pixel_x < shift[0] + base.width - 3 and shift[1] + 3 <= pixel_y < shift[1] + base.height - 3):
                    continue
                if is_truth:
                    draw.line((pixel_x-2, pixel_y-2, pixel_x+2, pixel_y+2), fill='#ffd02d')
                    draw.line((pixel_x-2, pixel_y+2, pixel_x+2, pixel_y-2), fill='#ffd02d')
                elif pred.observed is not None and pred.observed[spot]:
                    draw.line((pixel_x-3, pixel_y, pixel_x+3, pixel_y), fill='#00ffff')
                    draw.line((pixel_x, pixel_y-3, pixel_x, pixel_y+3), fill='#00ffff')
                else:
                    draw.ellipse((pixel_x-3, pixel_y-3, pixel_x+3, pixel_y+3), outline='#dbaaff')
    sheet.save(path, quality=90, subsampling=0)


def write_report(output, rows, cases, methods=METHODS, groups=GROUPS):
    """每张图一票，失败仍在分母内；实拍绝不写入零误差或伪造通过率。"""
    summaries = []
    cells = {}
    for protocol in ('auto', 'shared'):
        for group in groups:
            for method in methods:
                subset = [row for row in rows if (row['group'], row['protocol'], row['method']) == (group, protocol, method)]
                if not subset:
                    continue
                outputs = sum(row['output'] for row in subset)
                scored = subset[0]['passed'] is not None
                passes = sum(row['passed'] for row in subset) if scored else None
                summaries.append(dict(protocol=protocol, group=group, method=method, images=len(subset),
                                      output_count=outputs, pass_count=passes, no_output=len(subset)-outputs))
                if protocol == 'auto':
                    cells[method, group] = f'{passes if scored else outputs}/{len(subset)}'
    for name, records in (('metrics', rows), ('summary', summaries)):
        with (output / f'{name}.csv').open('w', newline='', encoding='utf-8-sig') as handle:
            writer = csv.DictWriter(handle, fieldnames=records[0].keys())
            writer.writeheader()
            writer.writerows(records)
    landing = 'color_bare_hard_n15_s7321' if 'color_bare' in groups else 'real_15x15_01'
    options = ''.join(f'<optgroup label="{label}">' + ''.join(
        f'<option value="images/{html.escape(case.name, quote=True)}.jpg"' +
        (' selected' if case.name == landing else '') + f'>{html.escape(case.name)}</option>'
        for case in cases if case.group == group) + '</optgroup>' for group, label in groups.items())
    table = ''.join('<tr><th>' + title + '</th>' + ''.join(
        f'<td>{cells.get((method, group), "—")}</td>' for group in groups) + '</tr>' for method, title in methods.items())
    headers = ''.join(f'<th>{label}</th>' for label in groups.values())
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FluoColor · 定位对照图库</title><style>body{max-width:1080px;margin:24px auto;padding:0 16px;font:16px/1.5 system-ui;color:#162637;background:#f6f9fc}select,button{font:inherit;padding:5px;margin:2px}img{width:100%;height:auto}table{border-collapse:collapse}td,th{padding:5px 12px;border-bottom:1px solid #ccd6df;text-align:left}a{color:#225688}</style>
<h1>微流控芯片定位对照</h1><p>同一张输入、同一物理孔号。上图层是各方法的定位结果，不是浓度。黄色 × 只表示仿真真值。</p>
<label for="cases">选择图像：</label><select id="cases">OPTIONS</select> <button id="prev">上一张</button><button id="next">下一张</button>
<p><a id="zoom"><img id="image" alt="多种定位方法在同一原图上的叠加对比"></a>点击图片可按原尺寸查看。</p>
<h2>按图统计</h2><p>仿真列为有真值的达标图数（P95 ≤ 0.20 孔距，未输出也计在分母），实拍及其紧裁仅为输出图数。定位达标不代表浓度准确。</p>
<table><thead><tr><th>方法</th>HEADERS</tr></thead><tbody>ROWS</tbody></table>
<p><a href="summary.csv">汇总 CSV</a> · <a href="metrics.csv">逐图指标</a> · <a href="coordinates.jsonl">逐孔原图坐标与观测标记</a></p>
<script>const list=document.getElementById('cases');function show(){document.getElementById('image').src=list.value;document.getElementById('zoom').href=list.value;}list.onchange=show;function step(n){list.selectedIndex=(list.selectedIndex+n+list.length)%list.length;show();}document.getElementById('prev').onclick=()=>step(-1);document.getElementById('next').onclick=()=>step(1);show();</script></html>'''
    (output / 'index.html').write_text(page.replace('OPTIONS', options).replace('HEADERS', headers).replace('ROWS', table), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='新的或空的结果目录；已有实验不会被覆盖')
    parser.add_argument('--case', action='append', help='可重复，只跑选定的图像编号')
    parser.add_argument('--suite', choices=('original', 'multimodal', 'controls', 'kmeans'), default='original', help='原六路、双模态留出、负对照/实拍一致性或同候选坐标聚类对照')
    parser.add_argument('--font', default='C:/Windows/Fonts/msyh.ttc', help='图库中文字体的路径')
    args = parser.parse_args()
    ref_dir = args.reference_dir.resolve()
    digest = hashlib.sha256((ref_dir / 'pg_grid.py').read_bytes().replace(b'\r\n', b'\n')).hexdigest()
    if digest != REFERENCE_SHA:
        parser.error('参考几何核心与 bc9b9ef 不一致，拒绝将其他版本混入比较')
    sys.path.insert(0, str(ref_dir))
    reference = importlib.import_module('pg_grid')
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('结果目录非空，请另选目录')
    cv2.setNumThreads(1)
    cv2.setRNGSeed(37)
    if args.suite == 'controls':
        if args.case:
            parser.error('controls 固定运行全部负对照与六张原图，不接受 --case')
        output.mkdir(parents=True, exist_ok=True)
        run_controls(reference, ref_dir, output)
        return
    font = ImageFont.truetype(args.font, 17)
    (output / 'images').mkdir(parents=True, exist_ok=True)
    multi = args.suite in ('multimodal', 'kmeans')
    methods, groups = (MULTIMODAL_METHODS, MULTIMODAL_GROUPS) if multi else (METHODS, GROUPS)
    locator = locate_multimodal if multi else locate
    if args.suite == 'kmeans':
        methods, locator = AXIS_METHODS, locate_component_methods
    if multi:
        research_files = (Path(__file__),) if args.suite == 'kmeans' else (Path(__file__), Path(__file__).with_name('spot_lattice.py'))
        run_info = dict(opencv=cv2.__version__, numpy=np.__version__, python=sys.version, seeds=HOLDOUT_SEEDS,
                        reference_commit='bc9b9efd0a7f5175eff31a6bd96c5d62891dfb94', p95_pitch_limit=LIMIT,
                        code_sha256={path.name: sha256_path(path) for path in research_files})
        if args.suite == 'kmeans':
            run_info.update(suite='kmeans', axis_seed=37, init='k-means++', attempts=10, max_iterations=100, epsilon_px=.01)
        (output / 'run.json').write_text(json.dumps(run_info, indent=2), encoding='utf-8')
    cases = list(multimodal_cases(ref_dir, output, args.case) if multi else load_cases(ref_dir))
    if args.case:
        unknown = set(args.case) - {case.name for case in cases}
        if unknown:
            parser.error(f'不存在的图像：{sorted(unknown)}')
        cases = [case for case in cases if case.name in args.case]
    elif sum(case.group == 'fluo_challenge' for case in cases) != 24:
        parser.error('参考工程应含 examples/fluo 中的全部 24 个困难案例')
    if multi and not args.case and (len(cases) != 80 or any(sum(case.group == group for case in cases) != 12
            for group in ('color_bare', 'color_opaque', 'color_translucent', 'fluo_new'))):
        raise ValueError('双模态语料数量与预定方案不符')
    all_rows = []
    with (output / 'coordinates.jsonl').open('w', encoding='utf-8') as evidence:
        for case in cases:
            if sha256_path(case.path).lower() != case.sha.lower():
                raise ValueError(f'{case.name} 图像指纹不符')
            image = reference.read_image_unicode(case.path)
            outputs, corners = locator(reference, image, case.size)
            main_rows = {}
            for (protocol, method), (pred, region, polarity, milliseconds) in outputs.items():
                median, p95, hits = score(pred, case.truth, case.size)
                row = dict(case=case.name, group=case.group, size=case.size, protocol=protocol, method=method,
                           sha256=case.sha.lower(), region=region, polarity=polarity, output=int(pred.points is not None),
                           observed=None if pred.observed is None else int(pred.observed.sum()), pg_trusted=pred.trusted,
                           median_pitch=median, p95_pitch=p95, hit_fraction=hits,
                           passed=None if case.truth is None else int(p95 is not None and p95 <= LIMIT),
                           elapsed_ms=round(milliseconds, 2), failure=pred.reason)
                if multi:
                    row.update(selected=pred.selected, evidence_score=pred.evidence)
                all_rows.append(row)
                if protocol == 'auto':
                    main_rows[method] = row
                evidence.write(json.dumps(dict(case=case.name, protocol=protocol, method=method,
                    xy_original=None if pred.points is None else pred.points.round(5).tolist(),
                    observed=None if pred.observed is None else pred.observed.tolist()), allow_nan=False) + '\n')
            make_sheet(image, case, outputs, corners, main_rows, output / 'images' / f'{case.name}.jpg', font, methods)
            print(f'{case.group}: {case.name}', flush=True)
    write_report(output, all_rows, cases, methods, groups)


if __name__ == '__main__':
    main()
