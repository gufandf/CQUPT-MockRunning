/*
 * 打卡流程编排：token → 规则 → 区域 → start2 → 分批上传 → end
 */
package com.whale.campusrun

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一个可跑步的场地。
 *
 * 服务器 /area/getAllArea 会把同一 placeCode 拆成多条记录：
 *   areaFuncCode = "dzwl" 电子围栏 —— 轨迹必须落在这个多边形内
 *   areaFuncCode = "jpq"  禁跑区   —— 轨迹必须避开（禁跑区里的点不计里程）
 *   areaFuncCode = "dkq"  打卡区
 *
 * 注意：/area/getAllArea 里混着学校数据库的测试残留（00001 上海成、00002 石桥铺），
 * 它们不在 /place/getPlayground 里。必须做交集，否则会列出一堆跑不了的场地。
 */
data class Area(
    val placeName: String,
    val placeCode: String,
    val fence: List<DoubleArray>,
    val excludes: List<List<DoubleArray>>
) {
    override fun toString(): String = "$placeName ($placeCode)"
}

data class Rule(val minPace: Double, val maxPace: Double, val raceMile: Double) {
    /** 取区间中段，别贴边 —— 这是唯一会被人工复查的地方。 */
    fun suggest(): Double = (minPace + maxPace) / 2.0
}

/** 打卡过程中的实时状态，驱动界面上的仪表盘。 */
data class RunState(
    val phase: String = "空闲",
    val pointsDone: Int = 0,
    val pointsTotal: Int = 0,
    val metersDone: Double = 0.0,
    val metersTotal: Double = 0.0,
    val elapsedSec: Int = 0,
    val etaSec: Int = -1,
    val paceSecKm: Double = 0.0,
    val paceLo: Double = 0.0,
    val paceHi: Double = 0.0,
    val batchesDone: Int = 0,
    val batchesTotal: Int = 0,
    val serverMileage: Double = -1.0,
    val serverSeconds: Int = -1,
    val finished: Boolean = false,
    val failed: Boolean = false,
    /** 暂停中（停表）。界面据此把按钮换成「继续」并显示暂停徽标。 */
    val paused: Boolean = false
)

/** 统计页的一项。 */
data class StatRow(
    val placeName: String,
    val distanceKm: Double,
    val durationSec: Int,
    val timeText: String,
    val valid: Boolean,
    val reason: String,
    /** "11-09" 这种短日期；拿不到时间戳时是空串（时间轴会退回「第 N 次」）。 */
    val dateLabel: String = "",
    /** 该条的配速（秒/公里），距离为 0 时是 0。 */
    val paceSecKm: Double = 0.0
)

data class Stats(
    val total: Int,
    val validCount: Int,
    val invalidCount: Int,
    val totalKm: Double,
    val avgPaceSecKm: Double,
    /** 有效成绩里最快的一条（秒/公里），没有有效成绩时是 0。 */
    val fastestPaceSecKm: Double = 0.0,
    val byPlace: List<Pair<String, Int>>,
    val rows: List<StatRow>
)

interface RunListener {
    fun log(line: String)
    fun onState(s: RunState)
}

/**
 * 打卡流程编排。
 *
 *   ① checkBinding(openid) → token
 *   ② setting/getRule      → 配速区间
 *   ③ place/getPlayground + area/getAllArea → 可跑步场地及其多边形
 *   ④ sport/start2         → sportRecordNo
 *   ⑤ point/saveListByNo   → 分批上传
 *   ⑥ sport/end/{no}
 *
 * 全程不碰 GPS，不需要定位权限。
 */
class RunEngine(private val api: Api, private val l: RunListener) {

    @Volatile private var cancel = false

    /**
     * 暂停标记。语义是**停表**，不是只停上传：
     *
     *   · 暂停期间不再往上吐点，本地时钟也冻结（界面「用时 / 剩余」不再往前走）；
     *   · 恢复时把暂停总时长记进 [pausedAccumMs]，之后每个点的时间轴整体往后挪 ——
     *     也就是说 `collectTime` 的跨度仍然是「真实跑动时长」，暂停那段不记进去。
     *
     * 为什么必须挪时间轴而不是只等待：服务端配速 = `timeConsuming / mileage`，
     * 而 `timeConsuming` 由这批点里首末点 `collectTime` 的跨度推出来。
     * 暂停期间照样按原计划推进的话，这十分钟会原封不动算进时长，
     * 配速立刻被拉到 maxPace 之外，整条记录判无效。
     *
     * 具体算术在 [PauseClock] 里（纯 JVM，可单测），这里只负责编排。
     */
    private val clock = PauseClock()

    /** 连接器层面的硬失败（服务器不可达）。界面据此弹「无法连接到服务器」。 */
    @Volatile var netFail = false
        private set

    fun cancel() {
        cancel = true
        // 取消时把暂停解开，否则等待循环卡在 paused 上永远退不出来
        clock.abort()
    }

    /** 暂停（停表）。已经在暂停 / 已取消时是空操作。 */
    fun pause() {
        if (cancel) return
        if (!clock.pause(SystemClock.elapsedRealtime())) return
        l.log("   暂停：停表，不再上传点（恢复后暂停时长不计入配速）")
    }

    /** 恢复。把这次暂停的时长累加进时间轴偏移。 */
    fun resume() {
        if (cancel) return
        val d = clock.resume(SystemClock.elapsedRealtime())
        if (d <= 0L) return
        l.log("   继续：本次暂停 ${fmtDuration((d / 1000).toInt())}，"
                + "累计已顺延 ${fmtDuration((clock.pausedAccumMs / 1000).toInt())}")
    }

    /** 界面用来画「已暂停」徽标。 */
    fun isPaused(): Boolean = clock.paused

    // ---------------------------------------------------------------- ①

    /**
     * 换 token。
     *
     * 这里的失败分两种，界面要区别对待：
     *   · 网络层失败（连不上服务器）→ 弹「无法连接到服务器」
     *   · 业务层失败（openid 无效 / 未绑定）→ 提示去小程序重新绑定
     * 原来的实现把"服务器不可达"和"openid 不对"都塞进 [lastError]，
     * 界面上根本分不出来。
     */
    fun connect(openid: String): Boolean {
        api.reset(openid)
        netFail = false
        l.log("① 换取 token ...")
        val j = api.checkBinding(openid)
        if (j == null) {
            l.log("   ✗ 无法连接到服务器：${api.lastError}")
            l.log("     检查手机网络 / 校园网是否可达 sport.cqupt.edu.cn")
            netFail = true
            return false
        }
        val r = Resp.of(j)
        if (!r.ok) {
            l.log("   ✗ code=${r.code} ${r.msg}")
            return false
        }
        val d = j.optJSONObject("data")
        if (d == null || d.optString("isBind") != "true") {
            l.log("   ✗ openid 未绑定校园跑身份（isBind=${d?.optString("isBind")}）")
            l.log("     请先在手机小程序里完成一次绑定，再回来复制 openid")
            return false
        }
        api.token = d.optString("token")
        l.log("   ✓ token = ${api.mask(api.token)}")
        return true
    }

    // ---------------------------------------------------------------- ②③

    fun fetchRule(): Rule? {
        l.log("② 拉取跑步规则 ...")
        val j = api.getRule()
        if (j == null) {
            l.log("   ✗ 无法连接到服务器：${api.lastError}")
            return null
        }
        val r = Resp.of(j)
        if (!r.ok) {
            l.log("   ✗ code=${r.code} ${r.msg}")
            return null
        }
        val d = r.data as? JSONObject ?: return null
        val min = d.optString("minPace").toDoubleOrNull() ?: 0.0
        val max = d.optString("maxPace").toDoubleOrNull() ?: 0.0
        val mile = d.optString("raceMile").toDoubleOrNull() ?: 2000.0
        l.log("   配速区间 ${fmtPace(min * 60)} ~ ${fmtPace(max * 60)}/km，"
                + "建议 ${fmtPace((min + max) / 2 * 60)}")
        l.log("   达标里程 ${mile.toInt()} m")
        return Rule(min * 60, max * 60, mile)
    }

    /**
     * 拉区域。
     *
     * 关键一步是跟 /place/getPlayground 做交集：/area/getAllArea 里混着学校的
     * 测试残留（00001 上海成、00002 石桥铺），它们不在场地表里，跑不了。
     */
    fun fetchAreas(): List<Area> {
        l.log("③ 拉取场地 ...")
        netFail = false

        // 可跑步场地：sportsItem == "0"（跑步），其余是球馆/游泳馆
        val runnable = LinkedHashMap<String, String>()
        val pj = api.getPlayground()
        if (pj == null) {
            l.log("   ✗ 无法连接到服务器：${api.lastError}")
            l.log("     检查手机网络 / 校园网是否可达 sport.cqupt.edu.cn")
            netFail = true
            return emptyList()
        }
        val pr = Resp.of(pj)
        if (pr.ok) {
            val arr = pr.data as? JSONArray ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                if (p.optString("sportsItem") == "0") {
                    runnable[p.optString("placeCode")] = p.optString("placeName")
                }
            }
            l.log("   可跑步场地 ${runnable.size} 个")
        } else {
            l.log("   ⚠ 场地表拉取失败（${pr.code} ${pr.msg}），不过滤")
        }

        val aj = api.getAllArea()
        if (aj == null) {
            l.log("   ✗ 无法连接到服务器：${api.lastError}")
            netFail = true
            return emptyList()
        }
        val r = Resp.of(aj)
        if (!r.ok) {
            l.log("   ✗ code=${r.code} ${r.msg}")
            return emptyList()
        }
        val arr = r.data as? JSONArray ?: return emptyList()

        val fences = LinkedHashMap<String, Pair<String, List<DoubleArray>>>()
        val bans = LinkedHashMap<String, MutableList<List<DoubleArray>>>()
        val skipped = ArrayList<String>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("placeCode")
            val name = o.optString("placeName")
            val func = o.optString("areaFuncCode")

            if (runnable.isNotEmpty() && code !in runnable) {
                if (code !in skipped) skipped.add("$name ($code)")
                continue
            }

            val pl = o.optJSONArray("areaPointList") ?: continue
            val pts = ArrayList<DoubleArray>()
            for (k in 0 until pl.length()) {
                val p = pl.optJSONObject(k) ?: continue
                pts.add(doubleArrayOf(p.optDouble("latitude"), p.optDouble("longitude")))
            }
            if (pts.size < 3) continue

            when (func) {
                "dzwl" -> if (!fences.containsKey(code)) {
                    fences[code] = Pair(runnable[code] ?: name, pts)
                }
                "jpq" -> bans.getOrPut(code) { ArrayList() }.add(pts)
            }
        }

        if (skipped.isNotEmpty()) {
            l.log("   已过滤测试残留 ${skipped.size} 个：${skipped.joinToString("、")}")
        }

        val out = fences.map { (code, v) -> Area(v.first, code, v.second, bans[code] ?: emptyList()) }
        l.log("   可用场地 ${out.size} 个")
        out.forEach {
            l.log("     · ${it.placeName} / ${it.placeCode} / 围栏 ${it.fence.size} 顶点"
                    + if (it.excludes.isEmpty()) "" else " / 禁跑区 ${it.excludes.size} 块")
        }
        return out
    }

    // ---------------------------------------------------------------- 统计

    /**
     * 只把 /sportsResult/list 拍平成 [StatsRecord] 列表，**不做有效 / 无效的聚合**。
     *
     * 为什么单开一个：成绩页现在「所有记录一视同仁」地排成卡片，
     * 不再需要 validCount / 平均配速 / 分场地那套摘要（那是按有效成绩算的）。
     * 启动时预取走这条，少算一遍聚合，也不用为了一个列表去构造 [Stats]。
     *
     * 返回 null 表示网络或业务层失败，调用方保留旧缓存。
     */
    /**
     * 拉全部历史记录（成绩页的数据源）。
     *
     * **主源是 `/sportRecord/mobile/list`，不是 `/sportsResult/list`。**
     * 理由是字段名有确证：
     *   · `refs/fetch_tracks.py` 是真实对着服务器跑通过的脚本（62 条记录），
     *     它读的就是 `sportRecordNo` / `placeName` / `mileage` / `timeConsuming` /
     *     `isValid` / `reason`；
     *   · 而 `/sportsResult/list` 的记录号字段叫 `sportsResultNo`，只有
     *     `refs/clear_stale.py` 间接用过 —— 之前代码读 `sportRecordNo`，
     *     于是每一条都拿不到记录号，轨迹页一律打不开。
     *
     * 顺带解决另一件事：这个接口给的是**全部记录**（含无效），正是成绩页要的口径。
     * `pageSize` 实测上限 100，按 `totalPage` 翻页，最多翻 [MAX_PAGES] 页防止打转。
     *
     * 拿不到时退回 `/sportsResult/list`（兼容极端情况：记录列表空但成绩表有数据）。
     */
    fun fetchRecords(): List<StatsRecord>? {
        val fromRecords = fetchRecordsFromList()
        if (fromRecords != null && fromRecords.isNotEmpty()) return fromRecords
        return fetchRecordsFromSportsResult()
    }

    /** 每页 100 条是服务端硬上限；翻到 [MAX_PAGES] 页（1000 条）还没完就不再翻了。 */
    private fun fetchRecordsFromList(): List<StatsRecord>? {
        val out = ArrayList<StatsRecord>()
        var page = 1
        var totalPage = 1
        while (page <= totalPage && page <= MAX_PAGES) {
            val j = api.getRecordPage(page, PAGE_SIZE)
            if (j == null) {
                l.log("✗ 记录列表拉取失败：${api.lastError}")
                netFail = true
                return if (page == 1) null else out
            }
            val r = Resp.of(j)
            if (!r.ok) {
                l.log("✗ 记录列表业务失败 code=${r.code} ${r.msg}")
                return if (page == 1) null else out
            }
            val data = r.data as? JSONObject ?: return if (page == 1) null else out
            totalPage = data.optString("totalPage").toIntOrNull()
                ?: data.optInt("totalPage", 1)
            val list = data.optJSONArray("list") ?: break
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val recordNo = str(o, "sportRecordNo").ifEmpty { str(o, "sportsResultNo") }
                if (recordNo.isEmpty()) continue
                // mileage 是公里、timeConsuming 是秒（FINDINGS §4.4 / fetch_tracks.py）
                val km = o.optDouble("mileage", 0.0).takeIf { it > 0.0 }
                    ?: str(o, "mileage").toDoubleOrNull()?.takeIf { it > 0.0 }
                    ?: 0.0
                val sec = o.optString("timeConsuming").toIntOrNull()
                    ?: o.optInt("timeConsuming", 0)
                out.add(
                    StatsRecord(
                        recordNo = recordNo,
                        placeName = str(o, "placeName"),
                        placeCode = str(o, "placeCode").ifEmpty {
                            recordNo.split("_").getOrNull(1).orEmpty()
                        },
                        distanceKm = km,
                        durationSec = sec,
                        dateLabel = StatsParse.dateLabel(recordNo, startTimeOf(o)),
                        valid = str(o, "isValid") == "1",
                        reason = str(o, "reason"),
                        paceSecKm = if (km > 0.01) sec / km else 0.0
                    )
                )
            }
            if (list.length() < PAGE_SIZE) break
            page++
        }
        if (out.isNotEmpty()) {
            // 记录列表里可能有「挂起未结束」的记录（没有 end 过），排掉以免显示成一条 0 公里的假成绩
            val clean = out.filter { it.distanceKm > 0.0 || it.durationSec > 0 }
            if (clean.size != out.size) {
                l.log("   过滤掉 ${out.size - clean.size} 条未结束 / 空记录")
            }
            return clean
        }
        return null
    }

    /** 记录里的时间字段名不统一，几处都试一下。 */
    private fun startTimeOf(o: JSONObject): String =
        str(o, "startTime").ifEmpty { str(o, "createTime") }
            .ifEmpty { str(o, "endTime") }.ifEmpty { str(o, "time") }

    /** 兜底源：`/sportsResult/list`。字段名见 [fetchRecords] 的注释。 */
    private fun fetchRecordsFromSportsResult(): List<StatsRecord>? {
        val j = api.getSportsResult()
        if (j == null) {
            l.log("✗ 无法连接到服务器：${api.lastError}")
            netFail = true
            return null
        }
        val r = Resp.of(j)
        if (!r.ok) {
            l.log("✗ 成绩拉取失败 code=${r.code} ${r.msg}")
            return null
        }
        val arr = r.data as? JSONArray ?: return emptyList()
        val out = ArrayList<StatsRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val km = str(o, "distance").toDoubleOrNull()?.div(1000.0) ?: 0.0
            val dur = str(o, "duration").toDoubleOrNull()?.toInt() ?: 0
            // 这个接口的记录号字段叫 sportsResultNo，和记录列表的 sportRecordNo 同构
            val recordNo = str(o, "sportsResultNo")
                .ifEmpty { str(o, "sportRecordNo") }
                .ifEmpty { str(o, "recordNo") }
            out.add(
                StatsRecord(
                    recordNo = recordNo,
                    placeName = str(o, "placeName"),
                    placeCode = str(o, "placeCode")
                        .ifEmpty { recordNo.split("_").getOrNull(1).orEmpty() },
                    distanceKm = km,
                    durationSec = dur,
                    dateLabel = StatsParse.dateLabel(recordNo, startTimeOf(o)),
                    valid = str(o, "isValid") == "1",
                    reason = str(o, "reason"),
                    paceSecKm = if (km > 0.01) dur / km else 0.0
                )
            )
        }
        val missingNo = out.count { it.recordNo.isEmpty() }
        if (missingNo > 0) {
            l.log("   ⚠ $missingNo/${out.size} 条记录没有记录号（字段可能变了，轨迹页会打不开）")
        }
        return out
    }

    fun fetchStats(): Stats? {
        val j = api.getSportsResult()
        if (j == null) {
            l.log("✗ 无法连接到服务器：${api.lastError}")
            return null
        }
        val r = Resp.of(j)
        if (!r.ok) {
            l.log("✗ 统计拉取失败 code=${r.code} ${r.msg}")
            return null
        }
        val arr = r.data as? JSONArray ?: return null
        val rows = ArrayList<StatRow>()
        val byPlace = LinkedHashMap<String, Int>()
        var totalKm = 0.0
        var totalSec = 0.0
        var validCount = 0
        var invalidCount = 0

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val km = str(o, "distance").toDoubleOrNull()?.div(1000.0) ?: 0.0
            val dur = str(o, "duration").toDoubleOrNull()?.toInt() ?: 0
            val valid = str(o, "isValid") == "1"
            val place = str(o, "placeName")
            val reason = str(o, "reason")

            rows.add(
                StatRow(
                    placeName = place,
                    distanceKm = km,
                    durationSec = dur,
                    timeText = fmtDuration(dur),
                    valid = valid,
                    reason = reason,
                    dateLabel = StatsParse.dateLabel(
                        o.optString("sportRecordNo").ifEmpty { o.optString("recordNo") },
                        o.optString("createTime").ifEmpty { o.optString("startTime") }
                            .ifEmpty { o.optString("time") }
                    ),
                    paceSecKm = if (km > 0.01) dur / km else 0.0
                )
            )
            if (valid) {
                validCount++
                totalKm += km
                totalSec += dur
                byPlace[place] = (byPlace[place] ?: 0) + 1
            } else {
                invalidCount++
            }
        }

        val avgPace = if (totalKm > 0) totalSec / totalKm else 0.0
        val fastest = rows.filter { it.valid && it.paceSecKm > 0 }.minOfOrNull { it.paceSecKm } ?: 0.0
        return Stats(
            total = rows.size,
            validCount = validCount,
            invalidCount = invalidCount,
            totalKm = totalKm,
            avgPaceSecKm = avgPace,
            fastestPaceSecKm = fastest,
            byPlace = byPlace.entries.sortedByDescending { it.value }.map { it.key to it.value },
            rows = rows
        )
    }

    // ---------------------------------------------------------------- 全部

    fun runAll(
        openid: String,
        area: Area,
        paceSecKm: Double,
        targetM: Double,
        realtime: Boolean,
        noise: Double,
        seed: Int,
        paceToleranceSec: Double = TrackGen.DEFAULT_PACE_TOLERANCE_SEC,
        validate: Boolean = true,
        burrs: Boolean = true
    ) {
        cancel = false
        var state = RunState(phase = "初始化", paceSecKm = paceSecKm, metersTotal = targetM)
        fun push() = l.onState(state)

        push()

        if (!connect(openid)) {
            l.onState(state.copy(phase = "失败：token", failed = true))
            return
        }

        val rule = fetchRule()
        if (rule != null && (paceSecKm < rule.minPace || paceSecKm > rule.maxPace)) {
            l.log("   ⚠ 配速 ${fmtPace(paceSecKm)} 落在服务端允许区间之外！"
                    + "建议 ${fmtPace(rule.suggest())}")
        }

        l.log("④ 生成轨迹（区域：${area.placeName}）")
        state = state.copy(phase = "生成轨迹")
        push()

        val t0 = System.currentTimeMillis()
        val res = TrackGen.generate(
            poly = area.fence,
            excludes = area.excludes,
            paceSecKm = paceSecKm,
            targetM = targetM,
            noise = noise,
            seed = seed,
            paceToleranceSec = paceToleranceSec,
            burrs = burrs,
            placeCode = area.placeCode
        )
        val track = res.points
        l.log("   轨迹模式：" + if (burrs) "四段流水线（内轨迹→逐圈→噪声→挤禁区）"
                else "光滑（毛刺关闭）")
        l.log("   场地参数：${area.placeName} / ${area.placeCode}")
        l.log("   ${TrackGen.summarize(track)}  (耗时 ${System.currentTimeMillis() - t0} ms)")

        // 平均配速是否落在「目标 ± 容差」内
        l.log("   平均配速 ${fmtPace(res.paceSecKm)}，"
                + "允许 ${fmtPace(res.window.start)}~${fmtPace(res.window.endInclusive)}"
                + if (res.adjusted) "（已自动微调采样点数压进窗口）" else "")
        if (!res.inWindow) {
            l.log("   ⚠ 平均配速仍在窗口外，距离可能被噪声顶到下限")
        }

        val paceRange = paceRangeOf(track)

        if (validate) {
            val inside = track.count { Geo.pointInPoly(it.lat, it.lon, area.fence) }
            val banned = track.count { p -> area.excludes.any { Geo.pointInPoly(p.lat, p.lon, it) } }
            l.log("   自检：围栏内 $inside/${track.size} = "
                    + "${"%.2f".format(inside * 100.0 / track.size)}% | 禁跑区 $banned 点")
            if (inside < track.size * 0.99) {
                l.log("   ✗ 轨迹有大量点落在围栏外，换一个场地试试")
                l.onState(state.copy(phase = "失败：轨迹越界", failed = true))
                return
            }
            if (banned > 0) {
                l.log("   ⚠ 有 $banned 个点落在禁跑区，这部分不计入里程")
            }
        }

        l.log("⑤ 开始跑步 start2 ...")
        state = state.copy(phase = "开始打卡")
        push()
        val sj = api.startSport(area.placeName, area.placeCode)
        if (sj == null) {
            l.log("   ✗ 网络失败：${api.lastError}")
            l.onState(state.copy(phase = "失败：start2 网络", failed = true))
            return
        }
        val sr = Resp.of(sj)
        if (!sr.ok) {
            l.log("   ✗ code=${sr.code} ${sr.msg}")
            l.onState(state.copy(phase = "失败：start2", failed = true))
            return
        }
        var recordNo = sr.data?.toString()?.trim()?.trim('"') ?: ""
        if (recordNo.isEmpty() || recordNo == "null") {
            l.log("   ✗ 未拿到 sportRecordNo：${sj.toString().take(200)}")
            l.onState(state.copy(phase = "失败：无记录号", failed = true))
            return
        }
        l.log("   ✓ sportRecordNo = $recordNo")

        // 服务端的 start2 会把「上次没结束的那条记录」原样接续下来。
        // 不处理的话，里程和配速会跟上次的混在一起，界面一进去就跳到一半。
        // 这里检测到残留就把它结束掉，再开一条干净的。
        val preInfo = (Resp.of(api.getRecordInfo(recordNo)).data as? JSONObject)
        val prePts = preInfo?.optJSONArray("points")?.length() ?: 0
        if (prePts > 0) {
            val preKm = preInfo?.optString("mileage") ?: "?"
            l.log("   ⚠ 这条是接续上次没结束的记录（已有 $prePts 点 / $preKm km）")
            l.log("     先把它结束，再开一条干净的 …")
            Resp.of(api.endSport(recordNo))
            val sj2 = api.startSport(area.placeName, area.placeCode)
            val r2 = Resp.of(sj2)
            val no2 = r2.data?.toString()?.trim()?.trim('"') ?: ""
            if (r2.ok && no2.isNotEmpty() && no2 != "null" && no2 != recordNo) {
                recordNo = no2
                l.log("   ✓ 新 sportRecordNo = $recordNo")
            } else {
                l.log("   ✗ 重新开一条失败（${r2.code}），仍用原记录")
            }
        } else {
            l.log("   记录干净，从 0 开始")
        }

        l.log("⑥ 上传轨迹点 ...")
        val startMs = System.currentTimeMillis()
        val startWall = SystemClock.elapsedRealtime()
        val valid = validFlags(track, area)
        val banned = valid.count { !it }
        val points = buildPoints(track, valid, recordNo, area, startMs)
        val batches = batch(points, Api.POINT_BATCH)
        val totalSec = track.size - 1
        l.log("   共 ${points.size} 点 → ${batches.size} 批（每批 ${Api.POINT_BATCH}，末点作下批首点）")
        l.log("   轨迹时长 ${fmtDuration(totalSec)}（服务端按 collectTime 跨度算时长），"
                + "退出 App 不会中断，但别划掉它")
        if (banned > 0) {
            l.log("   ⚠ 其中 $banned 点落在禁跑区，已按小程序语义标记 isValid=\"2\"（不计里程）")
        }

        var uploadedPoints = 0
        var uploadedMeters: Double
        var serverMileage = -1.0
        var serverSeconds = -1

        // 轨迹的累计里程，用于界面上"已跑"的估算
        val cum = DoubleArray(track.size)
        for (k in 1 until track.size) {
            cum[k] = cum[k - 1] + Geo.hav(
                track[k - 1].lat, track[k - 1].lon, track[k].lat, track[k].lon
            )
        }

        state = state.copy(
            phase = "上传中",
            pointsTotal = points.size,
            batchesTotal = batches.size,
            paceLo = paceRange.first,
            paceHi = paceRange.second
        )
        push()

        for ((i, b) in batches.withIndex()) {
            if (cancel) {
                l.log("   已取消")
                l.onState(state.copy(phase = "已取消", failed = true))
                return
            }

            // 暂停中：连点都不发。发出去服务端就会按 collectTime 把这批点连上，
            // 暂停的十分钟会被算成「跑得很慢」。
            if (!awaitResume(state) { st ->
                    state = st
                    push()
                }) {
                l.log("   已取消")
                l.onState(state.copy(phase = "已取消", failed = true))
                return
            }

            val uj = api.uploadPoints(recordNo, b)
            if (uj == null) {
                l.log("   批次 ${i + 1}/${batches.size} ✗ ${api.lastError}")
            } else {
                val ur = Resp.of(uj)
                val dd = ur.data as? JSONObject
                if (dd != null) {
                    val m = dd.optString("mileage").toDoubleOrNull()
                    val s = dd.optString("timeConsuming").toDoubleOrNull()?.toInt()
                    if (m != null) serverMileage = m
                    if (s != null) serverSeconds = s
                }
                if (!ur.ok) l.log("   批次 ${i + 1}/${batches.size} ✗ code=${ur.code} ${ur.msg}")
            }

            // 首批吃掉全部点，之后每批只有 (size-1) 个是新点（首点是上批末点）
            uploadedPoints += if (i == 0) b.size else b.size - 1
            if (uploadedPoints > track.size) uploadedPoints = track.size
            uploadedMeters = cum[(uploadedPoints - 1).coerceIn(0, track.size - 1)]

            // 进展写一条日志，否则成功批次不吭声，看起来像卡死了
            if ((i + 1) % 10 == 0 || i == batches.size - 1) {
                l.log("   批次 ${i + 1}/${batches.size}　已跑 %.2f/%.2f km　用时 %s"
                    .format(uploadedMeters / 1000.0, targetM / 1000.0,
                            fmtDuration((clock.runElapsedMs(
                                startWall, SystemClock.elapsedRealtime()
                            ) / 1000).toInt())))
            }

            if (realtime && i < batches.size - 1) {
                // 这一批要"占掉"的时长 = **本批末点的 secOffset**，不是批大小。
                // 相邻两批共享一个点（末点作下批首点），所以两批之间只跨 size-1 秒；
                // 按批大小等会把整轮拖长约 14%（690 点 / 每批 8 时多等 95 秒），
                // 服务端按 collectTime 跨度算时长，多出来的等待全都会记到配速里。
                //
                // 关键：用 elapsedRealtime 量真实时间，而不是"睡了几次 × 250ms"。
                // 息屏 / Doze 时 Thread.sleep 会睡过头，按次数算的话整轮会被拖长一倍多 ——
                // 表现就是"进度条不动，只有用时在涨"。
                val target = startWall + track[uploadedPoints - 1].secOffset * 1000L
                var lastPush = SystemClock.elapsedRealtime()
                while (!cancel) {
                    // 暂停期间不推进时间轴：等恢复之后 target 会整体挪到累计暂停上
                    if (clock.paused) {
                        if (!awaitResume(state) { st ->
                                state = st
                                push()
                            }) break
                        lastPush = SystemClock.elapsedRealtime()
                        continue
                    }
                    val remainMs = target + clock.pausedAccumMs - SystemClock.elapsedRealtime()
                    if (remainMs <= 0) break
                    Thread.sleep(minOf(remainMs, 200L))
                    if (clock.paused) continue
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPush >= 900) {
                        lastPush = now
                        // 本地时钟同样要扣掉暂停时长，否则暂停那十分钟会显示成"在跑"
                        val e = (clock.runElapsedMs(startWall, now) / 1000).toInt()
                        state = state.copy(elapsedSec = e, etaSec = maxOf(0, totalSec - e))
                        push()
                    }
                }
            }

            val elapsed = (clock.runElapsedMs(startWall, SystemClock.elapsedRealtime()) / 1000)
                .toInt()
            state = state.copy(
                pointsDone = uploadedPoints,
                metersDone = if (serverMileage >= 0) serverMileage * 1000 else uploadedMeters,
                elapsedSec = elapsed,
                etaSec = maxOf(0, totalSec - elapsed),
                batchesDone = i + 1,
                serverMileage = serverMileage,
                serverSeconds = serverSeconds
            )
            push()
        }

        if (cancel) {
            l.onState(state.copy(phase = "已取消", failed = true))
            return
        }

        l.log("⑦ 结束跑步 end ...")
        state = state.copy(phase = "结束打卡")
        push()
        val ej = api.endSport(recordNo)
        if (ej == null) {
            l.log("   ✗ ${api.lastError}")
            l.onState(state.copy(phase = "失败：end 网络", failed = true))
            return
        }
        val er = Resp.of(ej)
        l.log("   ${if (er.ok) "✓" else "✗"} code=${er.code} ${er.msg}")

        val info = Resp.of(api.getRecordInfo(recordNo))
        if (info.ok) {
            val d = info.data as? JSONObject
            val km = d?.optString("mileage") ?: "-"
            val sec = d?.optString("timeConsuming") ?: "-"
            val pts = (d?.optJSONArray("points"))?.length() ?: 0
            val forbidden = d?.optString("expiredCountInForbiddenArea") ?: "0"
            l.log("   记录：$km km / ${sec}s / $pts 点 / 禁跑区 $forbidden")
        }
        // 这里也要扣掉暂停：collectTime 的跨度不含暂停，日志/界面却按墙钟报的话，
        // 两个数会差出整整一个暂停时长，看起来像「服务端少算了」。
        val wallSec = ((System.currentTimeMillis() - startMs - clock.pausedAccumMs) / 1000)
            .coerceAtLeast(0L)
        l.log("完成。共耗时 $wallSec 秒")

        l.onState(state.copy(
            phase = if (er.ok) "已完成" else "已结束但返回异常",
            // 收尾时**不要**沿用 metersDone 的旧值 —— 那是上传过程中从服务端累计来的，
            // 总比目标少一点（最后一次轮询之后还落了几个批次没算进去），
            // 停在它上面就成了「已完成 99%」，看着像没跑完。
            // 整条轨迹就是 metersTotal 米，跑完就是 100%；服务端那个略小的数
            // 另有「服务端累计」一行在显示，不会丢。
            metersDone = state.metersTotal,
            pointsDone = state.pointsTotal,
            batchesDone = state.batchesTotal,
            elapsedSec = wallSec.toInt(),
            etaSec = 0,
            finished = true
        ))
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 停在暂停状态，直到恢复或取消。
     *
     * 为什么单独抽出来：三处地方都要等 —— 每批上传之前、批次间的等待循环里。
     * 等的时候要**继续广播 elapsedSec**（界面上的「用时」虽然冻结，但「暂停中」
     * 这个状态得让用户看得见时间停在哪一秒），所以把 push 以回调传进来，
     * 而不是在这里自己调 l.onState（那样会拿到调用方手里的旧 state）。
     *
     * @return true = 已恢复，可以继续；false = 被取消，调用方应当收工。
     */
    private fun awaitResume(currentState: RunState, emit: (RunState) -> Unit): Boolean {
        if (!clock.paused) return true
        var st = currentState.copy(phase = "已暂停", paused = true)
        emit(st)
        while (clock.paused && !cancel) {
            Thread.sleep(150L)
        }
        return if (cancel) {
            false
        } else {
            // 恢复：把 paused 清掉再广播一次，界面按钮才会从「继续」变回「结束打卡」
            st = st.copy(phase = "上传中", paused = false)
            emit(st)
            true
        }
    }

    // ---------------------------------------------------------------- 内部工具

    /** optString 对 JSON null 会返回字符串 "null"，统一清掉。 */
    private fun str(o: JSONObject, key: String): String {
        val v = o.optString(key, "")
        return if (v == "null") "" else v
    }

    /**
     * 每个点该填的 isValid。小程序语义（见 refs/FINDINGS.md 4.1）：
     *   "1" = 正常
     *   "2" = 落在禁跑区（jpq），服务端 calcDistance 返回 0，不计里程
     *
     * 原来这里无脑写 "1"，和上面自检打出来的 banned 计数自相矛盾。
     * 轨迹内缩后 banned 正常就是 0，但真出现时不应该瞒着服务端。
     */
    private fun validFlags(track: List<TrackPoint>, area: Area): BooleanArray =
        BooleanArray(track.size) { i ->
            val p = track[i]
            !area.excludes.any { Geo.pointInPoly(p.lat, p.lon, it) }
        }

    private fun buildPoints(
        track: List<TrackPoint>,
        valid: BooleanArray,
        recordNo: String,
        area: Area,
        startMs: Long
    ): List<JSONObject> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        return track.mapIndexed { i, p ->
            JSONObject().apply {
                put("sportRecordNo", recordNo)
                put("longitude", p.lon)
                put("latitude", p.lat)
                put("placeName", area.placeName)
                put("placeCode", area.placeCode)
                put("collectTime", fmt.format(Date(startMs + p.secOffset * 1000L)))
                put("isValid", if (valid[i]) "1" else "2")
            }
        }
    }

    /**
     * 15 秒滚动窗口的配速区间 (最快, 最慢)，秒/公里。
     *
     * 逐秒的瞬时配速在停顿处趋于无穷，取极值没意义；滚动窗口才对应
     * "跑者感受到的配速"。界面用它显示"分段配速 X ~ Y"。
     */
    private fun paceRangeOf(track: List<TrackPoint>, win: Int = 15): Pair<Double, Double> {
        if (track.size <= win + 1) return Pair(0.0, 0.0)
        var fastest = Double.MAX_VALUE
        var slowest = 0.0
        for (i in 0..track.size - 1 - win) {
            var seg = 0.0
            for (k in i until i + win) {
                seg += Geo.hav(track[k].lat, track[k].lon, track[k + 1].lat, track[k + 1].lon)
            }
            if (seg <= 0.1) continue
            val p = win / (seg / 1000.0)
            if (p < fastest) fastest = p
            if (p > slowest) slowest = p
        }
        if (fastest == Double.MAX_VALUE) return Pair(0.0, 0.0)
        return Pair(fastest, slowest)
    }

    /** 与小程序一致：每 n 个点一批，并把上一批的末点作为下一批的首点。 */
    private fun batch(pts: List<JSONObject>, n: Int): List<List<JSONObject>> {
        val out = ArrayList<List<JSONObject>>()
        var cur = ArrayList<JSONObject>()
        for (p in pts) {
            cur.add(p)
            if (cur.size >= n) {
                out.add(cur)
                cur = arrayListOf(cur.last())
            }
        }
        if (cur.size > 1) out.add(cur)
        return out
    }

    companion object {
        /** 记录列表每页条数：服务端硬上限就是 100，传更大也只回 100。 */
        private const val PAGE_SIZE = 100

        /** 最多翻 10 页（1000 条）。真要更多，早就该在服务端做筛选了。 */
        private const val MAX_PAGES = 10

        /** "5:30" → 330，"5.5" → 330，"330" → 330 */
        fun parsePace(s: String): Double? {
            val t = s.trim()
            if (t.isEmpty()) return null
            if (t.contains(":")) {
                val a = t.split(":")
                if (a.size != 2) return null
                val m = a[0].toDoubleOrNull() ?: return null
                val sec = a[1].toDoubleOrNull() ?: return null
                return m * 60 + sec
            }
            val v = t.toDoubleOrNull() ?: return null
            return if (v > 60) v else v * 60
        }

        fun fmtPace(secPerKm: Double): String {
            if (secPerKm <= 0) return "--"
            val m = (secPerKm / 60).toInt()
            val s = (secPerKm % 60).toInt()
            return "$m'${"%02d".format(s)}\""
        }

        fun fmtDuration(sec: Int): String {
            if (sec < 0) return "--:--"
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
