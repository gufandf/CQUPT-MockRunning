#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
compare_real_vs_gen.py —— 把 TrackGen（Python 镜像）生成的轨迹和真实轨迹逐项比对。

这是"完善跑步脚本逻辑"的尺子：
  左边是 5 份真实 GPX 的统计骨架，右边是同一场地、同一配速下生成的轨迹。
  两列的差就是模型要补的东西。

跑法：
    python refs/compare_real_vs_gen.py                 # 全部场地 × 毛刺开/关
    python refs/compare_real_vs_gen.py --pace 330      # 指定配速
"""

from __future__ import annotations

import argparse
import os
import statistics as st
import sys
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import gpx_metrics as M                      # noqa: E402
from verify_pace_profile import (            # noqa: E402
    generate, dedupe, measure, point_in_poly, fmt_pace as _fp,
)

# 真实轨迹 → 用在哪个围栏上
# 只按记录名里的 placeCode 认场地，不硬编码学号（个人信息不进被跟踪文件）
PLACE_CODE_TO_FIELD = {
    "T1014": "NING_JING",
    "T1001": "FENG_HUA",
    "T1005": "TAI_JI",
}


def field_of(name: str) -> str | None:
    for code, field in PLACE_CODE_TO_FIELD.items():
        if f"_{code}_" in name:
            return field
    return None

FIELDS = {
    "FENG_HUA": {
        "name": "风华运动场",
        "fence": [(29.532692, 106.608105), (29.532871, 106.608176),
                  (29.533363, 106.608104), (29.533592, 106.608107),
                  (29.533707, 106.607374), (29.533713, 106.607166),
                  (29.533627, 106.607020), (29.533197, 106.607033),
                  (29.532749, 106.607017), (29.532289, 106.607043),
                  (29.532065, 106.607032), (29.531956, 106.607445),
                  (29.531947, 106.607814), (29.531952, 106.607986),
                  (29.532145, 106.608111), (29.532466, 106.608101),
                  (29.532692, 106.608105)],
        "exclude": [[(29.532769, 106.607824), (29.533107, 106.607843),
                     (29.533293, 106.607835), (29.533417, 106.607734),
                     (29.533403, 106.607527), (29.533345, 106.607373),
                     (29.533219, 106.607323), (29.533012, 106.607303),
                     (29.532650, 106.607330), (29.532462, 106.607380),
                     (29.532362, 106.607479), (29.532323, 106.607624),
                     (29.532385, 106.607769), (29.532528, 106.607824),
                     (29.532717, 106.607841)]],
    },
    "TAI_JI": {
        "name": "太极运动场",
        "fence": [(29.533933, 106.608884), (29.531893, 106.608970),
                  (29.532019, 106.610646), (29.533979, 106.610558)],
        "exclude": [[(29.533214, 106.609447), (29.533071, 106.609473),
                     (29.532917, 106.609468), (29.532626, 106.609486),
                     (29.532506, 106.609612), (29.532533, 106.610029),
                     (29.532691, 106.610093), (29.533146, 106.610060),
                     (29.533421, 106.610058), (29.533549, 106.609690),
                     (29.533457, 106.609455)]],
    },
    "NING_JING": {
        "name": "宁静苑",
        "fence": [(29.530657, 106.606709), (29.530609, 106.608438),
                  (29.529696, 106.608317), (29.529679, 106.606648),
                  (29.530394, 106.606416)],
        "exclude": [],
    },
}


def gen_points(field_key, pace, dist_m, seed, burrs):
    """跑 TrackGen 镜像，转成 metrics 需要的 (lat, lon, ts) 形式。"""
    f = FIELDS[field_key]
    fence = dedupe(f["fence"])
    exc = [dedupe(e) for e in f["exclude"]]
    pts, _, real, window, _ = generate(
        fence, exc, pace, dist_m, seed=seed, burrs=burrs)
    t0 = datetime(2026, 1, 1, tzinfo=timezone.utc)
    out = [(p["lat"], p["lon"], (t0 + timedelta(seconds=p["sec"])).timestamp())
           for p in pts]
    return out, fence, exc, window


# 指标名 → 中文标签（打印用）
LABELS = [
    ("n", "点数"), ("dist", "距离m"), ("pace", "配速s"),
    ("step_med", "步长中位"), ("step_p90", "步长p90"), ("step_p99", "步长p99"),
    ("step_max", "步长max"), ("step_cv", "步长CV"),
    ("stall_frac", "停顿占比"), ("stall_runs", "停顿段数"), ("stall_med", "停顿中位s"),
    ("stall_max", "停顿最长s"),
    ("jump_runs", "跳点簇"), ("jump_multi", "多点簇"),
    ("turn_med", "转角中位"), ("turn_mean", "转角均值"),
    ("turn_p75", "转角p75"), ("turn_p90", "转角p90"), ("turn_p99", "转角p99"),
    ("dev_med", "偏移中位"), ("dev_p75", "偏移p75"), ("dev_p90", "偏移p90"),
    ("dev_p99", "偏移p99"), ("dev_max", "偏移max"),
    ("dev_frac_15", "超1.5m占比"),
    ("ac_speed1", "速ρ1"), ("ac_speed5", "速ρ5"), ("ac_speed10", "速ρ10"),
    ("ac_dev1", "偏ρ1"), ("ac_dev5", "偏ρ5"), ("ac_dev10", "偏ρ10"),
    ("heads", "航向分箱"), ("dt_irregular", "间隔异常"),
]


def show(rows_left, rows_right, title_left, title_right):
    print(f"{'指标':<14}{title_left:>14}{title_right:>14}{'差':>10}")
    print("-" * 54)
    for key, lab in LABELS:
        a = st.mean([r[key] for r in rows_left]) if rows_left else 0.0
        b = st.mean([r[key] for r in rows_right]) if rows_right else 0.0
        diff = b - a
        flag = ""
        if abs(a) > 1e-9:
            rel = diff / abs(a)
            if abs(rel) > 0.5 and key not in ("n", "dist"):
                flag = "  ←"
        print(f"{lab:<14}{a:>14.3f}{b:>14.3f}{diff:>+10.3f}{flag}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pace", type=float, default=None,
                    help="统一配速（秒/公里）；不给则用每条真实轨迹自己的配速")
    ap.add_argument("--seed", type=int, default=20261013)
    args = ap.parse_args()

    reals = M.real_tracks()
    if not reals:
        print("没有 refs/real_tracks/*.gpx")
        return 1

    real_rows = []
    for name, place, pts in reals:
        real_rows.append(M.metrics(pts, name))

    for burrs in (True, False):
        gen_rows, gen_meta = [], []
        for name, place, pts in reals:
            field = field_of(name)
            if not field:
                continue
            pace = args.pace or M.metrics(pts)["pace"]
            dist = M.metrics(pts)["dist"]
            gp, fence, exc, window = gen_points(field, pace, dist, args.seed, burrs)
            m = M.metrics(gp, f"{FIELDS[field]['name']}/{burrs}")
            inside = sum(1 for p in gp if point_in_poly(p[0], p[1], fence))
            banned = sum(1 for p in gp if any(point_in_poly(p[0], p[1], e) for e in exc))
            m["inside"] = inside / len(gp)
            m["banned"] = banned
            gen_rows.append(m)
            gen_meta.append((FIELDS[field]["name"], pace, dist, m, window))

        print("=" * 92)
        print(f"毛刺开关 = {'开' if burrs else '关'}　"
              f"（真实 5 条 vs 生成 5 条，同场地同配速同距离）")
        print("=" * 92)
        show(real_rows, gen_rows, "真实", "生成")
        print()
        print(f"{'场地':<12}{'配速':>8}{'距离':>9}{'生成点数':>8}{'围栏内':>8}{'禁跑区':>7}{'配速窗口':>14}")
        for nm, pace, dist, m, window in gen_meta:
            print(f"{nm:<12}{M.fmt_pace(pace):>8}{dist:>8.0f}m{m['n']:>8}"
                  f"{m['inside']:>8.1%}{m['banned']:>7}"
                  f"{M.fmt_pace(window[0]) + '~' + M.fmt_pace(window[1]):>14}")
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
