/*
 * 一维弹簧（阻尼振子）——按 ColorOS「触摸动效」那套 response/bounce 参数化。
 *
 * 逆向来源（计算器 17.2.16 / com.coloros.calculator）：
 *   com.coui.appcompat.animation.dynamicanimation.COUIForce / COUISpringForce
 *     · response  → 角频率 ω = 2π / response，刚度 k = ω²
 *     · bounce    → 阻尼比 ζ = 1 − bounce
 *     · 推进用**解析解**（欠阻尼 / 临界 / 过阻尼三支），不是逐帧欧拉
 *   参数取自 com.coui.appcompat.animation.touchanimation.param.olivelink.TouchMotionTierParams
 *   与 PressParams 的默认值，见 refs/REA-CALCULATOR-FLUID.md。
 *
 * 为什么是解析解，不是以前那种 a = −k·x − c·v 逐帧积分：
 *   ① 掉帧（dt 30ms+）时显式欧拉会发散，以前只能靠调用方夹住 dt 硬扛；
 *      解析解在大 dt 下仍然精确，天然稳定。
 *   ② ColorOS 的曲线是**参数化的**（改 response/bounce 就换手感），
 *      欧拉积分要反推 k / c，参数对不上逆向值。
 *   ③ 能在单元测试里断言「会过冲、会收敛、大 dt 不发散」——
 *      这套效果调坏了肉眼很难发现，只能用数值钉住（见 LiquidSpringTest）。
 */
package com.whale.campusrun

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal class LiquidSpring(
    /** 响应时间（秒）。越小越快，ColorOS 里按压是 0.15~0.25，回弹 0.27~0.65。 */
    response: Float = 0.30f,
    /** 弹性 0..1。0 = 临界阻尼不过冲，越大抖得越久；ColorOS 回弹常用 0.42~0.6。 */
    bounce: Float = 0.45f,
    /** 收尾阈值。到这儿就算「停了」，动画循环可以摘掉，不空转。 */
    private val minVisibleChange: Float = 5e-4f
) {
    companion object {
        /**
         * 速度收敛阈值 = 位移阈值 × 62.5。
         * 62.5 不是拍的：它就是 1s / 16ms（一帧）的倒数关系，
         * 意思是「这一帧最多还能动一个最小可见量」—— 抄自 COUISpringForce。
         */
        private const val VELOCITY_THRESHOLD_MULTIPLIER = 62.5f
    }

    /** 当前值。含义由调用方定（缩放倍数、位移 px、进度 0..1）。 */
    var value: Float = 0f
        private set

    /** 当前速度，单位 / 秒。 */
    var velocity: Float = 0f
        private set

    private var response: Float = response.coerceAtLeast(1e-4f)
    private var dampingRatio: Float = (1f - bounce).coerceAtLeast(0f)

    private var omega: Float = (2.0 * Math.PI / this.response).toFloat()
    private var dampedFreq: Float = 0f
    private var gammaPlus: Float = 0f
    private var gammaMinus: Float = 0f
    private var primed = false

    private val valueThreshold: Float get() = minVisibleChange * 0.75f
    private val velocityThreshold: Float get() = valueThreshold * VELOCITY_THRESHOLD_MULTIPLIER

    /** 换手感：按压与抬手用的是两组不同参数（见 LiquidButton）。 */
    fun retune(response: Float, bounce: Float) {
        val r = response.coerceAtLeast(1e-4f)
        val d = (1f - bounce).coerceAtLeast(0f)
        if (r == this.response && d == dampingRatio) return
        this.response = r
        this.dampingRatio = d
        omega = (2.0 * Math.PI / r).toFloat()
        primed = false
    }

    /** 直接把值钉在某处（按下 / 取消时用），速度清零。 */
    fun set(v: Float) {
        value = v
        velocity = 0f
    }

    /** 保留速度地改值 —— 换手/换参数时不能让曲线跳一下。 */
    fun setWithVelocity(v: Float, vel: Float) {
        value = v
        velocity = vel
    }

    private fun prime() {
        if (primed) return
        val z = dampingRatio
        if (z > 1f) {
            val s = sqrt(z * z - 1f)
            gammaMinus = -z * omega - omega * s
            gammaPlus = -z * omega + omega * s
        } else if (z < 1f) {
            dampedFreq = omega * sqrt(1f - z * z)
        }
        primed = true
    }

    /**
     * 往前推一步。
     *
     * @param target 目标值。
     * @param dt 秒。解析解对 dt 不敏感，但调用方仍应夹到 32ms 以内 ——
     *           一帧跳太远会「瞬移」，看起来像掉帧。
     * @return 是否还在动。false 表示已经稳在 target 上，可以停掉动画循环。
     */
    fun step(target: Float, dt: Float): Boolean {
        if (dt <= 0f) return isMoving(target)
        prime()

        val x0 = value - target
        val v0 = velocity
        val t = dt
        val z = dampingRatio

        var x: Float
        var v: Float

        if (z > 1f) {
            // 过阻尼：两个实指数
            val c1 = x0 - (gammaMinus * x0 - v0) / (gammaMinus - gammaPlus)
            val c2 = (x0 * gammaMinus - v0) / (gammaMinus - gammaPlus)
            x = (exp(gammaMinus * t) * c1 + exp(gammaPlus * t) * c2).toFloat()
            v = (c1 * gammaMinus * exp(gammaMinus * t) +
                c2 * gammaPlus * exp(gammaPlus * t)).toFloat()
        } else if (z == 1f) {
            // 临界阻尼：会最快收住，但**不过冲**
            val c1 = v0 + omega * x0
            val decay = exp(-omega * t)
            x = (decay * (x0 + c1 * t)).toFloat()
            v = (c1 * decay - omega * decay * (x0 + c1 * t)).toFloat()
        } else {
            // 欠阻尼：衰减正弦，这一支才会「荡过去再弹回来」
            val c1 = x0
            val c2 = (v0 + z * omega * x0) / dampedFreq
            val decay = exp(-z * omega * t).toFloat()
            val c = cos(dampedFreq * t)
            val s = sin(dampedFreq * t)
            x = decay * (c1 * c + c2 * s)
            v = -z * omega * x + decay * (-c1 * dampedFreq * s + c2 * dampedFreq * c)
        }

        value = x + target
        velocity = v
        return isMoving(target)
    }

    /** 是否已到平衡点。到点就直接钉死，避免尾巴上无限逼近。 */
    fun isMoving(target: Float = 0f): Boolean {
        if (abs(target - value) > valueThreshold) return true
        if (abs(velocity) > velocityThreshold) return true
        value = target
        velocity = 0f
        return false
    }

    /** 过冲到反向的最大幅度（单测用）。 */
    fun settleTrace(target: Float, dt: Float, maxFrames: Int = 600): List<Float> {
        val trace = ArrayList<Float>(maxFrames)
        var frames = 0
        while (frames < maxFrames) {
            val moving = step(target, dt)
            trace.add(value)
            frames++
            if (!moving) break
        }
        return trace
    }
}
