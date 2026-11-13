/*
 * 到头 / 到尾还能再拉一下的 ScrollView（下拉刷新 + 上拉刷新）。
 *
 * 为什么不引 SwipeRefreshLayout：
 *   · 它只支持顶部下拉，底部没有对称的能力；
 *   · 项目一直守着「零第三方依赖」，为一个手势加一个库不划算。
 *
 * 做法：ScrollView 滚到顶（或底）之后，继续往外拉的距离本来是没有意义的
 * （只会触发系统的 overscroll 光晕），这里把它截下来当成「刷新意图」：
 *   · 顶部继续往下拉 → 下拉刷新
 *   · 底部继续往上拉 → 上拉刷新
 * 拉过 64dp（原始的手指距离）松手就触发。
 *
 * 为什么这一版要补「跟手位移 + 回弹」：
 *   上一版把手势吃掉之后内容一动不动 —— 手指在拉、屏幕是死的，
 *   用户只能靠「气泡有没有变成刷新中」去猜自己拉够没有；不到阈值松手又是硬切回原样。
 *   手感这件事在触摸动效里不是装饰，它承担了「你有没有在拉、拉了多远」的信息，
 *   所以按项目里已有的流体动效参数化补齐三段：
 *     · 跟手段 —— 边缘外的距离经 FluidMotion.rubberBand 压成视觉位移，
 *                 越拉越硬，渐近但**永远到不了** MAX_OFFSET_DP，不会「扯断」；
 *     · 回弹段 —— 抬手用 LiquidSpring（response / bounce 参数化）把位移收回，
 *                 不到阈值弹回 0，过冲一点再停住，和 LiquidButton 是同一套手感；
 *     · 刷新段 —— 触发后**不**立刻归零，先停在六成位移的「停靠位」，
 *                 等外部 setRefreshing(false) 才收回；
 *                 这样「刷新中…」的气泡和内容的位移是同一个姿势，两处不会互相打脸。
 *
 * 只在「已经在边上」时才接管手势，正常的滚动一行代码都没改。
 * 接口（onRefresh / onPull / onRelease / setRefreshing）与上一版完全一致，
 * 调用方 SettingsGroupActivity.bindStats() 不需要跟着改。
 */
package com.whale.campusrun

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import kotlin.math.abs

/**
 * 边缘拉伸的纯数学部分：跟手映射、停靠位、回弹参数、帧间隔夹取。
 *
 * 单独拎出来（而不是写死在 View 里）是为了能在普通 JVM 单测里钉住这些数
 * —— 动效调坏了肉眼只会觉得「手感怪」，看不出是哪一步错了，见 BounceSpringTest。
 */
internal object PullBounce {

    /**
     * 橡皮筋曲率。
     *
     * 0.5 是「一上手就跟着走、再拉就明显变硬」的分界：
     *   · 开场斜率 = CURVE_RATIO / MAX_OFFSET_PX，也就是说前 1px 手指换 0.5px 位移
     *     —— 再小（0.3 以下）头 20dp 内内容几乎不动，又变回「拉了没反应」；
     *   · 拉到阈值（64dp）时位移只有上限的 25%，拉到头（96dp 以上）也只有 40% 左右，
     *     手感上「还有余量」，不会一拉就顶死；
     *   · 再大（0.6 以上）接近线性，回弹时落差太大、像直接平移动画。
     * 0.45~0.55 之间都成立，取中值 0.5 与 LiquidButton 的按压曲率同档。
     */
    const val CURVE_RATIO = 0.50f

    /** 跟手位移的上限（dp）。橡皮筋只会渐近它，永远拉不到。 */
    const val MAX_OFFSET_DP = 96f

    /** 刷新时的停靠位 = 上限的这个比例；气泡和内容位移靠它对齐。 */
    const val RESTING_RATIO = 0.60f

    /** 回弹：与 LiquidButton 的抬手段同档（0.30s 收完，留一点 0.25 的弹性）。 */
    const val SPRING_RESPONSE = 0.30f
    const val SPRING_BOUNCE = 0.25f

    /**
     * 收尾阈值（px）。
     *
     * LiquidSpring 的默认值 5e-4 是给「缩放倍数」用的尺度，换算成 px 只有 0.0004px，
     * 等于要求把尾巴上几十帧完全看不见的位移也走完 —— 动画循环白转。这里给 0.05px，
     * 肉眼早已分辨不出，却能早十几帧摘掉 postOnAnimation。
     */
    const val SPRING_MIN_VISIBLE = 0.05f

    /** 兜底帧数：弹簧理论上一定收敛，但动画循环不能有「万一不收敛」的活口。 */
    const val MAX_ANIM_FRAMES = 240

    private const val NOMINAL_FRAME_SECONDS = 1f / 60f
    private const val MAX_FRAME_SECONDS = 0.032f
    private const val MIN_FRAME_SECONDS = 1f / 240f

    /**
     * 手指在边缘外拉了 [pullPx]，内容应该画到哪儿。
     *
     * @param direction +1 = 顶部往下拉（内容往下走），-1 = 底部往上拉（内容往上走），0 = 没在拉。
     * @return 视觉位移，与手指方向同号，绝对值 < [maxOffsetPx]。
     */
    fun visualOffset(pullPx: Float, direction: Int, maxOffsetPx: Float): Float {
        if (direction == 0 || pullPx <= 0f || maxOffsetPx <= 0f) return 0f
        val band = FluidMotion.rubberBand(pullPx, CURVE_RATIO, maxOffsetPx)
        return if (direction > 0) band else -band
    }

    /** 刷新中的停靠位移（始终为正：顶部刷新往下、底部刷新往上，取绝对值再乘方向由调用方做）。 */
    fun restingOffset(maxOffsetPx: Float): Float = maxOffsetPx * RESTING_RATIO

    /**
     * 视觉位移 → 等效手指距离（橡皮筋的反函数）。
     *
     * 用在「回弹还没停就被新手势接住」的瞬间：从当前位移反推手指应该已经走了多远，
     * 把拉动的起点挪过去，位移就不会先跳回 0 再重新长出来。
     */
    fun equivalentPull(offsetPx: Float, maxOffsetPx: Float): Float {
        val a = abs(offsetPx)
        if (a <= 0f || maxOffsetPx <= 0f) return 0f
        return abs(FluidMotion.rubberBandInverse(a, CURVE_RATIO, maxOffsetPx))
    }

    /**
     * 两帧之间的秒数，夹在 [1/240, 0.032]。
     *
     * 解析解对 dt 不敏感（不会像欧拉积分那样炸），但一帧跳太远会「瞬移」得像掉帧；
     * 首帧 / 时钟回拨 / 卡顿都要挡住。
     */
    fun frameSeconds(nowMs: Long, lastMs: Long): Float {
        if (lastMs <= 0L || nowMs <= lastMs) return NOMINAL_FRAME_SECONDS
        return ((nowMs - lastMs) / 1000f).coerceIn(MIN_FRAME_SECONDS, MAX_FRAME_SECONDS)
    }
}

class RefreshScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** 拉过这个距离（dp）松手才算数。松手判定用的仍是**原始手指距离**，不是视觉位移。 */
    private val threshold = 64f * resources.displayMetrics.density

    private val maxOffset = PullBounce.MAX_OFFSET_DP * resources.displayMetrics.density

    /** 正在刷新时不再接受新手势。 */
    private var refreshing = false

    private var downY = 0f

    /** 这一次「边缘拉动」的起点 y —— 不是手势的起点，见 ACTION_MOVE 里的说明。 */
    private var anchorY = 0f

    private var pull = 0f

    /** 当前这次手势的方向：+1 = 向下拉（顶部刷新），-1 = 向上拉（底部刷新），0 = 没在边缘。 */
    private var direction = 0

    /** 已经画到内容上的位移（px）：正 = 内容往下，负 = 内容往上。 */
    private var offset = 0f

    private val spring = LiquidSpring(
        PullBounce.SPRING_RESPONSE,
        PullBounce.SPRING_BOUNCE,
        PullBounce.SPRING_MIN_VISIBLE
    )
    private var springTarget = 0f
    private var springRunning = false
    private var lastFrameMs = 0L
    private var frameCount = 0

    private val springFrame = object : Runnable {
        override fun run() {
            if (!springRunning) return
            val now = System.nanoTime() / 1_000_000L
            val dt = PullBounce.frameSeconds(now, lastFrameMs)
            lastFrameMs = now
            frameCount++

            val stillMoving = spring.step(springTarget, dt)
            applyOffset(spring.value)
            if (stillMoving && frameCount < PullBounce.MAX_ANIM_FRAMES) {
                // 只在真的还在动时续帧：停住就摘掉，不空转
                postOnAnimation(this)
                return
            }
            // 收干（或兜底帧数到了）：钉死在目标上，然后停
            spring.set(springTarget)
            applyOffset(springTarget)
            springRunning = false
            removeCallbacks(this)
        }
    }

    var onRefresh: (() -> Unit)? = null

    /** 拉动过程中的进度 0..1，用于驱动指示器。progress = 原始手指距离 / threshold，可以超过 1。 */
    var onPull: ((progress: Float, fromTop: Boolean) -> Unit)? = null

    /** 松手：触发刷新或取消。 */
    var onRelease: ((triggered: Boolean, fromTop: Boolean) -> Unit)? = null

    fun setRefreshing(value: Boolean) {
        refreshing = value
        pull = 0f
        direction = 0
        if (value) {
            // 已经拉出来的位移收到「刷新中」的停靠位，方向跟当前位移走
            // （顶部刷新往下停、底部刷新往上停，不能都写成正的）。
            // 本来就没有位移（比如进页面时程序自己调 loadStats() 刷新）时不动内容 ——
            // 主动刷新不该无故把整页顶下去 58dp。
            if (offset != 0f) animateTo(restingTarget(offset > 0f))
        } else {
            animateTo(0f)
        }
    }

    private fun canScrollUp(): Boolean = scrollY <= 0

    private fun canScrollDown(): Boolean {
        val child = getChildAt(0) ?: return true
        return scrollY + height >= child.height - 1
    }

    /**
     * 位移写在**内容 child** 上，不是写在这个 ScrollView 自己身上。
     *
     * 理由：
     *   · ScrollView 的滚动模型只认 scrollY / child 的 top 与高度；translationY 是绘制期
     *     （同时参与命中测试矩阵）的变换，不动 scrollY，也不动内置 Scroller 的惯性 ——
     *     把整块 View 搬走则要求父容器 clipChildren 配合，换一处布局就会漏底；
     *   · 挂在 child 上，ScrollView 自己的边光晕、滚动条、clipToPadding 的裁切区都留在原地，
     *     不会跟着一起跳（页面里 clipToPadding=false + paddingBottom，光晕就画在那圈 padding 上）；
     *   · 挂在 child 上也让「点按」跟着视觉位置走：矩阵是同一套，按钮挪到哪就能点到哪。
     */
    private fun applyOffset(px: Float) {
        offset = px
        content()?.translationY = px
    }

    private fun content(): View? = if (childCount > 0) getChildAt(0) else null

    /**
     * 「刷新中」的停靠位移：顶部刷新停在 +60%（内容往下让出气泡），
     * 底部刷新停在 -60%（内容往上让），符号必须跟着方向走 ——
     * 否则底部刷新松手时内容会反向窜一下再弹回去。
     */
    private fun restingTarget(fromTop: Boolean): Float {
        val r = PullBounce.restingOffset(maxOffset)
        return if (fromTop) r else -r
    }

    /** 让位移平滑地走到 [target]（0 = 归位，restingOffset = 刷新停靠位）。 */
    private fun animateTo(target: Float) {
        if (springRunning && target == springTarget) return
        springTarget = target
        if (springRunning) return // 已经在跑：只换目标，保留速度，别让曲线断一下
        if (abs(offset - target) < 1e-3f) {
            spring.set(target)
            applyOffset(target)
            return
        }
        spring.set(offset)
        springRunning = true
        lastFrameMs = 0L
        frameCount = 0
        postOnAnimation(springFrame)
    }

    /** 手指接管：回弹立刻让位，否则弹簧和手指会同时往 translationY 上写。 */
    private fun takeOver() {
        if (!springRunning) return
        springRunning = false
        removeCallbacks(springFrame)
        spring.set(offset)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downY = ev.y
            anchorY = ev.y
            pull = 0f
            direction = 0
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing) return super.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                anchorY = ev.y
                pull = 0f
                direction = 0
                return super.onTouchEvent(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                if (direction == 0) {
                    val dy = ev.y - downY
                    // 只有「已经在边上的那个方向」才可能变成刷新手势
                    val fromTop = canScrollUp() && dy > 0f
                    val fromBottom = canScrollDown() && dy < 0f
                    if (!fromTop && !fromBottom) return super.onTouchEvent(ev)
                    direction = if (fromTop) 1 else -1

                    // 拉动距离从「摸到边的那一刻」开始量，而不是从 ACTION_DOWN：
                    // 用户常常是一路划到顶再继续拉，拿整个手势的 dy 当拉动距离的话，
                    // 到边的一瞬间 pull 就已经超过阈值了（旧实现的隐性缺陷）。
                    val carried =
                        if (offset * direction > 0f) PullBounce.equivalentPull(offset, maxOffset) else 0f
                    anchorY = ev.y - direction * carried
                    takeOver()
                }

                val along = (ev.y - anchorY) * direction
                val stillAtEdge = if (direction > 0) canScrollUp() else canScrollDown()
                if (along > 0f && stillAtEdge) {
                    pull = along
                    applyOffset(PullBounce.visualOffset(pull, direction, maxOffset))
                    onPull?.invoke((pull / threshold).coerceIn(0f, 1.6f), direction > 0)
                    // 接管这次手势：不再往下传，避免系统光晕和内容一起动
                    return true
                }

                // 反手拉回去 / 已经离开边缘：这次拉动作废，位移交给弹簧收回
                val fromTop = direction > 0
                direction = 0
                pull = 0f
                onRelease?.invoke(false, fromTop)
                animateTo(0f)
                return super.onTouchEvent(ev)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (direction == 0) return super.onTouchEvent(ev)
                val fromTop = direction > 0
                val triggered = pull >= threshold && ev.actionMasked == MotionEvent.ACTION_UP
                direction = 0
                pull = 0f
                onRelease?.invoke(triggered, fromTop)
                if (triggered) {
                    refreshing = true
                    // 触发后先停在停靠位，**不**立刻归零：
                    // 气泡写着「刷新中…」而内容同时往回缩，是两处打架的状态。
                    animateTo(restingTarget(fromTop))
                    onRefresh?.invoke()
                } else {
                    animateTo(0f)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        springRunning = false
        removeCallbacks(springFrame)
        if (!refreshing) {
            spring.set(0f)
            applyOffset(0f)
        }
    }
}
