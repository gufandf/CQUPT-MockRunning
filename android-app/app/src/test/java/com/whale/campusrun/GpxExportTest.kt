/*
 * GpxExport 单元测试：纯 JVM，不碰 Android API。
 *
 * 这里断言的是「文件能不能被别的软件打开」，所以不比对整份字符串，
 * 只钉住 GPX 1.1 里那些错了就整份报废的点：根元素与命名空间、UTC 时间、
 * 坐标的 '.' 小数点与 7 位精度、XML 转义、空轨迹的合法结构。
 */
package com.whale.campusrun

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GpxExportTest {

    private val originalZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    /** 2026-11-12T04:18:49Z 的 epoch 毫秒，写死避免用被测代码自己算时间。 */
    private val startMs = 1_794_457_129_000L

    private val twoPoints = listOf(
        GpxExport.Point(29.5322023, 106.6071670, 0),
        GpxExport.Point(29.5324001, 106.6072002, 20)
    )

    @Test
    fun normalTrack_hasGpx11RootAndUtcTimes() {
        val gpx = GpxExport.toGpx("R20261112001", "风华运动场", startMs, twoPoints)

        // 根元素三件套：版本、默认命名空间、schemaLocation 都必须在，
        // 否则 gpsbabel / gpx.studio 会把它当未知格式。
        assertTrue(gpx, gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx, gpx.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue(gpx, gpx.contains("http://www.topografix.com/GPX/1/1/gpx.xsd"))

        assertTrue(gpx, gpx.contains("<name>R20261112001</name>"))
        assertTrue(gpx, gpx.contains("<desc>风华运动场</desc>"))
        assertTrue(gpx, gpx.contains("<metadata>"))

        // 首点时间 = startMs，次点 = startMs + 20s。
        assertTrue(gpx, gpx.contains("<time>2026-11-12T04:18:49Z</time>"))
        assertTrue(gpx, gpx.contains("<time>2026-11-12T04:19:09Z</time>"))

        // 结构完整性：trkseg 里两个点，文档以 </gpx> 收尾。
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx, gpx.trimEnd().endsWith("</gpx>"))
        assertTrue(gpx, gpx.endsWith("\n"))
    }

    @Test
    fun coordinates_areSevenDecimalsWithDot() {
        val gpx = GpxExport.toGpx("R1", "风华运动场", startMs, twoPoints)

        assertTrue(gpx, gpx.contains("<trkpt lat=\"29.5322023\" lon=\"106.6071670\">"))
        // 逗号小数点会让 xsd:decimal 直接非法，这是最容易被 locale 坑掉的一处。
        assertFalse(gpx, gpx.contains("29,5322023"))
        // 7 位小数：多一位少一位都说明格式串被改坏了。
        assertTrue(gpx, Regex("lat=\"-?\\d+\\.\\d{7}\"").containsMatchIn(gpx))
        assertFalse(gpx, Regex("lat=\"-?\\d+\\.\\d{8}").containsMatchIn(gpx))
    }

    @Test
    fun time_isUtcRegardlessOfSystemTimeZone() {
        // 上海时区如果漏进格式化，会变成 2026-11-12T12:18:49Z 这种整体偏 8 小时的值。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val gpx = GpxExport.toGpx("R1", "风华运动场", startMs, twoPoints)
        assertTrue(gpx, gpx.contains("<time>2026-11-12T04:18:49Z</time>"))
        assertTrue(gpx, gpx.contains("<time>2026-11-12T04:19:09Z</time>"))
        assertFalse(gpx, gpx.contains("2026-11-12T12:18:49Z"))

        // 反向再验一次：把默认时区换成另一个值，输出必须逐字不变。
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val gpx2 = GpxExport.toGpx("R1", "风华运动场", startMs, twoPoints)
        assertEquals(gpx, gpx2)

        // 顺带确认测试自己算的时间戳没写错（防止断言字符串与被测逻辑一起漂）。
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        assertEquals("2026-11-12T04:18:49Z", fmt.format(Date(startMs)))
    }

    @Test
    fun coordinates_useDotEvenUnderCommaDecimalLocale() {
        // de_DE 的 %f 会吐逗号小数点，xsd:decimal 立刻非法；Locale.US 必须压住它。
        Locale.setDefault(Locale.GERMANY)
        val gpx = GpxExport.toGpx("R1", "风华运动场", startMs, twoPoints)
        assertTrue(gpx, gpx.contains("lat=\"29.5322023\""))
        assertFalse(gpx, gpx.contains("29,5322023"))
    }

    @Test
    fun xmlSpecialCharsAreEscaped() {
        val gpx = GpxExport.toGpx("R&1", "A&B <C>", startMs, twoPoints)

        assertTrue(gpx, gpx.contains("<desc>A&amp;B &lt;C&gt;</desc>"))
        assertTrue(gpx, gpx.contains("<name>R&amp;1</name>"))
        // 没有裸的 &B 就意味着 '&' 确实被转义了（'<C>' 里的 '>' 也要转成 &gt;）。
        assertFalse(gpx, gpx.contains("&B"))
        assertTrue(gpx, gpx.contains("&gt;"))
        // 转义不能把已经转过的实体再套一层。
        assertFalse(gpx, gpx.contains("&amp;amp;"))
    }

    @Test
    fun emptyTrack_isStillValidGpx() {
        val gpx = GpxExport.toGpx("", "", startMs, emptyList())

        assertTrue(gpx, gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx, gpx.contains("<trkseg"))
        // recordNo 为空时 name 兜底成 "track"；placeName 为空时 desc 整个省略。
        assertTrue(gpx, gpx.contains("<name>track</name>"))
        assertFalse(gpx, gpx.contains("<desc>"))
        // 没有点时 metadata 的 <time> 用 startMs，这是整份文件唯一的时间来源。
        assertTrue(gpx, gpx.contains("<time>2026-11-12T04:18:49Z</time>"))
        assertEquals(0, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx, gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun negativeZeroIsNormalized() {
        val gpx = GpxExport.toGpx("R1", "", startMs, listOf(GpxExport.Point(-0.0, 0.0, 0)))
        assertTrue(gpx, gpx.contains("<trkpt lat=\"0.0000000\" lon=\"0.0000000\">"))
        assertFalse(gpx, gpx.contains("-0.0000000"))
    }
}
