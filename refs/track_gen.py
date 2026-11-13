#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
track_gen.py —— 轨迹生成脚本（重写版）

    ① 由固定内轨迹生成每圈不重合的轨迹
    ② 给内轨迹加波动、噪声
    ③ 把落在禁区里的点挤到禁区外
    参数按场地分别选择（placeCode → 场地档案；未知场地按可用走廊自动推导）

为什么重写
----------
旧实现（`track_model.generate`）把四件事揉在一起：`choose_ring()` 一边挑内缩比例
一边有机化，`lap_ring()` 每圈重画时又顺手做一次合法性回收，最后 `tolerate()`
在采样阶段"沿 base→q 二分回缩"把越界点收回 —— 收的时候只认 base 点，于是

  · 压进禁跑区的点被**拉回环线上**，而不是被挤到禁区外侧；画出来是"贴禁区边
    一条直边"，和真人绕内场那种外凸弧线完全不同；
  · 参数没有场地概念：同一套 wobble / 噪声 / 野点强度套到走廊只有 7 m 的风华和
    走廊 30 m 的宁静苑上，前者一路贴边（实测离围栏 7 m，真实是 14 m），
    后者又太干净（真实偏移中位 0.9~1.1 m，旧模型 0.44 m）；
  · 圈与圈"不重合"只是随机重画的副产品，没有任何约束保证真的不重合。

现在是**四段串联**，每段只干一件事，段与段之间只靠数据接口相连：

    ① inner_track()        围栏 → 固定内轨迹（内缩 / 长短轴 / 圆角），**与 seed 无关**
    ② LapTrack.lap(k)      在内轨迹上叠"每圈一套"的低频横向场，并**校验圈间距**
    ③ build()              多尺度 AR(1) 底噪 + 稀疏野点（真机 GPS 抽风）
    ④ _place()             落在禁区的点沿最短合法方向**推出去**（不是拉回来）

顺序为什么不能换
----------------
  · ①② 必须在 ③ 之前：波动和噪声都长在"环线坐标系"上，环线没定就无处落笔。
  · ③ 必须在 ④ 之前：噪声会把点推进禁区，先压噪再挤，才能保证挤完之后没人再推回去。
  · ④ 只做位移，且在标定的二分**内部**参与距离计算：标定看到的是最终坐标，
    不会出现"以为距离够了、挤出后其实被裁短"的自欺欺人。

场地参数从哪来（`refs/real_tracks/*.gpx`，5 条，3 个场地，实测）
--------------------------------------------------------------
   场地     走廊(内轨迹→围栏)  走廊(内轨迹→禁区)  真实偏移中位  真实跳点簇  真实转角p90
   宁静苑 T1014     30.5 m           —              0.92/1.12      5/44        32.6/99.2
   风华   T1001      7.2 m         25.4 m           0.40/0.40      6/7         41.4/15.5
   太极   T1005     39.0 m         13.9 m           0.56           2           14.8

三条结论直接决定了"参数要按场地选"：

  1. **走廊是硬上限，不是建议值**。风华只有 7.2 m 可活动，宁静苑有 30 m。
     同一份 wobble 幅度，在宁静苑是"这圈贴内道、下圈贴外道"，在风华就是"一路
     顶在围栏上"（旧模型实测离边 7 m，真实 14 m）。所以所有随机幅度都乘一个
     `corridor_scale = corridor / REF_CORRIDOR`，并被 `cap` 兜住。
  2. **真实轨迹的"毛"程度差两个量级**。风华两条 dev_med 都是 0.40 m，宁静苑
     一条 0.92 一条 1.12，太极 0.56 —— 风华走廊窄，GPS 反而更干净（贴楼少）。
     所以噪声系数与野点密度都进场地档案，不共用一套。
  3. **太极的禁跑区是"贴着一侧"**，内轨迹到禁区只剩 13.9 m，而到围栏还有 39 m：
     这一侧必须留出绕行空间，否则第 ④ 步会把几百个点往外挤，挤成一条新直边。

真实轨迹的其他依据
------------------
  · 轨迹一律逆时针（累计转角 −905° ~ −2849°）→ ① 强制逆时针，不看服务端顶点序。
  · 圈与圈形状互不相关：按圈切开测径向偏差，每圈 std 1.6~5.6 m、最大 4~24 m，
    相邻圈相关系数中位 ≈ 0.1 → ② 每圈重抽一组相位，并显式要求圈间平均横向差
    ≥ `lap_min_sep`，达不到就重抽（最多 LAP_MAX_TRIES 次）。
  · 人贴内道跑，离围栏边很远（中位 14 / 29 / 43 m）→ ① 的内缩量按场地反解值。
  · 遇到内场会**往外绕**，不会沿边滑成直线 → ④ 只允许"离开禁区"的方向。

跑法
    python refs/track_gen.py --verify                  # 硬约束 + 场地参数自检
    python refs/track_gen.py --profiles                # 打印各场地的推导参数
    python refs/track_gen.py --compare                 # 与真实轨迹逐场地比几何指标
    python refs/track_gen.py --plot                    # 画到 run/pipeline_*.png
    python refs/track_gen.py --field 风华运动场 --out run/track.json
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import statistics as st
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

# 几何原语、场地多边形、配速形态复用 track_model：球面几何和配速标定是稳定数学，
# 不该有第二份。重写的是**生成流程**（①~④ 的顺序与场地参数），不是坐标系。
import track_model as T          # noqa: E402

ROOT = os.path.dirname(HERE)
OUT_DIR = os.path.join(ROOT, "run")

# ============================================================ 全局常量

RING_STEP_M = 7.0            # 内轨迹加密步长：再密没有信息量，再稀磨不出圆角
DEFAULT_CORNER_M = 25.0      # 无场地档案时的圆角半径（真实三点定圆 41~58 m）
INSET_EXPAND_STEP = 0.01     # 内轨迹压禁区时的外扩步进

# 圈间横向场的频率骨架（整数圈保证绕回起点首尾闭合）
# 谐波只能取 {次数: 权重}。为什么不是固定一组：环线是"沿法向做偏移"，而法向在
# 直角拐点是不连续的 —— 次数越高、拐角处被揉得越圆。宁静苑是矩形跑道（直道占
# 45%），叠 k=7 会把四个直角磨成椭圆，画出来和真实跑道不是一种东西；而风华、
# 太极本来就是圆角矩形，多几个高频项只是让弯道更"手抖"。
LAP_HARMONIC_WEIGHT = {1: 0.55, 2: 0.45, 3: 0.34, 4: 0.24, 5: 0.16, 7: 0.10}
LAP_HARMONICS_DEFAULT = (1, 2, 3, 5)
LAP_BUMP_COUNT = (2, 4)
LAP_BUMP_WIDTH_M = (10.0, 40.0)
LAP_MAX_TRIES = 48
LAP_SEP_SAMPLES = 128

# 底噪沿用 track_model 标定好的四层 AR(1)（ρ / σ 有真实自相关支撑）
NOISE_RHO = T.NOISE_RHO
NOISE_SIGMA = T.NOISE_SIGMA

# 挤出禁区
EXIT_DIR_STEP_DEG = 6        # 60 个候选方向
EXIT_MARGIN_M = 1.2          # 挤出后留的余量
EXIT_MAX_PUSH_M = 45.0       # 单点最大推出距离，超过说明方向选错

DEFAULT_PACE_TOL = 20.0
MAX_PACE_FIX_ATTEMPTS = 4


# ============================================================ 场地档案

# 参考走廊：所有幅度都按 corridor / REF 缩放，所以走廊 7 m 的场地自动收敛。
REF_CORRIDOR_M = 15.0

# 场地档案（placeCode → 参数）。数值全部来自上面那张实测表 + 走廊实测。
#
#   inset_mult     在 track_model.FIELD_TUNING 的长短轴缩放上再乘一个系数，
#                  用来把"内轨迹离围栏距离"对到真实中位（宁静苑 30.5→29.0、
#                  风华 7.2→14.2、太极 39.0→41.2）。内轨迹是公共坐标系，
#                  这一步必须在 ① 定死，后面几段才有稳定基准。
#   corner_m       圆角半径；宁静苑是矩形跑道（0 = 直角），另两个有中等圆角。
#   lap_wobble_m   ② 每圈横向场的基准幅度（峰-峰再乘 LAP_HARMONIC_WEIGHT）。
#   lap_bump_amp   ② 局部鼓包幅度（绕人 / 抄近道）。
#   lap_min_sep_m  ② 圈间距下限：达不到就重抽这套相位。
#   noise_scale    ③ 底噪系数，叠在 track_model 的 NOISE_SIGMA 上。
#   spur_scale     ③ 野点强度系数。
#   pace_log_scale ③ 对数速度波动的平稳标准差系数（叠在 track_model.PACE_LOG_STD 上）。
#                  真实步长 CV：宁静苑 0.38/1.35、风华 0.40/0.59、太极 0.29 ——
#                  太极那一场几乎匀速，共用一套会把它的起伏放大一倍。
#   micro_stall_scale / long_stall_scale  ③ 停顿密度：真实每场 7~21 段，
#                  风华的 21 段明显多于宁静苑的 8 段。
#   excl_margin_m  ② 单圈横向场离禁区的保留距离（米）。
#   target_dev_med 验收目标：真实记录的横向偏移中位（米）。
FIELD_PROFILE = {
    # 宁静苑（T1014）：矩形跑道、无禁区、走廊 34.7 m 最宽。
    # 真实偏移最"粗"（dev_med 0.92/1.12），跳点也最多（5/44 簇），
    # 步长 CV 0.38/1.35 —— 一条正常、一条走走停停，所以配速波动给得足。
    "T1014": dict(
        inset_mult=1.000, corner_m=0.0,
        # 矩形走廊：围栏在自身长边帧下是 108.2(u) × 195.4(v)，
        # 两条真实记录跑出来的矩形（取 2%/98% 分位抗野点）是：
        #   记录1  u 71.9  v 105.0  → frac 0.665 / 0.537，中心 (-6.5, -9.7)
        #   记录2  u 61.6  v 102.5  → frac 0.569 / 0.525，中心 (-7.6, -12.0)
        # 取两条的中间值。长短边收得不一样多（长边方向两端要留弯道余量），
        # 所以是两个独立比例，不是一个等比内缩。
        ring_shape="rect", rect_frac_u=0.617, rect_frac_v=0.531,
        rect_shift_u=-7.0, rect_shift_v=-11.0,
        # 矩形跑道（直道占 45%）：偏移在每段上恒定，不用整圈正弦 ——
        # 一阶谐波正好把一条直边推成弧，直角立刻看不出来。
        lap_smooth_m=22.0, lap_harmonics=(1, 2, 3), lap_segmented=True,
        # 段幅度 7.5 m：真实相邻圈径向差 1.2~7.5 m（中位 5.8），段值在 ±amp 里
        # 铺开，相邻两圈的典型差才落在这个量级。走廊 34.7 m 装得下。
        # cross 直接归零：正交分量会让直道产生"弓形"（横向偏移 + 纵向微移
        # 组合起来就是一弯曲率），而真实记录的直道是笔直的。
        lap_seg_amp=6.0, lap_seg_cross=0.0, lap_seg_fade=60.0,
        # 幅度对齐真实相邻圈径向差（唯一有 2 圈的记录：中位 5.8 m）。
        # 之前 7.0 摆出来 9.4 m，把四个直角揉成花瓣 —— 直道占比 45% 的场地
        # 承受不了这么大的法向偏移。
        lap_wobble_m=4.2, lap_bump_amp=(2.5, 5.5), lap_min_sep_m=2.0,
        noise_scale=1.35, spur_scale=1.25, target_dev_med=1.00,
        # 慢漂移为主、快抖动中等：真实偏移 p90 2.09~3.01、转角 p90 32.6~99.2，最"毛"
        noise_layers=(1.30, 1.20, 1.05, 1.10),
        pace_log_scale=1.05, micro_stall_scale=1.00, long_stall_scale=1.00,
        note="矩形跑道、无禁区、走廊最宽 → 允许最粗的线和最多野点",
    ),
    # 风华（T1001）：走廊只有 7.9 m，禁区占内场 27.6%，真实偏移最"细"（0.40 m）。
    #
    # 圈间距下限只有 0.55 m，不是"偷懒"：真实两条风华记录的 dev_med 都是 0.40 m，
    # 整条轨迹本来就细成一条线（跑道宽、人少、GPS 干净），要求圈与圈差 2 m
    # 反而会画出真实记录里不存在的"粗带"。
    #
    # 但它的**停顿**最多（21 段 / 最长 21 秒，另一条 7 段）：两次采样是同一块场地
    # 的两个极端，取中位 0.8 让两头都不至于离谱。
    "T1001": dict(
        inset_mult=0.870, corner_m=40.0, lap_smooth_m=12.0,
        # 本来就是圆角矩形，弯道占 86%，高频项只是让弯道更"手抖"。
        # 真实相邻圈径向差 1.2~7.5 m（中位约 4 m），但这条走廊只有 7.9 m，
        # 幅度再大整圈就会被挤到围栏上，所以压到 2.6。
        lap_harmonics=(1, 2, 3, 5, 7), lap_segmented=False,
        lap_seg_amp=0.0, lap_seg_cross=0.0, lap_seg_fade=26.0,
        lap_wobble_m=2.6, lap_bump_amp=(1.2, 2.8), lap_min_sep_m=0.55,
        noise_scale=0.55, spur_scale=0.75, target_dev_med=0.42,
        # 真实 dev_med 0.40、偏移 p90 1.11~1.23：整条线细但抖动不算小
        noise_layers=(0.90, 1.00, 1.15, 1.30),
        pace_log_scale=0.85, micro_stall_scale=1.35, long_stall_scale=1.00,
        note="走廊最窄（7.9 m）→ 幅度全线收紧；但真实停顿最多",
    ),
    # 太极（T1005）：禁区只贴环线的一侧（到禁区 13.9 m，到围栏还有 39 m）。
    # 真实记录最干净：步长 CV 0.29、跳点只 2 簇、转角 p90 14.8°。
    # 禁区那一侧必须留绕行余量，否则 ④ 会把几百个点往外挤成一条新直边。
    "T1005": dict(
        inset_mult=0.948, corner_m=35.0, lap_smooth_m=14.0,
        # 轴向矩形：围栏在自身帧下是 163.2(u 东西) × 225.7(v 南北)，
        # 真实记录跑出来是 89.8 × 158.1 —— **纵向比横向长得多**（1.76 : 1）。
        # 按最小外接矩形等比缩放做不到这一点：那个算法只认"最长边/最短边"，
        # 而太极的最长边随帧旋转（deg=2°），纵向留不出应有的长度（实测只到 132.9，
        # 比真实短 16%，图上就是"压扁了一圈"）。
        # 这两个比例是二分反解出来的：0.530 / 0.698 让生成的 u/v 恰好命中 89.8 / 158.1。
        ring_shape="axis", axis_frac_u=0.530, axis_frac_v=0.698,
        axis_shift_u=-6.0, axis_shift_v=6.6,
        lap_harmonics=(1, 2, 3, 5), lap_segmented=False,
        lap_seg_amp=0.0, lap_seg_cross=0.0, lap_seg_fade=26.0,
        # 真实相邻圈径向差 1.2~7.2 m（中位 5.1），但禁区那条边只剩 13.3 m 走廊
        lap_wobble_m=3.4, lap_bump_amp=(1.6, 3.4), lap_min_sep_m=0.9,
        noise_scale=0.70, spur_scale=0.45, target_dev_med=0.58,
        # 真实转角 p90 只有 14.8°、偏移 p90 1.10：慢摆动保留，快抖动压到最低
        noise_layers=(1.05, 0.90, 0.70, 0.50),
        pace_log_scale=0.72, micro_stall_scale=0.85, long_stall_scale=0.70,
        excl_margin_m=7.0,
        # 环线额外留 3 m 余量：贴着禁区的这一侧如果让噪声自由往外冒，
        # ④ 会连续挤几百个点，挤出来就是一条新的直边。
        ring_excl_margin=3.0,
        note="禁区偏一侧 → 环线留 3 m 余量；几乎匀速、野点最稀",
    ),
}

# 未知场地：没有真实记录可依据，按走廊宽度推导一套保守参数。
AUTO_BASE = dict(
    inset_mult=1.0, corner_m=DEFAULT_CORNER_M, lap_smooth_m=14.0,
    lap_harmonics=LAP_HARMONICS_DEFAULT,
    lap_segmented=False, lap_seg_amp=3.5, lap_seg_cross=1.0, lap_seg_fade=26.0,
    lap_wobble_m=4.0, lap_bump_amp=(2.0, 5.0), lap_min_sep_m=1.4,
    noise_scale=0.85, spur_scale=0.85, target_dev_med=0.65,
    pace_log_scale=0.90, micro_stall_scale=1.00, long_stall_scale=1.00,
    note="自动推导（无真实记录）",
)


def round_ring(ring, corner_m):
    """只磨角、不动直道。

    不能整圈平滑：围栏是带尖角的多边形，整圈滑动平均会把长直道一起磨弯
    （窗口 25 m 时宁静苑直道占比从 12% 掉到 0%，画出来像鸭蛋）。真实记录是
    "长直道 + 中等圆角"，所以只对转角大的那几段做加权平滑。
    """
    n = len(ring)
    if corner_m <= 0 or n < 16:
        return list(ring)
    per = 0.0
    for i in range(n):
        per += T.hav(ring[i][0], ring[i][1], ring[(i + 1) % n][0], ring[(i + 1) % n][1])
    step_m = max(1.0, per / n)
    corners = T._corner_mask(ring, n, step_m)
    if not any(corners):
        return list(ring)
    win = max(1, int(round(corner_m / step_m / 2)))
    cur = list(ring)
    for _ in range(3):
        cur = [
            (
                (sum(cur[(i + k) % n][0] for k in range(-win, win + 1)) / (2 * win + 1))
                if corners[i] else cur[i][0],
                (sum(cur[(i + k) % n][1] for k in range(-win, win + 1)) / (2 * win + 1))
                if corners[i] else cur[i][1],
            )
            for i in range(n)
        ]
    return cur


def _ccw(poly):
    """强制逆时针。真实 5 条记录累计转角全为负，服务端顶点序可顺可逆。"""
    p = list(poly)
    return p if T.signed_area(p) >= 0 else list(reversed(p))


def _edge_dist(p, poly):
    """点到多边形边界的最短距离（米）。"""
    k = math.cos(math.radians(p[0]))
    best = 1e18
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        ax, ay = a[1] * k, a[0]
        bx, by = b[1] * k, b[0]
        px, py = p[1] * k, p[0]
        dx, dy = bx - ax, by - ay
        L = dx * dx + dy * dy
        t = 0.0 if L == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
        best = min(best, T.hav(py, px / k, ay + t * dy, (ax + t * dx) / k))
    return best


def corridor_width(base, fence, excludes):
    """可用走廊：内轨迹到围栏、到最近禁区的距离中位取小者。

    这是所有随机幅度的**硬上限来源**。真实轨迹的离边距（14 / 29 / 43 m）就是
    在这条走廊里落下来的，幅度定得比它大，画出来必然是贴边。
    """
    d_fence = [_edge_dist(p, fence) for p in base]
    if not excludes:
        d_exc = None
        med = st.median(d_fence)
    else:
        d_exc = st.median([min(_edge_dist(p, e) for e in excludes) for p in base])
        med = min(st.median(d_fence), d_exc)
    return med, st.median(d_fence), d_exc


def field_profile(place_code, corridor_m):
    """取该场地的参数档案；未知场地按走廊推导，并把幅度夹进走廊。"""
    base = dict(FIELD_PROFILE.get(place_code) or AUTO_BASE)
    scale = corridor_m / REF_CORRIDOR_M
    # 走廊比参考更宽 → 幅度放大，但最多 2 倍；更窄 → 按比例收紧。
    # 上限 2.0 是宁静苑（scale 2.03）的实测值，再大圈间距会超过真实圈间偏移。
    scale = max(0.25, min(2.0, scale))
    base["corridor_m"] = corridor_m
    base["corridor_scale"] = scale
    if place_code not in FIELD_PROFILE:
        base["lap_wobble_m"] *= scale
        base["noise_scale"] *= scale
        base["spur_scale"] *= scale
        lo, hi = base["lap_bump_amp"]
        base["lap_bump_amp"] = (lo * scale, hi * scale)
        base["lap_min_sep_m"] *= scale
    # 兜底：任何场地的单圈总摆幅不超过走廊的 45%，否则第 ④ 步会挤成直边
    cap = corridor_m * 0.45
    if base["lap_segmented"]:
        # 片段式：单圈摆幅 = 两倍段幅度（段值是 ±amp 均匀分布）
        total = base["lap_seg_amp"] * 2.0
    else:
        harm_sum = sum(LAP_HARMONIC_WEIGHT[k] for k in base["lap_harmonics"])
        total = base["lap_wobble_m"] * harm_sum
    if total > cap:
        base["lap_wobble_m"] *= cap / total
    base.setdefault("lap_shrink", 0.012)
    base.setdefault("ring_shape", "polygon")
    base.setdefault("axis_frac_u", 0.55)
    base.setdefault("axis_frac_v", 0.70)
    base.setdefault("axis_shift_u", 0.0)
    base.setdefault("axis_shift_v", 0.0)
    base.setdefault("rect_frac_u", 0.77)
    base.setdefault("rect_frac_v", 0.56)
    base.setdefault("rect_shift_u", 0.0)
    base.setdefault("rect_shift_v", 0.0)
    base.setdefault("lap_smooth_m", 14.0)
    base.setdefault("lap_segmented", False)
    base.setdefault("lap_seg_amp", 3.5)
    base.setdefault("lap_seg_cross", 1.0)
    base.setdefault("lap_seg_fade", 26.0)
    base.setdefault("noise_layers", (1.0, 1.0, 1.0, 1.0))
    base.setdefault("pace_log_scale", 0.90)
    base.setdefault("micro_stall_scale", 1.00)
    base.setdefault("long_stall_scale", 1.00)
    # 环线离禁区的额外余量：默认 0（不额外退让），只有贴得很近的场地才打开
    base.setdefault("ring_excl_margin", 0.0)
    # 圈间距下限不能超过实际可摆幅的一半，否则永远达不到、每圈都重抽 48 次
    # 圈间距下限必须留得住：它会被拿去和"相邻两圈的偏移差"比，
    # 而相邻两段值之差的中位大约是 2×段幅度的 0.5，所以按 total*0.35 兜底。
    base["lap_min_sep_m"] = min(base["lap_min_sep_m"], total * 0.35)
    base.setdefault("lap_harmonics", LAP_HARMONICS_DEFAULT)
    base["place_code"] = place_code or "?"
    return base


# ============================================================ ① 固定内轨迹

def _local_frame(poly):
    """在围栏质心处建一个"米制平面"帧，返回 (lat0, lon0, k)。"""
    n = len(poly)
    lat0 = sum(p[0] for p in poly) / n
    lon0 = sum(p[1] for p in poly) / n
    return lat0, lon0, 111320.0 * math.cos(math.radians(lat0))


def _to_uv(p, lat0, lon0, k, deg=0.0):
    x = (p[1] - lon0) * k
    y = (p[0] - lat0) * 110540.0
    if deg:
        c, sn = math.cos(math.radians(deg)), math.sin(math.radians(deg))
        return x * c + y * sn, -x * sn + y * c
    return x, y


def _from_uv(u, v, lat0, lon0, k, deg=0.0):
    if deg:
        c, sn = math.cos(math.radians(deg)), math.sin(math.radians(deg))
        x, y = u * c - v * sn, u * sn + v * c
    else:
        x, y = u, v
    return (lat0 + y / 110540.0, lon0 + x / k)


def _rect_frame(fence):
    """围栏在"长边对齐"帧里的 (lat0, lon0, k, deg, u_min, u_max, v_min, v_max)。

    用围栏自己的最小外接矩形定向，是为了让矩形环的四条边和围栏的四条边平行
    —— 宁静苑围栏是切了一角的五边形，但长边方向仍然是 89°，真实记录的矩形
    也正好落在这个方向上。
    """
    lat0, lon0, k = _local_frame(fence)
    xy = [_to_uv(p, lat0, lon0, k) for p in fence]
    best = None
    for deg in range(0, 180):
        c, sn = math.cos(math.radians(deg)), math.sin(math.radians(deg))
        us = [x * c + y * sn for x, y in xy]
        vs = [-x * sn + y * c for x, y in xy]
        w, h = max(us) - min(us), max(vs) - min(vs)
        if best is None or w * h < best[0]:
            best = (w * h, deg, us, vs)
    _area, deg, us, vs = best
    return lat0, lon0, k, deg, min(us), max(us), min(vs), max(vs)


def rect_ring(fence, prof):
    """① 的"矩形走廊"路径：直接构造一个**四条直边 + 四个直角**的矩形环。

    为什么需要单独的路径：服务端给的围栏是手采多边形，宁静苑是个被切掉左下
    角的五边形。对它"整体按比例缩放"得到的仍然是五边形 —— 真实记录里那四条
    直边根本出不来（图上就是一圈圆钝的斜角框）。

    实测（宁静苑，围栏 108×195 m）：
        围栏      u 宽 108.2   v 长 195.4
        真实轨迹  u 宽  83      v 长 107~113
    也就是说跑者跑的是一个 0.77×0.56 的**矩形**，不是围栏的等比缩略图 ——
    长短边收得不一样多（长边方向两端要留出弯道余量）。

    参数（`rect_frac_u` / `rect_frac_v`）就是这两个比例，全部来自真实记录反解。
    """
    lat0, lon0, k, deg, u0, u1, v0, v1 = _rect_frame(fence)
    cu, cv = (u0 + u1) / 2.0, (v0 + v1) / 2.0
    hu = (u1 - u0) / 2.0 * prof["rect_frac_u"]
    hv = (v1 - v0) / 2.0 * prof["rect_frac_v"]
    du = prof.get("rect_shift_u", 0.0)
    dv = prof.get("rect_shift_v", 0.0)
    cu += du
    cv += dv
    corners = [(cu - hu, cv - hv), (cu + hu, cv - hv),
               (cu + hu, cv + hv), (cu - hu, cv + hv)]
    return [_from_uv(u, v, lat0, lon0, k, deg) for u, v in corners]


INNER_RINGS_PATH = os.path.join(HERE, "inner_rings.json")


def load_inner_rings():
    """读手点内轨迹（`refs/track_picker.py` 导出）。

    结构：{placeCode: {"field": ..., "ring": [[lat,lon],...], ...}}
    """
    try:
        with open(INNER_RINGS_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def _picked_ring(place_code):
    """取该场地手点的内轨迹（没有就返回 None）。

    手点优先于任何自动反解：宁静苑是矩形跑道，服务端给的却是切了一角的五边形，
    "四条边到底该落在哪"最终是看一眼真实轨迹图就能定的东西 —— 人点四下比
    推一堆比例系数更可靠，也更好复查（点在哪，一眼就能和真实轨迹比）。
    """
    if not place_code or place_code == "?":
        return None
    entry = load_inner_rings().get(place_code)
    if not entry:
        return None
    ring = entry.get("ring")
    if not ring or len(ring) < 3:
        return None
    return [(float(p[0]), float(p[1])) for p in ring]


def axis_ring(fence, prof):
    """按围栏自身的长短轴帧，直接构造一个轴向缩放的环。

    为什么不继续用 `track_model.scale_axes`：那是"沿最小外接矩形的长短轴各缩放
    一次"，长短轴的定义随最小面积矩形旋转（太极的 deg=2°），**哪个方向是纵向**
    由算法决定，不由人决定。可真实跑道是"南北向长、东西向短"这件事，是要照着
    真实记录对齐的 —— 于是这里显式按帧给两个比例 + 一个平移。

    帧定义（`_rect_frame` 给出）：
        u = 东西向（东正），v = 南北向（北正），原点 = 围栏质心
    """
    lat0, lon0, k, deg, u0, u1, v0, v1 = _rect_frame(fence)
    if deg > 45.0:                      # 让 u 始终贴着"较长的那条边"
        deg -= 90.0
        lat0, lon0, k, deg, u0, u1, v0, v1 = _rect_frame(fence)
    cu, cv = (u0 + u1) / 2.0, (v0 + v1) / 2.0
    hu = (u1 - u0) / 2.0 * prof["axis_frac_u"]
    hv = (v1 - v0) / 2.0 * prof["axis_frac_v"]
    cu += prof.get("axis_shift_u", 0.0)
    cv += prof.get("axis_shift_v", 0.0)
    corners = [(cu - hu, cv - hv), (cu + hu, cv - hv),
               (cu + hu, cv + hv), (cu - hu, cv + hv)]
    return [_from_uv(u, v, lat0, lon0, k, deg) for u, v in corners]


def _inner_polyline(fence, excludes, prof):
    """围栏 → 环线折线（不含随机性）。四条路径，按优先级选。"""
    # 路径 -1：手点内轨迹（track_picker.py 导出）—— 最高优先级
    picked = _picked_ring(prof.get("place_code"))
    if picked is not None:
        picked = _ccw(picked)
        if not T.bad_points(picked, fence, excludes):
            return picked
        # 手点压到禁区 / 越界：不擅自改形状，只提示（自检里会报出来）
        return picked

    # 路径 0：矩形走廊（方形跑道专用，见 rect_ring 的说明）
    if prof.get("ring_shape") == "rect":
        ring = rect_ring(fence, prof)
        if not T.bad_points(ring, fence, excludes):
            return ring
        # 压到禁区 / 出围栏：两个方向一起收，直到合法
        for f in (0.97, 0.94, 0.90, 0.86, 0.82, 0.78, 0.72, 0.66):
            ring = rect_ring(fence, dict(prof, rect_frac_u=prof["rect_frac_u"] * f,
                                         rect_frac_v=prof["rect_frac_v"] * f))
            if not T.bad_points(ring, fence, excludes):
                return ring
        return ring

    # 路径 0.5：轴向矩形（按围栏自身的 东西/南北 帧缩放）
    if prof.get("ring_shape") == "axis":
        ring = axis_ring(fence, prof)
        if not T.bad_points(ring, fence, excludes):
            return ring
        return ring

    code = prof.get("place_code")
    params = T.field_params(code if code != "?" else None, fence)
    if params is not None:
        k_long, k_short, _corner, _note = params[0], params[1], params[2], params[3]
        shift = params[4] if len(params) > 4 else (0.0, 0.0)
        m = prof["inset_mult"]
        ring = T.scale_axes(fence, k_long * m, k_short * m, (shift[0] * m, shift[1] * m))
        if not T.bad_points(ring, fence, excludes):
            return ring
        # 压到禁区：只能往外扩（往里缩更早撞上禁区内场），平移量同比缩小
        f = 1.0
        while f < 1.6:
            f = min(1.6, f + INSET_EXPAND_STEP * 2)
            ring = T.scale_axes(fence, k_long * m * f, k_short * m * f,
                                (shift[0] * m * f, shift[1] * m * f))
            if f >= 1.0 and not T.bad_points(ring, fence, excludes):
                break
        return ring

    # 通用兜底：按"离边 = 半径 × 0.30"反解内缩，压禁区就往外让
    f = 0.55
    ring = T.inset_poly(fence, f)
    while f < 0.999 and T.bad_points(ring, fence, excludes):
        f = min(0.999, f + 0.02)
        ring = T.inset_poly(fence, f)
    return ring


def inner_track(fence, excludes, prof):
    """① 固定内轨迹：一条**与 seed 无关**的基准环（加密到 ~7 m，逆时针）。

    为什么要"固定"：它是 ②③ 的公共坐标系。每圈的差异应该来自 ② 显式叠上去的
    横向场，而不是"环线每次重画"这种副作用 —— 否则调噪声和调圈间距就分不开了。
    """
    ring = _inner_polyline(fence, excludes, prof)
    dense = T.densify(_ccw(ring), RING_STEP_M)
    dense = round_ring(dense, prof["corner_m"])
    # 圆角化会挪动几厘米，收一下确保基准环自身合法
    out = []
    for i, p in enumerate(dense):
        if T.is_valid(p[0], p[1], fence, excludes):
            out.append(p)
        else:
            out.append(T.pull_toward(dense[i], p, fence, excludes))
    return out


# ============================================================ ② 逐圈不重合

def lap_field(lap_index, seed, ring_len_m, prof, segments=None):
    """第 lap_index 圈的偏移场：f(u) → (横向米, 整圈缩放)。按弧长参数化。

    两条形状来源，按场地是否"方"来决定用哪一条：

      · 方走廊（`segments` 非空）：偏移在**每段直道/弯道上恒定**，只在段与段
        的交界处过渡。真实记录里直道就是一根直线，横向整体偏个一两米，不会
        沿直道弯起来 —— 用整圈正弦的话，一阶谐波正好把一条直边推成弧，
        矩形跑道立刻不像矩形。
      · 其余场地：整数圈正弦 + 局部鼓包。弯道占 86% 时这样最自然。

    `segments` 是 [(u0, u1), ...] 归一化弧长区间，由 LapTrack 从内轨迹的
    转角分布里切出来。
    """
    rnd = random.Random(seed * 1_000_003 + lap_index * 7919 + 101)
    wob = prof["lap_wobble_m"]
    const = wob * 0.16 * rnd.uniform(-1.0, 1.0)
    shrink = 1.0 + rnd.uniform(-prof["lap_shrink"], prof["lap_shrink"])

    if segments:
        # 每段一个恒定偏移。为了让"段内恒定、段间连续"同时成立，先把分段
        # 值铺到一张稠密表上，再做环形滑动平均 —— 插值式过渡在接缝处两侧
        # 各算各的，会出现一个台阶（图上看得见），平滑表没有这个问题。
        # 段值用"±幅度、近似对称"的抽法并打乱顺序：纯 uniform 抽到相近值很常见，
        # 两圈在同一条直道上就会贴在一起（实测圈间距只有 0.99 m）。分层抽 + 打乱
        # 能把相邻圈的分差稳定拉开，同时保持"这一圈贴内、下一圈贴外"的观感。
        k = len(segments)
        # 段值用"黄金比相位"铺开：第 k 圈第 i 段的取值 = amp·sin(2π·frac(...))，
        # 相位随 (i, lap_index) 一起推进。
        #
        # 为什么不用离散层：层数有限（5 个），跑到第 6 圈必然和前面某圈撞层，
        # 实测圈间距掉到 0.03 m —— 那两圈在图上就完全叠住了。用连续相位，
        # 任意两圈在同一条直道上几乎不会取到同一个值。
        lat_vals = []
        for i in range(k):
            ph = ((i * 0.6180339887) + lap_index * 0.3819660113) % 1.0
            lat_vals.append(math.sin(2.0 * math.pi * ph) * prof["lap_seg_amp"]
                            * rnd.uniform(0.85, 1.15))
        cross_vals = [rnd.uniform(-1.0, 1.0) * prof["lap_seg_cross"] for _ in segments]
        ng = 2048
        tab_lat = [0.0] * ng
        tab_cross = [0.0] * ng
        for j in range(ng):
            uu = j / ng
            for i, (u0, u1) in enumerate(segments):
                if u0 <= uu <= u1:
                    tab_lat[j] = lat_vals[i]
                    tab_cross[j] = cross_vals[i]
                    break
        # 过渡宽度按**米**换算成表上的点数：lap_seg_fade 是米。
        # 按 u 的比例给会随场地周长改变（260 m 的环上 1.2% 只有 3 m，
        # 段值一跨就是十几米的台阶，图上直接是方波）。
        fade_pts = max(1, int(round(prof["lap_seg_fade"] / max(1e-6, ring_len_m) * ng)))
        tab_lat = _circ_smooth(tab_lat, fade_pts)
        tab_cross = _circ_smooth(tab_cross, fade_pts)

        def f(u):
            u = u % 1.0
            j = int(u * ng) % ng
            return tab_lat[j] + tab_cross[j], shrink

        return f

    harm = [
        (k, wob * LAP_HARMONIC_WEIGHT[k] * rnd.uniform(0.55, 1.25),
         rnd.uniform(0.0, 2.0 * math.pi))
        for k in prof["lap_harmonics"]
    ]
    blo, bhi = prof["lap_bump_amp"]
    bumps = []
    for _ in range(rnd.randint(*LAP_BUMP_COUNT)):
        bumps.append((
            rnd.random(),
            rnd.uniform(*LAP_BUMP_WIDTH_M),
            rnd.uniform(blo, bhi) * (1.0 if rnd.random() < 0.5 else -1.0),
        ))

    def f(u):
        lat = const
        for k, amp, phase in harm:
            lat += amp * math.sin(2.0 * math.pi * k * u + phase)
        for center, width_m, amp in bumps:
            d = abs(u - center)
            d = min(d, 1.0 - d) * ring_len_m
            lat += amp * math.exp(-0.5 * (d / width_m) ** 2)
        return lat, shrink

    return f


def _slerp_bearing(a, b, t):
    """两个方位角之间按角度插值（走短弧）。"""
    d = (b - a + 540.0) % 360.0 - 180.0
    return (a + d * t) % 360.0


def _corner_blend(ring, k=2, turn_deg=25.0):
    """逐点标记"这里是不是折角"：0 = 折角（走径向），1 = 平顺（走法向）。

    判据是前后各 k 个点的方向差。折角处法向无定义，必须改用径向，
    否则 90° 的直角会被掰成圆弧（见 lap() 里的说明）。
    """
    n = len(ring)
    out = [1.0] * n
    for i in range(n):
        a = ring[(i - k) % n]
        b = ring[i]
        c = ring[(i + k) % n]
        d = abs(T.bearing(a[0], a[1], b[0], b[1]) - T.bearing(b[0], b[1], c[0], c[1]))
        d = min(d, 360.0 - d)
        if d > turn_deg:
            out[i] = 0.0
        elif d > turn_deg * 0.4:
            out[i] = 1.0 - (d - turn_deg * 0.4) / (turn_deg * 0.6)
    return out


def _corner_segments(ring, turn_deg=30.0, merge_m=6.0):
    """按**拐角**切段：一段 = 一条边（矩形 → 四条边）。

    不能按"每个顶点一段"：inner_track 会把环加密到 ~7 m 一个点（矩形因此有
    39 个点），按顶点切就是 39 段，段值被摊平、相邻圈分不开（实测圈间距 0.09 m）。
    也不能按 ring_segments 的转角阈值切：它会把矩形切成 8 段（短边被拆成两截）。
    这里直接找"转角 > turn_deg 的点"当顶点，相邻顶点之间就是一条边。
    """
    n = len(ring)
    if n < 3:
        return None
    c = T.cumulative(ring)
    total = c[-1]
    if total <= 0:
        return None
    corners = []
    for i in range(n):
        a, b, cc = ring[(i - 1) % n], ring[i], ring[(i + 1) % n]
        d = abs(T.bearing(a[0], a[1], b[0], b[1]) - T.bearing(b[0], b[1], cc[0], cc[1]))
        if min(d, 360.0 - d) > turn_deg:
            corners.append(i)
    if len(corners) < 3:
        return None
    # 以第一个拐角为起点，相邻拐角之间一段
    start_i = corners[0]
    order = corners[1:] + [corners[0] + n]
    bounds = [c[start_i] / total]
    for idx in order:
        bounds.append(min(1.0, c[idx % n] / total if idx < n else 1.0))
    segs = []
    for a, b in zip(bounds, bounds[1:]):
        if (b - a) * total >= merge_m:
            segs.append((a, b))
    return segs if len(segs) >= 3 else None


def _circ_smooth(vals, win):
    """环形滑动平均（win = 单侧点数，O(n)）。

    旧写法是"每个点再扫一遍窗口"：2048 点的表、60 m 的过渡（≈470 点窗口）
    单次就要约 100 万次加法，而 lap_field 每次重抽相位都要重建一次 —— 实测
    单条轨迹 90 秒里有 90.3 秒花在这个函数上。

    改成前缀和：环形窗口要么是一段连续区间，要么是"尾部 + 开头"两段，
    两种都只需要 2~3 次加法。
    """
    n = len(vals)
    if win <= 0:
        return list(vals)
    if n <= 2 * win + 1:
        # 窗口盖住整张表还绕圈：每个点的窗口都包含全部元素（有的元素出现多次），
        # 等价于"全表均值 + 绕圈修正"。真正的用途里不会出现这种参数，
        # 直接给全表均值，省掉一个容易写错的特例。
        mean = sum(vals) / n
        return [mean] * n
    span = 2 * win + 1
    pre = [0.0] * (n + 1)
    for i, v in enumerate(vals):
        pre[i + 1] = pre[i] + v
    total = pre[n]
    out = [0.0] * n
    for i in range(n):
        lo, hi = i - win, i + win + 1
        if lo >= 0 and hi <= n:
            out[i] = (pre[hi] - pre[lo]) / span      # 不跨 0
        elif lo < 0:
            out[i] = ((total - pre[lo + n]) + pre[hi]) / span   # 尾 + 头
        else:
            out[i] = ((total - pre[lo]) + pre[hi - n]) / span   # 头 + 尾
    return out


def ring_segments(ring, turn_deg=18.0, merge_m=8.0):
    """把环线切成"直道 / 弯道"交替的弧长区间，返回 [(u0, u1), ...]，按 u 递增。

    为什么要切段：横向偏移在直道上应当**恒定**（人沿直道跑，只会整体贴内或
    贴外一两米），只有在弯道才按弧长连续变化。用整圈正弦去偏移矩形跑道时，
    一阶谐波恰好把一条直边推成弧 —— 直角就没了，而真实记录的直道就是直线。

    实现上**不绕圈**：直接把环线从 index 0 开始按点序分成若干段，最后一段的
    终点钳在 1.0，相邻两段在同一条坐标上交接，永远不会出现越界的 u。
    """
    n = len(ring)
    if n < 8:
        return None
    c = T.cumulative(ring)
    total = c[-1]
    if total <= 0:
        return None
    flags = []
    for i in range(n):
        a, b, cc = ring[(i - 3) % n], ring[i], ring[(i + 3) % n]
        d = abs(T.bearing(a[0], a[1], b[0], b[1]) - T.bearing(b[0], b[1], cc[0], cc[1]))
        flags.append(min(d, 360.0 - d) > turn_deg)
    if all(flags) or not any(flags):
        return None

    raw = []
    u0 = 0.0
    cur = flags[0]
    for i in range(1, n):
        if flags[i] != cur:
            raw.append((u0, c[i] / total))
            u0 = c[i] / total
            cur = flags[i]
    raw.append((u0, 1.0))

    # 合并过短的碎段（加密误差会造出很多一两米的假段）
    merged = []
    for a, b in raw:
        if merged and (b - a) * total < merge_m:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))
    if len(merged) > 2 and (merged[0][1] - merged[0][0]) * total < merge_m:
        merged[1] = (merged[0][0], merged[1][1])
        merged.pop(0)
    # 首尾同态 → 并成一段跨 0（用 u0 - 1 表示）
    if len(merged) > 1:
        pass
    return merged if len(merged) >= 3 else None


class LapTrack:
    """② 的成品：按距离取环线的曲线。`locate(dist)` 给出该处的点与航向。

    圈长会因横向场差几米，所以不能"基准圈长 × 圈号"取模（圈边界会一圈圈漂），
    这里按实际圈长逐圈扣减，圈边界永远对得上。
    """

    def __init__(self, base, fence, excludes, seed, prof):
        self.base = base
        self.fence = fence
        self.excludes = excludes
        self.seed = seed
        self.prof = prof
        self._cache = {}
        self._fields = {}
        self._sep = []
        c = T.cumulative(base)
        self.base_len = c[-1]
        self._n = len(base)
        self._clat = sum(p[0] for p in base) / self._n
        self._clon = sum(p[1] for p in base) / self._n
        # 方形场地才切段。
        # 手点矩形 / rect_ring 出来的环只有 4~8 个顶点 —— 直接按**顶点**切，
        # 一条边一段。按"转角阈值"切会把它切成 8 段（每段只占周长 5~20%），
        # 段值被摊平、相邻圈分不开（实测圈间距掉到 0.68 m）。
        if prof["lap_segmented"]:
            if prof.get("ring_shape") == "rect" or len(base) <= 12:
                self._segments = _corner_segments(base)
            else:
                self._segments = ring_segments(base)
        else:
            self._segments = None

    def _sample_field(self, k):
        """抽一套相位并返回 f(u)；与已有各圈的分离度不够就重抽。

        分离度在 n 个等分 u 上比（n 是内轨迹的点数，7 m 一个，够用且便宜），
        求值本身走弧长（见 lap()）。
        """
        n = self._n
        us = [i / n for i in range(n)]
        want = self.prof["lap_min_sep_m"]
        best_f, best_sep = None, -1.0
        for _try in range(LAP_MAX_TRIES):
            f = lap_field(k + _try * 100003, self.seed, self.base_len,
                          self.prof, self._segments)
            offs = [f(u)[0] for u in us]
            sep = float("inf")
            for prev in self._fields.values():
                # 用"偏移差的中位数"而不是均值：分段模式下段数少，均值会把
                # 少数几条直道的巨大差异摊薄，而图上叠不叠在一起看的正是
                # 典型位置的差。
                #
                # 分段模式（方形场地）要按**段内**比：所有点落在 6 个离散段值上，
                # 整圈取中位会大量取到 0（很多段两圈都贴近 0），分不出好坏。
                if self._segments:
                    # 分段模式按"段"比：取所有段差的中位数。
                    # 不能取最小值 —— 段与段之间总有过渡段（const 分量）落进去，
                    # 那个位置两圈天然接近，最小值会被它拉到 0，什么也判不出来。
                    per_seg = []
                    for u0, u1 in self._segments:
                        idx = [i for i in range(n) if u0 <= us[i] <= u1]
                        if len(idx) < 3:
                            continue
                        per_seg.append(abs(sum(offs[i] for i in idx) / len(idx)
                                          - sum(prev[i] for i in idx) / len(idx)))
                    d = st.median(per_seg) if per_seg else 0.0
                else:
                    d = st.median([abs(offs[i] - prev[i]) for i in range(n)])
                if d < sep:
                    sep = d
            if sep > best_sep:
                best_sep, best_f = sep, f
            if sep >= want:
                break
        self._fields[k] = [best_f(u)[0] for u in us]
        self._sep.append(best_sep if math.isfinite(best_sep) else 0.0)
        return best_f

    def lap(self, k):
        """第 k 圈（0 开始）的加密折线与累计弧长。

        横向场**按弧长求值**，不是按顶点下标。这一条是硬要求：内轨迹是 7 m
        一个点（宁静苑只有 42 个顶点），k=7 的谐波在 42 个采样点上直接混叠，
        画出来是一条乱扭的面条（早期版本就是这个现象）。现在先加密到 1 m，
        每个点用自己的归一化弧长 u 求场，空间上就一定是连续的。

        `_sample_field` 也因此改成在粗网格（n 个点，n 是本圈的实际点数）上抽
        相位、比对圈间距；求值走弧长，两者分工不同。
        """
        if k in self._cache:
            return self._cache[k]
        base = self.base
        dense0 = T.densify(_ccw(base), 1.0)
        cum0 = T.cumulative(dense0)
        total = max(1e-6, cum0[-1])
        field = self._sample_field(k)
        m = len(dense0)

        # 偏移方向：弯道用局部法向，直角/折点用"远离质心"的径向。
        #
        # 为什么必须分：直角处的前后点几乎反向，法向在那里没有定义，
        # 两边各自往外一掰就把 90° 折角掰成一段圆弧 —— 宁静苑是矩形跑道
        # （直道 45%），照法向偏 4 m 直接变成圆角矩形，和真实形状不是一种东西。
        # 径向偏移在折点上是连续且"只放大不扭曲"的，正好保住折角。
        flat = _corner_blend(dense0)
        poly = []
        for i, p in enumerate(dense0):
            u = cum0[i] / total
            off, shrink = field(u)
            q = (self._clat + (p[0] - self._clat) * shrink,
                 self._clon + (p[1] - self._clon) * shrink)
            hdg = T.bearing(dense0[(i - 1) % m][0], dense0[(i - 1) % m][1],
                            dense0[(i + 1) % m][0], dense0[(i + 1) % m][1])
            brg = (hdg + 90.0) % 360.0
            if flat[i] < 1.0:
                rad = T.bearing(self._clat, self._clon, p[0], p[1])
                brg = _slerp_bearing(brg, rad, 1.0 - flat[i])
            poly.append(T.offset(q[0], q[1], off, brg))

        # 平滑窗口：方走廊只做"去毛"级别（3 m），弯道场地才用 lap_smooth_m。
        #
        # 宁静苑是矩形跑道，22 m 的窗口会把 90° 折角磨成一段圆弧 —— 图上直接
        # 变成圆角矩形，和真实形状不是一回事。分段偏移已经在直道上做恒定处理，
        # 这里只需要抹掉加密带来的细小锯齿。
        win = 3.0 if self._segments else self.prof["lap_smooth_m"]
        dense = _smooth_ring(poly, win)
        dense = self._clear(dense)
        cum = T.cumulative(dense)
        self._cache[k] = (dense, cum, cum[-1])
        return self._cache[k]

    def _clear(self, dense):
        """只处理"这一圈的形状本身压进禁区"的点；噪声推进来的点交给 ④。

        分工是刻意的：
          · 这里的 margin 是 0 —— 环线只要不踩进禁区就行，不再额外留余量。
            留多了会让整圈被顶离内场，等于把"绕内场的外凸弧"抹平成一条直边。
          · 噪声（③）把点推进禁区，属于"点级"的越界，统一由 ④ squeeze_out 处理，
            那一步只挪这一个点、方向取最短合法出路，形状代价最小。
        """
        excludes = self.excludes
        if not excludes:
            return dense
        ring_margin = self.prof.get("ring_excl_margin", 0.0)
        m = len(dense)
        nb = self._n
        out = []
        for i, p in enumerate(dense):
            q = p
            if _hit_exclude(p, excludes) is not None or (
                    ring_margin > 0 and any(_edge_dist(p, e) < ring_margin for e in excludes)):
                q = clear_of_excludes(p, self.fence, excludes, ring_margin)
                if not T.point_in_poly(q[0], q[1], self.fence):
                    # 只削这一段位移，保住这一圈的形状
                    anchor = self.base[int(i / m * nb) % nb]
                    q = T.pull_toward(anchor, q, self.fence, excludes)
            out.append(q)
        return out

    def locate(self, dist):
        k, d = 0, dist
        while True:
            dense, cum, length = self.lap(k)
            if d <= length or k > 5000:
                return T.point_at(dense, cum, min(d, length))
            d -= length
            k += 1

    def separations(self):
        """各圈与"之前所有圈"的最小平均横向差（米），第 0 圈记为 0。"""
        return list(self._sep)

    def min_separation(self):
        vals = [s for s in self._sep[1:]] if len(self._sep) > 1 else [0.0]
        return min(vals) if vals else 0.0


# ============================================================ ④ 挤出禁区

def _hit_exclude(p, excludes):
    for e in excludes:
        if T.point_in_poly(p[0], p[1], e):
            return e
    return None


def _exit_distance(p, poly, brg, max_m):
    """沿 brg 走出 poly 需要多远（二分到亚厘米）。出不去返回 None。"""
    t, prev = 0.5, 0.0
    while t <= max_m:
        q = T.offset(p[0], p[1], t, brg)
        if not T.point_in_poly(q[0], q[1], poly):
            lo, hi = prev, t
            for _ in range(18):
                mid = (lo + hi) / 2.0
                qq = T.offset(p[0], p[1], mid, brg)
                if T.point_in_poly(qq[0], qq[1], poly):
                    lo = mid
                else:
                    hi = mid
            return hi
        prev = t
        t *= 1.7
    return None


def squeeze_out(p, fence, excludes, margin=EXIT_MARGIN_M):
    """④ 把落在禁区里的点**挤出去**：扫一圈方向，取最短的合法出路。

    为什么不沿用"沿 base→p 拉回"：那样点被拽回环线，禁区边上出现一条笔直贴边
    轨迹；真人遇到内场是往外绕弧。这里只问一件事 —— 哪个方向最省距离、推出去
    之后仍在围栏内且不再碰任何禁区。

    `margin` 是推出去之后要留的余量（米）：④ 用 1 m 级别，② 的整圈避让用带余量
    的大值（见 EXCL_MARGIN 档案），这样环线本身就不会贴着禁区画边。

    返回 (新点, 推出距离)；无路可走返回 (原点, 0.0)。
    """
    hit = _hit_exclude(p, excludes)
    if hit is None:
        return p, 0.0
    best = None
    for deg in range(0, 360, EXIT_DIR_STEP_DEG):
        brg = float(deg)
        t = _exit_distance(p, hit, brg, EXIT_MAX_PUSH_M)
        if t is None:
            continue
        q = T.offset(p[0], p[1], t + margin, brg)
        if not T.point_in_poly(q[0], q[1], fence):
            continue
        if _hit_exclude(q, excludes) is not None:
            continue
        if best is None or t < best[0]:
            best = (t, q)
    if best is None:
        return p, 0.0
    return best[1], T.hav(p[0], p[1], best[1][0], best[1][1])


def clear_of_excludes(p, fence, excludes, margin):
    """把点挪到"围栏内、且离所有禁区 ≥ margin"。已经满足就原样返回。

    循环做的事和 squeeze_out 一样（最短出路），只是一直推到 margin 之外 ——
    给 ② 的整圈避让用，也用作 ④ 的兜底。
    """
    q = p
    for _ in range(3):
        if not T.point_in_poly(q[0], q[1], fence):
            q = T.clamp_to_fence(q[0], q[1], fence)
        near = None
        for e in excludes:
            if T.point_in_poly(q[0], q[1], e) or _edge_dist(q, e) < margin:
                near = e
                break
        if near is None:
            return q
        # 朝"远离该禁区质心"的方向退，退到 margin 之外；同时保证不出围栏
        clat = sum(t[0] for t in near) / len(near)
        clon = sum(t[1] for t in near) / len(near)
        brg = T.bearing(clat, clon, q[0], q[1])
        need = margin + EXIT_MARGIN_M - _edge_dist(q, near)
        step_ok = 0.0
        for i in range(1, 25):
            cand = T.offset(q[0], q[1], need + i * 0.4, brg)
            if not T.point_in_poly(cand[0], cand[1], fence):
                break
            if _hit_exclude(cand, excludes) is None and _edge_dist(cand, near) >= margin:
                step_ok = i
                q = cand
                break
        if step_ok == 0.0:
            out, push = squeeze_out(q, fence, excludes, margin)
            if push <= 0.0:
                return q
            q = out
    return q


def _smooth_ring(poly, win_m):
    """环形滑动平均。窗口按**米**给（传给它的 poly 是 1 m 一个点）。

    为什么按米：内轨迹的原始点间距是 7 m，如果按点数给窗口，同一个参数在不同
    场地上磨出的尺度完全不同。按米给，三块场地的平滑尺度才可比。
    """
    n = len(poly)
    win = max(1, int(round(win_m)))
    span = 2 * win + 1
    if n <= span:
        return list(poly)
    out = []
    for i in range(n):
        la = 0.0
        lo = 0.0
        for k in range(-win, win + 1):
            q = poly[(i + k) % n]
            la += q[0]
            lo += q[1]
        out.append((la / span, lo / span))
    return out


def _place(base, q, fence, excludes, stats):
    """把噪声点放到最终位置：先收围栏，再挤禁区。顺序不能反。

    围栏在前：挤出去的方向必须是"围栏内"的方向，所以先确定点在场内。
    围栏这一步只削噪声那一截（沿 base→q 二分），保住毛刺方向。
    """
    if not T.point_in_poly(q[0], q[1], fence):
        anchor = base if T.point_in_poly(base[0], base[1], fence) else T.rescue_point(base, fence, excludes)
        if anchor is not None and T.point_in_poly(anchor[0], anchor[1], fence):
            q = T.pull_toward(anchor, q, fence, ())
        else:
            q = T.clamp_to_fence(q[0], q[1], fence)

    if _hit_exclude(q, excludes) is not None:
        stats["squeezed"] += 1
        q2, push = squeeze_out(q, fence, excludes)
        if push <= 0.0:
            stats["stuck"] += 1
            q2 = T.rescue_point(q, fence, excludes) or q
            push = T.hav(q[0], q[1], q2[0], q2[1])
        q = q2
        if push > 0:
            stats["push_m"].append(push)
    return q


# ============================================================ ③ 波动（配速形态）

def pace_weights(n, seed, prof):
    """逐秒速度权重，均值恒为 1。波动强度按场地收放。

    为什么也要按场地：真实记录的步长 CV 差得很远 —— 宁静苑 0.38 / 1.35（一条
    正常、一条走走停停），风华 0.40 / 0.59，太极只有 0.29（那一场几乎匀速）。
    共用一套 `track_model.PACE_LOG_STD = 0.36` 会让太极的起伏比真实大一倍。

    做法：对数 AR(1) 的平稳标准差乘 `pace_log_scale`，疲劳 / 起跑 / 收尾保留
    （那是生理性的，场地无关），停顿按 `micro_stall_scale` 缩放密度。
    """
    if n < 4:
        return [1.0] * n
    rnd = random.Random(seed * 7919 + 13)

    std = T.PACE_LOG_STD * prof["pace_log_scale"]
    half = std * math.sqrt(1.0 - T.PACE_RHO ** 2) * math.sqrt(3.0)
    g = [0.0] * n
    g[0] = rnd.gauss(0.0, std)
    for i in range(1, n):
        nz = (rnd.uniform(-1.0, 1.0) + rnd.uniform(-1.0, 1.0)) / 1.4142
        g[i] = T.PACE_RHO * g[i - 1] + nz * half

    for i in range(n):
        t = i / (n - 1) if n > 1 else 0.0
        g[i] -= math.log(1.0 + T.FATIGUE * t)
        if i < T.START_RAMP_SEC:
            ramp = 1.0 - (1.0 - T.START_RAMP) * (1.0 - i / T.START_RAMP_SEC)
            g[i] += math.log(ramp)
        if i >= n - T.FINISH_KICK_SEC:
            g[i] += math.log(T.FINISH_KICK)

    w = [math.exp(v) for v in g]
    for at, ln in stall_windows(n, seed, prof):
        for k in range(at, min(n, at + ln)):
            w[k] *= T.STALL_FACTOR

    mean = sum(w) / n
    if mean > 0:
        w = [x / mean for x in w]
    return w


def stall_windows(n, seed, prof):
    """停顿区间。真实是一堆 1~2 秒的顿点 + 0~1 次长停顿，密度按场地缩放。"""
    rnd = random.Random(seed * 104729 + 31)
    out = []
    lo = max(2, int(n * 0.02))
    hi = max(lo + 1, int(n * 0.98))
    count = int(round(n / 60.0 * T.MICRO_STALL_PER_MIN * prof["micro_stall_scale"]))
    for _ in range(max(1, count)):
        ln = rnd.randint(*T.MICRO_STALL_LEN)
        at = rnd.randrange(lo, max(lo + 1, hi - ln))
        out.append((at, ln))
    if rnd.random() < T.LONG_STALL_PROB * prof["long_stall_scale"]:
        ln = rnd.randint(*T.LONG_STALL_LEN)
        at = rnd.randrange(lo, max(lo + 1, hi - ln))
        out.append((at, ln))
    out.sort()
    merged = []
    for at, ln in out:
        if merged and at <= merged[-1][0] + merged[-1][1]:
            a, b = merged[-1]
            merged[-1] = (a, max(b, at + ln - a))
        else:
            merged.append((at, ln))
    return merged


# ============================================================ ③ 采样（波动 + 噪声）

def _spurs(n, seed, intensity, prof):
    """稀疏野点：真机 GPS 在多径 / 树荫 / 贴楼时的突发跳点。"""
    return T.make_spurs(n, seed, intensity * prof["spur_scale"])


def build(track, n, weights, spurs, target_m, noise, seed, fence, excludes, prof):
    """固定点数 n，按配速权重铺路程，叠底噪与野点，再把点挤出禁区。

    `measure(step)` 只依赖 step；野点与 AR 噪声的位置只依赖下标与 seed，
    所以对 step 单调，可以直接二分反解地面步长 —— 输出距离精确等于 target_m，
    配速也就精确等于目标配速。
    """
    spur_lat, spur_fwd = spurs
    levels = len(NOISE_RHO)
    noise_eff = noise * prof["noise_scale"]
    # 四层 AR(1) 各有自己的时间常数（0.90 / 0.70 / 0.48 / 0.25，对应十几秒到一秒级）。
    # 场地档案可以单独收放其中某几层：太极的真实记录"慢摆动有、快抖动很少"
    # （转角 p90 只有 14.8°，而风华 15.5~41.4、宁静苑 32.6~99.2），
    # 只调总强度改不了这个比例 —— 必须分层。
    layer_w = prof["noise_layers"]
    lat_out = [0.0] * n
    lon_out = [0.0] * n
    pew_out = [0.0] * n
    stats = {"squeezed": 0, "stuck": 0, "push_m": []}

    def emit(step):
        rnd = random.Random(seed)
        perr = [0.0] * levels
        aerr = [0.0] * levels
        travelled = 0.0
        for i in range(n):
            p, hdg = track.locate(travelled)
            perr_t, aerr_t = spur_lat[i], spur_fwd[i]
            for c in range(levels):
                perr[c] = (NOISE_RHO[c] * perr[c]
                           + rnd.uniform(-1.0, 1.0) * noise_eff * NOISE_SIGMA[c] * layer_w[c])
                aerr[c] = (NOISE_RHO[c] * aerr[c]
                           + rnd.uniform(-1.0, 1.0) * noise_eff * NOISE_SIGMA[c] * layer_w[c])
                perr_t += perr[c]
                aerr_t += aerr[c] * 0.45
            o1 = T.offset(p[0], p[1], perr_t, (hdg + 90.0) % 360.0)
            q = T.offset(o1[0], o1[1], aerr_t, hdg)
            lat_out[i], lon_out[i] = _place(p, q, fence, excludes, stats)
            pew_out[i] = perr_t
            travelled += step * weights[i]

        # speed / bearing 从**最终坐标**反推：上报字段与轨迹严格自洽
        rnd_acc = random.Random(seed * 31 + 7)
        out = []
        for i in range(n):
            j = i + 1 if i < n - 1 else i - 1
            seg = T.hav(lat_out[i], lon_out[i], lat_out[j], lon_out[j])
            brg = (T.bearing(lat_out[i], lon_out[i], lat_out[j], lon_out[j]) if i < n - 1
                   else T.bearing(lat_out[j], lon_out[j], lat_out[i], lon_out[i]))
            acc = 4.5 + abs(pew_out[i]) * 0.30 + rnd_acc.uniform(-1.0, 2.2)
            out.append({
                "lat": lat_out[i], "lon": lon_out[i], "sec": i,
                "speed": round(seg, 2),
                "bearing": round(brg, 1),
                "acc": round(min(18.0, max(3.0, acc)), 1),
                "step": seg,
            })
        return out

    def measure(pts):
        return sum(T.hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
                   for i in range(len(pts) - 1))

    if measure(emit(0.0)) >= target_m:
        return emit(0.0), stats
    lo, hi = 0.0, 5.0
    while measure(emit(hi)) < target_m and hi < 1e5:
        hi *= 1.5
    for _ in range(46):
        mid = (lo + hi) / 2.0
        if measure(emit(mid)) < target_m:
            lo = mid
        else:
            hi = mid
    return emit((lo + hi) / 2.0), stats


# ============================================================ 入口

def pace_window(pace_s_km, tol):
    t = abs(tol)
    return (max(60.0, pace_s_km - t), pace_s_km + t)


def measure_dist(pts):
    return sum(T.hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
               for i in range(len(pts) - 1))


def pace_of(pts):
    d = measure_dist(pts)
    return (len(pts) - 1) / (d / 1000.0) if d > 0 else 0.0


def generate(poly, excludes, pace_s_km, target_m, noise=0.6, seed=0,
             pace_tol=DEFAULT_PACE_TOL, place_code=None):
    """与 track_model.generate 同签名，方便旧脚本直接换模型。

    返回 (pts, fence, pace, window, adjusted)。
    """
    pts, fence, pace, win, adj, _diag = generate_detail(
        poly, excludes, pace_s_km, target_m, noise=noise, seed=seed,
        pace_tol=pace_tol, place_code=place_code)
    return pts, fence, pace, win, adj


def generate_detail(poly, excludes, pace_s_km, target_m, noise=0.6, seed=0,
                    pace_tol=DEFAULT_PACE_TOL, place_code=None, profile=None):
    """完整版：额外返回诊断（场地参数 / 走廊 / 圈间距 / 挤出统计）。"""
    fence = T.dedupe(poly)
    exc = [T.dedupe(e) for e in (excludes or []) if len(e) >= 3]
    window = pace_window(pace_s_km, pace_tol)

    # ---- 0. 场地档案：先定死这一场的所有幅度，再进流水线
    if profile is None:
        # 先粗算一条内轨迹量走廊（档案里的 inset_mult 只用一次，不影响走廊量级）
        probe = dict(AUTO_BASE, place_code=place_code or "?", lap_shrink=0.012)
        probe_ring = inner_track(fence, exc, probe)
        corridor, to_fence, to_exc = corridor_width(probe_ring, fence, exc)
        profile = field_profile(place_code, corridor)
    profile = dict(profile)
    profile.setdefault("lap_shrink", 0.012)

    # ---- ① 固定内轨迹
    base = inner_track(fence, exc, profile)

    # ---- ② 逐圈不重合
    track = LapTrack(base, fence, exc, seed, profile)

    # ---- ③ 波动 + 噪声 → ④ 挤出禁区（在 build 内部逐点完成）
    n = max(2, int(round(target_m / (1000.0 / pace_s_km))))
    intensity = min(2.5, max(0.0, noise / 0.6))
    pts, stats = [], {"squeezed": 0, "stuck": 0, "push_m": []}
    adjusted = False
    for _ in range(MAX_PACE_FIX_ATTEMPTS):
        weights = pace_weights(n, seed, profile)
        spurs = _spurs(n, seed, intensity, profile)
        pts, stats = build(track, n, weights, spurs, target_m, noise, seed,
                           fence, exc, profile)
        pace = pace_of(pts)
        if window[0] <= pace <= window[1]:
            break
        ratio = pace / pace_s_km if pace_s_km > 0 else 1.0
        nxt = max(2, min(int(round(n / ratio)) if ratio > 0 else n, n * 4 + 8))
        if nxt == n:
            break
        n = nxt
        adjusted = True

    lap_d = lap_overlap_m(track, len(track._cache))
    lap_sep = min(lap_d) if lap_d else 0.0
    diag = {
        "place_code": profile.get("place_code"),
        "profile": profile,
        "base_points": len(base),
        "laps": len(track._cache),
        # 圈间距报告用**折线口径**（相邻两圈折线的最近距离中位），
        # 和自检里打印的"环差"是同一个量 —— 之前这里报的是偏移场口径，
        # 两边数字对不上，看着像"校验过了但其实没测同一件事"。
        "lap_sep_min": lap_sep, 
        "lap_sep": track.separations(),
        "squeezed": stats["squeezed"],
        "stuck": stats["stuck"],
        "push_mean": st.mean(stats["push_m"]) if stats["push_m"] else 0.0,
        "push_max": max(stats["push_m"]) if stats["push_m"] else 0.0,
        "n": n,
        "corridor_m": profile["corridor_m"],
    }
    return pts, fence, pace_of(pts), window, adjusted, diag


# ============================================================ 自检 / 对比 / 画图

def _inside_stats(pts, fence, exc):
    inside = sum(1 for p in pts if T.point_in_poly(p["lat"], p["lon"], fence))
    banned = sum(1 for p in pts if any(T.point_in_poly(p["lat"], p["lon"], e) for e in exc))
    return inside / len(pts), banned


def lap_overlap_m(track, laps):
    """圈与圈在地图上的叠合程度：相邻两圈折线之间的最近距离中位（米）。

    为什么量折线而不是量采样点：采样点是"环线 + 噪声"，两条环线本身差 2 m、
    噪声各 0.5 m，点对点的最近距离会被噪声压到 0.x m，看起来像叠在一起，
    而图上真正决定"看着是粗线还是同心圆"的是**环线**之间的距离。
    """
    rings = []
    for k in range(laps):
        dense, _c, _l = track.lap(k)
        rings.append(dense)
    out = []
    for k in range(laps - 1):
        a, b = rings[k], rings[k + 1]
        cell = 2e-4                      # ≈ 11~22 m，够覆盖一圈的局部
        grid = {}
        for q in b:
            grid.setdefault((int(q[0] / cell), int(q[1] / cell)), []).append(q)
        d = []
        for p in a:
            key = (int(p[0] / cell), int(p[1] / cell))
            cand = []
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    cand.extend(grid.get((key[0] + dx, key[1] + dy), ()))
            if cand:
                d.append(min(T.hav(p[0], p[1], q[0], q[1]) for q in cand))
        if d:
            out.append(st.median(d))
    return out


def cmd_profiles(args):
    print("=" * 100)
    print("场地参数档案（走廊由 ① 内轨迹实测，幅度按走廊缩放并被 cap 兜住）")
    print("=" * 100)
    for field, (fence, exc, code) in T.FIELDS.items():
        _p, _f, _pc, _w, _a, d = generate_detail(fence, exc, args.pace or 330.0, args.dist or 2000.0,
                                                 seed=args.seed[0], place_code=code)
        p = d["profile"]
        total = p["lap_wobble_m"] * sum(LAP_HARMONIC_WEIGHT[k] for k in p["lap_harmonics"])
        print(f"{field:<6} {code}  走廊 {d['corridor_m']:>5.1f} m  "
              f"内轨迹点 {d['base_points']:>3}  圆角 {p['corner_m']:>4.0f} m")
        print(f"        单圈摆幅 {total:>5.2f} m（cap {d['corridor_m'] * 0.45:.1f}）"
              f"  鼓包 {p['lap_bump_amp'][0]:.1f}~{p['lap_bump_amp'][1]:.1f} m"
              f"  圈间距下限 {p['lap_min_sep_m']:.2f} m")
        print(f"        噪声 ×{p['noise_scale']:.2f}  野点 ×{p['spur_scale']:.2f}"
              f"  目标偏移中位 {p['target_dev_med']:.2f} m")
        print(f"        说明：{p['note']}")
    return 0


def cmd_stage4(args):
    """④ 挤禁区的**单独**验证：故意把环线推进禁区，看它会不会被推出来。

    为什么单独测：正常跑一遍时 ④ 往往只碰几个点，甚至 0 个（环线本身就在走廊
    里），单看那行"挤出 0 次"分不清"没触发"和"坏了"。这里人为造越界，专门
    检查两件事：
      1. 处理之后压禁区的点必须归零，且不能因此跑到围栏外；
      2. 位移方向必须是"远离禁区"，不能是"拉回环线" —— 后者会在禁区边上
         留一条直线，正是这个流水线要解决的问题。
    """
    print("=" * 96)
    print("④ 挤禁区 / 单独验证：人为把环线推进禁区，看推出方向与结果")
    print("=" * 96)
    fails = 0
    items = list(T.FIELDS.items())
    if args.quick:
        items = [("太极运动场", T.FIELDS["太极运动场"])]
    for field, (fence, exc, code) in items:
        if not exc:
            continue
        fence = T.dedupe(fence)
        exc = [T.dedupe(e) for e in exc]
        prof = field_profile(code, 13.0)
        prof = field_profile(code, corridor_width(
            inner_track(fence, exc, dict(prof, place_code=code)), fence, exc)[0])
        ring = inner_track(fence, exc, prof)

        clat = sum(p[0] for p in exc[0]) / len(exc[0])
        clon = sum(p[1] for p in exc[0]) / len(exc[0])
        pushed = [T.offset(p[0], p[1], 10.0, T.bearing(p[0], p[1], clat, clon))
                  for p in ring]
        hit_before = sum(1 for p in pushed if _hit_exclude(p, exc) is not None)

        stats = {"squeezed": 0, "stuck": 0, "push_m": []}
        fixed = [_place(p, p, fence, exc, stats) for p in pushed]
        after = sum(1 for q in fixed if _hit_exclude(q, exc) is not None)
        outside = sum(1 for q in fixed if not T.point_in_poly(q[0], q[1], fence))

        moved = [(a, b) for a, b in zip(pushed, fixed)
                 if T.hav(a[0], a[1], b[0], b[1]) > 0.05]
        away = 0
        for a, b in moved:
            d = abs(T.bearing(a[0], a[1], clat, clon) - T.bearing(a[0], a[1], b[0], b[1]))
            if min(d, 360.0 - d) > 90.0:
                away += 1
        ok = (after == 0 and outside == 0 and stats["stuck"] == 0
              and (not moved or away == len(moved)))
        fails += 0 if ok else 1
        print(f"{field:<6} 人为越界 {hit_before:>3} 点 → 处理后 {after:>2} 点，"
              f"出围栏 {outside:>2} 点，位移 {len(moved):>3} 点"
              f"（均 {st.mean(stats['push_m']) if stats['push_m'] else 0:.2f} m，"
              f"最远 {max(stats['push_m']) if stats['push_m'] else 0:.2f} m），"
              f"远离禁区 {away}/{len(moved)} {'✓' if ok else '✗'}")
    print()
    print("④ 全部通过" if fails == 0 else f"{fails} 个场地未通过")
    return 0 if fails == 0 else 1


def cmd_verify(args):
    print("=" * 108)
    print("流水线自检：① 固定内轨迹 → ② 逐圈不重合 → ③ 波动 + 噪声 → ④ 挤出禁区")
    print("=" * 108)
    fields = [("宁静苑", T.FIELDS["宁静苑"])] if args.quick else list(T.FIELDS.items())
    fails = 0
    for field, (fence, exc, code) in fields:
        for seed in args.seed:
            pts, f2, pace, window, _adj, d = generate_detail(
                fence, exc, args.pace or 330.0, args.dist or 2000.0,
                seed=seed, place_code=code)
            inside, banned = _inside_stats(pts, f2, exc)
            dist = measure_dist(pts)
            min_sep = d["lap_sep_min"]
            want_dist = args.dist or 2000.0
            ok = (inside >= 0.9999 and banned == 0
                  and window[0] <= pace <= window[1]
                  and abs(dist - want_dist) <= want_dist * 0.01
                  and (d["laps"] < 2 or min_sep >= args.min_sep))
            fails += 0 if ok else 1
            print(f"{field:<6} s{seed:<9} n={len(pts):>5} {dist:>7.1f}m {T.fmt_pace(pace):>7} "
                  f"围栏{inside:>7.2%} 禁区{banned:>3} 圈{d['laps']} "
                  f"圈间距{min_sep:>5.2f}m "
                  f"挤出{d['squeezed']:>4}(均{d['push_mean']:>4.1f}m) {'✓' if ok else '✗'}")
    print()
    print("全部通过" if fails == 0 else f"{fails} 条不达标")
    return 0 if fails == 0 else 1


def cmd_compare(args):
    """逐条配对比较：每条真实记录用自己的配速与距离生成一条对照轨迹。

    为什么要配对而不是统一 330 s/km：`dev_med` / `turn_med` / `step_cv` 都是
    **随时间**的几何量，同样的噪声强度，配速 4'20" 与 6'54" 出来的转角完全不同。
    真实记录从 4'20" 到 6'54" 全都有，拿一条 5'30" 的生成轨迹去和它们比，
    比出来的差主要来自配速，不是模型。
    """
    import gpx_metrics as M
    import score_model as SM

    reals = M.real_tracks()
    if not reals:
        print("没有 refs/real_tracks/*.gpx")
        return 1

    print("=" * 104)
    print("逐条配对比较（每条真实记录用自己的配速 / 距离生成一条对照轨迹）")
    print("=" * 104)

    fails = 0
    for field, (fence, exc, code) in T.FIELDS.items():
        rows = [r for r in reals if r[1] == field]
        if not rows:
            continue
        print(f"\n{field}（{code}）")
        print(f"  {'真实记录':<22}{'配速':>7}{'距离':>8}{'点数':>6}   "
              f"{'指标':<8}{'真实':>8}{'生成':>8}{'差':>8}")
        for name, _place, rpts in rows:
            rm = M.metrics(rpts)
            pace, dist = rm["pace"], rm["dist"]
            pts, _f, _p, _w, _a, d = generate_detail(
                fence, exc, pace, dist, seed=args.seed[0], place_code=code)
            triple = [(q["lat"], q["lon"], float(q["sec"])) for q in pts]
            gm = M.metrics(triple)
            gj = len(SM.jump_clusters(triple))
            rj = len(SM.jump_clusters(rpts))
            edge_r = SM.edge_gap(rpts, field)
            edge_g = SM.edge_gap(pts, field)
            tag = name.replace(field, "")[-18:]
            print(f"  {tag:<22}{M.fmt_pace(pace):>7}{dist:>7.0f}m{rm['n']:>6}   "
                  f"{'走廊':<8}{d['corridor_m']:>8.1f}{'':>8}")
            for key, lab in (("dev_med", "偏移中位"), ("dev_p90", "偏移p90"),
                             ("turn_med", "转角中位"), ("turn_p90", "转角p90"),
                             ("step_cv", "步长CV")):
                a, b = rm[key], gm[key]
                # 容差：取真实值的 [0.5×, 1.8×]，尾部指标（p90/CV）放宽到 2.2×
                k = 2.2 if key in ("dev_p90", "turn_p90", "step_cv") else 1.8
                ok = a / k <= b <= a * k
                fails += 0 if ok else 1
                print(f"  {'':<22}{'':>7}{'':>8}{'':>6}   {lab:<8}{a:>8.2f}{b:>8.2f}"
                      f"{b - a:>+8.2f} {'✓' if ok else '✗'}")
            ok = gj <= max(2, rj) * 1.6 + 2
            fails += 0 if ok else 1
            print(f"  {'':<22}{'':>7}{'':>8}{'':>6}   {'跳点簇':<8}{rj:>8d}{gj:>8d}"
                  f"{gj - rj:>+8d} {'✓' if ok else '✗'}")
            ok = abs(edge_g - edge_r) <= max(4.0, edge_r * 0.35)
            fails += 0 if ok else 1
            print(f"  {'':<22}{'':>7}{'':>8}{'':>6}   {'离边距':<8}{edge_r:>8.1f}"
                  f"{edge_g:>8.1f}{edge_g - edge_r:>+8.1f} {'✓' if ok else '✗'}")

    print()
    print("全部配对项在容差内" if fails == 0 else f"{fails} 项超出容差")
    return 0 if fails == 0 else 1


def cmd_plot(args):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    matplotlib.rcParams["font.sans-serif"] = [
        "Noto Sans CJK SC", "Noto Sans CJK TC", "WenQuanYi Zen Hei", "DejaVu Sans"]
    matplotlib.rcParams["axes.unicode_minus"] = False

    os.makedirs(OUT_DIR, exist_ok=True)

    def xy(poly, lat0=None, lon0=None):
        if lat0 is None:
            lat0 = sum(p[0] for p in poly) / len(poly)
            lon0 = sum(p[1] for p in poly) / len(poly)
        k = 111320.0 * math.cos(math.radians(lat0))
        return ([(p[1] - lon0) * k for p in poly],
                [(p[0] - lat0) * 110540.0 for p in poly], lat0, lon0)

    fields = list(T.FIELDS.items())
    fig, axes = plt.subplots(len(fields), 3, figsize=(13.5, 4.0 * len(fields)))
    for r, (field, (fence, exc, code)) in enumerate(fields):
        pts, f2, pace, _w, _a, d = generate_detail(
            fence, exc, args.pace, args.dist, seed=args.seed[0], place_code=code)
        tri = [(q["lat"], q["lon"]) for q in pts]
        fx, fy, lat0, lon0 = xy(f2)

        ax = axes[r][0]
        ax.plot(fx + fx[:1], fy + fy[:1], color="#9ca3af", lw=1.0, ls="--")
        for e in exc:
            ex, ey, _a1, _a2 = xy(e, lat0, lon0)
            ax.fill(ex, ey, color="#fca5a5", alpha=0.45)
        px, py, _p1, _p2 = xy(tri, lat0, lon0)
        ax.plot(px, py, color="#2563eb", lw=0.8)
        ax.set_title(f"{field} {code}　{len(pts)} 点 {T.fmt_pace(pace)}", fontsize=9)

        ax = axes[r][1]
        base = inner_track(f2, exc, d["profile"])
        tr = LapTrack(base, f2, exc, args.seed[0], d["profile"])
        bx, by, _b1, _b2 = xy(base, lat0, lon0)
        ax.plot(bx + bx[:1], by + by[:1], color="#111827", lw=1.2, label="① 内轨迹")
        for k in range(min(4, max(2, d["laps"]))):
            dense, _c, _l = tr.lap(k)
            lx, ly, _l1, _l2 = xy(dense, lat0, lon0)
            ax.plot(lx + lx[:1], ly + ly[:1], lw=0.8, alpha=0.85, label=f"② 第{k + 1}圈")
        ax.legend(fontsize=6, loc="upper right")
        _p = d["profile"]
        ax.set_title(f"{field}　单圈摆幅 "
                     f"{_p['lap_wobble_m'] * sum(LAP_HARMONIC_WEIGHT[k] for k in _p['lap_harmonics']):.1f} m"
                     f"　圈间距 ≥{d['lap_sep_min']:.1f} m", fontsize=9)

        ax = axes[r][2]
        for e in exc:
            ex, ey, _e1, _e2 = xy(e, lat0, lon0)
            ax.fill(ex, ey, color="#fca5a5", alpha=0.4)
        ax.plot(px, py, color="#2563eb", lw=0.7)
        ax.set_xlim(min(px) - 12, max(px) + 12)
        ax.set_ylim(min(py) - 12, max(py) + 12)
        ax.set_title(f"{field}　④ 挤出 {d['squeezed']} 点（均 {d['push_mean']:.2f} m）", fontsize=9)

        for c in range(3):
            axes[r][c].set_aspect("equal", adjustable="datalim")
            axes[r][c].grid(True, lw=0.25, alpha=0.4)
            axes[r][c].tick_params(labelsize=6)

    fig.tight_layout()
    path = os.path.join(OUT_DIR, "pipeline_tracks.png")
    fig.savefig(path, dpi=140)
    print(f"[+] {path}")
    return 0


def cmd_compare_plot(args):
    """生成轨迹 vs 真实轨迹的对比图（同一坐标系、同一比例尺）。

    三联图，每行一个场地：
      ① 真实（该场地全部记录，灰）+ 电子围栏/禁区
      ② 生成（同配速同距离，蓝）+ 内轨迹
      ③ 叠图 —— 真实灰、生成蓝，一眼看出"跑的位置对不对、线有多粗"

    为什么必须同坐标系：把两条轨迹各自居中再叠，看起来永远"差不多"；
    只有在围栏的绝对坐标里叠，才能回答"生成的是不是跑在真实那条线上"。
    """
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import gpx_metrics as M

    matplotlib.rcParams["font.sans-serif"] = [
        "Noto Sans CJK SC", "Noto Sans CJK TC", "WenQuanYi Zen Hei", "DejaVu Sans"]
    matplotlib.rcParams["axes.unicode_minus"] = False
    os.makedirs(OUT_DIR, exist_ok=True)

    reals = M.real_tracks()
    by_field = {}
    for name, place, pts in reals:
        by_field.setdefault(place, []).append((name, pts))

    def frame(poly):
        lat0 = sum(p[0] for p in poly) / len(poly)
        lon0 = sum(p[1] for p in poly) / len(poly)
        k = 111320.0 * math.cos(math.radians(lat0))
        return lat0, lon0, k

    def xy(poly, lat0, lon0, k):
        return ([(p[1] - lon0) * k for p in poly],
                [(p[0] - lat0) * 110540.0 for p in poly])

    fields = [f for f in T.FIELDS if f in by_field]
    if args.field in T.FIELDS:
        fields = [args.field]
    rows = len(fields)
    fig, axes = plt.subplots(rows, 3, figsize=(14.0, 4.2 * rows))
    if rows == 1:
        axes = axes.reshape(1, -1)

    for r, field in enumerate(fields):
        fence, exc, code = T.FIELDS[field]
        fence = T.dedupe(fence)
        exc = [T.dedupe(e) for e in exc]
        real_list = by_field[field]

        # 用该场地第一条真实记录的配速 / 距离做对照（和 --compare 同口径）
        rm = M.metrics(real_list[0][1])
        pace = args.pace if args.pace else rm["pace"]
        dist = args.dist if args.dist else rm["dist"]
        pts, f2, rpace, _w, _a, d = generate_detail(
            fence, exc, pace, dist, seed=args.seed[0], place_code=code)

        lat0, lon0, k = frame(f2)
        fx, fy = xy(f2, lat0, lon0, k)
        px, py = xy([(q["lat"], q["lon"]) for q in pts], lat0, lon0, k)
        base = inner_track(f2, exc, d["profile"])
        bx, by = xy(base, lat0, lon0, k)
        rings = []
        for name, rp in real_list:
            rings.append(xy([(q[0], q[1]) for q in rp], lat0, lon0, k))

        def deco(ax, title):
            ax.plot(fx + fx[:1], fy + fy[:1], color="#9ca3af", lw=1.0,
                    ls="--", zorder=2)
            for e in exc:
                ex, ey = xy(e, lat0, lon0, k)
                ax.fill(ex, ey, color="#f87171", alpha=0.18, zorder=1)
            ax.set_title(title, fontsize=9)
            ax.set_aspect("equal", adjustable="datalim")
            ax.grid(True, lw=0.25, alpha=0.35)
            ax.tick_params(labelsize=6)

        ax = axes[r][0]
        for rx, ry in rings:
            ax.plot(rx, ry, color="#334155", lw=0.8, alpha=0.75, zorder=3)
        deco(ax, f"真实 · {field}（{len(rings)} 条记录）")

        ax = axes[r][1]
        ax.plot(bx + bx[:1], by + by[:1], color="#16a34a", lw=1.3, alpha=0.9,
                zorder=3, label="内轨迹")
        ax.plot(px, py, color="#2563eb", lw=0.8, zorder=4,
                label=f"生成 {len(pts)} 点")
        deco(ax, f"生成 · {field} {T.fmt_pace(rpace)} / {measure_dist(pts) / 1000:.2f} km")
        ax.legend(fontsize=7, loc="upper right")

        ax = axes[r][2]
        for rx, ry in rings:
            ax.plot(rx, ry, color="#64748b", lw=0.75, alpha=0.6, zorder=3,
                    label="真实" if rx is rings[0][0] else None)
        ax.plot(px, py, color="#dc2626", lw=0.85, alpha=0.9, zorder=4, label="生成")
        deco(ax, f"叠图 · {field}")
        ax.legend(fontsize=7, loc="upper right")

    fig.tight_layout()
    path = os.path.join(OUT_DIR, "compare_real_gen.png")
    fig.savefig(path, dpi=150)
    print(f"[+] {path}")
    return 0


def cmd_out(args):
    field = args.field or "风华运动场"
    if field not in T.FIELDS:
        print(f"未知场地：{field}，可选 {list(T.FIELDS)}")
        return 1
    fence, exc, code = T.FIELDS[field]
    pts, _f, pace, window, _a, d = generate_detail(
        fence, exc, args.pace or 330.0, args.dist or 2000.0,
        seed=args.seed[0], place_code=code)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    prof = {k: v for k, v in d["profile"].items() if k != "profile"}
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump({"field": field, "placeCode": code, "pace": pace,
                   "distance": measure_dist(pts), "profile": prof,
                   "diag": {k: v for k, v in d.items() if k != "profile"},
                   "points": pts}, fh, ensure_ascii=False, indent=1)
    print(f"[+] {args.out}  {len(pts)} 点 {measure_dist(pts):.1f} m {T.fmt_pace(pace)}"
          f"  圈{d['laps']} 挤出{d['squeezed']}次")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="轨迹生成脚本（重写版，参数按场地选择）")
    # pace/dist 默认不给：给了就"统一按这个跑"，不给就按每条真实记录自己的
    # 配速与距离生成对照轨迹（对比图必须这样，否则比的是配速差异不是模型差异）。
    ap.add_argument("--pace", type=float, default=None, help="统一配速（秒/公里），默认按真实记录")
    ap.add_argument("--dist", type=float, default=None, help="统一距离（米），默认按真实记录")
    # 默认只跑一个 seed：生成一条 ≈0.4~0.9 s，三个场地 × 三个 seed 就是
    # 十几秒的纯等待，而 seed 之间的差别本来就只在随机相位上。
    ap.add_argument("--seed", type=int, nargs="*", default=[20261013])
    ap.add_argument("--min-sep", type=float, default=0.5, help="相邻圈折线的最小间距（米），低于判失败")
    ap.add_argument("--quick", action="store_true",
                    help="只跑宁静苑（调试用，最快）")
    ap.add_argument("--profiles", action="store_true", help="打印各场地推导出的参数")
    ap.add_argument("--verify", action="store_true", help="硬约束自检")
    ap.add_argument("--stage4", action="store_true", help="④ 挤禁区的单独验证")
    ap.add_argument("--compare", action="store_true", help="与真实轨迹逐场地比几何指标")
    ap.add_argument("--plot", action="store_true", help="画图到 run/pipeline_tracks.png")
    ap.add_argument("--compare-plot", dest="compare_plot", action="store_true",
                    help="真实 vs 生成 叠图（run/compare_real_gen.png）")
    # 默认空 = 全部场地。--field 只对 --out 有意义，别让它把对比图过滤成一张。
    ap.add_argument("--field", default="")
    ap.add_argument("--out", default=os.path.join(OUT_DIR, "track.json"))
    args = ap.parse_args(argv)

    if args.profiles:
        return cmd_profiles(args)
    if args.stage4:
        return cmd_stage4(args)
    if args.verify:
        return cmd_verify(args)
    if args.compare:
        return cmd_compare(args)
    if args.compare_plot:
        return cmd_compare_plot(args)
    if args.plot:
        return cmd_plot(args)
    return cmd_out(args)


if __name__ == "__main__":
    raise SystemExit(main())
