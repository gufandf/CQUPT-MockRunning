/*
 * 鲸鲸约跑 · 地图画布
 *
 * 主页中央那块「地图」，不接任何地图 SDK —— 守住「零第三方依赖」。
 * 只画三样东西：
 *   · 绿色边界：电子围栏（可跑区域）
 *   · 暗红多边形：禁跑区
 *   · 红色轨迹：按进度逐段显示，看起来就是实时在跑
 *
 * 坐标系是 WGS84 经纬度（lat, lon），和 TrackGen 的输出一致；
 * 在操场这种几百米的尺度上，直接线性映射和墨卡托的差别肉眼看不出来。
 */
package com.whale.campusrun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_bg)
        style = Paint.Style.FILL
    }

    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_boundary)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val excludePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_exclude)
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_track)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_track)
        style = Paint.Style.FILL
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.fg_dim)
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }

    /** 电子围栏，每个顶点是 [lat, lon]。 */
    private var fence: List<DoubleArray> = emptyList()

    /** 禁跑区，每个是若干顶点。 */
    private var excludes: List<List<DoubleArray>> = emptyList()

    /** 整条轨迹（开跑时就生成好），按 [progress] 逐段显示。 */
    private var track: List<DoubleArray> = emptyList()

    /** 0..1，已跑比例。 */
    private var progress: Float = 0f

    /**
     * 是否显示"设置场地后显示边界 / 开始打卡后显示轨迹"那句占位提示。
     *
     * 倒计时会在地图上盖一层压暗，这两行字在遮罩下面是半透明的，
     * 会跟中间的大号秒数叠在一起（"3" 和"日"糊成一团）。倒计时期间关掉它。
     */
    private var hintVisible = true

    private val pad = 48f

    /** 地图卡片的圆角半径，跟 @drawable/box 的 radius_card（24dp）对齐。 */
    private val radius = resources.getDimension(R.dimen.radius_card)

    /** 圆角裁切路径，复用避免每次 onDraw 新建。 */
    private val clipPath = Path()

    /** 设置场地：边界 + 禁跑区。 */
    fun setArea(fencePoly: List<DoubleArray>, excludePolys: List<List<DoubleArray>>) {
        fence = fencePoly
        excludes = excludePolys
        invalidate()
    }

    /** 整条轨迹，只存不全部画，画到 [progress] 为止。 */
    fun setTrack(points: List<DoubleArray>) {
        track = points
        invalidate()
    }

    /** 倒计时期间把占位提示收起来，免得跟秒数叠字。 */
    fun setHintVisible(visible: Boolean) {
        if (hintVisible != visible) {
            hintVisible = visible
            invalidate()
        }
    }

    fun setProgress(p: Float) {
        val np = p.coerceIn(0f, 1f)
        if (np != progress) {
            progress = np
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 圆角：先铺一块圆角底色，再把内容整体裁进同一个圆角。
        // 只给 View 设背景是不够的 —— 边界和轨迹是直接画在 Canvas 上的，
        // 不裁切的话线会溢到圆角外面去。
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bgPaint)
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        drawContent(canvas)
        canvas.restore()
    }

    private fun drawContent(canvas: Canvas) {

        val shown = if (track.isEmpty()) 0
        else (progress * track.size).toInt().coerceIn(if (progress > 0f) 1 else 0, track.size)

        val all = ArrayList<DoubleArray>(fence.size + shown)
        all.addAll(fence)
        all.addAll(track.take(shown))

        if (all.isEmpty()) {
            if (hintVisible) {
                canvas.drawText("设置场地后显示边界", width / 2f, height / 2f - 10f, hintPaint)
                canvas.drawText("开始打卡后显示轨迹", width / 2f, height / 2f + 30f, hintPaint)
            }
            return
        }

        var latMin = Double.MAX_VALUE
        var latMax = -Double.MAX_VALUE
        var lonMin = Double.MAX_VALUE
        var lonMax = -Double.MAX_VALUE
        for (p in all) {
            if (p[0] < latMin) latMin = p[0]
            if (p[0] > latMax) latMax = p[0]
            if (p[1] < lonMin) lonMin = p[1]
            if (p[1] > lonMax) lonMax = p[1]
        }

        val latRange = latMax - latMin
        val lonRange = lonMax - lonMin
        if (latRange <= 0 || lonRange <= 0) return

        val availW = width - 2 * pad
        val availH = height - 2 * pad
        if (availW <= 0 || availH <= 0) return

        val scale = min(availW / lonRange, availH / latRange).toFloat()
        val offX = pad + (availW - lonRange * scale) / 2f
        val offY = pad + (availH - latRange * scale) / 2f

        fun sx(lon: Double) = ((lon - lonMin) * scale + offX).toFloat()
        // 纬度向上为大，屏幕 y 向下为大，所以要翻过来
        fun sy(lat: Double) = (height - ((lat - latMin) * scale + offY)).toFloat()

        // 禁跑区
        for (poly in excludes) {
            if (poly.size < 3) continue
            val path = Path()
            poly.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(sx(p[1]), sy(p[0])) else path.lineTo(sx(p[1]), sy(p[0]))
            }
            path.close()
            canvas.drawPath(path, excludePaint)
        }

        // 边界
        if (fence.size >= 3) {
            val path = Path()
            fence.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(sx(p[1]), sy(p[0])) else path.lineTo(sx(p[1]), sy(p[0]))
            }
            path.close()
            canvas.drawPath(path, boundaryPaint)
        }

        // 轨迹
        if (shown >= 2) {
            val path = Path()
            for (i in 0 until shown) {
                val p = track[i]
                if (i == 0) path.moveTo(sx(p[1]), sy(p[0])) else path.lineTo(sx(p[1]), sy(p[0]))
            }
            canvas.drawPath(path, trackPaint)
        }

        // 当前位置
        if (shown >= 1) {
            val p = track[shown - 1]
            canvas.drawCircle(sx(p[1]), sy(p[0]), 9f, dotPaint)
        }
    }
}
