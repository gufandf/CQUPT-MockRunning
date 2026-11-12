/*
 * 打卡进度环。
 *
 * 主页原来是一段文字：「进度 62%　已跑 1.24 / 2.00 km　用时 12:30　剩余 --」。
 * 数字并排堆在一起，一眼看不出「还剩多少」——那才是用户真想知道的事。
 *
 * 这里用一个环把「已完成」变成一眼可读的形状，中间直接写百分比；
 * 右侧竖排「剩余时间 / 已跑里程 / 配速」，主次分明。
 *
 * 环不是逐帧跳变的：进度的推进走 LiquidSpring（和按钮同一套解析解弹簧），
 * 服务器回来一批点就补一点，环会自己滑过去 —— 不会一格一格地蹦。
 */
package com.whale.campusrun

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import kotlin.math.min

class RunProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 目标进度 0..1（由 RunState 算出来）。 */
    private var target = 0f

    /** 当前显示的进度，靠弹簧追 target。 */
    private val spring = LiquidSpring(response = 0.55f, bounce = 0.15f, minVisibleChange = 1e-3f)

    private val density = resources.displayMetrics.density
    private val stroke = 11f * density
    private val rect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.field_bg)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f * density
        color = ContextCompat.getColor(context, R.color.fg)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    private var label: String = "0%"
    private var finished = false

    private val ticker = object : Runnable {
        private var last = 0L
        override fun run() {
            val now = AnimationUtils.currentAnimationTimeMillis()
            if (last == 0L) last = now
            val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.032f)
            last = now
            val moving = spring.step(target, dt)
            invalidate()
            if (moving) postOnAnimation(this) else last = 0L
        }
    }

    init {
        spring.set(0f)
    }

    /**
     * @param fraction 0..1
     * @param pctText  环中心那行字（"62%" / "完成"）
     */
    fun setProgress(fraction: Float, pctText: String, done: Boolean = false) {
        val f = fraction.coerceIn(0f, 1f)
        val jump = f < spring.value - 0.2f   // 重新开跑时直接落位，不要从旧值滑回来
        if (jump) spring.set(f)
        target = f
        label = pctText
        finished = done
        postOnAnimation(ticker)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = stroke / 2f + 2f * density
        rect.set(inset, inset, w - inset, h - inset)
        applyGradient()
    }

    private fun applyGradient() {
        val c1 = ContextCompat.getColor(context, R.color.accent)
        val c2 = ContextCompat.getColor(context, R.color.accent_deep)
        val c3 = ContextCompat.getColor(context, R.color.ok)
        arcPaint.shader = if (finished) {
            LinearGradient(0f, 0f, 0f, height.toFloat(), c3, c1, Shader.TileMode.CLAMP)
        } else {
            // 从 12 点方向顺时针铺一圈，颜色跟着进度转
            SweepGradient(
                width / 2f, height / 2f,
                intArrayOf(c1, c2, c1),
                floatArrayOf(0f, 0.5f, 1f)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (rect.width() <= 0f) return

        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        val sweep = 360f * spring.value
        if (sweep > 0.5f) {
            canvas.drawArc(rect, -90f, sweep, false, arcPaint)
        }

        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, textPaint)
    }

    /** 圆环在布局里的理想边长（窄屏也别撑爆）。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (108f * density).toInt()
        val w = resolveSize(min(want, MeasureSpec.getSize(widthMeasureSpec)), widthMeasureSpec)
        val h = resolveSize(want, heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }
}
