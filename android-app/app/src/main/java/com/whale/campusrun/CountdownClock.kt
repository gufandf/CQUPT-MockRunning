/*
 * 倒计时状态机（纯 JVM，可单测）。
 *
 * 为什么从 CountdownView 里抽出来：v1.12.0 之前这套逻辑写在 View 的 ticker 里，
 * 有一个"永远不会回调"的 bug ——
 *
 *     if (fadeTarget > 0f) {                 // 只有还在倒数时才进
 *         remaining -= dt
 *         if (remaining <= 0f) {
 *             fadeTarget = 0f                // ← 这一行把 fadeTarget 关掉了
 *             if (fade <= 0.001f) {          // ← 但同一帧 fade 还没降下去
 *                 onFinished()               //    于是这一帧不触发
 *             }
 *         }
 *     }
 *     // 下一帧：fadeTarget 已经是 0，整个 if 块被跳过 → onFinished 永远不触发
 *
 * 表现就是：倒计时数字淡出、遮罩消失，但跑步没开始，按钮还停在【取消】。
 * 逻辑一旦埋在 View 的动画回调里，这种"状态漏了一次转移"的问题只能靠肉眼看，
 * 抽成纯函数之后可以用固定时间步把它钉死。
 */
package com.whale.campusrun

/**
 * 倒计时推进器。
 *
 * 时间轴分三段：
 *   running  —— 数字还在走（remaining > 0）
 *   fading   —— 数字走完了，遮罩正在淡出
 *   finished —— 淡出结束，**且回调只发一次**
 *
 * 取消（[cancel]）走另一条路：不触发 `finished`，只淡出。
 */
class CountdownClock(
    /** 总时长（秒）。 */
    val total: Float = CountdownView.SECONDS.toFloat(),
    /** 淡入淡出需要多久（秒）。 */
    private val fadeSeconds: Float = 0.18f
) {

    /** 剩余秒数（>= 0）。 */
    var remaining: Float = 0f
        private set

    /** 遮罩 / 数字的可见度 0..1。 */
    var fade: Float = 0f
        private set

    private var fadeTarget = 0f
    private var shown = false
    private var cancelled = false
    private var finishedFired = false

    /** true = 还在倒数或淡出（界面上这层还存在）。 */
    val visible: Boolean get() = shown

    /** true = 已经被取消（不会再有 finished）。 */
    val isCancelled: Boolean get() = cancelled

    /** 开始（或重新开始）一轮。 */
    fun start() {
        remaining = total
        fade = 0f
        fadeTarget = 1f
        shown = true
        cancelled = false
        finishedFired = false
    }

    /** 取消：只淡出，不触发 finished。 */
    fun cancel() {
        if (cancelled) return
        cancelled = true
        fadeTarget = 0f
        // 已经淡完 / 从未开始：直接收掉，否则会停在半透明状态
        if (!shown || fade <= 0.001f) shown = false
    }

    /**
     * 推进一帧。
     *
     * @param dt 距上一帧的秒数（调用方负责夹上限，避免切后台回来一口气吃光）
     * @return true 表示**这一帧**刚走到"淡出结束" —— 调用方可以拿它当开跑信号。
     *         整个生命周期只返回一次 true。
     */
    fun step(dt: Float): Boolean {
        if (!shown) return false

        // 淡入淡出：线性跟上目标
        val fadeStep = if (fadeSeconds > 0f) dt / fadeSeconds else 1f
        fade = if (fadeTarget > fade) (fade + fadeStep).coerceAtMost(fadeTarget)
        else (fade - fadeStep).coerceAtLeast(fadeTarget.coerceAtLeast(0f))

        // 只有"还在倒数"时才推进秒数；淡出期间数字停住，免得取消后还在跳
        if (!cancelled && fadeTarget > 0f) {
            remaining = (remaining - dt).coerceAtLeast(0f)
            if (remaining <= 0f) fadeTarget = 0f
        }

        // 淡出结束 → 收掉这一层。注意这段判断在 if 之外：
        // 上一版把它写在 `if (fadeTarget > 0f)` 里，而上一行刚刚把 fadeTarget 置 0，
        // 于是这一帧进不去、下一帧也进不去，finished 永远发不出来。
        if (fadeTarget <= 0f && fade <= 0.001f) {
            shown = false
            if (!cancelled && !finishedFired) {
                finishedFired = true
                return true
            }
        }
        return false
    }
}
