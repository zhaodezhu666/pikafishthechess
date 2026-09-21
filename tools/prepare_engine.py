#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从「皮卡鱼」整合包里提取 Android 版引擎，校验后放进 prebuilt/。

为什么需要这个脚本
------------------
Pikafish 官方 release（如 Pikafish.2026-09-06.7z）里【只有 pikafish.nnue 权重，
没有任何引擎二进制】。安卓版的引擎二进制只能从社区整合包（如「皮卡鱼 XXXXXXXX.zip」）
或自行编译获得。

为什么要提取【两个】版本
------------------------
- `pikafish-armv8-dotprod`：需要 ARMv8.2-A 的 dot product 指令（asimddp），
  在支持的手机上快 20~30%；但**在不支持的旧 CPU 上会触发 SIGILL 直接崩溃**。
- `pikafish-armv8`：基线版本，兼容性最好。

App 会在运行时读 /proc/cpuinfo 判断有没有 asimddp，选对应的那个；
万一首选版本启动就挂，还会自动换另一个重试。所以两个都要打包。

用法
----
    python tools/prepare_engine.py "D:/临时/皮卡鱼 20260131.zip"

关于安卓能不能执行
------------------
安卓 5.0 以后要求「位置无关可执行文件（PIE）」，否则 linker 会拒绝。
但这条限制只针对**动态链接**的可执行文件。Pikafish 的安卓版是
**静态链接的 ET_EXEC**（没有 PT_INTERP），由内核直接加载、不经过 linker，
所以可以正常执行。判断依据是「有没有 PT_INTERP」，不是「e_type 是不是 3」。
"""

import os
import struct
import sys
import zipfile

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DST_DIR = os.path.join(PROJ, 'app', 'src', 'main', 'jniLibs', 'arm64-v8a')
KEEP_DIR = os.path.join(PROJ, 'prebuilt')

PT_INTERP = 3
EM_AARCH64 = 0xB7

# 输出名 -> 整合包内文件名关键词
VARIANTS = [
    ('libpikafish.so', 'pikafish-armv8-dotprod'),
    ('libpikafish-generic.so', 'pikafish-armv8'),
]


def pick(names, kw):
    for n in names:
        if n.startswith('__MACOSX') or n.endswith('/'):
            continue
        b = os.path.basename(n)
        # 精确匹配优先，避免 'pikafish-armv8' 命中 'pikafish-armv8-dotprod'
        if b == kw:
            return n
    for n in names:
        if n.startswith('__MACOSX') or n.endswith('/'):
            continue
        b = os.path.basename(n)
        if kw in b and not b.endswith(('.exe', '.nnue', '.txt', '.md')):
            return n
    return None


def check_elf(data):
    if data[:4] != b'\x7fELF':
        return False, '不是 ELF 文件（magic=%s）' % data[:4].hex()
    ei_class = data[4]
    e_type = struct.unpack_from('<H', data, 16)[0]
    e_machine = struct.unpack_from('<H', data, 18)[0]
    e_phoff = struct.unpack_from('<Q', data, 32)[0]
    e_phentsize = struct.unpack_from('<H', data, 54)[0]
    e_phnum = struct.unpack_from('<H', data, 56)[0]
    has_interp = any(
        struct.unpack_from('<I', data, e_phoff + i * e_phentsize)[0] == PT_INTERP
        for i in range(e_phnum)
    )
    if ei_class != 2:
        return False, '不是 64 位 ELF'
    if e_machine != EM_AARCH64:
        return False, '架构不是 AArch64（0x%X）' % e_machine
    if e_type == 3:
        return True, 'PIE 位置无关，安卓 5.0+ 直接可用'
    if not has_interp:
        return True, '静态链接 ET_EXEC（无 PT_INTERP），内核直接加载，安卓可执行'
    return False, '动态链接的非 PIE 可执行文件，安卓 5.0+ 会拒绝执行'


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        print('请传入整合包路径，例如：')
        print('    python tools/prepare_engine.py "D:/临时/皮卡鱼 20260131.zip"')
        return 1

    zip_path = sys.argv[1]
    if not os.path.exists(zip_path):
        print('找不到文件: ' + zip_path)
        return 1

    z = zipfile.ZipFile(zip_path)
    names = [i.filename for i in z.infolist()]
    os.makedirs(KEEP_DIR, exist_ok=True)
    os.makedirs(DST_DIR, exist_ok=True)

    done = 0
    for out_name, kw in VARIANTS:
        src = pick(names, kw)
        if not src:
            print('[跳过] 整合包里没有 %s' % kw)
            continue
        data = z.read(src)
        ok, msg = check_elf(data)
        flag = '✓' if ok else '✗'
        print('[%s] %-26s -> %-24s %6.2f MB  %s' % (flag, kw, out_name, len(data) / 1048576, msg))
        if not ok:
            continue
        keep = os.path.join(KEEP_DIR, out_name)
        dst = os.path.join(DST_DIR, out_name)
        for p in (keep, dst):
            with open(p, 'wb') as f:
                f.write(data)
        os.chmod(dst, 0o755)
        done += 1

    if done == 0:
        print('\n!! 没有提取到任何可用引擎。包内文件：')
        for n in names:
            print('   ', n)
        return 2

    print('\n已提取 %d 个引擎到 prebuilt/ 与 app/src/main/jniLibs/arm64-v8a/' % done)
    print('接下来：git add -A && git commit && git push，CI 会自动重新出包。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
