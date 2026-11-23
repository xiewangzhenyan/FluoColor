"""独立算法外部对照：同一张原图找阵列，与读取已标定模板的图像配准分开计分。

复用 compare_locators 的 48 张留出输入、另计的挑战图和实拍；比较时不会修改旧研究结果。
第三方代码/权重仅从调用方指定目录读取，不下载、不训练、不装进 Android，不上传芯片图。
"""

import argparse
import contextlib
import csv
import hashlib
import html
import importlib
import importlib.metadata
import io
import json
import sys
import warnings
from dataclasses import dataclass
from pathlib import Path
from time import perf_counter
from unittest.mock import patch

import cv2
import numpy as np
from PIL import ImageFont

from compare_locators import (LIMIT, MULTIMODAL_GROUPS, REFERENCE_SHA, Case, Prediction,
                              load_cases, make_sheet, original_pitch, score, sha256_path)


WIDTH = 960
HOLDOUT = ('color_bare', 'color_opaque', 'color_translucent', 'fluo_new')
DIRECT = {'pg': 'PG-Grid（项目）', 'graph': '斑点拓扑（项目）', 'circles': 'OpenCV 圆点阵列', 'pyphe': 'pyphe 自动布网'}
PAIR = {'sift': 'SIFT + RANSAC', 'orb': 'ORB + RANSAC', 'ecc': 'ECC 金字塔配准', 'lightglue': 'SuperPoint + LightGlue'}
SOURCE_DIGEST = '04b2e6fab321bb63c8b31218f070b6abbf9c19c12b99e4c19a8f9c746f1c23e2'
PYPHE_DIGEST = '6ba97af60dc9ebb8b27593f1d166cd5eb63edf798cae54568b550b255a0fe8cc'
WEIGHTS = {'superpoint_v1.pth': '52b6708629640ca883673b5d5c097c4ddad37d8048b33f09c8ca0d69db12c40e',
           'superpoint_lightglue.pth': '6ff7040d0a497fc6639337946d7538dae07428c18f77a067a0b5a960e7cc551a'}


@dataclass
class Reading:
    prediction: Prediction
    details: str = ''
    matches: int | None = None
    inliers: int | None = None


def refused(reason, details='', matches=None, inliers=None):
    return Reading(Prediction(reason=reason), details, matches, inliers)


def small_gray(image):
    """保留原图宽高比；返回从工作图像素中心到原图像素中心的精确仿射变换。"""
    height, width = image.shape[:2]
    scale = min(1., WIDTH / max(height, width))
    dims = (round(width * scale), round(height * scale))
    gray = cv2.cvtColor(cv2.resize(image, dims, interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
    scale_x, scale_y = width / dims[0], height / dims[1]
    inverse = np.array([[scale_x, 0, (scale_x-1)/2], [0, scale_y, (scale_y-1)/2], [0, 0, 1]], dtype=np.float64)
    return gray, inverse


def project(points, matrix):
    return cv2.perspectiveTransform(np.ascontiguousarray(points, dtype=np.float64).reshape(1, -1, 2), matrix)[0]


def canonical_grid(points, size):
    """对称阵列只按照片上/左编号；用轴方向消除返回序列的翻转，禁止用测试真值选朝向。"""
    grid = np.asarray(points).reshape(size, size, 2)
    variants = [view for turns in range(4) for view in (np.rot90(grid, turns), np.fliplr(np.rot90(grid, turns)))]

    def alignment(view):
        across, down = view[:, -1].mean(axis=0)-view[:, 0].mean(axis=0), view[-1].mean(axis=0)-view[0].mean(axis=0)
        return across[0] / max(np.linalg.norm(across), 1) + down[1] / max(np.linalg.norm(down), 1)
    return max(variants, key=alignment).reshape(-1, 2)


def local_contrast(energy, points, size):
    """只在亮/暗极性都形成网格时用图像自身择优；稀疏阵列用各孔与周边的平均对比，不访问真值。"""
    pitch = original_pitch(points, size)
    center = points.astype(np.float32).reshape(-1, 1, 2)
    response = cv2.GaussianBlur(energy.astype(np.float32), (0, 0), max(1, pitch * .075))
    def sample(offset):
        return cv2.remap(response, center + np.float32(offset), None, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    background = np.mean([sample((pitch*.45, 0)), sample((-pitch*.45, 0)), sample((0, pitch*.45)), sample((0, -pitch*.45))], axis=0)
    return float(np.mean(sample((0, 0)) - background))


def single_grid(gray, size, method, inverse, pyphe_auto):
    if np.ptp(gray) == 0:
        return refused('无图像对比度')
    params = cv2.SimpleBlobDetector_Params()
    params.minThreshold, params.maxThreshold, params.thresholdStep = 1, 253, 8
    params.filterByArea, params.minArea, params.maxArea = True, 6, max(32., 0.8*(min(gray.shape)/size)**2)
    params.filterByCircularity = params.filterByConvexity = params.filterByInertia = False
    params.filterByColor, params.blobColor, params.minDistBetweenBlobs = True, 0, 4
    detector = cv2.SimpleBlobDetector_create(params) if method == 'circles' else None
    options, issues = [], []
    for polarity, bright in [('亮孔', gray), ('暗孔', 255-gray)]:
        notes = io.StringIO()
        if method == 'pyphe':
            try:
                # 原作者的布网函数接受亮点，输出 (row,col) -> (y,x)。不把规则预测误当逐孔质心。
                with contextlib.redirect_stdout(notes), warnings.catch_warnings():
                    warnings.simplefilter('ignore', RuntimeWarning)
                    grid, _ = pyphe_auto(bright, f'{size}-{size}')
                points = np.array([grid[row+1, col+1][::-1] for row, col in np.ndindex(size, size)])
            except (ValueError, ZeroDivisionError, OverflowError, IndexError, FloatingPointError) as exc:
                issues.append(f'{polarity}:{type(exc).__name__}')
                continue
            note = notes.getvalue().strip().replace('\n', ' ')
        else:
            # 原版接口要求检出整阵；聚类模式只作为同一官方算法的第二次尝试，不补缺孔。
            for extra, note in ((0, 'symmetric'), (cv2.CALIB_CB_CLUSTERING, 'symmetric+clustering')):
                found, centers = cv2.findCirclesGrid(255-bright, (size, size), blobDetector=detector,
                                                    flags=cv2.CALIB_CB_SYMMETRIC_GRID | extra)
                if found:
                    break
            if not found:
                continue
            points = canonical_grid(centers, size)
        if not np.isfinite(points).all() or np.any(points < 0) or np.any(points >= gray.shape[::-1]) or original_pitch(points, size) <= 1:
            issues.append(f'{polarity}:无效网格')
            continue
        options.append((local_contrast(bright, points, size), project(points, inverse), polarity + ' ' + note))
    if not options:
        return refused('未形成完整有序阵列', ';'.join(issues))
    _, points, note = max(options, key=lambda option: option[0])
    return Reading(Prediction(points, np.ones(len(points), dtype=bool) if method == 'circles' else None), note)


class Matchers:
    def __init__(self, dependencies):
        # 校验再导入第三方源码；权重加载限定为两份作者文件，使用仅张量模式且绝不回退到联网下载。
        official = dependencies / 'official'
        digest = hashlib.sha256()
        for path in sorted((official / 'lightglue').rglob('*.py')):
            digest.update(path.relative_to(official).as_posix().encode() + b'\0' + path.read_bytes().replace(b'\r\n', b'\n'))
        if digest.hexdigest() != SOURCE_DIGEST:
            raise ValueError('LightGlue 官方源码不符：需要固定提交 5a650c4')
        sys.path[:0] = [str(dependencies / 'python'), str(official)]
        import torch
        from lightglue import LightGlue, SuperPoint
        import pyphe.quantify as quantify
        if hashlib.sha256(Path(quantify.__file__).read_bytes().replace(b'\r\n', b'\n')).hexdigest() != PYPHE_DIGEST:
            raise ValueError('pyphe 自动布网代码与 0.983 作者发行版不符')
        self.pyphe, self.torch = quantify.make_grid_auto, torch
        torch.set_num_threads(1)
        self.device = 'cuda' if torch.cuda.is_available() else 'cpu'
        def read_weights(url, **kwargs):
            name = url.split('/')[-1]
            path = dependencies / 'torch/hub/checkpoints' / name
            if name not in WEIGHTS or sha256_path(path) != WEIGHTS[name]:
                raise ValueError(f'不接受此权重：{name}')
            return torch.load(path, weights_only=True, map_location='cpu')
        with patch.object(torch.hub, 'load_state_dict_from_url', read_weights):
            self.superpoint = SuperPoint(max_num_keypoints=1536).eval().to(self.device)
            self.lightglue = LightGlue(features='superpoint', flash=False).eval().to(self.device)
        self.cv_detectors = {'sift': cv2.SIFT_create(nfeatures=1536), 'orb': cv2.ORB_create(nfeatures=1536)}

    def features(self, gray, method):
        if method in self.cv_detectors:
            keys, desc = self.cv_detectors[method].detectAndCompute(gray, None)
            return np.array([key.pt for key in keys], dtype=np.float32).reshape(-1, 2), desc
        with self.torch.inference_mode():
            tensor = self.torch.from_numpy(gray).to(self.device, dtype=self.torch.float32)[None, None] / 255
            return self.superpoint.extract(tensor, resize=None)

    def pairs(self, query, target, method):
        if method == 'lightglue':
            with self.torch.inference_mode():
                match = self.lightglue({'image0': query, 'image1': target})['matches'][0]
                return (query['keypoints'][0, match[:, 0]].cpu().numpy(), target['keypoints'][0, match[:, 1]].cpu().numpy())
        first, second = query[1], target[1]
        if first is None or second is None or min(len(first), len(second)) < 2:
            return np.empty((0, 2)), np.empty((0, 2))
        matcher = cv2.BFMatcher(cv2.NORM_L2 if method == 'sift' else cv2.NORM_HAMMING)
        forward = matcher.knnMatch(first, second, k=2)
        reverse = {match.queryIdx: match.trainIdx for match in matcher.match(second, first)}
        keep = [best for best, alt in forward if best.distance < .80 * alt.distance and reverse.get(best.trainIdx) == best.queryIdx]
        return query[0][[m.queryIdx for m in keep]], target[0][[m.trainIdx for m in keep]]


def template_set(output, matchers):
    import pg_colori_sim as color
    import pg_fluoro_sim as fluo
    folder = output / 'templates'
    folder.mkdir()
    templates = {}
    for index, group in enumerate(HOLDOUT):
        for size in (10, 15):
            name = f'reference_{group}_{size}'
            # 仅参考帧有设计孔中心；独立种子/均匀反应，不能用测试图的浓度梯度作编号密码。
            params = dict(grid_size=size, image_size=1200, seed=904021+size*41+index, panel_fill=.62,
                          well_fill=.42, rotation_deg=0., perspective_strength=0., radial_k1=0.)
            if group == 'fluo_new':
                config = fluo.FluorescenceConfig(**(fluo.DIFFICULTY_PRESETS['ideal'] | params), intensity_pattern='uniform',
                                                 intensity_min=.35, intensity_max=.35, intensity_jitter=0.)
                image, truth = fluo.render_fluorescence_scene(config)
            else:
                config = color.ColorimetricConfig(**(color.DIFFICULTY_PRESETS['ideal'] | params), concentration_pattern='uniform',
                    concentration_min_uM=300., concentration_max_uM=300., layout='bare' if group=='color_bare' else 'masked',
                    mask_transmittance=.20 if group=='color_translucent' else .002)
                image, truth = color.render_colorimetric_scene(config)
            ok, encoded = cv2.imencode('.png', image)
            if not ok:
                raise ValueError('参考图存档失败')
            path = folder / (name + '.png')
            path.write_bytes(encoded.tobytes())
            labels = np.array(truth['points'])
            (folder / (name + '.json')).write_text(json.dumps(dict(seed=config.seed, group=group, size=size,
                image_sha256=sha256_path(path), xy=labels.tolist()), allow_nan=False), encoding='utf-8')
            gray, to_full = small_gray(image)
            templates[group, size] = dict(name=name, gray=gray, points=project(labels, np.linalg.inv(to_full)),
                features={key: matchers.features(gray, key) for key in ('sift', 'orb', 'lightglue')})
    return templates


def ecc_homography(reference, target):
    """同画幅、恒等初值的直接法。逐层传播完整单应，目标到模板的反向采样与模板点前向投影相容。"""
    if reference.shape != target.shape:
        return None, '参考图画幅不匹配'
    height, width = reference.shape
    transform = np.eye(3)
    for scale in (.125, .25, .5, 1.):
        dims = (round(width * scale), round(height * scale))
        sx, sy = dims[0] / width, dims[1] / height
        shrink = np.array([[sx, 0, (sx-1)/2], [0, sy, (sy-1)/2], [0, 0, 1.]])
        coarse = (shrink @ transform @ np.linalg.inv(shrink)).astype(np.float32)
        try:
            correlation, coarse = cv2.findTransformECC(cv2.resize(reference, dims, interpolation=cv2.INTER_AREA),
                cv2.resize(target, dims, interpolation=cv2.INTER_AREA), coarse, cv2.MOTION_HOMOGRAPHY,
                (cv2.TERM_CRITERIA_COUNT | cv2.TERM_CRITERIA_EPS, 60, 1e-5), None, 5)
        except cv2.error as exc:
            if exc.code != cv2.Error.StsNoConv:  # 数值不收敛是有效实验结果，其他错误不能吞掉。
                raise
            return None, 'ECC 未收敛'
        transform = np.linalg.inv(shrink) @ coarse @ shrink
    return (transform, f'CC={correlation:.3f}') if correlation >= .50 else (None, 'ECC 相关不足')


def registered(template, gray, inverse, method, matchers):
    points = template['points']
    size = round(np.sqrt(len(points)))
    matches = inliers = None
    if method == 'ecc':
        matrix, note = ecc_homography(template['gray'], gray)
        if matrix is None:
            return refused(note)
    else:
        src, dst = matchers.pairs(template['features'][method], matchers.features(gray, method), method)
        # 原点属于参考芯片及一圈边框；测试图没有任何人工角点。相同位置的多方向描述子不得重复计票。
        margin = 1.5 * original_pitch(points, size)
        keep = ((src >= points.min(axis=0)-margin) & (src <= points.max(axis=0)+margin)).all(axis=1)
        src, dst = src[keep], dst[keep]
        _, indices = np.unique(np.round(src*4).astype(int), axis=0, return_index=True)
        src, dst = src[indices], dst[indices]
        matches = len(src)
        if matches < 8:
            return refused('有效匹配不足 8', matches=matches)
        matrix, mask = cv2.findHomography(src, dst, cv2.RANSAC, 3., maxIters=10000, confidence=.999)
        inliers = int(mask.sum()) if mask is not None else 0
        if matrix is None or inliers < 8:
            return refused('单应内点不足 8', matches=matches, inliers=inliers)
        footprint = cv2.contourArea(cv2.convexHull(src[mask.ravel().astype(bool)].astype(np.float32)))
        if footprint < .12 * cv2.contourArea(cv2.convexHull(points.astype(np.float32))):
            return refused('内点只覆盖参考图局部', matches=matches, inliers=inliers)
        note = '模板点投影，不是逐孔观测'
    grid = project(points, matrix).reshape(size, size, 2)
    across, down = np.diff(grid, axis=1), np.diff(grid, axis=0)
    lengths = np.r_[np.linalg.norm(across, axis=2).ravel(), np.linalg.norm(down, axis=2).ravel()]
    # 重复点阵可能内点很多却错行、镜像或转了 90°。可排除明显不合拍摄方向的解，不能靠真值修正整行平移。
    if (not np.isfinite(grid).all() or np.any(grid < 0) or np.any(grid >= gray.shape[::-1]) or lengths.min() < 3
            or lengths.max() > 3*lengths.min() or np.mean(across[:,:,0]) <= np.mean(np.abs(across[:,:,1]))
            or np.mean(down[:,:,1]) <= np.mean(np.abs(down[:,:,0]))):
        return refused('投影越界或编号方向不符', note, matches, inliers)
    return Reading(Prediction(project(grid.reshape(-1, 2), inverse)), note, matches, inliers)


def csv_rows(path):
    with path.open(encoding='utf-8-sig', newline='') as handle:
        return list(csv.DictReader(handle))


def save_csv(path, records):
    with path.open('w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=records[0].keys())
        writer.writeheader()
        writer.writerows(records)


def inputs(ref, previous, selected):
    known = {case.name: case for case in load_cases(ref)}
    original = {(row['case'], row['method']): row for row in csv_rows(previous/'metrics.csv') if row['protocol']=='auto'}
    archived = {}
    with (previous/'coordinates.jsonl').open(encoding='utf-8') as handle:
        for line in handle:
            item = json.loads(line)
            if item['protocol'] == 'auto' and item['method'] in ('pg', 'graph'):
                archived[item['case'], item['method']] = item
    cases = []
    for (name, method), meta in original.items():
        if method != 'pg' or (selected and name not in selected):
            continue
        group, size = meta['group'], int(meta['size'])
        if group in HOLDOUT or group == 'color_demo':
            folder = previous/'inputs' if group in HOLDOUT else ref/'examples/color'
            basename = name if group in HOLDOUT else name.removeprefix('color_demo_')
            truth = json.loads((folder/(basename+('.json' if group in HOLDOUT else '_truth.json'))).read_text('utf-8'))
            case = Case(name, group, folder/(basename+'.jpg'), size, np.array(truth['points']), meta['sha256'])
        else:
            case = known[name]
        if case.group != group or case.size != size or sha256_path(case.path) != meta['sha256']:
            raise ValueError(f'图片/规格与对照运行不符：{name}')
        case.sha = meta['sha256']
        cases.append(case)
    if (selected and {case.name for case in cases} != set(selected)) or (not selected and len(cases) != 80):
        raise ValueError('需要完整的双模态基线运行或确实存在的 --case')
    return cases, original, archived


def controls(matchers, templates):
    """圆标定板和标定模板已知变换用来检测接线/坐标错误；不与真正的芯片成绩合并。"""
    rows = []
    for size in (10, 15):
        pitch = 52
        side = (size+1)*pitch
        axis = np.arange(1, size+1)*pitch
        truth = np.stack(np.meshgrid(axis, axis), axis=-1).reshape(-1, 2)
        for background in (0, 255):
            image = np.full((side, side), background, dtype=np.uint8)
            for col, row in truth:
                cv2.circle(image, (col,row), 10, 255-background, -1, cv2.LINE_AA)
            for method in ('circles','pyphe'):
                pred = single_grid(image, size, method, np.eye(3), matchers.pyphe).prediction
                p95 = score(pred, truth, size)[1]
                rows.append(dict(control=f'circle_{size}_bg{background}', method=method, p95_pitch=p95,
                                 passed=int(p95 is not None and p95 <= LIMIT), reason=pred.reason))
    # 使用不同于测试集的参考帧进行已知刚性/透视扰动，不用测试图挑最佳匹配或重排编号。
    template = templates['color_opaque', 15]
    image = template['gray']
    dims = np.array(image.shape[::-1], np.float32)-1
    source = np.float32([[0,0],[1,0],[1,1],[0,1]])*dims
    target = np.float32([[.015,.018],[.988,.031],[.974,.981],[.023,.964]])*dims
    matrix = cv2.getPerspectiveTransform(source, target)
    # 另加有独特纹理的已知单应对照：区分 API/坐标接错，和规则点阵本就缺乏可区分特征。
    # 它只是阳性对照，既不混入样品统计，也不把这些人工标志贴到待测芯片上。
    rng = np.random.default_rng(619)
    textured = cv2.GaussianBlur(rng.integers(0,256,size=image.shape,dtype=np.uint8), (5,5), 1.2)
    for _ in range(370):
        center = tuple(map(int, rng.integers(20, min(image.shape)-20, size=2)))
        cv2.circle(textured, center, int(rng.integers(4,15)), int(rng.integers(10,245)), -1, cv2.LINE_AA)
    rich = dict(gray=textured, points=template['points'], features={key:matchers.features(textured,key) for key in ('sift','orb','lightglue')})
    for name, plate in (('independent_template_warp',template), ('nonperiodic_texture_warp',rich)):
        warped = cv2.warpPerspective(plate['gray'],matrix,image.shape[::-1])
        for method in PAIR:
            pred = registered(plate,warped,np.eye(3),method,matchers).prediction
            p95 = score(pred,project(plate['points'],matrix),15)[1]
            rows.append(dict(control=name,method=method,p95_pitch=p95,
                             passed=int(p95 is not None and p95<=LIMIT),reason=pred.reason))
    return rows


def report(output, records, cases):
    summaries = []
    for protocol, method in dict.fromkeys((r['protocol'], r['method']) for r in records):
        for group in [*MULTIMODAL_GROUPS, 'holdout_48']:
            subset = [r for r in records if r['protocol']==protocol and r['method']==method and
                      (r['group'] in HOLDOUT if group=='holdout_48' else r['group']==group)]
            if not subset:
                continue
            available = sum(r['output'] for r in subset)
            valid = sum(r['passed'] for r in subset) if subset[0]['passed'] is not None else None
            values = [r['p95_pitch'] for r in subset if r['p95_pitch'] is not None]
            summaries.append(dict(protocol=protocol, method=method, group=group, total=len(subset), outputs=available,
                passed=valid, returned_above_limit=None if valid is None else available-valid, no_output=len(subset)-available,
                median_p95_given_output=np.median(values) if values else None))
    save_csv(output/'metrics.csv', records)
    save_csv(output/'summary.csv', summaries)
    options = ''.join(f'<option value="{html.escape(case.name)}" data-real="{int(case.truth is None)}">{html.escape(case.name)}</option>' for case in cases)
    table = ''.join(f'<tr><td>{r["protocol"]}</td><td>{r["method"]}</td><td>{r["passed"]}/{r["total"]}</td><td>{r["no_output"]}</td>'
                    f'<td>{r["returned_above_limit"]}</td></tr>' for r in summaries if r['group']=='holdout_48')
    default_case = next((c.name for c in cases if c.name=='color_bare_typical_n15_s7321'), cases[0].name)
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>FluoColor · 外部基准</title>
<style>body{font:16px/1.5 system-ui;color:#142c42;max-width:980px;margin:24px auto;padding:0 1em;background:#f7f9fb}img{width:100%;height:auto}th,td{padding:4px 14px;text-align:left;border-bottom:1px solid #ced8df}select,button{font:inherit;padding:5px}a{color:#245681}</style>
<h1>公开算法与芯片定位</h1><label>图像 <select id="pick">OPTIONS</select></label> <button id="prev">上一张</button> <button id="next">下一张</button>
<h2>单图找孔</h2><p>上排为项目结果；下排为两种外部实现，均先自动找面板并矫正。外部算法直接吃整图、不矫正的对照见 CSV。</p><img id="single" alt="单图布网对照">
<section id="pair"><h2>给定一张已标孔的独立参考图</h2><p>这些方法输出两图配准，不是独立逐孔观测；所有模板法共享参考帧。切勿和单图成绩混为一次排名。</p><img id="registered" alt="独立模板配准对照"></section><p id="na" hidden>实拍没有本次独立编号的参考帧；不适用，而不是失败。</p>
<h2>留出仿真：按输入协议比较</h2><p>同名孔误差 P95 ≤ 0.20 孔距为达标；没有输出也计在分母。拟合内点多不保证物理编号正确。</p><table><thead><tr><th>输入协议</th><th>算法</th><th>达标图数</th><th>未输出</th><th>有输出但超线</th></tr></thead><tbody>TABLE</tbody></table>
<p><a href="summary.csv">汇总</a> · <a href="metrics.csv">逐图记录</a> · <a href="controls.csv">独立对照</a> · <a href="coordinates.jsonl">原图坐标</a></p>
<script>const sel=document.querySelector('#pick');sel.value='DEFAULT';function show(){let k=sel.value,real=sel.selectedOptions[0].dataset.real==='1';single.src='images/'+k+'_single.jpg';pair.hidden=real;na.hidden=!real;if(!real)registered.src='images/'+k+'_template.jpg';}sel.onchange=show;function step(n){sel.selectedIndex=(sel.selectedIndex+n+sel.length)%sel.length;show()}prev.onclick=()=>step(-1);next.onclick=()=>step(1);show()</script></html>'''
    (output/'index.html').write_text(page.replace('OPTIONS',options).replace('TABLE',table).replace('DEFAULT',default_case),encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference-dir', type=Path, required=True)
    parser.add_argument('--input-run', type=Path, required=True, help='第 10 节 --suite multimodal 的完整输出')
    parser.add_argument('--dependencies', type=Path, required=True, help='含 official/、python/、torch/hub/checkpoints/ 的固定依赖目录')
    parser.add_argument('--output', type=Path, required=True, help='新的或空的目录')
    parser.add_argument('--font', default='C:/Windows/Fonts/msyh.ttc')
    parser.add_argument('--case', action='append', help='限选复查；未指定时执行完整研究')
    args = parser.parse_args()
    ref, previous, output = args.reference_dir.resolve(), args.input_run.resolve(), args.output.resolve()
    pinned = {'pg_grid.py': REFERENCE_SHA, 'pg_colori_sim.py': 'e8ed544739ce7a64e80186337f07c6cba4350fd738045b8b37cd10e8e783353d',
              'pg_fluoro_sim.py': '8629c589a884867b970358ad3134a49c025c153b007455b044628231f81c7cec'}
    if any(hashlib.sha256((ref/name).read_bytes().replace(b'\r\n',b'\n')).hexdigest()!=sha for name,sha in pinned.items()):
        parser.error('参考几何或成像生成器版本不符')
    if output.exists() and any(output.iterdir()):
        parser.error('输出目录非空；不覆盖以前的研究结果')
    sys.path.insert(0,str(ref))
    reference = importlib.import_module('pg_grid')
    cv2.setNumThreads(1)
    cv2.setRNGSeed(37)
    font = ImageFont.truetype(args.font, 17)
    cases, original, archive = inputs(ref, previous, args.case)
    engine = Matchers(args.dependencies.resolve())
    (output/'images').mkdir(parents=True, exist_ok=True)
    templates = template_set(output, engine)
    save_csv(output/'controls.csv', controls(engine, templates))
    versions = {name:importlib.metadata.version(name) for name in ('pyphe','kornia','scikit-image','torch','numpy','scipy')}
    info = dict(opencv=cv2.__version__, packages=versions, device=engine.device,
            hardware=engine.torch.cuda.get_device_name() if engine.device=='cuda' else 'CPU', python=sys.version,
            lightglue_commit='5a650c444718caffc2669dc0094cde908f42dd73', lightglue_source_digest=SOURCE_DIGEST, weights=WEIGHTS,
            prior_metrics_sha256=sha256_path(previous/'metrics.csv'), prior_coordinates_sha256=sha256_path(previous/'coordinates.jsonl'),
            code_sha256=sha256_path(Path(__file__)), max_side=WIDTH, cutoff_pitch=LIMIT, seed=37)
    (output/'run.json').write_text(json.dumps(info, indent=2), encoding='utf-8')
    records = []
    with (output/'coordinates.jsonl').open('w',encoding='utf-8') as stream:
        for case in cases:
            image = reference.read_image_unicode(case.path)
            gray, inverse = small_gray(image)
            found = {}
            # 冻结项目结果只用于显示与同规则复评，不得下传到外部的检测器或两图配准。
            for key in ('pg','graph'):
                item, meta = archive[case.name,key], original[case.name,key]
                if meta['sha256'] != case.sha:
                    raise ValueError('既有对照来自不同图像')
                pred = Prediction(None if item['xy_original'] is None else np.asarray(item['xy_original']),
                    None if item['observed'] is None else np.asarray(item['observed']), reason=meta['failure'], trusted=meta['pg_trusted']=='True')
                found['single_project', key] = Reading(pred, '已冻结的同图坐标'), None
            start = perf_counter()
            region = reference.detect_chip_region(image)
            rect, _, rect_inverse = reference.rectify_chip(image, region, reference._default_rectified_size(case.size))
            rect_gray = cv2.cvtColor(rect, cv2.COLOR_BGR2GRAY)
            rect_ms = (perf_counter()-start)*1000
            for protocol, view, transform in (('single_raw',gray,inverse),('single_rectified',rect_gray,rect_inverse)):
                for method in ('circles','pyphe'):
                    start = perf_counter()
                    if protocol=='single_rectified' and region.method=='fallback_center':
                        result = refused('没有自动面板外框',region.method)
                    else:
                        result = single_grid(view,case.size,method,transform,engine.pyphe)
                    found[protocol,method] = result,(perf_counter()-start)*1000+(rect_ms if protocol=='single_rectified' else 0)
            template_id = ''
            if case.group != 'real_original':
                # 版型/模态是模板使用者必须事先知道的额外信息。实拍没有独立实物模板，不算失败或成功。
                family = {'color_demo':'color_bare','fluo_challenge':'fluo_new'}.get(case.group,case.group)
                template = templates[family,case.size]
                template_id = template['name']
                if sha256_path(output/'templates'/(template_id+'.png'))==case.sha:
                    raise ValueError('基准中禁止同图作自己的参考')
                for method in PAIR:
                    start = perf_counter()
                    result = registered(template,gray,inverse,method,engine)
                    found['known_template',method] = result,(perf_counter()-start)*1000
            overlays, summaries = {}, {}
            for (protocol, method), (result, elapsed) in found.items():
                pred = result.prediction
                median, p95, hit = score(pred,case.truth,case.size)
                row = dict(case=case.name,group=case.group,size=case.size,protocol=protocol,method=method,sha256=case.sha,
                    template=template_id if protocol=='known_template' else '', panel=region.method if protocol=='single_rectified' else '',
                    output=int(pred.points is not None),passed=None if case.truth is None else int(p95 is not None and p95<=LIMIT),
                    median_pitch=median,p95_pitch=p95,hit_fraction=hit,matches=result.matches,inliers=result.inliers,
                    failure=pred.reason,details=result.details,elapsed_ms=None if elapsed is None else round(elapsed,2))
                records.append(row)
                stream.write(json.dumps(dict(case=case.name,protocol=protocol,method=method,
                    xy_original=None if pred.points is None else pred.points.round(5).tolist()),allow_nan=False)+'\n')
                if protocol!='single_raw':
                    overlays['auto',method]=(pred,);summaries[method]=row
            make_sheet(image,case,overlays,region.points,summaries,output/'images'/(case.name+'_single.jpg'),font,DIRECT,'单张原图；外部方法共用自动矫正')
            if template_id:
                make_sheet(image,case,overlays,region.points,summaries,output/'images'/(case.name+'_template.jpg'),font,PAIR,'额外输入：独立同版型、已标孔的模板')
            print(case.name,flush=True)
    report(output,records,cases)


if __name__ == '__main__':
    main()
