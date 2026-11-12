# ColorOS 计算器的「液体按钮」与「弹出菜单」——逆向取证报告

> 目标：`计算器.apk`（`com.coloros.calculator` 17.2.16，versionCode 17002016，
> minSdk 30 / targetSdk 37，ColorOS 17 版计算器）
> 目的：把这套触摸动效搬到鲸鲸约跑的 UI 上。**搬的是公式和参数，不是代码。**
> 结论落在：`LiquidSpring.kt` / `FluidMotion.kt` / `LiquidButton.kt` / `FluidPopupMenu.kt`。

---

## 0. 取证链路（可复现）

| 环节 | 工具 / 版本 | 产物 |
|---|---|---|
| APK 资源解码 | Apktool **3.0.3** | `res/layout`、`res/values/dimens.xml`、`assets/` |
| 字节码反编译 | JADX **1.5.6**（`--no-res`） | `sources/` 全量 Java |
| REA 证据层 | `rea` **6.8.0** + `jadx-headless-mcp` **0.7.1** | `inspect-android-package` / `inspect-android-class` 的 Evidence（带 sha256、revision、limitations） |

REA 侧要点（它自己会把限制写清楚，别当成运行时结论）：

```bash
export REA_JADX_MCP_JAR=/path/to/jadx-headless-mcp-0.7.1-all.jar   # 审计固定 sha256 6e5eacf5…
rea doctor                                                          # jadx: healthy
rea inspect-android-package calc.apk --format json                  # 包名 / SDK / 组件 / 权限
rea inspect-android-class   calc.apk com.coui.appcompat.animation.touchanimation.COUIDeformAnimatorUnit
```

REA 的 engine 字段记录：`jadx-headless-mcp 0.7.1`，`artifact_sha256 6e5eacf5…`，
`source_revision 5844800a…`；`limitations` 明确写「静态反编译不证明运行时行为，APK 从未执行」。
下面所有结论都来自**字节码与资源**，没有一条依赖「跑起来看」。

APK 结构上的两个信号，先说清楚：

- **它不是自己实现动效的**。计算器的按键是 `com.android.calculator2.ui.widget.COUIButton`，
  真正的手感来自随包的 COUI 组件库（`com.coui.appcompat.*`）。
- **有一套完整的外置参数系统**：`TouchMotionTierParams` / `PressParams` /
  `PopupGravityMotionParams` 都带 `Companion` + `olivelink` 观察接口，说明这些数值
  本来是服务端可调的（`PressParamDefaults` 里直接写着中文参数名：
  「按压反馈-缩小」「按压形变的最小可见变化量」）。伞下默认值就是我们要抄的那组。

---

## 1. 液体按钮

### 1.1 它不是「画一条变形的轮廓」

第一直觉（也是我们上一版的写法）是把胶囊轮廓逐点往拖动方向推。看代码会发现**不是**：

`COUIDeformAnimatorUnit` 只产出两个浮点数 —— `scaleX` / `scaleY`，
由 `COUITouchMotionAnimator.TouchMotionCollector` 汇总后**对整个 View 设 scale**
（`COUIButton` 的 `setScaleX/setScaleY`，计算器自带的 `COUIButton.setScale()` 也一样，
把值夹在 `[0.92, 1.0]`）。轮廓始终是胶囊。

「像水」来自三点：**面积守恒**、**橡皮筋映射**、**手指朝哪边就钉住对边**。

### 1.2 三个并行的动画单元

`COUITouchMotionAnimator`（`extends COUIComposeMotionAnimator`）里挂三个 unit，
每个 unit 自己持有一到两根弹簧，`handleTouchEvent` 一次把事件分发给全部：

| unit | 管什么 | 属性 |
|---|---|---|
| `COUIPressAnimatorUnit` | 按下整体缩小 | `SCALE_X` / `SCALE_Y`（等比） |
| `COUIDeformAnimatorUnit` | 拖动时的拉长 / 压扁 | `SCALE_X` / `SCALE_Y`（不等比） |
| `COUIRubberAnimatorUnit` | 整体跟手位移 | `TRANSLATE_X` / `TRANSLATE_Y` |

`TouchMotionCollector.put()` 的合成方式是**加法**（相对 1 / 相对 0），
所以「按下缩到 0.95」和「横向拉到 1.06」会叠成 `0.95 × 1.06`。

### 1.3 橡皮筋：`TouchMotionRubberBand`

```java
apply(offset, curveRatio, maxDistancePx)
  = (1 − 1 / ((curveRatio · |offset|) / maxDistancePx + 1)) · maxDistancePx · sign(offset)
```

性质：单调、过原点、**渐近于 maxDistancePx 但永远到不了**。
`inverse()` 是它的反函数（抬手时要由「视觉位移」反算「等效拖动距离」）。

### 1.4 面积守恒形变：`COUIDeformAnimatorUnit.calculateDirectionScale`

```java
s        = (rubberBand(|d|, deformCurveRatio, maxDeformPx) / maxDeformPx) · (maxScale − 1) + 1
along    = s
across   = 1 / s
```

两轴都动时不是取平均，而是**几何平均**（log 域插值）：

```java
blendAreaConservingScaleFactors(a, b, wa, wb)
  = exp(wa·ln a.sx + wb·ln b.sx), exp(wa·ln a.sy + wb·ln b.sy)     // wa = |dx|/(|dx|+|dy|)
```

算术平均会破坏 `sx·sy = 1`，视觉上就是「一边鼓了另一边没瘦」——塑料感。

### 1.5 尺寸修正：`TouchAnimatorParamProvider.calculateScaleWithK`

```java
area   = w · h
aspect = max(w,h) / min(w,h)
decay  = min( k1/1e6 · ln(area / 38416) + k2/1000 · (aspect − 1), 1 )
maxScale = max( (maxScale − 1.03) · (1 − decay) + 1.03, 1.03 )
```

- `38416 = 196²` 是「小按钮」基准面积，`1.03` 是拉伸下限；
- `k1 = 350000`、`k2 = 15`（`CommonParams` 默认值）。

**这一条决定了宽胶囊只能拉几个百分点**，而计算器上那 90dp 的方键可以拉到 1.2 倍。
我们照抄后量出来：`890×242 px` 的主按钮在拖满时横向约 +1.6%，纵向 −1.6% —— 与公式一致。

### 1.6 弹簧：不是欧拉积分，是解析解

`COUISpringForce`：

```java
response → naturalFreq ω = 2π / response，刚度 k = ω²
bounce   → 阻尼比 ζ = 1 − bounce
```

每帧走 `updateValues(value, velocity, dtMs)`，分三支闭式解（过阻尼 `ζ>1` / 临界 `ζ=1` /
欠阻尼 `ζ<1`）。收敛判据：`|Δ| < minVisible·0.75` 且 `|v| < minVisible·62.5`
（62.5 = 1s/16ms，即「一帧内最多再动一个最小可见量」）。

我们照抄成 `LiquidSpring`（解析解），收益：大 dt 不发散、参数与逆向值一一对应。

### 1.7 参数表（`TouchMotionTierParams` / `CommonParams` / `PressParams` 默认值）

| 参数 | 值 | 出处 |
|---|---|---|
| `iolMaxStretchDistance` | **28 dp** | 位移橡皮筋上限 |
| `iolRubberCurveRatio` | **0.05** | 位移橡皮筋曲率 |
| `iolMaxDeformRubberDistance` | **150 dp** | 形变橡皮筋上限 |
| `iolDeformRubberCurveRatio` | **0.55** | 形变橡皮筋曲率 |
| `iolDragSpring` | bounce **0.15** / response **0.15** | 拖动时 |
| `iolHandUpSpring` | bounce **0.42** / response **0.65** | 抬手（小面积） |
| `iolHandUpSpringLargeArea` | bounce **0.45** / response **0.65** | 抬手（大面积） |
| 面积插值 | `56dp² → 100dp²`，bezier `(0.55026454, 0, 0.8915344, 0.18783069)` | 两套抬手弹簧之间过渡 |
| `iolHandUpMaxInitialVelocity` | **1000** px/s | 抬手速度归一化上限 |
| `iolDeadZone` | **5 dp** | 小于它不算拖动 |
| `iolMaxScale` | **1.2** | 拉伸上限（再被 scaleWithK 衰减） |
| `k1` / `k2` | **350000** / **15** | scaleWithK 的衰减系数 |
| `jellyPressSpring` | bounce **0.0** / response **0.25** | 按下 |
| `jellyHandupSpring` | bounce **0.6** / response **0.5** | 抬手（按压通道） |
| 按压缩放 | 面积 `20dp²→150dp²` 映射 **0.85 → 0.97**；宽高比 `>2` 再走 **0.85 → 1.0** 通道，两通道取平均 | `PressCalc.calculateUniformScaleBreakdown` |

抬手阻尼比不是常数，`COUIITouchMotionUnit.computeHandUpBounce`（旧分支）：

```java
lo = min(0.4, 0.65 · baseBounce)，hi = baseBounce
bounce = clamp( ((hi − lo) / maxV) · min(|pan|, maxV) + lo, lo, hi )
```

**拖得越远、松手越弹**——这就是「荡一下再收住」的来源。

### 1.8 边缘发光

`assets/cal_btn_glow_0328.coz` 是一个 zip，里面 `data` 是 `CO` + base64(JSON)，
JSON 的 `render[0].fs` 就是片元着色器（节选，原文注释还在）：

```glsl
float sdf = sdRoundedBox(p, buttonSize * 0.5, vec4(buttonCorner));
float d   = abs(sdf);
float l   = 1.1 / ((step(BaseA, d) * (d − BaseA) * glow + 1.)) − 0.1;
float PP  = smoothstep(−padding, 0.0, sdf) * smoothstep(2.0, 0.0, sdf);
fragColor = vec4(paddingAlpha * PP * l * lightColor);
```

即：**贴着圆角矩形轮廓的一道内发光**，向外快速衰减、向内留 2px。
`uniforms` 里存了实际调好的值：`padding 141`、`paddingAlpha 0.63`、`buttonCorner 78`、
`lightColor (0.667, 0.494, 0.345)`、`glow 0.01`。

我们没引 RuntimeShader（minSdk 24 / AGSL 要 33），用
`Paint(STROKE) + BlurMaskFilter` 贴轮廓复现观感，强度随按压淡入淡出。

---

## 2. 弹出菜单

### 2.1 入场是三条并行弹簧 + 一条重力弧

`com.coui.appcompat.anim.ability.PopupMotionAnimateKitController.startOpenPopupMotion()`：

1. `rebuildGeom()` 按锚点算出贝塞尔控制点；
2. `applyOpenInitialState(fromScratch)` 把 `pathT / scale / alpha` 置 0；
3. `runOpenWaves()` 起三条独立弹簧（`PopupAlphaProperty` / `PopupPathTProperty` /
   `PopupScaleProperty`），各自收敛到 1；
4. 每帧 `applyPathFromT(t)` → `BezierGravityPathMath.pointOnBezier(t)` → 写 `translationX/Y`；
   `applyScaleFromValue()` → `setScaleXY`；`applyAlphaFromValue()` → `setAlpha`。

退场把三个目标改成 0，弹簧参数换成「快且不过冲」的一组。

### 2.2 弹簧参数（`PopupGravityMotionParams` 默认值）

| 通道 | 打开（bounce / response） | 关闭 |
|---|---|---|
| 路径 progress | **0.13 / 0.29** | 0.0 / 0.26 |
| 缩放 | **0.20 / 0.40** | 0.0 / 0.21 |
| 透明度 | **0.00 / 0.27** | 0.0 / 0.23 |

三者节奏错开：透明度最快且不过冲（否则会闪），路径先到，缩放稍慢留一点余韵。

### 2.3 重力弧：`BezierGravityPathMath.controlPoints`

```java
len     = |B − A|
gravity = gMax · clamp((len − dMin)/(dMax − dMin), 0, 1) · min(|dx|,|dy|) / (max(|dx|,|dy|) + 1e-4)
n       = 单位法线（朝下）
P1 = A + t1·(B−A) + n·gravity
P2 = A + t2·(B−A) + n·gravity
```

默认 `dMin 30px`、`dMax 400px`、`gMax 130px`、`t1 0.35`、`t2 0.65`；
`normalizePathTParameters` 会把 t 夹到 `[0.05, 0.95]`，并保证 `t2 − t1 ≥ 0.08`
（不够就以中点为中心各让 0.04）。

注意 `balance` 那一项：**纯水平或纯垂直位移时弧退化为直线**。这不是 bug，是刻意的 ——
菜单一般从按钮正下方/正上方弹出，横向偏移很小时硬加弧线会显得「飘」。

我们实测的落地效果（logcat 采样，屏幕 1080×2400）：

```
t=0.000 s=0.000 a=0.000 tx=57 ty=-352   ← 起点就是锚点
t=0.177 s=0.107 a=0.188 tx=54 ty=-287
t=0.451 s=0.306 a=0.454 tx=43 ty=-191
t=0.680 s=0.514 a=0.665 tx=29 ty=-112
t=0.972 s=0.910 a=0.940 tx=3  ty=-10
t=1.004 s=1.011 a=0.994 tx=-0 ty=1      ← 缩放过冲到 1.015 才收住
```

### 2.4 面板本身

- `RoundFrameLayout`：圆角 `coui_popup_round_frame_layout_corner_radius = **28dp**`，
  背景模糊 `DEFAULT_BLUR_RADIUS_PX = 160`（`COUIBackgroundBlurBuilder`，走私有
  `RenderEffect`，第三方拿不到）；
- 条目：`item_min_height 40dp`、`item_padding_top_and_bottom 8dp`、
  `icon_margin_left 20dp`、`item_max_width 248dp`、列表垂直 padding 8dp；
- 条目内容：20dp 图标 + 标题（`couiTextAppearanceBodyL`）+ 描述 + 右侧勾选。

我们做不了背景模糊，就把玻璃做**厚**（`@color/menu_panel` 近乎不透明）——
菜单是浮在内容上的阅读层，透出底下的字就废了；再补一层
`BlurMaskFilter` 投影把面板从页面里「抬」出来。

---

## 3. 哪些没搬，为什么

| 原版能力 | 我们的取舍 |
|---|---|
| `RenderThread` 渲染（`COUIRtDeformAnimatorUnit` / `RtViewWrites`） | 主线程弹簧 + `Canvas`。RT 通道只是把同样的属性写到渲染线程，本地 60fps 足够 |
| `RenderEffect` 背景模糊（API 31+） | `minSdk 24`，改用近乎不透明玻璃 + 描边高光 |
| AGSL/GLSL 边缘发光 | 同 `minSdk`，用模糊描边复现观感 |
| `COUIAnimateKit` / `olivelink` 参数下发 | 参数直接写成常量（本项目没有服务端调参需求） |
| 菜单子菜单（主菜单缩到 0.9 / 透明度 0.7） | 我们的菜单是单层，没有子菜单 |

---

## 4. 落到本项目的位置

| 文件 | 作用 |
|---|---|
| `LiquidSpring.kt` | 解析解弹簧，`response / bounce` 参数化 |
| `FluidMotion.kt` | 纯 JVM 数学：橡皮筋、面积守恒形变、`scaleWithK`、按压缩放、抬手阻尼、重力弧 |
| `LiquidButton.kt` | 三个并行弹簧 + `Canvas` 缩放 + 边缘发光 |
| `FluidPopupMenu.kt` | `PopupWindow` 自绘面板，沿重力弧飞入 / 退出 |
| `LiquidSpringTest.kt` / `FluidMotionTest.kt` | 把这些公式的**性质**钉住（48 个用例） |

使用位置：主页【开始打卡】、设置页各按钮、日志页「更多」菜单、设置页「场地」选择。

---

## 5. 复现命令

```bash
# 资源
java -jar apktool_3.0.3.jar d 计算器.apk -o apktool_out --no-src
# 字节码
jadx -d jadx_out --no-res 计算器.apk
# REA 证据
export REA_JADX_MCP_JAR=/path/jadx-headless-mcp-0.7.1-all.jar
rea inspect-android-package 计算器.apk --format json
rea inspect-android-class   计算器.apk com.coui.appcompat.animation.touchanimation.COUIDeformAnimatorUnit

# 发光着色器
unzip -o 计算器.apk assets/cal_btn_glow_0328.coz -d glow && cd glow
python3 -c "import base64,json,zipfile;z=zipfile.ZipFile('cal_btn_glow_0328.coz');print(json.dumps(json.loads(base64.b64decode(z.read('data')[2:])),ensure_ascii=False,indent=1))"
```
