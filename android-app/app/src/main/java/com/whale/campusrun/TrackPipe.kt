/*
 * 轨迹流水线（TrackGen 的「毛刺」分支）：① 固定内轨迹 → ② 逐圈不重合
 * → ③ 波动 + 噪声 → ④ 把压进禁区的点挤出去。参数按场地（placeCode）选择。
 *
 * 为什么单独提一个文件：这套逻辑是 `refs/track_gen.py` 的 1:1 镜像，
 * 阶段之间只靠数据接口相连，每一段都能单独读、单独测；全塞回 TrackGen
 * 会和配速标定、光滑模式糊成一片。
 */
package com.whale.campusrun

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 场地参数档案。所有数值的依据见 `refs/track_gen.py` 的 FIELD_PROFILE 注释。 */
internal data class FieldProfile(
    val placeCode: String,
    /** ② 单圈横向场基准幅度（米）。 */
    val lapWobbleM: Double,
    /** ② 局部鼓包幅度区间（米）。 */
    val lapBumpAmp: ClosedFloatingPointRange<Double>,
    /** ② 圈间距下限（米）：达不到就重抽这套相位。 */
    val lapMinSepM: Double,
    /** ③ 底噪系数。 */
    val noiseScale: Double,
    /** ③ 野点强度系数。 */
    val spurScale: Double,
    /** ③ 四层 AR(1) 的相对权重（慢 → 快）。 */
    val noiseLayers: DoubleArray,
    /** ③ 对数速度波动的平稳标准差系数。 */
    val paceLogScale: Double,
    /** ③ 停顿密度系数。 */
    val microStallScale: Double,
    val longStallScale: Double,
    /** ① 圆角半径（米）；0 = 直角。 */
    val cornerM: Double,
    /** ② 横向场平滑窗口（米）。 */
    val lapSmoothM: Double,
    /** ② 参与叠加的整数圈谐波。 */
    val lapHarmonics: IntArray,
    /** ② 是否按"边"做恒定偏移（矩形跑道用），而不是整圈正弦。 */
    val lapSegmented: Boolean,
    /** ② 分段偏移幅度 / 正交分量 / 过渡宽度（米）。 */
    val lapSegAmp: Double,
    val lapSegCross: Double,
    val lapSegFadeM: Double,
    /** 每圈整体内缩微调比例。 */
    val lapShrink: Double,
    /** ① 环线形状："polygon"（长短轴缩放）/ "axis"（轴向矩形）/ "rect"（最小外接矩形比例）。 */
    val ringShape: String = "polygon",
    /** 轴向矩形：沿 东西(u) / 南北(v) 的比例与平移（米）。 */
    val axisFracU: Double = 0.55,
    val axisFracV: Double = 0.70,
    val axisShiftU: Double = 0.0,
    val axisShiftV: Double = 0.0,
    /** 最小外接矩形比例（rect 形状）。 */
    val rectFracU: Double = 0.77,
    val rectFracV: Double = 0.56,
    val rectShiftU: Double = 0.0,
    val rectShiftV: Double = 0.0,
    /** ① 走廊几何：未知场地按走廊宽度推导幅度。 */
    val corridorM: Double,
    val corridorScale: Double,
    /** ② 环线离禁区的额外余量（米）。 */
    val ringExclMarginM: Double,
    /** 手点内轨迹（可为空）：有就不再自动反解。 */
    val pickedRing: List<DoubleArray>?
)

internal object TrackPipe {

    const val RING_STEP_M = 7.0

    /** 谐波 → 权重。次数越高、把直角揉得越圆，所以按场地挑。 */
    private val HARMONIC_WEIGHT = mapOf(
        1 to 0.55, 2 to 0.45, 3 to 0.34, 4 to 0.24, 5 to 0.16, 7 to 0.10
    )

    private val LAP_BUMP_COUNT = 2..4
    private val LAP_BUMP_WIDTH_M = 10.0..40.0

    /** 挤出禁区：扫一圈方向取最短合法出路。 */
    private const val EXIT_DIR_STEP_DEG = 6
    private const val EXIT_MARGIN_M = 1.2
    private const val EXIT_MAX_PUSH_M = 45.0

    private const val REF_CORRIDOR_M = 15.0
    private const val LAP_MAX_TRIES = 48

    /**
     * 手点内轨迹的资源路径。
     *
     * 宁静苑是矩形跑道，服务端给的却是切了一角的五边形 —— "四条边到底落在哪"
     * 最终是看一眼真实轨迹图就能定的事，人点四下比推一堆比例系数更可靠。
     * `refs/track_picker.py` 导出的 `refs/inner_rings.json` 会同步到
     * `app/src/main/assets/inner_rings.json` 一起打包。
     *
     * 用 classpath 读而不是 Context：纯 JVM 单测里没有 Context，
     * 而 AssetManager 在单测里拿不到 —— classpath 读法两边都能用。
     */
    private const val RINGS_ASSET = "/inner_rings.json"

    @Volatile
    private var ringsCache: Map<String, List<DoubleArray>>? = null

    /**
     * 极简解析：只认 {"CODE": {"ring": [[lat,lon], ...]}} 这一层。
     *
     * 不用 org.json —— AGP 的 JVM 单测挂的是 mockable android.jar，
     * 它的每个方法都是 throw RuntimeException("Stub!")，用 JSONObject 解析
     * 等于"这段逻辑永远测不了"（同一个坑 AGENT.md 里记过一次）。
     */
    private fun parseRings(text: String): List<Pair<String, List<DoubleArray>>> {
        val out = ArrayList<Pair<String, List<DoubleArray>>>()
        val numRe = Regex("(-?\\d+(?:\\.\\d+)?)")
        // 每个场地一段：从 "CODE" 到下一个 "CODE" 之前
        val codeRe = Regex("\"([A-Za-z0-9_]{2,})\"\\s*:\\s*\\{")
        val marks = codeRe.findAll(text).toList()
        for ((i, m) in marks.withIndex()) {
            val code = m.groupValues[1]
            if (code == "ring" || code == "field" || code == "note" || code == "savedAt") continue
            val segStart = m.range.last + 1
            val segEnd = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
            val seg = text.substring(segStart, segEnd)
            // 只取 "ring" 之后的数字对
            val ringIdx = seg.indexOf("\"ring\"")
            if (ringIdx < 0) continue
            val body = seg.substring(ringIdx)
            val coordText = body.substringBefore("\"savedAt\"").substringBeforeLast("}")
            val nums = numRe.findAll(coordText).map { it.groupValues[1].toDouble() }.toList()
            val pts = ArrayList<DoubleArray>()
            var k = 0
            while (k + 1 < nums.size) {
                pts.add(doubleArrayOf(nums[k], nums[k + 1]))
                k += 2
            }
            if (pts.size >= 3) out.add(code to pts)
        }
        return out
    }

    /** 读手点内轨迹（{placeCode: [ [lat,lon], ... ]}）。读不到就当没有。 */
    private fun pickedRings(): Map<String, List<DoubleArray>> {
        ringsCache?.let { return it }
        val out = HashMap<String, List<DoubleArray>>()
        try {
            val stream = TrackPipe::class.java.getResourceAsStream(RINGS_ASSET)
            if (stream != null) {
                val text = stream.bufferedReader().use { it.readText() }
                // 不用 org.json：AGP 的 JVM 单测挂的是 mockable android.jar，
                // 它的每个方法都是 throw RuntimeException("Stub!")，
                // 用 JSONObject 解析等于"这段逻辑永远测不了"。
                // 这里只需要 "placeCode → 若干 [lat,lon]" 一层结构，正则足够。
                for ((code, ringText) in parseRings(text)) out[code] = ringText
            }
        } catch (t: Throwable) {
            // 资源缺失 / JSON 坏了：当成没有手点环，走自动反解。
            // 这里不抛 —— 轨迹不能因为一个可选资源坏了就生不出来。
        }
        ringsCache = out
        return out
    }

    // ------------------------------------------------------------ 场地档案

    /**
     * 场地参数。三种场地各一套，数值全部来自 `refs/real_tracks` 下的 GPX 的实测。
     *
     * 为什么必须按场地分：走廊宽度（内轨迹到围栏/禁区）差 4 倍 ——
     * 风华 7.9 m、太极 13.3 m、宁静苑 34.7 m。同一套幅度，在宁静苑是
     * "这圈贴内道、下圈贴外道"，在风华就是"一路顶在围栏上"。
     */
    private fun knownProfile(code: String): FieldProfile? = when (code) {
        // 宁静苑：矩形跑道、无禁区、走廊最宽。真实偏移最"粗"、跳点最多。
        "T1014" -> FieldProfile(
            placeCode = code,
            lapWobbleM = 4.2,
            lapBumpAmp = 2.5..5.5,
            lapMinSepM = 2.0,
            noiseScale = 1.35,
            spurScale = 1.25,
            noiseLayers = doubleArrayOf(1.30, 1.20, 1.05, 1.10),
            paceLogScale = 1.05,
            microStallScale = 1.00,
            longStallScale = 1.00,
            cornerM = 0.0,                 // 直角：矩形跑道不能磨圆
            lapSmoothM = 22.0,
            lapHarmonics = intArrayOf(1, 2, 3),
            lapSegmented = true,
            lapSegAmp = 6.0,
            lapSegCross = 0.0,             // 正交分量会让直道变"弓形"
            lapSegFadeM = 60.0,
            lapShrink = 0.012,
            ringShape = "rect", rectFracU = 0.617, rectFracV = 0.531,
            rectShiftU = -7.0, rectShiftV = -11.0,
            corridorM = 34.7,
            corridorScale = 2.0,
            ringExclMarginM = 0.0,
            pickedRing = null
        )
        // 风华：走廊只有 7.9 m（最窄），禁区占内场 27.6%，真实偏移最"细"。
        // 但它的**停顿**最多（真实 21 段 / 最长 21 秒）。
        "T1001" -> FieldProfile(
            placeCode = code,
            lapWobbleM = 2.6,
            lapBumpAmp = 1.2..2.8,
            lapMinSepM = 0.55,
            noiseScale = 0.55,
            spurScale = 0.75,
            noiseLayers = doubleArrayOf(0.90, 1.00, 1.15, 1.30),
            paceLogScale = 0.85,
            microStallScale = 1.35,
            longStallScale = 1.00,
            cornerM = 40.0,
            lapSmoothM = 12.0,
            lapHarmonics = intArrayOf(1, 2, 3, 5, 7),
            lapSegmented = false,
            lapSegAmp = 0.0,
            lapSegCross = 0.0,
            lapSegFadeM = 26.0,
            lapShrink = 0.012,
            corridorM = 7.9,
            corridorScale = 0.53,
            ringExclMarginM = 0.0,
            pickedRing = null
        )
        // 太极：禁区只贴环线一侧（到禁区 13.9 m，到围栏还有 39 m）。
        // 真实记录最干净：步长 CV 0.29、跳点只 2 簇、转角 p90 14.8°。
        "T1005" -> FieldProfile(
            placeCode = code,
            lapWobbleM = 3.4,
            lapBumpAmp = 1.6..3.4,
            lapMinSepM = 0.9,
            noiseScale = 0.70,
            spurScale = 0.45,
            noiseLayers = doubleArrayOf(1.05, 0.90, 0.70, 0.50),
            paceLogScale = 0.72,
            microStallScale = 0.85,
            longStallScale = 0.70,
            cornerM = 35.0,
            lapSmoothM = 14.0,
            lapHarmonics = intArrayOf(1, 2, 3, 5),
            lapSegmented = false,
            lapSegAmp = 0.0,
            lapSegCross = 0.0,
            lapSegFadeM = 26.0,
            lapShrink = 0.012,
            ringShape = "axis", axisFracU = 0.530, axisFracV = 0.698,
            axisShiftU = -6.0, axisShiftV = 6.6,
            corridorM = 13.3,
            corridorScale = 0.89,
            ringExclMarginM = 3.0,         // 贴禁区那一侧留绕行余量
            pickedRing = null
        )
        else -> null
    }

    /** 未知场地：按走廊宽度推导，幅度全线按 corridor/15 缩放。 */
    private fun autoProfile(code: String, corridorM: Double): FieldProfile {
        val s = corridorM / REF_CORRIDOR_M
        val scale = max(0.25, min(2.0, s))
        return FieldProfile(
            placeCode = code,
            lapWobbleM = 4.0 * scale,
            lapBumpAmp = (2.0 * scale)..(5.0 * scale),
            lapMinSepM = 1.4 * scale,
            noiseScale = 0.85 * scale,
            spurScale = 0.85 * scale,
            noiseLayers = doubleArrayOf(1.0, 1.0, 1.0, 1.0),
            paceLogScale = 0.90,
            microStallScale = 1.00,
            longStallScale = 1.00,
            cornerM = 25.0,
            lapSmoothM = 14.0,
            lapHarmonics = intArrayOf(1, 2, 3, 5),
            lapSegmented = false,
            lapSegAmp = 0.0,
            lapSegCross = 0.0,
            lapSegFadeM = 26.0,
            lapShrink = 0.012,
            corridorM = corridorM,
            corridorScale = scale,
            ringExclMarginM = 0.0,
            pickedRing = null
        )
    }

    /** 取场地档案；未知场地需要先量走廊。 */
    fun profileFor(placeCode: String?, fence: List<DoubleArray>): FieldProfile {
        val code = placeCode.orEmpty()
        val picked = pickedRings()[code]
        knownProfile(code)?.let { base ->
            return if (picked == null) base else base.copy(pickedRing = picked)
        }
        // 未知场地：先按保守参数铺一条环线量走廊，再据此推幅度
        val probe = autoProfile(code, REF_CORRIDOR_M)
        val ring = innerTrack(fence, emptyList(), probe)
        return autoProfile(code, corridorWidth(ring, fence))
    }

    // ------------------------------------------------------------ ① 固定内轨迹

    /** 强制逆时针：真实记录全部逆时针，服务端顶点序可顺可逆。 */
    private fun ccw(poly: List<DoubleArray>): List<DoubleArray> =
        if (signedArea(poly) >= 0) poly else poly.reversed()

    private fun signedArea(poly: List<DoubleArray>): Double {
        if (poly.size < 3) return 0.0
        val k = cos(Math.toRadians(poly.map { it[0] }.average()))
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            s += (a[1] * k) * b[0] - (b[1] * k) * a[0]
        }
        return s / 2.0
    }

    /** 点到多边形边界的最短距离（米）。 */
    private fun edgeDist(p: DoubleArray, poly: List<DoubleArray>): Double {
        val k = cos(Math.toRadians(p[0]))
        var best = Double.MAX_VALUE
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val ax = a[1] * k; val ay = a[0]
            val bx = b[1] * k; val by = b[0]
            val px = p[1] * k; val py = p[0]
            val dx = bx - ax; val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len))
            best = min(best, Geo.hav(py, px / k, ay + t * dy, (ax + t * dx) / k))
        }
        return best
    }

    /** 可用走廊：内轨迹到围栏、到最近禁区的距离中位取小者。 */
    fun corridorWidth(base: List<DoubleArray>, fence: List<DoubleArray>,
                      excludes: List<List<DoubleArray>> = emptyList()): Double {
        val toFence = base.map { edgeDist(it, fence) }.sorted()
        val medFence = toFence[toFence.size / 2]
        if (excludes.isEmpty()) return medFence
        val toExc = base.map { p -> excludes.minOf { edgeDist(p, it) } }.sorted()
        return min(medFence, toExc[toExc.size / 2])
    }

    /**
     * 只磨角、不动直道。
     *
     * 不能整圈平滑：围栏是带尖角的多边形，整圈滑动平均会把长直道一起磨弯
     * （宁静苑直道占比会掉到 0，画出来像鸭蛋）。真实记录是"长直道 + 中等圆角"。
     */
    private fun roundCorners(ring: List<DoubleArray>, cornerM: Double): List<DoubleArray> {
        val n = ring.size
        if (cornerM <= 0.0 || n < 16) return ring
        var per = 0.0
        for (i in 0 until n) {
            val a = ring[i]
            val b = ring[(i + 1) % n]
            per += Geo.hav(a[0], a[1], b[0], b[1])
        }
        val stepM = max(1.0, per / n)
        val corners = cornerMask(ring, stepM)
        if (corners.none { it }) return ring
        val win = max(1, Math.round(cornerM / stepM / 2).toInt())
        var cur = ring
        repeat(3) {
            val src = cur
            cur = List(n) { i ->
                if (!corners[i]) src[i] else {
                    var la = 0.0
                    var lo = 0.0
                    for (k in -win..win) {
                        val q = src[(i + k + n) % n]
                        la += q[0]; lo += q[1]
                    }
                    val span = (2 * win + 1).toDouble()
                    doubleArrayOf(la / span, lo / span)
                }
            }
        }
        return cur
    }

    /** 转角超过阈值的位置。整圈都在转（圆/椭圆）时返回全 false，交给别的路径。 */
    private fun cornerMask(ring: List<DoubleArray>, stepM: Double, turnDeg: Double = 12.0): List<Boolean> {
        val n = ring.size
        val k = 3
        val flags = BooleanArray(n)
        var turning = 0
        for (i in 0 until n) {
            val a = ring[(i - k + n) % n]
            val b = ring[i]
            val c = ring[(i + k) % n]
            val d = abs(Geo.bearing(a[0], a[1], b[0], b[1]) - Geo.bearing(b[0], b[1], c[0], c[1]))
            if (min(d, 360.0 - d) > turnDeg) {
                flags[i] = true
                turning++
            }
        }
        if (turning > n * 0.55) return List(n) { false }
        val spread = max(1, Math.round(22.0 / max(stepM, 0.5)).toInt())
        val out = BooleanArray(n)
        for (i in 0 until n) {
            if (flags[i]) {
                for (kk in -spread..spread) out[(i + kk + n) % n] = true
            }
        }
        return out.toList()
    }

    /**
     * ① 固定内轨迹：一条**与 seed 无关**的基准环（加密到 ~7 m，逆时针）。
     *
     * 为什么要"固定"：它是 ②③ 的公共坐标系。每圈的差异应该来自 ② 显式叠上去的
     * 横向场，而不是"环线每次重画"这种副作用 —— 否则调噪声和调圈间距就分不开。
     */
    fun innerTrack(fence: List<DoubleArray>, excludes: List<List<DoubleArray>>,
                   prof: FieldProfile): List<DoubleArray> {
        val ring = innerPolyline(fence, prof)
        val dense = densify(ccw(ring), RING_STEP_M)
        val rounded = roundCorners(dense, prof.cornerM)
        // 圆角化会挪动几厘米，收一下确保基准环自身合法
        return rounded.mapIndexed { i, p ->
            if (isValid(p[0], p[1], fence, excludes)) p
            else pullToward(dense[i], p, fence, excludes)
        }
    }

    // ------------------------------------------------------------ ① 环线形状

    /** 围栏在某朝向帧下的 (lat0, lon0, k, deg, uMin, uMax, vMin, vMax)。 */
    private class Frame(val lat0: Double, val lon0: Double, val k: Double, val deg: Double,
                        val u0: Double, val u1: Double, val v0: Double, val v1: Double)

    private fun rectFrame(fence: List<DoubleArray>): Frame {
        val lat0 = fence.map { it[0] }.average()
        val lon0 = fence.map { it[1] }.average()
        val k = cos(Math.toRadians(lat0))
        var bestDeg = 0.0
        var bestArea = Double.MAX_VALUE
        for (deg in 0 until 180) {
            val c = cos(Math.toRadians(deg.toDouble()))
            val sn = sin(Math.toRadians(deg.toDouble()))
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (q in fence) {
                val x = (q[1] - lon0) * k * 111320.0
                val y = (q[0] - lat0) * 110540.0
                val u = x * c + y * sn
                val v = -x * sn + y * c
                if (u < minU) minU = u; if (u > maxU) maxU = u
                if (v < minV) minV = v; if (v > maxV) maxV = v
            }
            if ((maxU - minU) * (maxV - minV) < bestArea) {
                bestArea = (maxU - minU) * (maxV - minV); bestDeg = deg.toDouble()
            }
        }
        val c = cos(Math.toRadians(bestDeg))
        val sn = sin(Math.toRadians(bestDeg))
        var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
        for (q in fence) {
            val x = (q[1] - lon0) * k * 111320.0
            val y = (q[0] - lat0) * 110540.0
            val u = x * c + y * sn
            val v = -x * sn + y * c
            if (u < minU) minU = u; if (u > maxU) maxU = u
            if (v < minV) minV = v; if (v > maxV) maxV = v
        }
        return Frame(lat0, lon0, k, bestDeg, minU, maxU, minV, maxV)
    }

    private fun fromUv(u: Double, v: Double, f: Frame): DoubleArray {
        val c = cos(Math.toRadians(f.deg))
        val sn = sin(Math.toRadians(f.deg))
        val x = u * c - v * sn
        val y = u * sn + v * c
        return doubleArrayOf(f.lat0 + y / 110540.0, f.lon0 + x / (f.k * 111320.0))
    }

    /**
     * 轴向矩形：按围栏自身帧的两个比例直接构造一个矩形环。
     *
     * 为什么太极需要这条路径：真实记录的纵向（南北）比横向长得多（158 : 90），
     * 而 `scaleAxes` 认的是"最小外接矩形的长边/短边"，太极那个矩形的朝向是 2°，
     * 纵向只能到 137.5 —— 比真实短 16%，图上就是"被压扁了一圈"。
     * 这里的两个比例是二分反解出来的（0.530 / 0.698），让生成的 东西/南北
     * 恰好命中真实的 89.8 / 158.1。
     */
    private fun axisRing(fence: List<DoubleArray>, prof: FieldProfile): List<DoubleArray> {
        val f = rectFrame(fence)
        val cu = (f.u0 + f.u1) / 2.0 + prof.axisShiftU
        val cv = (f.v0 + f.v1) / 2.0 + prof.axisShiftV
        val hu = (f.u1 - f.u0) / 2.0 * prof.axisFracU
        val hv = (f.v1 - f.v0) / 2.0 * prof.axisFracV
        return listOf(
            fromUv(cu - hu, cv - hv, f), fromUv(cu + hu, cv - hv, f),
            fromUv(cu + hu, cv + hv, f), fromUv(cu - hu, cv + hv, f)
        )
    }

    /** 最小外接矩形比例缩放（宁静苑这种矩形跑道）。 */
    private fun rectRing(fence: List<DoubleArray>, prof: FieldProfile): List<DoubleArray> {
        val f = rectFrame(fence)
        val cu = (f.u0 + f.u1) / 2.0 + prof.rectShiftU
        val cv = (f.v0 + f.v1) / 2.0 + prof.rectShiftV
        val hu = (f.u1 - f.u0) / 2.0 * prof.rectFracU
        val hv = (f.v1 - f.v0) / 2.0 * prof.rectFracV
        return listOf(
            fromUv(cu - hu, cv - hv, f), fromUv(cu + hu, cv - hv, f),
            fromUv(cu + hu, cv + hv, f), fromUv(cu - hu, cv + hv, f)
        )
    }

    /** 围栏 → 环线折线（不含随机性）。 */
    private fun innerPolyline(fence: List<DoubleArray>, prof: FieldProfile): List<DoubleArray> {
        prof.pickedRing?.let { return ccw(it) }
        // 形状路径优先：矩形 / 轴向矩形都直接构造四条边，不走长短轴缩放
        when (prof.ringShape) {
            "axis" -> {
                val ring = axisRing(fence, prof)
                if (badPoints(ring, fence, emptyList()) == 0) return ring
                return ring
            }
            "rect" -> {
                var ring = rectRing(fence, prof)
                if (badPoints(ring, fence, emptyList()) == 0) return ring
                for (k in listOf(0.97, 0.94, 0.90, 0.86, 0.82, 0.78, 0.72, 0.66)) {
                    ring = rectRing(fence, FieldProfile(
                        placeCode = prof.placeCode, lapWobbleM = prof.lapWobbleM,
                        lapBumpAmp = prof.lapBumpAmp, lapMinSepM = prof.lapMinSepM,
                        noiseScale = prof.noiseScale, spurScale = prof.spurScale,
                        noiseLayers = prof.noiseLayers, paceLogScale = prof.paceLogScale,
                        microStallScale = prof.microStallScale,
                        longStallScale = prof.longStallScale, cornerM = prof.cornerM,
                        lapSmoothM = prof.lapSmoothM, lapHarmonics = prof.lapHarmonics,
                        lapSegmented = prof.lapSegmented, lapSegAmp = prof.lapSegAmp,
                        lapSegCross = prof.lapSegCross, lapSegFadeM = prof.lapSegFadeM,
                        lapShrink = prof.lapShrink,
                        ringShape = prof.ringShape,
                        axisFracU = prof.axisFracU, axisFracV = prof.axisFracV,
                        axisShiftU = prof.axisShiftU, axisShiftV = prof.axisShiftV,
                        rectFracU = prof.rectFracU * k, rectFracV = prof.rectFracV * k,
                        rectShiftU = prof.rectShiftU, rectShiftV = prof.rectShiftV,
                        corridorM = prof.corridorM, corridorScale = prof.corridorScale,
                        ringExclMarginM = prof.ringExclMarginM,
                        pickedRing = prof.pickedRing
                    ))
                    if (badPoints(ring, fence, emptyList()) == 0) return ring
                }
                return ring
            }
        }
        val params = FieldTuning.of(prof.placeCode, fence)
        if (params != null) {
            val m = 1.0
            var ring = scaleAxes(fence, params.kLong, params.kShort, params.shiftLat, params.shiftLon)
            if (badPoints(ring, fence, emptyList()) == 0) return ring
            var f = 1.0
            while (f < 1.6) {
                f = min(1.6, f + 0.02)
                ring = scaleAxes(fence, params.kLong * f, params.kShort * f,
                    params.shiftLat * f, params.shiftLon * f)
                if (f >= 1.0 && badPoints(ring, fence, emptyList()) == 0) break
            }
            return ring
        }
        // 通用兜底：按半径比例内缩
        var f = 0.55
        var ring = inset(fence, f)
        while (f < 0.999 && badPoints(ring, fence, emptyList()) > 0) {
            f = min(0.999, f + 0.02)
            ring = inset(fence, f)
        }
        return ring
    }

    private fun inset(poly: List<DoubleArray>, f: Double): List<DoubleArray> {
        val cLat = poly.map { it[0] }.average()
        val cLon = poly.map { it[1] }.average()
        return poly.map { doubleArrayOf(cLat + (it[0] - cLat) * f, cLon + (it[1] - cLon) * f) }
    }

    /**
     * 沿围栏**自身最小外接矩形**的长短轴各缩放一次（再叠平移）。
     *
     * 关键：必须按最小外接矩形的朝向投影，不能直接拿"东西 / 南北"当长短轴。
     * 宁静苑的围栏是 108(东西) × 195(南北)，但它的最小外接矩形转到了 89°，
     * 也就是"长边"其实沿着南北 —— 直接按 x/y 极值判断会把 k_long / k_short
     * 张冠李戴，画出来是东西 113 m、南北 40 m 的横躺矩形（应当反过来）。
     */
    private fun scaleAxes(poly: List<DoubleArray>, kLong: Double, kShort: Double,
                          shiftLat: Double, shiftLon: Double): List<DoubleArray> {
        val lat0 = poly.map { it[0] }.average()
        val lon0 = poly.map { it[1] }.average()
        val k = cos(Math.toRadians(lat0))

        // 0..179° 逐度找最小面积外接矩形
        var bestDeg = 0.0
        var bestArea = Double.MAX_VALUE
        var bestW = 0.0
        var bestH = 0.0
        for (deg in 0 until 180) {
            val c = cos(Math.toRadians(deg.toDouble()))
            val sn = sin(Math.toRadians(deg.toDouble()))
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (q in poly) {
                val x = (q[1] - lon0) * k * 111320.0
                val y = (q[0] - lat0) * 110540.0
                val u = x * c + y * sn
                val v = -x * sn + y * c
                if (u < minU) minU = u; if (u > maxU) maxU = u
                if (v < minV) minV = v; if (v > maxV) maxV = v
            }
            val w = maxU - minU
            val h = maxV - minV
            if (w * h < bestArea) {
                bestArea = w * h; bestDeg = deg.toDouble(); bestW = w; bestH = h
            }
        }
        val c = cos(Math.toRadians(bestDeg))
        val sn = sin(Math.toRadians(bestDeg))
        val alongU = if (bestW >= bestH) kLong else kShort
        val alongV = if (bestW >= bestH) kShort else kLong
        return poly.map {
            val x = (it[1] - lon0) * k * 111320.0
            val y = (it[0] - lat0) * 110540.0
            val u = (x * c + y * sn) * alongU
            val v = (-x * sn + y * c) * alongV
            val x2 = u * c - v * sn + shiftLon
            val y2 = u * sn + v * c + shiftLat
            doubleArrayOf(lat0 + y2 / 110540.0, lon0 + x2 / (k * 111320.0))
        }
    }

    // ------------------------------------------------------------ 几何工具

    fun densify(ring: List<DoubleArray>, stepM: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val pts = ring + ring[0]
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val d = Geo.hav(a[0], a[1], b[0], b[1])
            val k = max(1, Math.ceil(d / stepM).toInt())
            for (j in 0 until k) {
                val t = j.toDouble() / k
                out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            }
        }
        return out
    }

    fun cumulative(dense: List<DoubleArray>): DoubleArray {
        val cum = DoubleArray(dense.size + 1)
        for (i in dense.indices) {
            val a = dense[i]
            val b = if (i == dense.size - 1) dense[0] else dense[i + 1]
            cum[i + 1] = cum[i] + Geo.hav(a[0], a[1], b[0], b[1])
        }
        return cum
    }

    fun pointAt(dense: List<DoubleArray>, cum: DoubleArray, dist: Double): Pair<DoubleArray, Double> {
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
        val a = dense[i]; val b = dense[i + 1]
        val p = doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        return Pair(p, Geo.bearing(a[0], a[1], b[0], b[1]))
    }

    fun isValid(lat: Double, lon: Double, fence: List<DoubleArray>,
                excludes: List<List<DoubleArray>>): Boolean {
        if (fence.isNotEmpty() && !Geo.pointInPoly(lat, lon, fence)) return false
        for (e in excludes) if (Geo.pointInPoly(lat, lon, e)) return false
        return true
    }

    fun badPoints(poly: List<DoubleArray>, fence: List<DoubleArray>,
                  excludes: List<List<DoubleArray>>): Int {
        var bad = 0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val d = Geo.hav(a[0], a[1], b[0], b[1])
            val k = max(1, Math.ceil(d / RING_STEP_M).toInt())
            for (j in 0 until k) {
                val t = j.toDouble() / k
                if (!isValid(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, fence, excludes)) bad++
            }
        }
        return bad
    }

    /** 沿 base → p 二分回缩，只削扰动、不动基准环。 */
    fun pullToward(base: DoubleArray, p: DoubleArray, fence: List<DoubleArray>,
                   excludes: List<List<DoubleArray>>): DoubleArray {
        if (!isValid(base[0], base[1], fence, excludes)) return p
        var lo = 0.0
        var hi = 1.0
        repeat(22) {
            val mid = (lo + hi) / 2.0
            if (isValid(base[0] + (p[0] - base[0]) * mid, base[1] + (p[1] - base[1]) * mid,
                    fence, excludes)) lo = mid else hi = mid
        }
        val t = (lo * 0.99).coerceIn(0.0, 1.0)
        return doubleArrayOf(base[0] + (p[0] - base[0]) * t, base[1] + (p[1] - base[1]) * t)
    }

    /** 沿"点 → 围栏质心"二分拉回围栏内。 */
    fun clampToFence(lat: Double, lon: Double, fence: List<DoubleArray>): DoubleArray {
        if (fence.size < 3 || Geo.pointInPoly(lat, lon, fence)) return doubleArrayOf(lat, lon)
        val cLat = fence.map { it[0] }.average()
        val cLon = fence.map { it[1] }.average()
        if (!Geo.pointInPoly(cLat, cLon, fence)) return doubleArrayOf(lat, lon)
        var lo = 0.0
        var hi = 1.0
        repeat(24) {
            val mid = (lo + hi) / 2.0
            if (Geo.pointInPoly(cLat + (lat - cLat) * mid, cLon + (lon - cLon) * mid, fence)) lo = mid else hi = mid
        }
        val t = (lo * 0.995).coerceIn(0.0, 1.0)
        return doubleArrayOf(cLat + (lat - cLat) * t, cLon + (lon - cLon) * t)
    }

    /** 把一个不合法点救回"围栏内且不进禁区"。找不到返回 null。 */
    fun rescuePoint(p: DoubleArray, fence: List<DoubleArray>,
                    excludes: List<List<DoubleArray>>): DoubleArray? {
        var q = clampToFence(p[0], p[1], fence)
        repeat(8) {
            val hit = excludes.firstOrNull { Geo.pointInPoly(q[0], q[1], it) } ?: run {
                return if (isValid(q[0], q[1], fence, excludes)) q else null
            }
            val cLat = hit.map { it[0] }.average()
            val cLon = hit.map { it[1] }.average()
            val brg = Geo.bearing(cLat, cLon, q[0], q[1])
            val o = Geo.offset(q[0], q[1], 2.5, brg)
            q = doubleArrayOf(o[0], o[1])
            if (!Geo.pointInPoly(q[0], q[1], fence)) return null
        }
        return if (isValid(q[0], q[1], fence, excludes)) q else null
    }

    /** 环形滑动平均（按点数）。 */
    private fun circSmooth(vals: DoubleArray, win: Int): DoubleArray {
        val n = vals.size
        if (win <= 0) return vals.copyOf()
        if (n <= 2 * win + 1) {
            val mean = vals.average()
            return DoubleArray(n) { mean }
        }
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + vals[i]
        val total = pre[n]
        val span = (2 * win + 1).toDouble()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val lo = i - win
            val hi = i + win + 1
            out[i] = when {
                lo >= 0 && hi <= n -> (pre[hi] - pre[lo]) / span
                lo < 0 -> ((total - pre[lo + n]) + pre[hi]) / span
                else -> ((total - pre[lo]) + pre[hi - n]) / span
            }
        }
        return out
    }

    private fun smoothRing(poly: List<DoubleArray>, winM: Double): List<DoubleArray> {
        val n = poly.size
        val win = max(1, Math.round(winM).toInt())
        val span = 2 * win + 1
        if (n <= span) return poly
        return List(n) { i ->
            var la = 0.0
            var lo = 0.0
            for (k in -win..win) {
                val q = poly[(i + k + n) % n]
                la += q[0]; lo += q[1]
            }
            doubleArrayOf(la / span, lo / span)
        }
    }

    // ------------------------------------------------------------ ② 逐圈不重合

    /** 按拐角切段：矩形 → 四条边。加密后的环有几十个点，不能按顶点切。 */
    private fun cornerSegments(ring: List<DoubleArray>, turnDeg: Double = 30.0,
                              mergeM: Double = 6.0): List<ClosedFloatingPointRange<Double>>? {
        val n = ring.size
        if (n < 3) return null
        val cum = cumulative(ring)
        val total = cum[n]
        if (total <= 0) return null
        val corners = ArrayList<Int>()
        for (i in 0 until n) {
            val a = ring[(i - 1 + n) % n]; val b = ring[i]; val c = ring[(i + 1) % n]
            val d = abs(Geo.bearing(a[0], a[1], b[0], b[1]) - Geo.bearing(b[0], b[1], c[0], c[1]))
            if (min(d, 360.0 - d) > turnDeg) corners.add(i)
        }
        if (corners.size < 3) return null
        val start = corners[0]
        val order = corners.drop(1) + (corners[0] + n)
        val bounds = ArrayList<Double>()
        bounds.add(cum[start] / total)
        for (idx in order) bounds.add(min(1.0, if (idx < n) cum[idx] / total else 1.0))
        val segs = ArrayList<ClosedFloatingPointRange<Double>>()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]; val b = bounds[i + 1]
            if ((b - a) * total >= mergeM) segs.add(a..b)
        }
        return if (segs.size >= 3) segs else null
    }

    /** 转角阈值切段（非矩形场地用）。 */
    private fun ringSegments(ring: List<DoubleArray>, turnDeg: Double = 18.0,
                            mergeM: Double = 8.0): List<ClosedFloatingPointRange<Double>>? {
        val n = ring.size
        if (n < 8) return null
        val cum = cumulative(ring)
        val total = cum[n]
        if (total <= 0) return null
        val flags = List(n) { i ->
            val a = ring[(i - 3 + n) % n]; val b = ring[i]; val c = ring[(i + 3) % n]
            val d = abs(Geo.bearing(a[0], a[1], b[0], b[1]) - Geo.bearing(b[0], b[1], c[0], c[1]))
            min(d, 360.0 - d) > turnDeg
        }
        if (flags.all { it } || flags.none { it }) return null
        val raw = ArrayList<Pair<Double, Double>>()
        var u0 = 0.0
        var cur = flags[0]
        for (i in 1 until n) {
            if (flags[i] != cur) {
                raw.add(Pair(u0, cum[i] / total))
                u0 = cum[i] / total
                cur = flags[i]
            }
        }
        raw.add(Pair(u0, 1.0))
        val merged = ArrayList<Pair<Double, Double>>()
        for ((a, b) in raw) {
            if (merged.isNotEmpty() && (b - a) * total < mergeM) {
                merged[merged.size - 1] = Pair(merged.last().first, b)
            } else merged.add(Pair(a, b))
        }
        if (merged.size > 2 && (merged[0].second - merged[0].first) * total < mergeM) {
            merged[1] = Pair(merged[0].first, merged[1].second)
            merged.removeAt(0)
        }
        return if (merged.size >= 3) merged.map { it.first..it.second } else null
    }

    /**
     * 第 k 圈的横向偏移场：返回 f(u) → (横向米, 整圈缩放)，按弧长参数化。
     *
     * 方走廊（矩形跑道）：偏移在**每段上恒定**，只在段与段交界处过渡 ——
     * 用整圈正弦的话，一阶谐波正好把一条直边推成弧，直角立刻看不出来。
     */
    private fun lapField(lapIndex: Int, seed: Int, ringLenM: Double, prof: FieldProfile,
                         segments: List<ClosedFloatingPointRange<Double>>?): (Double) -> Pair<Double, Double> {
        val rnd = Random(seed * 1_000_003 + lapIndex * 7919 + 101)
        val wob = prof.lapWobbleM
        val const = wob * 0.16 * rnd.nextDouble(-1.0, 1.0)
        val shrink = 1.0 + rnd.nextDouble(-prof.lapShrink, prof.lapShrink)

        if (segments != null && prof.lapSegmented) {
            // 段值用黄金比相位铺开：层数有限时第 6 圈必然和前面撞层（圈间距掉到
            // 0.03 m，图上完全叠住）；连续相位则几乎不会撞。
            val latVals = segments.indices.map { i ->
                val ph = ((i * 0.6180339887) + lapIndex * 0.3819660113) % 1.0
                sin(2.0 * PI * ph) * prof.lapSegAmp * rnd.nextDouble(0.85, 1.15)
            }
            val crossVals = segments.map { rnd.nextDouble(-1.0, 1.0) * prof.lapSegCross }

            // 铺到稠密表再环形平滑：插值式过渡两侧各算各的，接缝会错开一个台阶
            val ng = 2048
            val tabLat = DoubleArray(ng)
            val tabCross = DoubleArray(ng)
            for (j in 0 until ng) {
                val uu = j.toDouble() / ng
                for (i in segments.indices) {
                    if (uu >= segments[i].start && uu <= segments[i].endInclusive) {
                        tabLat[j] = latVals[i]; tabCross[j] = crossVals[i]; break
                    }
                }
            }
            val fadePts = max(1, Math.round(prof.lapSegFadeM / max(1e-6, ringLenM) * ng).toInt())
            val sLat = circSmooth(tabLat, fadePts)
            val sCross = circSmooth(tabCross, fadePts)
            return { u: Double ->
                val j = ((u % 1.0) * ng).toInt().let { if (it < 0) it + ng else it } % ng
                Pair(sLat[j] + sCross[j], shrink)
            }
        }

        val harm = prof.lapHarmonics.map { k ->
            Triple(k, wob * (HARMONIC_WEIGHT[k] ?: 0.1) * rnd.nextDouble(0.55, 1.25),
                rnd.nextDouble(0.0, 2.0 * PI))
        }
        val bumps = ArrayList<Triple<Double, Double, Double>>()
        val count = rnd.nextInt(LAP_BUMP_COUNT.first, LAP_BUMP_COUNT.last + 1)
        repeat(count) {
            bumps.add(Triple(
                rnd.nextDouble(),
                rnd.nextDouble(LAP_BUMP_WIDTH_M.start, LAP_BUMP_WIDTH_M.endInclusive),
                rnd.nextDouble(prof.lapBumpAmp.start, prof.lapBumpAmp.endInclusive) *
                    if (rnd.nextBoolean()) 1.0 else -1.0
            ))
        }
        return { u: Double ->
            var lat = const
            for ((k, amp, phase) in harm) lat += amp * sin(2.0 * PI * k * u + phase)
            for ((center, widthM, amp) in bumps) {
                var d = abs(u - center)
                d = min(d, 1.0 - d) * ringLenM
                lat += amp * exp(-0.5 * (d / widthM) * (d / widthM))
            }
            Pair(lat, shrink)
        }
    }

    /**
     * ② 的成品：按距离取环线的曲线。
     *
     * 圈长会因横向场差几米，所以不能"基准圈长 × 圈号"取模（圈边界会一圈圈漂），
     * 这里按实际圈长逐圈扣减。
     */
    class LapTrack(
        private val base: List<DoubleArray>,
        private val fence: List<DoubleArray>,
        private val excludes: List<List<DoubleArray>>,
        private val seed: Int,
        private val prof: FieldProfile
    ) {
        private val cache = HashMap<Int, Triple<List<DoubleArray>, DoubleArray, Double>>()
        private val fields = HashMap<Int, DoubleArray>()
        private val sep = ArrayList<Double>()
        private val n = base.size
        private val cLat = base.map { it[0] }.average()
        private val cLon = base.map { it[1] }.average()
        private val baseLen: Double = cumulative(base)[n]

        /** 方形场地按"边"切段；切不出来就退回整圈正弦。 */
        private val segments: List<ClosedFloatingPointRange<Double>>? =
            if (prof.lapSegmented) cornerSegments(base) ?: ringSegments(base) else null

        private fun sampleField(k: Int): (Double) -> Pair<Double, Double> {
            val us = List(n) { it.toDouble() / n }
            val want = prof.lapMinSepM
            var bestF: (Double) -> Pair<Double, Double> = lapField(k, seed, baseLen, prof, segments)
            var bestSep = -1.0
            for (attempt in 0 until LAP_MAX_TRIES) {
                val f = lapField(k + attempt * 100003, seed, baseLen, prof, segments)
                val offs = us.map { f(it).first }
                var sep2 = Double.MAX_VALUE
                for (prev in fields.values) {
                    val d = if (segments != null) {
                        val perSeg = segments.mapNotNull { seg ->
                            val idx = us.indices.filter { us[it] >= seg.start && us[it] <= seg.endInclusive }
                            if (idx.size < 3) null
                            else abs(idx.sumOf { offs[it] } / idx.size - idx.sumOf { prev[it] } / idx.size)
                        }
                        perSeg.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
                    } else {
                        offs.indices.map { abs(offs[it] - prev[it]) }.sorted().let { it[it.size / 2] }
                    }
                    if (d < sep2) sep2 = d
                }
                if (sep2 > bestSep) {
                    bestSep = sep2
                    bestF = f
                }
                if (sep2 >= want) break
            }
            fields[k] = us.map { bestF(it).first }.toDoubleArray()
            sep.add(if (bestSep.isFinite()) bestSep else 0.0)
            return bestF
        }

        fun lap(k: Int): Triple<List<DoubleArray>, DoubleArray, Double> {
            cache[k]?.let { return it }
            val dense0 = densify(ccw(base), 1.0)
            val cum0 = cumulative(dense0)
            val total = max(1e-6, cum0[dense0.size])
            val field = sampleField(k)
            val m = dense0.size
            val corners = cornerBlend(dense0)

            val poly = List(m) { i ->
                val u = cum0[i] / total
                val (off, shrink) = field(u)
                val p = dense0[i]
                val q = doubleArrayOf(cLat + (p[0] - cLat) * shrink, cLon + (p[1] - cLon) * shrink)
                val hdg = Geo.bearing(dense0[(i - 1 + m) % m][0], dense0[(i - 1 + m) % m][1],
                    dense0[(i + 1) % m][0], dense0[(i + 1) % m][1])
                // 折角处法向没有定义，两边各自往外掰会把 90° 掰成圆弧 → 改用径向
                var brg = (hdg + 90.0) % 360.0
                if (corners[i] < 1.0) {
                    val rad = Geo.bearing(cLat, cLon, p[0], p[1])
                    brg = slerpBearing(brg, rad, 1.0 - corners[i])
                }
                val o = Geo.offset(q[0], q[1], off, brg)
                doubleArrayOf(o[0], o[1])
            }

            // 方走廊只去毛（3 m）：22 m 窗口会把 90° 折角磨成圆弧
            val win = if (segments != null) 3.0 else prof.lapSmoothM
            val dense = clear(smoothRing(poly, win))
            val cum = cumulative(dense)
            val res = Triple(dense, cum, cum[dense.size])
            cache[k] = res
            return res
        }

        /**
         * 只处理"这一圈的形状本身压进禁区"的点；噪声推进来的点交给 ④。
         *
         * 这里的 margin 默认 0：环线只要不踩进禁区就行。留多了整圈会被顶离内场，
         * 等于把"绕内场的外凸弧"抹平成一条直边。
         */
        private fun clear(dense: List<DoubleArray>): List<DoubleArray> {
            if (excludes.isEmpty()) return dense
            val margin = prof.ringExclMarginM
            val m = dense.size
            return dense.mapIndexed { i, p ->
                val hit = excludes.any { Geo.pointInPoly(p[0], p[1], it) } ||
                    (margin > 0 && excludes.any { edgeDist(p, it) < margin })
                if (!hit) p else {
                    val q = clearOfExcludes(p, fence, excludes, margin)
                    if (!Geo.pointInPoly(q[0], q[1], fence)) {
                        pullToward(base[((i.toDouble() / m) * n).toInt() % n], q, fence, excludes)
                    } else q
                }
            }
        }

        fun locate(dist: Double): Pair<DoubleArray, Double> {
            var k = 0
            var d = dist
            while (true) {
                val (dense, cum, length) = lap(k)
                if (d <= length || k > 5000) return pointAt(dense, cum, min(d, length))
                d -= length
                k++
            }
        }

        fun minSeparation(): Double = if (sep.size > 1) sep.drop(1).min() else 0.0
    }

    /** 逐点标记"这里是不是折角"：0 = 折角（走径向），1 = 平顺（走法向）。 */
    private fun cornerBlend(ring: List<DoubleArray>, k: Int = 2, turnDeg: Double = 25.0): DoubleArray {
        val n = ring.size
        val out = DoubleArray(n) { 1.0 }
        for (i in 0 until n) {
            val a = ring[(i - k + n) % n]; val b = ring[i]; val c = ring[(i + k) % n]
            val d = abs(Geo.bearing(a[0], a[1], b[0], b[1]) - Geo.bearing(b[0], b[1], c[0], c[1]))
            val dd = min(d, 360.0 - d)
            out[i] = when {
                dd > turnDeg -> 0.0
                dd > turnDeg * 0.4 -> 1.0 - (dd - turnDeg * 0.4) / (turnDeg * 0.6)
                else -> 1.0
            }
        }
        return out
    }

    /** 两个方位角按短弧插值。 */
    private fun slerpBearing(a: Double, b: Double, t: Double): Double {
        val d = (b - a + 540.0) % 360.0 - 180.0
        return (a + d * t + 360.0) % 360.0
    }

    // ------------------------------------------------------------ ④ 挤出禁区

    private fun hitExclude(p: DoubleArray, excludes: List<List<DoubleArray>>): List<DoubleArray>? =
        excludes.firstOrNull { Geo.pointInPoly(p[0], p[1], it) }

    /** 沿 brg 走出 poly 需要多远。出不去返回 null。 */
    private fun exitDistance(p: DoubleArray, poly: List<DoubleArray>, brg: Double,
                             maxM: Double): Double? {
        var t = 0.5
        var prev = 0.0
        while (t <= maxM) {
            val q = Geo.offset(p[0], p[1], t, brg)
            if (!Geo.pointInPoly(q[0], q[1], poly)) {
                var lo = prev
                var hi = t
                repeat(18) {
                    val mid = (lo + hi) / 2.0
                    val qq = Geo.offset(p[0], p[1], mid, brg)
                    if (Geo.pointInPoly(qq[0], qq[1], poly)) lo = mid else hi = mid
                }
                return hi
            }
            prev = t
            t *= 1.7
        }
        return null
    }

    /**
     * 把落在禁区里的点**挤出去**：扫一圈方向，取最短的合法出路。
     *
     * 不沿用"沿 base→p 拉回"：那样点被拽回环线，禁区边上会出现一条笔直的贴边
     * 轨迹；真人遇到内场是往外绕弧。
     */
    fun squeezeOut(p: DoubleArray, fence: List<DoubleArray>,
                   excludes: List<List<DoubleArray>>, margin: Double = EXIT_MARGIN_M): Pair<DoubleArray, Double> {
        val hit = hitExclude(p, excludes) ?: return Pair(p, 0.0)
        var bestT = Double.MAX_VALUE
        var best: DoubleArray? = null
        var deg = 0
        while (deg < 360) {
            val brg = deg.toDouble()
            val t = exitDistance(p, hit, brg, EXIT_MAX_PUSH_M)
            if (t != null) {
                val q = Geo.offset(p[0], p[1], t + margin, brg)
                if (Geo.pointInPoly(q[0], q[1], fence) && hitExclude(doubleArrayOf(q[0], q[1]), excludes) == null) {
                    if (t < bestT) {
                        bestT = t
                        best = doubleArrayOf(q[0], q[1])
                    }
                }
            }
            deg += EXIT_DIR_STEP_DEG
        }
        val b = best ?: return Pair(p, 0.0)
        return Pair(b, Geo.hav(p[0], p[1], b[0], b[1]))
    }

    /** 把点挪到"围栏内、且离所有禁区 ≥ margin"。 */
    private fun clearOfExcludes(p: DoubleArray, fence: List<DoubleArray>,
                                excludes: List<List<DoubleArray>>, margin: Double): DoubleArray {
        var q = p
        repeat(3) {
            if (!Geo.pointInPoly(q[0], q[1], fence)) q = clampToFence(q[0], q[1], fence)
            val near = excludes.firstOrNull {
                Geo.pointInPoly(q[0], q[1], it) || edgeDist(q, it) < margin
            } ?: return q
            val cLat = near.map { it[0] }.average()
            val cLon = near.map { it[1] }.average()
            val brg = Geo.bearing(cLat, cLon, q[0], q[1])
            val need = margin + EXIT_MARGIN_M - edgeDist(q, near)
            var moved = false
            for (i in 1..24) {
                val cand = Geo.offset(q[0], q[1], need + i * 0.4, brg)
                if (!Geo.pointInPoly(cand[0], cand[1], fence)) break
                if (hitExclude(doubleArrayOf(cand[0], cand[1]), excludes) == null &&
                    edgeDist(doubleArrayOf(cand[0], cand[1]), near) >= margin) {
                    q = doubleArrayOf(cand[0], cand[1])
                    moved = true
                    break
                }
            }
            if (!moved) {
                val (out, push) = squeezeOut(q, fence, excludes, margin)
                if (push <= 0.0) return q
                q = out
            }
        }
        return q
    }

    /**
     * 把噪声点放到最终位置：先收围栏，再挤禁区。顺序不能反 ——
     * 挤出去的方向必须是"围栏内"的方向，所以先确定点在场内。
     */
    fun place(base: DoubleArray, qIn: DoubleArray, fence: List<DoubleArray>,
              excludes: List<List<DoubleArray>>, pushSink: MutableList<Double>): DoubleArray {
        var q = qIn
        if (!Geo.pointInPoly(q[0], q[1], fence)) {
            val anchor = if (Geo.pointInPoly(base[0], base[1], fence)) base
            else rescuePoint(base, fence, excludes)
            q = if (anchor != null && Geo.pointInPoly(anchor[0], anchor[1], fence)) {
                pullToward(anchor, q, fence, emptyList())
            } else {
                clampToFence(q[0], q[1], fence)
            }
        }
        if (hitExclude(q, excludes) != null) {
            val (q2, push) = squeezeOut(q, fence, excludes)
            val out = if (push <= 0.0) {
                rescuePoint(q, fence, excludes) ?: q
            } else q2
            if (out !== q) {
                val moved = Geo.hav(q[0], q[1], out[0], out[1])
                if (moved > 0) pushSink.add(moved)
            }
            q = out
        }
        return q
    }
}

/** `track_model.FIELD_TUNING` 的 Kotlin 版：三个真实场地反解出来的长短轴缩放。 */
internal class FieldTuning(
    val kLong: Double,
    val kShort: Double,
    val shiftLat: Double,
    val shiftLon: Double
) {
    companion object {
        fun of(placeCode: String, @Suppress("UNUSED_PARAMETER") fence: List<DoubleArray>): FieldTuning? =
            when (placeCode) {
                "T1014" -> FieldTuning(0.58, 0.37, -10.0, 15.0)
                "T1001" -> FieldTuning(0.93, 0.86, 0.0, 0.0)
                "T1005" -> FieldTuning(0.62, 0.52, 0.0, 0.0)
                else -> null
            }
    }
}
