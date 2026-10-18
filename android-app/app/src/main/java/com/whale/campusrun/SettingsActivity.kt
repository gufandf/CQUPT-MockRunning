/*
 * 鲸鲸约跑 · 设置页
 *
 * 主页只留地图和【开始打卡】，所有「要配置的」「要看的」都收在这里，按用途分段：
 *   账号   openid / 连接 / 场地
 *   跑步   配速 / 距离 / 配速抖动
 *   统计   成绩汇总、分场地、最近记录
 *   教程   使用步骤 + 取码脚本
 *   外观   主题
 *   关于   署名 / 原理 / 开源仓库
 *
 * 走 Android 原生 Activity 转场，所以侧滑返回、预测式返回的跟手动画都由系统提供
 * （清单里 enableOnBackInvokedCallback="true"），不自己写手势。
 */
package com.whale.campusrun

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var etOpenid: EditText
    private lateinit var btnConnect: Button
    private lateinit var spArea: Spinner
    private lateinit var etPace: EditText
    private lateinit var etDist: EditText
    private lateinit var etTol: EditText
    private lateinit var tvPaceWindow: TextView

    private lateinit var btnRefreshStats: Button
    private lateinit var tvStatsSummary: TextView
    private lateinit var tvStatsByPlace: TextView
    private lateinit var tvStatsList: TextView

    private lateinit var scriptScroll: ScrollView
    private lateinit var tvScript: TextView
    private var scriptText: String = ""

    private lateinit var segAuto: TextView
    private lateinit var segDark: TextView
    private lateinit var segLight: TextView

    private val api = Api()
    private val ui = Handler(Looper.getMainLooper())

    private var areas: List<Area> = emptyList()
    private var boundRevision = -1L
    private var panelBusy = false

    private val prefs by lazy { getSharedPreferences("campusrun", MODE_PRIVATE) }

    /** 设置页只需要一个能吞日志的 Listener，真正的日志在主页/日志页看。 */
    private val quiet = object : RunListener {
        override fun log(line: String) {}
        override fun onState(s: RunState) {}
    }

    private val engine by lazy { RunEngine(api, quiet) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // ── 账号 ──
        etOpenid = findViewById(R.id.etOpenid)
        btnConnect = findViewById(R.id.btnConnect)
        spArea = findViewById(R.id.spArea)

        etOpenid.setText(prefs.getString("openid", ""))
        etOpenid.addTextChangedListener(simpleWatcher {
            val v = etOpenid.text.toString().trim()
            if (v != prefs.getString("openid", "")) {
                prefs.edit().putString("openid", v).apply()
            }
        })
        btnConnect.setOnClickListener { doConnect() }

        val help = findViewById<View>(R.id.panelOpenidHelp)
        findViewById<Button>(R.id.btnToggleOpenidHelp).setOnClickListener {
            help.visibility = if (help.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        spArea.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                areas.getOrNull(pos)?.let {
                    prefs.edit().putString("placeCode", it.placeCode).apply()
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // ── 跑步参数 ──
        etPace = findViewById(R.id.etPace)
        etDist = findViewById(R.id.etDist)
        etTol = findViewById(R.id.etTol)
        tvPaceWindow = findViewById(R.id.tvPaceWindow)

        etPace.setText(prefs.getString("pace", "5:45"))
        etDist.setText(prefs.getString("dist", "2.0"))
        etTol.setText(prefs.getString("paceTol", "20"))

        etPace.addTextChangedListener(simpleWatcher {
            prefs.edit().putString("pace", etPace.text.toString().trim()).apply()
            updatePaceWindow()
        })
        etDist.addTextChangedListener(simpleWatcher {
            prefs.edit().putString("dist", etDist.text.toString().trim()).apply()
        })
        etTol.addTextChangedListener(simpleWatcher {
            prefs.edit().putString("paceTol", etTol.text.toString().trim()).apply()
            updatePaceWindow()
        })

        // ── 统计 ──
        btnRefreshStats = findViewById(R.id.btnRefreshStats)
        tvStatsSummary = findViewById(R.id.tvStatsSummary)
        tvStatsByPlace = findViewById(R.id.tvStatsByPlace)
        tvStatsList = findViewById(R.id.tvStatsList)
        btnRefreshStats.setOnClickListener { loadStats() }

        // ── 教程 / 脚本 ──
        scriptScroll = findViewById(R.id.scriptScroll)
        tvScript = findViewById(R.id.tvScript)
        findViewById<Button>(R.id.btnCopyScript).setOnClickListener { copyScript() }
        findViewById<Button>(R.id.btnShareScript).setOnClickListener { shareScript() }
        findViewById<Button>(R.id.btnToggleScript).setOnClickListener {
            scriptScroll.visibility =
                if (scriptScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // ── 外观 ──
        segAuto = findViewById(R.id.segThemeAuto)
        segDark = findViewById(R.id.segThemeDark)
        segLight = findViewById(R.id.segThemeLight)
        segAuto.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        segDark.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_YES) }
        segLight.setOnClickListener { applyThemeMode(AppCompatDelegate.MODE_NIGHT_NO) }

        // ── 关于 ──
        findViewById<TextView>(R.id.tvRepo).setOnClickListener { openRepo() }
        findViewById<Button>(R.id.btnCopyRepo).setOnClickListener { copyRepo() }
        findViewById<TextView>(R.id.tvCredit).text =
            "鲸鲸约跑 v${BuildConfig.VERSION_NAME}\n由 WilliTourt的蓝色大肥鱼 🐟 编写"

        loadScript()
        updateThemeUi()
        updatePaceWindow()
        restoreAreas()
        bindList(AreaStore.snapshot())
        syncAreas()
    }

    override fun onResume() {
        super.onResume()
        updatePaceWindow()
        syncAreas()
    }

    // ---------------------------------------------------------------- 小工具

    private fun simpleWatcher(block: () -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = block()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun openid(): String = prefs.getString("openid", "").orEmpty().trim()

    // ---------------------------------------------------------------- 场地

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
        val keep = areas.getOrNull(spArea.selectedItemPosition)?.placeCode
            ?: prefs.getString("placeCode", "").orEmpty()
        areas = list
        val names = if (list.isEmpty()) listOf(getString(R.string.need_area_short))
        else list.map { it.toString() }
        spArea.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val idx = list.indexOfFirst { it.placeCode == keep }
        if (idx >= 0) {
            spArea.setSelection(idx)
        } else if (list.isNotEmpty()) {
            // 上次选的场地这次没下发（改名 / 停用）：退回第一个并记住，
            // 否则主页 mapView 每次都要重新猜一遍
            prefs.edit().putString("placeCode", list.first().placeCode).apply()
        }
        boundRevision = AreaStore.revision
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
        val oid = etOpenid.text.toString().trim()
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
        btnConnect.isEnabled = false
        val old = btnConnect.text
        btnConnect.text = getString(R.string.conn_connecting)

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
                    btnConnect.isEnabled = true
                    btnConnect.text = old
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

    // ---------------------------------------------------------------- 配速窗口

    private fun updatePaceWindow() {
        val pace = RunEngine.parsePace(etPace.text.toString())
        val tol = etTol.text.toString().trim().toDoubleOrNull()
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

    // ---------------------------------------------------------------- 统计

    private fun loadStats() {
        if (panelBusy) return
        val oid = openid()
        if (oid.isEmpty()) {
            tvStatsSummary.text = getString(R.string.stats_need_openid)
            return
        }
        panelBusy = true
        btnRefreshStats.isEnabled = false
        tvStatsSummary.text = getString(R.string.stats_loading)

        Thread {
            try {
                if (!engine.connect(oid)) {
                    ui.post {
                        panelBusy = false
                        btnRefreshStats.isEnabled = true
                        if (engine.netFail) {
                            tvStatsSummary.text = getString(R.string.conn_failed_title)
                            showConnectFailed()
                        } else {
                            tvStatsSummary.text = getString(R.string.stats_bad_openid)
                        }
                    }
                    return@Thread
                }
                val s = engine.fetchStats()
                ui.post {
                    panelBusy = false
                    btnRefreshStats.isEnabled = true
                    if (s == null) tvStatsSummary.text = getString(R.string.stats_failed)
                    else renderStats(s)
                }
            } catch (t: Throwable) {
                ui.post {
                    panelBusy = false
                    btnRefreshStats.isEnabled = true
                    tvStatsSummary.text = "拉取异常：${t.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun renderStats(s: Stats) {
        tvStatsSummary.text = buildString {
            append("有效　").append(s.validCount).append(" 次\n")
            append("无效　").append(s.invalidCount).append(" 次\n")
            append("里程　").append("%.2f".format(s.totalKm)).append(" km\n")
            append("均配　")
            append(if (s.avgPaceSecKm > 0) RunEngine.fmtPace(s.avgPaceSecKm) + "/km" else "--")
            append("\n记录　").append(s.total).append(" 条")
        }
        tvStatsByPlace.text = if (s.byPlace.isEmpty()) getString(R.string.stats_dash)
        else s.byPlace.joinToString("\n") { "${it.first}　${it.second} 次" }

        tvStatsList.text = if (s.rows.isEmpty()) getString(R.string.stats_dash)
        else s.rows.joinToString("\n") { r ->
            val mark = if (r.valid) "✓" else "✗"
            val extra = if (r.valid) "" else "  ${r.reason}"
            "%s %-8s %5.2fkm %8s%s".format(mark, r.placeName, r.distanceKm, r.timeText, extra)
        }
    }

    // ---------------------------------------------------------------- 脚本

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

    // ---------------------------------------------------------------- 主题 / 仓库

    private fun applyThemeMode(mode: Int) {
        prefs.edit().putInt("themeMode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
        updateThemeUi()
    }

    private fun currentTheme(): Int =
        prefs.getInt("themeMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    private fun updateThemeUi() {
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
