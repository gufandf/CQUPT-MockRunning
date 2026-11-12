/*
 * 液体按钮弹簧的数值测试。
 *
 * 这类动效很难靠眼睛判断好坏：调「坏」了往往只是回弹软一点、或者抖得久一点，
 * 看着都「有在动」。所以这里用数值把三条性质钉住：
 *   ① 会回弹      —— 从 1 出发一定穿过 0（否则只是淡出，不是液体）
 *   ② 会收敛      —— 有限时间内停在 0 附近（不会永远抖）
 *   ③ 不过度发散  —— 最大过冲有上界（真的会「甩出去」就是参数废了）
 */
package com.whale.campusrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidSpringTest {

    private val dt = 1f / 60f

    /** 按 60fps 推进，返回轨迹。超过 maxFrames 就停，防止死循环。 */
    private fun run(
        spring: LiquidSpring,
        target: Float,
        maxFrames: Int = 600
    ): List<Float> {
        val trace = ArrayList<Float>(maxFrames)
        var frames = 0
        while (frames < maxFrames) {
            val moving = spring.step(target, dt)
            trace.add(spring.value)
            frames++
            if (!moving) break
        }
        return trace
    }

    @Test
    fun `松手后一定会过冲穿过零点`() {
        val s = LiquidSpring()
        s.set(1f)
        val trace = run(s, 0f)

        // 至少要有一帧是负的 —— 那就是「荡回去再弹出来」的那一下
        assertTrue(
            "欠阻尼弹簧必须过冲，实际最小 ${trace.minOrNull()}",
            trace.minOrNull()!! < 0f
        )
    }

    @Test
    fun `最终会停在零点不再抖动`() {
        val s = LiquidSpring()
        s.set(1f)
        run(s, 0f)

        assertEquals(0f, s.value, 0.1f)
        assertEquals(0f, s.velocity, 1.0f)
    }

    @Test
    fun `收敛时间在两秒以内`() {
        val s = LiquidSpring()
        s.set(1f)
        val trace = run(s, 0f)

        // 60fps → 120 帧 = 2 秒
        assertTrue("回弹应在 2 秒内收住，实际 ${trace.size} 帧", trace.size <= 120)
    }

    @Test
    fun `过冲幅度不会超过起始值`() {
        val s = LiquidSpring()
        s.set(1f)
        val trace = run(s, 0f)

        // 回到反方向超过 100% 就是「甩飞了」，视觉上会像抽屉脱轨
        assertTrue("过冲过大：${trace.minOrNull()}", trace.minOrNull()!! > -0.6f)
    }

    // ── 以下是按 ColorOS 的 response / bounce 参数化补的断言 ──

    @Test
    fun `bounce 为 0 时不过冲（临界阻尼）`() {
        // ColorOS 里「按下」用的是 bounce 0 / response 0.25：
        // 干脆地压下去，不能先弹出来一下
        val s = LiquidSpring(response = 0.25f, bounce = 0f)
        s.set(1f)
        val trace = run(s, 0.85f)
        assertTrue("临界阻尼不该过冲：min=${trace.minOrNull()}",
            trace.minOrNull()!! >= 0.85f - 1e-3f)
    }

    @Test
    fun `bounce 越大过冲越明显`() {
        fun overshoot(bounce: Float): Float {
            val s = LiquidSpring(response = 0.42f, bounce = bounce)
            s.set(1f)
            val trace = run(s, 0f)
            return -trace.minOrNull()!!
        }
        val low = overshoot(0.2f)
        val high = overshoot(0.65f)
        assertTrue("bounce 0.65 的过冲 ($high) 应大于 0.2 ($low)", high > low)
    }

    @Test
    fun `response 越小收敛越快`() {
        fun frames(response: Float): Int {
            val s = LiquidSpring(response = response, bounce = 0.3f)
            s.set(1f)
            return run(s, 0f).size
        }
        assertTrue("response 0.2 应比 0.8 收得更快", frames(0.2f) < frames(0.8f))
    }

    @Test
    fun `抬手弹簧的时长落在 0_6 到 1_4 秒之间`() {
        // 手感标定：太快像被弹回去，太慢像卡住。
        // ColorOS 的 0.42s 在它 90dp 的小按键上刚好，我们的主按钮宽 330dp，
        // 放到 0.62s（约 0.8~1.0 秒收尾）才不像「弹开」。
        val s = LiquidSpring(response = 0.58f, bounce = 0.5f)
        s.set(1f)
        val seconds = run(s, 0f).size * dt
        assertTrue("回弹太快：${seconds}s", seconds > 0.6f)
        assertTrue("回弹太慢：${seconds}s", seconds < 1.4f)
    }

    @Test
    fun `大 dt 不会发散`() {
        // 掉帧到 20fps 时调用方会把 dt 夹到 32ms，这里直接把最坏情况喂进去
        val s = LiquidSpring()
        s.set(1f)
        repeat(120) { s.step(0f, 0.032f) }

        assertTrue("大 dt 下发散了：${s.value}", kotlin.math.abs(s.value) < 0.2f)
    }
}
