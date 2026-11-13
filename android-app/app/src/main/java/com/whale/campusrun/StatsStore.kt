/*
 * 成绩记录的本地缓存 + 启动时统一拉取。
 *
 * 为什么不再「打开成绩页才拉」：
 *   成绩是「打开就想看到」的东西，而拉取要换 token + 调 /sportsResult/list，
 *   弱网下两三秒。以前的做法是进页面先渲染「加载中」再补上数字 ——
 *   用户看到的是页面在跳。现在改成**启动时就拉一次并落盘**，
 *   打开成绩页直接读快照，页面一次成型。
 *
 * 为什么不塞进 ProfileStore / AreaCache：
 *   那两个是「场地」的事（多边形很大、按 openid 区分）。成绩是另一份数据，
 *   生命周期也不同（场地只在换账号时失效，成绩每次启动都刷新）。
 *   混在一起会让「清场地」顺手清掉成绩。
 *
 * 存哪儿：SharedPreferences 里一段 JSON 数组，字段与网络响应同构
 * （`StatsRow` 已经把它拍平了，但落盘保留原始字段更抗上游改字段）。
 */
package com.whale.campusrun

import android.content.Context

/**
 * 一条缓存的成绩。
 *
 * 与 [StatRow] 的区别：这里**不区分有效 / 无效**（v1.11.1 起列表一视同仁），
 * 只把服务端给的原字段留住，展示层自己决定怎么读。
 */
data class StatsRecord(
    val recordNo: String,
    val placeName: String,
    val placeCode: String,
    val distanceKm: Double,
    val durationSec: Int,
    val dateLabel: String,
    /** 服务端判定的有效性。列表照样展示，只是卡片上多一个小标记。 */
    val valid: Boolean,
    val reason: String,
    /** 该条的配速（秒/公里），距离为 0 时是 0。 */
    val paceSecKm: Double
)

/** 启动时拉一次的结果快照。 */
data class StatsSnapshot(
    val openid: String,
    val records: List<StatsRecord>,
    /** 拉取成功的时间戳（毫秒），0 表示这份是失败后留下的旧缓存。 */
    val fetchedAt: Long,
    /** 上次拉取失败的原因；成功时为 null。页面用它决定要不要在顶部提示一句。 */
    val error: String? = null
)

/**
 * 成绩中枢：内存快照 + 磁盘缓存 + 「启动时拉一次」的唯一入口。
 *
 * 和 [AreaStore] 一样是个 object —— 主页、设置页、轨迹页三者都要读同一份，
 * 各持一份就会出现「设置页拉到了、主页还是空的」。
 */
object StatsStore {

    private const val PREFS = "campusrun"
    private const val KEY_JSON = "statsCache"
    private const val KEY_OPENID = "statsCacheOpenid"
    private const val KEY_AT = "statsCacheAt"

    @Volatile
    var snapshot: StatsSnapshot? = null
        private set

    /** 每次内容变化 +1，页面据此判断要不要重绘（和 AreaStore.revision 同一套观察方式）。 */
    @Volatile
    var revision: Long = 0L
        private set

    @Volatile
    var loading: Boolean = false
        private set

    /** 冷启动时把上次落盘的快照读进内存。只认同一个 openid 的缓存。 */
    fun restore(ctx: Context, openid: String) {
        if (openid.isEmpty()) return
        if (snapshot?.openid == openid) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getString(KEY_OPENID, "") != openid) return
        val raw = sp.getString(KEY_JSON, "").orEmpty()
        if (raw.isEmpty()) return
        val list = try {
            decode(raw)
        } catch (_: Throwable) {
            return
        }
        snapshot = StatsSnapshot(
            openid = openid,
            records = list,
            fetchedAt = sp.getLong(KEY_AT, 0L)
        )
        revision++
    }

    /**
     * 拉取结果回写。`ok == false` 时**保留旧记录**、只更新错误信息 ——
     * 断网时把上一次的成绩清空是最糟的选择。
     */
    fun publish(openid: String, records: List<StatsRecord>?, error: String?) {
        val prev = snapshot
        val merged = records ?: prev?.takeIf { it.openid == openid }?.records ?: emptyList()
        snapshot = StatsSnapshot(
            openid = openid,
            records = merged,
            fetchedAt = if (records != null) System.currentTimeMillis()
            else prev?.takeIf { it.openid == openid }?.fetchedAt ?: 0L,
            error = error
        )
        revision++
    }

    /** 落盘。失败的结果不写，免得把「上次成功的时间」冲掉。 */
    fun save(ctx: Context, snapshot: StatsSnapshot) {
        if (snapshot.openid.isEmpty()) return
        if (snapshot.error != null && snapshot.fetchedAt == 0L) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_OPENID, snapshot.openid)
            .putString(KEY_JSON, encode(snapshot.records))
            .putLong(KEY_AT, snapshot.fetchedAt)
            .apply()
    }

    /** 换账号时清空 —— 别人的成绩不能留在本机。 */
    fun invalidate() {
        snapshot = null
        revision++
    }

    fun clear(ctx: Context) {
        invalidate()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_JSON).remove(KEY_OPENID).remove(KEY_AT).apply()
    }

    /** 正在拉的时候不要重复发起（启动 + 手动刷新可能撞车）。 */
    fun beginLoad(): Boolean {
        if (loading) return false
        loading = true
        return true
    }

    fun endLoad() {
        loading = false
    }

    // ---------------------------------------------------------------- 序列化

    /*
     * 缓存格式是**自己定的一行一条**，不走 org.json。
     *
     * 为什么不用 org.json：它在 AGP 的 JVM 单测里是个空壳，所有方法都直接
     * `throw RuntimeException("Stub!")`，于是「缓存写进去能不能读回来」这件事
     * 在单测里根本验不了 —— 而这正是缓存最容易错的地方（字段漏一个、精度掉了、
     * 顺序变了，界面上都看不出来，只是重启之后数据变了）。
     *
     * 格式：`v1` 头 + 每条记录一行，字段用 `\u001F`（单元分隔符）隔开，
     * 换行/分隔符在字段里出现时转义成 `\\n` / `\\u001f`。
     * 选择分隔符而不是 JSON 的理由：字段里不会有分隔符，解析就是 split，
     * 出错面比手写 JSON 解析器小得多；文件也只在本机重启时读一次。
     */

    /**
     * 缓存版本。改这里等于让旧缓存自然失效（decode 遇到不认识的版本直接返回空）。
     *
     * v2：v1 那份里 `recordNo` 全是空的 —— 当时从 `/sportsResult/list` 读的是
     * `sportRecordNo`，而那个接口的字段叫 `sportsResultNo`。空记录号会让成绩页
     * 每条记录都点不进轨迹，留着这份缓存只会继续骗人，所以直接作废重拉。
     */
    private const val FORMAT_VERSION = "v2"
    private const val RECORD_SEP = '\u001F'

    /** 一条记录存成一行（不含换行）。顺序即入参顺序。 */
    private fun lineOf(r: StatsRecord): String = listOf(
        escape(r.recordNo),
        escape(r.placeName),
        escape(r.placeCode),
        r.distanceKm.toString(),
        r.durationSec.toString(),
        escape(r.dateLabel),
        if (r.valid) "1" else "0",
        escape(r.reason),
        r.paceSecKm.toString()
    ).joinToString(RECORD_SEP.toString())

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace(RECORD_SEP.toString(), "\\u001f")

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'u' -> {
                        // \u001f：分隔符本身被写进了字段
                        if (i + 5 < s.length && s.substring(i + 2, i + 6) == "001f") {
                            sb.append(RECORD_SEP); i += 6
                        } else { sb.append(c); i++ }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** 编码成落盘字符串。空列表也写版本头，方便将来平滑升级格式。 */
    fun encode(rows: List<StatsRecord>): String {
        val sb = StringBuilder()
        sb.append(FORMAT_VERSION)
        for (r in rows) sb.append('\n').append(lineOf(r))
        return sb.toString()
    }

    fun decode(raw: String): List<StatsRecord> {
        if (raw.isEmpty()) return emptyList()
        val lines = raw.split('\n')
        // 第一行是版本头；不认识就当作空缓存，宁可重拉也不要读出一堆错数据
        if (lines.isEmpty() || lines[0] != FORMAT_VERSION) return emptyList()
        val out = ArrayList<StatsRecord>(lines.size - 1)
        for (k in 1 until lines.size) {
            val line = lines[k]
            if (line.isEmpty()) continue
            val f = line.split(RECORD_SEP)
            if (f.size < 9) continue
            out.add(
                StatsRecord(
                    recordNo = unescape(f[0]),
                    placeName = unescape(f[1]),
                    placeCode = unescape(f[2]),
                    distanceKm = f[3].toDoubleOrNull() ?: 0.0,
                    durationSec = f[4].toIntOrNull() ?: 0,
                    dateLabel = unescape(f[5]),
                    valid = f[6] == "1",
                    reason = unescape(f[7]),
                    paceSecKm = f[8].toDoubleOrNull() ?: 0.0
                )
            )
        }
        return out
    }
}
