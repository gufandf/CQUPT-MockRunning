#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
track_model.py —— 轨迹生成模型（唯一权威 Python 实现，TrackGen.kt 的 1:1 镜像）

为什么有这份文件
----------------
以前 `verify_pace_profile.py` / `verify_trackgen.py` / `verify_real_areas.py`
各自抄了一份实现，抄着抄着就漂了：噪声强度一边是 (0.50,0.27,0.27,0.33)、
一边是 (0.22,0.10,0.07,0.045)，配速形态一边是「分段常数 + t^1.5 疲劳」、
一边是「配速段 + 线性疲劳 + 4~9 秒停顿」。对着两份不一致的镜像标定参数
等于自欺欺人。现在只有这一份，三个验证脚本都 import 它。

标定依据
--------
`refs/real_tracks/*.gpx` 里 5 条真实跑步记录（3 个场地），量出来的统计骨架：

    指标                    真实（5 条的中位）      旧模型
    ----------------------------------------------------------
    逐秒步长中位             3.14 m                 3.22 m
    步长 CV                  0.40                   0.40
    停顿段数（每场）          11                     3.2      ← 差
    停顿占时长               4%                     0.5%     ← 差
    停顿段长中位 / 最长       1s / 6s                1s / 1.6s ← 差
    逐秒转角中位             6.3°                   8.2°
    逐秒转角 p90             32.6°                  47.8°    ← 差（太毛）
    横向偏移中位             0.56 m                 0.44 m
    横向偏移 p90             1.23 m                 1.42 m
    横向偏移 p99             3.53 m                 3.96 m
    >5m 跳点簇数             6                      10.4     ← 差（太多）
    其中多点多于 1 个的       2.4                    6.8      ← 差
    速度自相关 ρ1/ρ5/ρ10     0.87/0.59/0.30         0.65/0.33/0.29 ← 差
    横向偏移自相关 ρ1/ρ5     0.78/0.03              0.56/0.12
    轨迹方向                 逆时针（5/5）          依多边形顶点序 ← 差

三条最关键的结构结论（都来自真实数据，不是猜的）：

  1. **速度变化基本是一个 AR(1)**，相关系数 ≈ 0.89（τ ≈ 9 秒）。
     真实的中位自相关阶梯 0.87 / 0.83 / 0.75 / 0.59 / 0.40 / 0.30 / 0.18
     和 0.89^lag = 0.89 / 0.79 / 0.70 / 0.56 / 0.39 / 0.31 / 0.17 几乎重合。
     旧模型「每 40~70 秒换一档常数」的阶梯状速度在 lag 5~10 上掉得太快。
  2. **停顿是「很多次 1~2 秒」而不是「几次 4~9 秒」**。真实每场 7~21 段，
     中位 1~2 秒，约占 4% 时长；另有 0~1 次 5~21 秒的长停顿（看手机 / 系鞋带）。
     旧模型 2~3 段、每段 4~9 秒，画出来是「几次突兀的断点」。
  3. **横向偏移是「短突发」不是「长漂移」**。真实偏移自相关从 0.78 迅速掉到
     lag 5 的 0.03 —— 指数衰减做不出这么快，只有 2~3 秒的有限长度事件能做到。
     所以「粗线」的来源是稀疏野点簇，不是分钟级 AR 漂移。

方向硬约束：真实 5 条全部逆时针（累计转角 -905° ~ -2849°）。
国内操场默认逆时针跑，所以环线一律强制成逆时针，不管服务端顶点序。
"""

from __future__ import annotations

import bisect
import math
import random

R_EARTH = 6371008.8

# ============================================================ 可调参数（唯一出处）

# ---- 配速形态 ----
# 速度的对数 AR(1)：ρ 与真实自相关阶梯对齐（见文件头结论 1）
PACE_RHO = 0.89
# 平稳对数标准差。0.36 → 速度 CV ≈ 0.37，真实 0.27~0.41（去掉一条异常）
PACE_LOG_STD = 0.36
# 疲劳：到终点累计慢 10%（真实每圈用时的漂移量级）
FATIGUE = 0.10
# 起步 15 秒还没跑开 / 最后 20 秒冲一下
START_RAMP_SEC = 15
START_RAMP = 0.90
FINISH_KICK_SEC = 20
FINISH_KICK = 1.05

# ---- 停顿 ----
# 微停顿：每 60 秒约 1.1 次、1~2 秒。660 秒 → 约 12 段，真实 7~21
MICRO_STALL_PER_MIN = 1.1
MICRO_STALL_LEN = (1, 2)
# 长停顿：约 65% 的场次出现一次，5~20 秒（真实最长 5~21 秒）
LONG_STALL_PROB = 0.65
LONG_STALL_LEN = (5, 20)
# 停顿期间的速度乘子（≈ 原地站住，但不绝对为 0，GPS 会有微小抖动）
STALL_FACTOR = 0.03

# ---- 横向 / 纵向噪声（四层 AR(1)）----
# 时间常数从「十几秒」到「一秒级」。注意**没有分钟级那一层**：
# 把真实轨迹按圈切开、逐圈算横向偏移的均值，5 条记录全部落在 ±0.3 m 内
# （说明不存在「这圈整体偏左」的圈间偏置），所以分钟级漂移不但没用，
# 还会把所有圈糊成一条粗带。
NOISE_RHO = (0.900, 0.700, 0.480, 0.250)
# 逐层强度。中位偏移由这两档决定：0.24 左右的合成标准差对应 ~0.56 m 的中位偏移，
# 与真实 5 条（0.40~1.12 m）对齐。p90 / p99 的尾巴交给下面的野点，不靠加噪声。
NOISE_SIGMA = (0.060, 0.110, 0.190, 0.380)

# ---- 稀疏野点（毛刺 / 突起）----
# 只看几何、不看配速时的真实画像（5 条的中位）：
#   >5m 跳点簇 6 个，其中多点簇 2 个；偏移 >1.5m 的突发段 24 段（中位长 3s、
#   最长 6s）；偏移 p90 1.24 m、p99 2.82 m、max 4.74 m。
#
# 还有一条更关键的方向结论：真实的大跳点**多数是沿着轨迹方向的**
# （把 >5m 的那一步和上一步比方向，60° 以内算沿轨：5 条记录里 57%~100% 都是）。
# 也就是说真机"抽一下"是往前窜一米多，不是往侧面甩。
# 早期版本把大跳全放在横向上，结果横向偏移 p90 被顶到 1.8 m、突发段 39 个，
# 画出来像一圈毛球 —— 而真实的粗线其实很薄，只是偶尔有一根长刺。
SPUR_BIG_COUNT_PER_660 = 1.6      # 单点大跳 9~16 m：沿轨为主，一步跳出去立刻回来
SPUR_BIG_AMP = (9.0, 16.0)
SPUR_BUMP_COUNT_PER_660 = 1.0     # 4~9 点的绕行包 5~11 m（侧面，像绕人）
SPUR_BUMP_AMP = (5.0, 11.0)
SPUR_HARD_COUNT_PER_660 = 1.6     # 单点 / 两步来回的硬毛刺 3~8 m
SPUR_HARD_AMP = (3.0, 8.0)
SPUR_SOFT_COUNT_PER_660 = 11.0    # 0.8~2.6 m 的小毛刺，把线边揉毛
SPUR_SOFT_AMP = (0.8, 2.6)
# 大跳的方向配比：横向 share 越小越「沿轨」
SPUR_BIG_LATERAL_SHARE = 0.28
SPUR_BUMP_LATERAL = 1.0

# ---- 环线 ----
# 内缩多少，是**照着真实轨迹反解出来的**，不是拍脑袋定的。
#
# 早期版本从 0.90 开始逐档试（"能塞进去就塞进去"），结果轨迹紧贴围栏边缘：
# 离边界只有 7~9 m。而 5 条真实记录的离边距中位是 14.3 / 29.3 / 43.1 m ——
# 人在跑道内圈跑，不会沿着围栏的画线跑。
#
# 把三个场地各自的真实离边距换算成"质心到顶点的平均半径"的倍数，
# 得到的都是 0.30（0.30 / 0.17 / 0.31），也就是说：
#
#     跑者离边的距离 ≈ 场地半径 × 0.30
#
# 反解成顶点缩放系数是 0.49~0.55（三个场地一致），所以用 RING_RADIUS_INSET
# 按半径比例内缩，而不是按顶点比例硬缩放。风华运动场因为有禁跑区、
# 走廊被压窄，实际落在 0.75 那一档 —— 由 ring_by_corridor() 自动算出来。
RING_RADIUS_INSET = 0.30

# 场地特调表：placeCode → (目标内缩系数, 目标离边距米)
#
# 为什么不只靠公式：禁跑区（jpq）会把可用走廊压窄，而"压窄多少"取决于
# 禁跑区在这块场地的具体位置和形状 —— 风华是内场几乎占满、太极是偏一侧。
# 同一个 0.30 半径比例在风华会被禁跑区顶回去（只能到 0.74），在太极却会
# 直接压进禁跑区。与其写一个到处打补丁的自动算法，不如把三个真实场地
# 各自反解出来的值直接列出来 —— 这是实测的，只影响"跑哪条线"。
#
# 数值来源（离边距中位，真实 vs 该档位实测）：
#   T1014 宁静苑   29.3 m  → f=0.50  实测 29.0 m
#   T1001 风华     14.3 m  → f=0.74  实测 14.7 m
#   T1005 太极     43.1 m  → 目标值 43.1，但**禁跑区不允许踩**
#                           → 自动退到最大的合法内缩（f≈0.62 一带）
#
# 第二个数是目标离边距，用于校验；第三个是"是否禁止踩入禁跑区"的硬开关，
# 恒为 True —— 踩进去的点服务端会判 isValid="2" 不计里程，宁可在走廊里
# 少跑一点，也不能让一条记录里出现"不计里程"的段落。
FIELD_TUNING = {
    # placeCode: (长轴缩放, 短轴缩放, 圆角半径 m, 说明)
    #
    # 为什么是"长轴/短轴"两个数，而不是一个内缩系数：
    # 把真实轨迹和围栏各自的最小外接矩形量出来，会发现真人跑的不是
    # 围栏的等比缩略图 —— 长短轴缩放根本不一样：
    #
    #     场地     真实矩形    围栏矩形     长轴比  短轴比
    #     宁静苑    78x115     108x195      0.59   0.72
    #     风华      96x181     112x195      0.93   0.86
    #     太极      97x160     163x226      0.71   0.59
    #
    # 同一个 f 去缩放，长边和短边必然同比例变，怎么做都对不上其中一边。
    # 而"跑道"本来就是这个样子：人沿内道跑，长直道方向能贴得更近，
    # 两端弯道要留出半径，于是两个方向的比值天然不同。
    #
    # 圆角半径取自真实记录的三点定圆曲率半径中位（41 / 58 / 47 m），
    # 再大就把直道也磨弯（宁静苑会从"方"变成"鸭蛋"，直道占比掉到 0%）。
    # 宁静苑：五边形，左端被 P4(-86,+21) 斜切，真实跑道整体偏右偏下。
    #   x 轴缩放 0.59、y 轴缩放 0.81、平移 (+24.8, -13.2) m 全部反解自真实记录。
    "T1014": (0.58, 0.37, 0.0, "宁静苑：矩形，左右不动，上下各收 20",
              (15.0, -10.0)),
    "T1001": (0.93, 0.86, 40.0, "风华：内场占 27.6%，走廊本来就宽", (0.0, 0.0)),
    "T1005": (0.62, 0.52, 35.0, "太极：禁跑区偏一侧，南北向压扁", (0.0, 0.0)),
}

# 场地指纹：(围栏周长 m, 顶点数)。用来在**不知道 placeCode** 时认出场地
# —— 围栏多边形本身就是最好的指纹，周长两两相差 ≥10 m。
FIELD_REF = {
    "T1014": (554, 5),     # 宁静苑
    "T1001": (564, 16),    # 风华运动场
    "T1005": (770, 4),     # 太极运动场
}

RING_INSETS = (0.49, 0.45, 0.40, 0.35, 0.30, 0.25, 0.20, 0.15)
RING_WOBBLE_M = 4.5
RING_STEP_M = 7.0
RING_SMOOTH_WIN = 2
# 弯道圆角半径（米）。真实记录的三点定圆曲率半径中位 41~58 m，
# 围栏本身是尖角多边形，所以必须先按这个量级圆角化，否则画出来是圆角矩形。
RING_CORNER_RADIUS_M = 25.0

# ---- 配速容差 ----
DEFAULT_PACE_TOL = 20.0
MAX_PACE_FIX_ATTEMPTS = 4


# ============================================================ Geo

def hav(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R_EARTH * math.asin(min(1.0, math.sqrt(a)))


def bearing(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    x = math.sin(dl) * math.cos(p2)
    y = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0


def offset(lat, lon, dist, brg):
    p1, l1, brg = math.radians(lat), math.radians(lon), math.radians(brg)
    d = dist / R_EARTH
    p2 = math.asin(math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(brg))
    l2 = l1 + math.atan2(math.sin(brg) * math.sin(d) * math.cos(p1),
                         math.cos(d) - math.sin(p1) * math.sin(p2))
    return math.degrees(p2), math.degrees(l2)


def point_in_poly(lat, lon, poly):
    """与小程序 IsPtInPoly 等价的射线法。"""
    n = len(poly)
    if n < 3:
        return False
    inside = False
    for i in range(n):
        a = poly[i]
        b = poly[0] if i == n - 1 else poly[i + 1]
        la, lo = a[0], a[1]
        lb, lob = b[0], b[1]
        if (la <= lat < lb) or (lb <= lat < la):
            if abs(la - lb) > 0:
                if lo - (lo - lob) * (la - lat) / (la - lb) < lon:
                    inside = not inside
    return inside


def dedupe(poly):
    out = []
    for p in poly:
        t = (p[0], p[1])
        if not out or out[-1] != t:
            out.append(t)
    if len(out) > 1 and out[0] == out[-1]:
        out.pop()
    return out


def is_valid(lat, lon, fence, excludes):
    if fence and not point_in_poly(lat, lon, fence):
        return False
    for e in excludes or ():
        if point_in_poly(lat, lon, e):
            return False
    return True


def signed_area(poly):
    """有向面积：正 = 逆时针，负 = 顺时针（局部平面近似足够）。"""
    if len(poly) < 3:
        return 0.0
    k = math.cos(math.radians(sum(p[0] for p in poly) / len(poly)))
    s = 0.0
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        s += (a[1] * k) * b[0] - (b[1] * k) * a[0]
    return s / 2.0


def inset_poly(poly, f):
    """朝质心按比例缩进，并**强制逆时针**。

    为什么强制：真实 5 条记录的累计转角全是负的（逆时针），而服务端给的
    围栏顶点序可顺可逆 —— 宁静苑那块就是顺时针。照抄顶点序的话，同一场地
    换台设备可能画成顺时针，和真实跑向相反，一眼假。
    """
    clat = sum(p[0] for p in poly) / len(poly)
    clon = sum(p[1] for p in poly) / len(poly)
    scaled = [(clat + (p[0] - clat) * f, clon + (p[1] - clon) * f) for p in poly]
    return scaled if signed_area(scaled) >= 0 else list(reversed(scaled))


# ============================================================ 配速形态

def pace_profile(n, seed):
    """逐秒的「速度权重」，均值恒为 1。

    三层：
      · 对数 AR(1) 漂移（ρ=0.89，τ≈9 秒）—— 真实数据的主要结构
      · 疲劳 + 起跑加速 + 收尾冲
      · 微停顿 / 长停顿

    用对数空间是为了「快 30%」和「慢 30%」对称；线性乘子叠加会让快的那侧
    被均值归一化吃掉，越跑越快的感觉失真。
    """
    if n < 4:
        return [1.0] * n

    rnd = random.Random(seed * 7919 + 13)

    # ---- 对数 AR(1)。平稳启动：g[0] 直接从平稳分布抽
    # uniform(-1,1) 的标准差是 1/√3，所以半宽要按 √3 放大
    half = PACE_LOG_STD * math.sqrt(1.0 - PACE_RHO ** 2) * math.sqrt(3.0)
    g = [0.0] * n
    g[0] = rnd.gauss(0.0, PACE_LOG_STD)
    for i in range(1, n):
        # 用两个 uniform 之和近似正态，保持和 Kotlin（只有 nextDouble）同构
        nz = (rnd.uniform(-1.0, 1.0) + rnd.uniform(-1.0, 1.0)) / 1.4142
        g[i] = PACE_RHO * g[i - 1] + nz * half

    # ---- 疲劳 / 起步 / 收尾（同样在对数空间加减）
    for i in range(n):
        t = i / (n - 1) if n > 1 else 0.0
        g[i] -= math.log(1.0 + FATIGUE * t)
        if i < START_RAMP_SEC:
            ramp = 1.0 - (1.0 - START_RAMP) * (1.0 - i / START_RAMP_SEC)
            g[i] += math.log(ramp)
        if i >= n - FINISH_KICK_SEC:
            g[i] += math.log(FINISH_KICK)

    w = [math.exp(v) for v in g]

    # ---- 停顿
    for (at, ln) in stall_windows(n, seed):
        for k in range(at, min(n, at + ln)):
            w[k] *= STALL_FACTOR

    # ---- 归一化：均值 = 1，配速标定只负责总距离，两者互不干扰
    mean = sum(w) / n
    if mean > 0:
        w = [x / mean for x in w]
    return w


def stall_windows(n, seed):
    """停顿区间 [(起始下标, 长度)]。

    真实画像（5 条记录）：每场 7~21 段、中位 1~2 秒、约占 4% 时长，
    另有一次 5~21 秒的长停顿。旧模型 2~3 段 × 4~9 秒是错的 ——
    画出来是几个突兀的断点，而不是「跑着跑着脚步顿一下」。
    """
    rnd = random.Random(seed * 104729 + 31)
    out = []
    lo = max(2, int(n * 0.02))
    hi = max(lo + 1, int(n * 0.98))

    # 微停顿：按秒数算密度，和总时长挂钩，2km / 5km 观感一致
    count = int(round(n / 60.0 * MICRO_STALL_PER_MIN))
    for _ in range(max(2, count)):
        ln = rnd.randint(MICRO_STALL_LEN[0], MICRO_STALL_LEN[1])
        at = rnd.randrange(lo, max(lo + 1, hi - ln))
        out.append((at, ln))

    # 长停顿：不是每场都有
    if rnd.random() < LONG_STALL_PROB:
        ln = rnd.randint(LONG_STALL_LEN[0], LONG_STALL_LEN[1])
        at = rnd.randrange(lo, max(lo + 1, hi - ln))
        out.append((at, ln))

    # 合并重叠区间，避免同一秒被乘两次 0.03（会变成完全静止）
    out.sort()
    merged = []
    for at, ln in out:
        if merged and at <= merged[-1][0] + merged[-1][1]:
            a, b = merged[-1]
            merged[-1] = (a, max(b, at + ln - a))
        else:
            merged.append((at, ln))
    return merged


# ============================================================ 环线

def bad_points(poly, fence, excludes):
    bad = 0
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        d = hav(a[0], a[1], b[0], b[1])
        k = max(1, int(math.ceil(d / RING_STEP_M)))
        for j in range(k):
            t = j / k
            if not is_valid(a[0] + (b[0] - a[0]) * t,
                            a[1] + (b[1] - a[1]) * t, fence, excludes):
                bad += 1
    return bad


def pull_toward(base, p, fence, excludes):
    """沿 base → p 二分回缩，只削扰动、不动基准环。"""
    if not is_valid(base[0], base[1], fence, excludes):
        return p
    lo, hi = 0.0, 1.0
    for _ in range(22):
        mid = (lo + hi) / 2.0
        if is_valid(base[0] + (p[0] - base[0]) * mid,
                    base[1] + (p[1] - base[1]) * mid, fence, excludes):
            lo = mid
        else:
            hi = mid
    t = max(0.0, min(1.0, lo * 0.99))
    return (base[0] + (p[0] - base[0]) * t, base[1] + (p[1] - base[1]) * t)


def densify(waypoints, step_m=1.0):
    """按弧长加密，闭合（不含重复的末尾点）。"""
    pts = list(waypoints) + [waypoints[0]]
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        d = hav(a[0], a[1], b[0], b[1])
        n = max(1, int(math.ceil(d / step_m)))
        for i in range(1, n + 1):
            t = i / n
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    return out[:-1]


def cumulative(dense):
    cum = [0.0]
    for i in range(len(dense)):
        a, b = dense[i], dense[(i + 1) % len(dense)]
        cum.append(cum[-1] + hav(a[0], a[1], b[0], b[1]))
    return cum


def point_at(dense, cum, dist):
    """按累计弧长定位（环形取模），返回 (点, 该处航向)。"""
    total = cum[-1]
    if total <= 0:
        return dense[0], 0.0
    d = dist % total
    i = bisect.bisect_right(cum, d) - 1
    i = max(0, min(i, len(dense) - 1))
    j = (i + 1) % len(dense)
    seg = cum[i + 1] - cum[i]
    t = 0.0 if seg <= 0 else (d - cum[i]) / seg
    p = (dense[i][0] + (dense[j][0] - dense[i][0]) * t,
         dense[i][1] + (dense[j][1] - dense[i][1]) * t)
    return p, bearing(dense[i][0], dense[i][1], dense[j][0], dense[j][1])



def _corner_mask(dense, n, step_m, turn_deg=12.0, spread_m=22.0):
    """标出"是弯道"的那些点。

    判据：该点前后各 3 个点的方向变化超过 turn_deg 就算转弯，
    再沿弧长向两侧各扩 spread_m 米（圆角要从切点之前就开始磨，
    否则只在角尖磨会留下一道硬折）。

    返回布尔列表；整圈都在转弯（圆形场地）时返回空列表，
    表示"不用区分角与直道"。
    """
    k = 3
    flags = [False] * n
    turning = 0
    for i in range(n):
        a = dense[(i - k) % n]
        b = dense[i]
        c = dense[(i + k) % n]
        d = abs(bearing(a[0], a[1], b[0], b[1]) - bearing(b[0], b[1], c[0], c[1]))
        if min(d, 360.0 - d) > turn_deg:
            flags[i] = True
            turning += 1
    # 大部分点都在转 → 这是个圆/椭圆，分不出直道，交给整圈平滑
    if turning > n * 0.55:
        return [True] * n
    spread = max(1, int(round(spread_m / max(step_m, 0.5))))
    out = [False] * n
    for i in range(n):
        if flags[i]:
            for kk in range(-spread, spread + 1):
                out[(i + kk) % n] = True
    return out


def organic(raw, seed, fence=None, excludes=(), wobble_m=RING_WOBBLE_M,
            corner_m=None):
    """把内缩折线磨成「人跑出来」的**一圈**形状。

    为什么是「一圈」：真实轨迹里每一圈的形状都不一样 —— 弯道切入角度、
    直道贴内道还是外道、遇到人绕开的位置，全都在变。把 5 条真实记录按圈切开
    测径向偏差（相对该圈自身拟合椭圆），每圈 std 1.6~5.6 m、最大 4~24 m，
    而且**圈与圈之间互不相关**（相邻圈的偏移相关系数中位 ≈ 0.1）。

    所以环线不能只生成一次然后反复叠加噪声：那样每一圈都精确落在同一个
    轮廓上，画出来是同心圆，一眼就是机器画的（早期版本正是如此）。
    正确做法是每绕一圈都用不同的随机数重画一次形状。
    """
    if corner_m is None:
        corner_m = RING_CORNER_RADIUS_M
    dense = densify(raw, RING_STEP_M)
    n = len(dense)
    if n < 16:
        return list(raw)

    per = 0.0
    for i in range(n):
        per += hav(dense[i][0], dense[i][1], dense[(i + 1) % n][0], dense[(i + 1) % n][1])
    step_m = max(1.0, per / n)

    rnd = random.Random(seed * 1000003 + 97)
    harmonic = (1, 2, 3, 5, 7, 11, 17)
    weight = (0.62, 0.46, 0.34, 0.24, 0.17, 0.12, 0.07)
    phase = [rnd.uniform(0, 2 * math.pi) for _ in harmonic]
    wave_amp = [wobble_m * weight[i] * rnd.uniform(0.55, 1.25)
                for i in range(len(harmonic))]

    bumps = []
    for _ in range(3 + rnd.randint(0, 2)):
        center = rnd.uniform(0.0, n)
        width_m = rnd.uniform(10.0, 38.0)
        strength = rnd.uniform(2.0, 6.2) * (1 if rnd.random() < 0.5 else -1)
        bumps.append((center, max(1.0, width_m / step_m), strength))

    # ---- 只磨角点，不动直道 ----
    # 这一步是「像不像跑道」的关键，而且**不能整圈磨**。
    #
    # 服务端给的围栏是带尖角的多边形（宁静苑 5 个顶点、太极 4 个、风华 16 个）。
    # 直接沿边走是圆角矩形 —— 直角拐弯，像尺子画的；整圈做滑动平均又会把
    # 长直道一起磨弯，变成椭圆（试过：窗口 25m 整圈平均后，宁静苑的直道占比
    # 掉到 0%，画出来是个鸭蛋，而真实记录有 12% 的时间是半径 >200 m 的直道）。
    #
    # 真实记录的形状是「长直道 + 中等圆角」：
    #     场地     短边    长边    曲率半径中位   直道占比
    #     宁静苑   109m   196m      41 m          12%
    #     风华     112m   196m      58 m          22%
    #     太极     170m   232m      47 m          23%
    #
    # 所以只对**转角大的地方**做局部圆角：先算每个点的转角，把连续的大转角
    # 区域找出来（就是四个角），只在角上做加权平滑，直道保持原样。
    # corner_m == 0 → 明确要求直角，任何磨角都不做
    corners = _corner_mask(dense, n, step_m) if corner_m > 0 else []
    if corners:
        win = max(1, int(round(corner_m / step_m / 2)))
        for _ in range(3):
            dense = [
                (
                    (sum(dense[(i + k) % n][0] for k in range(-win, win + 1))
                     / (2 * win + 1)) if corners[i] else dense[i][0],
                    (sum(dense[(i + k) % n][1] for k in range(-win, win + 1))
                     / (2 * win + 1)) if corners[i] else dense[i][1],
                )
                for i in range(n)
            ]

    if corner_m <= 0:
        # 直角模式：不叠任何有机扰动，环线就是内缩后的多边形本身。
        # 这些扰动（整数圈正弦 + 局部鼓包）是为了把折角磨成弧，圆角为 0
        # 时它们反而会在直边上鼓出波浪，把"矩形"变得不像矩形。
        out = [p if is_valid(p[0], p[1], fence, excludes)
               else pull_toward(raw[i % len(raw)], p, fence, excludes)
               for i, p in enumerate(dense)]
        return out

    perturbed = []
    for i, pt in enumerate(dense):
        hdg = bearing(dense[(i - 1) % n][0], dense[(i - 1) % n][1],
                      dense[(i + 1) % n][0], dense[(i + 1) % n][1])
        u = i / n * 2 * math.pi
        off = sum(wave_amp[k] * math.sin(harmonic[k] * u + phase[k])
                  for k in range(len(harmonic)))
        for center, width, strength in bumps:
            di = min(abs(i - center), n - abs(i - center))
            off += strength * math.exp(-0.5 * (di / width) ** 2)
        perturbed.append(offset(pt[0], pt[1], off, (hdg + 90.0) % 360.0))

    # 小窗口再磨一遍：只去掉上面正弦叠加出来的尖，不破坏大圆角
    w = RING_SMOOTH_WIN
    smoothed = [(
        sum(perturbed[(i + k) % n][0] for k in range(-w, w + 1)) / (2 * w + 1),
        sum(perturbed[(i + k) % n][1] for k in range(-w, w + 1)) / (2 * w + 1),
    ) for i in range(n)]

    if fence:
        smoothed = [p if is_valid(p[0], p[1], fence, excludes)
                    else pull_toward(dense[i], p, fence, excludes)
                    for i, p in enumerate(smoothed)]
    return smoothed

    if fence:
        smoothed = [p if is_valid(p[0], p[1], fence, excludes)
                    else pull_toward(dense[i], p, fence, excludes)
                    for i, p in enumerate(smoothed)]
    return smoothed


def mean_radius(poly):
    """质心到顶点的平均距离（米）。用来把"离边多少米"换算成比例。"""
    clat = sum(p[0] for p in poly) / len(poly)
    clon = sum(p[1] for p in poly) / len(poly)
    return sum(hav(clat, clon, p[0], p[1]) for p in poly) / len(poly)


def dist_to_edge(p, poly):
    """点到多边形边界的最短距离（米）。"""
    k = math.cos(math.radians(p[0]))
    px, py = p[1] * k, p[0]
    best = 1e18
    for i in range(len(poly)):
        a, b = poly[i], poly[(i + 1) % len(poly)]
        ax, ay = a[1] * k, a[0]
        bx, by = b[1] * k, b[0]
        dx, dy = bx - ax, by - ay
        L = dx * dx + dy * dy
        t = 0.0 if L == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
        best = min(best, hav(py, px / k, ay + t * dy, (ax + t * dx) / k))
    return best


def field_params(place_code=None, fence=None):
    """取场地特调参数，返回 (长轴缩放, 短轴缩放, 圆角半径, 说明) 或 None。

    主键是 **placeCode**（`/area/getAllArea` 返回的 `placeCode`，如 "T1014"）。
    这是服务端自己的场地 ID，App 里从 `Area.placeCode` 直接拿得到，
    没有任何推断成分 —— 早先版本用"围栏周长 + 顶点数"当几何指纹，
    结果风华（564 顶点 16）和宁静苑（554 顶点 5）的周长只差 10 m，
    按 ±12 m 匹配时两块场地撞在一起，风华被识别成了宁静苑、
    参数全错。ID 就是 ID，不该绕。

    拿不到 ID 时才退回几何指纹，那是给离线脚本用的兜底。
    """
    if place_code and place_code in FIELD_TUNING:
        return FIELD_TUNING[place_code]
    if fence:
        per = 0.0
        for i in range(len(fence)):
            a, b = fence[i], fence[(i + 1) % len(fence)]
            per += hav(a[0], a[1], b[0], b[1])
        for code, params in FIELD_TUNING.items():
            ref = FIELD_REF.get(code)
            if ref and abs(per - ref[0]) <= 4.0 and len(fence) == ref[1]:
                return params
    return None


def min_area_rect(poly):
    """最小外接矩形的 (宽, 高, 旋转角)。1° 步长足够。"""
    clat = sum(p[0] for p in poly) / len(poly)
    clon = sum(p[1] for p in poly) / len(poly)
    k = math.cos(math.radians(clat))
    xy = [((p[1] - clon) * k * 111320.0, (p[0] - clat) * 110540.0) for p in poly]
    best = (1e18, 0.0, 0.0, 0.0)
    for deg in range(0, 180):
        c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
        xs = [x * c + y * s for x, y in xy]
        ys = [-x * s + y * c for x, y in xy]
        w = max(xs) - min(xs)
        h = max(ys) - min(ys)
        if w * h < best[0]:
            best = (w * h, w, h, deg)
    return best[1], best[2], best[3]


def scale_axes(poly, k_long, k_short, shift_m=(0.0, 0.0)):
    """沿最小外接矩形的长轴 / 短轴分别缩放（各向异性内缩）。

    为什么不用统一系数：真实轨迹和围栏各自的最小外接矩形量出来后，
    长短轴的缩放比值明显不同（见 FIELD_TUNING 的注释）。跑道就是这个道理
    —— 长直道方向能贴得更近，两端弯道要留出半径。
    """
    w, h, deg = min_area_rect(poly)
    clat = sum(p[0] for p in poly) / len(poly)
    clon = sum(p[1] for p in poly) / len(poly)
    k = math.cos(math.radians(clat))
    along_long = k_long if w >= h else k_short
    along_short = k_short if w >= h else k_long
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    out = []
    # 平移用"东 / 北"米，和 (x, y) 同向
    shift_x, shift_y = shift_m
    for p in poly:
        x = (p[1] - clon) * k * 111320.0
        y = (p[0] - clat) * 110540.0
        u = x * c + y * s
        v = -x * s + y * c
        u *= along_long
        v *= along_short
        x2 = u * c - v * s + shift_x
        y2 = u * s + v * c + shift_y
        out.append((clat + y2 / 110540.0, clon + x2 / (k * 111320.0)))
    return out if signed_area(out) >= 0 else list(reversed(out))


def choose_ring(fence, excludes, seed, place_code=None):
    """定环线，返回（原始折线, 参数元组）。

    优先级：
      1. **场地特调**（按 placeCode）—— 三个真实场地各自反解出来的
         长短轴缩放与圆角半径。主力路径。
      2. **通用兜底** —— 没见过的场地按「离边 = 半径 × 0.30」等比内缩，
         再用禁跑区把环线往外顶。

    任何情况下都不允许环线踩进禁跑区：踩进去的点服务端判 isValid="2"
    不计里程，一条记录里出现这种段落，比"跑得不像"严重得多。
    """
    params = field_params(place_code, fence)
    if params is not None:
        k_long, k_short, corner_m, note = params[0], params[1], params[2], params[3]
        shift = params[4] if len(params) > 4 else (0.0, 0.0)
        ring = scale_axes(fence, k_long, k_short, shift)
        if not bad_points(ring, fence, excludes):
            return ring, (k_long, k_short, corner_m, note)
        # 压到禁跑区：只能**往外扩**（往里缩离围栏更远、更早撞上禁跑区）。
        # 平移量按比例缩小，避免"放大后整体又被推出围栏"。
        kl, ks = k_long, k_short
        f = 1.0
        while kl < 1.0 or ks < 1.0:
            kl = min(1.0, kl + 0.01)
            ks = min(1.0, ks + 0.01)
            f = min(1.0, f + 0.02)
            ring = scale_axes(fence, kl, ks, (shift[0] * f, shift[1] * f))
            if not bad_points(ring, fence, excludes):
                return ring, (kl, ks, corner_m, note)
        return ring, (kl, ks, corner_m, note)

    # ---- 通用兜底：等比内缩 + 往外避让禁跑区 ----
    r = mean_radius(fence)
    goal = r * RING_RADIUS_INSET

    def gap_of(fv):
        pts = densify(inset_poly(fence, fv), RING_STEP_M)
        return sum(dist_to_edge(p, fence) for p in pts) / max(1, len(pts))

    lo, hi = 0.08, 0.97
    for _ in range(26):
        mid = (lo + hi) / 2.0
        if gap_of(mid) > goal:
            lo = mid
        else:
            hi = mid
    f = (lo + hi) / 2.0

    if excludes and bad_points(inset_poly(fence, f), fence, excludes):
        ff = f
        while ff < 0.99:
            ff += 0.01
            if not bad_points(inset_poly(fence, ff), fence, excludes):
                return inset_poly(fence, ff), (ff, ff, RING_CORNER_RADIUS_M, "自动")
    return inset_poly(fence, f), (f, f, RING_CORNER_RADIUS_M, "自动")


def push_off_excludes(ring, fence, excludes):
    """把压进禁跑区的环线点推出来。

    推的方向是"远离该禁跑区质心"，步长按实际压入深度算，
    所以只会动那一段，环线的其余部分保持原来的内缩量 ——
    这正是真实跑者遇到内场时的做法（那一侧往外绕一点，其他地方照旧）。
    """
    if not excludes:
        return ring
    out = []
    for p in ring:
        q = p
        for _ in range(6):
            hit = None
            for e in excludes:
                if point_in_poly(q[0], q[1], e):
                    hit = e
                    break
            if hit is None:
                break
            clat = sum(t[0] for t in hit) / len(hit)
            clon = sum(t[1] for t in hit) / len(hit)
            brg = bearing(clat, clon, q[0], q[1])
            q = offset(q[0], q[1], 3.0, brg)
            if not point_in_poly(q[0], q[1], fence):
                q = clamp_to_fence(q[0], q[1], fence)
                break
        out.append(q)
    return out


# ============================================================ 逐圈重画

def lap_ring(base_ring, lap_index, seed, fence, excludes, corner_m=None):
    """给第 lap_index 圈重画一次形状。

    形状参数全部重新抽：正弦相位、幅度、鼓包位置和强度都换一套，
    所以第 2 圈不会和第 1 圈重合。内缩比例也轻微变化（±2%），
    对应真实里「这圈贴内道、下圈贴外道」。
    """
    # 每圈换一个种子：同一个 base_ring 内缩比例不变，但扰动完全重来
    rnd = random.Random(seed * 1_000_003 + lap_index * 7919 + 13)
    # 内缩微调：±2%，等效横向 2~4 m，正好是真实圈间中心偏移的量级
    shrink = 1.0 + rnd.uniform(-0.02, 0.02)
    clat = sum(p[0] for p in base_ring) / len(base_ring)
    clon = sum(p[1] for p in base_ring) / len(base_ring)
    ring = [(clat + (p[0] - clat) * shrink, clon + (p[1] - clon) * shrink)
            for p in base_ring]
    return organic(ring, seed * 31 + lap_index * 977 + 7, fence, excludes,
                   corner_m=corner_m)


# ============================================================ 野点

def make_spurs(n, seed, intensity=1.0):
    """稀疏野点：真实 GPS 在多径 / 树荫 / 贴楼时的突发跳点。

    四档，按「每 660 秒多少次」定密度，所以 2km 和 5km 的观感一致。
    幅度和档位是照着真实画像定的（见文件头的目标表）：
    大而稀的单点跳，比小而密的多点包更像真机 —— 真实数据里 >5m 的跳点簇
    只有 6 个左右，其中多数还是一步就回来的单点。
    """
    lateral = [0.0] * n
    forward = [0.0] * n
    if n < 80:
        return lateral, forward

    rnd = random.Random(seed * 1000003 + 77)
    used = []
    scale = n / 660.0

    def free(at, gap):
        return all(abs(at - u) >= gap for u in used)

    lo = max(2, int(n * 0.03))
    hi = max(lo + 1, min(n - 4, int(n * 0.97)))

    def take(count, gap, gaps):
        """按概率取 count 个不重叠的位置。"""
        nonlocal used
        want = count * scale
        k = int(want) + (1 if rnd.random() < (want - int(want)) else 0)
        for _ in range(k):
            for _try in range(80):
                at = rnd.randrange(lo, hi)
                if not free(at, gap):
                    continue
                used.append(at)
                gaps.append(at)
                break

    # ① 单点大跳：9~16m，以沿轨方向为主 —— 真机是「往前窜一下」而不是「往侧面甩」
    pos = []
    take(SPUR_BIG_COUNT_PER_660, 14, pos)
    for at in pos:
        a = rnd.uniform(*SPUR_BIG_AMP) * intensity * (1 if rnd.random() < 0.5 else -1)
        share = rnd.uniform(-SPUR_BIG_LATERAL_SHARE, SPUR_BIG_LATERAL_SHARE)
        lateral[at] = a * abs(share)
        forward[at] = a * (1.0 if share >= 0 else -1.0) * (1.0 - abs(share))

    # ② 绕行包：4~9 个点走成一个不对称三角（绕人 / 抄近道），这一档是横向的
    pos = []
    take(SPUR_BUMP_COUNT_PER_660, 12, pos)
    for at in pos:
        span = rnd.randint(4, 9)
        if at + span >= n:
            continue
        a = rnd.uniform(*SPUR_BUMP_AMP) * intensity * (1 if rnd.random() < 0.5 else -1)
        share = rnd.uniform(-0.25, 0.25)
        for k in range(span + 1):
            s = k / span
            lateral[at + k] = a * SPUR_BUMP_LATERAL * math.sin(math.pi * s)
            forward[at + k] = a * share * math.sin(2 * math.pi * s)

    # ③ 硬毛刺：3~8m 的单点，或两步来回；也偏沿轨
    pos = []
    take(SPUR_HARD_COUNT_PER_660, 8, pos)
    for at in pos:
        a = rnd.uniform(*SPUR_HARD_AMP) * intensity
        sign = 1.0 if rnd.random() < 0.5 else -1.0
        lateral[at] = a * 0.45 * sign
        forward[at] = a * 0.9 * sign
        if rnd.random() < 0.28 and at + 1 < n:
            lateral[at + 1] = -a * 0.3 * sign

    # ④ 小毛刺：0.8~2.6m，纯横向，把线边揉毛
    pos = []
    take(SPUR_SOFT_COUNT_PER_660, 4, pos)
    for at in pos:
        a = rnd.uniform(*SPUR_SOFT_AMP) * intensity * (1 if rnd.random() < 0.5 else -1)
        lateral[at] = a
        forward[at] = rnd.uniform(-0.5, 0.5) * a * 0.4

    return lateral, forward


# ============================================================ 采样

def clamp_to_fence(lat, lon, fence):
    """沿「点 → 质心」二分拉回围栏内（兜底用）。"""
    if len(fence) < 3 or point_in_poly(lat, lon, fence):
        return lat, lon
    clat = sum(p[0] for p in fence) / len(fence)
    clon = sum(p[1] for p in fence) / len(fence)
    if not point_in_poly(clat, clon, fence):
        return lat, lon
    lo, hi = 0.0, 1.0
    for _ in range(24):
        mid = (lo + hi) / 2.0
        if point_in_poly(clat + (lat - clat) * mid, clon + (lon - clon) * mid, fence):
            lo = mid
        else:
            hi = mid
    t = max(0.0, min(1.0, lo * 0.995))
    return clat + (lat - clat) * t, clon + (lon - clon) * t


def tolerate(base, q, fence, excludes):
    """把噪声点收回合法范围，但只收噪声那一截（保住毛刺方向）。

    三级兜底，每一级都同时检查围栏和禁跑区：
      1. 噪声点本身合法 → 原样用（绝大多数点走这条）
      2. 基准点合法 → 沿 base→q 二分，只削掉越界的那一段，毛刺方向保留
      3. 基准点自己就不合法（凹围栏 / 环线被禁跑区压住）→ 先把基准点救回来，
         再把噪声沿新的基准点收一次

    第 3 级以前直接用 clamp_to_fence（只认围栏、不认禁跑区），于是禁跑区
    边缘会溢出十来个点 —— 服务端按 isValid 把它们记成不计里程，实测里程
    会短几十米。现在把禁跑区一起纳入判定。
    """
    if is_valid(q[0], q[1], fence, excludes):
        return q
    if is_valid(base[0], base[1], fence, excludes):
        return pull_toward(base, q, fence, excludes)

    fixed = rescue_point(base, fence, excludes)
    if fixed is None:
        return clamp_to_fence(q[0], q[1], fence)
    return pull_toward(fixed, q, fence, excludes)


def rescue_point(p, fence, excludes):
    """把一个不合法点弄到"围栏内且不进禁跑区"。找不到就返回 None。

    做法两步：先按「点 → 围栏质心」拉进围栏（clamp_to_fence 干的事），
    再按「点 → 该禁跑区质心」的反方向推出禁跑区。两次都只动这一小段。
    """
    p = clamp_to_fence(p[0], p[1], fence)
    for _ in range(8):
        hit = None
        for e in excludes:
            if point_in_poly(p[0], p[1], e):
                hit = e
                break
        if hit is None:
            return p if is_valid(p[0], p[1], fence, excludes) else None
        clat = sum(t[0] for t in hit) / len(hit)
        clon = sum(t[1] for t in hit) / len(hit)
        brg = bearing(clat, clon, p[0], p[1])
        p = offset(p[0], p[1], 2.5, brg)
        if not point_in_poly(p[0], p[1], fence):
            return None
    return p if is_valid(p[0], p[1], fence, excludes) else None


def build(base_ring, n, weights, spurs, target_m, noise, seed, fence=(), excludes=(),
          lap_fn=None):
    """固定点数 n，按权重铺路程，叠多尺度噪声与野点，二分反解地面步长。

    `lap_fn(lap_index)` 返回"第几圈的环线形状"。给 None 时只用 `base_ring`
    这一份形状（旧行为，所有圈同心）。给了它就**每绕完一圈换一份新形状** ——
    这是让轨迹像人跑的关键，真实记录的每一圈都不重合。
    """
    spur_lat, spur_fwd = spurs
    levels = len(NOISE_RHO)

    # 每圈的 (dense, cum, 圈长)。圈长用来决定下一个点落在第几圈的第几米。
    lap_cache = {}

    def lap_of(k):
        if lap_fn is None:
            if 0 not in lap_cache:
                d = densify(base_ring, 1.0)
                lap_cache[0] = (d, cumulative(d))
            return lap_cache[0]
        if k not in lap_cache:
            d = densify(lap_fn(k), 1.0)
            lap_cache[k] = (d, cumulative(d))
        return lap_cache[k]

    d0, c0 = lap_of(0)
    base_len = max(1.0, c0[-1])

    # 圈长会随形状变化，用第 0 圈的长度做基准；后面按实际长取模，
    # 差几米的累积误差最多让"圈边界"错开一点，不影响观感。
    def locate(dist):
        k = int(dist // base_len)
        local = dist - k * base_len
        d, c = lap_of(k)
        return point_at(d, c, local)

    def emit(step):
        rnd = random.Random(seed)
        rnd_acc = random.Random(seed * 31 + 7)
        perr = [0.0] * levels
        aerr = [0.0] * levels
        travelled = 0.0
        lat = [0.0] * n
        lon = [0.0] * n
        pew = [0.0] * n
        for i in range(n):
            p, hdg = locate(travelled)
            # 野点是「事件」，AR 噪声是「底噪」：先加野点再加底噪
            perr_t, aerr_t = spur_lat[i], spur_fwd[i]
            for c in range(levels):
                perr[c] = NOISE_RHO[c] * perr[c] + rnd.uniform(-1.0, 1.0) * noise * NOISE_SIGMA[c]
                aerr[c] = NOISE_RHO[c] * aerr[c] + rnd.uniform(-1.0, 1.0) * noise * NOISE_SIGMA[c]
                perr_t += perr[c]
                aerr_t += aerr[c] * 0.45
            o1 = offset(p[0], p[1], perr_t, (hdg + 90.0) % 360.0)
            q = offset(o1[0], o1[1], aerr_t, hdg)
            lat[i], lon[i] = tolerate(p, q, fence, excludes)
            pew[i] = perr_t
            travelled += step * weights[i]

        # speed / bearing 从**最终坐标**反推，保证上报字段与轨迹自洽
        out = []
        for i in range(n):
            j = i + 1 if i < n - 1 else i - 1
            seg = hav(lat[i], lon[i], lat[j], lon[j])
            brg = (bearing(lat[i], lon[i], lat[j], lon[j]) if i < n - 1
                   else (bearing(lat[j], lon[j], lat[i], lon[i]) if n > 1 else 0.0))
            acc = 4.5 + abs(pew[i]) * 0.30 + rnd_acc.uniform(-1.0, 2.2)
            out.append({
                "lat": lat[i], "lon": lon[i], "sec": i,
                "speed": round(seg, 2),
                "bearing": round(brg, 1),
                "acc": round(min(18.0, max(3.0, acc)), 1),
                "step": seg,
            })
        return out

    def measure(pts):
        return sum(hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
                   for i in range(len(pts) - 1))

    if measure(emit(0.0)) >= target_m:
        return emit(0.0)
    lo, hi = 0.0, 5.0
    while measure(emit(hi)) < target_m and hi < 1e5:
        hi *= 1.5
    for _ in range(46):
        mid = (lo + hi) / 2.0
        if measure(emit(mid)) < target_m:
            lo = mid
        else:
            hi = mid
    return emit((lo + hi) / 2.0)


# ============================================================ 入口

def pace_window(pace_s_km, tol):
    t = abs(tol)
    return (max(60.0, pace_s_km - t), pace_s_km + t)


def measure_dist(pts):
    return sum(hav(pts[i]["lat"], pts[i]["lon"], pts[i + 1]["lat"], pts[i + 1]["lon"])
               for i in range(len(pts) - 1))


def pace_of(pts):
    d = measure_dist(pts)
    return (len(pts) - 1) / (d / 1000.0) if d > 0 else 0.0


def generate(poly, excludes, pace_s_km, target_m, noise=0.6, seed=0,
             pace_tol=DEFAULT_PACE_TOL, burrs=True, place_code=None):
    """生成一条落在围栏内、平均配速精确的闭环轨迹。

    burrs=True（App 默认）：完整的配速形态 + 多尺度噪声 + 稀疏野点。
    burrs=False：v1.10.1 的旧光滑轨迹，只为 A/B 对比保留（见 generate_legacy）。
    """
    fence = dedupe(poly)
    exc = [dedupe(e) for e in (excludes or []) if len(e) >= 3]
    window = pace_window(pace_s_km, pace_tol)

    if not burrs:
        return generate_legacy(fence, exc, pace_s_km, target_m, noise, seed, window)

    base_ring, inset_f = choose_ring(fence, exc, seed, place_code=place_code)
    n = max(2, int(round(target_m / (1000.0 / pace_s_km))))
    intensity = min(2.5, max(0.0, noise / 0.6))
    # 野点位置只跟 n 和 seed 有关，所以 measure(step) 关于 step 单调，二分有效
    spurs = make_spurs(n, seed, intensity)

    # 每一圈的形状都重画：真实记录的圈与圈之间形状不相关（见 organic() 的说明）。
    # 用 base_ring（已选好的内缩比例）当基准，避免每圈都重跑一遍 bad_points 搜索。
    # corner_m 来自 choose_ring 返回的参数（场地特调的第 3 项）
    corner_m = inset_f[2] if isinstance(inset_f, tuple) else RING_CORNER_RADIUS_M


    def lap_fn(k):
        return lap_ring(base_ring, k, seed, fence, exc, corner_m=corner_m)

    pts = []
    adjusted = False
    for _ in range(MAX_PACE_FIX_ATTEMPTS):
        weights = pace_profile(n, seed)
        pts = build(base_ring, n, weights, spurs, target_m, noise, seed, fence, exc,
                    lap_fn=lap_fn)
        pace = pace_of(pts)
        if window[0] <= pace <= window[1]:
            break
        # 配速偏慢（数值大）= 秒数多了，要减点；反之加点
        ratio = pace / pace_s_km if pace_s_km > 0 else 1.0
        nxt = max(2, min(int(round(n / ratio)) if ratio > 0 else n, n * 4 + 8))
        if nxt == n:
            break
        n = nxt
        spurs = make_spurs(n, seed, intensity)
        adjusted = True

    return pts, fence, pace_of(pts), window, adjusted


# ============================================================ 旧光滑轨迹（A/B 对照）

def generate_legacy(fence, exc, pace_s_km, target_m, noise, seed, window):
    """v1.10.1 的光滑轨迹：两条整数圈正弦 + 两轮磨角 + 单个 AR(1)。

    只为「毛刺开关关闭」的对照保留。真实数据表明它不像真机
    （没有停顿、转角只有真机的一半、没有任何跳点），所以不再是默认。
    """
    n = max(2, int(round(target_m / (1000.0 / pace_s_km))))
    pts, pace, adjusted = [], 0.0, False
    for _ in range(MAX_PACE_FIX_ATTEMPTS):
        best, best_score = [], -1.0
        for f in (0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50):
            ring_len = 0.0
            raw = inset_poly(fence, f)
            for i in range(len(raw)):
                ring_len += hav(raw[i][0], raw[i][1],
                                raw[(i + 1) % len(raw)][0], raw[(i + 1) % len(raw)][1])
            ring = organic_smooth(raw, seed, seg_m=max(6.0, ring_len / 64.0), wobble_m=3.0)
            cand = build_smooth(ring, n, pace_profile(n, seed), target_m, noise, seed, fence)
            inside = sum(1 for q in cand if point_in_poly(q["lat"], q["lon"], fence))
            banned = sum(1 for q in cand
                         if any(point_in_poly(q["lat"], q["lon"], e) for e in exc))
            score = (inside - banned) / len(cand)
            if score > best_score:
                best_score, best = score, cand
            if banned == 0 and inside == len(cand):
                break
        pts = best
        pace = pace_of(pts)
        if window[0] <= pace <= window[1]:
            break
        ratio = pace / pace_s_km if pace_s_km > 0 else 1.0
        nxt = max(2, min(int(round(n / ratio)) if ratio > 0 else n, n * 4 + 8))
        if nxt == n:
            break
        n = nxt
        adjusted = True
    return pts, fence, pace_of(pts), window, adjusted


def organic_smooth(waypoints, seed, seg_m=15.0, wobble_m=4.0):
    """v1.10.1 的环线：两条整数圈正弦 + 两轮磨角。"""
    dense = densify(waypoints, seg_m)
    n = len(dense)
    if n < 8:
        return list(waypoints)
    rnd = random.Random(seed)
    p1, p2 = rnd.uniform(0, 2 * math.pi), rnd.uniform(0, 2 * math.pi)
    a1, a2 = wobble_m * rnd.uniform(0.5, 1.0), wobble_m * rnd.uniform(0.3, 0.7)
    cur = []
    for i, pt in enumerate(dense):
        hdg = bearing(dense[(i - 1) % n][0], dense[(i - 1) % n][1],
                      dense[(i + 1) % n][0], dense[(i + 1) % n][1])
        u = i / n * 2 * math.pi
        off = a1 * math.sin(2 * u + p1) + a2 * math.sin(3 * u + p2)
        cur.append(offset(pt[0], pt[1], off, (hdg + 90.0) % 360.0))
    for _ in range(2):
        w = 2
        cur = [(sum(cur[(i + k) % n][0] for k in range(-w, w + 1)) / (2 * w + 1),
                sum(cur[(i + k) % n][1] for k in range(-w, w + 1)) / (2 * w + 1))
               for i in range(n)]
    return cur


def build_smooth(ring, n, weights, target_m, noise, seed, fence=()):
    """v1.10.1 的采样：单个 AR(1) + clampToFence。"""
    dense = densify(ring, 1.0)
    cum = cumulative(dense)

    def emit(step):
        rnd = random.Random(seed)
        rnd_acc = random.Random(seed * 31 + 7)
        perr = aerr = 0.0
        travelled = 0.0
        lat = [0.0] * n
        lon = [0.0] * n
        pew = [0.0] * n
        for i in range(n):
            p, hdg = point_at(dense, cum, travelled)
            perr = 0.9 * perr + rnd.uniform(-1.0, 1.0) * noise * 0.55
            aerr = 0.9 * aerr + rnd.uniform(-1.0, 1.0) * noise * 0.35
            o1 = offset(p[0], p[1], perr, (hdg + 90.0) % 360.0)
            o2 = offset(o1[0], o1[1], aerr, hdg)
            lat[i], lon[i] = clamp_to_fence(o2[0], o2[1], fence)
            pew[i] = perr
            travelled += step * weights[i]

        out = []
        for i in range(n):
            j = i + 1 if i < n - 1 else i - 1
            seg = hav(lat[i], lon[i], lat[j], lon[j])
            brg = (bearing(lat[i], lon[i], lat[j], lon[j]) if i < n - 1
                   else (bearing(lat[j], lon[j], lat[i], lon[i]) if n > 1 else 0.0))
            acc = 4.5 + abs(pew[i]) * 0.45 + rnd_acc.uniform(-1.0, 2.2)
            out.append({
                "lat": lat[i], "lon": lon[i], "sec": i,
                "speed": round(seg, 2),
                "bearing": round(brg, 1),
                "acc": round(min(18.0, max(3.0, acc)), 1),
                "step": seg,
            })
        return out

    if measure_dist(emit(0.0)) >= target_m:
        return emit(0.0)
    lo, hi = 0.0, 5.0
    while measure_dist(emit(hi)) < target_m and hi < 1e5:
        hi *= 1.5
    for _ in range(44):
        mid = (lo + hi) / 2.0
        if measure_dist(emit(mid)) < target_m:
            lo = mid
        else:
            hi = mid
    return emit((lo + hi) / 2.0)


# ============================================================ 真实场地

FENG_HUA = [
    (29.532692, 106.608105), (29.532871, 106.608176), (29.533363, 106.608104),
    (29.533592, 106.608107), (29.533707, 106.607374), (29.533713, 106.607166),
    (29.533627, 106.607020), (29.533197, 106.607033), (29.532749, 106.607017),
    (29.532289, 106.607043), (29.532065, 106.607032), (29.531956, 106.607445),
    (29.531947, 106.607814), (29.531952, 106.607986), (29.532145, 106.608111),
    (29.532466, 106.608101), (29.532692, 106.608105),
]
FENG_HUA_JPQ = [[
    (29.532769, 106.607824), (29.533107, 106.607843), (29.533293, 106.607835),
    (29.533417, 106.607734), (29.533403, 106.607527), (29.533345, 106.607373),
    (29.533219, 106.607323), (29.533012, 106.607303), (29.532650, 106.607330),
    (29.532462, 106.607380), (29.532362, 106.607479), (29.532323, 106.607624),
    (29.532385, 106.607769), (29.532528, 106.607824), (29.532717, 106.607841),
]]
TAI_JI = [
    (29.533933, 106.608884), (29.531893, 106.608970),
    (29.532019, 106.610646), (29.533979, 106.610558),
]
TAI_JI_JPQ = [[
    (29.533214, 106.609447), (29.533071, 106.609473), (29.532917, 106.609468),
    (29.532626, 106.609486), (29.532506, 106.609612), (29.532533, 106.610029),
    (29.532691, 106.610093), (29.533146, 106.610060), (29.533421, 106.610058),
    (29.533549, 106.609690), (29.533457, 106.609455),
]]
NING_JING = [
    (29.530657, 106.606709), (29.530609, 106.608438), (29.529696, 106.608317),
    (29.529679, 106.606648), (29.530394, 106.606416),
]

# 场地名 → (围栏, 禁跑区, 真实参考配速与距离)
# 场地中文名 → (围栏, 禁跑区, placeCode)
# placeCode 就是 /area/getAllArea 返回的字段，直接当特调表的键用。
FIELDS = {
    "风华运动场": (FENG_HUA, FENG_HUA_JPQ, "T1001"),
    "太极运动场": (TAI_JI, TAI_JI_JPQ, "T1005"),
    "宁静苑": (NING_JING, [], "T1014"),
}


def place_code_of(field):
    """中文场地名 → placeCode（特调表用）。"""
    v = FIELDS.get(field)
    return v[2] if v and len(v) > 2 else None


def fmt_pace(s):
    if s <= 0 or s > 3600:
        return "--"
    return "%d'%02d\"" % (int(s // 60), int(s % 60))


if __name__ == "__main__":
    print("场地          配速     点数   距离      实测配速  围栏内  禁跑区")
    for name, (fence, exc, code) in FIELDS.items():
        pts, f2, real, win, _ = generate(fence, exc, 330.0, 2000.0,
                                         seed=20261013, place_code=code)
        inside = sum(1 for p in pts if point_in_poly(p["lat"], p["lon"], f2))
        banned = sum(1 for p in pts if any(point_in_poly(p["lat"], p["lon"], e) for e in exc))
        print(f"{name:<12}{fmt_pace(330.0):>8}{len(pts):>6}{measure_dist(pts):>9.1f}"
              f"{fmt_pace(real):>10}{inside / len(pts):>8.1%}{banned:>8}")
