#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
track_picker.py —— 在内轨迹上"点四个点"，把结果落成可被 track_gen 读取的 JSON。

为什么需要它
------------
宁静苑是**矩形跑道**，而服务端给的围栏是个被切掉一角的五边形。自动反解出来的
矩形虽然能量出来（`track_gen.rect_ring`），但"应该贴着哪条线跑"这件事最终是
人一眼定的：看一眼真实轨迹叠在围栏上的图，就知道四条边该落在哪。

所以这里提供一个纯本地的网页：
  · 画围栏、禁跑区、**该场地的全部真实轨迹**（refs/real_tracks/*.gpx）
  · 鼠标点四下 → 四条边（点与点之间自动按顺序连线）
  · 实时显示四个点的经纬度、边长、周长、与围栏边的最小距离
  · 一键导出 → 写进 `refs/inner_rings.json`，track_gen 直接读

跑法
    python refs/track_picker.py                       # 起服务并打印 URL
    python refs/track_picker.py --field 宁静苑 --port 8765

导出格式（`refs/inner_rings.json`）
    {
      "T1014": {
        "field": "宁静苑",
        "ring": [[lat, lon], [lat, lon], [lat, lon], [lat, lon]],
        "savedAt": "2026-11-13T10:40:00+08:00",
        "note": "手点矩形"
      }
    }
track_gen 的 `inner_track()` 见到某个 placeCode 有手点环，就**优先用它**，
不再走 `rect_ring` / `scale_axes`。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import track_model as T          # noqa: E402
import gpx_metrics as M          # noqa: E402

ROOT = os.path.dirname(HERE)
RINGS_PATH = os.path.join(HERE, "inner_rings.json")


# ---------------------------------------------------------------- 数据装配

def load_rings():
    if not os.path.exists(RINGS_PATH):
        return {}
    try:
        with open(RINGS_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def save_rings(all_rings, code, field, ring, note=""):
    all_rings[code] = {
        "field": field,
        "ring": [[float(p[0]), float(p[1])] for p in ring],
        "savedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "note": note,
    }
    tmp = RINGS_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(all_rings, fh, ensure_ascii=False, indent=2)
    os.replace(tmp, RINGS_PATH)
    return all_rings


def field_payload(field, code):
    """一个场地的全部绘图数据：围栏、禁区、真实轨迹。"""
    fence, exc, _c = T.FIELDS[field]
    fence = T.dedupe(fence)
    exc = [T.dedupe(e) for e in exc]
    reals = []
    for name, place, pts in M.real_tracks():
        if place != field:
            continue
        reals.append({
            "name": name,
            "points": [[p[0], p[1]] for p in pts],
        })
    return {
        "field": field,
        "placeCode": code,
        "fence": [[p[0], p[1]] for p in fence],
        "excludes": [[[p[0], p[1]] for p in e] for e in exc],
        "reals": reals,
        "saved": load_rings().get(code),
    }


PAGE = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>内轨迹取点 · __FIELD__</title>
<style>
  :root{
    --bg:#0f1115; --panel:#171a21; --line:#262b36; --fg:#e8ecf4; --dim:#8b94a7;
    --accent:#4c9aff; --warn:#ff6b6b; --ok:#31c48d; --real:#8b94a7;
  }
  *{box-sizing:border-box}
  html,body{margin:0;padding:0;width:100%;height:100%;background:var(--bg);color:var(--fg);
    font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC",
    "Hiragino Sans GB","Microsoft YaHei",sans-serif;overflow:hidden}
  #app{display:flex;height:100%}
  #side{width:330px;flex:0 0 330px;background:var(--panel);border-right:1px solid var(--line);
    display:flex;flex-direction:column;overflow:hidden}
  #side header{padding:16px 18px 10px;border-bottom:1px solid var(--line)}
  #side h1{margin:0;font-size:15px;font-weight:600;letter-spacing:.02em}
  #side .sub{margin-top:4px;font-size:12px;color:var(--dim)}
  #body{padding:14px 18px;overflow:auto;flex:1}
  .row{display:flex;justify-content:space-between;gap:10px;padding:5px 0;font-size:13px}
  .row b{font-weight:600;font-variant-numeric:tabular-nums}
  .row span{color:var(--dim)}
  .hint{color:var(--dim);font-size:12px;line-height:1.7;margin:0 0 12px}
  .pt{padding:8px 0;border-bottom:1px dashed var(--line);font-size:12px;
    font-variant-numeric:tabular-nums;display:flex;justify-content:space-between;gap:8px}
  .pt .k{color:var(--accent);font-weight:600;width:26px;flex:0 0 26px}
  .pt .c{color:var(--fg);flex:1}
  .pt .d{color:var(--dim);text-align:right}
  .pt.empty .c{color:var(--dim)}
  #foot{padding:12px 18px 16px;border-top:1px solid var(--line);display:flex;flex-direction:column;gap:8px}
  button{font:inherit;padding:9px 12px;border-radius:9px;border:1px solid var(--line);
    background:#202533;color:var(--fg);cursor:pointer;transition:.12s}
  button:hover{background:#262c3c}
  button.primary{background:var(--accent);border-color:var(--accent);color:#06101f;font-weight:600}
  button.primary:hover{filter:brightness(1.08)}
  button:disabled{opacity:.4;cursor:not-allowed}
  .inline{display:flex;gap:8px}
  .inline button{flex:1}
  #status{font-size:12px;color:var(--dim);min-height:18px}
  #stage{flex:1 1 auto;position:relative;background:#0b0d12;min-width:0;min-height:0}
  canvas{position:absolute;inset:0;display:block;width:100%;height:100%;cursor:crosshair}
  #tabs{display:flex;gap:6px;margin-bottom:12px;flex-wrap:wrap}
  #tabs button{padding:5px 10px;font-size:12px;border-radius:999px}
  #tabs button.on{background:var(--accent);border-color:var(--accent);color:#06101f;font-weight:600}
  kbd{background:#202533;border:1px solid var(--line);border-radius:5px;padding:1px 5px;font-size:11px}
</style>
</head>
<body>
<div id="app">
  <aside id="side">
    <header>
      <h1>内轨迹取点 · <span id="fname">__FIELD__</span></h1>
      <div class="sub" id="fcode">placeCode __CODE__</div>
    </header>
    <div id="body">
      <p class="hint">在右侧图上依次点四个角，按顺序连成闭环矩形。
        点选后可直接拖动微调；<kbd>Ctrl</kbd>+<kbd>Z</kbd> 撤回上一颗。</p>
      <div id="tabs"></div>
      <div id="pts"></div>
      <div id="stats"></div>
      <p class="hint" id="err" style="color:var(--warn)"></p>
    </div>
    <div id="foot">
      <div class="inline">
        <button id="undo">撤回</button>
        <button id="clear">清空</button>
      </div>
      <div class="inline">
        <button id="zoom">聚焦到四点</button>
      </div>
      <div class="inline">
        <button id="save" class="primary">保存为内轨迹</button>
        <button id="load">读取已存</button>
      </div>
      <div id="status"></div>
    </div>
  </aside>
  <main id="stage"><canvas id="cv"></canvas></main>
</div>
<script>
const DATA = __DATA__;
const cv = document.getElementById('cv');
const ctx = cv.getContext('2d');
let pts = [];             // [{lat, lon}]
let dragIdx = -1;
let zoomToPts = false;
let view = null;          // {lat0, lon0, k, scale, ox, oy}

const $ = id => document.getElementById(id);

// ---- 投影：以围栏质心为原点的米制平面，再线性映射到画布
function buildView(){
  const all = [];
  DATA.fence.forEach(p => all.push(p));
  DATA.excludes.forEach(e => e.forEach(p => all.push(p)));
  DATA.reals.forEach(r => r.points.forEach(p => all.push(p)));
  pts.forEach(p => all.push([p.lat, p.lon]));
  if (!all.length) return;
  const lat0 = all.reduce((a,p)=>a+p[0],0)/all.length;
  const lon0 = all.reduce((a,p)=>a+p[1],0)/all.length;
  const kx = 111320*Math.cos(lat0*Math.PI/180), ky = 110540;
  let minx=1e18,maxx=-1e18,miny=1e18,maxy=-1e18;
  for (const p of all){
    const x=(p[1]-lon0)*kx, y=(p[0]-lat0)*ky;
    minx=Math.min(minx,x);maxx=Math.max(maxx,x);
    miny=Math.min(miny,y);maxy=Math.max(maxy,y);
  }
  // 默认永远看全景：点是照着围栏和真实轨迹点的，放大到四个点之后就看不见
  // 参照物了，等于"闭着眼睛调"。聚焦模式放在 zoomToPts 里，由按钮显式触发。
  if (zoomToPts && pts.length>=3){
    minx=1e18;maxx=-1e18;miny=1e18;maxy=-1e18;
    for (const p of pts){
      const x=(p.lon-lon0)*kx, y=(p.lat-lat0)*ky;
      minx=Math.min(minx,x);maxx=Math.max(maxx,x);
      miny=Math.min(miny,y);maxy=Math.max(maxy,y);
    }
    const pad=Math.max(30,(maxx-minx)*0.22,(maxy-miny)*0.22);
    minx-=pad;maxx+=pad;miny-=pad;maxy+=pad;
  }
  const w=cv.clientWidth, h=cv.clientHeight;
  const m=44;
  const scale=Math.min((w-2*m)/Math.max(1,(maxx-minx)),(h-2*m)/Math.max(1,(maxy-miny)));
  view={lat0,lon0,kx,ky,scale,
    ox:(w-(maxx-minx)*scale)/2-minx*scale,
    oy:(h-(maxy-miny)*scale)/2+maxy*scale};
}

function toPx(lat,lon){
  const v=view;
  const x=(lon-v.lon0)*v.kx, y=(lat-v.lat0)*v.ky;
  return [v.ox+x*v.scale, v.oy-y*v.scale];
}
function toLL(px,py){
  const v=view;
  const x=(px-v.ox)/v.scale, y=(v.oy-py)/v.scale;
  return {lat:v.lat0+y/v.ky, lon:v.lon0+x/v.kx};
}

// ---- 绘制
function poly(pl,{stroke,fill,lw=1,dash=null,close=true,alpha=1}={}){
  if(!pl.length) return;
  ctx.save();ctx.globalAlpha=alpha;
  ctx.beginPath();
  pl.forEach((p,i)=>{
    const [x,y]=toPx(p[0],p[1]);
    i?ctx.lineTo(x,y):ctx.moveTo(x,y);
  });
  if(close) ctx.closePath();
  if(fill){ctx.fillStyle=fill;ctx.fill();}
  if(stroke){ctx.strokeStyle=stroke;ctx.lineWidth=lw;
    if(dash)ctx.setLineDash(dash);else ctx.setLineDash([]);ctx.stroke();}
  ctx.restore();
}

function draw(){
  // 每帧按 CSS 尺寸同步 backing store：canvas 是 absolute 定位铺满 stage，
  // 但它自己的 width/height 属性不会跟着 CSS 变 —— 不在画之前同步，就会
  // 按旧的（很小的）分辨率画，图只占左上角一块。
  const dpr = window.devicePixelRatio || 1;
  const w = cv.clientWidth, h = cv.clientHeight;
  const bw = Math.max(1, Math.round(w * dpr)), bh = Math.max(1, Math.round(h * dpr));
  if (cv.width !== bw || cv.height !== bh) { cv.width = bw; cv.height = bh; }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0,0,w,h);
  ctx.fillStyle='#0b0d12';ctx.fillRect(0,0,w,h);
  buildView();
  if(!view) return;

  // 网格（按米画，方便估尺度）
  const step = niceStep(30/view.scale);
  ctx.save();ctx.strokeStyle='#141821';ctx.lineWidth=1;
  const v=view;
  const x0=(0-v.ox)/v.scale, x1=(w-v.ox)/v.scale;
  const y0=(v.oy-h)/v.scale, y1=v.oy/v.scale;
  for(let x=Math.floor(x0/step)*step;x<=x1;x+=step){
    const px=v.ox+x*v.scale;ctx.beginPath();ctx.moveTo(px,0);ctx.lineTo(px,h);ctx.stroke();
  }
  for(let y=Math.floor(y0/step)*step;y<=y1;y+=step){
    const py=v.oy-y*v.scale;ctx.beginPath();ctx.moveTo(0,py);ctx.lineTo(w,py);ctx.stroke();
  }
  ctx.restore();

  // 禁跑区
  DATA.excludes.forEach(e=>poly(e,{stroke:'#5a2732',fill:'rgba(255,107,107,.16)',lw:1}));
  // 围栏
  poly(DATA.fence,{stroke:'#6b7688',dash:[6,4],lw:1.4});
  // 真实轨迹
  DATA.reals.forEach((r,i)=>{
    poly(r.points,{stroke:'#7d8aa0',lw:1.0,alpha:0.62,close:false});
  });

  // 用户点
  if(pts.length){
    const pl=pts.map(p=>[p.lat,p.lon]);
    poly(pl,{stroke:'#4c9aff',lw:2,close:pts.length>=3,
      fill:pts.length>=3?'rgba(76,154,255,.10)':null});
    const badNow = pts.map(p => !pointInPoly(p.lat,p.lon,DATA.fence)
                              || DATA.excludes.some(e=>pointInPoly(p.lat,p.lon,e)));
    pts.forEach((p,i)=>{
      const [x,y]=toPx(p.lat,p.lon);
      ctx.beginPath();ctx.arc(x,y,7,0,Math.PI*2);
      ctx.fillStyle = badNow[i] ? '#ff6b6b' : '#4c9aff';ctx.fill();
      ctx.strokeStyle='#0b0d12';ctx.lineWidth=2;ctx.stroke();
      ctx.fillStyle='#06101f';ctx.font='600 11px system-ui';
      ctx.textAlign='center';ctx.textBaseline='middle';
      ctx.fillText(String(i+1),x,y+0.5);
      if (badNow[i]){
        ctx.beginPath();ctx.arc(x,y,13,0,Math.PI*2);
        ctx.strokeStyle='rgba(255,107,107,.85)';ctx.lineWidth=2;ctx.stroke();
      }
    });
  }

  // 指北 + 比例尺
  const v2=view;
  const px100=100*v2.scale;
  ctx.save();
  ctx.strokeStyle='#3a4252';ctx.lineWidth=2;
  ctx.beginPath();ctx.moveTo(w-24,40);ctx.lineTo(w-24,40-30);ctx.stroke();
  ctx.beginPath();ctx.moveTo(w-24,40);ctx.lineTo(w-29,50);ctx.lineTo(w-19,50);
  ctx.closePath();ctx.fillStyle='#3a4252';ctx.fill();
  ctx.fillStyle='#8b94a7';ctx.font='11px system-ui';ctx.textAlign='center';
  ctx.fillText('N',w-24,28);
  ctx.textAlign='left';
  ctx.beginPath();ctx.moveTo(20,h-22);ctx.lineTo(20+px100,h-22);ctx.stroke();
  ctx.fillText('100 m',20,h-30);
  ctx.restore();
}

function niceStep(raw){
  const p=Math.pow(10,Math.floor(Math.log10(Math.max(1,raw))));
  const n=raw/p;
  return p*(n<1.5?1:n<3.5?2:n<7.5?5:10);
}

// ---- 统计
function stats(){
  const out={};
  if(pts.length>=2){
    const per=[];
    for(let i=0;i<pts.length;i++){
      const a=pts[i],b=pts[(i+1)%pts.length];
      if(pts.length<3 && i===pts.length-1) break;
      per.push(T.hav(a.lat,a.lon,b.lat,b.lon));
    }
    out.sides=per;
    out.perimeter=per.reduce((x,y)=>x+y,0);
    if(pts.length===4){
      const ang=[];
      for(let i=0;i<4;i++){
        const a=pts[(i+3)%4],b=pts[i],c=pts[(i+1)%4];
        ang.push(angle(a,b,c));
      }
      out.angles=ang;
      out.area=Math.abs(shoelace(pts));
    }
  }
  // 离围栏边的最近距离（每个点）
  if(pts.length){
    out.gaps=pts.map(p=>minDistToPoly(p.lat,p.lon,DATA.fence));
    out.inside=pts.map(p=>pointInPoly(p.lat,p.lon,DATA.fence));
    out.banned=pts.map(p=>DATA.excludes.some(e=>pointInPoly(p.lat,p.lon,e)));
  }
  return out;
}
function angle(a,b,c){
  const p1=Math.atan2(a.lat-b.lat,(a.lon-b.lon)*Math.cos(b.lat*Math.PI/180));
  const p2=Math.atan2(c.lat-b.lat,(c.lon-b.lon)*Math.cos(b.lat*Math.PI/180));
  let d=Math.abs(p1-p2)*180/Math.PI;
  if(d>180) d=360-d;
  return d;
}
function shoelace(pl){
  const lat0=pl[0].lat;
  const k=111320*Math.cos(lat0*Math.PI/180);
  let s=0;
  for(let i=0;i<pl.length;i++){
    const a=pl[i],b=pl[(i+1)%pl.length];
    s+=(a.lon-b.lon)*k*(a.lat+b.lat)*110540/2;
  }
  return s;
}
function pointInPoly(lat,lon,poly){
  let inside=false;
  for(let i=0,j=poly.length-1;i<poly.length;j=i++){
    const yi=poly[i][0],xi=poly[i][1],yj=poly[j][0],xj=poly[j][1];
    if(((yi>lat)!==(yj>lat)) && (lon<(xj-xi)*(lat-yi)/(yj-yi)+xi)) inside=!inside;
  }
  return inside;
}
function segDist(lat,lon,a,b){
  const k=Math.cos(lat*Math.PI/180);
  const ax=a[1]*k,ay=a[0],bx=b[1]*k,by=b[0],px=lon*k,py=lat;
  const dx=bx-ax,dy=by-ay,L=dx*dx+dy*dy;
  let t=L?((px-ax)*dx+(py-ay)*dy)/L:0;t=Math.max(0,Math.min(1,t));
  const qx=ax+t*dx,qy=ay+t*dy;
  return Math.hypot(px-qx,py-qy)*111320;
}
function minDistToPoly(lat,lon,poly){
  let best=1e18;
  for(let i=0;i<poly.length;i++) best=Math.min(best,segDist(lat,lon,poly[i],poly[(i+1)%poly.length]));
  return best;
}
const T={hav:(a,b,c,d)=>{const R=6371008.8,p1=a*Math.PI/180,p2=c*Math.PI/180;
  const dp=p2-p1,dl=(d-b)*Math.PI/180;
  const x=Math.sin(dp/2)**2+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)**2;
  return 2*R*Math.asin(Math.min(1,Math.sqrt(x)));}};

// ---- UI 刷新
function refresh(){
  const s=stats();
  const rows=[];
  const names=['①','②','③','④'];
  for(let i=0;i<4;i++){
    const p=pts[i];
    if(p){
      rows.push(`<div class="pt"><span class="k">${names[i]}</span>
        <span class="c">${p.lat.toFixed(6)}, ${p.lon.toFixed(6)}</span>
        <span class="d">${s.gaps?('离边 '+s.gaps[i].toFixed(1)+' m'):''}</span></div>`);
    }else{
      rows.push(`<div class="pt empty"><span class="k">${names[i]}</span>
        <span class="c">未选</span><span class="d">—</span></div>`);
    }
  }
  $('pts').innerHTML=rows.join('');

  const st=[];
  if(s.sides){
    st.push(`<div class="row"><span>边长</span><b>${
      s.sides.map(v=>v.toFixed(1)).join(' / ')} m</b></div>`);
    st.push(`<div class="row"><span>周长</span><b>${s.perimeter.toFixed(1)} m</b></div>`);
  }
  if(s.angles){
    st.push(`<div class="row"><span>内角</span><b>${
      s.angles.map(v=>v.toFixed(1)+'°').join(' / ')}</b></div>`);
    st.push(`<div class="row"><span>面积</span><b>${s.area.toFixed(0)} m²</b></div>`);
  }
  if(s.inside){
    const okIn=s.inside.every(Boolean)&&s.banned.every(v=>!v);
    st.push(`<div class="row"><span>合法性</span><b style="color:${
      okIn?'var(--ok)':'var(--warn)'}">${
      okIn?'全部在围栏内、不进禁区':'有越界点'}</b></div>`);
  }
  $('stats').innerHTML=st.join('');
  $('save').disabled = pts.length!==4;
  $('err').textContent = pts.length&&pts.length!==4 ? '需要正好四个点（当前 '+pts.length+' 个）' : '';
  $('status').textContent = DATA.saved
    ? '已存内轨迹：'+DATA.saved.ring.length+' 点，'+(DATA.saved.savedAt||'')
    : '尚未保存过内轨迹';
}

// ---- 交互
function evPos(e){
  const r=cv.getBoundingClientRect();
  return [e.clientX-r.left, e.clientY-r.top];
}
cv.addEventListener('pointerdown',e=>{
  if(!view) return;
  const [x,y]=evPos(e);
  // 先判断是不是在拖已有的点
  for(let i=0;i<pts.length;i++){
    const [px,py]=toPx(pts[i].lat,pts[i].lon);
    if(Math.hypot(px-x,py-y)<12){ dragIdx=i; cv.setPointerCapture(e.pointerId); return; }
  }
  if(pts.length>=4) return;
  const p=toLL(x,y);
  pts.push(p); refresh(); draw();
});
cv.addEventListener('pointermove',e=>{
  if(dragIdx<0||!view) return;
  const [x,y]=evPos(e);
  pts[dragIdx]=toLL(x,y); refresh(); draw();
});
cv.addEventListener('pointerup',e=>{
  if(dragIdx>=0){ dragIdx=-1; cv.releasePointerCapture?.(e.pointerId); }
});
window.addEventListener('keydown',e=>{
  if((e.ctrlKey||e.metaKey)&&e.key==='z'){ e.preventDefault(); pts.pop(); refresh(); draw(); }
});

$('undo').onclick=()=>{ pts.pop(); refresh(); draw(); };
$('zoom').onclick=()=>{
  zoomToPts = !zoomToPts;
  $('zoom').textContent = zoomToPts ? '回到全景' : '聚焦到四点';
  draw();
};
$('clear').onclick=()=>{ pts=[]; refresh(); draw(); };
$('load').onclick=()=>{
  if(DATA.saved && DATA.saved.ring){ pts=DATA.saved.ring.map(r=>({lat:r[0],lon:r[1]})); }
  refresh(); draw();
};
$('save').onclick=async()=>{
  if(pts.length!==4) return;
  $('status').textContent='保存中…';
  try{
    const res=await fetch('/api/save',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({placeCode:DATA.placeCode,field:DATA.field,
        ring:pts.map(p=>[p.lat,p.lon])})});
    const j=await res.json();
    if(j.ok){ $('status').textContent='已写入 refs/inner_rings.json'; }
    else { $('status').textContent='失败：'+(j.error||'未知'); }
  }catch(err){ $('status').textContent='失败：'+err; }
};

// 切换场地
(function(){
  const tabs=$('tabs');
  (DATA.allFields||[]).forEach(f=>{
    const b=document.createElement('button');
    b.textContent=f.field; b.className = f.field===DATA.field?'on':'';
    b.onclick=()=>{ location.href='/?field='+encodeURIComponent(f.field); };
    tabs.appendChild(b);
  });
})();

function resize(){ draw(); }
window.addEventListener('resize',resize);
if (window.ResizeObserver) new ResizeObserver(() => draw()).observe(cv);
if(DATA.saved && DATA.saved.ring){
  pts=DATA.saved.ring.map(r=>({lat:r[0],lon:r[1]}));
}
resize(); refresh();
</script>
</body>
</html>
"""


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "track_picker/1.0"

    def log_message(self, fmt, *args):      # 安静点，只留错误
        pass

    def _send(self, code, body, ctype="text/html; charset=utf-8"):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        from urllib.parse import urlparse, parse_qs
        u = urlparse(self.path)
        if u.path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
            return
        if u.path not in ("/", "/index.html"):
            self._send(404, "not found", "text/plain; charset=utf-8")
            return
        qs = parse_qs(u.query)
        field = (qs.get("field") or [self.server.default_field])[0]
        if field not in T.FIELDS:
            field = self.server.default_field
        code = T.place_code_of(field)
        payload = field_payload(field, code)
        payload["allFields"] = [{"field": f, "placeCode": T.place_code_of(f)}
                                for f in T.FIELDS]
        page = (PAGE
                .replace("__FIELD__", field)
                .replace("__CODE__", code or "?")
                .replace("__DATA__", json.dumps(payload, ensure_ascii=False)))
        self._send(200, page)

    def do_POST(self):
        from urllib.parse import urlparse
        u = urlparse(self.path)
        if u.path != "/api/save":
            self._send(404, json.dumps({"ok": False, "error": "not found"}),
                       "application/json; charset=utf-8")
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n) or b"{}")
            code = body["placeCode"]
            field = body["field"]
            ring = body["ring"]
            if len(ring) < 3:
                raise ValueError("至少需要 3 个点")
            for p in ring:
                if len(p) != 2:
                    raise ValueError("点必须是 [lat, lon]")
            rings = save_rings(load_rings(), code, field, ring, note="手点")
            self._send(200, json.dumps({"ok": True, "count": len(rings)}),
                       "application/json; charset=utf-8")
        except Exception as exc:                     # noqa: BLE001
            self._send(500, json.dumps({"ok": False, "error": str(exc)},
                                       ensure_ascii=False),
                       "application/json; charset=utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description="内轨迹取点工具（本地网页）")
    ap.add_argument("--field", default="宁静苑", choices=list(T.FIELDS))
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args(argv)

    srv = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    srv.default_field = args.field
    url = f"http://127.0.0.1:{args.port}/"
    print(f"[+] 取点工具：{url}  （场地 {args.field}）")
    print(f"    导出的内轨迹写入：{RINGS_PATH}")
    print("    Ctrl+C 结束")
    if not args.no_browser:
        try:
            webbrowser.open(url)
        except Exception:                            # noqa: BLE001
            pass
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n[+] 已停止")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
