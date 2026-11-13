/*
 * 倒计时状态机的单元测试。
 *
 * 重点是最后那条：**倒计时走完必须发出开跑信号，且只发一次**。
 * v1.12.0 之前这里有个 bug —— 数字淡出、遮罩消失，但 onFinished 永远不触发，
 * 于是倒计时结束后跑步没开始、按钮还停在【取消】。原因见 CountdownClock 顶部注释。
 */
package com.whale.campusrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownClockTest {

    /** 逐帧推进到结束，返回 (发出开跑信号的次数, 总用时秒)。 */
    private fun runToEnd(clock: CountdownClock, frame: Float = 1f / 60f,
                         maxSeconds: Float = 30f): Pair<Int, Float> {
        var fires = 0
        var t = 0f
        while (t < maxSeconds) {
            if (clock.step(frame)) fires++
            t += frame
            if (!clock.visible) break
        }
        return fires to t
    }

    @Test
    fun `倒计时走完必须发出一次开跑信号`() {
        val clock = CountdownClock(total = 3f)
        clock.start()
        val (fires, elapsed) = runToEnd(clock)
        assertEquals("开跑信号应当恰好发出 1 次（上一版的 bug 是 0 次）", 1, fires)
        assertFalse("结束后这一层应当收掉", clock.visible)
        // 3 秒倒数 + 0.18 秒淡出，允许几帧余量
        assertTrue("总用时 ${elapsed}s 不合理，应当在 3.0~3.6s", elapsed in 3.0f..3.6f)
    }

    @Test
    fun `信号只发一次，继续推进也不会重复`() {
        val clock = CountdownClock(total = 3f)
        clock.start()
        var fires = 0
        repeat(600) { if (clock.step(1f / 60f)) fires++ }
        assertEquals(1, fires)
    }

    @Test
    fun `取消之后不再发开跑信号`() {
        val clock = CountdownClock(total = 3f)
        clock.start()
        repeat(30) { clock.step(1f / 60f) }      // 走 0.5 秒
        clock.cancel()
        val (fires, _) = runToEnd(clock)
        assertEquals("取消后不该再开跑", 0, fires)
        assertTrue("取消状态要能被读到", clock.isCancelled)
        assertFalse(clock.visible)
    }

    @Test
    fun `剩余秒数随时间单调减少到零`() {
        val clock = CountdownClock(total = 3f)
        clock.start()
        var last = clock.remaining
        assertEquals(3f, last, 1e-3f)
        repeat(180) {
            clock.step(1f / 60f)
            assertTrue("剩余秒数不能回升：$last → ${clock.remaining}", clock.remaining <= last + 1e-6f)
            last = clock.remaining
        }
        assertEquals("走满 3 秒后应当归零", 0f, clock.remaining, 1e-3f)
    }

    /**
     * 大时间步也不能把信号吞掉。
     *
     * 真机上 ticker 的 dt 有上限（切后台回来不会一口气吃掉 3 秒），但
     * 万一某一帧特别长（主线程卡住），状态机也必须照样把信号发出来。
     */
    @Test
    fun `单帧时间超长也能正常结束`() {
        val clock = CountdownClock(total = 3f)
        clock.start()
        var fires = 0
        // 一帧直接跨过 3 秒
        if (clock.step(5f)) fires++
        // 再推一帧完成淡出
        if (clock.step(0.5f)) fires++
        assertEquals(1, fires)
        assertFalse(clock.visible)
    }

    @Test
    fun `未开始时不发信号`() {
        val clock = CountdownClock(total = 3f)
        repeat(120) { assertFalse(clock.step(1f / 60f)) }
        assertFalse(clock.visible)
    }
}
