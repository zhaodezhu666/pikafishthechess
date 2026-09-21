#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从「皮卡鱼」整合包里提取 Android 版引擎，校验后放进 prebuilt/。

为什么需要这个脚本
------------------
Pikafish 官方 release（如 Pikafish.2026-09-06.7z）里【只有 pikafish.nnue 权重，
没有任何引擎二进制】。安卓版的引擎二进制只能从社区整合包（如「皮卡鱼 XXXXXXXX.zip」）
或自行编译获得。

本脚本做三件事：
  1. 从整合包里挑出 arm64 引擎（优先 dotprod，ARMv8.2+ 手机上快 20~30%）
  2. 校验它确实是 arm64 ELF、且在安卓上能执行
  3. 落盘到 prebuilt/libpikafish-arm64-dotprod.so

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


def pick(names, kw):
    for n in names:
        if n.startswith('__MACOSX') or n.endswith('/'):
            continue
        if kw in os.path.basename(n):
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

    src = pick(names, 'pikafish-armv8-dotprod') or pick(names, 'pikafish-armv8')
    if not src:
        # 兜底：任何看着像 arm64 安卓引擎的文件
        for n in names:
            b = os.path.basename(n)
            if b.endswith(('.exe', '.nnue', '.txt', '.md')):
                continue
            if 'arm64' in b or 'aarch64' in b or 'armv8' in b:
                src = n
                break
    if not src:
        print('!! 整合包里没找到 Android 版引擎。包内文件：')
        for n in names:
            print('   ', n)
        return 1

    data = z.read(src)
    print('选用: %s  (%.2f MB)' % (src, len(data) / 1048576))

    ok, msg = check_elf(data)
    print('校验: ' + msg)
    if not ok:
        return 2

    os.makedirs(KEEP_DIR, exist_ok=True)
    os.makedirs(DST_DIR, exist_ok=True)
    keep = os.path.join(KEEP_DIR, 'libpikafish-arm64-dotprod.so')
    dst = os.path.join(DST_DIR, 'libpikafish.so')
    with open(keep, 'wb') as f:
        f.write(data)
    with open(dst, 'wb') as f:
        f.write(data)
    os.chmod(dst, 0o755)

    print('\n已写入：')
    print('   prebuilt/libpikafish-arm64-dotprod.so        （留档，随仓库分发）')
    print('   app/src/main/jniLibs/arm64-v8a/libpikafish.so （打包用）')
    print('\n接下来：git add -A && git commit && git push，CI 会自动重新出包。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
