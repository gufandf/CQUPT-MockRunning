/*
 * GPX 导出：把一次跑步的轨迹点序列化成 GPX 1.1 文本。
 *
 * 为什么要这个类：打卡记录留在本地只有「一行文字」的价值，导出成 GPX 之后
 * 就能丢进运动手表、轨迹回放网站、骑行软件，让别人（或者以后的自己）真的
 * 看见那条路线跑成了什么样。选址这里刻意做成纯字符串拼接 —— 项目底线是
 * 零第三方依赖，为一个几十行的导出功能去引 XML 序列化库完全不划算，
 * 而且 GPX 的结构稳定到不需要通用 XML 写入器。
 *
 * 三个必须守住的细节（踩过一次就会毁掉整份文件）：
 *   1. 时间只能是 UTC。GPX 的 <time> 是 xsd:dateTime，带 Z 就代表 UTC；
 *      如果跟着系统时区走，导出的轨迹会整体偏移 8 小时，导入方按 UTC 解析后
 *      时间戳直接落错天。
 *   2. 坐标小数点必须是 '.'。某些 locale（de/fr/ru）下 String.format 会吐逗号，
 *      于是 lat="29,5322023" 这种非法的 xsd:decimal，解析器当场报错。
 *   3. XML 特殊字符必须转义。场地名里出现 '&' 是很正常的事（"A&B 球场"），
 *      不转义就是格式错误的文档。
 */
package com.whale.campusrun

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object GpxExport {

    /** 一个轨迹点：纬度、经度、该点的秒偏移（相对起点）。 */
    data class Point(val lat: Double, val lon: Double, val secOffset: Int)

    /** creator 写死成工具名，方便日后在别人的轨迹站上认领自己导出的文件。 */
    private const val CREATOR = "JingjingYuepao"

    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"

    /** 7 位小数 ≈ 1cm，田径场尺度下足够了；再多只是给文件加体积。 */
    private const val COORD_PATTERN = "%.7f"

    /**
     * 生成 GPX 1.1 文档文本。
     *
     * @param recordNo  记录号，用作 <name>（为空则用 "track"）
     * @param placeName 场地名，写进 <desc>
     * @param startMs   起点毫秒时间戳（UTC epoch millis），每点时间 = startMs + secOffset*1000
     * @param points    轨迹点，按时间正序
     */
    fun toGpx(
        recordNo: String,
        placeName: String,
        startMs: Long,
        points: List<Point>
    ): String {
        // 空记录号在 GPX 里没有意义（<name> 是给人和软件看的定位标签），
        // 用 "track" 兜底，保证文件在任何查看器里都有个可读的名字。
        val name = escape(recordNo.ifBlank { "track" })
        // 空场地名没有信息量，按 GPX 的可选元素语义直接省掉 <desc>，
        // 而不是留一个 <desc></desc> 占位。
        val desc = escape(placeName)
        val hasDesc = desc.isNotEmpty()
        // metadata 的 <time> 用起点时间：没有轨迹点时它就是这次记录唯一的
        // 时间信息；有点时它也等于第一个点的时间（points 按正序且首点偏移 0）。
        val startTime = formatUtc(startMs)

        val sb = StringBuilder(256 + points.size * 96)

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(CREATOR).append('"')
        sb.append(" xmlns=\"").append(GPX_NS).append('"')
        sb.append(" xmlns:xsi=\"").append(XSI_NS).append('"')
        // schemaLocation 是「命名空间 → xsd 地址」的成对列表，两段都不能省，
        // 严格的校验器（gpsbabel / gpx.studio）会拿它去取 schema。
        sb.append(" xsi:schemaLocation=\"").append(GPX_NS).append(' ').append(GPX_NS)
            .append("/gpx.xsd\"")
        sb.append(">\n")

        sb.append("  <metadata><name>").append(name).append("</name>")
        if (hasDesc) sb.append("<desc>").append(desc).append("</desc>")
        sb.append("<time>").append(startTime).append("</time></metadata>\n")

        sb.append("  <trk><name>").append(name).append("</name>")
        if (hasDesc) sb.append("<desc>").append(desc).append("</desc>")

        if (points.isEmpty()) {
            // 空轨迹也要是一份合法文档：<trkseg/> 自闭合，别留悬空标签。
            sb.append("<trkseg/>\n")
            sb.append("  </trk>\n")
        } else {
            sb.append("<trkseg>\n")
            for (p in points) {
                sb.append("    <trkpt lat=\"").append(coord(p.lat))
                    .append("\" lon=\"").append(coord(p.lon)).append("\">")
                sb.append("<time>").append(formatUtc(startMs + p.secOffset.toLong() * 1000L))
                    .append("</time>")
                sb.append("</trkpt>\n")
            }
            sb.append("  </trkseg></trk>\n")
        }

        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * 坐标格式化。
     *
     * Locale.US 是硬要求：xsd:decimal 只认 '.'，而德语/法语等 locale 的
     * %f 会输出 '29,53'，整份 GPX 立刻变成非法 XML 数据。
     * 负零（-0.0 或 -1e-9 这类格式化后舍入为 0 的负数）也要清掉：
     * "lat=\"-0.0000000\"" 虽然语法合法，但会被一些查看器当成异常值处理，
     * 而且它明显不是我们想表达的坐标。双保险：先规整 -0.0，再兜一次格式化结果。
     */
    private fun coord(v: Double): String {
        val norm = if (v == 0.0) 0.0 else v
        val s = String.format(Locale.US, COORD_PATTERN, norm)
        return if (s == "-0.0000000") "0.0000000" else s
    }

    /**
     * UTC 时间格式化。
     *
     * SimpleDateFormat 不是线程安全的，而这个 object 是进程内单例，
     * 所以每次调用都新建一个实例 —— 导出是低频操作，这点分配无所谓。
     * 显式设 UTC 时区 + Locale.US：前者保证不受系统时区影响，
     * 后者防止某些 locale 输出非 ASCII 数字或本地化日期名。
     */
    private fun formatUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    /**
     * XML 文本转义。
     *
     * '&' 必须最先替换，否则后续替换出来的 '&amp;' / '&lt;' 会被二次转义成
     * '&amp;amp;'。引号也一起转：虽然这里只往文本节点里写，但万一以后有人
     * 把这些值挪到属性里，转义过的版本仍然是安全的。
     */
    private fun escape(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
