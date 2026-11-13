/*
 * 开跑前的 3 秒倒计时（画在地图上层）。
 *
 * 为什么要有它：点【开始打卡】之后，引擎要先换 token、拉场地、生成轨迹，几百毫秒到
 * 一两秒不等。这段时间界面没有任何反馈，用户会以为没点上，于是再点一下 —— 那一下
 * 可能是取消，也可能是重复触发。给一段**看得见的 3 秒**，既盖住数据准备的空白，
 * 也给了一个「现在反悔还来得及」的窗口：倒计时结束前再点一次按钮就取消。
 *
 * 视觉：地图整体压暗一层（scrim），中间一个环 + 大号秒数。
 *   · 环是**连续**从 3 走到 0 的（线性，不用弹簧）—— 倒计时是时间，不是手感，
 *     弹一下会让人怀疑「刚才是一秒还是两秒」；
 *   · 秒数每一次切换走一个短弹簧过冲（scale 1.35 → 1.0，response 0.28，bounce 0.35），
 *     给每一秒一个"啪"的节拍点；
 *   · 入场淡入 180ms，取消时淡出 —— 不做花哨转场，它不是内容，只是过程。
 */
package com.whale.campusrun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.min

class CountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** 倒计时总长（秒）。改这里就够了，MainActivity 从 [SECONDS] 取。 */
        const val SECONDS = 3
    }

    private val density = resources.displayMetrics.density

    /** 环的线宽：比主页进度环细一点 —— 它是临时层，不该压过内容。 */
    private val ringStroke = 7f * density

    private val ringRect = RectF()
    private val scrimRect = RectF()

    /** 环底：一圈几乎看不见的灰，用来框住进度。 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.field_bg)
    }

    /** 剩下的那一截：主色，随时间从满圈收回去。 */
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }

    /**
     * 压暗地图的那一层。
     *
     * 颜色**必须**是深色（`@color/scrim`，两套主题都是深色，只是深色下更狠一点）。
     * 曾经用 `fg` 去想"浅色主题下压灰、深色主题下压黑"，结果深色主题的 fg 本身就是
     * 浅色（#E8EEF7），一加透明度就把深色底刷成灰白，看上去像弹了个系统对话框。
     */
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scrim)
        alpha = 0
    }

    /**
     * 数字用前景色（深色主题下是近白），环用主色。
     *
     * 数字和环同为蓝色时，压在深色遮罩上整块都是蓝的，秒数不够跳；
     * 环给颜色、数字给亮度，一眼就能读出"还剩几秒"。
     */
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.fg)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.fg_dim)
        textSize = 13f * density
    }

    /** 剩余秒数（浮点），从 [SECONDS] 线性减到 0。 */
    private var remaining = SECONDS.toFloat()

    /** 每次秒数变化时弹一下：1.35 → 1.0。 */
    private val pop = LiquidSpring(response = 0.28f, bounce = 0.35f, minVisibleChange = 1e-3f)

    /** 整层的淡入淡出 0..1。 */
    /**
     * 倒计时的**状态机**（纯 JVM，见 `CountdownClock`）。
     *
     * 视觉部分只负责画，什么时候结束、什么时候回调全部交给它 ——
     * 上一版把这段逻辑写在这个 View 的 ticker 里，`fadeTarget` 被提前置 0，
     * 于是 `onFinished` 永远不触发：数字淡出、遮罩消失，但跑步没开始。
     */
    private val clock = CountdownClock(SECONDS.toFloat())

    private val fade: Float get() = clock.fade
    private var fadeTarget = 0f

    /** 上一次画出来的整数秒，用来判定「该弹一下了」。 */
    private var lastShownSecond = -1

    /** 上一帧的时间戳（毫秒）。0 表示刚起步。 */
    private var lastFrame = 0L

    /** 倒计时走完 / 被取消时的回调。只会触发一次。 */
    var onFinished: (() -> Unit)? = null

    /** 是否正在倒计时（入场动画之后到结束之前都算）。 */
    val isRunning: Boolean get() = clock.visible

    init {
        // 它是纯装饰层，不参与点击 —— 点地图/点开始按钮都要照常落到下层
        isClickable = false
        isFocusable = false
        visibility = GONE
        pop.set(1f)
    }

    /** 开始（或重新开始）一轮倒计时。 */
    fun start() {
        clock.start()
        remaining = SECONDS.toFloat()
        lastShownSecond = -1
        lastFrame = 0L
        pop.set(1.35f)          // 第一个数字也弹一下，进场就有节拍
        fadeTarget = 1f
        visibility = VISIBLE
        postInvalidateOnAnimation()
        postOnAnimation(ticker)
    }

    /** 取消：立刻淡出，不触发 [onFinished]。 */
    fun cancel() {
        clock.cancel()
        fadeTarget = 0f
        postOnAnimation(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = AnimationUtils.currentAnimationTimeMillis()
            if (lastFrame == 0L) lastFrame = now
            // dt 夹在 32ms 内：切后台回来时不要一口气把 3 秒全吃掉
            val dt = ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.032f)
            lastFrame = now

            // 状态推进全交给 clock（纯函数，有单测钉住"一定会回调"这件事）
            val done = clock.step(dt)
            remaining = clock.remaining
            var moving = pop.step(1f, dt)

            if (done) {
                // 淡出结束、这一层收掉，同时发出开跑信号
                visibility = GONE
                lastFrame = 0L
                invalidate()
                onFinished?.invoke()
                onFinished = null
                return
            }

            invalidate()
            if (moving || clock.visible) {
                postOnAnimation(this)
            } else {
                visibility = GONE
                lastFrame = 0L
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (fade <= 0.001f) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.16f
        if (radius <= 0f) return

        // 压暗：颜色自带 alpha（@color/scrim），这里再乘淡入淡出系数。
        // 画成圆角矩形而不是整块方片 —— 底下那张地图是 24dp 圆角卡片，
        // 方角压暗会在四个角露出一撮多余的黑边。
        val scrimBase = android.graphics.Color.alpha(scrimPaint.color)
        scrimPaint.alpha = (scrimBase * fade).toInt().coerceIn(0, 255)
        val r = resources.getDimension(R.dimen.radius_card)
        scrimRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(scrimRect, r, r, scrimPaint)

        ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val a = (fade * 255).toInt().coerceIn(0, 255)
        trackPaint.alpha = (a * 0.5f).toInt()
        canvas.drawArc(ringRect, 0f, 360f, false, trackPaint)

        // 剩下的时间占比：从 12 点方向顺时针，随时间收回 12 点
        val frac = (remaining / SECONDS).coerceIn(0f, 1f)
        if (frac > 0f) {
            arcPaint.alpha = a
            canvas.drawArc(ringRect, -90f, 360f * frac, false, arcPaint)
        }

        // 秒数：向上取整，所以显示 3 → 2 → 1（最后一秒停在 1，不会闪一个 0）
        val sec = ceil(remaining.toDouble()).toInt().coerceIn(0, SECONDS)
        if (sec != lastShownSecond) {
            if (lastShownSecond >= 0) pop.set(1.35f)
            lastShownSecond = sec
        }
        val shown = if (sec == 0) 0 else sec

        numberPaint.alpha = a
        numberPaint.textSize = radius * 1.05f * pop.value
        val baseline = cy - (numberPaint.descent() + numberPaint.ascent()) / 2f
        canvas.drawText(shown.toString(), cx, baseline, numberPaint)

        hintPaint.alpha = (a * 0.8f).toInt()
        canvas.drawText("再点一次可取消", cx, cy + radius + 34f * density, hintPaint)
    }
}
