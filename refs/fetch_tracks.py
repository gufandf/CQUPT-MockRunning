#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""fetch_tracks.py —— 批量拉取历史锻炼记录与轨迹点

协议逆向自小程序 wx13b7520eb7351f4f，实测于 2026-11-12。

两个接口的语义（实测纠正了 FINDINGS.md 里的旧描述）：

    GET /sportRecord/mobile/list/{pageNo}/{pageSize}
        → data.list[]  记录元数据；分页从 1 开始，pageSize 上限 100
                         （传 500 也只回 100，剩余靠 totalPage 翻页）

    GET /sportRecord/info/{sportRecordNo}
        → data.points  **经常是空数组**。只有极少数记录（短跑、失败记录）
                        带点。长跑的 points 恒为 []，别拿它当轨迹源。
        → data.mileage/timeConsuming 也是 0，和列表里的值对不上。

    GET /sportPoint/listAll/{sportRecordNo}          ← 真正的轨迹源
        → data = [{sportPointNo, longitude, latitude, collectTime, isValid, ...}]
        → 参数虽然叫 listAll{v} 看着像 placeCode，实际吃的是 sportRecordNo
        → **返回顺序是时间倒序**（最新在前），入库时要 reverse
        → 记录号不存在时返回 200 + []，不报错

用法：
    python refs/fetch_tracks.py --openid OPENID                # 全部 62 条
    python refs/fetch_tracks.py --openid OPENID --pages 2      # 只翻 2 页
    python refs/fetch_tracks.py --openid OPENID --geojson out.geojson
    python refs/fetch_tracks.py --openid OPENID --no-points    # 只列表头
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from wecat_run import Client  # noqa: E402

PAGE_MAX = 100          # 服务端 pageSize 硬上限，实测再大也截到 100


# ------------------------------------------------------------------ 轨迹换算

def haversine(lat1, lon1, lat2, lon2) -> float:
    """两点球面距离，米。"""
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def track_length(pts: list[dict]) -> float:
    """点列（已按时间正序）的总长度，米。"""
    total = 0.0
    for a, b in zip(pts, pts[1:]):
        total += haversine(float(a["latitude"]), float(a["longitude"]),
                           float(b["latitude"]), float(b["longitude"]))
    return total


# ------------------------------------------------------------------ 拉取

def list_records(c: Client, pages: int | None = None) -> list[dict]:
    """翻页拉全部记录。pages=None 表示翻到底。"""
    out: list[dict] = []
    page_no = 1
    while True:
        data = (c.get_wx_sport_page(page_no, PAGE_MAX) or {}).get("data") or {}
        batch = data.get("list") or []
        total_page = int(data.get("totalPage") or 1)
        out.extend(batch)
        if not batch:
            break
        if pages is not None and page_no >= pages:
            break
        if page_no >= total_page:
            break
        page_no += 1
    return out


def fetch_points(c: Client, record_no: str) -> list[dict]:
    """按记录号拉轨迹点，返回时间正序。"""
    data = c._call("GET", f"/sportPoint/listAll/{record_no}").get("data")
    if not isinstance(data, list):
        return []
    pts = [p for p in data if p.get("latitude") and p.get("longitude")]
    # 服务端给的是倒序（最新在前），按 collectTime 正序重排，稳妥
    pts.sort(key=lambda p: p.get("collectTime") or "")
    return pts


# ------------------------------------------------------------------ 输出

def to_geojson(tracks: list[tuple[dict, list[dict]]]) -> dict:
    """一条记录 = 一条 LineString。"""
    feats = []
    for rec, pts in tracks:
        if len(pts) < 2:
            continue
        feats.append({
            "type": "Feature",
            "properties": {
                "sportRecordNo": rec.get("sportRecordNo"),
                "placeName": rec.get("placeName"),
                "placeCode": rec.get("placeCode"),
                "startTime": rec.get("startTime"),
                "endTime": rec.get("endTime"),
                "mileage": rec.get("mileage"),
                "timeConsuming": rec.get("timeConsuming"),
                "isValid": rec.get("isValid"),
                "pointCount": len(pts),
            },
            "geometry": {
                "type": "LineString",
                "coordinates": [
                    [round(float(p["longitude"]), 7), round(float(p["latitude"]), 7)]
                    for p in pts
                ],
            },
        })
    return {"type": "FeatureCollection", "features": feats}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--openid", default=os.environ.get("CR_OPENID", ""))
    ap.add_argument("--token", default="", help="已有 token 可跳过换 token")
    ap.add_argument("--pages", type=int, default=None, help="只翻前 N 页（每页 100）")
    ap.add_argument("--no-points", action="store_true", help="只拉列表头，不拉轨迹点")
    ap.add_argument("--geojson", default="", help="导出 GeoJSON 到该路径")
    ap.add_argument("--csv", default="", help="导出点表 CSV 到该路径")
    ap.add_argument("--json", dest="json_out", default="", help="原始结果存 JSON")
    a = ap.parse_args()

    if not a.openid and not a.token:
        print("用法: fetch_tracks.py --openid OPENID [--geojson out.geojson]")
        return 2

    c = Client(openid=a.openid, verbose=False)
    if a.token:
        c.token = a.token
    elif not c.acquire_token():
        return 1

    recs = list_records(c, a.pages)
    print(f"记录 {len(recs)} 条\n")

    hdr = f"{'记录号':<34}{'场地':<12}{'km':>6}{'秒':>6}{'点':>6}{'实测m':>8}  状态"
    print(hdr)
    print("-" * len(hdr))

    tracks: list[tuple[dict, list[dict]]] = []
    for rec in recs:
        no = rec.get("sportRecordNo") or ""
        pts = [] if a.no_points else fetch_points(c, no)
        length = track_length(pts) if pts else 0.0
        km = rec.get("mileage")
        print(f"{no:<34}{str(rec.get('placeName')):<12}"
              f"{km if km is not None else '-':>6}"
              f"{str(rec.get('timeConsuming') or '-'):>6}"
              f"{len(pts):>6}{length:>8.0f}  "
              f"{'✓' if str(rec.get('isValid')) == '1' else '✗'}"
              f"{(' ' + rec['reason']) if rec.get('reason') else ''}")
        tracks.append((rec, pts))

    with_pts = [t for t in tracks if len(t[1]) >= 2]
    print(f"\n含轨迹的记录 {len(with_pts)} / {len(tracks)}")

    if a.geojson:
        with open(a.geojson, "w", encoding="utf-8") as f:
            json.dump(to_geojson(tracks), f, ensure_ascii=False)
        print(f"GeoJSON -> {a.geojson}")

    if a.csv:
        with open(a.csv, "w", encoding="utf-8") as f:
            f.write("sportRecordNo,placeCode,seq,longitude,latitude,collectTime,isValid\n")
            for rec, pts in tracks:
                for i, p in enumerate(pts):
                    f.write(f"{rec.get('sportRecordNo')},{rec.get('placeCode')},{i},"
                            f"{p['longitude']},{p['latitude']},"
                            f"{p.get('collectTime')},{p.get('isValid')}\n")
        print(f"CSV -> {a.csv}")

    if a.json_out:
        payload = [
            {"record": r, "points": p} for r, p in tracks
        ]
        with open(a.json_out, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        print(f"JSON -> {a.json_out}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
