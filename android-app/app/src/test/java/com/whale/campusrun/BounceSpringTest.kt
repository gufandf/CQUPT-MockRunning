/*
 * 边缘拉伸 / 回弹的数值测试。
 *
 * 跟手位移和回弹是同一类「肉眼只能说怪、说不出哪儿错」的东西：
 * 曲线选错只是「拉起来轻飘飘」，参数选错只是「弹得软一点」，看着都「有在动」。
 * 所以把四条性质用数值钉住：
 *   ① 跟手有阻尼      —— 视觉位移永远小于手指距离（橡皮筋不是线性跟随）
 *   ② 跟手有界        —— 拉再远也到不了上限（内容不会被扯出去）
 *   ③ 映射可逆        —— 位移 → 等效手指距离 → 位移，能闭合（接住回弹用）
 *   ④ 回弹会收敛且不过头 —— 有限帧数内精确归零，过冲幅度有上界
 */
package com.whale.campusrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BounceSpringTest {

    private val max = PullBounce.MAX_OFFSET_DP // 96f，dp 与 px 在本测试里都只是「长度单位」

    // ─────────────────────────────────────── ① 跟手有阻尼

    @Test
    fun `视觉位移始终小于手指距离`() {
        var raw = 1f
        while (raw <= 3000f) {
            val visual = abs(PullBounce.visualOffset(raw, 1, max))
            assertTrue("raw=$raw 视觉位移($visual)不该追上手指", visual < raw)
            raw = raw * 1.3f + 1f
        }
    }

    @Test
    fun `跟手映射单调递增`() {
        var prev = -1f
        var raw = 0f
        while (raw <= 3000f) {
            val visual = PullBounce.visualOffset(raw, 1, max)
            assertTrue("raw=$raw 应单调", visual > prev)
            prev = visual
            raw += 3f
        }
    }

    @Test
    fun `拉到阈值时的位移落在可感知但有余量的区间`() {
        // 阈值是 64dp，跟手位移应该「看得出来」但明显没顶死 ——
        // 太小又变回「拉了没反应」，太大就一拉到底。
        val thresholdPx = 64f
        val v = PullBounce.visualOffset(thresholdPx, 1, max)
        assertTrue("阈值处位移过小：$v", v > thresholdPx * 0.2f)
        assertTrue("阈值处位移过大：$v", v < max * 0.5f)
    }

    // ─────────────────────────────────────── ② 有界 / 方向

    @Test
    fun `位移永不到达上限`() {
        val visual = PullBounce.visualOffset(1_000_000f, 1, max)
        assertTrue("视觉位移 $visual 应严格小于上限 $max", visual < max)
        assertTrue("拉这么远应该已经很接近上限了：$visual", visual > max * 0.9f)
    }

    @Test
    fun `位移跟随方向且零拉动归零`() {
        assertEquals(0f, PullBounce.visualOffset(0f, 1, max), 1e-6f)
        assertEquals(0f, PullBounce.visualOffset(120f, 0, max), 1e-6f)
        assertEquals(0f, PullBounce.visualOffset(120f, 1, 0f), 1e-6f)

        val down = PullBounce.visualOffset(120f, 1, max)
        val up = PullBounce.visualOffset(120f, -1, max)
        assertTrue("顶部下拉内容应往下走：$down", down > 0f)
        assertEquals("上下应对称", -down, up, 1e-5f)
    }

    @Test
    fun `刷新停靠位在六成上限且小于任何满拉位移`() {
        assertEquals(max * 0.60f, PullBounce.restingOffset(max), 1e-4f)
        assertTrue(
            "停靠位应超过阈值处的跟手位移，触发瞬间内容才有一记「弹到位」而不是往回缩",
            PullBounce.restingOffset(max) > PullBounce.visualOffset(64f, 1, max)
        )
        assertTrue("停靠位不该超过上限", PullBounce.restingOffset(max) < max)
    }

    // ─────────────────────────────────────── ③ 映射可逆

    @Test
    fun `位移反推手指距离能闭合`() {
        var raw = 2f
        while (raw <= 2000f) {
            val visual = PullBounce.visualOffset(raw, 1, max)
            val back = PullBounce.visualOffset(
                PullBounce.equivalentPull(visual, max), 1, max
            )
            assertEquals("raw=$raw 反推后应回到同一位移", visual, back, 1e-2f)
            raw = raw * 1.7f + 2f
        }
    }

    // ─────────────────────────────────────── ④ 回弹

    /** 按 60fps 推进到停，返回轨迹（含起点）。 */
    private fun settle(from: Float, target: Float, maxFrames: Int = 600): List<Float> {
        val s = LiquidSpring(
            PullBounce.SPRING_RESPONSE,
            PullBounce.SPRING_BOUNCE,
            PullBounce.SPRING_MIN_VISIBLE
        )
        s.set(from)
        val trace = ArrayList<Float>(maxFrames)
        trace.add(s.value)
        var frames = 0
        while (frames < maxFrames) {
            val moving = s.step(target, 1f / 60f)
            trace.add(s.value)
            frames++
            if (!moving) break
        }
        return trace
    }

    @Test
    fun `回弹会在有限帧内精确归零`() {
        val trace = settle(PullBounce.restingOffset(max), 0f)
        assertEquals("最后必须精确落在 0", 0f, trace.last(), 0f)
        assertTrue("回弹帧数过多（${trace.size}），会显得拖沓", trace.size < 60)
        assertTrue("回弹帧数过少（${trace.size}），会像直接切走", trace.size > 6)
    }

    @Test
    fun `回弹会轻微过冲但不会甩出去`() {
        val from = PullBounce.restingOffset(max)
        val trace = settle(from, 0f)
        val overshoot = trace.minOrNull() ?: 0f
        assertTrue("应过冲到零以下（液体感），实际最小 $overshoot", overshoot < 0f)
        assertTrue("过冲过大，像弹飞了：$overshoot", abs(overshoot) < from * 0.10f)
    }

    @Test
    fun `停靠位收敛快于整个动画预算`() {
        // MAX_ANIM_FRAMES 是兜底：正常参数下绝不该撞到它。
        val trace = settle(PullBounce.restingOffset(max), 0f)
        assertNotEquals("撞到兜底帧数说明参数不收敛", PullBounce.MAX_ANIM_FRAMES + 1, trace.size)
        assertTrue(trace.size < PullBounce.MAX_ANIM_FRAMES / 2)
    }

    @Test
    fun `帧间隔被夹在合理区间`() {
        val nominal = PullBounce.frameSeconds(1000L, 0L)
        assertEquals(1f / 60f, nominal, 1e-4f)

        // 卡顿 / 后台挂起：不能一帧跳太远
        assertTrue(PullBounce.frameSeconds(5000L, 1000L) <= 0.032f)
        // 时钟回拨 / 同毫秒两帧：不能给出 0 或负数
        assertTrue(PullBounce.frameSeconds(1000L, 1000L) > 0f)
        assertTrue(PullBounce.frameSeconds(900L, 1000L) > 0f)
        assertTrue(PullBounce.frameSeconds(1001L, 1000L) >= 1f / 240f)
    }

    @Test
    fun `曲率取在中档且能产生阻尼`() {
        assertTrue("curveRatio 应在 0.45~0.55 之间", PullBounce.CURVE_RATIO in 0.45f..0.55f)
        // 曲率 0 会退化成「完全不动」，这里必须是真的有量
        assertTrue(PullBounce.visualOffset(100f, 1, max) > 0f)
    }
}
