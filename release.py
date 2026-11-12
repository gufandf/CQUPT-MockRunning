#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
发版脚本 —— 只有在你**明确要求发布版本**时才跑这一个。

为什么需要它：
    版本号以前直接写在 app/build.gradle.kts 里，每改点东西就顺手 +1，
    结果版本号涨得比内容还快（19 个小版本里一半只是文案和间距）。
    现在版本号只有一份（android-app/version.properties），
    而改它的动作被收进这个脚本 —— 你不动这个脚本，版本号就不会动。

    python release.py patch --dry  # 只看会发生什么，不落盘

用法：
    python release.py patch        # G1.11.1 -> G1.11.2（修 bug）
    python release.py minor        # G1.11.1 -> G1.12.0（加功能）
    python release.py major        # G1.11.1 -> G2.0.0（不兼容改动）

versionName 允许一个纯展示前缀（version.properties 的 version.prefix，可空）。
前缀只拼在 versionName 前面，**不参与 versionCode 计算**，也不随进位改变：
prefix 空着就是 1.11.1 这种纯数字形态。

它做四件事（顺序固定，任一步失败就停下，不写半个版本号）：
    1. 读 version.properties，算出新版本号
    2. 把 CHANGELOG.md 的 [Unreleased] 段落改名为新版本 + 今天日期
       （没有 Unreleased 段落就报错 —— 说明这次发版没什么可写的，先补记录）
    3. 回写 version.properties
    4. 构建 release APK，产物按 jingjing-yuepao-<prefix>X.Y.Z.apk 命名
       （文件名纯 ASCII；有前缀时如 jingjing-yuepao-G1.11.1.apk）

**不会**自动 git commit / tag / 推 Release。这四件事改完、
你在 GitHub 上点了发布之后再手动做，脚本把命令打出来供你复制。
"""

from __future__ import annotations

import argparse
import datetime
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.join(ROOT, "android-app")
VERSION_FILE = os.path.join(APP_DIR, "version.properties")
CHANGELOG = os.path.join(ROOT, "CHANGELOG.md")
APK_DIR = os.path.join(APP_DIR, "app", "build", "outputs", "apk", "release")

SEMVER = re.compile(r"^[A-Za-z]*\d+\.\d+\.\d+$")
UNRELEASED = re.compile(r"^##\s*\[Unreleased\]", re.MULTILINE)
DATE_PLACEHOLDER = re.compile(r"^##\s*\[Unreleased\]\s*(?:—|-|·)?\s*[^\n]*$", re.MULTILINE)


def die(msg: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"✗ {msg}", file=sys.stderr)
    sys.exit(1)


def read_version() -> tuple[str, int, int, int]:
    """返回 (prefix, major, minor, patch)。prefix 是纯展示前缀，可空字符串。

    version.prefix 不是整数（它是字母/空），所以单独读，不参与整数校验，
    也不参与 versionCode 计算 —— versionCode 永远由三段数字算出。
    """
    if not os.path.exists(VERSION_FILE):
        die(f"找不到 {VERSION_FILE}")
    vals: dict[str, int] = {}
    prefix = ""
    with open(VERSION_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k = k.strip()
            if k == "version.prefix":
                prefix = v.strip()
                continue
            if k.startswith("version."):
                try:
                    vals[k[len("version."):]] = int(v.strip())
                except ValueError:
                    die(f"version.properties 里 {k} 不是整数：{v!r}")
    missing = {"major", "minor", "patch"} - set(vals)
    if missing:
        die(f"version.properties 缺少字段：{', '.join(sorted(missing))}")
    return prefix, vals["major"], vals["minor"], vals["patch"]


def bump(ver: tuple[int, int, int], part: str) -> tuple[int, int, int]:
    """只进三段数字。前缀是展示用的，不进位、不参与 versionCode。"""
    major, minor, patch = ver
    if part == "major":
        return major + 1, 0, 0
    if part == "minor":
        return major, minor + 1, 0
    return major, minor, patch + 1


def version_name(prefix: str, ver: tuple[int, int, int]) -> str:
    """versionName / tag / APK 名用的形态：prefix + "major.minor.patch"。"""
    return prefix + ".".join(map(str, ver))


def rewrite_changelog(new_ver: str, today: str) -> None:
    """把 [Unreleased] 改成 [<prefix>x.y.z] — 日期，并补上引用式链接。"""
    with open(CHANGELOG, encoding="utf-8") as f:
        text = f.read()

    if not UNRELEASED.search(text):
        die(
            "CHANGELOG.md 里没有 ## [Unreleased] 段落。\n"
            "  发版前先把这次的内容写到 [Unreleased] 下面 —— \n"
            "  版本号是给用户看的变更摘要，不是构建计数器。"
        )

    # 只替换标题行，正文原样保留
    text = DATE_PLACEHOLDER.sub(f"## [{new_ver}] — {today}", text, count=1)

    # 引用式链接：把新版本插到链接列表最上面（也就是紧跟 "---" 之后那段）
    link = f"[{new_ver}]: #"
    if link not in text:
        # 找到第一条 [x.y.z]: 开头的那一行，插在它前面
        m = re.search(r"^\[[A-Za-z]*\d+\.\d+\.\d+\]:", text, re.MULTILINE)
        if m:
            text = text[: m.start()] + f"[{new_ver}]: #\n" + text[m.start():]

    with open(CHANGELOG, "w", encoding="utf-8") as f:
        f.write(text)


def write_version(ver: tuple[int, int, int]) -> None:
    major, minor, patch = ver
    with open(VERSION_FILE, encoding="utf-8") as f:
        text = f.read()
    text = re.sub(r"^version\.major=.*$", f"version.major={major}", text, flags=re.MULTILINE)
    text = re.sub(r"^version\.minor=.*$", f"version.minor={minor}", text, flags=re.MULTILINE)
    text = re.sub(r"^version\.patch=.*$", f"version.patch={patch}", text, flags=re.MULTILINE)
    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        f.write(text)


def build_release(version: str, dry: bool) -> str | None:
    gradlew = os.path.join(APP_DIR, "gradlew")
    if not os.path.exists(gradlew):
        die(f"找不到 {gradlew}")
    cmd = [gradlew, ":app:assembleRelease"]
    print(f"→ {'(dry) ' if dry else ''}{' '.join(cmd)}")
    if dry:
        return None
    r = subprocess.run(cmd, cwd=APP_DIR)
    if r.returncode != 0:
        die("构建失败，版本号已写入但 APK 没出来，先修构建再发版")
    src = os.path.join(APK_DIR, "app-release.apk")
    if not os.path.exists(src):
        die(f"构建成功但没找到 {src}")
    dst = os.path.join(ROOT, f"jingjing-yuepao-{version}.apk")
    shutil.copyfile(src, dst)
    print(f"✓ APK：{os.path.basename(dst)}")
    return dst


def main() -> None:
    ap = argparse.ArgumentParser(description="按语义化规则进位版本号并构建 release APK")
    ap.add_argument("part", choices=["major", "minor", "patch"], help="进哪一位")
    ap.add_argument("--dry", action="store_true", help="只打印，不落盘、不构建")
    ap.add_argument("--no-build", action="store_true", help="只改版本号和 CHANGELOG，不构建")
    args = ap.parse_args()

    prefix, *nums = read_version()
    cur: tuple[int, int, int] = tuple(nums)  # type: ignore[assignment]
    new = bump(cur, args.part)
    cur_s = version_name(prefix, cur)
    new_s = version_name(prefix, new)
    today = datetime.date.today().isoformat()

    print(f"版本：{cur_s} → {new_s}（{args.part}）"
          f"{'' if prefix else '（无前缀）'}")

    if args.dry:
        print(f"（dry）会改 {os.path.relpath(VERSION_FILE, ROOT)}")
        print(f"（dry）会把 CHANGELOG.md 的 [Unreleased] 改成 [{new_s}] — {today}")
        build_release(new_s, dry=True)
        return

    rewrite_changelog(new_s, today)
    write_version(new)
    print(f"✓ 版本号已写入 {os.path.relpath(VERSION_FILE, ROOT)}")
    print("✓ CHANGELOG.md 的 [Unreleased] 已改名为新版本")

    if not args.no_build:
        build_release(new_s, dry=False)

    print()
    print("接下来手动做（脚本不替你提交 / 打 tag / 发 Release）：")
    print(f'  git add -A && git commit -m "{new_s}"')
    print(f'  git tag v{new_s}')
    print(f'  gh release create v{new_s} "jingjing-yuepao-{new_s}.apk" '
          f'--title "鲸鲸约跑 v{new_s}" --notes-file notes.md')
    print()
    print("⚠ 附件名必须是纯英文：GitHub 会剥掉非 ASCII 字符。")
    print("  还有，记得先跑 AGENT.md §6 的隐私检查（openid / token 不能进仓库）。")


if __name__ == "__main__":
    main()
