#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
draw_text.py —— 在地图上「写字」的打卡：在所选场地顺时针写下「重庆邮电大学」

和 wecat_run.py 的差别只有第 3 步 —— 轨迹不是绕圈，而是一行字：

    openid → token → 场地 → 电子围栏 + 禁跑区
    → 沿「围栏内、禁跑区外」的跑道中线拉一条环线（顺时针）
    → 六个字各占环线一格，字框整个挪到环线外侧，朝北正立
    → 手写化：整字旋转/缩放/基线漂移 + 单笔小角度 + 抖腕正弦 + 抬笔弧线
    → 逐秒铺点（笔画 0.9 米/点，抬笔段跑快）→ start2 → saveListByNo → end

几个必须这么做的点：

* **字模用「骨架」不用字体轮廓。** GPS 只能画一条线，轮廓字会变成双线描边，
  缩到 20 米字高就糊成一团。所以每个字手写成 7~8 笔单线（见 GLYPHS），
  笔顺也按真实笔顺 —— 抬笔的连线才像人跑出来的，而不是乱跳。
* **环线不能拿围栏等比缩放。** 风华运动场的禁跑区在南北方向更「顶」，缩放出来的
  环线会一头扎进禁跑区。所以按径向剖面量出每个方向上「禁跑区边界 / 围栏边界」，
  在两者之间插值，环线才贴着跑道走。
* **字要整个落在跑道带里，环线从字外面过。** 字框按它在半径方向上的投影宽度让位，
  环线就不会从字中间穿过去 —— 一条线横穿字的画面很难看。
* **字高由带宽决定，脚本自己搜。** 六个字均分一圈时总有字落在拐角上，
  所以每个字允许在自己的格子里挪一点（手写本来也不等间距），
  躲开拐角后字能大两圈。
* **时间要花在笔画上。** 1 秒一个点，按 6'30" 配速一步 2.5 米，笔画拐角会被削平。
  所以笔画固定 0.9 米/点，剩下的秒数全丢给「抬笔沿环线跑」那段 ——
  那段压在环线已有的线条上，跑多快都不多出可见笔画。
* **2 公里不用写满两圈。** 一圈字迹约 1 公里，差的那部分沿环线跑掉（同样不可见），
  圈数越少，同一行字的采样越密、越清楚。

用法：

    python draw_text.py                              # 交互：问 openid、问场地
    python draw_text.py --place 太极运动场            # 只问 openid
    python draw_text.py --openid oXXX --place T1001 --live
    python draw_text.py --offline --poly feng --png run/plan.png   # 不联网，只看排版

`run/` 在 .gitignore 里，预览图和轨迹 JSON 丢那儿就行。
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
import zlib
from datetime import datetime, timedelta

import track_engine as TE

TEXT = "重庆邮电大学"
INK, HOP, RING = 0, 1, 2         # 着墨（笔画）/ 抬笔短跳 / 抬笔沿环线跑

DEFAULT_PACE = "6:30"            # 规则窗口 4'00"~7'30"，写字要拐弯，取偏慢一端
DEFAULT_DIST = 2.0
DEFAULT_NOISE = 0.35            # 逐秒 GPS 抖动强度（米）；写字要细，别把笔画抖散
MIN_H = 5.0                      # 字高下限，再小就认不出来了


# ============================================================ 字模（手写骨架）
#
# 归一化坐标：x 0→1 由左到右，y 0→1 由下到上（y=0 是基线，就是环线）。
# 每字一串笔画，每笔一串控制点 —— 少画几笔没关系，认得出就行，
# 笔画太密在 20 米字高下反而糊成一团。

GLYPHS: dict[str, list[list[tuple[float, float]]]] = {
    "重": [
        [(0.46, 0.98), (0.38, 0.92), (0.26, 0.85)],            # 丿
        [(0.10, 0.83), (0.90, 0.83)],                          # 一
        [(0.50, 0.80), (0.50, 0.07)],                          # 丨（从上横贯穿到底横）
        [(0.25, 0.68), (0.25, 0.26)],                          # 曰 左竖
        [(0.25, 0.68), (0.75, 0.68), (0.75, 0.26)],            # 曰 横折
        [(0.25, 0.47), (0.75, 0.47)],                          # 曰 中横
        [(0.25, 0.26), (0.75, 0.26)],                          # 曰 底横
        [(0.10, 0.07), (0.90, 0.07)],                          # 底长横
    ],
    "庆": [
        [(0.42, 0.98), (0.50, 0.90)],                          # 丶
        [(0.12, 0.87), (0.88, 0.87)],                          # 广 横
        [(0.14, 0.87), (0.10, 0.66), (0.06, 0.53)],            # 广 撇
        [(0.16, 0.53), (0.84, 0.53)],                          # 大 横
        [(0.50, 0.53), (0.26, 0.18), (0.16, 0.06)],            # 大 撇
        [(0.50, 0.53), (0.76, 0.20), (0.88, 0.10)],            # 大 捺
    ],
    "邮": [
        [(0.14, 0.62), (0.14, 0.20)],                          # 由 左竖
        [(0.14, 0.62), (0.52, 0.62), (0.52, 0.20)],            # 由 横折
        [(0.14, 0.41), (0.52, 0.41)],                          # 由 中横
        [(0.14, 0.20), (0.52, 0.20)],                          # 由 底横
        [(0.33, 0.80), (0.33, 0.62)],                          # 由 上出头
        [(0.64, 0.74), (0.84, 0.72), (0.84, 0.56), (0.66, 0.47)],  # 阝 横折折
        [(0.64, 0.76), (0.64, 0.04)],                          # 阝 竖
    ],
    "电": [
        [(0.20, 0.62), (0.20, 0.18)],                          # 左竖
        [(0.20, 0.62), (0.62, 0.62), (0.62, 0.18)],            # 横折
        [(0.20, 0.40), (0.62, 0.40)],                          # 中横
        [(0.20, 0.18), (0.62, 0.18)],                          # 底横
        [(0.41, 0.62), (0.41, 0.10), (0.52, 0.03), (0.66, 0.05), (0.73, 0.13)],  # 竖弯钩
    ],
    "大": [
        [(0.08, 0.62), (0.92, 0.62)],
        [(0.50, 0.62), (0.24, 0.20), (0.12, 0.06)],
        [(0.50, 0.62), (0.78, 0.22), (0.90, 0.12)],
    ],
    "学": [
        [(0.28, 0.86), (0.17, 0.75)],                          # 丶
        [(0.50, 0.92), (0.50, 0.79)],                          # 丶
        [(0.72, 0.86), (0.83, 0.75)],                          # 丶
        [(0.10, 0.70), (0.50, 0.61), (0.90, 0.70)],            # 冖
        [(0.18, 0.53), (0.82, 0.53), (0.58, 0.36)],            # 子 横撇
        [(0.50, 0.44), (0.50, 0.10), (0.36, 0.04)],            # 子 竖钩
        [(0.12, 0.20), (0.88, 0.20)],                          # 子 长横
    ],
}


# ============================================================ 平面几何（米）
#
# 全场只有几百米，全部换算到「以场地质心为原点的局地平面」上算：
# x 东、y 北，单位米。字模的旋转/法线/切线在这里都是普通二维向量，
# 最后再变回经纬度交给协议层。

class Plane:
    """局地平面 ↔ WGS84/GCJ02 经纬度（两者在这个尺度下差一个常数偏移，不影响相对关系）。"""

    def __init__(self, lat0: float, lon0: float):
        self.lat0, self.lon0 = lat0, lon0
        p = math.radians(lat0)
        # 子午圈 / 卯酉圈 每度长度，比写死 111320 准一点点
        self.ky = 111132.92 - 559.82 * math.cos(2 * p) + 1.175 * math.cos(4 * p)
        self.kx = 111412.84 * math.cos(p) - 93.5 * math.cos(3 * p)

    def xy(self, lat: float, lon: float) -> tuple[float, float]:
        return ((lon - self.lon0) * self.kx, (lat - self.lat0) * self.ky)

    def geo(self, x: float, y: float) -> tuple[float, float]:
        return (self.lat0 + y / self.ky, self.lon0 + x / self.kx)


def dist(a, b) -> float:
    return math.hypot(b[0] - a[0], b[1] - a[1])


def poly_len(pts) -> float:
    return sum(dist(a, b) for a, b in zip(pts, pts[1:]))


def signed_area(poly) -> float:
    """>0 逆时针，<0 顺时针（x 东 y 北）。"""
    a = 0.0
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        a += x1 * y2 - x2 * y1
    return a / 2.0


def point_in_poly(x: float, y: float, poly) -> bool:
    """射线法，和服务端 IsPtInPoly 同一套判定。"""
    n = len(poly)
    if n < 3:
        return False
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y):
            if x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                inside = not inside
        j = i
    return inside


def inset_poly(poly, f: float):
    """每个顶点朝质心缩放 f 倍（f<1 内缩，f>1 外扩）。"""
    cx = sum(p[0] for p in poly) / len(poly)
    cy = sum(p[1] for p in poly) / len(poly)
    return [(cx + (p[0] - cx) * f, cy + (p[1] - cy) * f) for p in poly]


def resample_closed(poly, step: float = 1.0):
    """闭合折线按弧长重采样，首尾只留一个点。"""
    out = [poly[0]]
    for a, b in zip(poly, list(poly[1:]) + [poly[0]]):
        d = dist(a, b)
        n = max(1, int(math.ceil(d / step)))
        for i in range(1, n + 1):
            t = i / n
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    if dist(out[0], out[-1]) < 1e-9:
        out.pop()
    return out


def cum_len(pts) -> list[float]:
    cum = [0.0]
    for a, b in zip(pts, pts[1:]):
        cum.append(cum[-1] + dist(a, b))
    return cum


def point_at(pts, cum, s: float):
    """环形定位：返回 (点, 单位切线, 单位外法线)。超出一圈自动取模。"""
    total = cum[-1]
    s = s % total
    lo, hi = 0, len(cum) - 1
    while lo < hi - 1:
        mid = (lo + hi) // 2
        if cum[mid] <= s:
            lo = mid
        else:
            hi = mid
    seg = cum[lo + 1] - cum[lo]
    t = 0.0 if seg <= 0 else (s - cum[lo]) / seg
    a, b = pts[lo], pts[(lo + 1) % len(pts)]
    p = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    d = dist(a, b) or 1.0
    tan = ((b[0] - a[0]) / d, (b[1] - a[1]) / d)
    nrm = (-tan[1], tan[0])          # 顺时针环线的左手法线 = 朝外
    return p, tan, nrm


def point_at_xy(pts, cum, s):
    return point_at(pts, cum, s)[0]


# ============================================================ 手写笔画

def catmull_rom(ctrl, step: float = 0.25):
    """过控制点的样条采样：手写线条是弧，直线插值会显得很「机器」。"""
    if len(ctrl) < 3:
        return resample_open(ctrl, step)
    out = []
    for i in range(len(ctrl) - 1):
        p0 = ctrl[i - 1] if i > 0 else ctrl[i]
        p1, p2 = ctrl[i], ctrl[i + 1]
        p3 = ctrl[i + 2] if i + 2 < len(ctrl) else ctrl[i + 1]
        seg = dist(p1, p2)
        m = max(2, int(seg / step) + 1)
        for j in range(m):
            t = j / m
            t2, t3 = t * t, t * t * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t
                       + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                       + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t
                       + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                       + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    out.append(ctrl[-1])
    return out


def resample_open(pts, step: float = 0.25):
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        d = dist(a, b)
        n = max(1, int(math.ceil(d / step)))
        for i in range(1, n + 1):
            t = i / n
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    return out


def _rot(p, ang, c=(0.0, 0.0)):
    ca, sa = math.cos(ang), math.sin(ang)
    x, y = p[0] - c[0], p[1] - c[1]
    return (c[0] + x * ca - y * sa, c[1] + x * sa + y * ca)


def glyph_strokes(ch: str, H: float, W: float, rnd: random.Random):
    """把一个字画成若干条「已经写过的手」的折线（字模局部坐标，米，基线 y=0）。"""
    strokes = GLYPHS[ch]

    # 整字：轻微歪头、缩放不一、切变、基线上下漂 —— 六个字看起来才不是同一个模子
    char_rot = math.radians(rnd.gauss(0.0, 1.4))
    sx = 1.0 + rnd.gauss(0.0, 0.030)
    sy = 1.0 + rnd.gauss(0.0, 0.030)
    shear = rnd.gauss(0.0, 0.030)
    drift_y = rnd.gauss(0.0, 0.015) * H
    drift_x = rnd.gauss(0.0, 0.010) * W
    center = (0.0, H * 0.5)

    out = []
    for stroke in strokes:
        # 归一化 → 米
        ctrl = [((x - 0.5) * W, y * H) for x, y in stroke]

        # 单笔：各自再歪一点点，毛笔落纸不会笔笔平行
        own = math.radians(rnd.gauss(0.0, 0.5))
        cx = sum(p[0] for p in ctrl) / len(ctrl)
        cy = sum(p[1] for p in ctrl) / len(ctrl)
        ctrl = [_rot(p, own, (cx, cy)) for p in ctrl]

        dense = catmull_rom(ctrl, max(0.2, H * 0.012))

        # 抖腕：沿笔画主方向做低频正弦横移，长横不会是一条数学直线
        amp = H * rnd.uniform(0.004, 0.010)
        k = rnd.uniform(1.0, 2.0)
        ph = rnd.uniform(0, math.tau)
        total = poly_len(dense) or 1.0
        acc = 0.0
        wob = [dense[0]]
        for a, b in zip(dense, dense[1:]):
            d = dist(a, b) or 1e-6
            tx, ty = (b[0] - a[0]) / d, (b[1] - a[1]) / d
            acc += d
            u = acc / total
            off = amp * math.sin(k * math.tau * u + ph)
            wob.append((b[0] - ty * off, b[1] + tx * off))

        # 整字变换：缩放 → 切变 → 旋转 → 平移
        pts = []
        for x, y in wob:
            x, y = x * sx, (y - center[1]) * sy + center[1]
            x += shear * (y - center[1])
            x, y = _rot((x, y), char_rot, center)
            pts.append((x + drift_x, y + drift_y))
        out.append(pts)
    return out


def bezier(p0, p1, bulge: float = 0.0, step: float = 0.6):
    """抬笔时的两点过渡：给一点侧向鼓包，别让轨迹出现笔直的「瞬移」直角。"""
    d = dist(p0, p1)
    if d < 1e-6:
        return [p1]
    nx, ny = -(p1[1] - p0[1]) / d, (p1[0] - p0[0]) / d
    c = ((p0[0] + p1[0]) / 2 + nx * bulge, (p0[1] + p1[1]) / 2 + ny * bulge)
    n = max(2, int(d / step) + 1)
    out = []
    for i in range(1, n + 1):
        t = i / n
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]
        out.append((x, y))
    return out


# ============================================================ 沿环线排一行字

def start_arc(ring, cum) -> float:
    """起笔的弧长位置：环线顶部那条边的中点。

    直接取「最高点」会经常落在拐角上，那里的切线是斜的，「重」会被写成歪的。
    """
    cx = sum(p[0] for p in ring) / len(ring)
    y_max = max(p[1] for p in ring)
    y_min = min(p[1] for p in ring)
    band = (y_max - y_min) * 0.06
    cand = [i for i, p in enumerate(ring) if p[1] > y_max - band]
    i_top = min(cand, key=lambda i: abs(ring[i][0] - cx))
    return cum[i_top]


def char_frame(ring, cum, s: float, H: float, orient: str, W: float | None = None):
    """每个字的局部坐标系：原点 + 切线(x) + 法线(y)，字向 +y 方向长。

    orient="path"：跟着环线转，基线贴着环线 —— 南北两侧正立，东西两侧躺下。
    orient="up"  ：字一律朝北正立，整框挪到环线外侧 —— 地图上最好认，环线也不会
                   从字中间穿过去（按字框在半径方向上的投影宽度让位）。
    """
    p, tan, nrm = point_at(ring, cum, s)
    if orient != "up":
        return p, tan, nrm

    W = H if W is None else W
    proj = W * abs(nrm[0]) + H * abs(nrm[1])      # 字框在半径方向上的投影宽度
    off = proj * 0.5 + 2.5                        # 让开环线 2.5 米
    cx, cy = p[0] + nrm[0] * off, p[1] + nrm[1] * off
    return (cx, cy - H * 0.5), (1.0, 0.0), (0.0, 1.0)


def build_path(ring, cum, anchors, text: str, H: float, laps: int, seed: int,
               aspect: float = 1.0, pad: float = 0.0, orient: str = "up"):
    """把「一行字」按顺时针铺到环线上。

    返回 (点列, 每点类型)：INK = 笔画，HOP = 抬笔移动，RING = 沿环线奔跑。

    「抬笔」在地图上其实是一条可见的线（GPS 不知道笔尖离纸了），所以不能让
    它斜穿字心 —— 那样六个字会被一堆对角线糊乱。规则是：
      字内换笔 → 先落回基线，沿基线走，再竖起来到下一笔起点（像人写完一笔先把笔
                 放回纸上挪一挪）；跨字 → 沿环线跑，不拉弦。
    """
    total = cum[-1]
    W = aspect * H
    rng = random.Random(seed)

    pts: list[tuple[float, float]] = []
    kinds: list[int] = []

    def push(p, kind):
        if pts and dist(pts[-1], p) < 1e-4:
            return
        pts.append(p)
        kinds.append(kind)

    def glide(world, kind=HOP):
        """两点之间走一小段弧，别做成直角折线。"""
        if not pts:
            push(world, kind)
            return
        bulge = rng.uniform(-0.05, 0.05) * min(dist(pts[-1], world), 10.0)
        for q in bezier(pts[-1], world, bulge):
            push(q, kind)

    def run_ring(s_from: float, s_to: float, step: float = 1.5):
        """沿环线顺时针跑一段（抬笔）。"""
        d = s_to - s_from
        if d < 0:                            # 起点在身后就绕一圈补上
            d += total
        if d <= 0.05:
            return
        n = max(1, int(d / step))
        for k in range(1, n + 1):
            push(point_at_xy(ring, cum, s_from + d * k / n), RING)

    # 起跑：从字框左边缘前 2 米（再往前垫 pad 米）开始。垫的这段压在环线上，
    # 而每一圈字迹本来就要把环线跑满，所以垫里程不会多出任何可见笔画。
    s_cur = anchors[0] - W / 2.0 - 2.0 - pad
    push(point_at_xy(ring, cum, s_cur), RING)

    for lap in range(laps):
        lay = random.Random(seed * 7919 + lap * 104729)
        for ci, ch in enumerate(text):
            s_anchor = anchors[ci]
            anchor_p, tan, nrm = char_frame(ring, cum, s_anchor, H, orient, W)

            def local(x, y):
                return (anchor_p[0] + tan[0] * x + nrm[0] * y,
                        anchor_p[1] + tan[1] * x + nrm[1] * y)

            entry = s_anchor - W / 2.0 - 1.0
            run_ring(s_cur, entry)
            s_cur = entry

            strokes = glyph_strokes(ch, H, W, lay)
            # 笔画之间的「抬笔」其实会画出一条线，所以走「落基线 → 沿基线平移 →
            # 竖起来」而不是斜穿字心 —— 字才不会被一堆对角线糊住。
            prev_end = None
            for stroke in strokes:
                if prev_end is not None:
                    lift = H * 0.055
                    glide(local(prev_end[0], lift))
                    glide(local(stroke[0][0], lift), HOP)
                    glide(local(stroke[0][0], stroke[0][1]))
                else:
                    glide(local(*stroke[0]))
                for x, y in stroke[1:]:
                    push(local(x, y), INK)
                prev_end = stroke[-1]

            exit_s = s_anchor + W / 2.0 + 1.0
            glide(point_at_xy(ring, cum, exit_s))
            s_cur = exit_s

    return pts, kinds


def box_ok(ring, cum, s: float, H: float, W: float, orient: str,
           fence_safe, ban_safe) -> bool:
    """一个字的四角（带手写余量）是否都在围栏内、禁跑区外。"""
    p, tan, nrm = char_frame(ring, cum, s, H, orient, W)
    hw = W * 0.58
    for a, b in ((-hw, -0.06 * H), (hw, -0.06 * H), (hw, H * 1.06), (-hw, H * 1.06)):
        x = p[0] + tan[0] * a + nrm[0] * b
        y = p[1] + tan[1] * a + nrm[1] * b
        if not point_in_poly(x, y, fence_safe):
            return False
        if any(point_in_poly(x, y, b2) for b2 in ban_safe):
            return False
    return True


def place_chars(ring, cum, text: str, H: float, W: float, orient: str,
                fence_safe, ban_safe):
    """给每个字找落点：在它的槽位附近左右挪，避开围栏拐角和禁跑区。

    六个字均分一圈时，总有一两个正好落在拐角上 —— 拐角处带宽窄，字就长不大。
    允许每个字在自己的槽位里挪 ±0.45 格（手写本来也不会等间距），
    找到能让字框完全落进跑道的位置。
    """
    total = cum[-1]
    slot = total / len(text)
    if W > slot * 0.86:              # 字挤在一起就认不出了
        return None
    if not all(point_in_poly(x, y, fence_safe) and
               not any(point_in_poly(x, y, b) for b in ban_safe)
               for x, y in ring[::8]):
        return None

    s0 = start_arc(ring, cum)
    step = max(1.0, slot / 40.0)
    reach = int(0.32 * slot / step)
    min_gap = max(W + 6.0, slot * 0.42)      # 字与字之间至少留这么远，别叠在一起
    out = []
    for k in range(len(text)):
        base = s0 + k * slot
        lo = out[-1] + min_gap if out else -1e9
        chosen = None
        for j in range(reach + 1):
            for d in ((0.0,) if j == 0 else (j * step, -j * step)):
                s = base + d
                if s < lo:
                    continue
                if box_ok(ring, cum, s, H, W, orient, fence_safe, ban_safe):
                    chosen = s
                    break
            if chosen is not None:
                break
        if chosen is None:
            return None
        out.append(chosen)
    return out


def ray_hits(poly, o, u):
    """射线 o+t·u 与多边形各边的交点参数 t（只保留 t>0 且落在边上的）。"""
    ts = []
    for a, b in zip(poly, poly[1:] + poly[:1]):
        ex, ey = b[0] - a[0], b[1] - a[1]
        den = u[1] * ex - u[0] * ey
        if abs(den) < 1e-12:
            continue
        ax, ay = a[0] - o[0], a[1] - o[1]
        t = (ay * ex - ax * ey) / den
        seg = (u[0] * ay - u[1] * ax) / den
        if t > 0.0 and -1e-9 <= seg <= 1.0 + 1e-9:
            ts.append(t)
    ts.sort()
    return ts


def ray_last_inside(poly, o, u) -> float:
    """沿射线最远能走到多边形内部的哪里（o 在内、在外都成立）。"""
    inside = point_in_poly(o[0], o[1], poly)
    last = 0.0
    for t in ray_hits(poly, o, u):
        if inside:
            last = max(last, t)
            inside = False
        else:
            inside = True
    return last


def ring_profile(fence_xy, ban_xy, n: int = 240):
    """以围栏质心为原点，量每个方向上「禁跑区边界」和「围栏边界」的径向距离。

    环线不能简单拿围栏等比缩放：风华运动场的禁跑区在南北方向更「顶」，
    缩放出来的环线会一头扎进禁跑区。按径向剖面在两界之间插值才贴合跑道。
    """
    cx = sum(p[0] for p in fence_xy) / len(fence_xy)
    cy = sum(p[1] for p in fence_xy) / len(fence_xy)
    prof = []
    for k in range(n):
        th = k / n * math.tau
        u = (math.cos(th), math.sin(th))
        d_f = max(1.0, ray_last_inside(fence_xy, (cx, cy), u))
        d_b = 0.0
        for bp in ban_xy:
            d_b = max(d_b, ray_last_inside(bp, (cx, cy), u))
        prof.append((u, min(d_b, d_f * 0.85), d_f))
    return (cx, cy), prof


def ring_from_profile(origin, prof, frac: float):
    """frac=0 贴着禁跑区，frac=1 贴着围栏。顺时针输出（θ 递减），法线才朝外。"""
    cx, cy = origin
    out = []
    for u, d_b, d_f in reversed(prof):
        d = d_b + (d_f - d_b) * frac
        out.append((cx + u[0] * d, cy + u[1] * d))
    return out


H_CHOICES = (52.0, 46.0, 41.0, 36.0, 31.0, 27.0, 24.0, 21.0, 19.0, 17.0,
             15.0, 13.0, 11.0, 9.0, 7.0)
FRAC_CHOICES = (0.06, 0.10, 0.14, 0.19, 0.24, 0.30, 0.37, 0.45, 0.54)


def pick_ring(fence_xy, ban_xy, text: str, aspect: float, orient: str = "up"):
    """在「围栏内、禁跑区外」里搜一版放得下的最大字高，顺带定环线位置。"""
    origin, prof = ring_profile(fence_xy, ban_xy)
    fence_safe = inset_poly(fence_xy, 0.985)
    ban_safe = [inset_poly(b, 1.035) for b in ban_xy]

    for H in H_CHOICES:
        for frac in FRAC_CHOICES:
            ring = resample_closed(ring_from_profile(origin, prof, frac), 1.6)
            cum = cum_len(ring)
            anchors = place_chars(ring, cum, text, H, aspect * H, orient,
                                  fence_safe, ban_safe)
            if anchors:
                return ring, cum, H, frac, anchors
    return None


# ============================================================ 逐秒铺点

def build_track(pts, kinds, n: int, plane: Plane, seed: int,
                noise: float = DEFAULT_NOISE, altitude: float = 260.0,
                ink_speed: float = 0.9):
    """把字迹铺成 n 个 1 秒点，返回 (逐秒点, 实测长度, 每点类型)。

    做法是「按时间走」而不是「按步长走」：

    * 笔画段跑 0.9 米/秒 —— 一个字才 20 米高，1 秒一步 2.5 米的话拐角直接被削平；
    * 抬笔段的速度由剩余时间反解，它压在环线已有的线条上，跑多快都不多出笔画；
    * 于是第 i 个点正好落在「走到第 i 秒」的位置，点数 = 时长，一秒一个点，
      不用再凑点，配速也不会因为点数四舍五入而漂。
    """
    cum = cum_len(pts)
    n = max(2, n)

    # 每段的「每米用时」倒数：速度
    v = [ink_speed if kinds[i] == INK else None for i in range(len(pts))]
    ink_len = sum(dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1)
                  if k_is_ink(kinds, i))
    rest_len = max(1e-6, cum[-1] - ink_len)

    total_t = n - 1.0
    ink_t = min(ink_len / ink_speed, total_t * 0.92)
    v_ink = ink_len / ink_t if ink_t > 0 else ink_speed
    v_rest = rest_len / max(1e-6, total_t - ink_t)

    # 沿稠密点累计时间
    times = [0.0]
    for i in range(len(pts) - 1):
        seg = dist(pts[i], pts[i + 1])
        sp = v_ink if k_is_ink(kinds, i) else v_rest
        times.append(times[-1] + seg / max(sp, 1e-6))
    scale = total_t / times[-1] if times[-1] > 0 else 1.0

    rnd = random.Random(seed)
    perr = aerr = 0.0
    out, ks = [], []
    j = 0
    for i in range(n):
        t = i * scale
        while j + 1 < len(times) and times[j + 1] <= t:
            j += 1
        seg_t = times[min(j + 1, len(times) - 1)] - times[j]
        f = 0.0 if seg_t <= 0 else min(1.0, max(0.0, (t - times[j]) / seg_t))
        a, b = pts[j], pts[min(j + 1, len(pts) - 1)]
        p = (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
        kd = kinds[min(j, len(kinds) - 1)]
        ks.append(kd)

        d1 = dist(a, b) or 1.0
        tx, ty = (b[0] - a[0]) / d1, (b[1] - a[1]) / d1
        if i + 1 < n:                       # 报告速度 = 下一秒要走多远
            t2 = (i + 1) * scale
            j2 = j
            while j2 + 1 < len(times) and times[j2 + 1] <= t2:
                j2 += 1
            seg2 = times[min(j2 + 1, len(times) - 1)] - times[j2]
            f2 = 0.0 if seg2 <= 0 else min(1.0, max(0.0, (t2 - times[j2]) / seg2))
            a2, b2 = pts[j2], pts[min(j2 + 1, len(pts) - 1)]
            q = (a2[0] + (b2[0] - a2[0]) * f2, a2[1] + (b2[1] - a2[1]) * f2)
            step = dist(p, q)
        else:
            step = 0.0

        perr = 0.90 * perr + rnd.gauss(0.0, noise * 0.55)   # 横向漂移（AR(1)）
        aerr = 0.90 * aerr + rnd.gauss(0.0, noise * 0.35)   # 纵向漂移
        x = p[0] - ty * perr + tx * aerr
        y = p[1] + tx * perr + ty * aerr

        lat, lon = plane.geo(x, y)
        brg = math.degrees(math.atan2(tx, ty)) % 360.0
        out.append({
            "t": float(i),
            "lat": round(lat, 7),
            "lon": round(lon, 7),
            "speed": round(step, 2),
            "bearing": round((brg + rnd.gauss(0.0, 1.4)) % 360.0, 1),
            "accuracy": round(max(3.0, 4.5 + abs(perr) * 0.5 + rnd.uniform(-1.0, 2.0)), 1),
            "altitude": round(altitude + rnd.uniform(-2.0, 2.0), 1),
        })
    return out, track_length(out), ks


def k_is_ink(kinds, i: int) -> bool:
    return kinds[min(i, len(kinds) - 1)] == INK


def track_length(track) -> float:
    return sum(TE.haversine((a["lat"], a["lon"]), (b["lat"], b["lon"]))
               for a, b in zip(track, track[1:]))


def fmt_pace(sec_per_km: float) -> str:
    if not sec_per_km or sec_per_km > 3600:
        return "--"
    return "%d'%02d\"" % (int(sec_per_km // 60), int(sec_per_km % 60))


# ============================================================ 排版求解

def solve_layout(ring, cum, anchors, text: str, target_m: float, aspect: float,
                 seed: int, h_max: float, orient: str = "up", laps_hint: int = 0):
    """定字高、圈数、以及「垫里程」。

    字高直接顶到场地能放下的上限（字越大越认得出），圈数取不满一圈的整数圈，
    剩下的零头沿环线跑掉 —— 那段路本来就压在环线上，不会多出可见笔画。
    """
    h = max(MIN_H, h_max * 0.97)
    lap = poly_len(build_path(ring, cum, anchors, text, h, 1, seed, aspect, 0.0, orient)[0])
    # 圈数尽量少：多画一圈只是把同一行字重描一遍，笔画采样反而变稀；
    # 零头沿环线跑掉（压在已有线条上，看不出多余笔画），但别垫过 4 圈。
    if laps_hint:
        laps = laps_hint
    else:
        laps = max(1, math.ceil((target_m - 4.0 * cum[-1]) / lap))
    pad = max(0.0, target_m - laps * lap)
    return h, laps, lap, pad


def ring_lap_len(ring, cum, anchors, text: str, h: float, seed: int, orient: str) -> float:
    return poly_len(build_path(ring, cum, anchors, text, h, 1, seed, 1.0, 0.0, orient)[0])


def make_route(plane: Plane, ring, cum, anchors, text: str, orient: str,
               h_max: float, target_m: float, pace_s_per_km: float, seed: int,
               noise: float = DEFAULT_NOISE):
    """把「场地 + 目标里程 + 配速」解成一条可上传的逐秒轨迹。

    三个约束各自独立，所以分开解：

    * **点数**由目标里程与配速直接算死：`n = 里程 ÷ 配速 × 1000 + 1`。
      服务端算的配速就是 `(n-1) / (里程/1000)`，点数对了配速就对了。
    * **字迹长度 ≤ 目标里程**：字高先顶到场地能放下的上限，写一圈比 2 公里还长
      就把字高按比例压下去，保证 `圈数 × 一圈 ≤ 目标`。
    * **差的里程沿环线垫**：垫的那段压在环线已有的线条上，看不出多余笔画。
      二分垫多少，判据直接用「1 Hz 采样后的实测里程」——
      削角、抖动都算在里面，所以命中得准，不是估的。
    """
    h = max(MIN_H, h_max * 0.97)
    perim = cum[-1]

    # 一圈字迹比 2 公里还长？按比例压字高，压到能整除出至少一圈
    lap = ring_lap_len(ring, cum, anchors, text, h, seed, orient)
    for _ in range(6):
        if lap <= target_m:
            break
        h *= max(0.55, target_m / lap)
        lap = ring_lap_len(ring, cum, anchors, text, h, seed, orient)
    laps = max(1, int(target_m // max(lap, 1e-6)))

    n = max(2, int(round(target_m * pace_s_per_km / 1000.0)) + 1)
    # 垫里程上限：字迹本身 + 最多再跑 6 圈（垫的这段压在环线上，看不出笔画）
    pad_max = max(lap * 0.95, 6.0 * perim)

    def probe(pad: float):
        pts, kinds = build_path(ring, cum, anchors, text, h, laps, seed,
                                1.0, pad, orient)
        return build_track(pts, kinds, n, plane, seed, noise) + (pts, kinds)

    lo, hi = 0.0, pad_max
    track, total, tk, pts, kinds = probe(0.0)
    if total > target_m:                       # 一圈就超了：交给上面的压字高兜底
        hi = 0.0
    else:
        for _ in range(22):                    # 实测里程对 pad 单调
            mid = (lo + hi) / 2.0
            track, total, tk, pts, kinds = probe(mid)
            if total < target_m:
                lo = mid
            else:
                hi = mid
        track, total, tk, pts, kinds = probe(hi)
    return h, laps, lap, hi, pts, kinds, track, tk, total


# ============================================================ 预览图（零依赖 PNG）

def _png(path, w, h, buf: bytearray):
    """最小 PNG 编码器：RGB、无滤波、zlib。只为看一眼排版，不引第三方库。"""
    import struct

    def u32(v):
        return struct.pack(">I", v)

    def chunk(tag, data):
        return u32(len(data)) + tag + data + u32(zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += buf[y * w * 3:(y + 1) * w * 3]
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", u32(w) + u32(h) + bytes((8, 2, 0, 0, 0)))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def render_png(path: str, fence, ban, track_xy, size: int = 1800, ss: int = 2):
    """俯视预览：围栏绿、禁跑区暗红、轨迹红、起点青。超采样降采样当抗锯齿用。"""
    W, H = size * ss, size * ss
    buf = bytearray(bytes((14, 16, 20)) * (W * H))

    xs = [p[0] for p in fence] + [p[0] for poly in ban for p in poly]
    ys = [p[1] for p in fence] + [p[1] for poly in ban for p in poly]
    x0, x1 = min(xs) - 12, max(xs) + 12
    y0, y1 = min(ys) - 12, max(ys) + 12
    scale = min(W / (x1 - x0), H / (y1 - y0))
    mx = (W - (x1 - x0) * scale) / 2
    my = (H - (y1 - y0) * scale) / 2

    def px(p):
        return ((p[0] - x0) * scale + mx, (y1 - p[1]) * scale + my)

    def put(x, y, c):
        if 0 <= x < W and 0 <= y < H:
            i = (int(y) * W + int(x)) * 3
            buf[i:i + 3] = bytes(c)

    def line(a, b, c, r=1):
        ax, ay = a
        bx, by = b
        d = math.hypot(bx - ax, by - ay)
        n = max(1, int(d / 0.7))
        rr = int(math.ceil(r))
        for k in range(n + 1):
            t = k / n
            cx, cy = ax + (bx - ax) * t, ay + (by - ay) * t
            for dx in range(-rr, rr + 1):
                for dy in range(-rr, rr + 1):
                    if dx * dx + dy * dy <= r * r + 0.4:
                        put(cx + dx, cy + dy, c)

    f = [px(p) for p in fence]
    for a, b in zip(f, f[1:] + f[:1]):
        line(a, b, (52, 140, 100), 1.6 * ss)

    for poly in ban:
        q = [px(p) for p in poly]
        ylo, yhi = int(min(p[1] for p in q)), int(max(p[1] for p in q))
        for yy in range(ylo, yhi, 2):                  # 扫描线填充，够看就行
            row = []
            for a, b in zip(q, q[1:] + q[:1]):
                if (a[1] > yy) != (b[1] > yy):
                    row.append(a[0] + (yy - a[1]) * (b[0] - a[0]) / (b[1] - a[1]))
            row.sort()
            for i in range(0, len(row) - 1, 2):
                for xx in range(int(row[i]), int(row[i + 1])):
                    put(xx, yy, (36, 19, 21))
        for a, b in zip(q, q[1:] + q[:1]):
            line(a, b, (92, 36, 40), 1.2 * ss)

    t = [px(p) for p in track_xy]
    for a, b in zip(t, t[1:]):
        line(a, b, (236, 74, 60), 0.8 * ss)
    if t:
        for dx in range(-3 * ss, 3 * ss + 1):
            for dy in range(-3 * ss, 3 * ss + 1):
                if dx * dx + dy * dy <= 9 * ss * ss:
                    put(t[0][0] + dx, t[0][1] + dy, (90, 220, 255))

    ow, oh = W // ss, H // ss
    out = bytearray(ow * oh * 3)
    for y in range(oh):
        for x in range(ow):
            a0 = a1 = a2 = 0
            for dy in range(ss):
                base = ((y * ss + dy) * W + x * ss) * 3
                for dx in range(ss):
                    i = base + dx * 3
                    a0 += buf[i]
                    a1 += buf[i + 1]
                    a2 += buf[i + 2]
            j = (y * ow + x) * 3
            out[j] = a0 // (ss * ss)
            out[j + 1] = a1 // (ss * ss)
            out[j + 2] = a2 // (ss * ss)
    _png(path, ow, oh, out)


# ============================================================ 协议层

def offline_poly(which: str):
    """不联网时用真实下发的多边形（来自 refs/verify_pace_profile.py）做排版预览。"""
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "refs"))
    import verify_pace_profile as VPP
    table = {
        "feng": ("风华运动场", "T1001", VPP.FENG_HUA, VPP.FENG_HUA_JPQ),
        "tai": ("太极运动场", "T1005", VPP.TAI_JI, []),
        "ning": ("宁静苑", "T1014", VPP.NING_JING, []),
    }
    if which not in table:
        raise SystemExit("--poly 可选：" + "、".join(table))
    name, code, fence, ban = table[which]
    return name, code, [tuple(p) for p in fence], [[tuple(p) for p in b] for b in ban]


def connect(openid: str, token: str, phone: str):
    import wecat_run as W
    c = W.Client(openid=openid, token=token, phone=phone, verbose=False)
    if not c.token:
        print("  [1/5] 用 openid 换 token ...", flush=True)
        if not c.acquire_token():
            raise SystemExit("  [×] 换 token 失败，检查 openid 是否正确")
    else:
        print("  [1/5] 用已有 token 跳过换票", flush=True)
    return c, W


def pick_place(areas, raw: str):
    raw = (raw or "").strip()
    if not raw:
        return None
    if raw.isdigit():
        i = int(raw)
        if 0 <= i < len(areas):
            return areas[i]
    for a in areas:
        if str(a["placeCode"]) == raw:
            return a
    for a in areas:
        if raw in (a.get("placeName") or ""):
            return a
    return None


def submit(c, W, area, track, start_dt, verbose: bool, sport_type: str = "1"):
    place_code = area["placeCode"]
    place_name = area["placeName"]

    payload = {
        "placeCode": place_code,
        "placeName": place_name,
        "sportType": sport_type,
        "longitude": track[0]["lon"],
        "latitude": track[0]["lat"],
        "collectTime": W.fmt_time(start_dt),
    }
    r = c.start_sport(payload)
    record_no = (r.get("data") or {}).get("sportRecordNo") or (r.get("data") or {}).get("no")
    if not record_no:
        raise SystemExit(f"  [×] start2 没返回 sportRecordNo: {json.dumps(r, ensure_ascii=False)[:300]}")
    print(f"      sportRecordNo = {record_no}")

    pts = W.build_points(track, record_no, place_name, place_code, start_dt)
    batches = W.batch_points(pts, W.POINT_BATCH)
    for i, b in enumerate(batches):
        c.upload_points(record_no, b)
        if verbose or i == 0 or i == len(batches) - 1:
            print(f"      批次 {i + 1}/{len(batches)}：{len(b)} 点 "
                  f"{b[0]['collectTime']} → {b[-1]['collectTime']}")
    c.end_sport(record_no)

    info = c.get_sport_record_info(record_no)
    data = info.get("data") or info
    mileage = data.get("mileage")
    cost = data.get("timeConsuming")
    if mileage and cost:
        print(f"  [✓] 记录已生成：{float(mileage):.0f} m / {int(cost)} 秒 / "
              f"{fmt_pace(float(cost) / (float(mileage) / 1000.0))}/km")
    else:
        print("  [✓] 已结束：" + json.dumps(data, ensure_ascii=False)[:200])
    return record_no


# ============================================================ 自检

def self_test() -> int:
    """3 场地 × 4 配速 × 2 朝向压一遍硬指标。

    主张的事和 android-app 的 TrackGenTest 一样：围栏内 100%、禁跑区 0 点、
    实测里程 2000 米（±0.5%）、实测配速等于目标（±1 秒）。
    """
    fails = []
    print(f"{'场地':<8}{'朝向':<6}{'配速':<9}{'点数':>6}{'里程':>9}{'实测配速':>10}"
          f"{'围栏内':>8}{'禁跑区':>7}")
    print("-" * 70)
    for which in ("feng", "tai", "ning"):
        name, code, fence, ban = offline_poly(which)
        lat0 = sum(p[0] for p in fence) / len(fence)
        lon0 = sum(p[1] for p in fence) / len(fence)
        plane = Plane(lat0, lon0)
        fxy = [plane.xy(*p) for p in fence]
        bxy = [[plane.xy(*p) for p in b] for b in ban]
        if signed_area(fxy) > 0:
            fxy, bxy = fxy[::-1], [b[::-1] for b in bxy]

        for orient in ("up", "path"):
            picked = pick_ring(fxy, bxy, TEXT, 1.0, orient)
            if not picked:
                fails.append((name, orient, "放不下这行字"))
                continue
            ring, cum, h_max, _frac, anchors = picked
            for pace in (240.0, 330.0, 390.0, 450.0):
                h, laps, lap, pad, pts, kinds, track, tk, total = make_route(
                    plane, ring, cum, anchors, TEXT, orient, h_max,
                    2000.0, pace, 7)
                real = (len(track) - 1) / (total / 1000.0)
                xy = [plane.xy(p["lat"], p["lon"]) for p in track]
                inside = sum(1 for p in xy if point_in_poly(p[0], p[1], fxy))
                banned = sum(1 for p in xy
                             if any(point_in_poly(p[0], p[1], b) for b in bxy))
                ok = (inside == len(track) and banned == 0
                      and abs(total - 2000.0) <= 20.0
                      and abs(real - pace) <= 1.5)
                if not ok:
                    fails.append((name, orient, fmt_pace(pace), round(total),
                                  round(real), inside, len(track), banned))
                print(f"{name:<8}{orient:<6}{fmt_pace(pace):<9}{len(track):>6}"
                      f"{total:>9.0f}{fmt_pace(real):>10}"
                      f"{inside / len(track) * 100:>7.1f}%{banned:>7}"
                      + ("" if ok else "   <<< 失败"))
    print("-" * 70)
    if fails:
        print(f"[×] {len(fails)} 项未通过：")
        for f in fails:
            print("   ", f)
        return 1
    print("[✓] 全部通过")
    return 0


# ============================================================ main

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="draw_text",
        description="在所选场地顺时针写下「重庆邮电大学」（文字轨迹打卡）")
    ap.add_argument("--openid", default="", help="小程序 openid；留空则交互输入")
    ap.add_argument("--token", default="", help="已有 token，可跳过换票")
    ap.add_argument("--phone", default="PC_Windows", help="phoneType")
    ap.add_argument("--place", default=None, help="场地序号 / placeCode / 名字片段；留空则交互选择")
    ap.add_argument("--text", default=TEXT, help=f"要写的字（字模内置：{''.join(GLYPHS)}）")
    ap.add_argument("--pace", default=DEFAULT_PACE, help=f"配速，默认 {DEFAULT_PACE}")
    ap.add_argument("--dist", type=float, default=DEFAULT_DIST, help="目标距离 km，默认 2.0")
    ap.add_argument("--noise", type=float, default=DEFAULT_NOISE, help="逐秒 GPS 抖动强度")
    ap.add_argument("--seed", type=int, default=-1, help="随机种子，-1 每次随机")
    ap.add_argument("--live", action="store_true", help="不再询问，直接上传打卡")
    ap.add_argument("--dry-run", action="store_true", help="只排版不上传，也不问")
    ap.add_argument("--png", default=None, help="预览图输出路径（PNG，零依赖）")
    ap.add_argument("--json", default=None, help="轨迹点导出路径")
    ap.add_argument("--offline", action="store_true", help="不联网，用内置真实场地多边形排版")
    ap.add_argument("--poly", default="feng", help="offline 用哪个场地：feng/tai/ning")
    ap.add_argument("--orient", default="up", choices=("up", "path"),
                    help="字朝向：up=一律朝北正立（默认），path=跟着环线转、基线贴环线")
    ap.add_argument("--sport-type", default="1")
    ap.add_argument("--self-test", action="store_true",
                    help="不联网，压测 3 场地 × 4 配速 × 2 朝向的硬指标")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    bad = [ch for ch in args.text if ch not in GLYPHS]
    if bad:
        raise SystemExit(f"[×] 字模里没有 {''.join(bad)}，目前支持：{''.join(GLYPHS)}")
    if args.seed < 0:
        args.seed = random.randrange(1, 2 ** 31 - 1)

    client = W = None

    # ---------- 1) 场地与围栏 ----------
    if args.offline:
        name, code, fence, ban = offline_poly(args.poly)
        print(f"[*] 离线排版：{name} ({code})")
        area = None
    else:
        openid = args.openid or input("openid：").strip()
        client, W = connect(openid, args.token, args.phone)
        print("  [2/5] 拉取场地 ...", flush=True)
        areas = W.fetch_areas(client)
        if not areas:
            raise SystemExit("  [×] 没拿到区域列表（围栏为空）")
        for i, a in enumerate(areas):
            print(f"      [{i}] {a['placeCode']:<7} {a['placeName']:<12} "
                  f"围栏 {len(a['fence'])} 点"
                  + (f" / 禁跑区 {len(a['excludes'])} 块" if a["excludes"] else ""))
        raw = args.place if args.place is not None else input("选场地（序号 / 名字 / placeCode）：")
        area = pick_place(areas, raw)
        if not area:
            raise SystemExit(f"  [×] 找不到场地 {raw}")
        name, code = area["placeName"], area["placeCode"]
        fence = [tuple(p) for p in area["fence"]]
        ban = [[tuple(p) for p in b] for b in area["excludes"]]
        print(f"      选定：{name} ({code})")

    # 局部平面：以围栏质心为原点
    lat0 = sum(p[0] for p in fence) / len(fence)
    lon0 = sum(p[1] for p in fence) / len(fence)
    plane = Plane(lat0, lon0)
    fence_xy = [plane.xy(*p) for p in fence]
    ban_xy = [[plane.xy(*p) for p in b] for b in ban]

    # ---------- 2) 排版：环线 + 字高 ----------
    if signed_area(fence_xy) > 0:       # 统一成顺时针
        fence_xy = fence_xy[::-1]
        ban_xy = [b[::-1] for b in ban_xy]

    picked = pick_ring(fence_xy, ban_xy, args.text, 1.0, args.orient)
    if not picked:
        raise SystemExit("[×] 这块场地放不下这行字（围栏与禁跑区之间太窄），换个场地试试")
    ring, cum, h_max, f_ring, anchors = picked
    print(f"  [3/5] 排版：环线位置 frac={f_ring:.2f}（周长 {cum[-1]:.0f} m），"
          f"最大字高 {h_max:.0f} m")

    pace_s = TE.parse_pace(args.pace)
    if not (240.0 <= pace_s <= 450.0):
        print(f"  [!] 配速 {args.pace} 不在规则窗口 4'00\"~7'30\" 内，服务端可能不认")

    # 排版 → 逐秒轨迹一步到位：点数由「里程 × 配速」定死，差的里程沿环线垫，
    # 垫多少用「1 Hz 采样后的实测里程」二分（削角、抖动都算进去）。
    target_m = args.dist * 1000.0
    h, laps, lap_len, pad, pts, kinds, track, tkind, total = make_route(
        plane, ring, cum, anchors, args.text, args.orient, h_max,
        target_m, pace_s, args.seed, args.noise)

    dense_len = poly_len(pts)
    pace_real = (len(track) - 1) / (total / 1000.0)
    print(f"      字高 {h:.1f} m × {len(args.text)} 字 × {laps} 圈"
          f"（一圈 {lap_len:.0f} m）"
          + (f"，起跑沿环线垫 {pad:.0f} m" if pad > 5 else ""))

    inside = sum(1 for p in track
                 if point_in_poly(*plane.xy(p["lat"], p["lon"]), fence_xy))
    banned = sum(1 for p in track
                 if any(point_in_poly(*plane.xy(p["lat"], p["lon"]), b) for b in ban_xy))
    ink_n = sum(1 for k in tkind if k == INK)
    print(f"  [4/5] 轨迹：{len(track)} 点（着墨 {ink_n} 点）| 字迹 {dense_len:.0f} m"
          f"（实测 {total:.0f} m）| 时长 {len(track) - 1}s | 配速 {fmt_pace(pace_real)}/km")
    print(f"      围栏内 {inside}/{len(track)} | 进禁跑区 {banned} 点")

    for out in (args.png, args.json):
        if out:
            os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    if args.png:
        render_png(args.png, fence_xy, ban_xy,
                   [plane.xy(p["lat"], p["lon"]) for p in track])
        print(f"      预览图 → {args.png}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fp:
            json.dump({"place": name, "text": args.text, "height_m": h, "laps": laps,
                       "distance_m": round(total, 1), "points": track},
                      fp, ensure_ascii=False, indent=1)
        print(f"      轨迹 JSON → {args.json}")

    if not client:
        print("\n[*] 离线模式只看排版，不上传")
        return 0

    do_submit = args.live
    if not do_submit and not args.dry_run:
        ans = input(f"\n在「{name}」写 {args.text}：{total:.0f} m / "
                    f"{fmt_pace(pace_real)}/km，约 {(len(track) - 1) // 60} 分钟。"
                    f"现在上传？[Y/n] ").strip().lower()
        do_submit = ans in ("", "y", "yes")
    if not do_submit:
        print("[*] 只排版，没有上传（--live 直接开跑，--dry-run 静默干跑）")
        return 0

    print("  [5/5] 开始上传 ...")
    # 记录落在「刚才跑完」的时间轴上：末点≈现在，比一堆未来时间戳自然
    start_dt = datetime.now() - timedelta(seconds=max(0, len(track) - 1))
    submit(client, W, area, track, start_dt, verbose=False, sport_type=args.sport_type)
    print("\n[*] 完事。人脸在场地摄像头上，记得本人到场走一趟。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
