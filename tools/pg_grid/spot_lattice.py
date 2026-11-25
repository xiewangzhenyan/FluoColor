"""双模态研究定位器：原图稳定区域 → 带整数行列差的邻接图 → 抗离群投影。

只接受照片与已声明的方阵规格。不使用芯片框、参考图、模态名或真值；凹凸亮度及颜色
只用于找几何证据，绝不能把这里的增强图、颜色差或模型补点拿来代替原始光度。
"""

from dataclasses import dataclass

import cv2
import numpy as np
from scipy.ndimage import gaussian_filter
from scipy.spatial import cKDTree


@dataclass
class SpotGrid:
    points: np.ndarray | None
    observed: np.ndarray | None
    candidates: np.ndarray
    reason: str


def _region_centers(image, allow_weak=False):
    """亮、暗极值均检测，再加一幅通道间色差响应以保留灰度接近的显色孔。"""
    height, width = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    chroma = image.max(axis=2) - image.min(axis=2)
    detector = cv2.MSER_create(3, 6, max(16, round(min(width, height) ** 2 * 0.004)), 0.45, 0.2)
    candidates = []
    integral = cv2.integral(image, sdepth=cv2.CV_64F)

    def local_rgb(center_x, center_y, widths, heights, fraction):
        """同一候选比较内核与较大的外窗；积分图让弱信号/颜色证据不必逐区域重复读像素。"""
        left = np.maximum(0, np.rint(center_x - fraction * widths).astype(int))
        right = np.minimum(width, np.rint(center_x + fraction * widths).astype(int) + 1)
        top = np.maximum(0, np.rint(center_y - fraction * heights).astype(int))
        bottom = np.minimum(height, np.rint(center_y + fraction * heights).astype(int) + 1)
        total = integral[bottom, right] - integral[top, right] - integral[bottom, left] + integral[top, left]
        return total / ((right - left) * (bottom - top))[:, None]

    for channel in (gray, chroma):
        if np.ptp(channel) < 4:
            continue
        # 对暗通道作单调压缩/拉伸，弱发光孔也有机会穿过 MSER 的灰阶稳定区间。
        levels = np.arange(256, dtype=float)
        lut = np.rint(255 * np.arcsinh(levels / 20) / np.arcsinh(255 / 20)).astype(np.uint8)
        regions, boxes = detector.detectRegions(cv2.LUT(channel, lut))
        if not len(boxes):
            continue
        area = np.fromiter(map(len, regions), dtype=float, count=len(boxes))
        left, top, widths, heights = boxes.T
        short = np.minimum(widths, heights)
        aspect = short / np.maximum(widths, heights)
        fill = area / (widths * heights)
        center_x, center_y = left + (widths - 1) / 2, top + (heights - 1) / 2
        inner = local_rgb(center_x, center_y, widths, heights, 0.23)
        outer = local_rgb(center_x, center_y, widths, heights, 0.77)
        contrast = np.abs(inner - outer).max(axis=1)
        # 单通道约一两个数码灰阶的细小色噪也会生成稳定极值；大而连贯的弱斑点保留较低阈值。
        # 门槛只限制定位投票，绝不表示对应样品不可检出、阴性或浓度为零。
        visible = (contrast >= 2.8) | ((short >= 0.009 * min(width, height)) & (contrast >= 1.0))
        valid = (short >= 3) & (aspect > 0.52) & (fill > 0.49)
        if not allow_weak:
            valid &= visible
        quality = aspect * fill if allow_weak else aspect * fill * (0.65 + 0.35 * np.minimum(1.0, contrast / 10))
        for index in np.flatnonzero(valid):
            candidates.append((center_x[index], center_y[index], short[index], quality[index]))
    if not candidates:
        return np.empty((0, 4))
    candidates = np.array(candidates)
    # 一个斑点在多个阈值/通道可能出现数十次。相近中心只保留一个投票，强斑点不能
    # 靠嵌套轮廓的数量模拟出一整行；小框优先级不高于完整、方正的稳定区。
    tree = cKDTree(candidates[:, :2])
    seen = np.zeros(len(candidates), dtype=bool)
    merged = []
    for index in np.argsort(-candidates[:, 3], kind='stable'):
        if seen[index]:
            continue
        neighbors = tree.query_ball_point(candidates[index, :2], max(2.2, 0.32 * candidates[index, 2]))
        cluster = candidates[np.asarray(neighbors)[~seen[neighbors]]]
        seen[neighbors] = True
        merged.append(np.median(cluster, axis=0))
    return np.array(merged)


def _axis_proposals(vectors, lengths, weights, axis, pitch_min, pitch_max):
    """在邻孔位移上投票求水平/垂直基向量。棋盘、隔行点亮会增强二倍孔距峰，故同时考察半周期。"""
    oriented = vectors * np.where(vectors[:, axis:axis+1] >= 0, 1, -1)
    angles = np.degrees(np.arctan2(oriented[:, 1-axis], oriented[:, axis]))
    good = (np.abs(angles) < 32) & (lengths >= pitch_min) & (lengths <= pitch_max * 1.5)
    if good.sum() < 8:
        return []
    angles, norms, oriented, strength = angles[good], lengths[good], oriented[good], weights[good]
    bins = np.arange(np.log(pitch_min) - 0.04, np.log(pitch_max * 1.5) + 0.09, 0.065)
    degrees = np.arange(-34, 35, 3)
    hist = np.histogram2d(np.log(norms), angles, bins=(bins, degrees), weights=strength)[0]
    hist = gaussian_filter(hist, (0.9, 1.0))
    proposals = []
    for _ in range(3):
        size_bin, angle_bin = np.unravel_index(np.argmax(hist), hist.shape)
        if hist[size_bin, angle_bin] <= 0:
            break
        log_step = (bins[size_bin] + bins[size_bin + 1]) / 2
        angle = (degrees[angle_bin] + degrees[angle_bin + 1]) / 2
        near = (np.abs(np.log(norms) - log_step) < 0.11) & (np.abs(angles - angle) < 6)
        if near.sum() >= 5:
            basis = np.median(oriented[near], axis=0)
            for step in (basis, basis / 2):
                if pitch_min <= np.linalg.norm(step) <= pitch_max and not any(
                        np.linalg.norm(step - other) < 0.11 * np.linalg.norm(other) for other in proposals):
                    proposals.append(step)
        hist[max(0, size_bin-2):size_bin+3, max(0, angle_bin-2):angle_bin+3] = 0
    return proposals


def _numbered_components(points, pairs, steps, residual, size):
    """按局部孔距而非全图坐标取整；相邻边可传播弯曲、轻度透视下的相对孔号。"""
    graph = [[] for _ in points]
    for pair, step, err in zip(pairs, steps, residual):
        first, second = pair
        graph[first].append((err, second, step))
        graph[second].append((err, first, -step))
    labeled = np.zeros(len(points), dtype=bool)
    labels = np.zeros((len(points), 2), dtype=int)
    for anchor in np.argsort([-len(edges) for edges in graph], kind='stable'):
        if labeled[anchor] or len(graph[anchor]) < 2:
            continue
        queue = [anchor]
        labeled[anchor] = True
        for current in queue:
            for _, target, diff in sorted(graph[current], key=lambda edge: edge[0]):
                if not labeled[target]:
                    labeled[target] = True
                    labels[target] = labels[current] + diff
                    queue.append(target)
        if len(queue) < max(12, size * size * 0.17):
            continue
        group = labels[queue] - labels[queue].min(axis=0)
        counts = group.max(axis=0) + 1
        # 正方形无方向标记时只支持照片上方/左侧的编号约定；若外行/外列全失，
        # 单张图可能有两个等价原点。必须有覆盖边界的唯一窗口才生成物理编号。
        if np.any(counts < size) or np.any(counts > 4 * size):
            continue
        occupancy = np.zeros((counts[1], counts[0]), dtype=np.uint8)
        occupancy[group[:, 1], group[:, 0]] = 1
        integral = cv2.integral(occupancy)
        windows = integral[size:, size:] - integral[:-size, size:] - integral[size:, :-size] + integral[:-size, :-size]
        row, col = np.unravel_index(np.argmax(windows), windows.shape)
        if windows.size > 1 and np.count_nonzero(windows == windows[row, col]) > 1:
            continue
        local = group - [col, row]
        inside = ((local >= 0) & (local < size)).all(axis=1)
        yield local[inside], points[np.asarray(queue)[inside]]


def _fit_grid(points, size, width, height):
    if len(points) < size * 3:
        return None
    tree = cKDTree(points[:, :2])
    _, neighbors = tree.query(points[:, :2], k=min(23, len(points)))
    sources = np.repeat(np.arange(len(points)), neighbors.shape[1] - 1)
    targets = neighbors[:, 1:].ravel()
    pairs = np.unique(np.sort(np.column_stack([sources, targets]), axis=1), axis=0)
    sources, targets = pairs.T
    vectors = points[targets, :2] - points[sources, :2]
    lengths = np.linalg.norm(vectors, axis=1)
    widths = points[:, 2]
    shapes = widths[sources] / widths[targets]
    usable = (shapes > 0.48) & (shapes < 2.08) & (lengths > 1.10 * np.minimum(widths[sources], widths[targets]))
    pairs, vectors, lengths = pairs[usable], vectors[usable], lengths[usable]
    scales = np.sqrt(widths[pairs[:, 0]] * widths[pairs[:, 1]])
    weights = np.minimum(1., 2.5 * scales / np.maximum(1., lengths)) ** 2
    lower = max(4., min(width, height) * 0.10 / (size - 1))
    upper = min(width, height) * 1.05 / (size - 1)
    horizontal, vertical = [_axis_proposals(vectors, lengths, weights, axis, lower, upper) for axis in (0, 1)]
    ideal = np.stack(np.meshgrid(np.arange(size), np.arange(size)), axis=-1).reshape(-1, 2).astype(np.float32)
    best = None
    for across in horizontal:
        for down in vertical:
            pitch = (np.linalg.norm(across) + np.linalg.norm(down)) / 2
            if not 0.74 < np.linalg.norm(across) / np.linalg.norm(down) < 1.35:
                continue
            matrix = np.stack([across, down])
            if np.linalg.det(matrix) < 0.75 * pitch ** 2:
                continue
            local = vectors @ np.linalg.inv(matrix)
            steps = np.rint(local).astype(int)
            distances = np.linalg.norm(local - steps, axis=1)
            valid = (distances < 0.22) & (np.abs(steps).max(axis=1) <= 2) & np.any(steps != 0, axis=1)
            for slot, candidate in _numbered_components(points[:, :2], pairs[valid], steps[valid], distances[valid], size):
                # 单应拟合只用到候选间的整数约束。投影外的强噪点不应把物理孔号拉歪。
                homography, inliers = cv2.findHomography(slot.astype(np.float32), candidate, cv2.RANSAC, 0.22 * pitch,
                                                           maxIters=800, confidence=0.995)
                if homography is None or inliers is None or inliers.sum() < size * 2:
                    continue
                modeled = cv2.perspectiveTransform(ideal[None], homography)[0]
                gap, match = tree.query(modeled)
                observed = (gap < 0.25 * pitch) & (points[match, 2] > 0.10 * pitch) & (points[match, 2] < 0.90 * pitch)
                visible = observed.reshape(size, size)
                if (observed.mean() < 0.22 or min(visible[0].sum(), visible[-1].sum(), visible[:, 0].sum(), visible[:, -1].sum()) < 2
                        or len(np.unique(match[observed])) != observed.sum()):
                    continue
                # 固定孔号的最外行/列应有直接证据；打分还惩罚恰在网格外接续的整行候选，防止错一格。
                ends = np.arange(size, dtype=np.float32)
                outside = np.vstack([np.column_stack([ends, np.full(size, edge)]) for edge in (-1, size)] +
                                    [np.column_stack([np.full(size, edge), ends]) for edge in (-1, size)]).astype(np.float32)
                ring_gap, _ = tree.query(cv2.perspectiveTransform(outside[None], homography)[0])
                merit = (sum(observed) - 0.8 * sum(ring_gap < 0.23 * pitch)) / len(ideal) - 0.22 * np.median(gap[observed]) / pitch
                if best is None or merit > best[0]:
                    adjusted = modeled.copy()
                    adjusted[observed] = points[match[observed], :2]
                    best = merit, adjusted, observed
    return None if best is None else best[1:]


def evidence_score(grid, spots, size):
    """用同一组高对比候选复核任一网格，逐孔对齐与四边有无支撑分别投票；不使用真实孔号。"""
    if grid is None or not len(spots):
        return 0.0
    array = grid.reshape(size, size, 2)
    pitch = np.median(np.r_[np.linalg.norm(np.diff(array, axis=0), axis=2).ravel(),
                            np.linalg.norm(np.diff(array, axis=1), axis=2).ravel()])
    if not np.isfinite(pitch) or pitch < 1:
        return 0.0
    plausible = spots[(spots[:, 2] > 0.11 * pitch) & (spots[:, 2] < 0.90 * pitch), :2]
    if not len(plausible):
        return 0.0
    tree = cKDTree(plausible)
    distance, _ = tree.query(grid)
    unit = np.clip(1 - (distance / (0.32 * pitch)) ** 2, 0., 1.).reshape(size, size)
    borders = np.array([unit[0].mean(), unit[-1].mean(), unit[:, 0].mean(), unit[:, -1].mean()])
    # 内侧近乎整行都亮且外侧仍有一整行候选时，可能错编号一格。用真实外沿延拓一步，不用浓度亮度排行。
    ring = np.vstack([2*array[0]-array[1], 2*array[-1]-array[-2], 2*array[:, 0]-array[:, 1], 2*array[:, -1]-array[:, -2]])
    ring_hits = (tree.query(ring)[0] < 0.22 * pitch).reshape(4, size).mean(axis=1)
    return float(0.75 * unit.mean() + 0.25 * borders.mean() - 0.12 * ring_hits.max())


def locate_spots(image, size):
    """以最长边 1000 px 的图做候选检测，最后按 OpenCV 的像素中心关系投回原图。"""
    if size < 3 or image.ndim != 3 or image.shape[2] != 3 or min(image.shape[:2]) < size * 2:
        raise ValueError('需要有效的 BGR 原图和规则方阵规格')
    height, width = image.shape[:2]
    scale = min(1., 1000 / max(height, width))
    working = cv2.resize(image, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA)
    scales = np.array([working.shape[1] / width, working.shape[0] / height])
    # 点云候选很多时先淘汰高纹理背景的小碎片，给图搜索设容量上限，不能用无限算力换准确率。
    def bounded(spots):
        return spots if len(spots) <= 2200 else spots[np.argsort(-spots[:, 2] * spots[:, 3], kind='stable')[:2200]]

    spots = bounded(_region_centers(working))
    fitted = _fit_grid(spots, size, working.shape[1], working.shape[0])
    if fitted is None or fitted[1].mean() < 0.85:
        # 弱荧光可能只剩高阈值区域的半块阵列。另用未做对比度筛除的候选独立组网，
        # 但不允许更多弱噪点自行给自己加分：两种编号始终用第一遍强候选作同尺度复核。
        relaxed = _fit_grid(bounded(_region_centers(working, allow_weak=True)), size, working.shape[1], working.shape[0])
        if relaxed is not None and (fitted is None or evidence_score(relaxed[0], spots, size) > evidence_score(fitted[0], spots, size)):
            fitted = relaxed
    raw_candidates = np.column_stack([(spots[:, :2] + 0.5) / scales - 0.5, spots[:, 2] / scales.mean()])
    if fitted is None:
        return SpotGrid(None, None, raw_candidates, '点云未形成覆盖四边的可编号晶格')
    centers, observed = fitted
    original = (centers + 0.5) / scales - 0.5
    if np.any(original < 0) or np.any(original >= [width, height]):
        return SpotGrid(None, None, raw_candidates, '推算孔位越过原图边界')
    if evidence_score(original, raw_candidates, size) < 0.24:
        return SpotGrid(None, None, raw_candidates, '稳定候选缺少独立支撑')
    return SpotGrid(original, observed, raw_candidates, '')
