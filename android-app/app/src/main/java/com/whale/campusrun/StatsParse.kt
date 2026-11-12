/*
 * 成绩记录的字段解析（纯 JVM，可单测）。
 *
 * `/sportsResult/list` 没有公开字段文档，唯一可靠的线索是
 * `sportRecordNo` = `<unifyId>_<placeCode>_<yyyyMMddHHmmss>`（refs/FINDINGS.md 实测）。
 * 所以按「记录号尾部 14 位 → 时间戳字段 → 空」三级退让 ——
 * 拿不到就返回空串，界面用「第 N 次」兜底，绝不编一个假日期出来。
 */
package com.whale.campusrun

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object StatsParse {

    private val MD = SimpleDateFormat("MM-dd", Locale.getDefault())

    /** 从记录号 / 时间戳字段里取 "MM-dd"；都没有就返回空串。 */
    fun dateLabel(recordNo: String?, timestamp: String?): String {
        val no = recordNo.orEmpty()
        if (no.length >= 14) {
            val tail = no.takeLast(14)
            if (tail.all { it.isDigit() }) {
                return "${tail.substring(4, 6)}-${tail.substring(6, 8)}"
            }
        }
        val num = timestamp.orEmpty().toLongOrNull() ?: return ""
        // 10 位是秒、13 位是毫秒，两种都认
        val ms = if (num < 10_000_000_000L) num * 1000 else num
        return MD.format(Date(ms))
    }
}
