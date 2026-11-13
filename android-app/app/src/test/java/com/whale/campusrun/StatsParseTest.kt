/*
 * 成绩记录日期解析的测试。
 *
 * 这块最容易悄悄坏：字段名是猜的，猜错了界面不会报错，只会一直显示「第 N 次」。
 * 所以把三条退让路径都钉住：记录号尾部时间戳、秒级时间戳、毫秒级时间戳。
 */
package com.whale.campusrun

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsParseTest {

    @Test
    fun `记录号尾部的 14 位时间戳能取到月日`() {
        assertEquals(
            "11-09",
            StatsParse.dateLabel("oxxxxxxxx_T1001_20261109235959", null)
        )
    }

    @Test
    fun `毫秒时间戳能取到月日`() {
        // 2026-11-09 23:00:00 +08:00 = 1762700400000
        assertEquals("11-09", StatsParse.dateLabel(null, "1762700400000"))
    }

    @Test
    fun `秒级时间戳也能取到月日`() {
        assertEquals("11-09", StatsParse.dateLabel("", "1762700400"))
    }

    /**
     * `sportsResultNo`（成绩表那个字段名）和 `sportRecordNo` 同构：
     * `<unifyId>_<placeCode>_<yyyyMMddHHmmss>`。成绩页读的是前一个，
     * 万一哪天有人以为它不带时间戳、把记录号从 dateLabel 里摘掉，日期就会全空。
     */
    @Test
    fun `成绩表的 sportsResultNo 同样能取到月日`() {
        assertEquals(
            "11-12",
            StatsParse.dateLabel("1234567_T1001_20261112120538", null)
        )
    }

    /** 记录号第 2 段是场地编号，轨迹页靠它去缓存里找围栏。 */
    @Test
    fun `记录号第二段是场地编号`() {
        assertEquals("T1001", "1234567_T1001_20261112120538".split("_").getOrNull(1))
        // 记录号不规范时不能抛，返回 null 由调用方兜底
        assertEquals(null, "nounderscore".split("_").getOrNull(1))
    }

    @Test
    fun `拿不到时间就返回空串`() {
        assertEquals("", StatsParse.dateLabel(null, null))
        assertEquals("", StatsParse.dateLabel("abc", "not-a-number"))
        // 尾部不是 14 位数字，别硬当日期
        assertEquals("", StatsParse.dateLabel("abc_12345", null))
    }
}
