/*
 * 滚轮选择器（数字拨盘）。
 *
 * 参照 ColorOS「新建闹钟」的时间选择：中间一行高亮放大、上下两行淡出，
 * 中间行上下各一条细线，手指拨动能甩，松手吸附到最近一格。
 *
 * 为什么自己画：
 *   系统只有 NumberPicker，观感是 Holo/Material 的框线风格，和这套卡片对不上；
 *   引第三方 wheel 库违背「零第三方依赖」。这里只有一个 Canvas + OverScroller，
 *   约两百行，行为可预期。
 *
 * 交互约定：
 *   · 松手后先按惯性滑动（fling），滑停再吸附到最近一行；
 *   · 吸附完成才回调 onSelectionChanged —— 否则拖动过程中 prefs 会被写几十次。
 */
package com.whale.campusrun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt

class WheelPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** 可见三行：中间选中行 + 上下各一行做「还有更多」的暗示。 */
        private const val VISIBLE_ROWS = 3

        /** 吸附动画时长。太长会显得迟钝，太短像弹一下。 */
        private const val SNAP_MS = 220
    }

    /** 候选值（已经格式化好的字符串）。 */
    var items: List<String> = emptyList()
        set(value) {
            field = value
            scrollPx = selectedIndex * rowHeight
            invalidate()
        }

    /** 当前选中下标。 */
    var selectedIndex: Int = 0
        private set

    /** 吸附结束时回调，参数是最终下标。拖动过程中不会触发。 */
    var onSelectionChanged: ((Int) -> Unit)? = null

    /** 选中项后缀（配速的「分」/「秒」这种单位），画在右侧。 */
    var unit: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val rowHeight = 40f * density
    private val radius = 24f * density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 24f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val sidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18f * density
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        textAlign = Paint.Align.RIGHT   // 右对齐才不会顶出边界被裁
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
    }
    private val rect = RectF()

    private val cFg = ContextCompat.getColor(context, R.color.fg)
    private val cDim = ContextCompat.getColor(context, R.color.fg_dim)
    private val cBg = ContextCompat.getColor(context, R.color.field_bg)
    private val cLine = ContextCompat.getColor(context, R.color.row_divider)

    private val scroller = OverScroller(context)
    private var scrollPx = 0f
    private var lastY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    /** 吸附完成后才允许回调，避免写 prefs 写到手软。 */
    private var pendingNotify = false

    private val flingTicker = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                scrollPx = scroller.currY.toFloat()
                invalidate()
                postOnAnimation(this)
            } else {
                snapToNearest()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((120f * density).toInt(), widthMeasureSpec)
        val h = resolveSize((rowHeight * VISIBLE_ROWS).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f

        // 选择区底：一个浅底圆角块，像闹钟页里那块「拨盘区」
        rect.set(0f, 0f, w, h)
        bgPaint.color = cBg
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // 中线（选中行上下各一条）
        linePaint.color = cLine
        canvas.drawLine(0f, cy - rowHeight / 2f, w, cy - rowHeight / 2f, linePaint)
        canvas.drawLine(0f, cy + rowHeight / 2f, w, cy + rowHeight / 2f, linePaint)

        if (items.isEmpty()) return

        val center = scrollPx / rowHeight
        val first = (center - VISIBLE_ROWS).toInt().coerceAtLeast(0)
        val last = (center + VISIBLE_ROWS).toInt().coerceAtMost(items.size - 1)

        for (i in first..last) {
            val dy = (i - center) * rowHeight
            val y = cy + dy
            if (y < -rowHeight || y > h + rowHeight) continue

            // 越靠边越淡：中间 1.0，相邻 0.45，再远 0.18
            val dist = abs(dy) / rowHeight
            val alpha = (1f - dist * 0.55f).coerceIn(0.15f, 1f)
            val isCenter = dist < 0.5f

            val paint = if (isCenter) textPaint else sidePaint
            paint.color = (if (isCenter) cFg else cDim)
            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)

            val baseline = y - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(items[i], w / 2f, baseline, paint)

            if (unit.isNotEmpty() && isCenter) {
                unitPaint.color = cDim
                unitPaint.alpha = 190
                canvas.drawText(unit, w - 10f * density, baseline, unitPaint)
            }
        }
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return false

        if (velocity == null) velocity = VelocityTracker.obtain()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                /*
                 * 只有按在**中间高亮行**上才算「要转滚轮」，按在上下两行就把手势还给父容器。
                 *
                 * 为什么：跑步参数页是 ScrollView，三个滚轮几乎占满中部。以前滚轮对任何
                 * ACTION_DOWN 都 requestDisallowIntercept，于是「想往下滑看电池白名单」
                 * 的结果是参数被改了（实测距离从 2.0 被滑成 0.5）。中间行本来就是
                 * 唯一高亮、唯一表达"当前值"的地方，让它独占拖动既符合直觉，
                 * 也给页面留出上下两条可滚动带。
                 */
                // 判定带 = 中间行 + 上下各 4dp（总共 48dp，正好是 Android 建议的最小触控尺寸）。
                // 只取 rowHeight/2 的话是 40dp，比建议值小，落指要很准。
                val halfBand = rowHeight / 2f + 4f * density
                if (abs(event.y - height / 2f) > halfBand) return false

                scroller.forceFinished(true)
                removeCallbacks(flingTicker)
                dragging = true
                lastY = event.y
                velocity?.clear()
                velocity?.addMovement(event)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dy = event.y - lastY
                lastY = event.y
                scrollPx = (scrollPx - dy).coerceIn(0f, maxScroll())
                velocity?.addMovement(event)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)

                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                velocity?.recycle()
                velocity = null

                if (abs(vy) > 120f) {
                    // 甩一把：让 OverScroller 滑到边界，停下来再吸附
                    scroller.fling(
                        0, scrollPx.roundToInt(),
                        0, (-vy).roundToInt(),
                        0, 0,
                        0, maxScroll().roundToInt()
                    )
                    postOnAnimation(flingTicker)
                } else {
                    snapToNearest()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun maxScroll(): Float = ((items.size - 1) * rowHeight).coerceAtLeast(0f)

    /** 吸附：把 scrollPx 缓动到最近一行的整数倍，结束后再回调。 */
    private fun snapToNearest() {
        val target = (scrollPx / rowHeight).roundToInt().coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val targetPx = target * rowHeight
        val delta = targetPx - scrollPx
        if (abs(delta) < 0.5f) {
            finishSnap(target)
            return
        }
        scroller.startScroll(0, scrollPx.roundToInt(), 0, delta.roundToInt(), SNAP_MS)
        pendingNotify = true
        postOnAnimation(flingTicker)
    }

    private fun finishSnap(index: Int) {
        scrollPx = index * rowHeight
        invalidate()
        if (index != selectedIndex || pendingNotify) {
            selectedIndex = index
            pendingNotify = false
            onSelectionChanged?.invoke(index)
        }
    }

    /** 外部设置选中值（不进动画，直接落位）。 */
    fun setSelectedIndex(index: Int, notify: Boolean = false) {
        val i = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        selectedIndex = i
        scrollPx = i * rowHeight
        invalidate()
        if (notify) onSelectionChanged?.invoke(i)
    }

    /** 供外部做「动到某一项」的平滑跳转（点 chip 时用）。 */
    fun animateTo(index: Int) {
        val i = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val targetPx = i * rowHeight
        scroller.startScroll(0, scrollPx.roundToInt(), 0, (targetPx - scrollPx).roundToInt(), SNAP_MS)
        pendingNotify = true
        postOnAnimation(flingTicker)
    }

    /** 一次性把「值 → 下标」对齐，不触发回调。 */
    fun alignTo(value: String) {
        val i = items.indexOf(value)
        if (i >= 0) setSelectedIndex(i, notify = false)
    }

    /** 选中值。 */
    fun selectedValue(): String = items.getOrElse(selectedIndex) { "" }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(flingTicker)
        velocity?.recycle()
        velocity = null
    }
}
