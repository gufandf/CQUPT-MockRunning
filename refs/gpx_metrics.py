#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gpx_metrics.py —— 轨迹"拟真度"指标（真实轨迹与生成轨迹共用同一套算法）。

为什么单独抽一个模块：`analyze_real_tracks.py`（看真实轨迹）和
`compare_real_vs_gen.py`（比对生成轨迹）必须用**完全相同的口径**，
否则调参数就是在对着两把不同的尺子比。

指标分三类，对应真实轨迹里三种可测量的结构：

  几何   —— 逐秒转角 / 横向偏移（相对 9 点滑动平均骨架）/ 包围盒
  时间   —— 步长分布、停顿段、跳点簇、采样间隔
  相关   —— 步长与横向偏移的自相关（快慢变化和噪声各是什么时间尺度）

输入统一是 [(lat, lon, unix_ts), ...] + 可选 gpx 文本。
"""

from __future__ import annotations

import glob
import math
import os
import re
import statistics as st
from datetime import datetime, timezone

R_EARTH = 6371008.8
HERE = os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------- 基础几何

def hav(a, b):
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    h = (math.sin((lat2 - lat1) / 2) ** 2
         + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2)
    return 2 * R_EARTH * math.asin(min(1.0, math.sqrt(h)))


def bearing(a, b):
    lat1, lon1 = math.radians(a[0]), math.radians(a[1])
    lat2, lon2 = math.radians(b[0]), math.radians(b[1])
    dl = lon2 - lon1
    x = math.sin(dl) * math.cos(lat2)
    y = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0


def turn(a, b, c):
    d = abs(bearing(a, b) - bearing(b, c))
    return min(d, 360.0 - d)


def pct(vals, q):
    if not vals:
        return 0.0
    s = sorted(vals)
    return s[min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))]


def autocorr(x, lag):
    """Pearson 自相关。不去趋势，因为噪音本就是平稳的。"""
    n = len(x)
    if n <= lag + 2:
        return 0.0
    a, b = x[:n - lag], x[lag:]
    ma, mb = st.mean(a), st.mean(b)
    va = sum((v - ma) ** 2 for v in a)
    vb = sum((v - mb) ** 2 for v in b)
    if va <= 0 or vb <= 0:
        return 0.0
    return sum((a[i] - ma) * (b[i] - mb) for i in range(len(a))) / math.sqrt(va * vb)


def runs(mask):
    """连续 True 的段长。"""
    out, cur = [], 0
    for m in mask:
        if m:
            cur += 1
        elif cur:
            out.append(cur)
            cur = 0
    if cur:
        out.append(cur)
    return out


# ---------------------------------------------------------------- 读 GPX

def load_gpx(path):
    """读本项目导出的 GPX（trkpt lat/lon + time），返回 (name, place, [(lat,lon,ts)])。"""
    txt = open(path, encoding="utf-8").read()
    m = re.search(r"<metadata><name>(.*?)</name><desc>(.*?)</desc>", txt)
    name, place = (m.group(1), m.group(2)) if m else (os.path.basename(path), "")
    pts = []
    for t in re.finditer(
            r'<trkpt lat="([-\d.]+)" lon="([-\d.]+)"><time>([^<]+)</time>', txt):
        ts = datetime.strptime(t.group(3), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc).timestamp()
        pts.append((float(t.group(1)), float(t.group(2)), ts))
    return name, place, pts


def real_tracks():
    """refs/real_tracks/*.gpx，按文件名排序。"""
    out = []
    for f in sorted(glob.glob(os.path.join(HERE, "real_tracks", "*.gpx"))):
        name, place, pts = load_gpx(f)
        out.append((name, place, pts))
    return out


# ---------------------------------------------------------------- 骨架 / 横向偏移

def skeleton(latlons, win=9):
    """9 点（±4 秒）滑动平均。真实与生成都用它当"应然路径"。"""
    n = len(latlons)
    h = win // 2
    if n < win:
        return list(latlons)
    out = []
    for i in range(n):
        a, b = max(0, i - h), min(n, i + h + 1)
        out.append((sum(p[0] for p in latlons[a:b]) / (b - a),
                    sum(p[1] for p in latlons[a:b]) / (b - a)))
    return out


def lateral(latlons, skel):
    """每点到骨架最近点的距离（米），即"线有多粗"。"""
    return [min(hav(p, q) for q in skel) for p in latlons]


def signed_lateral(latlons, skel):
    """带符号的横向偏移：正 = 行进方向右侧。"""
    out = []
    for i, p in enumerate(latlons):
        j, k = max(0, i - 4), min(len(latlons) - 1, i + 4)
        if k <= j:
            out.append(0.0)
            continue
        ref = math.radians(bearing(skel[j], skel[k]) + 90.0)
        dlat = math.radians(p[0] - skel[i][0]) * R_EARTH
        dlon = (math.radians(p[1] - skel[i][1]) * R_EARTH
                * math.cos(math.radians(skel[i][0])))
        out.append(dlon * math.cos(ref) + dlat * math.sin(ref))
    return out


# ---------------------------------------------------------------- 指标

def metrics(pts, label=""):
    """一套完整指标。pts = [(lat, lon, ts)]。"""
    latlons = [(p[0], p[1]) for p in pts]
    ts = [p[2] for p in pts]
    n = len(pts)

    steps = [hav(latlons[i], latlons[i + 1]) for i in range(n - 1)]
    dts = [max(0.0, ts[i + 1] - ts[i]) for i in range(n - 1)]
    speeds = [s / d if d > 0.5 else 0.0 for s, d in zip(steps, dts)]
    turns = [turn(latlons[i], latlons[i + 1], latlons[i + 2]) for i in range(n - 2)]

    dist = sum(steps)
    dur = ts[-1] - ts[0] if n > 1 else 0.0
    pace = dur / (dist / 1000.0) if dist > 0 else 0.0
    cruise = dist / dur if dur > 0 else 0.0      # m/s 平均水平

    skel = skeleton(latlons, 9)
    dev = lateral(latlons, skel)
    sdev = signed_lateral(latlons, skel)

    stall_mask = [s < 0.6 and d >= 0.5 for s, d in zip(speeds, dts)]
    stalls = runs(stall_mask)
    jump_mask = [s > max(5.0, 1.75 * cruise) for s in steps]
    jumps = runs(jump_mask)

    # 航向多样性：把方位角按 5° 分箱
    heads = set()
    for i in range(n - 1):
        heads.add(int(bearing(latlons[i], latlons[i + 1]) // 5))

    lats = [p[0] for p in latlons]
    lons = [p[1] for p in latlons]

    return {
        "label": label, "n": n, "dist": dist, "dur": dur, "pace": pace,
        "cruise": cruise,
        # 步长
        "step_med": pct(steps, .5), "step_p90": pct(steps, .9),
        "step_p99": pct(steps, .99), "step_max": max(steps) if steps else 0.0,
        "step_cv": (st.pstdev(steps) / st.mean(steps)) if steps and st.mean(steps) > 0 else 0.0,
        "stall_frac": sum(stall_mask) / max(1, len(stall_mask)),
        "stall_runs": len(stalls),
        "stall_med": st.median(stalls) if stalls else 0.0,
        "stall_max": max(stalls) if stalls else 0,
        "jump_runs": len(jumps),
        "jump_multi": sum(1 for r in jumps if r > 1),
        # 转角
        "turn_med": pct(turns, .5), "turn_mean": st.mean(turns) if turns else 0.0,
        "turn_p75": pct(turns, .75), "turn_p90": pct(turns, .9),
        "turn_p99": pct(turns, .99),
        # 横向偏移
        "dev_med": pct(dev, .5), "dev_p75": pct(dev, .75), "dev_p90": pct(dev, .9),
        "dev_p99": pct(dev, .99), "dev_max": max(dev) if dev else 0.0,
        "dev_frac_15": sum(1 for d in sdev if abs(d) > 1.5) / max(1, n),
        # 自相关
        "ac_speed1": autocorr(speeds, 1), "ac_speed5": autocorr(speeds, 5),
        "ac_speed10": autocorr(speeds, 10),
        "ac_dev1": autocorr(sdev, 1), "ac_dev5": autocorr(sdev, 5),
        "ac_dev10": autocorr(sdev, 10),
        # 形状
        "heads": len(heads),
        "bbox_w": hav((st.mean(lats), min(lons)), (st.mean(lats), max(lons))),
        "bbox_h": hav((min(lats), st.mean(lons)), (max(lats), st.mean(lons))),
        "dt_irregular": sum(1 for d in dts if abs(d - 1.0) > 0.01) / max(1, len(dts)),
    }


def fmt_pace(s):
    if s <= 0 or s > 3600:
        return "--"
    return "%d'%02d\"" % (int(s // 60), int(s % 60))


if __name__ == "__main__":
    for name, place, pts in real_tracks():
        m = metrics(pts, name)
        print(f"{name}  {place}  {m['n']} 点  {m['dist']:.0f}m  {fmt_pace(m['pace'])}")
