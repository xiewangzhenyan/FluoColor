"""在无网络、无共享盘的临时 Linux 内存盘上执行作者 M3G 0.01，不修改 WSL 或 Android。

cache 中的 Ubuntu Hardy deb 按发行版索引逐包验 SHA-256；只将包内文件写入访客 cpio，
不在宿主安装或执行包脚本。需要 Python、通用 x86_64 QEMU 和公开发布物。
"""

import argparse
import concurrent.futures
import gzip
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tarfile
import threading
import urllib.request


AUTHOR = '53604cf849a1e2a873fd583c213ed84a9bca2bb3e1aed12360b23d2c285473b2'
INDEX = '20f3f8a554745e2ac2dbf6fbaaea23fc442fcda601269b279574df48ebdb93d7'
UBUNTU = 'https://old-releases.ubuntu.com/ubuntu/'
FILES = {'m3g-original.tgz': (AUTHOR, 'https://media.springernature.com/full/springer-static/esm/art%3A10.1186%2F1471-2105-11-49/MediaObjects/12859_2009_3506_MOESM1_ESM.TGZ'),
         'hardy-Packages.gz': (INDEX, UBUNTU+'dists/hardy/main/binary-amd64/Packages.gz'),
         'm3g-hardy/hardy-kernel.deb': ('9ba028909621096ca3a93e7553e46a6dc602a93148aa70ef906f1fde82289ce6',
             UBUNTU+'pool/main/l/linux/linux-image-2.6.24-16-generic_2.6.24-16.30_amd64.deb')}


def checked(path, digest, url):
    data = path.read_bytes() if path.is_file() else urllib.request.urlopen(url, timeout=90).read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'发布物指纹不符：{path.name}')
    if not path.exists():
        path.write_bytes(data)
    return data


def package_set(cache):
    (cache/'m3g-hardy').mkdir(parents=True, exist_ok=True)
    for name, (digest, url) in FILES.items():
        checked(cache/name, digest, url)
    with tarfile.open(fileobj=deb_data(cache/'m3g-hardy/hardy-kernel.deb')) as archive:
        (cache/'m3g-hardy/vmlinuz-hardy').write_bytes(archive.extractfile('./boot/vmlinuz-2.6.24-16-generic').read())
    entries = [dict(line.split(': ', 1) for line in block.splitlines() if ': ' in line and not line.startswith(' '))
               for block in gzip.decompress((cache/'hardy-Packages.gz').read_bytes()).decode('latin1').split('\n\n')]
    packages = {entry['Package']: entry for entry in entries if 'Package' in entry}
    pending = 'gimp imagemagick xvfb xfonts-base xfonts-utils bash bc coreutils grep sed debianutils gawk net-tools mount busybox-initramfs findutils libgtk2.0-bin libglib2.0-0 ttf-dejavu-core'.split()
    selected = set()
    while pending:
        name = pending.pop()
        if name in selected or name not in packages:
            continue
        selected.add(name)
        for key in ('Pre-Depends', 'Depends'):
            for clause in packages[name].get(key, '').split(','):
                options = [item.strip().split(' ')[0] for item in clause.split('|')]
                pending.extend([item for item in options if item in packages][:1])

    def fetch(name):
        path = cache/'m3g-hardy'/(name+'.deb')
        checked(path, packages[name]['SHA256'], UBUNTU+packages[name]['Filename'])
        return path
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        return list(pool.map(fetch, sorted(selected)))


def deb_data(path):
    blob = path.read_bytes()
    offset = 8
    while offset < len(blob):
        name = blob[offset:offset+16].strip().rstrip(b'/')
        length = int(blob[offset+48:offset+58])
        offset += 60
        if name.startswith(b'data.tar'):
            return io.BytesIO(blob[offset:offset+length])
        offset += length + length % 2
    raise ValueError(f'deb 缺少数据段：{path}')


INIT = '''#!/bin/bash
export PATH=/bin:/usr/bin:/sbin:/usr/sbin HOME=/root LANG=C DISPLAY=:1
mount -t proc proc /proc
mount -t sysfs sys /sys
mkdir -p /dev/pts /dev/shm /tmp/.X11-unix /root /var/tmp /var/run /etc/pango /etc/gtk-2.0
chmod 1777 /tmp /tmp/.X11-unix
ifconfig lo 127.0.0.1 up
ln -sf /usr/bin/gawk /usr/bin/awk
ln -sf /bin/bash /bin/sh
ldconfig 2>/dev/null
mkfontdir /usr/share/fonts/X11/misc 2>/dev/null
pango-querymodules >/etc/pango/pango.modules 2>/dev/null
gdk-pixbuf-query-loaders >/etc/gtk-2.0/gdk-pixbuf.loaders 2>/dev/null
Xvfb :1 -screen 0 800x600x24 -fp /usr/share/fonts/X11/misc -nolisten tcp >/tmp/xlog 2>&1 &
sleep 1
cd /m3g-0.01 || exit 1
bash bin/start-gimp.sh >/tmp/glog 2>&1 &
for attempt in $(seq 1 90); do bin-x86_64/script-fu-client localhost 10008 <<<'(car (gimp-version))' >/dev/null 2>&1 && break; sleep 1; done
echo 'M3G_READY'; tail -c 650 /tmp/xlog /tmp/glog
run_case() {
    caseid="$1"; input="$2"; mkdir -p "/work/$caseid"; cd "/work/$caseid" || return
    # 外部芯片已经自动矫正。禁止作者脚本再次旋转并裁去边缘，否则分隔线会失去原图坐标。
    if [ "$caseid" != author_example ]; then export RADONANG=180; fi
    echo "M3G_RUNNING $caseid"
    /bin/bash /m3g-0.01/m3g.sh -i "$input" -g result.grid 2>&1 | tee report; echo "${PIPESTATUS[0]}" >status
    echo "M3G_BEGIN $caseid $(cat status)"; cat result.grid 2>/dev/null
    echo M3G_DETAILS; tail -c 1800 report; echo; echo M3G_END
}
run_case author_example /m3g-0.01/images/64309_ch1-01.tif
for file in /cases/*.pgm; do
    [ -f "$file" ] && [ "$(cat /work/author_example/status)" = 0 ] || break
    key=$(basename "$file" .pgm); run_case "$key" "$file"
done
cat /tmp/xlog /tmp/glog | tail -c 1500
echo; echo M3G_FINISHED
exec sleep 3600
'''


def make_guest(cache, folder, cases):
    entries = {}

    def add(name, mode, content=b''):
        name = str(PurePosixPath(name))
        if name == '.':
            return
        if name.startswith('/') or '..' in PurePosixPath(name).parts:
            raise ValueError('不接受归档越界路径')
        parent = str(PurePosixPath(name).parent)
        if parent not in entries and parent != '.':
            add(parent, stat.S_IFDIR | 0o755)
        entries[name] = mode, content

    def include(tar, member):
        if member.isdir():
            add(member.name, stat.S_IFDIR | member.mode)
        elif member.isfile() or member.islnk():
            add(member.name, stat.S_IFREG | member.mode, tar.extractfile(member).read())
        elif member.issym():
            add(member.name, stat.S_IFLNK | 0o777, member.linkname.encode())

    for path in package_set(cache):
        with tarfile.open(fileobj=deb_data(path)) as archive:
            for member in archive:
                # 不需要手册、翻译和 GIMP 画笔；保留插件、配置、解释器和字体。
                if not any(part in '/'+member.name+'/' for part in ('/share/doc/', '/share/man/', '/share/locale/', '/share/info/')):
                    include(archive, member)
    with tarfile.open(cache/'m3g-original.tgz') as archive:
        for member in archive:
            if '/bin-i686' not in member.name and ('/images/' not in member.name or member.name.endswith('/64309_ch1-01.tif')):
                include(archive, member)
    for name in ('proc', 'sys', 'dev', 'tmp', 'work', 'cases', 'root', 'etc'):
        add(name, stat.S_IFDIR | 0o755)
    add('etc/passwd', stat.S_IFREG | 0o644, b'root:x:0:0:root:/root:/bin/bash\n')
    add('etc/group', stat.S_IFREG | 0o644, b'root:x:0:\n')
    add('etc/hosts', stat.S_IFREG | 0o644, b'127.0.0.1 localhost\n')
    add('etc/nsswitch.conf', stat.S_IFREG | 0o644, b'hosts: files\npasswd: files\ngroup: files\n')
    add('bin/sh', stat.S_IFLNK | 0o777, b'bash')
    devices={'dev/console':(5,1),'dev/null':(1,3),'dev/zero':(1,5),'dev/random':(1,8),'dev/urandom':(1,9),'dev/tty':(5,0)}
    for name in devices:
        add(name, stat.S_IFCHR | 0o666)
    add('init', stat.S_IFREG | 0o755, INIT.encode())
    for path in cases:
        if not re.fullmatch(r'[a-zA-Z0-9_-]+\.pgm', path.name):
            raise ValueError('非法案例名')
        add('cases/'+path.name, stat.S_IFREG | 0o644, path.read_bytes())
    # newc 文件仅在访客内存盘解包，宿主不展开含绝对符号链接的历史 Linux 包。
    target = folder/'guest.cpio.gz'
    with gzip.open(target, 'wb', compresslevel=1) as stream:
        for inode, (name, (mode, data)) in enumerate([*entries.items(), ('TRAILER!!!', (0, b''))], 1):
            major,minor=devices.get(name,(0,0)); name = name.encode()+b'\0'
            header = b'070701'+(''.join(f'{field:08x}' for field in
                (inode, mode, 0, 0, 1, 0, len(data), 0, 0, major, minor, len(name), 0))).encode()
            stream.write(header+name+b'\0'*(-(len(header)+len(name))%4)); stream.write(data); stream.write(b'\0'*(-len(data)%4))
    return target


def run_guest(cache, folder, cases, qemu, bios, accel):
    if not qemu.is_file() or not bios.is_dir():
        raise ValueError('请提供通用 QEMU 可执行文件和 PC BIOS 目录（不是 Android 模拟器启动器）')
    folder.mkdir(parents=True, exist_ok=False)
    ram = make_guest(cache, folder, cases)
    cmd = [str(qemu), '-machine','pc,accel='+accel,
           '-cpu','qemu64', '-m','1536', '-nodefaults', '-no-reboot','-display','none','-net','none','-monitor','none',
           '-serial','stdio','-L',str(bios), '-kernel',str(cache/'m3g-hardy/vmlinuz-hardy'),
           '-initrd',str(ram),'-append','console=ttyS0 rdinit=/init panic=-1 loglevel=3']
    env = os.environ.copy(); env['PATH'] = str(qemu.parent)+os.pathsep+env['PATH']
    finished=False
    # 老内核不一定能让新版虚拟机 ACPI 关机。完整串口结束标记出现后只回收自己启动的进程；
    # 如果缺少结束标记、访客崩溃或超时，就不能从不完整的日志计算成绩。
    with (folder/'serial.log').open('wb') as log, subprocess.Popen(cmd,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT) as guest:
        deadline=threading.Timer(3600,guest.kill); deadline.daemon=True; deadline.start()
        try:
            for line in guest.stdout:
                log.write(line); log.flush()
                if line.strip()==b'M3G_FINISHED':
                    finished=True; break
        finally:
            deadline.cancel()
            if guest.poll() is None: guest.kill()
            guest.wait()
    if not finished:
        raise RuntimeError('访客未完成；不能把启动失败算作定位失败，请检查 serial.log')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', required=True, type=Path); parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--qemu', required=True, type=Path); parser.add_argument('--bios', required=True, type=Path); parser.add_argument('--inputs', type=Path)
    parser.add_argument('--accel', choices=('tcg','whpx','kvm'), default='tcg')
    args = parser.parse_args()
    run_guest(args.cache.resolve(), args.output.resolve(), sorted(args.inputs.glob('*.pgm')) if args.inputs else [], args.qemu.resolve(), args.bios.resolve(),args.accel)
