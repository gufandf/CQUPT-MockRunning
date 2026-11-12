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
 * 拉过 [thresholdDp] 松手就触发；不到阈值就弹回去（回弹交给 overscroll 自己表现）。
 *
 * 只在「已经在边上」时才接管手势，正常的滚动一行代码都没改。
 */
package com.whale.campusrun

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView
import kotlin.math.abs

class RefreshScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** 拉过这个距离（dp）松手才算数。 */
    private val threshold = 64f * resources.displayMetrics.density

    /** 正在刷新时不再接受新手势。 */
    private var refreshing = false

    private var downY = 0f
    private var pull = 0f

    /** 当前这次手势的方向：+1 = 向下拉（顶部刷新），-1 = 向上拉（底部刷新），0 = 没在边缘。 */
    private var direction = 0

    var onRefresh: (() -> Unit)? = null

    /** 拉动过程中的进度 0..1，用于驱动指示器。 */
    var onPull: ((progress: Float, fromTop: Boolean) -> Unit)? = null

    /** 松手：触发刷新或取消。 */
    var onRelease: ((triggered: Boolean, fromTop: Boolean) -> Unit)? = null

    fun setRefreshing(value: Boolean) {
        refreshing = value
        if (value) {
            pull = 0f
            direction = 0
        }
    }

    private fun canScrollUp(): Boolean = scrollY <= 0

    private fun canScrollDown(): Boolean {
        val child = getChildAt(0) ?: return true
        return scrollY + height >= child.height - 1
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                pull = 0f
                direction = 0
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing) return super.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                pull = 0f
                direction = 0
                return super.onTouchEvent(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                // 只有「已经在边上的那个方向」才可能变成刷新手势
                val fromTop = canScrollUp() && dy > 0f
                val fromBottom = canScrollDown() && dy < 0f
                if (fromTop || fromBottom) {
                    direction = if (fromTop) 1 else -1
                    pull = abs(dy)
                    onPull?.invoke((pull / threshold).coerceIn(0f, 1.6f), fromTop)
                    // 接管这次手势：不再往下传，避免系统光晕和内容一起动
                    return true
                }
                // 离开边缘（用户往回滚）就取消这次拉伸手势
                if (direction != 0 && ((direction > 0 && !canScrollUp()) || (direction < 0 && !canScrollDown()))) {
                    direction = 0
                    pull = 0f
                    onRelease?.invoke(false, true)
                }
                return super.onTouchEvent(ev)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (direction != 0) {
                    val fromTop = direction > 0
                    val triggered = pull >= threshold && ev.actionMasked == MotionEvent.ACTION_UP
                    direction = 0
                    pull = 0f
                    onRelease?.invoke(triggered, fromTop)
                    if (triggered) {
                        refreshing = true
                        onRefresh?.invoke()
                    }
                    return true
                }
                return super.onTouchEvent(ev)
            }
        }
        return super.onTouchEvent(ev)
    }
}
