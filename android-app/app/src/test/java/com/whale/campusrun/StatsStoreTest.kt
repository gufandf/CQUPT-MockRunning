/*
 * StatsStore 单元测试：成绩缓存层的「序列化」与「合并语义」。
 *
 * 为什么值得单测：这两块坏掉都不会崩，只会安静地给人错数据 ——
 *   · 序列化坏 → 重启后成绩页空了一片，或者日期/配速悄悄变成 0；
 *   · 合并语义坏 → 弱网下把上次的成绩清空（用户以为记录被删了），
 *     或者换账号后把上一个人的成绩当成自己的展示出来。
 * 界面上的表现都是「数据不见了」而不是报错，所以必须在 JVM 上钉死。
 *
 * 只覆盖纯逻辑：encode / decode / publish / invalidate / revision。
 * restore / save / clear 需要 Context，留给真机验证，这里不碰 Android API。
 *
 * StatsStore 是 object（单例），测试之间会互相污染，所以每个用例前先
 * invalidate() 复位，用例结束再复位一次，避免影响同进程里其它测试类。
 *
 * 序列化为什么不用 org.json：AGP 的 JVM 单测挂的是 mockable android.jar，
 * 里面 org.json 的每个方法都是 `throw RuntimeException("Stub!")`，
 * 「写进去能不能读回来」这件事在单测里根本验不了 —— 而那正是缓存最容易错的地方。
 * 所以 StatsStore 的缓存格式改成自己定的一行一条（见 `encode` 上方注释），
 * 纯 Kotlin 字符串处理，这里就能真跑。
 */
package com.whale.campusrun

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StatsStoreTest {

    @Before
    fun setUp() {
        StatsStore.invalidate()
    }

    @After
    fun tearDown() {
        StatsStore.invalidate()
    }

    // ------------------------------------------------------------ 样本数据

    /** 正常一条：有效、有日期、距离配速都正常。 */
    private val good = StatsRecord(
        recordNo = "oABCDEFGH_T1001_20261109235959",
        placeName = "风华运动场",
        placeCode = "T1001",
        distanceKm = 2.0,
        durationSec = 690,
        dateLabel = "11-09",
        valid = true,
        reason = "",
        paceSecKm = 345.0
    )

    /** 服务端判定无效的一条：valid=false 且带原因，但列表照样要展示。 */
    private val rejected = StatsRecord(
        recordNo = "oABCDEFGH_T1005_20261110235959",
        placeName = "太极运动场",
        placeCode = "T1005",
        distanceKm = 1.98,
        durationSec = 402,
        dateLabel = "11-10",
        valid = false,
        reason = "配速超出规定范围",
        paceSecKm = 203.0303030303
    )

    /** 退化的一条：日期解析不出来（空串）、距离为 0，配速跟着也是 0。 */
    private val degenerate = StatsRecord(
        recordNo = "oABCDEFGH_T1014_",
        placeName = "宁静苑",
        placeCode = "T1014",
        distanceKm = 0.0,
        durationSec = 0,
        dateLabel = "",
        valid = false,
        reason = "距离不足",
        paceSecKm = 0.0
    )

    /** 样本集：valid / invalid+reason / 空日期+零距离，三种形态都在里面。 */
    private val three = listOf(good, rejected, degenerate)

    /**
     * Double 按容差比：`toString()` / `toDouble()` 来回一趟不必指望位级相同，
     * 但 1e-9 之内等价就是「没丢精度」。
     */
    private fun assertRecord(expected: StatsRecord, actual: StatsRecord, where: String) {
        assertEquals("$where recordNo", expected.recordNo, actual.recordNo)
        assertEquals("$where placeName", expected.placeName, actual.placeName)
        assertEquals("$where placeCode", expected.placeCode, actual.placeCode)
        assertEquals("$where distanceKm", expected.distanceKm, actual.distanceKm, 1e-9)
        assertEquals("$where durationSec", expected.durationSec, actual.durationSec)
        assertEquals("$where dateLabel", expected.dateLabel, actual.dateLabel)
        assertEquals("$where valid", expected.valid, actual.valid)
        assertEquals("$where reason", expected.reason, actual.reason)
        assertEquals("$where paceSecKm", expected.paceSecKm, actual.paceSecKm, 1e-9)
    }

    // -------------------------------------------------------------- 序列化

    /**
     * 往返一致：所有字段都要原样回来。
     *
     * 只测一个字段是不够的 —— 这九个字段分别由 put/optString/optDouble/optInt/optBoolean
     * 承接，漏掉任何一个 key，界面就少一块信息（比如 reason 丢了就看不出为什么无效）。
     */
    @Test
    fun `三条记录往返后逐字段不变`() {
        val back = StatsStore.decode(StatsStore.encode(three))
        assertEquals("条数变了", three.size, back.size)
        for (i in three.indices) {
            assertRecord(three[i], back[i], "第 $i 条")
        }
    }

    /**
     * 落盘走的是「对象 → 文本 → 对象」这条路，比纯内存往返更容易丢东西
     * （小数点被格式化、空串被吞、布尔变字符串）。这里模拟磁盘上的真实形态，
     * 免得 restart 之后才发现。
     */
    @Test
    fun `经过落盘文本往返后仍然一致`() {
        val text = StatsStore.encode(three)
        val back = StatsStore.decode(text)
        assertEquals("从文本恢复后条数变了", three.size, back.size)
        for (i in three.indices) {
            assertRecord(three[i], back[i], "文本往返第 $i 条")
        }
        // 空串日期必须还是空串，不能被默认值补成别的东西
        assertEquals("", back[2].dateLabel)
        // 无效记录的原因不能丢，成绩页要靠它显示小标记
        assertEquals("配速超出规定范围", back[1].reason)
        assertTrue("valid=true 的标记丢了", back[0].valid)
    }

    /** 空列表是合法状态（新生一个记录都没跑过），编解码都不能抛。 */
    @Test
    fun `空列表编码只剩版本头且解码回空列表`() {
        val text = StatsStore.encode(emptyList())
        assertTrue("空列表不该编出记录行", StatsStore.decode(text).isEmpty())
        // 空串 / 乱码都当作「没有缓存」，宁可重拉也不要读出一堆错数据
        assertTrue(StatsStore.decode("").isEmpty())
        assertTrue(StatsStore.decode("garbage").isEmpty())
    }

    /**
     * 顺序保持：成绩页按服务端顺序展示，乱序会让人以为记录顺序变了
     * （最上面那条不再是最近一次跑步）。
     */
    @Test
    fun `往返后顺序与服务端下发一致`() {
        val back = StatsStore.decode(StatsStore.encode(three))
        assertEquals(
            "顺序被打乱",
            listOf("oABCDEFGH_T1001_20261109235959", "oABCDEFGH_T1005_20261110235959", "oABCDEFGH_T1014_"),
            back.map { it.recordNo }
        )
    }

    /**
     * 字段缺失时退到默认值而不是抛异常：上游改字段名 / 老版本落的盘，
     * 都只会让这几条变成「空记录」，不该让整份缓存读不出来
     * （restore 只 catch 异常，一条坏数据不该连累其余记录）。
     */
    /**
     * 字段数量不对的行直接跳过，不能让一条坏数据连累整份缓存 ——
     * 上游改字段、老版本落的盘、写一半被断电，都可能留下半行。
     */
    @Test
    fun `残缺的行被跳过而不是拖垮整份缓存`() {
        val text = StatsStore.encode(three)
        val broken = text + "\n只有\u001F两\u001F个字段"
        val out = StatsStore.decode(broken)
        assertEquals("好记录应该照常读出来", 3, out.size)
        assertEquals(good.recordNo, out[0].recordNo)
    }

    /**
     * 字段里出现分隔符 / 换行时不能把行拆坏。
     *
     * 场地名和原因都是服务端下发的，出现制表符、换行、或者那个单元分隔符
     * 都不是不可能；不转义的话一条记录会被切成两半，重启后成绩页就少一条、
     * 多一条乱码。
     */
    @Test
    fun `字段里的分隔符与换行会被转义`() {
        val nasty = good.copy(
            placeName = "风华\n运动场\u001F二期",
            reason = "含分隔符\u001F与换行\n的记录"
        )
        val back = StatsStore.decode(StatsStore.encode(listOf(nasty)))
        assertEquals(1, back.size)
        assertEquals(nasty.placeName, back[0].placeName)
        assertEquals(nasty.reason, back[0].reason)
        assertEquals("其余字段不该受影响", nasty.recordNo, back[0].recordNo)
    }

    // ------------------------------------------------------------ publish 合并

    /**
     * 失败不能清空已有成绩。
     *
     * 断网时把上一次的成绩抹掉是最糟的选择：用户刚跑完的记录会「消失」，
     * 而且 fetchedAt 被冲成 0 之后，连「这是上次的结果」都判断不了。
     */
    @Test
    fun `失败时保留旧记录只更新错误`() {
        StatsStore.publish("oA", listOf(good, rejected), null)
        val first = StatsStore.snapshot
        assertNotNull("成功发布后应有快照", first)
        assertEquals(2, first!!.records.size)
        assertNull("成功时不该有错误", first.error)
        assertTrue("成功发布必须记下时间", first.fetchedAt > 0L)

        StatsStore.publish("oA", null, "网络失败")
        val after = StatsStore.snapshot!!
        assertEquals("失败把记录清空了", listOf(good, rejected), after.records)
        assertEquals("网络失败", after.error)
        assertEquals("失败把上次成功时间冲掉了", first.fetchedAt, after.fetchedAt)
        assertTrue("fetchedAt 不该归零", after.fetchedAt > 0L)
        assertEquals("openid 不该变", "oA", after.openid)
    }

    /** 成功必须清掉上次的错误提示，否则成绩页会一直挂着那句「网络失败」。 */
    @Test
    fun `成功时清掉之前的错误`() {
        StatsStore.publish("oA", listOf(good), null)
        StatsStore.publish("oA", null, "网络失败")
        assertEquals("网络失败", StatsStore.snapshot?.error)

        StatsStore.publish("oA", listOf(degenerate), null)
        val after = StatsStore.snapshot!!
        assertNull("成功后错误提示没清掉", after.error)
        assertEquals("成功应以新结果整体替换", listOf(degenerate), after.records)
        assertTrue("成功必须刷新时间戳", after.fetchedAt > 0L)
    }

    /**
     * 换账号不继承别人的记录。
     *
     * 「失败时保留旧记录」的前提是同一个 openid；换了账号还沿用旧记录，
     * 界面会把上一个人的成绩当成自己的展示出来 —— 这是隐私问题，不只是显示问题。
     */
    @Test
    fun `换 openid 失败时不继承别人的记录`() {
        StatsStore.publish("A", listOf(good), null)
        StatsStore.publish("B", null, "失败")

        val after = StatsStore.snapshot!!
        assertEquals("B", after.openid)
        assertTrue("把 A 的记录当成 B 的了", after.records.isEmpty())
        assertEquals("失败", after.error)
        assertEquals("B 从没成功过，时间戳应为 0", 0L, after.fetchedAt)
    }

    /** 换账号成功时当然是直接替换，不该残留 A 的任何一条。 */
    @Test
    fun `换 openid 成功后只剩新账号的记录`() {
        StatsStore.publish("A", listOf(good, rejected, degenerate), null)
        StatsStore.publish("B", listOf(rejected), null)
        val after = StatsStore.snapshot!!
        assertEquals("B", after.openid)
        assertEquals(listOf(rejected), after.records)
        assertNull(after.error)
    }

    // -------------------------------------------------------------- revision

    /**
     * 每次内容变化都要推进 revision。
     *
     * 页面是靠「revision 变了没」决定要不要重绘的；漏推进就等于
     * 数据更新了但界面停在旧数字上（重复发布同一份也要推进，因为
     * 页面可能正好在两次发布之间重建过）。
     */
    @Test
    fun `每次 publish 和 invalidate 都推进 revision`() {
        var last = StatsStore.revision

        StatsStore.publish("oA", listOf(good), null)
        assertTrue("第一次 publish 没推进 revision", StatsStore.revision > last)
        last = StatsStore.revision

        StatsStore.publish("oA", null, "网络失败")
        assertTrue("失败发布没推进 revision", StatsStore.revision > last)
        last = StatsStore.revision

        StatsStore.publish("oA", listOf(good), null)
        assertTrue("重复发布同一份没推进 revision", StatsStore.revision > last)
        last = StatsStore.revision

        StatsStore.invalidate()
        assertTrue("invalidate 没推进 revision", StatsStore.revision > last)
    }

    /** invalidate 之后没有快照 —— 冷启动 / 换账号的起点必须是空的，不能留着上一个人的。 */
    @Test
    fun `invalidate 清空快照`() {
        StatsStore.publish("oA", listOf(good), null)
        assertNotNull(StatsStore.snapshot)
        StatsStore.invalidate()
        assertNull("invalidate 后还留着旧快照", StatsStore.snapshot)
    }
}
