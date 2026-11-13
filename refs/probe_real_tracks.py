#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
probe_real_tracks.py —— 深挖真实轨迹的时间结构，用来定 TrackGen 的噪声/配速模型。

analyze_real_tracks.py 只回答"分布长什么样"。这里问的是"结构长什么样"：

  1. 逐秒步长的自相关 —— 快慢变化是成簇的还是逐秒独立的？簇有多长？
  2. 停顿 —— 有多长、连续几秒、整场几次
  3. 横向偏移的自相关 —— GPS 噪声的时间尺度（AR(1) 的 rho 到底该多大）
  4. 横向偏移的簇 —— 偏移是不是"成段"出现（贴楼 / 树荫）
  5. 圈间重叠 —— 同一场地多次经过同一位置的横向散布（"粗线"宽度）
  6. 圈长与配速的漂移 —— 每圈用时是否递增（疲劳）
  7. 大跳（>5m）的成对结构 —— 是单点尖刺还是 2~3 秒锯齿

跑法：python refs/probe_real_tracks.py
"""

from __future__ import annotations

import glob
import math
import os
import statistics as st

from analyze_real_tracks import load_gpx, hav, bearing, turn, smooth_path, pct, HERE


def autocorr(x, lag):
    """自相关（未去均值的话低频趋势会主导，这里按 Pearson 算）。"""
    n = len(x)
    if n <= lag + 2:
        return 0.0
    a = x[:n - lag]
    b = x[lag:]
    ma, mb = st.mean(a), st.mean(b)
    va = sum((v - ma) ** 2 for v in a)
    vb = sum((v - mb) ** 2 for v in b)
    if va <= 0 or vb <= 0:
        return 0.0
    cov = sum((a[i] - ma) * (b[i] - mb) for i in range(len(a)))
    return cov / math.sqrt(va * vb)


def runs(mask):
    """连续 True 的段长列表。"""
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


def resample_turns(latlons):
    return [turn(latlons[i], latlons[i + 1], latlons[i + 2])
            for i in range(len(latlons) - 2)]


def analyze(path):
    (name, place), pts = load_gpx(path)
    latlons = [(p[0], p[1]) for p in pts]
    ts = [p[2] for p in pts]
    steps = [hav(latlons[i], latlons[i + 1]) for i in range(len(pts) - 1)]
    dts = [max(0.0, ts[i + 1] - ts[i]) for i in range(len(pts) - 1)]

    # 归一化速度：除以 dt，得到 m/s（真实 App 有非 1s 间隔）
    speeds = [(s / d) if d > 0.5 else 0.0 for s, d in zip(steps, dts)]

    skel = smooth_path(latlons, 9)
    dev = [min(hav(p, q) for q in skel) for p in latlons]
    # 带符号的横向偏移
    signed = []
    for i, p in enumerate(latlons):
        j = max(0, i - 4)
        k = min(len(latlons) - 1, i + 4)
        if k <= j:
            signed.append(0.0)
            continue
        hdg = bearing(skel[j], skel[k])
        sk = skel[i]
        ref = math.radians(hdg + 90.0)
        dlat = math.radians(p[0] - sk[0]) * 6371008.8
        dlon = math.radians(p[1] - sk[1]) * 6371008.8 * math.cos(math.radians(sk[0]))
        signed.append(dlon * math.cos(ref) + dlat * math.sin(ref))

    # 圈识别：用累计转角，每 ±360° 算一圈
    cum = [0.0]
    for i in range(len(pts) - 2):
        b1 = bearing(latlons[i], latlons[i + 1])
        b2 = bearing(latlons[i + 1], latlons[i + 2])
        cum.append(cum[-1] + (b2 - b1 + 540.0) % 360.0 - 180.0)
    laps = int(max(abs(cum[0]), abs(cum[-1])) // 360)

    # 每圈用时（按 360° 转折点切分）
    lap_secs = []
    if abs(cum[-1]) > 720:
        sign = 1.0 if cum[-1] > 0 else -1.0
        idx = [0]
        k = 1
        while k < len(cum):
            if abs(cum[k]) >= abs(cum[idx[-1]]) + 360.0:
                idx.append(k)
            k += 1
        idx.append(len(pts) - 1)
        lap_secs = [ts[idx[i + 1]] - ts[idx[i]] for i in range(len(idx) - 1)]

    # 圈间重叠：把轨迹按"到起点距离"分箱，看同位置多次经过的横向散布
    # 更直接的做法：用骨架上的弧长做参数化，统计同弧长处的 dev 分布
    # 这里用 2m 网格去重，统计每格被经过的次数与坐标散布
    grid = {}
    for p, d in zip(latlons, dev):
        key = (round(p[0], 5), round(p[1], 5))     # ~1.1m
        grid.setdefault(key, []).append(d)

    return {
        "name": name, "place": place,
        "steps": steps, "dts": dts, "speeds": speeds,
        "dev": dev, "signed": signed,
        "laps": laps, "lap_secs": lap_secs,
        "n": len(pts),
    }


def main():
    files = sorted(glob.glob(os.path.join(HERE, "real_tracks", "*.gpx")))
    rows = [analyze(f) for f in files]

    print("=" * 100)
    print("① 逐秒速度的自相关 —— 快慢变化的时间尺度")
    print("=" * 100)
    print(f"{'轨迹':<32}" + "".join(f"{f'ρ{lag}':>7}" for lag in (1, 2, 3, 5, 10, 20)) + f"{'半衰':>7}")
    print("-" * 100)
    for r in rows:
        sp = r["speeds"]
        acs = [autocorr(sp, lag) for lag in (1, 2, 3, 5, 10, 20)]
        half = next((lag for lag, a in zip((1, 2, 3, 5, 10, 20), acs) if a < 0.5), 20)
        print(f"{r['name']:<32}" + "".join(f"{a:>7.2f}" for a in acs) + f"{half:>7}")

    print()
    print("=" * 100)
    print("② 停顿（速度 < 0.6 m/s）—— 有几次、每次多长")
    print("=" * 100)
    for r in rows:
        mask = [s < 0.6 and d >= 0.5 for s, d in zip(r["speeds"], r["dts"])]
        rs = runs(mask)
        if rs:
            print(f"{r['name']:<32} {len(rs):>2} 段  段长 {sorted(rs)}"
                  f"  合计 {sum(rs)}s ({sum(rs) / r['n']:.1%})")
        else:
            print(f"{r['name']:<32}  0 段")

    print()
    print("=" * 100)
    print("③ 横向偏移的自相关 —— AR(1) 的 rho 该多大")
    print("=" * 100)
    print(f"{'轨迹':<32}" + "".join(f"{f'ρ{lag}':>7}" for lag in (1, 2, 3, 5, 10, 30, 60)))
    print("-" * 100)
    for r in rows:
        sg = r["signed"]
        print(f"{r['name']:<32}"
              + "".join(f"{autocorr(sg, lag):>7.2f}" for lag in (1, 2, 3, 5, 10, 30, 60)))

    print()
    print("=" * 100)
    print("④ 横向偏移「成段」吗 —— 连续多少秒偏移超过 1.5m")
    print("=" * 100)
    for r in rows:
        mask = [abs(v) > 1.5 for v in r["signed"]]
        rs = runs(mask)
        if rs:
            print(f"{r['name']:<32} {len(rs):>2} 段  段长中位 {st.median(rs):.0f}s"
                  f"  最长 {max(rs)}s  总计 {sum(rs)}s ({sum(rs) / r['n']:.1%})")
        else:
            print(f"{r['name']:<32}  0 段")

    print()
    print("=" * 100)
    print("⑤ 圈数与每圈用时 —— 疲劳 / 掉速")
    print("=" * 100)
    for r in rows:
        ls = r["lap_secs"]
        if not ls:
            continue
        trend = "递增（掉速）" if ls[-1] > ls[0] * 1.03 else (
            "递减（加速）" if ls[-1] < ls[0] * 0.97 else "基本持平")
        print(f"{r['name']:<32} {len(ls)} 圈  每圈 {[round(x) for x in ls]}s"
              f"  → {trend}  {(ls[-1] / ls[0] - 1) * 100:+.1f}%")

    print()
    print("=" * 100)
    print("⑥ 大跳（>5m 位移）的结构 —— 单点尖刺还是多点锯齿")
    print("=" * 100)
    for r in rows:
        mask = [s > 5.0 for s in r["steps"]]
        rs = runs(mask)
        if rs:
            print(f"{r['name']:<32} {len(rs):>3} 簇  簇长分布 {sorted(set(rs))}"
                  f"  单点 {rs.count(1)}/{len(rs)}")
        else:
            print(f"{r['name']:<32}  0 簇")

    print()
    print("=" * 100)
    print("⑦ 采样间隔异常 —— 真实 App 不是严格 1Hz")
    print("=" * 100)
    for r in rows:
        bad = sum(1 for d in r["dts"] if abs(d - 1.0) > 0.01)
        print(f"{r['name']:<32} 非 1s 间隔 {bad}/{len(r['dts'])} = {bad / len(r['dts']):.1%}"
              f"  重复时间戳 {sum(1 for d in r['dts'] if d == 0.0)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
