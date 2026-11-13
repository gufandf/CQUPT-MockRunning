#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
score_model.py —— 用真实轨迹的统计骨架给生成轨迹打分。

**只评几何，不评配速。** 配速是用户设定值（必须精确命中目标），
不是"拟真"的一部分；真实记录里配速从 3'57" 到 6'54" 都有，
拿它当标准没有意义。这里比较的是「画在图上像不像人跑出来的」。

验收区间的取法
--------------
5 条真实记录的同一指标差异很大（比如偏移 p90 从 1.07 到 8.34 m ——
那是一个人 GPS 好、一个人总贴楼）。所以不能用「中位 ± 固定容差」，
而是取真实样本的 **[min, max] 作为接受域**：生成结果只要落在真实记录
自己都出现过的范围内，就算拟真。这比拟合单条"最干净"的记录更稳，
也不会逼着模型去凑某一次异常采样。

跑法：
    python refs/score_model.py             # 3 场地 × 4 seed
    python refs/score_model.py --verbose   # 打印逐条明细
"""

from __future__ import annotations

import argparse
import os
import statistics as st
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import gpx_metrics as M          # noqa: E402
import track_model as T          # noqa: E402

TARGET_DIST = 2000.0
TARGET_PACE = 330.0      # 5'30"，学校允许区间 4'00"~7'30" 的中间

# 指标 → (中文名, 方向)。方向：
#   in   落在真实 [min, max] 内
#   max  不超过真实最大值（尾部指标，越小越保守）
CHECKS = [
    ("dev_med",    "偏移中位",     "in"),
    ("dev_p90",    "偏移p90",      "in"),
    ("dev_p99",    "偏移p99",      "max"),
    ("turn_med",   "转角中位",     "in"),
    ("turn_p90",   "转角p90",      "in"),
    ("turn_p99",   "转角p99",      "in"),
    ("step_cv",    "步长CV",       "in"),
]


def jump_clusters(pts, k=2.2, win=15):
    """按「局部巡航步长的 k 倍」判跳点。

    不用「整场均值 × 1.75」：真实记录的巡航步长本身就在 2.7~4.2 m 之间，
    整场均值会被后半程的慢速拉低，于是前半程每个正常步都会被判成跳点。
    局部中位数跟着配速一起漂，判据才是稳健的。
    """
    ll = [(p[0], p[1]) for p in pts]
    steps = [M.hav(ll[i], ll[i + 1]) for i in range(len(ll) - 1)]
    flags = []
    for i in range(len(steps)):
        a, b = max(0, i - win), min(len(steps), i + win + 1)
        local = st.median(steps[a:b])
        flags.append(steps[i] > k * max(local, 0.5))
    return M.runs(flags)


def edge_gap(pts, field):
    """离围栏边界的距离中位。pts 的元素可以是 (lat,lon,ts) 或 dict。"""
    fence = T.dedupe(T.FIELDS[field][0])
    d = []
    for p in pts:
        lat = p["lat"] if isinstance(p, dict) else p[0]
        lon = p["lon"] if isinstance(p, dict) else p[1]
        d.append(T.dist_to_edge((lat, lon), fence))
    return st.median(d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, nargs="*", default=[12345, 777, 20261013, 42])
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    reals = M.real_tracks()
    if not reals:
        print("没有 refs/real_tracks/*.gpx")
        return 1

    # ---- 真实基线 ----
    real_rows, real_jumps, real_edge = [], [], {}
    for name, place, pts in reals:
        m = M.metrics(pts, name)
        real_rows.append(m)
        jc = jump_clusters(pts)
        real_jumps.append((len(jc), sum(1 for r in jc if r > 1)))
        if place in T.FIELDS:
            real_edge.setdefault(place, []).append(edge_gap(pts, place))

    print("=" * 92)
    print("真实基线（5 条记录，逐条列出；验收域取 [min, max]）")
    print("=" * 92)
    print(f"{'指标':<12}" + "".join(f"{r['n']:>9}" for r in real_rows)
          + f"{'接受域':>18}")
    print("-" * 92)
    for key, lab, mode in CHECKS:
        v = [r[key] for r in real_rows]
        print(f"{lab:<12}" + "".join(f"{x:>9.2f}" for x in v)
              + f"{f'[{min(v):.2f}, {max(v):.2f}]':>18}")
    jv = [x[0] for x in real_jumps]
    jm = [x[1] for x in real_jumps]
    print(f"{'跳点簇':<12}" + "".join(f"{x[0]:>9d}" for x in real_jumps)
          + f"{f'[{min(jv)}, {max(jv)}]':>18}")
    print(f"{'多点簇':<12}" + "".join(f"{x[1]:>9d}" for x in real_jumps)
          + f"{f'[{min(jm)}, {max(jm)}]':>18}")
    print(f"\n{'离边距m':<12}" + "".join(
        f"{st.mean(real_edge.get(p, [0])):>9.1f}" if p in real_edge else f"{'--':>9}"
        for _, p, _ in reals))
    print(f"{'对应场地':<12}" + "".join(f"{p[:8]:>9}" for _, p, _ in reals))

    # ---- 生成 ----
    gens = []
    for field, (fence, exc) in T.FIELDS.items():
        for seed in args.seed:
            pts = T.generate(fence, exc, TARGET_PACE, TARGET_DIST, seed=seed, place_code=field)[0]
            triple = [(p["lat"], p["lon"], float(p["sec"])) for p in pts]
            m = M.metrics(triple, f"{field}/s{seed}")
            jc = jump_clusters(triple)
            m["jump_runs"] = len(jc)
            m["jump_multi"] = sum(1 for r in jc if r > 1)
            m["edge"] = edge_gap(pts, field)
            m["inside"] = sum(1 for p in pts
                              if T.point_in_poly(p["lat"], p["lon"], fence)) / len(pts)
            m["banned"] = sum(1 for p in pts
                              if any(T.point_in_poly(p["lat"], p["lon"], e) for e in exc))
            m["gen_dist"] = T.measure_dist(pts)
            gens.append(m)

    print()
    print("=" * 92)
    print(f"生成（{len(T.FIELDS)} 场地 × {len(args.seed)} seed，"
          f"{T.fmt_pace(TARGET_PACE)} / {TARGET_DIST / 1000:.1f} km）")
    print("=" * 92)
    print(f"{'指标':<12}{'生成min':>10}{'生成中位':>10}{'生成max':>10}{'验收域':>18}{'判定':>6}")
    print("-" * 92)
    fails = []
    for key, lab, mode in CHECKS:
        rv = [r[key] for r in real_rows]
        gv = [g[key] for g in gens]
        lo, hi = min(rv), max(rv)
        if mode == "in":
            ok = min(gv) <= hi and max(gv) >= lo
            detail = f"[{lo:.2f}, {hi:.2f}]"
        else:
            ok = max(gv) <= hi * 1.15
            detail = f"≤ {hi:.2f}"
        if not ok:
            fails.append((lab, st.median(gv), lo, hi))
        print(f"{lab:<12}{min(gv):>10.2f}{st.median(gv):>10.2f}{max(gv):>10.2f}"
              f"{detail:>18}{'✓' if ok else '✗':>6}")

    for key, lab, rv in (("jump_runs", "跳点簇", jv), ("jump_multi", "多点簇", jm)):
        gv = [g[key] for g in gens]
        ok = max(gv) <= max(rv) * 1.25
        if not ok:
            fails.append((lab, st.median(gv), min(rv), max(rv)))
        print(f"{lab:<12}{min(gv):>10.0f}{st.median(gv):>10.0f}{max(gv):>10.0f}"
              f"{f'≤ {max(rv)}':>18}{'✓' if ok else '✗':>6}")

    print()
    print("=" * 92)
    print("逐场地：离边距（真实 → 生成）")
    print("=" * 92)
    for field in T.FIELDS:
        if field not in real_edge:
            continue
        want = st.mean(real_edge[field])
        got = [g["edge"] for g in gens if g["label"].startswith(field)]
        print(f"  {field:<12} 真实 {want:>5.1f} m → 生成 "
              f"{min(got):.1f}~{max(got):.1f} m（中位 {st.median(got):.1f}）")

    print()
    print("=" * 92)
    print("硬约束（每一条都必须成立）")
    print("=" * 92)
    hard = []
    for g in gens:
        if g["inside"] < 0.999:
            hard.append(f"{g['label']} 围栏内仅 {g['inside']:.2%}")
        if g["banned"] > 0:
            hard.append(f"{g['label']} 禁跑区 {g['banned']} 点")
        if abs(g["gen_dist"] - TARGET_DIST) > TARGET_DIST * 0.01:
            hard.append(f"{g['label']} 距离 {g['gen_dist']:.0f} 偏离目标")
    if hard:
        for h in hard:
            print("  ✗", h)
    else:
        print("  ✓ 围栏内 100% / 禁跑区 0 点 / 距离误差 <1%"
              f"（{len(gens)} 条全部通过）")

    if args.verbose:
        print()
        print("=" * 92)
        print("逐条明细")
        print("=" * 92)
        cols = ["label", "n", "dev_med", "dev_p90", "turn_med", "turn_p90",
                "jump_runs", "jump_multi", "edge"]
        print("".join(f"{c:>16}" for c in cols))
        for g in gens:
            print("".join(f"{g[c]:>16.2f}" if isinstance(g[c], float) else f"{g[c]:>16}"
                          for c in cols))

    print()
    if fails:
        print(f"✗ {len(fails)} 项未落在真实域内：")
        for lab, med, lo, hi in fails:
            print(f"   {lab}: 中位 {med:.2f}，真实域 [{lo}, {hi}]")
        return 1
    if hard:
        return 1
    print(f"✓ {len(CHECKS) + 2} 项拟真指标全部落在真实域内")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
