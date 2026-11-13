/*
 * 停表时钟：把「真实经过的时间」翻译成「跑步经过的时间」。
 *
 * 为什么单独抽一个类：
 *   暂停的算术有三处要用（每批上传前等待、批次间等待、界面进度），
 *   而且它最容易被「顺手写错」—— 少扣一次累计暂停，配速就被拉慢，
 *   整条记录判无效，但界面上一切正常，看不出问题。
 *   抽成纯 JVM 类就能脱离线程、网络、Android API 直接钉住它。
 *
 * 语义（「停表」，和小程序一致）：
 *   · 暂停期间 `runElapsedMs` 冻结在暂停那一刻，不再增长；
 *   · 恢复时才把这段时长累加进 `pausedAccumMs`；
 *   · 轨迹点的发送目标时刻 = 起点 + 秒偏移 + 累计暂停 —— 也就是**暂停不占时间轴**。
 *
 * 参数都是调用方传入的毫秒时间戳（`SystemClock.elapsedRealtime()` 口径），
 * 类本身不读时钟，这样测试可以任意摆布时间。
 */
package com.whale.campusrun

internal class PauseClock {

    /** 累计暂停时长（毫秒）。每恢复一次加一次。 */
    @Volatile
    var pausedAccumMs: Long = 0L
        private set

    /** 当前这次暂停从哪个时刻开始；没在暂停时是 0。 */
    @Volatile
    private var pausedAtMs: Long = 0L

    val paused: Boolean
        get() = pausedAtMs > 0L

    /**
     * 开始暂停。
     *
     * @return 是否真的进入了暂停（已经在暂停时返回 false，且不会重置起点 ——
     *         否则重复调用会把已经暂停的时长算丢）。
     */
    fun pause(nowMs: Long): Boolean {
        if (paused) return false
        pausedAtMs = nowMs
        return true
    }

    /**
     * 结束暂停。
     *
     * @return 本次暂停的时长（毫秒）；本来就没在暂停时返回 0。
     */
    fun resume(nowMs: Long): Long {
        if (!paused) return 0L
        val dur = (nowMs - pausedAtMs).coerceAtLeast(0L)
        pausedAccumMs += dur
        pausedAtMs = 0L
        return dur
    }

    /** 取消 / 中止：丢掉「正在暂停」的状态，但保留已累计的时长（这一轮已经过去了）。 */
    fun abort() {
        pausedAtMs = 0L
    }

    /**
     * 跑步已经进行了多久（毫秒）。
     *
     * 正在暂停时返回「暂停那一刻已经跑了多久」—— 这就是停表。
     * 结果永远非负。
     */
    fun runElapsedMs(startMs: Long, nowMs: Long): Long {
        val cut = if (paused) pausedAtMs else nowMs
        return (cut - startMs - pausedAccumMs).coerceAtLeast(0L)
    }

    /**
     * 第 [secOffsetSec] 秒那个点应当在什么时候被发出去（毫秒，elapsedRealtime 口径）。
     *
     * 加上 `pausedAccumMs` 就是「暂停占用的真实时间不算进跑步时间轴」这件事的落点：
     * 暂停 8 分钟之后，第 30 秒的点仍然在「开跑 30 秒 + 8 分钟暂停」那一刻发，
     * 而不是在「开跑 14 分 30 秒」发。
     */
    fun targetForPointMs(startMs: Long, secOffsetSec: Int): Long =
        startMs + secOffsetSec * 1000L + pausedAccumMs
}
