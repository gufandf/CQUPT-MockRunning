/*
 * 成绩的「启动时拉一次」调度。
 *
 * 以前成绩只在「打开成绩页」时拉：进页面先渲染「加载中」，两三秒后数字才跳出来，
 * 而且每进一次都要重新换 token + 请求一遍。现在把它挪到应用启动：
 *
 *   冷启动 → 立刻读磁盘缓存（有就先把页面填上）→ 后台静默拉一次 → 落盘 → 通知页面
 *
 * 为什么做成 object + 回调表，而不是让 App.onCreate 直接开线程：
 *   Activity 可能在拉取途中就被销毁/重建，结果必须落在 [StatsStore] 而不是某个
 *   Activity 的字段上。这里只负责「谁在等」，等的人自己按 revision 判断要不要重绘。
 *
 * 为什么不在失败时弹窗：启动阶段的网络失败不是用户主动触发的，
 * 弹出来只会让人以为 App 坏了。失败原因记进快照，成绩页顶部小字提示一句就够。
 */
package com.whale.campusrun

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object StatsLoader {

    /** 拉完（成功或失败）通知一次，主线程回调。 */
    fun interface OnLoaded {
        fun onLoaded(snapshot: StatsSnapshot)
    }

    private val listeners = CopyOnWriteArrayList<OnLoaded>()
    private val ui = Handler(Looper.getMainLooper())

    /** 同一次启动里最多自动拉一次：Activity 重建不该再打一遍网络。 */
    @Volatile
    private var autoRanFor: String = ""

    fun addListener(l: OnLoaded) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: OnLoaded) {
        listeners.remove(l)
    }

    /**
     * 冷启动入口：先 restore 缓存，再决定要不要拉。
     *
     * @param force true = 无视「本次启动已拉过」，手动刷新用。
     */
    fun warmUp(ctx: Context, openid: String, force: Boolean = false) {
        if (openid.isEmpty()) return
        val app = ctx.applicationContext

        // 换账号：旧缓存是别人的成绩，不能拿来先顶上
        StatsStore.snapshot?.let { if (it.openid != openid) StatsStore.invalidate() }
        StatsStore.restore(app, openid)

        if (!force && autoRanFor == openid) return
        if (!StatsStore.beginLoad()) {
            // 已经有一次在飞（启动预取还没回来时用户就下拉了）：不重复发请求，
            // 但要把当前快照回一次，否则页面的「刷新中…」只能等兜底定时器才消失。
            StatsStore.snapshot?.let { snap ->
                ui.post { listeners.forEach { runCatching { it.onLoaded(snap) } } }
            }
            return
        }
        autoRanFor = openid

        Thread({
            val engine = RunEngine(Api(), quiet)
            var records: List<StatsRecord>? = null
            var error: String? = null
            // 失败原因会被成绩页顶上那行小字原样显示给用户，所以取资源；
            // app 是 applicationContext，在后台上取字符串没有 UI 线程依赖
            try {
                if (!engine.connect(openid)) {
                    error = when {
                        engine.netFail -> app.getString(R.string.stats_err_net)
                        else -> app.getString(R.string.stats_err_openid)
                    }
                } else {
                    val r = engine.fetchRecords()
                    if (r == null) {
                        error = if (engine.netFail) app.getString(R.string.stats_err_net)
                        else app.getString(R.string.stats_err_fetch)
                    } else {
                        records = r
                    }
                }
            } catch (t: Throwable) {
                error = app.getString(R.string.stats_err_exception, t.javaClass.simpleName)
            } finally {
                StatsStore.endLoad()
            }

            StatsStore.publish(openid, records, error)
            val snap = StatsStore.snapshot ?: return@Thread
            if (records != null) StatsStore.save(app, snap)

            ui.post {
                for (l in listeners) {
                    try {
                        l.onLoaded(snap)
                    } catch (_: Throwable) {
                    }
                }
            }
        }, "campusrun-stats").start()
    }

    /** 手动刷新（上拉 / 下拉）。成功后把 autoRanFor 复位，让下次启动还能自动拉。 */
    fun refresh(ctx: Context, openid: String) {
        autoRanFor = ""
        warmUp(ctx, openid, force = true)
    }

    /** 成绩页只需要「拉」，不需要往日志页写一堆东西。 */
    private val quiet = object : RunListener {
        override fun log(line: String) {}
        override fun onState(s: RunState) {}
    }
}
