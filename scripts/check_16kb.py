#!/usr/bin/env python3
"""16 KB page-size check for every native library in a built APK or AAB.

Google Play requires apps with native code to run on 16 KB-page devices.
Two things decide that, and both are checked here on the final artifact,
not on our own build flags -- a prebuilt .so pulled in from an AAR is
packaged just the same and is the usual offender:

1. ELF: every PT_LOAD segment of every .so must be aligned to >= 16384
   (a library linked for 4 KB pages cannot be mapped on a 16 KB device).
2. APK zip layout: a .so stored uncompressed (loaded straight from the APK)
   must start at a 16384-aligned offset in the zip -- what
   `zipalign -c -P 16` checks. Compressed .so files are extracted at install
   time, so their zip offset does not matter. An AAB has no such layout
   (Play builds the APKs), so only (1) applies to it.

Usage: check_16kb.py [--ours NAME,NAME] ARTIFACT [ARTIFACT ...]
exit 1 on any violation, and on an artifact with no native libraries at all
(a false green otherwise). With --ours, only libraries this project builds
(lib<NAME>*.so) fail the check; a prebuilt one from a dependency is reported
as a warning -- for a build that is not a Play artifact, where the finding
is real but not ours to fix.

Shared with IntelliVerse (its scripts/check_16kb.py), which runs it on its
release APK and AAB without --ours.
"""

import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1


def load_alignments(data: bytes):
    """p_align of each PT_LOAD segment; raises ValueError for a non-ELF blob."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(endian + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    else:
        phoff, = struct.unpack_from(endian + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x2A)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type, = struct.unpack_from(endian + "I", data, off)
        if p_type != PT_LOAD:
            continue
        if is64:
            p_align, = struct.unpack_from(endian + "Q", data, off + 0x30)
        else:
            p_align, = struct.unpack_from(endian + "I", data, off + 0x1C)
        aligns.append(p_align)
    return aligns


def data_offset(zf: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    """Where the entry's bytes start in the archive (after its local header)."""
    zf.fp.seek(info.header_offset)
    header = zf.fp.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check(path: str):
    problems, checked = [], 0
    is_apk = path.endswith(".apk")
    with zipfile.ZipFile(path) as zf:
        for info in zf.infolist():
            if not info.filename.endswith(".so") or "/lib/" not in "/" + info.filename:
                continue
            checked += 1
            try:
                aligns = load_alignments(zf.read(info))
            except ValueError as e:
                problems.append(f"{info.filename}: {e}")
                continue
            bad = [a for a in aligns if a < PAGE]
            if not aligns or bad:
                problems.append(f"{info.filename}: PT_LOAD alignment {[hex(a) for a in aligns]} < 0x4000")
            if is_apk and info.compress_type == zipfile.ZIP_STORED:
                offset = data_offset(zf, info)
                if offset % PAGE:
                    problems.append(f"{info.filename}: stored at zip offset {offset}, not 16 KB-aligned (zipalign -P 16)")
    return checked, problems


def is_ours(problem: str, ours) -> bool:
    if ours is None:
        return True
    name = problem.split(":", 1)[0].rsplit("/", 1)[-1]
    return any(name.startswith("lib" + prefix) for prefix in ours)


def main(args):
    ours = None
    if args[:1] == ["--ours"]:
        ours = [p for p in args[1].split(",") if p]
        args = args[2:]
    failed = False
    for path in args:
        checked, all_problems = check(path)
        problems = [p for p in all_problems if is_ours(p, ours)]
        print(f"{path}: {checked} native libraries checked, {len(problems)} problem(s)")
        for p in problems:
            print(f"  16KB: {p}")
        for p in all_problems:
            if p not in problems:
                print(f"  16KB (prebuilt, not ours -- warning only): {p}")
        if checked == 0:
            # This app always ships native code; finding none means the
            # packaging or the lib/ path changed, not that all is well.
            print(f"  16KB: no native libraries found -- nothing was checked")
            failed = True
        failed |= bool(problems)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
