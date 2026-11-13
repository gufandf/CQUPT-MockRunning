/*
 * 液体形变数学的数值测试。
 *
 * 这些公式是从 ColorOS 计算器里逐条抄下来的（见 refs/REA-CALCULATOR-FLUID.md），
 * 抄错了单看界面很难发现 —— 按钮还是会动，只是「不像液体」。
 * 所以把每条性质用数值钉住：
 *   ① 橡皮筋单调、有界、过原点
 *   ② 面积守恒：单轴拉伸时 sx·sy == 1
 *   ③ 尺寸衰减：大按钮永远拉不过小按钮，且不会跌破 1.03
 *   ④ 按压：宽胶囊比圆按钮压得轻
 *   ⑤ 重力弧：端点必须精确落在锚点 / 终点
 */
package com.whale.campusrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FluidMotionTest {

    // ─────────────────────────────────────── ① 橡皮筋

    @Test
    fun `橡皮筋过原点且单调递增`() {
        assertEquals(0f, FluidMotion.rubberBand(0f, 0.05f, 100f), 1e-6f)

        var prev = -1f
        var x = 0f
        while (x <= 2000f) {
            val v = FluidMotion.rubberBand(x, 0.05f, 100f)
            assertTrue("在 x=$x 处不单调：$prev → $v", v > prev)
            prev = v
            x += 5f
        }
    }

    @Test
    fun `橡皮筋永远到不了上限`() {
        val max = 100f
        // 拖到 100 倍上限，视觉位移仍然严格小于 max
        val v = FluidMotion.rubberBand(10_000f, 0.05f, max)
        assertTrue("越界了：$v", v < max)
        assertTrue("几乎顶到上限才合理：$v", v > max * 0.8f)
    }

    @Test
    fun `橡皮筋符号与输入一致`() {
        assertTrue(FluidMotion.rubberBand(-50f, 0.05f, 100f) < 0f)
        assertTrue(FluidMotion.rubberBand(50f, 0.05f, 100f) > 0f)
    }

    @Test
    fun `橡皮筋反函数能还原原始位移`() {
        val curve = 0.55f
        val max = 150f
        for (raw in listOf(10f, 40f, 90f, 200f, 600f)) {
            val seen = FluidMotion.rubberBand(raw, curve, max)
            val back = FluidMotion.rubberBandInverse(seen, curve, max)
            assertEquals("raw=$raw", raw, back, raw * 0.02f + 1f)
        }
    }

    // ─────────────────────────────────────── ② 面积守恒

    @Test
    fun `单轴拉伸时两轴面积守恒`() {
        val f = FluidMotion.deformScaleFactors(80f, 0f, 150f, 0.55f, 1.2f)
        assertEquals("拉伸必须有面积守恒：sx·sy 应约等于 1", 1f, f.sx * f.sy, 1e-3f)
        assertTrue("横向拖动应该拉伸 x：${f.sx}", f.sx > 1f)
        assertTrue("横向拖动应该压缩 y：${f.sy}", f.sy < 1f)
    }

    @Test
    fun `纵向拖动拉伸 y 而不是 x`() {
        val f = FluidMotion.deformScaleFactors(0f, 80f, 150f, 0.55f, 1.2f)
        assertTrue("纵向拖动应该拉伸 y：${f.sy}", f.sy > 1f)
        assertTrue("纵向拖动应该压缩 x：${f.sx}", f.sx < 1f)
        assertEquals(1f, f.sx * f.sy, 1e-3f)
    }

    @Test
    fun `零位移不产生形变`() {
        val f = FluidMotion.deformScaleFactors(0f, 0f, 150f, 0.55f, 1.2f)
        assertEquals(1f, f.sx, 1e-6f)
        assertEquals(1f, f.sy, 1e-6f)
    }

    @Test
    fun `斜向拖动的混合仍然面积守恒`() {
        val f = FluidMotion.deformScaleFactors(60f, 30f, 150f, 0.55f, 1.2f)
        // log 域混合（几何平均）才能保住这条；算术平均会破坏它
        assertEquals("斜向混合破坏了面积守恒", 1f, f.sx * f.sy, 5e-3f)
    }

    @Test
    fun `形变幅度不会超过设定上限`() {
        // 拖到橡皮筋天花板，仍然不能超过 maxScale
        val f = FluidMotion.deformScaleFactors(100_000f, 0f, 150f, 0.55f, 1.2f)
        assertTrue("超过上限了：${f.sx}", f.sx <= 1.2f + 1e-4f)
    }

    // ─────────────────────────────────────── ②b 装进渲染框

    /** 一轴上的实际占用量：(s−1)·half + |t|，与 onDraw 的画法一一对应。 */
    private fun used(s: Float, t: Float, half: Float): Float =
        (((s - 1f) * half).coerceAtLeast(0f)) + abs(t)

    @Test
    fun `形变与位移叠加后仍装得进渲染框`() {
        // 极限情况：满形变 + 满位移同时出现（以前两笔各占一份 room，加一起就出框了）
        val half = 14f      // 56dp 图标按钮：room 6dp、本体 44dp → 半宽 14dp
        val budget = 3.5f   // room 扣掉描边和发光后剩下的预算
        for (s in listOf(1f, 1.05f, 1.2f, 1.5f)) {
            for (t in listOf(0f, 2f, 5.4f, 20f, -20f)) {
                val m = FluidMotion.fitMotion(s, s, t, t, half, half, budget)
                assertTrue(
                    "s=$s t=$t 出框：x 用了 ${used(m.sx, m.tx, half)}",
                    used(m.sx, m.tx, half) <= budget + 1e-3f
                )
                assertTrue(
                    "s=$s t=$t 出框：y 用了 ${used(m.sy, m.ty, half)}",
                    used(m.sy, m.ty, half) <= budget + 1e-3f
                )
            }
        }
    }

    @Test
    fun `超额时拉伸与位移按同一比例一起收`() {
        val half = 20f
        val budget = 4f
        // 拉伸占 4（(1.2−1)·20）、位移占 8，总需求 12 → k = 1/3
        val m = FluidMotion.fitMotion(1.2f, 1f, 8f, 0f, half, half, budget)
        assertEquals("位移应收到 1/3", 8f / 3f, m.tx, 0.05f)
        // 拉伸只剩 1/3 的增量：(1.2−1)·(1/3) = 0.0667 → sx ≈ 1.0667
        assertEquals("拉伸应同步收到 1/3", 1.0667f, m.sx, 0.005f)
        assertEquals("没超预算的轴不该被改", 1f, m.sy, 1e-6f)
    }

    @Test
    fun `没超预算时原样返回`() {
        val m = FluidMotion.fitMotion(1.05f, 0.98f, 1f, -2f, 30f, 30f, 10f)
        assertEquals(1.05f, m.sx, 1e-6f)
        assertEquals(0.98f, m.sy, 1e-6f)
        assertEquals(1f, m.tx, 1e-6f)
        assertEquals(-2f, m.ty, 1e-6f)
    }

    @Test
    fun `只压缩不拉伸时不占用预算`() {
        // 按压缩放（<1）是往里收，不该被预算罚掉；位移仍可全额使用
        val m = FluidMotion.fitMotion(0.955f, 0.955f, 3f, 0f, 30f, 30f, 5f)
        assertEquals(0.955f, m.sx, 1e-6f)
        assertEquals(3f, m.tx, 1e-6f)
    }

    @Test
    fun `退化的框尺寸不产生 NaN`() {
        val m = FluidMotion.fitMotion(1.2f, 1.2f, 50f, 50f, 0f, 0f, 5f)
        assertEquals(1f, m.sx, 1e-6f)
        assertEquals(0f, m.tx, 1e-6f)
    }

    @Test
    fun `按下拖满再松手的全过程都不出框`() {
        // 按 LiquidButton 的真实时序重放一遍：按下 → 拖到极限 → 松手回弹，
        // 每帧都过 fitMotion。弹簧会过冲，所以只夹目标值不够 ——
        // 这一条守的就是「过冲那几帧也不能顶出渲染框」。
        val halfW = 14f
        val halfH = 14f
        val budget = 3.2f
        val dt = 1f / 60f

        val deformX = LiquidSpring(0.15f, 0.15f).apply { set(1f) }
        val deformY = LiquidSpring(0.15f, 0.15f).apply { set(1f) }
        val rubberX = LiquidSpring(0.15f, 0.15f).apply { set(0f) }
        val rubberY = LiquidSpring(0.15f, 0.15f).apply { set(0f) }

        // 最大拉伸按 scaleWithK 算（56dp @3x = 168px 见方）
        val maxScale = FluidMotion.scaleWithK(168f, 168f, 1.2f)
        // 拖到橡皮筋几乎顶死
        val f = FluidMotion.deformScaleFactors(500f, 300f, 90f * 3f, 0.55f, maxScale)
        val target = FluidMotion.fitMotion(
            f.sx, f.sy,
            FluidMotion.rubberBand(500f, 0.05f, 28f * 3f),
            FluidMotion.rubberBand(300f, 0.05f, 28f * 3f),
            halfW, halfH, budget
        )

        fun assertFits(tag: String, sx: Float, sy: Float, tx: Float, ty: Float) {
            val m = FluidMotion.fitMotion(sx, sy, tx, ty, halfW, halfH, budget)
            assertTrue(
                "$tag x 出框：${used(m.sx, m.tx, halfW)} > $budget",
                used(m.sx, m.tx, halfW) <= budget + 1e-3f
            )
            assertTrue(
                "$tag y 出框：${used(m.sy, m.ty, halfH)} > $budget",
                used(m.sy, m.ty, halfH) <= budget + 1e-3f
            )
        }

        // 阶段一：拖到极限（弹簧追目标）
        repeat(40) {
            deformX.step(target.sx, dt)
            deformY.step(target.sy, dt)
            rubberX.step(target.tx, dt)
            rubberY.step(target.ty, dt)
            assertFits("拖到极限第 $it 帧", deformX.value, deformY.value, rubberX.value, rubberY.value)
        }

        // 阶段二：松手回弹（欠阻尼，会过冲）
        val bounce = 0.65f
        listOf(deformX, deformY, rubberX, rubberY).forEach { it.retune(0.58f, bounce) }
        repeat(120) {
            deformX.step(1f, dt)
            deformY.step(1f, dt)
            rubberX.step(0f, dt)
            rubberY.step(0f, dt)
            assertFits("回弹第 $it 帧", deformX.value, deformY.value, rubberX.value, rubberY.value)
        }
    }

    // ─────────────────────────────────────── ③ 尺寸衰减

    @Test
    fun `大按钮允许的拉伸小于小按钮`() {
        val small = FluidMotion.scaleWithK(48f, 48f, 1.2f)
        val wide = FluidMotion.scaleWithK(900f, 150f, 1.2f)
        assertTrue("小按钮应达上限：$small", abs(small - 1.2f) < 1e-3f)
        assertTrue("宽按钮应被衰减：$wide", wide < small)
    }

    @Test
    fun `再大也不会跌破下限 1_03`() {
        val huge = FluidMotion.scaleWithK(4000f, 1200f, 1.2f)
        assertTrue("跌破下限：$huge", huge >= 1.03f - 1e-4f)
    }

    // ─────────────────────────────────────── ④ 按压

    @Test
    fun `按压缩放在 0_4 到 1 之间`() {
        for ((w, h) in listOf(48f to 48f, 300f to 60f, 900f to 150f, 10f to 10f)) {
            val s = FluidMotion.pressScale(w, h, 1f)
            assertTrue("w=$w h=$h → $s", s in 0.4f..1f)
        }
    }

    @Test
    fun `按下只是微微收缩，最多 5%`() {
        // 0.85 那组是 ColorOS 给 90dp 小按键定的下限，套到整宽胶囊上会像塌陷。
        // 现在按下只负责"碰到了"的反馈，任何尺寸的实际收缩都不该超过 5%。
        val sizes = listOf(
            56f to 56f,      // 顶栏圆形图标钮
            240f to 56f,     // 弹窗里的胶囊
            328f to 92f,     // 首页【开始打卡】
            900f to 150f
        )
        for (d in listOf(1f, 2f, 3f, 3.5f)) {
            for ((w, h) in sizes) {
                val s = FluidMotion.pressScale(w * d, h * d, d)
                assertTrue("w=$w h=$h density=$d → $s 收缩过多", s >= 0.95f)
                assertTrue("w=$w h=$h density=$d → $s 看不出按下", s < 1f)
            }
        }
    }

    @Test
    fun `宽胶囊压得比圆按钮轻`() {
        val circle = FluidMotion.pressScale(96f, 96f, 2f)
        val pill = FluidMotion.pressScale(700f, 130f, 2f)
        assertTrue("圆按钮($circle) 应该压得更狠才对", circle < pill)
    }

    // ─────────────────────────────────────── ⑤ 重力弧

    @Test
    fun `贝塞尔路径端点精确落在锚点与终点`() {
        val ax = 200f
        val ay = 120f
        val g = FluidMotion.gravityPath(ax, ay, 0f, 0f)

        val start = FluidMotion.pointOnPath(0f, ax, ay, g, 0f, 0f)
        assertEquals(ax, start.first, 1e-3f)
        assertEquals(ay, start.second, 1e-3f)

        val end = FluidMotion.pointOnPath(1f, ax, ay, g, 0f, 0f)
        assertEquals(0f, end.first, 1e-3f)
        assertEquals(0f, end.second, 1e-3f)
    }

    @Test
    fun `路径中段确实被重力压弯了`() {
        val g = FluidMotion.gravityPath(300f, -260f, 0f, 0f)
        val mid = FluidMotion.pointOnPath(0.5f, 300f, -260f, g, 0f, 0f)
        // 直线插值的中点是 (150, -130)；弧线必须偏离它
        val dx = abs(mid.first - 150f)
        val dy = abs(mid.second + 130f)
        assertTrue("路径没有弯曲：$mid", dx + dy > 1f)
    }

    @Test
    fun `纯水平位移的弧弯度不为零但受比例限制`() {
        // 纯水平（dy=0）时 balance 项为 0，弧退化成直线 —— 与 ColorOS 一致
        val g = FluidMotion.gravityPath(300f, 0f, 0f, 0f)
        val mid = FluidMotion.pointOnPath(0.5f, 300f, 0f, g, 0f, 0f)
        assertEquals("纯水平不该有垂直弯曲", 0f, mid.second, 1e-3f)
    }

    // ─────────────────────────────────────── 抬手阻尼

    @Test
    fun `拖得越远抬手越弹`() {
        val near = FluidMotion.handUpBounce(5f, 0.65f)
        val far = FluidMotion.handUpBounce(900f, 0.65f)
        assertTrue("近处 $near 应小于远处 $far", near < far)
        assertTrue("阻尼比不能为负：$near", near >= 0f)
        assertTrue("阻尼比不能超过 1：$far", far <= 1f)
    }
}
