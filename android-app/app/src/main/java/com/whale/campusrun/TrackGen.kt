/*
 * 轨迹生成：配速标定 / 电子围栏内缩 / 禁跑区避让 / 多尺度 GPS 噪声 / 毛刺突起 / 温和起伏
 */
package com.whale.campusrun

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 几何工具。全部在 GCJ02 经纬度上做平面近似 —— 服务器和区域多边形都用 gcj02，
 * 保持一致可以彻底避开坐标系转换带来的偏移。
 */
object Geo {

    const val R = 6371008.8

    fun hav(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val x = sin(dl) * cos(p2)
        val y = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }

    /** 从 (lat,lon) 沿方位角 brgDeg 走 dist 米，返回 [lat, lon]。 */
    fun offset(lat: Double, lon: Double, dist: Double, brgDeg: Double): DoubleArray {
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val brg = Math.toRadians(brgDeg)
        val d = dist / R
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(brg))
        val l2 = l1 + atan2(sin(brg) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return doubleArrayOf(Math.toDegrees(p2), Math.toDegrees(l2))
    }

    /** 与小程序 IsPtInPoly 等价的射线法。poly 元素为 [lat, lon]。 */
    fun pointInPoly(lat: Double, lon: Double, poly: List<DoubleArray>): Boolean {
        val n = poly.size
        if (n < 3) return false
        var inside = false
        for (i in 0 until n) {
            val a = poly[i]
            val b = if (i == n - 1) poly[0] else poly[i + 1]
            val la = a[0]; val lo = a[1]
            val lb = b[0]; val lob = b[1]
            if ((lat >= la && lat < lb) || (lat >= lb && lat < la)) {
                if (abs(la - lb) > 0) {
                    if (lo - (lo - lob) * (la - lat) / (la - lb) < lon) inside = !inside
                }
            }
        }
        return inside
    }
}

/** 轨迹点：时间用相对起始的秒数表示，由调用方加上真实起始时刻。 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val secOffset: Int,
    val speed: Double,
    val bearing: Double,
    val accuracy: Double
)

/** 一次轨迹生成的结果，连同实测配速一起返回，方便界面说明"有没有被钳制"。 */
data class TrackResult(
    val points: List<TrackPoint>,
    /** 实测平均配速，秒/公里。 */
    val paceSecKm: Double,
    /** 目标配速加减容差得到的允许窗口。 */
    val window: ClosedFloatingPointRange<Double>,
    /** 采样点数是否为了压进窗口而被调整过。 */
    val adjusted: Boolean
) {
    val inWindow: Boolean get() = paceSecKm in window
}

/**
 * 轨迹生成器。
 *
 * 输入是从服务器拉下来的区域多边形（gcj02），输出是一条落在多边形内、
 * 配速精确的闭环轨迹。
 *
 * 两套路径：
 *  · `burrs = false`（默认，「轨迹毛刺」开关关闭）：`generateSmooth()`，
 *    保留 v1.10.1 的光滑轨迹
 *  · `burrs = true`（开关打开）：下面的多圈错位 + 毛刺 / 突起
 *
 * 毛刺模式的关键点：
 *  1. 环线由多边形向内缩放得到，先用「基准环是否合法」挑内缩比例，
 *     再叠多条整数圈正弦 + 局部凸起。整数圈保证环线闭合，凸起保证每一圈
 *     都不会像同一个模子刻出来的
 *  2. 采样点数由「配速 × 距离」锁死，二分反解地面速度，
 *     使**实测配速等于目标配速**（否则噪声会把距离撑长十几个百分点）
 *  3. 四层时间尺度的 AR(1) 噪声：几分钟的贴线漂移、几秒的摆臂晃动、
 *     一秒级的毛糙。它让每一圈都能错开几米，叠成真实 App 上那种"粗线"
 *  4. 稀疏的野点：一秒级的尖刺、两三秒的锯齿、几秒的三角形绕行，
 *     模拟多径 / 树荫 / 贴楼时 GPS 的突发跳点 —— 这就是图上的"毛刺/突起"
 *  5. 越界不硬拽：按「基准点 → 噪声点」方向二分回缩，只削掉越界的那一小段，
 *     保住毛刺的形状，同时保证 100% 在围栏内、绝不进禁跑区
 *  6. 逐秒速度带**温和起伏**（起步慢、中途数段快慢、越跑越累、尾声略冲，
 *     并叠入少量短暂停顿），**平均值仍锁死在目标配速上**
 *  7. 生成后校验平均配速是否落在「目标 ± 容差」内，越界就按比例微调采样点数重算
 */
object TrackGen {

    /** 平均配速相对目标值允许偏离多少秒/公里。 */
    const val DEFAULT_PACE_TOLERANCE_SEC = 20.0

    /** 最多重算几次来把平均配速压进窗口。 */
    private const val MAX_PACE_FIX_ATTEMPTS = 4

    /** 环线相对围栏的内缩比例：从外向里试，取第一个基准环完全合法的。 */
    private val RING_INSETS = doubleArrayOf(
        0.90, 0.88, 0.86, 0.84, 0.81, 0.77, 0.72, 0.66, 0.59, 0.51
    )

    /** 环线有机摆动的基准幅度（米）。 */
    private const val RING_WOBBLE_M = 4.5

    /** 环线加密步长（米）：太稀磨不出来，太密纯浪费时间。 */
    private const val RING_STEP_M = 7.0

    /**
     * AR(1) 噪声的相关系数与强度（强度再乘 noise）。
     * 0.997 ≈ 几分钟级（每一圈的横向错位），0.97 / 0.85 ≈ 十几秒，
     * 0.55 ≈ 一秒级毛糙。分开叠而不是一个系数，才能既有大漂移又有小锯齿。
     */
    private val NOISE_RHO = doubleArrayOf(0.997, 0.970, 0.850, 0.550)

    /*
     * 噪声强度 —— 对着真实轨迹重新标定。
     *
     * 旧值 (0.50, 0.27, 0.27, 0.33) 让逐秒转角的中位数到了 6.0°，
     * 而真实那次跑步只有 2.3°（p90 真实 11.2°，旧值 18.3°）——
     * 也就是说旧模型"抖得太厉害"，画出来像信号差的仪器，不像人跑。
     *
     * 现在整体降到 0.22 / 0.10 / 0.07 / 0.045：中位数落到 3.2°、p90 降到 18°
     * 一带，和真实分布的骨架对齐。保持远大于 > 近的衰减顺序不变（分钟级漂移最大），
     * 因为那个是"每一圈横向错开几米"的来源，压掉就不像多圈叠图了。
     */
    private val NOISE_SIGMA = doubleArrayOf(0.22, 0.10, 0.07, 0.045)

    /**
     * 配速形态参数（与 `refs/track_model.py` 同一套，都有真实自相关支撑）。
     *
     * 速度变化基本是一个 AR(1)：真实记录的中位自相关阶梯 0.87 / 0.83 / 0.75 /
     * 0.59 / 0.40 / 0.30 / 0.18 和 0.89^lag 几乎重合。所以不再用"每 40~70 秒
     * 换一档常数"，那在 lag 5~10 上掉得太快。
     */
    private const val PACE_RHO = 0.89

    /** 对数速度的平稳标准差。0.36 → 速度 CV ≈ 0.37，真实 0.27~0.41。 */
    private const val PACE_LOG_STD = 0.36

    /** 到终点累计慢 10%（真实每圈用时的漂移量级）。 */
    private const val FATIGUE = 0.10

    /** 起步 15 秒还没跑开 / 最后 20 秒冲一下。 */
    private const val START_RAMP_SEC = 15
    private const val START_RAMP = 0.90
    private const val FINISH_KICK_SEC = 20
    private const val FINISH_KICK = 1.05

    /**
     * 停顿：真实每场 7~21 段、中位 1~2 秒（约占 4% 时长），另有 0~1 次长停顿。
     * 旧模型"2~3 段 × 4~9 秒"画出来是几个突兀的断点，而不是"跑着跑着顿一下"。
     */
    private const val MICRO_STALL_PER_MIN = 1.1
    private val MICRO_STALL_LEN = 1..2
    private const val LONG_STALL_PROB = 0.65
    private val LONG_STALL_LEN = 5..20

    /** 停顿期间的速度乘子（≈ 原地站住，但不绝对为 0，GPS 会有微小抖动）。 */
    private const val STALL_FACTOR = 0.03

    /**
     * @param poly             电子围栏多边形（dzwl，gcj02），元素 [lat, lon]
     * @param excludes         禁跑区多边形（jpq），轨迹必须完全避开
     * @param paceSecKm        目标配速，秒/公里
     * @param targetM          目标距离，米
     * @param noise            逐秒抖动强度，0.6 ≈ 开阔地手机滤波后的表现
     * @param seed             随机种子
     * @param paceToleranceSec 平均配速容差（秒/公里）
     * @param burrs            是否走 ①~④ 流水线。v1.12.0 起默认开启；
     *                         关闭则回到 v1.10.1 的光滑轨迹（只作对照）
     */
    fun generate(
        poly: List<DoubleArray>,
        excludes: List<List<DoubleArray>> = emptyList(),
        paceSecKm: Double,
        targetM: Double,
        noise: Double = 0.6,
        seed: Int = 0,
        paceToleranceSec: Double = DEFAULT_PACE_TOLERANCE_SEC,
        burrs: Boolean = true,
        /** 场地 ID（`/area/getAllArea` 的 placeCode）：决定 ①~④ 的整套参数。 */
        placeCode: String? = null
    ): TrackResult {
        require(poly.size >= 3) { "区域多边形至少需要 3 个顶点" }
        require(paceSecKm > 0) { "配速必须为正数" }

        val fence = dedupe(poly)
        val bans = excludes.map { dedupe(it) }.filter { it.size >= 3 }
        val window = paceWindow(paceSecKm, paceToleranceSec)

        // 开关关闭：保持 v1.10.1 的光滑轨迹，一条线、没有多圈错位和野点。
        if (!burrs) return generateSmooth(fence, bans, paceSecKm, targetM, noise, seed, window)

        // 开关打开：走 ①→④ 流水线（TrackPipe），参数按场地（placeCode）选。
        //
        // 顺序不能换：
        //   · ①② 在 ③ 之前 —— 横向场和噪声都长在"环线坐标系"上；
        //   · ③ 在 ④ 之前 —— 噪声会把点推进禁区，先压噪再挤，挤完才没人再推回去；
        //   · ④ 只做位移，且在标定的二分内部参与距离计算，标定看到的是最终坐标。
        return generatePipelined(fence, bans, paceSecKm, targetM, noise, seed, window, placeCode)
    }

    /** 平均配速允许窗口，单位秒/公里。 */
    fun paceWindow(paceSecKm: Double, tol: Double): ClosedFloatingPointRange<Double> {
        val t = abs(tol)
        return (paceSecKm - t).coerceAtLeast(60.0)..(paceSecKm + t)
    }

    /** 轨迹的实测平均配速（秒/公里）。时长按采样点跨度算，与 collectTime 一致。 */
    fun paceOf(pts: List<TrackPoint>): Double {
        if (pts.size < 2) return 0.0
        val d = distanceOf(pts)
        return if (d > 0) (pts.size - 1) / (d / 1000.0) else 0.0
    }

    /** 轨迹总距离（米）。 */
    fun distanceOf(pts: List<TrackPoint>): Double {
        var s = 0.0
        for (i in 0 until pts.size - 1) {
            s += Geo.hav(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        return s
    }

    /** 去掉重复顶点（服务器下发的多边形经常有连续重复点）。 */
    private fun dedupe(poly: List<DoubleArray>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(poly.size)
        for (p in poly) {
            val last = out.lastOrNull()
            if (last == null || last[0] != p[0] || last[1] != p[1]) out.add(p)
        }
        if (out.size > 1 && out[0][0] == out.last()[0] && out[0][1] == out.last()[1]) {
            out.removeAt(out.size - 1)
        }
        return out
    }
    /**
     * 逐秒的"速度权重"。均值恒为 1，所以整体配速仍然锚在目标上；
     * 它只负责把匀速拆成有快有慢。
     *
     * 三层时间尺度叠加：
     *   · 疲劳（整场，单向变慢）
     *   · 地形 / 路况（一两分钟量级）
     *   · 步频波动（十几秒量级）—— 纯模型噪声，与传感器无关
     */
    private fun stepWeights(n: Int, seed: Int): DoubleArray {
        if (n < 24) return DoubleArray(n) { 1.0 }

        val slow = slownessProfile(n, seed)
        val terrain = randomWalkSegments(n, 260, 0.06, seed, 1)
        val jitter = randomWalkSegments(n, 11, 0.045, seed, 2)

        val w = DoubleArray(n)
        for (i in 0 until n) w[i] = 1.0 / (slow[i] * terrain[i] * jitter[i])

        // 归一化：均值 = 1。标定只负责把总距离校准到 targetM，
        // 形态不参与配速计算，因此两者不会互相干扰。
        val mean = w.average()
        if (mean > 0) for (i in 0 until n) w[i] /= mean
        return w
    }
    /**
     * "慢"的倍数：1.0 = 目标配速，越大越慢，22 ≈ 停下来系鞋带。
     *
     * 这是"温和起伏"：起步慢、中途数段快慢、越跑越累、尾声略冲，
     * 并叠入少量短暂停顿。全程不停的那种匀速跑是不存在的。
     */
    private fun slownessProfile(n: Int, seed: Int): DoubleArray {
        val out = DoubleArray(n) { 1.0 }
        val rnd = Random(seed * 7919 + 13)

        // 配速段：每 40~70 秒换一档，模拟"这一段跑快些、那一段拖着"
        val segLen = 40 + rnd.nextInt(31)
        var i = 0
        while (i < n) {
            val end = min(n, i + segLen)
            val f = 0.85 + rnd.nextDouble() * 0.60          // 0.85 ~ 1.45
            for (k in i until end) out[k] = f
            i = end
        }

        for (k in 0 until n) {
            // 起跑要加速到位
            if (k < 25) out[k] *= 1.08
            // 尾声通常会冲一下
            if (k >= n - 25) out[k] *= 0.94
            // 疲劳：越到后面越慢
            out[k] *= 1.0 + 0.10 * k / n
        }

        // 停顿：约 7% 的时间点落在里面，2 公里大概是 2~3 次
        val pauses = (n * 0.07 / 6.0).toInt().coerceIn(1, 5)
        repeat(pauses) {
            val len = 4 + rnd.nextInt(6)                     // 4 ~ 9 秒
            val span = maxOf(1, n - len - 1)
            val at = (span * (0.12 + rnd.nextDouble() * 0.76)).toInt()
            for (k in at until min(n, at + len)) out[k] = 22.0
        }

        return out
    }
    /**
     * 分段常数的乘性扰动：每段随机长度、随机幅度。
     *
     * 用它而不是逐点白噪声，是因为真实的速度变化是**成簇**的
     * （一段路跑快、一段路跑慢），逐点独立抖动看起来反而像仪器噪声。
     */
    private fun randomWalkSegments(
        n: Int, segLen: Int, jitter: Double, seed: Int, salt: Int
    ): DoubleArray {
        val rnd = Random(seed * 131 + salt * 977 + 17)
        val out = DoubleArray(n) { 1.0 }
        var i = 0
        while (i < n) {
            val g = maxOf(1, segLen / 2 + rnd.nextInt(segLen + 1))
            val v = 1.0 + rnd.nextDouble(-jitter, jitter)
            val end = min(n, i + g)
            for (k in i until end) out[k] = v
            i = end
        }
        return out
    }
    /** 向内缩放：每个顶点朝质心方向按比例靠拢。 */
    /**
     * 向内缩放：每个顶点朝质心方向按比例靠拢，并**统一成逆时针**。
     *
     * 为什么强制逆时针：国内操场默认逆时针跑，真实记录（5 条）累计转角全是负的；
     * 而围栏多边形的顶点序是服务端给的，可顺可逆 —— 照抄的话，同一块场地
     * 换台设备可能画成顺时针，与真实跑向相反，一眼假。
     */
    private fun inset(poly: List<DoubleArray>, f: Double): List<DoubleArray> {
        val cLat = poly.map { it[0] }.average()
        val cLon = poly.map { it[1] }.average()
        val scaled = poly.map {
            doubleArrayOf(cLat + (it[0] - cLat) * f, cLon + (it[1] - cLon) * f)
        }
        return if (signedArea(scaled) >= 0) scaled else scaled.reversed()
    }

    /** 多边形有向面积（正 = 逆时针）。操场量级上用 (lon·cos(lat), lat) 平面近似足够。 */
    private fun signedArea(poly: List<DoubleArray>): Double {
        if (poly.size < 3) return 0.0
        val k = Math.cos(Math.toRadians(poly.map { it[0] }.average()))
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            s += (a[1] * k) * b[0] - (b[1] * k) * a[0]
        }
        return s / 2.0
    }
    /**
     * 把落到围栏外的点拉回围栏内 —— 沿"该点 → 质心"的连线二分逼近边界。
     *
     * 为什么需要它：内缩系数是离散的档位（0.90 / 0.85 / …），而 GPS 噪声
     * 会把点横向推出十几米。某些 seed / 配速组合下，档位挑得再准也总有几个点
     * 被噪声顶到围栏外（实测风华运动场 330s/km + seed=555 会有 2 个点出界）。
     * 出界的点服务端不计里程，于是"实测配速"就会被拉偏，达标判定跟着遭殃。
     *
     * 这里只动越界的那几个点，而且位移极小（刚好回到边界内侧），
     * 对整体形态和配速标定的影响可以忽略。
     */
    private fun clampToFence(
        lat: Double,
        lon: Double,
        fence: List<DoubleArray>
    ): DoubleArray {
        if (fence.size < 3) return doubleArrayOf(lat, lon)
        if (Geo.pointInPoly(lat, lon, fence)) return doubleArrayOf(lat, lon)

        // 朝"最近的边"收，而不是"朝质心"收。
        //
        // 朝质心收的位移可以很大：实测一个越界点能被拽 69.9 m，于是相邻两个
        // 采样点一个被拽回来、一个没被拽，一步就是 20 m —— 图上是一根拉出去
        // 又拉回来的长刺，也正是"光滑轨迹却有 8m+ 跳点"的来源。
        // 找最近边、沿该边法向贴回去，位移就是"越界深度"，最小且连续。
        var bestD = Double.MAX_VALUE
        var bestLat = lat
        var bestLon = lon
        for (i in fence.indices) {
            val a = fence[i]
            val b = fence[(i + 1) % fence.size]
            val k = Math.cos(Math.toRadians(lat))
            val ax = a[1] * k; val ay = a[0]
            val bx = b[1] * k; val by = b[0]
            val px = lon * k; val py = lat
            val dx = bx - ax; val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len
            val cx = ax + t.coerceIn(0.0, 1.0) * dx
            val cy = ay + t.coerceIn(0.0, 1.0) * dy
            val d = Math.hypot(px - cx, py - cy)
            if (d < bestD) {
                bestD = d
                // 从最近边界点再往里让 0.6 m，避免正好压线被判出界
                val inward = if (len == 0.0) 0.0 else 0.6 / Math.sqrt(len)
                val tx = cx - dx * inward
                val ty = cy - dy * inward
                bestLat = ty
                bestLon = tx / k
            }
        }
        if (!Geo.pointInPoly(bestLat, bestLon, fence)) return doubleArrayOf(lat, lon)
        return doubleArrayOf(bestLat, bestLon)
    }
    /**
     * 沿 base → p 连线二分，找最大的、仍然合法的内插点。
     *
     * 和 clampToFence（拉向质心）的区别：这里只收"扰动"本身，不动基准环。
     * 所以毛刺被沿着它自己的方向削短，而不是被抹平在围栏边。
     */
    private fun pullToward(
        base: DoubleArray,
        p: DoubleArray,
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>
    ): DoubleArray {
        if (!isValid(base[0], base[1], fence, excludes)) return p
        var lo = 0.0
        var hi = 1.0
        repeat(22) {
            val mid = (lo + hi) / 2.0
            val mlat = base[0] + (p[0] - base[0]) * mid
            val mlon = base[1] + (p[1] - base[1]) * mid
            if (isValid(mlat, mlon, fence, excludes)) lo = mid else hi = mid
        }
        val t = (lo * 0.99).coerceIn(0.0, 1.0)
        return doubleArrayOf(base[0] + (p[0] - base[0]) * t, base[1] + (p[1] - base[1]) * t)
    }

    private fun isValid(
        lat: Double,
        lon: Double,
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>
    ): Boolean {
        if (!Geo.pointInPoly(lat, lon, fence)) return false
        for (e in excludes) if (Geo.pointInPoly(lat, lon, e)) return false
        return true
    }
    private fun densify(ring: List<DoubleArray>, stepM: Double): Pair<List<DoubleArray>, DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val pts = ring + ring[0]
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val d = Geo.hav(a[0], a[1], b[0], b[1])
            val k = maxOf(1, Math.ceil(d / stepM).toInt())
            for (j in 0 until k) {
                val t = j.toDouble() / k
                out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            }
        }
        val cum = DoubleArray(out.size + 1)
        for (i in 0 until out.size) {
            val a = out[i]
            val b = if (i == out.size - 1) out[0] else out[i + 1]
            cum[i + 1] = cum[i] + Geo.hav(a[0], a[1], b[0], b[1])
        }
        return Pair(out, cum)
    }

    private fun pointAt(dense: List<DoubleArray>, cum: DoubleArray, dist: Double): Pair<DoubleArray, Double> {
        val total = cum[cum.size - 1]
        if (total <= 0.0) return Pair(dense[0], 0.0)
        val d = ((dist % total) + total) % total
        var lo = 0
        var hi = dense.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= d) lo = mid else hi = mid - 1
        }
        val i = min(lo, dense.size - 2)
        val seg = cum[i + 1] - cum[i]
        val t = if (seg <= 0) 0.0 else (d - cum[i]) / seg
        val a = dense[i]
        val b = dense[i + 1]
        val p = doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        return Pair(p, Geo.bearing(a[0], a[1], b[0], b[1]))
    }

    // ------------------------------------------------------------ ①~④ 流水线

    /**
     * 毛刺模式的完整流水线：① 固定内轨迹 → ② 逐圈不重合 → ③ 波动 + 噪声
     * → ④ 把压进禁区的点挤出去。四段实现在 `TrackPipe` 里，这里只负责
     * 采样、二分标定和配速窗口修正。
     *
     * 采样点数 n 由「配速 × 距离」确定（时长是硬约束）；地面步长用二分反解，
     * 使**输出距离精确等于 targetM**。噪声按秒注入、与步长无关，不标定就会把
     * 实测距离撑长十几个百分点，配速跟着失准。
     *
     * 关键：④ 的挤禁区发生在 emit() 内部，所以标定看到的是**最终坐标**，
     * 不会出现"以为距离够了、挤出后其实被裁短"的自欺欺人。
     */
    private fun generatePipelined(
        fence: List<DoubleArray>,
        bans: List<List<DoubleArray>>,
        paceSecKm: Double,
        targetM: Double,
        noise: Double,
        seed: Int,
        window: ClosedFloatingPointRange<Double>,
        placeCode: String?
    ): TrackResult {
        val prof = TrackPipe.profileFor(placeCode, fence)
        val base = TrackPipe.innerTrack(fence, bans, prof)
        val track = TrackPipe.LapTrack(base, fence, bans, seed, prof)

        // 采样点数 = 「时长 + 1」：逐秒出点，n 个点覆盖 n-1 秒。
        // 写成 round(targetM / 每步米数) 会丢掉这一秒 —— 2 km @ 330 s/km
        // 应当是 660 秒，那种写法得到 660 个点 = 659 秒，配速天生少 0.5 秒，
        // 再叠上距离误差就顶出 1 秒的容差。
        var n = maxOf(2, Math.round(targetM * paceSecKm / 1000.0).toInt() + 1)
        var adjusted = false
        var pts = emptyList<TrackPoint>()
        var pace = 0.0

        for (attempt in 0 until MAX_PACE_FIX_ATTEMPTS) {
            val weights = paceWeights(n, seed, prof)
            pts = buildPipelined(track, n, weights, targetM, noise, seed, fence, bans, prof)
            pace = paceOf(pts)
            if (pace in window) break
            val ratio = pace / paceSecKm
            val next = Math.round(n / ratio).toInt().coerceIn(2, n * 4 + 8)
            if (next == n) break
            n = next
            adjusted = true
        }
        return TrackResult(pts, pace, window, adjusted)
    }

    /** 固定 n，按权重铺路程，叠底噪与野点，再逐点挤出禁区。 */
    private fun buildPipelined(
        track: TrackPipe.LapTrack,
        n: Int,
        weights: DoubleArray,
        targetM: Double,
        noise: Double,
        seed: Int,
        fence: List<DoubleArray>,
        bans: List<List<DoubleArray>>,
        prof: FieldProfile
    ): List<TrackPoint> {
        val levels = NOISE_RHO.size
        val noiseEff = noise * prof.noiseScale
        val spurLat = DoubleArray(n)
        val spurFwd = DoubleArray(n)
        fillSpurs(n, seed, (noise / 0.6).coerceIn(0.0, 2.5) * prof.spurScale, spurLat, spurFwd)

        fun emit(step: Double): List<TrackPoint> {
            val rnd = Random(seed)
            val rndAcc = Random(seed * 31 + 7)
            val perr = DoubleArray(levels)
            val aerr = DoubleArray(levels)
            var travelled = 0.0
            val lat = DoubleArray(n)
            val lon = DoubleArray(n)
            val pew = DoubleArray(n)
            val pushes = ArrayList<Double>()

            for (i in 0 until n) {
                val (p, hdg) = track.locate(travelled)
                // 野点是"事件"，AR 噪声是"底噪"：先加野点再加底噪
                var perrT = spurLat[i]
                var aerrT = spurFwd[i]
                for (c in 0 until levels) {
                    // 四层各有自己的时间常数，场地可以单独收放其中某几层
                    val w = prof.noiseLayers[c]
                    perr[c] = NOISE_RHO[c] * perr[c] +
                            rnd.nextDouble(-1.0, 1.0) * noiseEff * NOISE_SIGMA[c] * w
                    aerr[c] = NOISE_RHO[c] * aerr[c] +
                            rnd.nextDouble(-1.0, 1.0) * noiseEff * NOISE_SIGMA[c] * w
                    perrT += perr[c]
                    aerrT += aerr[c] * 0.45
                }
                val o1 = Geo.offset(p[0], p[1], perrT, (hdg + 90.0) % 360.0)
                val q = Geo.offset(o1[0], o1[1], aerrT, hdg)
                val c = TrackPipe.place(p, doubleArrayOf(q[0], q[1]), fence, bans, pushes)
                lat[i] = c[0]
                lon[i] = c[1]
                pew[i] = perrT
                travelled += step * weights[i]
            }

            // speed / bearing 从**最终坐标**反推：上报字段与轨迹严格自洽
            val out = ArrayList<TrackPoint>(n)
            for (i in 0 until n) {
                val j = if (i < n - 1) i + 1 else i - 1
                val seg = Geo.hav(lat[i], lon[i], lat[j], lon[j])
                val brg = if (i < n - 1) Geo.bearing(lat[i], lon[i], lat[j], lon[j])
                else if (n > 1) Geo.bearing(lat[j], lon[j], lat[i], lon[i]) else 0.0
                val acc = 4.5 + abs(pew[i]) * 0.30 + rndAcc.nextDouble(-1.0, 2.2)
                out.add(
                    TrackPoint(
                        lat = lat[i], lon = lon[i], secOffset = i,
                        speed = Math.round(seg * 100.0) / 100.0,
                        bearing = Math.round(brg * 10.0) / 10.0,
                        accuracy = Math.round(acc.coerceIn(3.0, 18.0) * 10.0) / 10.0
                    )
                )
            }
            return out
        }

        fun measure(p: List<TrackPoint>): Double = distanceOf(p)

        if (measure(emit(0.0)) >= targetM) return emit(0.0)
        var lo = 0.0
        var hi = 5.0
        while (measure(emit(hi)) < targetM && hi < 1e5) hi *= 1.5
        repeat(46) {
            val mid = (lo + hi) / 2.0
            if (measure(emit(mid)) < targetM) lo = mid else hi = mid
        }
        var step = (lo + hi) / 2.0
        var best = emit(step)
        // ④ 的挤禁区会让 measure(step) 出现平台：点被推到禁区外之后，再加大
        // 地面步长，实测距离的增量不再均匀（太极实测偏 1.02 秒，刚好卡在容差外）。
        // 二分之后再做几步"按比例修正"，把实测距离拉回 targetM。
        for (round in 0 until 8) {
            val got = measure(best)
            val err = got - targetM
            if (abs(err) <= targetM * 0.0005) break
            val ratio = if (got > 0) targetM / got else 1.0
            val nxt = (step * ratio).coerceIn(step * 0.5, step * 2.0)
            if (abs(nxt - step) < 1e-9) break
            val cand = emit(nxt)
            if (abs(measure(cand) - targetM) < abs(err)) {
                step = nxt
                best = cand
            } else break   // 不再改善就停，避免在平台上反复横跳
        }
        return best
    }

    /**
     * 逐秒速度权重（均值恒为 1），波动强度按场地收放。
     *
     * 真实步长 CV 差得很远：宁静苑 0.38/1.35、风华 0.40/0.59、太极 0.29 ——
     * 共用一套 PACE_LOG_STD 会让太极的起伏比真实大一倍。
     */
    private fun paceWeights(n: Int, seed: Int, prof: FieldProfile): DoubleArray {
        if (n < 4) return DoubleArray(n) { 1.0 }
        val rnd = Random(seed * 7919 + 13)
        val std = PACE_LOG_STD * prof.paceLogScale
        val half = std * Math.sqrt(1.0 - PACE_RHO * PACE_RHO) * Math.sqrt(3.0)
        val g = DoubleArray(n)
        g[0] = rnd.nextDouble(-std, std)
        for (i in 1 until n) {
            // 两个 uniform 之和近似正态：Kotlin 侧只有 nextDouble，和 Python 同构
            val nz = (rnd.nextDouble(-1.0, 1.0) + rnd.nextDouble(-1.0, 1.0)) / 1.4142
            g[i] = PACE_RHO * g[i - 1] + nz * half
        }
        for (i in 0 until n) {
            val t = if (n > 1) i.toDouble() / (n - 1) else 0.0
            g[i] -= Math.log(1.0 + FATIGUE * t)
            if (i < START_RAMP_SEC) {
                val ramp = 1.0 - (1.0 - START_RAMP) * (1.0 - i.toDouble() / START_RAMP_SEC)
                g[i] += Math.log(ramp)
            }
            if (i >= n - FINISH_KICK_SEC) g[i] += Math.log(FINISH_KICK)
        }
        val w = DoubleArray(n) { Math.exp(g[it]) }
        for ((at, len) in stallWindows(n, seed, prof)) {
            for (k in at until min(n, at + len)) w[k] *= STALL_FACTOR
        }
        val mean = w.average()
        if (mean > 0) for (i in 0 until n) w[i] /= mean
        return w
    }

    /**
     * 稀疏野点：真机 GPS 在多径 / 树荫 / 贴楼时的突发跳点。
     *
     * 四档，按「每 660 秒多少次」定密度，所以 2km 和 5km 的观感一致：
     *   · 大跳 9~16 m（沿轨为主，一步窜出去立刻回来）
     *   · 绕行包 4~9 点 × 5~11 m（侧面，像绕人）
     *   · 硬毛刺 3~8 m（一秒尖刺 / 两步来回）
     *   · 小毛刺 0.8~2.6 m（把线边揉毛）
     *
     * 位置只依赖下标与 seed，与 step 无关 —— 这样二分标定时 measure(step)
     * 仍然单调，配速不会因为野点漂移而失准。
     */
    private fun fillSpurs(n: Int, seed: Int, intensity: Double,
                          lateral: DoubleArray, forward: DoubleArray) {
        if (n < 80) return
        val rnd = Random(seed * 1_000_003 + 77)
        val used = ArrayList<Int>()
        fun free(at: Int, gap: Int): Boolean = used.none { abs(it - at) < gap }
        val lo = maxOf(2, (n * 0.03).toInt())
        val hi = maxOf(lo + 1, minOf(n - 4, (n * 0.97).toInt()))
        val scale = n / 660.0

        fun take(count: Double, gap: Int): List<Int> {
            val want = count * scale
            val k = want.toInt() + if (rnd.nextDouble() < (want - want.toInt())) 1 else 0
            val out = ArrayList<Int>()
            repeat(k) {
                var tries = 0
                while (tries++ < 80) {
                    val at = rnd.nextInt(lo, hi)
                    if (!free(at, gap)) continue
                    used.add(at); out.add(at); break
                }
            }
            return out
        }

        for (at in take(1.6, 14)) {
            val a = rnd.nextDouble(9.0, 16.0) * intensity * if (rnd.nextBoolean()) 1.0 else -1.0
            val share = rnd.nextDouble(-0.28, 0.28)
            lateral[at] = a * abs(share)
            forward[at] = a * (if (share >= 0) 1.0 else -1.0) * (1.0 - abs(share))
        }
        for (at in take(1.0, 12)) {
            val span = rnd.nextInt(4, 10)
            if (at + span >= n) continue
            val a = rnd.nextDouble(5.0, 11.0) * intensity * if (rnd.nextBoolean()) 1.0 else -1.0
            val share = rnd.nextDouble(-0.25, 0.25)
            for (k in 0..span) {
                val s = k.toDouble() / span
                lateral[at + k] = a * sin(PI * s)
                forward[at + k] = a * share * sin(2 * PI * s)
            }
        }
        for (at in take(1.6, 8)) {
            val a = rnd.nextDouble(3.0, 8.0) * intensity
            val sign = if (rnd.nextBoolean()) 1.0 else -1.0
            lateral[at] = a * 0.45 * sign
            forward[at] = a * 0.9 * sign
            if (rnd.nextDouble() < 0.28 && at + 1 < n) lateral[at + 1] = -a * 0.3 * sign
        }
        for (at in take(11.0, 4)) {
            val a = rnd.nextDouble(0.8, 2.6) * intensity * if (rnd.nextBoolean()) 1.0 else -1.0
            lateral[at] = a
            forward[at] = rnd.nextDouble(-0.5, 0.5) * a * 0.4
        }
    }

    /** 停顿区间：真实是"一堆 1~2 秒"+ 0~1 次长停顿，密度按场地缩放。 */
    private fun stallWindows(n: Int, seed: Int, prof: FieldProfile): List<Pair<Int, Int>> {
        val rnd = Random(seed * 104729 + 31)
        val out = ArrayList<Pair<Int, Int>>()
        val lo = maxOf(2, (n * 0.02).toInt())
        val hi = maxOf(lo + 1, (n * 0.98).toInt())
        val count = Math.round(n / 60.0 * MICRO_STALL_PER_MIN * prof.microStallScale).toInt()
        repeat(maxOf(1, count)) {
            val len = rnd.nextInt(MICRO_STALL_LEN.first, MICRO_STALL_LEN.last + 1)
            out.add(Pair(rnd.nextInt(lo, maxOf(lo + 1, hi - len)), len))
        }
        if (rnd.nextDouble() < LONG_STALL_PROB * prof.longStallScale) {
            val len = rnd.nextInt(LONG_STALL_LEN.first, LONG_STALL_LEN.last + 1)
            out.add(Pair(rnd.nextInt(lo, maxOf(lo + 1, hi - len)), len))
        }
        out.sortBy { it.first }
        val merged = ArrayList<Pair<Int, Int>>()
        for ((at, len) in out) {
            if (merged.isNotEmpty() && at <= merged.last().first + merged.last().second) {
                val (a, b) = merged.last()
                merged[merged.size - 1] = Pair(a, maxOf(b, at + len - a))
            } else merged.add(Pair(at, len))
        }
        return merged
    }

    // ------------------------------------------------------------ 光滑模式（v1.10.1 兼容）

    /**
     * 「轨迹毛刺」开关关闭时的轨迹。
     *
     * 完全保留 v1.10.1 的算法：两条低频正弦 + 两轮磨角，
     * 单个 AR(1) 噪声，不复用上面的多尺度噪声 / 野点 / 环线自检。
     * 这样用户关掉开关看到的就是熟悉的光滑轨迹，而不是"少了一点毛刺"的半成品。
     */
    private fun generateSmooth(
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>,
        paceSecKm: Double,
        targetM: Double,
        noise: Double,
        seed: Int,
        window: ClosedFloatingPointRange<Double>
    ): TrackResult {
        // 采样点数由「配速 × 距离」唯一确定（时长是硬约束）
        // 采样点数 = 「时长 + 1」：逐秒出点，n 个点覆盖 n-1 秒。
        // 写成 round(targetM / 每步米数) 会丢掉这一秒 —— 2 km @ 330 s/km
        // 应当是 660 秒，那种写法得到 660 个点 = 659 秒，配速天生少 0.5 秒，
        // 再叠上距离误差就顶出 1 秒的容差。
        var n = maxOf(2, Math.round(targetM * paceSecKm / 1000.0).toInt() + 1)
        var adjusted = false
        var pts = emptyList<TrackPoint>()
        var pace = 0.0

        for (attempt in 0 until MAX_PACE_FIX_ATTEMPTS) {
            val weights = stepWeights(n, seed)
            pts = generateForCountSmooth(fence, excludes, n, weights, targetM, noise, seed)
            pace = paceOf(pts)
            if (pace in window) break

            val ratio = pace / paceSecKm
            val next = Math.round(n / ratio).toInt().coerceIn(2, n * 4 + 8)
            if (next == n) break
            n = next
            adjusted = true
        }

        return TrackResult(pts, pace, window, adjusted)
    }

    /**
     * 光滑模式：逐档挑内缩系数 → 铺点 → 返回得分最高的一版。
     * 得分 = （围栏内点数 − 禁跑区点数）/ 总点数。
     */
    private fun generateForCountSmooth(
        fence: List<DoubleArray>,
        excludes: List<List<DoubleArray>>,
        n: Int,
        weights: DoubleArray,
        targetM: Double,
        noise: Double,
        seed: Int
    ): List<TrackPoint> {
        var best: List<TrackPoint> = emptyList()
        var bestScore = -1.0

        for (f in doubleArrayOf(0.90, 0.85, 0.80, 0.74, 0.66, 0.58, 0.50)) {
            val ring = organicSmooth(inset(fence, f), 3.0, seed)
            val pts = buildSmooth(ring, n, weights, targetM, noise, seed, fence)
            val inside = pts.count { Geo.pointInPoly(it.lat, it.lon, fence) }
            val banned = pts.count { p -> excludes.any { Geo.pointInPoly(p.lat, p.lon, it) } }
            val score = (inside - banned).toDouble() / pts.size
            if (score > bestScore) {
                bestScore = score
                best = pts
            }
            if (banned == 0 && inside == pts.size) break
        }
        return best
    }

    /**
     * 有机化：把规则多边形磨成"人跑出来"的环线。
     *
     * v1.10.1 的光滑版本：两条整数圈正弦 + 两轮滑动平均，磨成干净的椭圆。
     * 只给「轨迹毛刺」开关关闭时用；开关打开走上面的 organic()。
     */
    private fun organicSmooth(raw: List<DoubleArray>, amp: Double, seed: Int): List<DoubleArray> {
        // 加密到 ~4 m 一个点。
        //
        // 旧值是 12 m：风华围栏最长边 53 m，12 m 一步只铺 5 个点，
        // 后续"只磨角"的平滑窗口按点数算（±2 点 = ±24 m），在长边上几乎没有
        // 作用；而两个正弦的偏移量在这几个点上差异很大，相邻点会被推出
        // 十几米 —— 实测出现 20~35 m 的一步（图上是一根拉出去又回来的长刺）。
        // 4 m 一步让偏移场在空间上连续，步长才不会被环线本身放大。
        val dense = ArrayList<DoubleArray>()
        val pts = raw + raw[0]
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val d = Geo.hav(a[0], a[1], b[0], b[1])
            val k = maxOf(1, Math.ceil(d / 4.0).toInt())
            for (j in 0 until k) {
                val t = j.toDouble() / k
                dense.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            }
        }
        val n = dense.size
        if (n < 8) return raw

        val rnd = Random(seed)
        val p1 = rnd.nextDouble(0.0, 2 * Math.PI)
        val p2 = rnd.nextDouble(0.0, 2 * Math.PI)
        val a1 = amp * rnd.nextDouble(0.5, 1.0)
        val a2 = amp * rnd.nextDouble(0.3, 0.7)

        // 偏移方向：平顺处走局部法向，**折角处走径向**。
        //
        // 为什么必须分：折角的前后点几乎反向，法向在那里没有定义，两边各自往外
        // 一掰，原本 90° 的折角就被掰成一段圆弧 —— 更糟的是偏移量在折点突变，
        // 相邻两点的间距会从一个步长直接跳到几十米。风华那条 16 顶点的围栏上
        // 实测会出现 35 m 的一步（图上是一条拉出去又拉回来的长刺）。
        val cLat = dense.map { it[0] }.average()
        val cLon = dense.map { it[1] }.average()
        var cur = ArrayList<DoubleArray>(n)
        for (i in 0 until n) {
            val prev = dense[(i - 1 + n) % n]
            val next = dense[(i + 1) % n]
            val hdg = Geo.bearing(prev[0], prev[1], next[0], next[1])
            val u = i.toDouble() / n * 2 * Math.PI
            val off = a1 * sin(2 * u + p1) + a2 * sin(3 * u + p2)
            val k = 2
            val a = dense[(i - k + n) % n]
            val c = dense[(i + k) % n]
            val turn = abs(Geo.bearing(a[0], a[1], prev[0], prev[1]) -
                    Geo.bearing(next[0], next[1], c[0], c[1]))
            var brg = (hdg + 90.0) % 360.0
            if (min(turn, 360.0 - turn) > 20.0) {
                brg = Geo.bearing(cLat, cLon, dense[i][0], dense[i][1])
            }
            cur.add(Geo.offset(dense[i][0], dense[i][1], off, brg))
        }

        repeat(2) {
            val w = 2
            val sm = ArrayList<DoubleArray>(n)
            for (i in 0 until n) {
                var sla = 0.0
                var slo = 0.0
                for (k in -w..w) {
                    val q = cur[(i + k + n) % n]
                    sla += q[0]
                    slo += q[1]
                }
                sm.add(doubleArrayOf(sla / (2 * w + 1), slo / (2 * w + 1)))
            }
            cur = sm
        }
        return cur
    }

    /**
     * 光滑模式：单个 AR(1) 低频漂移，越界点直接 clampToFence 拉回。
     * 保持 v1.10.1 的行为不变，和上面的多尺度 build() 分开。
     */
    private fun buildSmooth(
        ring: List<DoubleArray>,
        n: Int,
        weights: DoubleArray,
        targetM: Double,
        noise: Double,
        seed: Int,
        fence: List<DoubleArray> = emptyList()
    ): List<TrackPoint> {
        val (dense, cum) = densify(ring, 1.0)

        fun emit(step: Double): List<TrackPoint> {
            val rnd = Random(seed)
            val rndAcc = Random(seed * 31 + 7)
            var perr = 0.0
            var aerr = 0.0
            var travelled = 0.0

            // 第一遍：先只按权重铺出真实坐标
            val lat = DoubleArray(n)
            val lon = DoubleArray(n)
            val pew = DoubleArray(n)
            for (i in 0 until n) {
                val (p, hdg) = pointAt(dense, cum, travelled)
                perr = 0.9 * perr + rnd.nextDouble(-1.0, 1.0) * noise * 0.55
                aerr = 0.9 * aerr + rnd.nextDouble(-1.0, 1.0) * noise * 0.35
                val o1 = Geo.offset(p[0], p[1], perr, (hdg + 90.0) % 360.0)
                val o2 = Geo.offset(o1[0], o1[1], aerr, hdg)
                val c = clampToFence(o2[0], o2[1], fence)
                lat[i] = c[0]
                lon[i] = c[1]
                pew[i] = perr
                travelled += step * weights[i]
            }

            // 第二遍：speed / bearing 从**最终坐标**反推。
            // 这样上报的字段和轨迹严格自洽，不会出现"速度写着 3.0 但两点间距 1.2m"。
            val out = ArrayList<TrackPoint>(n)
            for (i in 0 until n) {
                val j = if (i < n - 1) i + 1 else i - 1
                val seg = Geo.hav(lat[i], lon[i], lat[j], lon[j])
                val brg = if (i < n - 1) Geo.bearing(lat[i], lon[i], lat[j], lon[j])
                else if (n > 1) Geo.bearing(lat[j], lon[j], lat[i], lon[i]) else 0.0

                // 精度：多数时候 4~8m，背阴 / 贴楼时偶尔跳到十几米
                val acc = 4.5 + abs(pew[i]) * 0.45 + rndAcc.nextDouble(-1.0, 2.2)

                out.add(
                    TrackPoint(
                        lat = lat[i],
                        lon = lon[i],
                        secOffset = i,
                        speed = Math.round(seg * 100.0) / 100.0,
                        bearing = Math.round(brg * 10.0) / 10.0,
                        accuracy = Math.round(acc.coerceIn(3.0, 18.0) * 10.0) / 10.0
                    )
                )
            }
            return out
        }

        fun measure(pts: List<TrackPoint>): Double = distanceOf(pts)

        // 噪声按秒注入、与步长无关，会把实测距离撑长。二分反解地面步长，
        // 让输出轨迹的实测距离 == targetM，配速才准。
        if (measure(emit(0.0)) >= targetM) return emit(0.0)

        var lo = 0.0
        var hi = 5.0                       // 5 m/s 起步，2000m 的量级足够越过目标
        while (measure(emit(hi)) < targetM && hi < 1e5) hi *= 1.5
        repeat(44) {
            val mid = (lo + hi) / 2.0
            if (measure(emit(mid)) < targetM) lo = mid else hi = mid
        }
        return emit((lo + hi) / 2.0)
    }

    // ------------------------------------------------------------ 统计

    /**
     * 结果统计。配速区间按 15 秒滚动窗口算 —— 逐秒的瞬时配速在停顿处会趋于无穷，
     * 直接取极值没有意义，滚动窗口才对应"跑者感受到的配速"。
     */
    fun summarize(pts: List<TrackPoint>): String {
        val d = distanceOf(pts)
        val dur = pts.size - 1
        val pace = if (d > 0) dur / (d / 1000.0) else 0.0

        val (lo, hi) = windowPaceRange(pts, 15)
        val spread = if (lo > 0 && hi > 0) " | 分段配速 ${fmtPace(lo)}~${fmtPace(hi)}" else ""
        return "点数 ${pts.size} | 距离 ${"%.1f".format(d)} m | 时长 ${dur / 60}分${dur % 60}秒 | " +
                "实测配速 ${fmtPace(pace)}$spread"
    }

    /** 15 秒滚动窗口的配速 (最快, 最慢)，秒/公里。 */
    fun windowPaceRange(pts: List<TrackPoint>, win: Int): Pair<Double, Double> {
        if (pts.size <= win + 1) return Pair(0.0, 0.0)
        var fastest = Double.MAX_VALUE
        var slowest = 0.0
        for (i in 0..pts.size - 1 - win) {
            var seg = 0.0
            for (k in i until i + win) {
                seg += Geo.hav(pts[k].lat, pts[k].lon, pts[k + 1].lat, pts[k + 1].lon)
            }
            if (seg <= 0.1) continue
            val p = win / (seg / 1000.0)
            if (p < fastest) fastest = p
            if (p > slowest) slowest = p
        }
        if (fastest == Double.MAX_VALUE) return Pair(0.0, 0.0)
        return Pair(fastest, slowest)
    }

    fun fmtPace(secPerKm: Double): String {
        if (secPerKm <= 0 || secPerKm > 3600) return "--"
        val m = (secPerKm / 60).toInt()
        val s = (secPerKm % 60).toInt()
        return "$m'${"%02d".format(s)}\""
    }
}
