"""使用冻结的同图语料筛选三种传统定位器；不改历史实验，不把缺少依赖计为算法失败。"""

import argparse
import hashlib
import importlib
import inspect
import importlib.metadata
import json
import re
import sys
from pathlib import Path
from time import perf_counter

import cv2
import numpy as np
from PIL import ImageFont
from scipy.spatial import cKDTree
from sklearn.cluster import DBSCAN

from compare_locators import (LIMIT, MULTIMODAL_GROUPS, REFERENCE_SHA, Case, Prediction, candidate_grid, component_candidates,
                              make_sheet, match_candidates, multimodal_cases, pg_geometry, score, sha256_path, to_original, write_report)
from published_baselines import HOLDOUT, inputs, save_csv
from spot_lattice import _fit_grid, evidence_score


METHODS = {'dbscan': 'DBSCAN 行列聚类', 'log': '多尺度 LoG＋晶格', 'm3g': 'M3G 作者版', 'kmeans': '坐标 K-means（同候选）'}
M3G_SIDE = 384
BLIND_SEEDS = (29033, 51769)
AUTHOR_SORTED_LINES_SHA = '0222d352558e841e6a64ab107b2c8ce6712288a221407210cfe49939160c5773'


def density_axis(values, size, pitch):
    """每个局部簇须有邻居。若存在两个同样合理的连续编号窗口，则拒绝而非凭亮度猜首孔。"""
    labels = DBSCAN(eps=.12*pitch, min_samples=max(3, int(np.ceil(.25*size))), algorithm='kd_tree').fit_predict(values[:, None])
    groups = sorted((np.median(values[labels == label]), label) for label in np.unique(labels) if label >= 0)
    options = []
    for start in range(len(groups)-size+1):
        window = groups[start:start+size]
        centers = np.array([center for center, _ in window]); gaps = np.diff(centers); step = np.median(gaps)
        if .68*pitch <= step <= 1.35*pitch and np.max(np.abs(gaps/step-1)) <= .30:
            options.append((centers, np.isin(labels, [label for _, label in window])))
    return options[0] if len(options) == 1 else None


def density_grid(reference, candidates, size, side, pitch):
    if len(candidates) < 4*size:
        return Prediction(reason='候选不足')
    center = (side/2, side/2)
    angle = reference._estimate_lattice_rotation(candidates[:, :2], side)
    aligned = reference._rotate_points(candidates[:, :2], angle, center)
    axes = [density_axis(values, size, pitch) for values in aligned.T]
    if any(axis is None for axis in axes):
        return Prediction(reason='密度簇不足、孔距不规则或首行列不唯一')
    columns, rows = axes
    grid = np.stack(np.meshgrid(columns[0], rows[0]), axis=-1).reshape(-1, 2)
    valid = candidates[columns[1] & rows[1]]
    if not len(valid):
        return Prediction(reason='没有同时属于行、列密度簇的观测')
    return match_candidates(reference._rotate_points(grid, -angle, center), valid, size)


def log_spots(image, size):
    """尺度归一化 LoG 响应：同图搜索亮/暗和色差，不读模态、原点、外框或测试真值。"""
    height, width = image.shape[:2]
    maximum_pitch = min(width, height)/(size-1)
    scales = np.unique(np.maximum(1.2, maximum_pitch*np.array([.035, .055, .085, .13, .19, .28])))
    candidates = []
    for channel in (cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), image.max(axis=2)-image.min(axis=2)):
        if np.ptp(channel) < 3:
            continue
        plane = channel.astype(np.float32)
        for sigma in scales:
            smooth = cv2.GaussianBlur(plane, (0, 0), sigma)
            curvature = cv2.Laplacian(smooth, cv2.CV_32F, ksize=1)*sigma**2
            # 单个长边也可能有强二阶响应；须同时在两个方向弯曲才作斑点候选。
            second_x = cv2.filter2D(smooth, -1, np.float32([[1,-2,1]]))*sigma**2
            mixed = cv2.Sobel(smooth, cv2.CV_32F, 1, 1, ksize=3, scale=.25*sigma**2)
            compact = second_x*(curvature-second_x)-mixed*mixed > .12*curvature*curvature
            radius = max(2, int(round(sigma)))
            kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2*radius+1, 2*radius+1))
            threshold = max(1.6, 4.0*1.4826*np.median(np.abs(curvature[::4,::4])))
            for sign in (-1, 1):
                response = sign*curvature
                peaks = compact & (response >= threshold) & (response == cv2.dilate(response, kernel))
                vertical, horizontal = np.nonzero(peaks)
                amplitudes = response[vertical, horizontal]
                for index in np.argsort(-amplitudes, kind='stable')[:size*size*3]:
                    candidates.append((horizontal[index], vertical[index], 2*np.sqrt(2)*sigma, amplitudes[index]))
    if not candidates:
        return np.empty((0,4))
    candidates = np.asarray(candidates)
    tree = cKDTree(candidates[:,:2]); taken = np.zeros(len(candidates), dtype=bool); selected = []
    # 同一孔跨尺度、跨通道只能投一票。保留的尺度是该中心归一化响应最强的尺度。
    for index in np.argsort(-candidates[:,3], kind='stable'):
        if not taken[index]:
            selected.append(index)
            taken[tree.query_ball_point(candidates[index,:2], max(2.5,.55*candidates[index,2]))] = True
            if len(selected) >= min(1200, 5*size*size):
                break
    return candidates[selected]


def locate_log(image, size):
    height, width = image.shape[:2]; ratio = min(1., 800/max(width,height))
    work = cv2.resize(image, (round(width*ratio), round(height*ratio)), interpolation=cv2.INTER_AREA)
    points = log_spots(work, size)
    fitted = _fit_grid(points, size, work.shape[1], work.shape[0])
    if fitted is None:
        return Prediction(reason='LoG 候选没有形成覆盖四边的可编号晶格')
    grid, observed = fitted
    if np.any(grid < 0) or np.any(grid >= work.shape[1::-1]) or evidence_score(grid,points,size) < .45:
        return Prediction(reason='LoG 网格越界或局部与四边支撑不足')
    scales = np.array([work.shape[1]/width, work.shape[0]/height])
    return Prediction((grid+.5)/scales-.5, observed)


def m3g_centers(lines, size, width, height):
    """作者的线分隔相邻行列，非孔中心。只接受每轴 N−1 条内部线；不以真值删线、补线或翻转。"""
    axes = [[], []]
    for line in lines:
        fields = line.replace(',', ' ').split()
        if len(fields) != 5 or fields[0] != 'line':
            return Prediction(reason='作者输出分隔线格式无效')
        first, second = np.asarray(fields[1:], dtype=float).reshape(2,2)
        dx, dy = second-first
        if not np.isfinite([*first,*second]).all() or max(abs(dx), abs(dy)) < 1:
            return Prediction(reason='作者输出无效线段')
        axis = 0 if abs(dy)>abs(dx) else 1
        slope = dx/dy if axis==0 else dy/dx
        if abs(slope) > .5:
            return Prediction(reason='分隔线方向不符')
        axes[axis].append(np.array([slope, first[axis]-slope*first[1-axis]]))
    if [len(axis) for axis in axes] != [size-1,size-1]:
        return Prediction(reason=f'分隔线数 {len(axes[0])}×{len(axes[1])} 不符 {size-1}×{size-1}')
    centers = []
    for axis, collection in enumerate(axes):
        mid = (height if axis==0 else width)/2
        ordered = np.array(sorted(collection,key=lambda coeff: coeff[0]*mid+coeff[1]))
        # 上下/左右边缘孔离最近分隔线半个局部间距，内部取相邻线的中线，再求两族中线交点。
        centers.append(np.vstack([1.5*ordered[0]-.5*ordered[1],(ordered[1:]+ordered[:-1])/2,1.5*ordered[-1]-.5*ordered[-2]]))
    grid = []
    for row_slope, row_base in centers[1]:
        for col_slope, col_base in centers[0]:
            grid_x = (col_base+col_slope*row_base)/(1-col_slope*row_slope)
            grid.append((grid_x,row_slope*grid_x+row_base))
    grid = np.asarray(grid); shaped = grid.reshape(size,size,2)
    if (np.any(grid < 0) or np.any(grid >= [width,height]) or np.any(np.diff(shaped[:,:,0],axis=1)<=0)
            or np.any(np.diff(shaped[:,:,1],axis=0)<=0)):
        return Prediction(reason='中线交点越界或孔号次序不合法')
    return Prediction(grid)


def extra_images(reference, cases):
    blank = np.zeros((565,690,3), np.uint8)
    for name, image in [('black', blank), ('white', 255-blank), *[(f'noise_{seed}',np.random.default_rng(seed).integers(
            0,255,size=blank.shape,dtype=np.uint8)) for seed in (37,23,59)]]:
        yield name, 'negative', image, 10, None
    for case in cases:
        if case.group != 'real_original':
            continue
        image = reference.read_image_unicode(case.path); height,width=image.shape[:2]; dims=np.float32([width-1,height-1])
        transform=cv2.getPerspectiveTransform(np.float32([[0,0],[1,0],[1,1],[0,1]])*dims,
            np.float32([[.018,.024],[.982,.065],[.963,.972],[.042,.944]])*dims)
        yield case.name+'_warp', 'real_warp', cv2.warpPerspective(image,transform,(width,height),borderMode=cv2.BORDER_REPLICATE), case.size, transform


def panel(reference, image, size):
    region = reference.detect_chip_region(image); side = reference._default_rectified_size(size)
    rect, _, inverse = reference.rectify_chip(image,region,side)
    gray = cv2.cvtColor(rect,cv2.COLOR_BGR2GRAY); polarity=reference._estimate_polarity_hint(gray)
    return region, gray, inverse, polarity


def m3g_input(gray, polarity):
    # 作者协议针对暗底亮斑；仅由自动前端判极性。固定缩放到扫描子阵列量级，不从真值裁图。
    bright = gray if polarity=='bright' else 255-gray
    return cv2.imencode('.pgm', cv2.resize(bright, (M3G_SIDE,M3G_SIDE), interpolation=cv2.INTER_AREA))[1].tobytes()


def author_fixture(size):
    """仿作者扫描子阵列的独立接口阳性对照；不用于手机分组得分或选参。"""
    pitch=16; side=size*pitch; axis=np.arange(size)*pitch+(pitch-1)/2
    truth=np.stack(np.meshgrid(axis,axis),axis=-1).reshape(-1,2)
    vertical,horizontal=np.mgrid[:side,:side]; rng=np.random.default_rng(1301+size)
    signal=np.full((side,side),7.0)
    for center_x,center_y in truth:
        signal+=rng.uniform(110,220)*np.exp(-((horizontal-center_x)**2+(vertical-center_y)**2)/(2*3.0**2))
    image=np.clip(signal+rng.normal(0,1.,signal.shape),0,255).astype(np.uint8)
    return cv2.imencode('.pgm',image)[1].tobytes(), truth


def frozen_geometry():
    """锁定定位函数和共用后端，不把汇报文案或文件路径算作几何参数。"""
    from compare_locators import kmeans_grid
    functions=(density_axis,density_grid,log_spots,locate_log,m3g_centers,panel,m3g_input,
               candidate_grid,kmeans_grid,component_candidates,match_candidates,to_original)
    digest=hashlib.sha256(''.join(inspect.getsource(function) for function in functions).encode())
    digest.update(Path(__file__).with_name('spot_lattice.py').read_bytes().replace(b'\r\n',b'\n'))
    digest.update(f'{REFERENCE_SHA}:{M3G_SIDE}:{LIMIT}'.encode()); return digest.hexdigest()


def generate_blind(ref, output):
    frozen=frozen_geometry()
    rows=[]
    for case in multimodal_cases(ref,output,None,seeds=BLIND_SEEDS):
        if case.group in HOLDOUT:
            rows.append(dict(name=case.name,group=case.group,size=case.size,image_sha256=case.sha,
                             truth_sha256=sha256_path(case.path.with_suffix('.json'))))
    if len(rows)!=48 or any(sum(row['group']==group for row in rows)!=12 for group in HOLDOUT):
        raise ValueError('新仿真数量不完整')
    (output/'blind_manifest.json').write_text(json.dumps(dict(seeds=BLIND_SEEDS,frozen_geometry=frozen,cases=rows),indent=2),'utf-8')


def blind_cases(folder, selected):
    manifest=json.loads((folder/'blind_manifest.json').read_text('utf-8'))
    if manifest['frozen_geometry']!=frozen_geometry() or tuple(manifest['seeds'])!=BLIND_SEEDS:
        raise ValueError('本批生成后的定位算法或种子组发生改变，不作为锁参复验')
    all_rows=manifest['cases']
    if (len({row['name'] for row in all_rows})!=48 or len(all_rows)!=48 or
            any(sum(row['size']==size and row['group']==group for row in all_rows)!=6 for size in (10,15) for group in HOLDOUT)):
        raise ValueError('复验清单有重名、缺图或分层数量错误')
    cases=[]
    for item in all_rows:
        name=item['name']
        if not re.fullmatch(r'[a-z0-9_]+',name):raise ValueError('无效案例名')
        if selected and name not in selected:continue
        path=folder/'inputs'/(name+'.jpg'); truth_path=path.with_suffix('.json')
        if sha256_path(path)!=item['image_sha256'] or sha256_path(truth_path)!=item['truth_sha256']:
            raise ValueError(f'{name} 图片或真值指纹不匹配')
        truth=json.loads(truth_path.read_text('utf-8'))
        if truth['grid_size']!=item['size'] or len(truth['points'])!=item['size']**2:raise ValueError('生成器真值规格不符')
        cases.append(Case(name,item['group'],path,item['size'],np.asarray(truth['points']),item['image_sha256']))
    if selected and set(selected)!={case.name for case in cases}:raise ValueError('所选案例不存在')
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('reference-dir','output'):
        parser.add_argument('--'+arg, required=True, type=Path)
    parser.add_argument('--input-run',type=Path); parser.add_argument('--generate-holdout',action='store_true')
    parser.add_argument('--prepare-m3g',action='store_true'); parser.add_argument('--serial',type=Path)
    parser.add_argument('--prepared',type=Path); parser.add_argument('--case',action='append'); parser.add_argument('--font',default='C:/Windows/Fonts/msyh.ttc')
    args=parser.parse_args(); ref=args.reference_dir.resolve(); output=args.output.resolve()
    if hashlib.sha256((ref/'pg_grid.py').read_bytes().replace(b'\r\n',b'\n')).hexdigest()!=REFERENCE_SHA:
        parser.error('参考几何代码指纹不符')
    sys.path.insert(0,str(ref)); reference=importlib.import_module('pg_grid'); cv2.setNumThreads(1); cv2.setRNGSeed(37)
    output.mkdir(parents=True,exist_ok=False)
    if args.generate_holdout:
        generate_blind(ref,output);return
    if not args.input_run:parser.error('运行需要 --input-run')
    blind=(args.input_run/'blind_manifest.json').is_file()
    cases=blind_cases(args.input_run,args.case) if blind else inputs(ref,args.input_run.resolve(),args.case)[0]
    examples={case.name:case for case in cases}; methods=dict(METHODS); raw={}; native={}
    if blind:
        # 旧语料的 PG 已冻结在上轮；新种子的 PG 必须在新图上实跑，不能搬用旧分数。
        methods={'pg':'PG-Grid（默认参考）',**methods}
    if args.serial:
        if not args.prepared: parser.error('需要 --prepared 来校验交给作者程序的逐图字节')
        text=args.serial.read_text('utf-8',errors='replace').replace('\r','')
        if 'M3G_FINISHED' not in text: raise ValueError('M3G 环境运行不完整，不能计为算法失败')
        blocks=re.findall(r'^M3G_BEGIN ([\w-]+) (\d+)\n(.*?)^M3G_DETAILS\n',text,re.M|re.S)
        native={name:(int(code),lines.strip().splitlines()) for name,code,lines in blocks}
        prepared_names={path.stem for path in args.prepared.glob('*.pgm')}
        if len(native)!=len(blocks) or set(native)!=(prepared_names|{'author_example'}):
            raise ValueError('作者输出与实际送入的文件不一一对应，不计为试验结果')
        code,lines=native.get('author_example',(1,[]))
        if code or hashlib.sha256(('\n'.join(sorted(lines))+'\n').encode()).hexdigest()!=AUTHOR_SORTED_LINES_SHA:
            raise ValueError('作者示例分隔线与论文附件不符，停止评分')
        with (output/'m3g_lines.jsonl').open('w',encoding='utf-8') as stream:
            for name in sorted(prepared_names):
                status,lines=native[name]
                stream.write(json.dumps(dict(case=name,pgm_sha256=sha256_path(args.prepared/(name+'.pgm')),exit_code=status,lines=lines))+'\n')
    else:
        methods.pop('m3g')
    if not args.prepare_m3g:
        (output/'images').mkdir(); font=ImageFont.truetype(args.font,17)
    def data():
        for case in cases:
            yield case.name,case.group,reference.read_image_unicode(case.path),case.size,None
        if not blind: yield from extra_images(reference,cases)
    records=[]; controls=[]; coordinates=[]
    for name,group,image,size,warp in data():
        started=perf_counter(); region,gray,inverse,polarity=panel(reference,image,size); front=perf_counter()-started
        pgm=m3g_input(gray,polarity) if region.method!='fallback_center' else None
        if args.prepare_m3g:
            if pgm is not None: (output/(name+'.pgm')).write_bytes(pgm)
            continue
        pitch=gray.shape[0]*(1-2*reference.PipelineConfig(grid_size=size).margin_ratio)/(size-1)
        cand_start=perf_counter(); candidates=component_candidates(gray if polarity=='bright' else 255-gray,pitch) if pgm is not None else None
        common=front+perf_counter()-cand_start; found={}; main_rows={}
        for method in methods:
            started=perf_counter(); region_key=region.method; pol=polarity
            if method=='pg':
                best,pred=pg_geometry(reference,image,size,gray.shape[0],reference.PipelineConfig(grid_size=size))
                pred=to_original(pred,best['inverse_matrix'],size);elapsed=(perf_counter()-started)*1000
                region_key=best['region'].method;pol=best['polarity']
            elif method=='log':
                pred=locate_log(image,size); elapsed=(perf_counter()-started)*1000; region_key='整幅原图'; pol='亮暗＋色差'
            elif pgm is None:
                pred=Prediction(reason='未检测到芯片区域，仅有中央兜底框'); elapsed=front*1000 if method!='m3g' else None
            elif method=='m3g':
                if (args.prepared/(name+'.pgm')).read_bytes()!=pgm:raise ValueError(f'{name} M3G 输入与本次前端不符')
                code,lines=native[name]; pred=m3g_centers(lines,size,M3G_SIDE,M3G_SIDE) if code==0 else Prediction(reason=f'作者程序退出码 {code}')
                scale=gray.shape[0]/M3G_SIDE; resize=np.array([[scale,0,(scale-1)/2],[0,scale,(scale-1)/2],[0,0,1.]])
                pred=to_original(pred,inverse@resize,size);elapsed=None
            else:
                pred=density_grid(reference,candidates,size,gray.shape[0],pitch) if method=='dbscan' else candidate_grid(reference,candidates,size,gray.shape[0],use_kmeans=True)
                pred=to_original(pred,inverse,size);elapsed=(common+perf_counter()-started)*1000
            found['auto',method]=(pred,region_key,pol,elapsed)
            if name in examples:
                case=examples[name]; median,p95,hits=score(pred,case.truth,size)
                row=dict(case=name,group=group,size=size,protocol='auto',method=method,sha256=case.sha,region=region_key,polarity=pol,
                    output=int(pred.points is not None),observed=None if pred.observed is None else int(pred.observed.sum()),
                    median_pitch=median,p95_pitch=p95,hit_fraction=hits,passed=None if case.truth is None else int(p95 is not None and p95<=LIMIT),
                    elapsed_ms=None if elapsed is None else round(elapsed,2),failure=pred.reason)
                records.append(row);main_rows[method]=row
                if group=='real_original':raw[name,method]=pred.points
                coordinates.append(dict(case=name,method=method,xy_original=None if pred.points is None else pred.points.tolist(),
                                        observed=None if pred.observed is None else pred.observed.tolist()))
            else:
                expected=raw.get((name.removesuffix('_warp'),method)) if warp is not None else None
                if expected is not None: expected=cv2.perspectiveTransform(expected.astype(np.float32)[None],warp)[0]
                _,p95,_=score(pred,expected,size); controls.append(dict(case=name,group=group,size=size,method=method,output=int(pred.points is not None),
                    consistency_p95=p95,passed=int(pred.points is None) if group=='negative' else int(p95 is not None and p95<=LIMIT),failure=pred.reason))
        if name in examples:make_sheet(image,examples[name],found,region.points,main_rows,output/'images'/(name+'.jpg'),font,methods)
        print(name,flush=True)
    if args.prepare_m3g:
        for size in (10,15): (output/f'fixture_{size}.pgm').write_bytes(author_fixture(size)[0])
        return
    write_report(output,records,cases,methods,{key:value for key,value in MULTIMODAL_GROUPS.items() if not blind or key in HOLDOUT})
    if controls:save_csv(output/'controls.csv',controls)
    fixture_rows=[]
    for size in (10,15):
        key=f'fixture_{size}'
        if key not in native:continue
        pgm,truth=author_fixture(size);code,lines=native[key]
        if (args.prepared/(key+'.pgm')).read_bytes()!=pgm:raise ValueError('扫描点阵阳性对照输入不符')
        pred=m3g_centers(lines,size,size*16,size*16) if code==0 else Prediction(reason=f'退出码 {code}')
        _,p95,_=score(pred,truth,size);fixture_rows.append(dict(case=key,lines=len(lines),p95_pitch=p95,passed=int(p95 is not None and p95<=LIMIT),failure=pred.reason))
    if fixture_rows:save_csv(output/'m3g_fixtures.csv',fixture_rows)
    (output/'coordinates.jsonl').write_text(''.join(json.dumps(row,allow_nan=False)+'\n' for row in coordinates),'utf-8')
    (output/'run.json').write_text(json.dumps(dict(opencv=cv2.__version__,numpy=np.__version__,reference=REFERENCE_SHA,
        python=sys.version,packages={name:importlib.metadata.version(name) for name in ('scikit-learn','scipy','Pillow')},
        frozen_geometry=frozen_geometry(),blind_seeds=BLIND_SEEDS if blind else None,
        input_manifest=sha256_path(args.input_run/('blind_manifest.json' if blind else 'metrics.csv')),code={path.name:sha256_path(path) for path in (Path(__file__),Path(__file__).with_name('spot_lattice.py'),Path(__file__).with_name('compare_locators.py'))},
        m3g_serial=sha256_path(args.serial) if args.serial else None,cutoff_pitch=LIMIT),indent=2),'utf-8')


if __name__ == '__main__':
    main()
