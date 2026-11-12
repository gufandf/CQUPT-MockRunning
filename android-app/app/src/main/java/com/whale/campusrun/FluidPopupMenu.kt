/*
 * 液体弹出菜单 —— 按 ColorOS 计算器（com.coloros.calculator 17.2.16）的
 * COUIPopupListWindow + PopupMotionAnimateKitController 重写。
 *
 * 逆向证据见 refs/REA-CALCULATOR-FLUID.md，关键类：
 *   com.coui.appcompat.poplist.COUIPopupListWindow                 菜单本体（圆角 + 背景模糊）
 *   com.coui.appcompat.poplist.RoundFrameLayout                    28dp 圆角面板
 *   com.coui.appcompat.poplist.COUITouchRecyclerView               条目列表
 *   com.coui.appcompat.anim.ability.PopupMotionAnimateKitController 入场 / 退场编排
 *   com.coui.appcompat.animation.bezier.BezierGravityPathMath      重力弧
 *   com.coui.appcompat.anim.param.PopupGravityMotionParams         弹簧默认值
 *
 * 它和我们平时用的 PopupMenu 差在哪：
 *
 *   ① **菜单不是「出现」，是「飞过来」。**
 *      从锚点出发，沿一条被重力压弯的三次贝塞尔弧落到最终位置，
 *      同时从 0 放大到 1、从全透明到不透明 —— 三个量各有自己的弹簧。
 *      弧的弯折量与「水平 / 垂直位移的比例」成正比：被按出来的，不是滑出来的。
 *
 *   ② **三条弹簧的节奏不一样。**（PopupGravityMotionParams 默认值）
 *      路径：  bounce 0.13 / response 0.29   —— 先到，路线先画完
 *      缩放：  bounce 0.20 / response 0.40   —— 稍慢，留一点「弹出来」的余韵
 *      透明度：bounce 0.00 / response 0.27   —— 最快，不过冲（否则会闪一下）
 *      三者错开才有机重感；齐步走会像一段 GIF。
 *
 *   ③ **退场全程不过冲**（bounce 全 0），而且要快（0.21~0.26）。
 *      打开可以说故事，关闭不能拖 —— 用户已经在做下一件事了。
 *
 * 为什么自己写而不引 PopupMenu：
 *   ColorOS 的菜单要背景模糊（RenderEffect）、要沿弧线飞、要对条目做按压缩放，
 *   系统 PopupMenu 一样都给不了；引第三方库又违背「零第三方依赖」的底线。
 */
package com.whale.campusrun

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 一个菜单项。
 *
 * @param label 显示文字
 * @param hint  右侧辅助文字（可为空）
 * @param checked 是否打勾（单选场景）
 */
class FluidMenuItem(
    val label: String,
    val hint: String = "",
    val checked: Boolean = false
)

/**
 * 液体弹出菜单。
 *
 * 用法：
 * ```
 * FluidPopupMenu.build(context, anchorView, items) { index -> ... }.show()
 * ```
 * 菜单会在锚点下方弹出（下方放不下就翻到上方），沿重力弧飞入。
 */
class FluidPopupMenu private constructor(
    private val context: Context,
    private val anchor: View,
    private val items: List<FluidMenuItem>,
    private val onPick: (Int) -> Unit
) {

    companion object {
        /** 面板圆角 28dp —— RoundFrameLayout 的 coui_popup_round_frame_layout_corner_radius。 */
        private const val RADIUS_DP = 28f

        /** 条目最小高 40dp —— coui_popup_list_window_item_min_height。 */
        private const val ITEM_MIN_HEIGHT_DP = 40f

        /** 列表上下内边距 8dp —— coui_popup_list_padding_vertical。 */
        private const val LIST_PADDING_V_DP = 8f

        /** 条目左右内边距 20dp —— coui_popup_list_window_item_icon_margin_left。 */
        private const val ITEM_PADDING_H_DP = 20f

        /** 条目之间 8dp 的上下呼吸 —— item_padding_top_and_bottom。 */
        private const val ITEM_PADDING_V_DP = 8f

        /** 最大宽度 248dp —— coui_popup_list_window_item_max_width。 */
        private const val MAX_WIDTH_DP = 248f

        /** 距屏幕边缘 / 锚点的最小间距。 */
        private const val MARGIN_DP = 8f

        /**
         * 面板四周的「溢血」边距（dp）。
         *
         * PopupWindow 的内容**在窗口边界处会被裁掉**：面板贴着窗口画时，
         * ① 投影只能看到里面那一半，看起来是个方角；
         * ② 回弹时缩放/位移会过冲（scale 到 1.015），边缘会被切掉一条。
         * 所以窗口比面板大一圈，面板画在中间，投影和过冲都有地方去。
         */
        private const val BLEED_DP = 16f

        fun build(
            context: Context,
            anchor: View,
            items: List<FluidMenuItem>,
            onPick: (Int) -> Unit
        ): FluidPopupMenu = FluidPopupMenu(context, anchor, items, onPick)
    }

    private val density = context.resources.displayMetrics.density

    /** 窗口比面板每边多出来的像素（阴影 + 过冲）。 */
    private val bleed = BLEED_DP * density

    private val panel = MenuPanel(context)
    private val popup = PopupWindow(
        panel,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        true
    )

    /** 实际的重力弧 —— show() 时按锚点位置重算。 */
    private var usedPath: FluidMotion.GravityPath? = null

    /** 三条弹簧 —— 路径进度、缩放、透明度。 */
    private val pathSpring = LiquidSpring(response = 0.29f, bounce = 0.13f)
    private val scaleSpring = LiquidSpring(response = 0.40f, bounce = 0.20f, minVisibleChange = 1e-3f)
    private val alphaSpring = LiquidSpring(response = 0.27f, bounce = 0f, minVisibleChange = 1e-3f)

    private var fromX = 0f
    private var fromY = 0f
    private var dismissing = false

    init {
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // 菜单自己画阴影和圆角，窗口层不要再多一层
        popup.elevation = 0f
        popup.setOnDismissListener { panel.stopTicker() }
        panel.onPick = { index ->
            onPick(index)
            dismiss()
        }
        panel.onOutside = {
            if (popup.isShowing) dismiss()
        }
    }

    // ---------------------------------------------------------------- 显示

    fun show() {
        if (items.isEmpty() || popup.isShowing) return

        val size = panel.measureDesired(density, items)
        val w = size[0]
        val h = size[1]

        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val margin = (MARGIN_DP * density).toInt()

        // 水平：优先与锚点左对齐，右边放不下就往左挪
        var x = anchorLoc[0]
        if (x + w > screenW - margin) x = screenW - margin - w
        if (x < margin) x = margin

        // 垂直：优先在锚点下方（圆角避开一点），放不下翻到上方
        val belowY = anchorLoc[1] + anchor.height + margin
        val aboveY = anchorLoc[1] - h - margin
        val y = if (belowY + h <= screenH - margin) belowY
        else max(margin, aboveY)

        // 菜单中心相对锚点中心的偏移 = 重力弧的起点
        val anchorCx = anchorLoc[0] + anchor.width / 2f
        val anchorCy = anchorLoc[1] + anchor.height / 2f
        val menuCx = x + w / 2f
        val menuCy = y + h / 2f

        fromX = anchorCx - menuCx
        fromY = anchorCy - menuCy
        usedPath = FluidMotion.gravityPath(
            fromX, fromY, 0f, 0f,
            dMinPx = 30f * density,
            dMaxPx = 400f * density,
            arcMaxPx = 130f * density,
            p1t = 0.35f,
            p2t = 0.65f
        )

        panel.prepare(items, w, h)

        pathSpring.set(0f)
        scaleSpring.set(0f)
        alphaSpring.set(0f)
        dismissing = false

        val outerW = w + (bleed * 2).toInt()
        val outerH = h + (bleed * 2).toInt()
        popup.width = outerW
        popup.height = outerH
        // 窗口外扩，面板视觉位置不变
        popup.showAtLocation(
            anchor.rootView, Gravity.TOP or Gravity.START,
            (x - bleed).toInt(), (y - bleed).toInt()
        )

        panel.startTicker()
    }

    /** 退场：三弹簧反向，全程不过冲，收完再真正 dismiss。 */
    fun dismiss() {
        if (!popup.isShowing || dismissing) return
        dismissing = true

        pathSpring.retune(0.26f, 0f)
        scaleSpring.retune(0.21f, 0f)
        alphaSpring.retune(0.23f, 0f)

        panel.stopTicker()
        panel.startTicker()
    }

    // ---------------------------------------------------------------- 面板

    /**
     * 菜单面板本体：自绘圆角玻璃 + 条目 + 按压高亮。
     *
     * 条目不用子 View —— 一个菜单最多几行，全部在一个 Canvas 里画完更快，
     * 也才能在「按压时整块面板跟着轻微缩放」的同时保持文字清晰（不需要重建布局）。
     */
    private inner class MenuPanel(context: Context) : View(context) {

        var onPick: ((Int) -> Unit)? = null
        var onOutside: (() -> Unit)? = null

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** 面板阴影：没有 RenderEffect，用一层模糊描边当投影。 */
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, R.color.menu_shadow)
            maskFilter = BlurMaskFilter(14f * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 1f
        }
        private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 15f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * density
        }
        private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val panelPath = Path()
        private val panelRect = RectF()

        private var rows: List<FluidMenuItem> = emptyList()
        private var panelW = 0f
        private var panelH = 0f
        private var rowCenters = FloatArray(0)
        private var rowHeight = 0f
        private var pressedIndex = -1
        private var pressAlpha = 0f

        private val cPanel = ContextCompat.getColor(context, R.color.menu_panel)
        private val cGlass = ContextCompat.getColor(context, R.color.glass)
        private val cStroke = ContextCompat.getColor(context, R.color.glass_stroke)
        private val cFg = ContextCompat.getColor(context, R.color.fg)
        private val cDim = ContextCompat.getColor(context, R.color.fg_dim)
        private val cAccent = ContextCompat.getColor(context, R.color.accent)
        private val cPress = ContextCompat.getColor(context, R.color.accent)

        private val ticker = object : Runnable {
            private var last = 0L

            override fun run() {
                val now = AnimationUtils.currentAnimationTimeMillis()
                if (last == 0L) last = now
                val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.032f)
                last = now

                val target = if (dismissing) 0f else 1f
                var moving = pathSpring.step(target, dt)
                moving = scaleSpring.step(target, dt) or moving
                moving = alphaSpring.step(target, dt) or moving

                if (pressedIndex < 0 && pressAlpha > 0f) {
                    pressAlpha = (pressAlpha - dt * 6f).coerceAtLeast(0f)
                }

                applyMotion()

                if (moving) {
                    // 重新挂自己推进弹簧
                    postOnAnimation(this)
                    return
                }
                last = 0L
                if (dismissing && popup.isShowing) popup.dismiss()
            }
        }

        /** 按当前的弹簧值把「沿弧线飞 + 缩放 + 透明度」落到 View 上。 */
        fun applyMotion() {
            val g = usedPath
            val t = pathSpring.value
            if (g == null) {
                translationX = 0f
                translationY = 0f
            } else {
                // t = 0 在锚点，t = 1 在最终位置
                val p = FluidMotion.pointOnPath(t, fromX, fromY, g, 0f, 0f)
                translationX = p.first
                translationY = p.second
            }

            val s = scaleSpring.value.coerceAtLeast(0f)
            pivotX = width / 2f
            pivotY = height / 2f
            scaleX = s
            scaleY = s

            alpha = alphaSpring.value.coerceIn(0f, 1f)
        }

        private var wantTicker = false

        /**
         * 开始推进弹簧。
         *
         * 注意：PopupWindow 里的 contentView 在 showAtLocation 返回时**还没 attach**，
         * 这时 postOnAnimation 会被丢掉，弹簧一步都不走 —— 表现就是「点了没反应」。
         * 所以先记一个意愿，等 attach 之后再真正挂上去。
         */
        fun startTicker() {
            wantTicker = true
            applyMotion()
            if (isAttachedToWindow) postOnAnimation(ticker)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (wantTicker) postOnAnimation(ticker)
        }

        fun stopTicker() {
            removeCallbacks(ticker)
        }

        /** 量一下菜单需要多大。宽度按最长文字算，夹在 [120dp, 248dp]。 */
        fun measureDesired(d: Float, list: List<FluidMenuItem>): IntArray {
            textPaint.textSize = 15f * d
            hintPaint.textSize = 13f * d

            var maxText = 0f
            var maxHint = 0f
            for (it in list) {
                maxText = max(maxText, textPaint.measureText(it.label))
                if (it.hint.isNotEmpty()) maxHint = max(maxHint, hintPaint.measureText(it.hint))
            }

            val padH = ITEM_PADDING_H_DP * d
            val checkW = if (list.any { it.checked }) 26f * d else 0f
            val gap = if (maxHint > 0f) 16f * d else 0f

            val wantW = padH * 2 + maxText + gap + maxHint + checkW
            val minW = 132f * d
            val maxW = MAX_WIDTH_DP * d
            val w = min(max(wantW, minW), maxW).toInt()

            val rowH = max(textPaint.textSize * 1.9f, ITEM_MIN_HEIGHT_DP * d) + ITEM_PADDING_V_DP * 2 * d
            val h = (rowH * list.size + LIST_PADDING_V_DP * 2 * d).toInt()

            return intArrayOf(max(w, 1), max(h, 1))
        }

        fun prepare(list: List<FluidMenuItem>, w: Int, h: Int) {
            rows = list
            val padV = LIST_PADDING_V_DP * density
            val usable = h - padV * 2
            rowHeight = usable / list.size.coerceAtLeast(1)
            // 行中心 = 溢血边距 + 面板内偏移
            rowCenters = FloatArray(list.size) { bleed + padV + rowHeight * (it + 0.5f) }
            panelW = w.toFloat()
            panelH = h.toFloat()
            layoutParams = ViewGroup.LayoutParams(
                w + (bleed * 2).toInt(),
                h + (bleed * 2).toInt()
            )
            requestLayout()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return

            val r = RADIUS_DP * density
            panelRect.set(bleed + 0.5f, bleed + 0.5f, bleed + panelW - 0.5f, bleed + panelH - 0.5f)
            panelPath.reset()
            panelPath.addRoundRect(panelRect, r, r, Path.Direction.CW)

            // 投影：整体下移一点再画一遍模糊轮廓。
            // 现在面板外有一圈溢血边距，投影的圆角不会被窗口切掉。
            val saveS = canvas.save()
            canvas.translate(0f, 4f * density)
            canvas.drawPath(panelPath, shadowPaint)
            canvas.restoreToCount(saveS)

            // 玻璃面：厚玻璃（近乎不透明），菜单是阅读层，不能透出底下的字
            bgPaint.color = cPanel
            canvas.drawPath(panelPath, bgPaint)

            strokePaint.color = cStroke
            canvas.drawPath(panelPath, strokePaint)

            val padH = ITEM_PADDING_H_DP * density

            for (i in rows.indices) {
                val item = rows[i]
                val cy = rowCenters[i]

                if (i == pressedIndex) {
                    pressPaint.color = Color.argb((0.10f * 255).toInt(), 47, 127, 224)
                    val hr = RectF(
                        bleed + 6f * density, cy - rowHeight / 2f + 2f * density,
                        bleed + panelW - 6f * density, cy + rowHeight / 2f - 2f * density
                    )
                    canvas.drawRoundRect(hr, 14f * density, 14f * density, pressPaint)
                }

                textPaint.color = cFg
                val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(item.label, bleed + padH, baseline, textPaint)

                if (item.hint.isNotEmpty()) {
                    hintPaint.color = cDim
                    val hw = hintPaint.measureText(item.hint)
                    val hb = cy - (hintPaint.descent() + hintPaint.ascent()) / 2f
                    canvas.drawText(item.hint, bleed + panelW - padH - hw, hb, hintPaint)
                }

                if (item.checked) {
                    checkPaint.color = cAccent
                    val cx = bleed + panelW - padH - 8f * density
                    val tick = Path()
                    tick.moveTo(cx - 5f * density, cy)
                    tick.lineTo(cx - 1.2f * density, cy + 4f * density)
                    tick.lineTo(cx + 6f * density, cy - 5f * density)
                    canvas.drawPath(tick, checkPaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedIndex = rowAt(event.y)
                    if (pressedIndex >= 0) pressAlpha = 1f
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val idx = rowAt(event.y)
                    if (idx != pressedIndex) {
                        pressedIndex = idx
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val idx = rowAt(event.y)
                    pressedIndex = -1
                    invalidate()
                    if (idx >= 0 && idx < rows.size) onPick?.invoke(idx)
                    else onOutside?.invoke()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressedIndex = -1
                    invalidate()
                    onOutside?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun rowAt(rawY: Float): Int {
            val y = rawY
            for (i in rows.indices) {
                val half = rowHeight / 2f
                if (y >= rowCenters[i] - half && y <= rowCenters[i] + half) return i
            }
            return -1
        }
    }
}
