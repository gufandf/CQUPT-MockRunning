/*
 * 液体形变的数学内核 —— 纯 JVM，没有一行 Android API，可直接单测。
 *
 * 全部公式逆向自 ColorOS 计算器 17.2.16 的触摸动效库
 * （com.coui.appcompat.animation.touchanimation.*），出处逐条写在函数注释里，
 * 完整取证见 refs/REA-CALCULATOR-FLUID.md。
 *
 * 三个概念分清楚，这是「像液体」和「像被压扁的板」的分水岭：
 *
 *   ① 橡皮筋（rubberBand）
 *      越拉越"硬"，位移随手指增长但被 maxDistance 渐近吞掉 ——
 *      永远到不了上限，也永远不会脱出容器。ColorOS 用它同时管
 *      「整体跟手位移」和「形变量」两件事。
 *
 *   ② 面积守恒形变（deformScaleFactors）
 *      沿拖动方向拉伸 s 倍，垂直方向就压到 1/s。
 *      横纵都动时按 |dx|/|dy| 的权重在 log 域混合 ——
 *      exp(w1·ln a + w2·ln b)，几何平均而不是算术平均，
 *      保证 s·(1/s) = 1 这条守恒在混合后仍然成立。
 *
 *   ③ 面积/宽高比修正的最大拉伸（scaleWithK）
 *      同一套参数，大按钮不能像小按钮那样拉 1.2 倍，否则整块板在抖。
 *      面积越大、长宽比越悬殊，允许的最大拉伸越靠近 1.03。
 */
package com.whale.campusrun

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

internal object FluidMotion {

    // ───────────────────────────────────────────── ① 橡皮筋

    /**
     * 橡皮筋映射：真实位移 → 视觉位移。
     *
     *     apply(x) = (1 − 1 / ((curveRatio·|x|) / max + 1)) · max · sign(x)
     *
     * 逆向自 com.coui.appcompat.animation.touchanimation.TouchMotionRubberBand#apply。
     * curveRatio 越小越"硬"（更快顶到 max 附近）：
     *   · 整体跟手位移用 0.05 + maxStretchDistance 28dp
     *   · 形变量用 0.55 + maxDeformRubberDistance 150dp
     *
     * @return 与 offset 同号，绝对值严格小于 maxDistancePx。
     */
    fun rubberBand(offset: Float, curveRatio: Float, maxDistancePx: Float): Float {
        if (maxDistancePx <= 0f) return 0f
        val denom = (curveRatio * abs(offset)) / maxDistancePx + 1f
        return (1f - 1f / denom) * maxDistancePx * sign(offset)
    }

    /**
     * 橡皮筋的反函数：视觉位移 → 等效真实位移。
     *
     * 抬手时要「从当前视觉位置算等效拖动距离」，才能把回弹速度算对。
     * 逆向自同一个类的 #inverse。
     */
    fun rubberBandInverse(result: Float, curveRatio: Float, maxDistancePx: Float): Float {
        if (maxDistancePx <= 0f) return result
        val a = abs(result)
        if (a >= maxDistancePx) return maxDistancePx * sign(result)
        val rest = maxDistancePx - a
        if (curveRatio <= 0f || rest <= 0f) return result
        return (maxDistancePx * a) / (curveRatio * rest) * sign(result)
    }

    // ───────────────────────────────────────────── ② 面积守恒形变

    /** 形变结果：两个轴的缩放倍数。 */
    data class ScaleFactors(val sx: Float, val sy: Float) {
        val isUnity: Boolean get() = abs(sx - 1f) < 1e-4f && abs(sy - 1f) < 1e-4f
    }

    /**
     * 单方向形变：沿该轴拉伸，另一轴按 1/s 压缩。
     *
     * 逆向自 COUIDeformAnimatorUnit#calculateDirectionScale：
     *     s = (rubberBand(|d|) / maxPx) · (maxScale − 1) + 1
     *     other = 1 / s
     */
    private fun directionScale(
        distance: Float,
        curveRatio: Float,
        maxPx: Float,
        maxScale: Float,
        along: Boolean
    ): ScaleFactors {
        if (distance <= 0f || maxPx <= 0f) return ScaleFactors(1f, 1f)
        val s = (rubberBand(distance, curveRatio, maxPx) / maxPx) * (maxScale - 1f) + 1f
        if (s <= 0f) return ScaleFactors(1f, 1f)
        val inv = 1f / s
        return if (along) ScaleFactors(s, inv) else ScaleFactors(inv, s)
    }

    /**
     * 两轴合成 —— 几何平均（log 域线性插值）。
     *
     * 逆向自 COUIDeformAnimatorUnit#blendAreaConservingScaleFactors：
     *     sx = exp(wx·ln sx_a + wy·ln sx_b)
     * 用算术平均会破坏「拉伸多少、就压扁多少」的守恒，
     * 视觉上就是「一边鼓了但另一边没瘦」，像塑料不像水。
     */
    private fun blend(a: ScaleFactors, b: ScaleFactors, wa: Float, wb: Float): ScaleFactors {
        val sx = exp(ln(a.sx) * wa + ln(b.sx) * wb)
        val sy = exp(ln(a.sy) * wa + ln(b.sy) * wb)
        return ScaleFactors(sx, sy)
    }

    /**
     * 由拖动位移算形变。
     *
     * @param dx/dy     手指相对按下点的位移（px，已过死区）
     * @param maxDeformPx 形变橡皮筋的最大距离（ColorOS 默认 150dp）
     * @param curveRatio  形变橡皮筋曲率（默认 0.55）
     * @param maxScale    最大拉伸倍数（默认 1.2，再由 [scaleWithK] 按尺寸衰减）
     */
    fun deformScaleFactors(
        dx: Float,
        dy: Float,
        maxDeformPx: Float,
        curveRatio: Float,
        maxScale: Float
    ): ScaleFactors {
        val ax = max(0f, abs(dx))
        val ay = max(0f, abs(dy))
        val total = ax + ay
        if (total <= 0f) return ScaleFactors(1f, 1f)

        val sxDir = directionScale(ax, curveRatio, maxDeformPx, maxScale, along = true)
        val syDir = directionScale(ay, curveRatio, maxDeformPx, maxScale, along = false)

        if (ay <= 0f) return sxDir
        if (ax <= 0f) return syDir
        return blend(sxDir, syDir, ax / total, ay / total)
    }

    // ───────────────────────────────────────────── ③ 尺寸修正

    /**
     * 按面积 + 长宽比衰减最大拉伸倍数。
     *
     * 逆向自 TouchAnimatorParamProvider.Companion#calculateScaleWithK：
     *   decay = k1/1e6 · ln(area / 38416) + k2/1000 · (aspect − 1)   ∈ [0, 1]
     *   maxScale = max((maxScale − 1.03) · (1 − decay) + 1.03, 1.03)
     *
     * 38416 = 196² 是「小按钮」的基准面积；1.03 是下限 ——
     * 再小就没有形变了，大按钮至少还有一点点呼吸感。
     * k1 = 350000、k2 = 15 取自 CommonParams 默认值。
     */
    fun scaleWithK(width: Float, height: Float, maxScale: Float, k1: Int = 350000, k2: Int = 15): Float {
        val area = width * height
        val minSide = min(width, height)
        if (area <= K_MIN_AREA || minSide <= 0f) return maxScale
        val aspect = max(width, height) / minSide
        val decay = min(
            (k1 / 1_000_000.0) * ln(area / K_MIN_AREA) + (k2 / 1_000.0) * (aspect - 1.0),
            DECAY_MAX
        )
        return max((maxScale - K_MIN_SCALE) * (1f - decay.toFloat()) + K_MIN_SCALE, K_MIN_SCALE)
    }

    private const val K_MIN_AREA = 38416f
    private const val K_MIN_SCALE = 1.03f
    private const val DECAY_MAX = 1.0

    /**
     * 按压缩放的目标值。
     *
     * 通道结构逆向自 PressCalc#calculateUniformScaleBreakdown（两通道，取平均）：
     *   面积通道：area 在 [20dp², 150dp²] 区间内把缩放从 minScale 插到 maxScale
     *   宽高比通道：ratio > 2 之后从 minScale 插到 1.0（长条的按下更接近等比）
     *
     * 但**取值范围是本项目重新标定的**，不沿用 ColorOS 的 0.85~0.97：
     * 那组值是给计算器 90dp 见方的按键定的，缩 15% 才够"按得动"。
     * 放到这里 328×92dp 的主按钮上，0.85 的下限经过宽高比通道加权后仍有 ~0.89，
     * 一条整宽胶囊整体缩掉一成，看起来是"塌了一下"而不是被指尖压。
     *
     * 现在压到 **最多 4.5%**（0.955~0.985），实测主按钮 ≈0.973、56dp 图标钮 ≈0.959：
     * 只有边缘和描边轻微内收，形变/位移那些夸张效果交给拖动通道，
     * 按下只负责给一条"接触到了"的反馈。
     */
    fun pressScale(
        widthPx: Float,
        heightPx: Float,
        density: Float,
        minAreaDp: Int = 20,
        maxAreaDp: Int = 150,
        minScale: Float = PRESS_MIN_SCALE,
        maxScale: Float = PRESS_MAX_SCALE
    ): Float {
        if (widthPx <= 0f || heightPx <= 0f) return 1f
        val area = widthPx * heightPx
        val minArea = dp2px(minAreaDp, density).let { it * it }
        val maxArea = dp2px(maxAreaDp, density).let { it * it }
        val areaT = if (maxArea <= minArea) 0f else ((area - minArea) / (maxArea - minArea)).coerceIn(0f, 1f)
        val areaScale = minScale + areaT * (maxScale - minScale)

        val ratio = widthPx / heightPx
        val extremeRatio = max(ratio, 1f / ratio)
        if (extremeRatio <= ASPECT_ACTIVATION) return areaScale

        val aspectT = ((extremeRatio - ASPECT_ACTIVATION) / (ASPECT_EXTREME - ASPECT_ACTIVATION)).coerceIn(0f, 1f)
        val aspectScale = minScale + aspectT * (1f - minScale)
        return (areaScale + aspectScale) / 2f
    }

    private const val ASPECT_ACTIVATION = 2.0f
    private const val ASPECT_EXTREME = 15.0f

    /** 按压缩放的上下限（本项目标定值，见 [pressScale]）：最多收 4.5%。 */
    private const val PRESS_MIN_SCALE = 0.955f
    private const val PRESS_MAX_SCALE = 0.985f

    private fun dp2px(dp: Int, density: Float): Float = dp * density

    // ───────────────────────────────────────────── 手抬起弹曲线

    /**
     * 抬手时的阻尼比：拖得越远，回弹越弹。
     *
     * 逆向自 COUIITouchMotionUnit#computeHandUpBounce 的旧版分支
     * （TouchMotionDebugFlags.useNormalizedHandUpVelocity 为 false 时）：
     *   min = min(0.4, 0.65·baseBounce)，max = baseBounce，
     *   按 |pan| / handUpMaxInitialVelocity 在两者间插值。
     *
     * 直觉：轻轻一碰松手应该"啪"一下就收（bounce 低），
     * 拖了很远再松手才值得荡两下（bounce 高）。
     */
    fun handUpBounce(panPx: Float, baseBounce: Float, maxVelocityPxPerSec: Float = 1000f): Float {
        val lo = min(0.4f, 0.65f * baseBounce)
        val hi = baseBounce
        if (maxVelocityPxPerSec <= 0f) return hi
        val a = min(abs(panPx), maxVelocityPxPerSec)
        return (((hi - lo) / maxVelocityPxPerSec) * a + lo).coerceIn(lo, hi)
    }

    // ───────────────────────────────────────────── 弹出菜单：贝塞尔重力路径

    /** 三次贝塞尔的两个控制点（起点、终点由调用方按锚点偏移给出）。 */
    data class GravityPath(
        val p1x: Float,
        val p1y: Float,
        val p2x: Float,
        val p2y: Float
    )

    /**
     * 菜单弹出的「重力弧」控制点。
     *
     * 逆向自 com.coui.appcompat.animation.bezier.BezierGravityPathMath#controlPoints。
     *
     * 菜单不是直线平移过去的，它从锚点出发**沿一条被重力压弯的弧线**落到终点，
     * 弧的弯折量与「水平/垂直位移的比例」成正比 —— 横向拉得越远，垂得越狠，
     * 看起来才像「被按出来的」，而不是「滑出来的」。
     *
     *   gravity = gMax · clamp((len − dMin)/(dMax − dMin)) · min(|dx|,|dy|)/(max(|dx|,|dy|)+ε)
     *   P1 = A + t1·(B−A) + n·gravity
     *   P2 = A + t2·(B−A) + n·gravity      n 为 (B−A) 的单位法线
     *
     * 默认值（PopupGravityMotionParams）：dMin 30px、dMax 400px、gMax 130px、
     * t1 = 0.35、t2 = 0.65（归一化后保证 t1 < t2 且间距 ≥ 0.08）。
     */
    fun gravityPath(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        dMinPx: Float = 30f,
        dMaxPx: Float = 400f,
        arcMaxPx: Float = 130f,
        p1t: Float = 0.35f,
        p2t: Float = 0.65f
    ): GravityPath {
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return GravityPath(ax, ay, bx, by)

        val t = normalizeT(p1t, p2t)

        val span = dMaxPx - dMinPx
        val lenT = if (span > 1e-4f) ((len - dMinPx) / span).coerceIn(0f, 1f)
        else if (len <= dMinPx) 0f else 1f
        val balance = min(abs(dx), abs(dy)) / (max(abs(dx), abs(dy)) + 1e-4f)
        val gravity = arcMaxPx * lenT * balance

        // 单位法线，朝向让弯曲"往下"（与 ColorOS 一致：v 分量为正）
        var nx = -dy / len
        var ny = dx / len
        if (ny < 0f) {
            nx = -nx
            ny = -ny
        }

        return GravityPath(
            p1x = ax + t.first * dx + nx * gravity,
            p1y = ay + t.first * dy + ny * gravity,
            p2x = ax + t.second * dx + nx * gravity,
            p2y = ay + t.second * dy + ny * gravity
        )
    }

    /** t 归一化：夹到 [0.05, 0.95]，并保证两者间距 ≥ 0.08（逆向自 normalizePathTParameters）。 */
    private fun normalizeT(p1t: Float, p2t: Float): Pair<Float, Float> {
        var a = p1t.coerceIn(0.05f, 0.95f)
        var b = p2t.coerceIn(0.05f, 0.95f)
        if (a > b) {
            val s = a
            a = b
            b = s
        }
        if (b - a < 0.08f) {
            val c = ((a + b) * 0.5f).coerceIn(0.09f, 0.91f)
            a = (c - 0.04f).coerceIn(0.05f, 0.95f)
            b = (c + 0.04f).coerceIn(0.05f, 0.95f)
        }
        return a to b
    }

    /**
     * 三次贝塞尔取点。逆向自 BezierGravityPathMath#pointOnBezier。
     * @param t 0 = 锚点，1 = 菜单最终位置
     */
    fun pointOnPath(
        t: Float,
        ax: Float,
        ay: Float,
        g: GravityPath,
        bx: Float,
        by: Float
    ): Pair<Float, Float> {
        val u = 1f - t
        val uu = u * u
        val tt = t * t
        val uuu = uu * u
        val ttt = tt * t
        val uut3 = uu * 3f * t
        val utt3 = u * 3f * tt
        return Pair(
            ax * uuu + g.p1x * uut3 + g.p2x * utt3 + bx * ttt,
            ay * uuu + g.p1y * uut3 + g.p2y * utt3 + by * ttt
        )
    }
}
