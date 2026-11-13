#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
plot_compare.py —— 把真实轨迹和生成轨迹并排画出来，用眼睛验收。

指标会骗人：偏移 p90 差 0.5m 在数字上是 40%，在图上根本看不出；
反过来，两条曲线指标一样也可能一条像人跑、一条像机器画的。
所以验收顺序是**先看图，再看指标**：图不对直接调，图对了再用
`score_model.py` 守住不许退化。

跑法：
    python refs/plot_compare.py                 # 输出 run/compare_*.png
    python refs/plot_compare.py --pace 330      # 指定配速
"""

from __future__ import annotations

import argparse
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt                    # noqa: E402
import numpy as np                                 # noqa: E402

# 中文字体：不设的话图上全是方块，比没有图还糟
matplotlib.rcParams["font.sans-serif"] = [
    "Noto Sans CJK SC", "Noto Sans CJK TC", "Noto Sans CJK JP",
    "WenQuanYi Zen Hei", "DejaVu Sans",
]
matplotlib.rcParams["axes.unicode_minus"] = False

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import gpx_metrics as M                            # noqa: E402
import track_model as T                            # noqa: E402

ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "run")

# 记录文件名形如 `<学号>_<placeCode>_<时间戳>.gpx`，这里**只按 placeCode 认场地**，
# 不把学号写进代码 —— 学号属于个人信息，不该出现在被跟踪的文件里。
PLACE_CODE_TO_FIELD = {
    "T1014": "宁静苑",
    "T1001": "风华运动场",
    "T1005": "太极运动场",
}


def field_of(name: str) -> str | None:
    """从记录名里认出场地（只依赖 placeCode 那一段）。"""
    for code, field in PLACE_CODE_TO_FIELD.items():
        if f"_{code}_" in name:
            return field
    return None


def to_xy(pts, lat0, lon0):
    """经纬度 → 以 (lat0, lon0) 为原点的米制平面（够看形状）。"""
    k = 111320.0 * np.cos(np.radians(lat0))
    return (np.array([(p[1] - lon0) * k for p in pts]),
            np.array([(p[0] - lat0) * 110540.0 for p in pts]))


def draw(ax, pts, title, color="#3b82f6", lw=0.9):
    lat0 = float(np.mean([p[0] for p in pts]))
    lon0 = float(np.mean([p[1] for p in pts]))
    x, y = to_xy(pts, lat0, lon0)
    ax.plot(x, y, color=color, lw=lw, alpha=0.85, solid_capstyle="round")
    ax.scatter([x[0]], [y[0]], s=26, c="#16a34a", zorder=5, marker="o")
    ax.scatter([x[-1]], [y[-1]], s=26, c="#dc2626", zorder=5, marker="s")
    ax.set_title(title, fontsize=9)
    ax.set_aspect("equal", adjustable="datalim")
    ax.grid(True, lw=0.25, alpha=0.4)
    ax.tick_params(labelsize=7)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pace", type=float, default=330.0)
    ap.add_argument("--dist", type=float, default=2000.0)
    ap.add_argument("--seed", type=int, default=20261013)
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    reals = M.real_tracks()
    by_field = {}
    for name, place, pts in reals:
        by_field.setdefault(field_of(name) or place, []).append((name, pts))

    fields = [f for f in T.FIELDS if f in by_field]
    fig, axes = plt.subplots(len(fields), 3, figsize=(12, 4.0 * len(fields)))
    if len(fields) == 1:
        axes = axes.reshape(1, -1)

    for r, field in enumerate(fields):
        # 真实（同一场地可能有多条，只画第一条）
        real_name, real_pts = by_field[field][0]
        draw(axes[r][0], real_pts, f"真实 · {field}\n（{len(real_pts)} 点）", "#111827")

        fence, exc = T.FIELDS[field]
        gen_pts = T.generate(fence, exc, args.pace, args.dist, seed=args.seed, place_code=field)[0]
        tri = [(p["lat"], p["lon"]) for p in gen_pts]
        draw(axes[r][1], tri, f"生成 · {field}\n（{len(gen_pts)} 点，{T.fmt_pace(args.pace)}）",
             "#2563eb")

        # 叠图：真实 + 生成（以真实轨迹质心对齐）
        lat0 = float(np.mean([p[0] for p in real_pts]))
        lon0 = float(np.mean([p[1] for p in real_pts]))
        rx, ry = to_xy(real_pts, lat0, lon0)
        gx, gy = to_xy(tri, lat0, lon0)
        ax = axes[r][2]
        ax.plot(rx, ry, color="#111827", lw=0.8, alpha=0.55, label="真实")
        ax.plot(gx, gy, color="#dc2626", lw=0.8, alpha=0.55, label="生成")
        ax.set_title(f"叠图 · {field}", fontsize=9)
        ax.set_aspect("equal", adjustable="datalim")
        ax.grid(True, lw=0.25, alpha=0.4)
        ax.tick_params(labelsize=7)
        ax.legend(fontsize=7, loc="upper right")

    fig.tight_layout()
    path = os.path.join(OUT, "compare_tracks.png")
    fig.savefig(path, dpi=140)
    print(f"[+] {path}")

    # 局部放大：只截 90 秒，看"线有多粗"
    fig2, axes2 = plt.subplots(1, 2, figsize=(11, 5))
    field = fields[0]
    real_name, real_pts = by_field[field][0]
    seg = real_pts[100:190]
    draw(axes2[0], seg, f"真实局部 90s · {field}（看线有多粗）", "#111827", lw=1.3)
    fence, exc = T.FIELDS[field]
    gp = T.generate(fence, exc, args.pace, args.dist, seed=args.seed, place_code=field)[0]
    # 生成轨迹的 100~190 秒段
    seg2 = [(p["lat"], p["lon"]) for p in gp[100:190]]
    draw(axes2[1], seg2, "生成局部 90s", "#2563eb", lw=1.3)
    fig2.tight_layout()
    path2 = os.path.join(OUT, "compare_zoom.png")
    fig2.savefig(path2, dpi=150)
    print(f"[+] {path2}")

    # 单圈对比：真实取前 360°，生成取前 360°
    fig3, axes3 = plt.subplots(1, 2, figsize=(11, 5))
    draw(axes3[0], real_pts[:120], f"真实首圈附近 · {field}", "#111827", lw=1.2)
    draw(axes3[1], [(p["lat"], p["lon"]) for p in gp[:120]], "生成首圈附近",
         "#2563eb", lw=1.2)
    fig3.tight_layout()
    path3 = os.path.join(OUT, "compare_lap.png")
    fig3.savefig(path3, dpi=150)
    print(f"[+] {path3}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
