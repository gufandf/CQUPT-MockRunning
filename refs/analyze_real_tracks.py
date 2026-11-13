#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
analyze_real_tracks.py —— 用真实 GPX 轨迹标定 TrackGen 的噪声参数。

输入是 refs/real_tracks/*.gpx（真实的、人跑出来的记录，由服务端导出）。
输出是一组统计量，用来回答「TrackGen 生成的轨迹像不像真的」：

  · 时长 / 距离 / 配速 —— 服务端怎么算的，我们的目标是什么
  · 逐秒步长分布 —— 有没有停顿、跳点、原地不动
  · 逐秒转角分布 —— 毛刺有多粗（中位数 / p90 / 平均）
  · 横向偏移（相对平滑后的骨架）—— GPS 噪声幅度到底多大
  · 多圈叠图的圈间距 —— 「粗线」到底有多粗
  · 采样间隔的实际分布 —— 是不是严格 1 秒
  · 经纬度定长 / 精度

跑法：python refs/analyze_real_tracks.py
"""

from __future__ import annotations

import glob
import math
import os
import re
import statistics as st
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
R = 6371008.8


# ---------------------------------------------------------------- 基础

def hav(a, b):
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    h = (math.sin((lat2 - lat1) / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * R * math.asin(min(1.0, math.sqrt(h)))


def bearing(a, b):
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dl = lon2 - lon1
    x = math.sin(dl) * math.cos(lat2)
    y = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0


def turn(a, b, c):
    """a→b→c 的转角（0~180，越小越直）。"""
    d = abs(bearing(a, b) - bearing(b, c))
    return min(d, 360.0 - d)


def load_gpx(path):
    """只认本项目导出的格式：trkpt lat/lon + time。"""
    txt = open(path, encoding="utf-8").read()
    name = re.search(r"<metadata><name>(.*?)</name><desc>(.*?)</desc>", txt)
    pts = []
    for m in re.finditer(
            r'<trkpt lat="([-\d.]+)" lon="([-\d.]+)"><time>([^<]+)</time>', txt):
        t = datetime.strptime(m.group(3), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc).timestamp()
        pts.append((float(m.group(1)), float(m.group(2)), t))
    return (name.group(1), name.group(2)) if name else (os.path.basename(path), ""), pts


def pct(vals, q):
    if not vals:
        return 0.0
    s = sorted(vals)
    i = min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))
    return s[i]


# ---------------------------------------------------------------- 平滑骨架

def smooth_path(latlons, win=9):
    """滑动平均，用来当"真实骨架"（去掉 GPS 噪声后的应然路径）。"""
    n = len(latlons)
    if n < win:
        return list(latlons)
    h = win // 2
    out = []
    for i in range(n):
        a = max(0, i - h)
        b = min(n, i + h + 1)
        la = sum(p[0] for p in latlons[a:b]) / (b - a)
        lo = sum(p[1] for p in latlons[a:b]) / (b - a)
        out.append((la, lo))
    return out


def lateral_dev(latlons, skel):
    """每个点到骨架最近点的距离（米）—— 就是"线有多粗"。"""
    d = []
    for p in latlons:
        d.append(min(hav(p, q) for q in skel))
    return d


# ---------------------------------------------------------------- 主分析

def analyze(path):
    (name, place), pts = load_gpx(path)
    latlons = [(p[0], p[1]) for p in pts]
    ts = [p[2] for p in pts]

    steps = [hav(latlons[i], latlons[i + 1]) for i in range(len(pts) - 1)]
    dts = [round(ts[i + 1] - ts[i], 3) for i in range(len(pts) - 1)]
    turns = [turn(latlons[i], latlons[i + 1], latlons[i + 2])
             for i in range(len(pts) - 2)]

    dist = sum(steps)
    dur = ts[-1] - ts[0]
    pace = dur / (dist / 1000.0) if dist > 0 else 0.0

    # 骨架偏移：多圈叠图时用较长窗口，一次跑步几圈要按圈长折算
    skel = smooth_path(latlons, 9)
    lat_dev = lateral_dev(latlons, skel)

    # 累计转角符号：判断顺时针 / 逆时针（国内操场逆时针）
    tot = 0.0
    for i in range(len(pts) - 2):
        b1 = bearing(latlons[i], latlons[i + 1])
        b2 = bearing(latlons[i + 1], latlons[i + 2])
        d = (b2 - b1 + 540.0) % 360.0 - 180.0
        tot += d

    # 轨迹包围盒
    lats = [p[0] for p in latlons]
    lons = [p[1] for p in latlons]
    w = hav((sum(lats) / len(lats), min(lons)), (sum(lats) / len(lats), max(lons)))
    h = hav((min(lats), sum(lons) / len(lons)), (max(lats), sum(lons) / len(lons)))

    stalled = sum(1 for s in steps if s < 0.4)
    jumps = sum(1 for s in steps if s > 5.0)
    mean_step = dist / max(1, len(steps))

    return {
        "name": name, "place": place, "n": len(pts),
        "dist": dist, "dur": dur, "pace": pace,
        "dts": dts, "steps": steps, "turns": turns,
        "lat_dev": lat_dev, "bbox": (w, h),
        "stalled": stalled, "jumps": jumps,
        "mean_step": mean_step,
        "dir": "逆时针" if tot > 0 else "顺时针",
        "cum_turn_deg": tot,
        "lat_span": max(lats) - min(lats), "lon_span": max(lons) - min(lons),
    }


def fmt_pace(s):
    if s <= 0 or s > 3600:
        return "--"
    return f"{int(s // 60)}'{int(s % 60):02d}\""


def main():
    files = sorted(glob.glob(os.path.join(HERE, "real_tracks", "*.gpx")))
    if not files:
        print("没有找到 refs/real_tracks/*.gpx")
        return 1

    rows = [analyze(f) for f in files]

    print("=" * 100)
    print("真实轨迹总览")
    print("=" * 100)
    print(f"{'轨迹':<32}{'场地':<10}{'点数':>5}{'距离':>9}{'时长':>8}{'配速':>9}"
          f"{'单圈长':>8}{'方向':>7}")
    print("-" * 100)
    for r in rows:
        print(f"{r['name']:<32}{r['place']:<10}{r['n']:>5}{r['dist']:>8.0f}m"
              f"{r['dur'] / 60:>7.1f}m{r['pace']:>9}"
              f"{r['dist'] / max(1, abs(r['cum_turn_deg']) / 360):>7.0f}m"
              f"{r['dir']:>7}")

    print()
    print("=" * 100)
    print("逐秒步长（米）—— 有没有停顿 / 跳点")
    print("=" * 100)
    print(f"{'轨迹':<32}{'min':>6}{'p10':>6}{'中位':>6}{'p90':>6}{'max':>6}"
          f"{'<0.4m':>7}{'>5m':>6}{'均值':>7}")
    print("-" * 100)
    for r in rows:
        s = r["steps"]
        print(f"{r['name']:<32}{min(s):>6.2f}{pct(s, .1):>6.2f}{pct(s, .5):>6.2f}"
              f"{pct(s, .9):>6.2f}{max(s):>6.1f}{r['stalled']:>7}{r['jumps']:>6}"
              f"{r['mean_step']:>7.2f}")

    print()
    print("=" * 100)
    print("逐秒转角（度）—— 线有多毛")
    print("=" * 100)
    print(f"{'轨迹':<32}{'平均':>7}{'中位':>7}{'p75':>7}{'p90':>7}{'p99':>7}")
    print("-" * 100)
    for r in rows:
        t = r["turns"]
        print(f"{r['name']:<32}{st.mean(t):>7.1f}{pct(t, .5):>7.1f}{pct(t, .75):>7.1f}"
              f"{pct(t, .9):>7.1f}{pct(t, .99):>7.1f}")

    print()
    print("=" * 100)
    print("横向偏移（相对 9 点滑动平均骨架，米）—— 「粗线」有多粗")
    print("=" * 100)
    print(f"{'轨迹':<32}{'中位':>7}{'p75':>7}{'p90':>7}{'p99':>7}{'max':>7}")
    print("-" * 100)
    for r in rows:
        d = r["lat_dev"]
        print(f"{r['name']:<32}{pct(d, .5):>7.2f}{pct(d, .75):>7.2f}"
              f"{pct(d, .9):>7.2f}{pct(d, .99):>7.2f}{max(d):>7.2f}")

    print()
    print("=" * 100)
    print("采样间隔（秒）与坐标跨度")
    print("=" * 100)
    for r in rows:
        dts = r["dts"]
        cnt = {}
        for d in dts:
            cnt[d] = cnt.get(d, 0) + 1
        top = sorted(cnt.items(), key=lambda kv: -kv[1])[:5]
        print(f"{r['name']:<32} 间隔分布 {top}")
        print(f"{'':<32} 包围盒 {r['bbox'][0]:.0f}m x {r['bbox'][1]:.0f}m"
              f"  累计转角 {r['cum_turn_deg']:+.1f} rad"
              f"  约 {abs(r['cum_turn_deg']) / 360:.1f} 圈")

    # 汇总：真实轨迹的"目标画像"
    print()
    print("=" * 100)
    print("汇总画像（真实轨迹的统计骨架，TrackGen 应对齐到这里）")
    print("=" * 100)
    all_turns = [t for r in rows for t in r["turns"]]
    all_dev = [d for r in rows for d in r["lat_dev"]]
    all_steps = [s for r in rows for s in r["steps"]]
    print(f"  转角   平均 {st.mean(all_turns):.1f}°  中位 {pct(all_turns, .5):.1f}°"
          f"  p90 {pct(all_turns, .9):.1f}°  p99 {pct(all_turns, .99):.1f}°")
    print(f"  横向偏移 中位 {pct(all_dev, .5):.2f}m  p90 {pct(all_dev, .9):.2f}m"
          f"  p99 {pct(all_dev, .99):.2f}m  max {max(all_dev):.2f}m")
    print(f"  步长   中位 {pct(all_steps, .5):.2f}m  p90 {pct(all_steps, .9):.2f}m"
          f"  静止点(<0.4m) {sum(1 for s in all_steps if s < 0.4) / len(all_steps):.1%}")
    print(f"  跳点(>5m) {sum(1 for s in all_steps if s > 5.0) / len(all_steps):.1%}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
