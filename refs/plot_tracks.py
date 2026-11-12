#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""plot_tracks.py —— 把 fetch_tracks.py 导出的 GeoJSON 画成叠加图

用法：
    python refs/plot_tracks.py run/tracks.geojson --place T1001 -o run/T1001.png
    python refs/plot_tracks.py run/tracks.geojson -o run/all.png          # 全部场地叠加
"""

from __future__ import annotations

import argparse
import collections
import json
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager

CJK_CANDIDATES = [
    os.path.expanduser("~/.local/share/fonts/宋/宋体.ttf"),
    os.path.expanduser("~/.local/share/fonts/方/方正黑体_GBK.ttf"),
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Light.ttc",
]


def setup_cjk() -> None:
    for p in CJK_CANDIDATES:
        if os.path.exists(p):
            font_manager.fontManager.addfont(p)
            name = font_manager.FontProperties(fname=p).get_name()
            plt.rcParams["font.sans-serif"] = [name]
            plt.rcParams["axes.unicode_minus"] = False
            return


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("geojson")
    ap.add_argument("--place", default="", help="只画某个 placeCode")
    ap.add_argument("-o", "--out", default="tracks.png")
    ap.add_argument("--dpi", type=int, default=140)
    ap.add_argument("--figsize", default="8,8")
    a = ap.parse_args()

    setup_cjk()
    data = json.load(open(a.geojson, encoding="utf-8"))
    feats = data["features"]
    if a.place:
        feats = [f for f in feats if f["properties"]["placeCode"] == a.place]

    by_place = collections.defaultdict(list)
    for f in feats:
        by_place[f["properties"]["placeCode"]].append(f)

    w, h = (float(x) for x in a.figsize.split(","))
    fig, ax = plt.subplots(figsize=(w, h), dpi=a.dpi)

    colors = plt.rcParams["axes.prop_cycle"].by_key()["color"]
    for i, (code, group) in enumerate(sorted(by_place.items())):
        color = colors[i % len(colors)]
        name = group[0]["properties"]["placeName"]
        for f in group:
            cs = f["geometry"]["coordinates"]
            ax.plot([c[0] for c in cs], [c[1] for c in cs],
                    lw=0.7, alpha=0.5, color=color)
        # 图例只画一次
        ax.plot([], [], color=color, lw=1.4,
                label=f"{name} ({code}) × {len(group)}")

    ax.set_aspect("equal")
    ax.set_xlabel("longitude")
    ax.set_ylabel("latitude")
    ax.legend(loc="best", fontsize=9, framealpha=.9)
    ax.grid(alpha=.2)
    ax.set_title(f"{len(feats)} 条历史轨迹叠加")
    fig.tight_layout()
    fig.savefig(a.out)
    print(f"{a.out}  ({len(feats)} 条, {len(by_place)} 个场地)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
