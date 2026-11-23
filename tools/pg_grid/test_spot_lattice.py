"""确定性几何契约：专门验证编号、缺孔和颜色信息，不依赖外部仿真器或 Android。"""

import unittest
from unittest.mock import Mock

import cv2
import numpy as np

from compare_locators import Prediction, candidate_grid, choose_grid, score
from classical_trial import density_grid, locate_log, m3g_centers
from spot_lattice import locate_spots


def chart(missing=(), high_resolution=False):
    width, height, pitch = (1591, 1327, 87) if high_resolution else (520, 490, 39)
    row, col = np.mgrid[:10, :10]
    points = np.stack([65 + pitch * col, 40 + pitch * row], axis=-1).reshape(-1, 2)
    # 两种 BGR 颜色在 8 位 OpenCV 灰度图中都是 98；仅用灰度的“跨模态”测试无法利用此图。
    image = np.full((height, width, 3), [83, 100, 100], dtype=np.uint8)
    for index, (center_x, center_y) in enumerate(points):
        if index not in missing:
            radius = pitch // 5
            cv2.rectangle(image, (center_x-radius, center_y-radius), (center_x+radius, center_y+radius), (12, 90, 146), -1)
    return image, points


class SpotLatticeTest(unittest.TestCase):
    def test_isoluminant_and_missing_interiors(self):
        missing = [23, 55, 67]
        image, truth = chart(missing)
        self.assertEqual(np.ptp(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)), 0)
        found = locate_spots(image, 10)
        self.assertLess(score(found, truth, 10)[1], 0.08)
        self.assertEqual(int(found.observed.sum()), 97)
        self.assertFalse(found.observed[missing].any())

    def test_original_pixel_coordinates_after_noninteger_resize(self):
        image, truth = chart(high_resolution=True)
        found = locate_spots(image, 10)
        self.assertLess(score(found, truth, 10)[1], 0.08)

    def test_missing_outer_row_and_featureless_frame(self):
        # 缺失整个外行的纯点图不能区分首行暗掉还是末行暗掉，不应编造绝对行号。
        image, _ = chart(range(10))
        self.assertIsNone(locate_spots(image, 10).points)
        self.assertIsNone(locate_spots(np.zeros_like(image), 10).points)

    def test_one_pitch_disagreement(self):
        _, truth = chart()
        first = Prediction(truth, trusted=True, evidence=0.70)
        other = Prediction(truth + [39, 0], evidence=0.71)
        self.assertEqual(score(other, truth, 10), (1.0, 1.0, 0.0))
        self.assertEqual(choose_grid(first, other, 10).selected, 'rejected')
        self.assertEqual(choose_grid(first, Prediction(truth, evidence=0.80), 10).selected, 'graph')
        self.assertEqual(score(first, None, 10), (None, None, None))


class AxisKMeansTest(unittest.TestCase):
    def setUp(self):
        # 此处直接给矫正后的轴对齐候选，隔离“分行列”这一步，不要求开发机另装参考工程。
        self.geometry = Mock(spec=['_estimate_lattice_rotation', '_rotate_points'])
        self.geometry._estimate_lattice_rotation.return_value = 0.0
        self.geometry._rotate_points.side_effect = lambda points, angle, center: points

    def test_row_ids_and_missing_spots(self):
        for size in (10, 15):
            with self.subTest(size=size):
                axis = 30 + np.arange(size) * 24
                truth = np.stack(np.meshgrid(axis, axis), axis=-1).reshape(-1, 2)
                observed = np.ones(size ** 2, dtype=bool)
                observed[[size + 4, 2 * size + 3, 7 * size + 1]] = False
                candidates = np.column_stack((truth[observed], np.ones(observed.sum())))
                prediction = candidate_grid(self.geometry, candidates, size, 400, use_kmeans=True)
                np.testing.assert_allclose(prediction.points, truth, atol=.001)
                np.testing.assert_array_equal(prediction.observed, observed)

    def test_no_candidates_or_too_few_distinct_rows(self):
        # 无法形成 10 个不同的行中心时必须拒绝；即使如此，带抖动的缺行仍可能被固定 K 错分。
        axis = np.arange(10) * 25 + 30
        points = np.stack(np.meshgrid(axis, axis[:-1]), axis=-1).reshape(-1, 2)
        for candidates in ([], np.column_stack((points, np.ones(len(points))))):
            self.assertIsNone(candidate_grid(self.geometry, candidates, 10, 400, use_kmeans=True).points)

    def test_density_uses_observations_not_noise_or_missing_rows(self):
        for size in (10, 15):
            axis = 21 + 23*np.arange(size); truth = np.stack(np.meshgrid(axis,axis),axis=-1).reshape(-1,2)
            keep = np.ones(len(truth),bool); keep[[size+2,4*size+4]] = False
            points = np.vstack((truth[keep], [[3,5],[390,311]])); candidates=np.column_stack((points,np.ones(len(points))))
            pred = density_grid(self.geometry,candidates,size,400,23)
            np.testing.assert_array_equal(pred.observed,keep); np.testing.assert_allclose(pred.points,truth,atol=.001)
            masked = candidates[candidates[:,1] != axis[0]]
            self.assertIsNone(density_grid(self.geometry,masked,size,400,23).points)


class ClassicalTrialGeometryTest(unittest.TestCase):
    def test_log_bright_and_dark_centers(self):
        # 正负极性使用相同的几何模型；删除内孔后要保留孔号，但不能把缺孔写成直接观测。
        axis=25+25*np.arange(10); truth=np.stack(np.meshgrid(axis,axis),axis=-1).reshape(-1,2)
        image=np.zeros((275,275,3),np.uint8)
        for index,point in enumerate(truth):
            if index!=37: cv2.circle(image,tuple(point),5,(160,160,160),-1)
        for view in (image,255-image):
            pred=locate_log(view,10)
            self.assertIsNotNone(pred.points); self.assertLess(score(pred,truth,10)[1],.08)
            self.assertFalse(pred.observed[37])
        self.assertIsNone(locate_log(np.zeros_like(image),10).points)

    def test_author_lines_are_not_centers(self):
        # 仅内部隔线，首尾孔都要向外延半个间距；行列数量不对时不能按真值选线。
        lines=[line for pos in range(10,100,10) for line in (f'line {pos},0 {pos},100',f'line 100,{pos} 0,{pos}')]
        result=m3g_centers(lines[::-1],10,100,100); axis=np.arange(5,100,10)
        np.testing.assert_allclose(result.points,np.stack(np.meshgrid(axis,axis),axis=-1).reshape(-1,2)); self.assertIsNone(result.observed)
        self.assertIsNone(m3g_centers(lines[1:],10,100,100).points)
