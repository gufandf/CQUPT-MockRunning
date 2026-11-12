/*
 * 设置页的二级页面。
 *
 * 分层结构（v1.13.1 起）：
 *   设置首页 SettingsActivity —— 只有分组入口和「当前取值」摘要
 *   二级页   SettingsGroupActivity + settings_group_*.xml —— 真正的表单
 *
 * 为什么共用一个 Activity 而不是七个子 Activity：
 *   每组页面的骨架一模一样（固定顶栏 + 一个 ScrollView + 卡片），
 *   差异只在布局文件和绑定代码。七个 Activity 会复制七份「顶栏 + 返回 + 转场」，
 *   改一次顶栏要改七处；这里用 EXTRA_GROUP 选布局、按组绑定，骨架只有一份。
 */
package com.whale.campusrun

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class SettingsGroupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP = "group"

        const val G_ACCOUNT = "account"
        const val G_RUN = "run"
        const val G_STATS = "stats"
        const val G_TUTORIAL = "tutorial"
        const val G_LIVE = "live"
        const val G_APPEARANCE = "appearance"
        const val G_ABOUT = "about"

        /** 每组的：布局、标题。顺序即设置首页的顺序。 */
        private val LAYOUT = mapOf(
            G_ACCOUNT to R.layout.settings_group_account,
            G_RUN to R.layout.settings_group_run,
            G_STATS to R.layout.settings_group_stats,
            G_TUTORIAL to R.layout.settings_group_tutorial,
            G_LIVE to R.layout.settings_group_live,
            G_APPEARANCE to R.layout.settings_group_appearance,
            G_ABOUT to R.layout.settings_group_about
        )

        fun titleRes(group: String): Int = when (group) {
            G_ACCOUNT -> R.string.g_account
            G_RUN -> R.string.g_run
            G_STATS -> R.string.g_stats
            G_TUTORIAL -> R.string.tutorial_title
            G_LIVE -> R.string.g_live
            G_APPEARANCE -> R.string.g_appearance
            else -> R.string.g_about
        }

        fun start(ctx: Context, group: String) {
            ctx.startActivity(
                Intent(ctx, SettingsGroupActivity::class.java)
                    .putExtra(EXTRA_GROUP, group)
            )
        }
    }

    private lateinit var group: String

    private var rowOpenid: SettingsRow? = null
    private var rowConnect: SettingsRow? = null
    private var rowArea: SettingsRow? = null

    private lateinit var wheelPaceMin: WheelPicker
    private lateinit var wheelPaceSec: WheelPicker
    private lateinit var wheelDist: WheelPicker
    private lateinit var wheelTol: WheelPicker
    private lateinit var tvPaceWindow: TextView
    private lateinit var swBurrs: androidx.appcompat.widget.SwitchCompat
    private lateinit var rowBattery: SettingsRow

    private lateinit var rowRefreshStats: SettingsRow
    private lateinit var tvStatsBig: TextView
    private lateinit var tvStatsSub: TextView
    private lateinit var tvStatsByPlace: TextView
    private lateinit var timelineContainer: LinearLayout
    private lateinit var rowInvalid: SettingsRow
    private lateinit var invalidContainer: LinearLayout
    private lateinit var statsScroll: RefreshScrollView
    private lateinit var tipTop: TextView
    private lateinit var tipBottom: TextView

    private lateinit var scriptPanel: View
    private lateinit var scriptScroll: ScrollView
    private lateinit var tvScript: TextView
    private var scriptText: String = ""

    private lateinit var rowLiveState: SettingsRow

    private lateinit var segAuto: TextView
    private lateinit var segDark: TextView
    private lateinit var segLight: TextView

    private val api = Api()
    private val ui = Handler(Looper.getMainLooper())

    private var areas: List<Area> = emptyList()
    private var areaIndex = -1
    private var boundRevision = -1L
    private var panelBusy = false

    private val prefs by lazy { getSharedPreferences("campusrun", MODE_PRIVATE) }

    /** 二级页只需要一个能吞日志的 Listener，真正的日志在主页/日志页看。 */
    private val quiet = object : RunListener {
        override fun log(line: String) {}
        override fun onState(s: RunState) {}
    }

    private val engine by lazy { RunEngine(api, quiet) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        group = intent.getStringExtra(EXTRA_GROUP) ?: G_ACCOUNT
        val layout = LAYOUT[group] ?: R.layout.settings_group_account
        setContentView(layout)

        findViewById<TextView>(R.id.tvGroupTitle).setText(titleRes(group))
        findViewById<LiquidButton>(R.id.btnBack).setOnClickListener { finish() }

        when (group) {
            G_ACCOUNT -> bindAccount()
            G_RUN -> bindRun()
            G_STATS -> bindStats()
            G_TUTORIAL -> bindTutorial()
            G_LIVE -> bindLive()
            G_APPEARANCE -> bindAppearance()
            G_ABOUT -> bindAbout()
        }
    }

    override fun onResume() {
        super.onResume()
        when (group) {
            G_ACCOUNT -> syncAreas()
            G_RUN -> {
                updatePaceWindow()
                renderBattery()
            }
            G_LIVE -> updateLiveState()
            G_APPEARANCE -> updateThemeUi()
            else -> {}
        }
    }

    // ---------------------------------------------------------------- 账号与场地

    private fun bindAccount() {
        rowOpenid = findViewById(R.id.rowOpenid)
        rowConnect = findViewById(R.id.rowConnect)
        rowArea = findViewById(R.id.rowArea)

        renderOpenid()
        rowOpenid?.setOnClickListener { showOpenidDialog() }
        rowConnect?.setOnClickListener { doConnect() }

        val help = findViewById<View>(R.id.panelOpenidHelp)
        findViewById<SettingsRow>(R.id.rowHelp).setOnClickListener {
            help.visibility = if (help.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        rowArea?.setOnClickListener { openAreaMenu() }

        restoreAreas()
        bindList(AreaStore.snapshot())
        syncAreas()
    }

    /** 行右侧只显示掩码 —— 整串 openid 是身份凭据，不该在列表里明晃晃地摆着。 */
    private fun renderOpenid() {
        val oid = openid()
        rowOpenid?.setValue(
            when {
                oid.isEmpty() -> "未填写"
                oid.length <= 8 -> "已填写"
                else -> "${oid.take(4)}…${oid.takeLast(4)}"
            }
        )
    }

    /**
     * 修改 openid：参照 ColorOS「更改手机名称」的弹窗 ——
     * 标题 + 一行说明 + 行内输入（右侧 28 位计数、× 清除、粘贴）+ 下划线 + 取消 / 保存。
     *
     * 保存后直接连一次，省掉「保存 → 再去点连接」这一步：openid 本来就是给连接用的。
     */
    private fun showOpenidDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_openid, null)
        val input = view.findViewById<EditText>(R.id.etOpenidInput)
        val count = view.findViewById<TextView>(R.id.tvOpenidCount)
        val warn = view.findViewById<TextView>(R.id.tvOpenidWarn)
        val clear = view.findViewById<View>(R.id.btnOpenidClear)
        val paste = view.findViewById<View>(R.id.btnOpenidPaste)
        val cancel = view.findViewById<LiquidButton>(R.id.btnOpenidCancel)
        val save = view.findViewById<LiquidButton>(R.id.btnOpenidSave)

        input.setText(openid())
        input.setSelection(input.text.length)

        fun refresh() {
            val n = input.text.toString().trim().length
            count.text = "$n/28"
            warn.visibility = if (n in 1..27) View.VISIBLE else View.GONE
            warn.text = getString(R.string.openid_len_warn, n)
        }
        refresh()

        input.addTextChangedListener(simpleWatcher { refresh() })

        clear.setOnClickListener {
            input.setText("")
            refresh()
        }
        paste.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
            if (text.isNotEmpty()) {
                input.setText(text.take(28))
                input.setSelection(input.text.length)
                refresh()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        // 弹窗自己画圆角卡片，不要系统那层白底
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.90f).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val oid = input.text.toString().trim()
            if (oid.isEmpty()) {
                warn.visibility = View.VISIBLE
                warn.text = "先填 openid"
                return@setOnClickListener
            }
            prefs.edit().putString("openid", oid).apply()
            renderOpenid()
            dialog.dismiss()
            // 换了账号：旧场地缓存立刻作废，再自动连一次
            if (AreaStore.publishedOpenid.isNotEmpty() && AreaStore.publishedOpenid != oid) {
                AreaStore.invalidate()
                AreaCache.clear(this)
            }
            doConnect()
        }

        dialog.show()
    }

    private fun syncAreas() {
        val oid = openid()
        if (AreaStore.publishedOpenid.isNotEmpty() && AreaStore.publishedOpenid != oid) {
            AreaStore.invalidate()
            boundRevision = -1L
        }
        if (AreaStore.hasResultFor(oid)) bindIfChanged()
    }

    private fun bindIfChanged() {
        if (boundRevision == AreaStore.revision) return
        bindList(AreaStore.snapshot())
    }

    private fun bindList(list: List<Area>) {
        val keep = areas.getOrNull(areaIndex)?.placeCode
            ?: prefs.getString("placeCode", "").orEmpty()
        areas = list
        val idx = list.indexOfFirst { it.placeCode == keep }
        areaIndex = when {
            idx >= 0 -> idx
            list.isNotEmpty() -> {
                // 上次选的场地这次没下发（改名 / 停用）：退回第一个并记住，
                // 否则主页 mapView 每次都要重新猜一遍
                prefs.edit().putString("placeCode", list.first().placeCode).apply()
                0
            }
            else -> -1
        }
        renderArea()
        boundRevision = AreaStore.revision
    }

    private fun renderArea() {
        rowArea?.setValue(
            areas.getOrNull(areaIndex)?.toString() ?: getString(R.string.need_area_short)
        )
    }

    /** 场地选择：液体弹出菜单，替代系统 Spinner 的下拉列表。 */
    private fun openAreaMenu() {
        if (areas.isEmpty()) {
            toast(getString(R.string.need_area_short))
            return
        }
        val items = areas.mapIndexed { i, a ->
            FluidMenuItem(label = a.toString(), checked = i == areaIndex)
        }
        FluidPopupMenu.build(this, rowArea ?: return, items) { index ->
            areaIndex = index
            prefs.edit().putString("placeCode", areas[index].placeCode).apply()
            renderArea()
        }.show()
    }

    /**
     * 冷启动先还原上次落盘的场地。
     *
     * 只认同一个 openid 的缓存：换了账号，磁盘里那份是别人的场地，不能用。
     */
    private fun restoreAreas() {
        val oid = openid()
        if (oid.isEmpty() || AreaStore.hasResultFor(oid)) return
        val cached = AreaCache.load(this) ?: return
        if (cached.first == oid) AreaStore.publish(cached.first, cached.second)
    }

    private fun doConnect() {
        if (panelBusy) return
        val oid = openid()
        if (oid.isEmpty()) {
            toast("先填 openid")
            return
        }
        if (AreaStore.publishedOpenid.isNotEmpty() && AreaStore.publishedOpenid != oid) {
            AreaStore.invalidate()
            // 旧 openid 的落盘缓存也一起丢，免得留在本机
            AreaCache.clear(this)
        }
        prefs.edit().putString("openid", oid).apply()
        panelBusy = true
        rowConnect?.isEnabled = false
        rowConnect?.setValue(getString(R.string.conn_connecting))

        Thread {
            var netFail = false
            var badOpenid = false
            var got = 0
            try {
                if (engine.connect(oid)) {
                    engine.fetchRule()
                    val list = engine.fetchAreas()
                    netFail = engine.netFail
                    got = list.size
                    if (netFail) AreaStore.markNetFailed(oid) else {
                        AreaStore.publish(oid, list)
                        // 落盘：冷启动不用打网络也能画边界、还原选中的场地
                        AreaCache.save(applicationContext, oid, list)
                    }
                } else {
                    netFail = engine.netFail
                    badOpenid = !netFail
                    if (netFail) AreaStore.markNetFailed(oid) else AreaStore.markBadOpenid(oid)
                }
            } catch (t: Throwable) {
                AreaStore.markNetFailed(oid)
                netFail = true
            } finally {
                ui.post {
                    panelBusy = false
                    rowConnect?.isEnabled = true
                    rowConnect?.setValue(null)
                    bindIfChanged()
                    when {
                        netFail -> showConnectFailed()
                        badOpenid -> toast(getString(R.string.conn_bad_openid))
                        got == 0 -> toast(getString(R.string.conn_no_area))
                        else -> {
                            // 第一次连上时把第一个场地设为默认，主页立刻有边界可画
                            if (prefs.getString("placeCode", "").isNullOrEmpty()) {
                                areas.firstOrNull()?.let {
                                    prefs.edit().putString("placeCode", it.placeCode).apply()
                                }
                            }
                            toast(getString(R.string.conn_ok))
                        }
                    }
                }
            }
        }.start()
    }

    private fun showConnectFailed() {
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.conn_failed_title)
                .setMessage(R.string.conn_failed_msg)
                .setPositiveButton(R.string.ok, null)
                .show()
        } catch (_: Throwable) {
            toast(getString(R.string.conn_failed_toast))
        }
    }

    // ---------------------------------------------------------------- 跑步参数

    private fun bindRun() {
        wheelPaceMin = findViewById(R.id.wheelPaceMin)
        wheelPaceSec = findViewById(R.id.wheelPaceSec)
        wheelDist = findViewById(R.id.wheelDist)
        wheelTol = findViewById(R.id.wheelTol)
        tvPaceWindow = findViewById(R.id.tvPaceWindow)

        // 值域与步进：分 3~9、秒 00~59（1 秒一跳）、距离 0.5~10.0（0.1）、抖动 0~60（5 秒）
        wheelPaceMin.items = (3..9).map { it.toString() }
        wheelPaceMin.unit = "分"
        wheelPaceSec.items = (0..59).map { "%02d".format(it) }
        wheelPaceSec.unit = "秒"

        wheelDist.items = (5..100).map { "%.1f".format(it / 10.0) }
        wheelDist.unit = "km"

        wheelTol.items = (0..12).map { (it * 5).toString() }
        wheelTol.unit = "秒"

        // 现有值 → 鼓轮位置（超范围就夹到边界）
        val (m0, s0) = parsePaceParts(prefs.getString("pace", "5:45").orEmpty())
        wheelPaceMin.setSelectedIndex((m0 - 3).coerceIn(0, wheelPaceMin.items.size - 1))
        wheelPaceSec.setSelectedIndex(s0.coerceIn(0, 59))

        val dist0 = prefs.getString("dist", "2.0").orEmpty().toDoubleOrNull() ?: 2.0
        wheelDist.setSelectedIndex(((dist0 * 10).toInt() - 5).coerceIn(0, wheelDist.items.size - 1))

        val tol0 = prefs.getString("paceTol", "20").orEmpty().toIntOrNull() ?: 20
        wheelTol.setSelectedIndex((tol0 / 5).coerceIn(0, wheelTol.items.size - 1))

        wheelPaceMin.onSelectionChanged = { persistPace() }
        wheelPaceSec.onSelectionChanged = { persistPace() }
        wheelDist.onSelectionChanged = { i ->
            prefs.edit().putString("dist", wheelDist.items[i]).apply()
            markChips(findViewById(R.id.chipsDist), wheelDist.items[i])
        }
        wheelTol.onSelectionChanged = { i ->
            prefs.edit().putString("paceTol", wheelTol.items[i]).apply()
            markChips(findViewById(R.id.chipsTol), wheelTol.items[i])
            updatePaceWindow()
        }

        // 快捷值：和闹钟页那排 chip 一样，点了滚轮会滑过去
        bindChips(findViewById(R.id.chipsPace), listOf("5:00", "5:30", "5:45", "6:00")) { v ->
            val (m, s) = parsePaceParts(v)
            wheelPaceMin.animateTo((m - 3).coerceIn(0, wheelPaceMin.items.size - 1))
            wheelPaceSec.animateTo(s.coerceIn(0, 59))
        }
        bindChips(findViewById(R.id.chipsDist), listOf("1.0", "2.0", "3.0", "5.0")) { v ->
            wheelDist.animateTo(wheelDist.items.indexOf(v).coerceAtLeast(0))
        }
        bindChips(findViewById(R.id.chipsTol), listOf("0", "10", "20", "30")) { v ->
            wheelTol.animateTo(wheelTol.items.indexOf(v).coerceAtLeast(0))
        }

        markChips(findViewById(R.id.chipsPace), currentPaceString())
        markChips(findViewById(R.id.chipsDist), wheelDist.items[wheelDist.selectedIndex])
        markChips(findViewById(R.id.chipsTol), wheelTol.items[wheelTol.selectedIndex])

        // 轨迹毛刺：默认关闭 = 光滑轨迹；打开 = 多圈错位 + 毛刺 / 突起。
        // 先读 prefs 再挂监听，免得初始化时把默认值当成一次用户改动写回去。
        swBurrs = findViewById(R.id.swBurrs)
        swBurrs.isChecked = prefs.getBoolean("burrs", false)
        swBurrs.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("burrs", on).apply()
        }

        rowBattery = findViewById(R.id.rowBattery)
        rowBattery.setOnClickListener {
            if (isIgnoringBatteryOptimizations()) {
                toast("已经在电池白名单里了")
            } else {
                showBatteryDialog()
            }
        }
        renderBattery()

        updatePaceWindow()
    }

    private fun parsePaceParts(text: String): Pair<Int, Int> {
        val parts = text.trim().split(":")
        val m = parts.getOrNull(0)?.toIntOrNull() ?: 5
        val s = parts.getOrNull(1)?.toIntOrNull() ?: 45
        return m to s
    }

    private fun currentPaceString(): String {
        val m = wheelPaceMin.items[wheelPaceMin.selectedIndex]
        val s = wheelPaceSec.items[wheelPaceSec.selectedIndex]
        return "$m:$s"
    }

    private fun persistPace() {
        val pace = currentPaceString()
        prefs.edit().putString("pace", pace).apply()
        markChips(findViewById(R.id.chipsPace), pace)
        updatePaceWindow()
    }

    /** 一排快捷 chip：选中态用主色实心，其余浅底。 */
    private fun bindChips(
        container: LinearLayout,
        values: List<String>,
        onPick: (String) -> Unit
    ) {
        container.removeAllViews()
        values.forEach { v ->
            val chip = TextView(this)
            chip.text = v
            chip.textSize = 13f
            chip.gravity = android.view.Gravity.CENTER
            chip.setPadding(dp(14), dp(6), dp(14), dp(6))
            chip.setTextColor(ContextCompat.getColor(this, R.color.fg_dim))
            chip.setBackgroundResource(R.drawable.chip_bg)
            chip.isClickable = true
            chip.isFocusable = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            chip.layoutParams = lp
            chip.tag = v
            chip.setOnClickListener { onPick(v) }
            container.addView(chip)
        }
    }

    /** 把与当前值相同的那颗 chip 点亮。 */
    private fun markChips(container: LinearLayout, value: String) {
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? TextView ?: continue
            val on = chip.tag?.toString() == value
            chip.setBackgroundResource(if (on) R.drawable.chip_bg_on else R.drawable.chip_bg)
            chip.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.on_accent else R.color.fg_dim)
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 电池白名单

    /**
     * 息屏后会不会被冻结。
     *
     * 原来这块在主页（一条红色警示 + 点击设置），但主页现在只放「看进度 / 点开始」，
     * 权限类设置归到跑步参数这一页 —— 它影响的是「跑得稳不稳」，和配速同类。
     */
    private fun isIgnoringBatteryOptimizations(): Boolean = try {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(packageName)
    } catch (t: Throwable) {
        true
    }

    private fun renderBattery() {
        if (!::rowBattery.isInitialized) return
        val ok = isIgnoringBatteryOptimizations()
        rowBattery.setValue(if (ok) "已加入" else "未加入")
        rowBattery.setValueColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.danger))
    }

    private fun showBatteryDialog() {
        AlertDialog.Builder(this)
            .setTitle("让它在后台跑稳")
            .setMessage("深度省电会冻结后台，进度会变慢。允许应用始终在后台运行即可。")
            .setPositiveButton("去设置") { _, _ -> openBatterySettings() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openBatterySettings() {
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast("打开设置失败")
            }
        }
    }

    private fun updatePaceWindow() {
        if (!::wheelPaceMin.isInitialized) return
        val pace = RunEngine.parsePace(currentPaceString())
        val tol = wheelTol.items[wheelTol.selectedIndex].toDoubleOrNull()
        if (pace == null || tol == null || pace < 60 || tol < 0) {
            tvPaceWindow.text = getString(R.string.pace_window_bad)
            return
        }
        val w = TrackGen.paceWindow(pace, tol)
        tvPaceWindow.text = getString(
            R.string.pace_window,
            RunEngine.fmtPace(w.start), RunEngine.fmtPace(w.endInclusive)
        )
    }

    // ---------------------------------------------------------------- 成绩统计

    private fun bindStats() {
        rowRefreshStats = findViewById(R.id.rowRefreshStats)
        tvStatsBig = findViewById(R.id.tvStatsBig)
        tvStatsSub = findViewById(R.id.tvStatsSub)
        tvStatsByPlace = findViewById(R.id.tvStatsByPlace)
        timelineContainer = findViewById(R.id.timelineContainer)
        rowInvalid = findViewById(R.id.rowInvalid)
        invalidContainer = findViewById(R.id.invalidContainer)

        statsScroll = findViewById(R.id.statsScroll)
        tipTop = findViewById(R.id.tipTop)
        tipBottom = findViewById(R.id.tipBottom)

        rowRefreshStats.setOnClickListener { loadStats() }

        statsScroll.onPull = { progress, fromTop ->
            val tip = if (fromTop) tipTop else tipBottom
            tip.visibility = View.VISIBLE
            tip.alpha = (0.4f + progress.coerceIn(0f, 1f) * 0.6f).coerceIn(0f, 1f)
            tip.text = getString(
                if (progress >= 1f) R.string.release_to_refresh else
                    if (fromTop) R.string.pull_to_refresh else R.string.pull_to_refresh_up
            )
        }
        statsScroll.onRelease = { triggered, fromTop ->
            val tip = if (fromTop) tipTop else tipBottom
            if (triggered) {
                tip.visibility = View.VISIBLE
                tip.alpha = 1f
                tip.text = getString(R.string.refreshing)
            } else {
                tip.visibility = View.GONE
            }
        }
        statsScroll.onRefresh = { loadStats() }

        // 点开这一页就自动拉一次：成绩是「打开就想看最新的」的东西，
        // 还要再点一下「刷新统计」才是多余的一步。
        loadStats()
        rowInvalid.setOnClickListener {
            val show = invalidContainer.visibility != View.VISIBLE
            invalidContainer.visibility = if (show) View.VISIBLE else View.GONE
            // 右侧「展开 / 收起」跟着状态走，别让人以为没生效
            rowInvalid.setValue(if (show) "收起" else "展开")
        }
    }

    private fun loadStats() {
        // 正在拉的时候又拉了一次：把刷新指示器收掉，别让它一直转
        if (panelBusy) {
            finishRefresh()
            return
        }
        val oid = openid()
        if (oid.isEmpty()) {
            showStatsEmpty(getString(R.string.stats_need_openid))
            finishRefresh()
            return
        }
        panelBusy = true
        rowRefreshStats.isEnabled = false
        statsScroll.setRefreshing(true)
        tvStatsBig.text = getString(R.string.stats_loading)
        tvStatsSub.text = ""

        Thread {
            try {
                if (!engine.connect(oid)) {
                    ui.post {
                        panelBusy = false
                        rowRefreshStats.isEnabled = true
                        finishRefresh()
                        if (engine.netFail) {
                            showStatsEmpty(getString(R.string.conn_failed_title))
                            showConnectFailed()
                        } else {
                            showStatsEmpty(getString(R.string.stats_bad_openid))
                        }
                    }
                    return@Thread
                }
                val s = engine.fetchStats()
                ui.post {
                    panelBusy = false
                    rowRefreshStats.isEnabled = true
                    finishRefresh()
                    if (s == null) showStatsEmpty(getString(R.string.stats_failed))
                    else renderStats(s)
                }
            } catch (t: Throwable) {
                ui.post {
                    panelBusy = false
                    rowRefreshStats.isEnabled = true
                    finishRefresh()
                    showStatsEmpty("拉取异常：${t.javaClass.simpleName}")
                }
            }
        }.start()
    }

    /** 拉取失败 / 没账号时的统一空态：上面一句原因，下面两块区域清空。 */
    private fun showStatsEmpty(reason: String) {
        tvStatsBig.text = reason
        tvStatsSub.text = ""
        tvStatsByPlace.visibility = View.GONE
        renderTimeline(emptyList())
        renderInvalid(emptyList())
    }

    /** 收起「刷新中…」气泡，把滚动容器恢复成可拉状态。 */
    private fun finishRefresh() {
        if (!::statsScroll.isInitialized) return
        statsScroll.setRefreshing(false)
        statsScroll.postDelayed({
            tipTop.visibility = View.GONE
            tipBottom.visibility = View.GONE
        }, 220)
    }

    /**
     * 渲染成绩：时间轴 + 折叠的无效记录。
     *
     * 只用有效成绩（`valid == true`）：无效记录在服务端就是废的，
     * 混在列表里只会让人以为「跑成了」；它们收在最底部，需要时才展开。
     */
    private fun renderStats(s: Stats) {
        val valid = s.rows.filter { it.valid }
        val invalid = s.rows.filterNot { it.valid }

        tvStatsBig.text = getString(R.string.stats_valid_big, s.validCount)
        tvStatsSub.text = if (s.validCount == 0) {
            getString(R.string.stats_empty_hint)
        } else {
            getString(
                R.string.stats_valid_sub,
                s.totalKm,
                if (s.avgPaceSecKm > 0) RunEngine.fmtPace(s.avgPaceSecKm) else "--",
                if (s.fastestPaceSecKm > 0) RunEngine.fmtPace(s.fastestPaceSecKm) else "--"
            )
        }

        // 分场地汇总：一行小字，保留「哪个场地跑得多」这个信息
        tvStatsByPlace.visibility = if (s.byPlace.isEmpty()) View.GONE else View.VISIBLE
        tvStatsByPlace.text = s.byPlace.joinToString("　·　") { "${it.first} ${it.second} 次" }

        renderTimeline(valid)
        renderInvalid(invalid)
    }

    private fun renderTimeline(rows: List<StatRow>) {
        timelineContainer.removeAllViews()
        if (rows.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.stats_empty)
                setTextColor(ContextCompat.getColor(context, R.color.fg_dim))
                textSize = 14f
                setPadding(0, dp(20), 0, dp(20))
            }
            timelineContainer.addView(empty)
            return
        }

        rows.forEachIndexed { index, row ->
            val item = layoutInflater.inflate(R.layout.item_stats_timeline, timelineContainer, false)

            item.findViewById<TextView>(R.id.tvTlTitle).text = buildString {
                // 日期拿不到就退回「第 N 次」，绝不显示假日期
                append(if (row.dateLabel.isNotEmpty()) row.dateLabel else "第 ${rows.size - index} 次")
                append("　").append(row.placeName)
            }
            item.findViewById<TextView>(R.id.tvTlSub).text =
                "%.2f km　·　用时 %s".format(row.distanceKm, row.timeText)
            item.findViewById<TextView>(R.id.tvTlPace).text =
                if (row.paceSecKm > 0) RunEngine.fmtPace(row.paceSecKm) else "--"

            // 最新的那条打「最新」，并且圆点用主色（其余淡一档）
            item.findViewById<View>(R.id.tvTlBadge).visibility =
                if (index == 0) View.VISIBLE else View.GONE

            val dot = item.findViewById<View>(R.id.tlDot)
            dot.alpha = if (index == 0) 1f else 0.45f

            // 竖线要首尾相接：第一行从圆点往下画，中间行贯通整行，最后一行画到圆点为止。
            // 只设可见性会有 22dp 的断口 —— 每行的线都从自己顶上 22dp 才开始。
            val line = item.findViewById<View>(R.id.tlLineTop)
            val lp = line.layoutParams as android.widget.FrameLayout.LayoutParams
            when {
                rows.size == 1 -> line.visibility = View.INVISIBLE
                index == 0 -> {
                    lp.topMargin = dp(22)
                    lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                }
                index == rows.size - 1 -> {
                    lp.topMargin = 0
                    lp.height = dp(22)
                }
                else -> {
                    lp.topMargin = 0
                    lp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                }
            }
            line.layoutParams = lp

            timelineContainer.addView(item)
        }
    }

    private fun renderInvalid(rows: List<StatRow>) {
        rowInvalid.setTitle(getString(R.string.stats_invalid_title) + "（${rows.size}）")
        rowInvalid.setValue(
            when {
                rows.isEmpty() -> "无"
                invalidContainer.visibility == View.VISIBLE -> "收起"
                else -> "展开"
            }
        )
        invalidContainer.removeAllViews()

        if (rows.isEmpty()) {
            rowInvalid.isEnabled = false
            invalidContainer.visibility = View.GONE
            return
        }
        rowInvalid.isEnabled = true

        rows.forEach { row ->
            val tv = TextView(this).apply {
                text = buildString {
                    append(if (row.dateLabel.isNotEmpty()) row.dateLabel + "　" else "")
                    append(row.placeName)
                    append("　").append("%.2f km".format(row.distanceKm))
                    if (row.reason.isNotEmpty()) append("　").append(row.reason)
                }
                setTextColor(ContextCompat.getColor(context, R.color.fg_dim))
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
            }
            invalidContainer.addView(tv)
        }
    }

    // ---------------------------------------------------------------- 使用教程

    private fun bindTutorial() {
        scriptPanel = findViewById(R.id.scriptPanel)
        scriptScroll = findViewById(R.id.scriptScroll)
        tvScript = findViewById(R.id.tvScript)
        findViewById<SettingsRow>(R.id.rowCopyScript).setOnClickListener { copyScript() }
        findViewById<SettingsRow>(R.id.rowShareScript).setOnClickListener { shareScript() }
        findViewById<SettingsRow>(R.id.rowToggleScript).setOnClickListener {
            scriptPanel.visibility =
                if (scriptPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        loadScript()
    }

    private fun loadScript() {
        try {
            scriptText = assets.open("get_openid.py")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            scriptText = "# 脚本读取失败：" + t.javaClass.simpleName
        }
        tvScript.text = scriptText
        findViewById<TextView>(R.id.tvScriptPath).text =
            "共 ${scriptText.lines().size} 行，存成 get_openid.py 双击运行"
    }

    private fun copyScript() {
        if (scriptText.isEmpty()) loadScript()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("get_openid.py", scriptText))
        toast(getString(R.string.copied))
    }

    private fun shareScript() {
        if (scriptText.isEmpty()) loadScript()
        try {
            val dir = File(cacheDir, "share")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "get_openid.py")
            f.writeText(scriptText, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "get_openid.py")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "分享 get_openid.py"))
            return
        } catch (_: Throwable) {
            // 文件方式失败，退化成纯文本
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "get_openid.py")
            putExtra(Intent.EXTRA_TEXT, scriptText)
        }
        startActivity(Intent.createChooser(i, "分享脚本"))
    }

    // ---------------------------------------------------------------- 流体云

    private fun bindLive() {
        rowLiveState = findViewById(R.id.rowLiveState)
        findViewById<SettingsRow>(R.id.rowLivePreview).setOnClickListener { sendLivePreview() }
        updateLiveState()
    }

    private fun updateLiveState() {
        if (!::rowLiveState.isInitialized) return
        val ok = Build.VERSION.SDK_INT >= 36 &&
            notificationsAllowed() &&
            LiveCapsule.supported(this)
        // 一行状态就够；具体原因写在设置页的说明卡里，不再占一行
        rowLiveState.setValue(if (ok) "可用" else "不可用")
        rowLiveState.setValueColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.fg_dim))
    }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    /** 发一条 8 秒后自消的假进度，让用户当场看见胶囊长什么样。 */
    private fun sendLivePreview() {
        if (!notificationsAllowed()) {
            toast(getString(R.string.live_preview_fail))
            return
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val n = LiveCapsule.preview(this)
            nm.notify(LiveCapsule.PREVIEW_ID, n)
            // 顺手把「这条通知到底合不合格」写进运行日志 —— 胶囊没出现时，
            // 用户可以直接去日志页看是哪一环没满足，不用靠猜。
            RunController.logFromUi("流体云：${LiveCapsule.diagnose(this, n)}")
            toast(getString(R.string.live_preview_sent))
        } catch (t: Throwable) {
            toast(getString(R.string.live_preview_fail))
        }
    }

    // ---------------------------------------------------------------- 外观

    private fun bindAppearance() {
        segAuto = findViewById(R.id.segThemeAuto)
        segDark = findViewById(R.id.segThemeDark)
        segLight = findViewById(R.id.segThemeLight)
        segAuto.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        segDark.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_YES) }
        segLight.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_NO) }
        updateThemeUi()
    }

    private fun applyThemeMode(mode: Int) {
        prefs.edit().putInt("themeMode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
        updateThemeUi()
    }

    private fun currentTheme(): Int =
        prefs.getInt("themeMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun updateThemeUi() {
        if (!::segAuto.isInitialized) return
        val cur = currentTheme()
        seg(segAuto, cur == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        seg(segDark, cur == AppCompatDelegate.MODE_NIGHT_YES)
        seg(segLight, cur == AppCompatDelegate.MODE_NIGHT_NO)
    }

    private fun seg(t: TextView, active: Boolean) {
        if (active) {
            t.setBackgroundResource(R.drawable.seg_selected)
            t.setTextColor(ContextCompat.getColor(this, R.color.on_accent))
            t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        } else {
            t.setBackgroundResource(R.drawable.seg_normal)
            t.setTextColor(ContextCompat.getColor(this, R.color.fg_dim))
            t.typeface = android.graphics.Typeface.DEFAULT
        }
    }

    // ---------------------------------------------------------------- 关于

    private fun bindAbout() {
        findViewById<SettingsRow>(R.id.rowVersion).setValue(BuildConfig.VERSION_NAME)
        findViewById<SettingsRow>(R.id.rowRepoOpen).setOnClickListener { openRepo() }
        findViewById<SettingsRow>(R.id.rowRepoCopy).setOnClickListener { copyRepo() }
        // 原仓库只作出处说明：不可点、没有箭头，也不进剪贴板
        findViewById<SettingsRow>(R.id.rowRepoOrigin).setValue(null)
    }

    private fun openRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.REPO_URL)))
        } catch (t: Throwable) {
            copyRepo()
            toast(getString(R.string.repo_open_failed))
        }
    }

    private fun copyRepo() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("repo", MainActivity.REPO_URL))
        toast(getString(R.string.repo_copied))
    }

    // ---------------------------------------------------------------- 小工具

    private fun simpleWatcher(block: () -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = block()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun openid(): String = prefs.getString("openid", "").orEmpty().trim()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
