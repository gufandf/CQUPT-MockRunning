/*
 * 鲸鲸约跑 · 主界面
 *
 * 布局只有三块：
 *   顶栏    鲸鲸约跑 + 日志 + 设置
 *   地图    场地边界 / 禁跑区 / 实时轨迹（MapCanvasView，自己画的）
 *   底部    电池状态 + 仪表盘摘要 + 【开始打卡】
 *
 * 所有参数（openid、场地、配速、距离、抖动）和统计、教程都在 SettingsActivity，
 * 主页不再堆表单 —— 这是「安卓原生侧滑 + 一个页面一件主任务」的取舍。
 *
 * 跑步线程归 RunController 单例所有，本 Activity 只是观察者。
 *
 * 作者：WilliTourt的蓝色大肥鱼 🐟
 */
package com.whale.campusrun

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), RunListener {

    private lateinit var mapView: MapCanvasView
    private lateinit var btnRun: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvBattery: TextView

    private val api = Api()
    private lateinit var engine: RunEngine
    private val ui = Handler(Looper.getMainLooper())

    private var busy = false
    private val prefs by lazy { getSharedPreferences("campusrun", MODE_PRIVATE) }

    /** 串行化「连接 + 拉场地」，避免自动补拉和点开始同时换 token。 */
    private val fetchLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        RunController.attachApp(this)
        engine = RunEngine(api, this)

        mapView = findViewById(R.id.mapView)
        btnRun = findViewById(R.id.btnRun)
        tvStatus = findViewById(R.id.tvStatus)
        tvBattery = findViewById(R.id.tvBattery)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        btnRun.setOnClickListener { if (RunController.running) doStop() else doRun() }
        tvBattery.setOnClickListener {
            if (isIgnoringBatteryOptimizations()) toast("已经在电池白名单里了")
            else showBatteryDialog()
        }

        updateBatteryHint()
        askNotificationPermission()
        restoreOrInit()
    }

    override fun onStart() {
        super.onStart()
        RunController.addListener(this)
        onState(RunController.state)
        refreshRunButton()
        if (RunController.logSnapshot().isEmpty()) welcome()
    }

    override fun onStop() {
        RunController.removeListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryHint()
        // 从设置页回来：场地可能换了 / openid 可能换了，只重画边界，不动正在跑的打卡
        if (!RunController.running) showArea()
    }

    private fun restoreOrInit() {
        if (RunController.running) {
            RunController.logFromUi("（已重新附着到正在进行的打卡）")
        } else {
            showArea()
        }
    }

    private fun welcome() {
        RunController.logFromUi("鲸鲸约跑 v${BuildConfig.VERSION_NAME}")
        RunController.logFromUi("① 右上角【设置】填 openid、连场地、设配速")
        RunController.logFromUi("② 回主页点【开始打卡】")
        RunController.logFromUi("开始后可以锁屏/切走，别从最近任务划掉")
    }

    /** 用上次选中的场地画边界。没场地就空着，等用户去设置页连。 */
    private fun showArea() {
        val code = prefs.getString("placeCode", "").orEmpty()
        val area = AreaStore.snapshot().firstOrNull { it.placeCode == code }
            ?: AreaStore.snapshot().firstOrNull()
            ?: return
        mapView.setArea(area.fence, area.excludes)
    }

    // ---------------------------------------------------------------- 打卡

    private fun doRun() {
        if (busy) return

        val oid = prefs.getString("openid", "").orEmpty().trim()
        if (oid.isEmpty()) {
            toast("请先在设置里填写 openid")
            return
        }
        val pace = RunEngine.parsePace(prefs.getString("pace", "5:45").orEmpty())
        if (pace == null || pace < 60) {
            toast("配速格式不对，去设置里改")
            return
        }
        val dist = prefs.getString("dist", "2.0").orEmpty().toDoubleOrNull()
        if (dist == null || dist <= 0) {
            toast("距离格式不对，去设置里改")
            return
        }
        val tol = prefs.getString("paceTol", "20").orEmpty().toDoubleOrNull()
        if (tol == null || tol < 0) {
            toast("配速抖动格式不对，去设置里改")
            return
        }

        busy = true
        btnRun.isEnabled = false

        Thread {
            var area: Area? = null
            var traj: List<DoubleArray> = emptyList()
            try {
                if (!AreaStore.hasAreasFor(oid)) fetchAreasFor(oid, silent = false)
                val code = prefs.getString("placeCode", "").orEmpty()
                area = AreaStore.snapshot().firstOrNull { it.placeCode == code }
                    ?: AreaStore.snapshot().firstOrNull()

                // 整条轨迹一次性生成好，再按进度逐段显示 —— 「实时轨迹」不需要真定位
                area?.let { a ->
                    try {
                        val res = TrackGen.generate(
                            poly = a.fence,
                            excludes = a.excludes,
                            paceSecKm = pace,
                            targetM = dist * 1000.0,
                            noise = 0.6,
                            seed = (System.currentTimeMillis() % 100000).toInt(),
                            paceToleranceSec = tol
                        )
                        traj = res.points.map { doubleArrayOf(it.lat, it.lon) }
                    } catch (t: Throwable) {
                        RunController.logFromUi("✗ 轨迹生成失败：${t.message}；改由引擎内部生成")
                    }
                }
            } catch (t: Throwable) {
                logStack("连接", t)
            } finally {
                val a = area
                val tr = traj
                ui.post {
                    busy = false
                    btnRun.isEnabled = true
                    if (a == null) {
                        toast("没有可用场地，去设置里连接并选择")
                        return@post
                    }
                    mapView.setArea(a.fence, a.excludes)
                    mapView.setTrack(tr)
                    mapView.setProgress(0f)

                    RunController.start(
                        openid = oid,
                        area = a,
                        paceSecKm = pace,
                        targetM = dist * 1000.0,
                        noise = 0.6,
                        seed = (System.currentTimeMillis() % 100000).toInt(),
                        paceToleranceSec = tol
                    )
                    refreshRunButton()
                }
            }
        }.start()
    }

    private fun doStop() {
        RunController.cancel()
        toast("正在停止，当前批次传完就停")
        refreshRunButton()
    }

    private fun fetchAreasFor(oid: String, silent: Boolean): Boolean {
        synchronized(fetchLock) {
            if (AreaStore.hasAreasFor(oid)) return true
            if (!engine.connect(oid)) {
                if (engine.netFail) AreaStore.markNetFailed(oid)
                else AreaStore.markBadOpenid(oid)
                if (engine.netFail && !silent) ui.post { showConnectFailed() }
                return false
            }
            engine.fetchRule()
            val list = engine.fetchAreas()
            if (engine.netFail) {
                AreaStore.markNetFailed(oid)
                if (!silent) ui.post { showConnectFailed() }
                return false
            }
            AreaStore.publish(oid, list)
            return list.isNotEmpty()
        }
    }

    private fun showConnectFailed() {
        try {
            AlertDialog.Builder(this)
                .setTitle("无法连接到服务器")
                .setMessage("请检查网络；也可以去设置页点【连接并拉取场地】重试。")
                .setPositiveButton("好", null)
                .show()
        } catch (_: Throwable) {
            toast("连接失败，请检查网络")
        }
    }

    private fun refreshRunButton() {
        val running = RunController.running
        btnRun.setText(if (running) R.string.b_stop else R.string.b_run)
        btnRun.setBackgroundResource(
            if (running) R.drawable.btn_danger else R.drawable.btn_solid
        )
        btnRun.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.danger else R.color.on_accent)
        )
    }

    // ---------------------------------------------------------------- 状态

    override fun onState(s: RunState) {
        ui.post {
            val frac = if (s.metersTotal > 0)
                (s.metersDone / s.metersTotal).toFloat().coerceIn(0f, 1f) else 0f
            mapView.setProgress(if (s.finished || s.failed) 1f else frac)

            val pct = (frac * 100).toInt()
            val eta = if (s.etaSec >= 0) RunEngine.fmtDuration(s.etaSec) else "--:--"
            val pace = if (s.paceSecKm > 0) RunEngine.fmtPace(s.paceSecKm) + "/km" else "--"
            tvStatus.text = buildString {
                append("进度 ").append(pct).append("%　")
                append("已跑 %.2f / %.2f km".format(s.metersDone / 1000.0, s.metersTotal / 1000.0))
                append("\n用时 ").append(RunEngine.fmtDuration(s.elapsedSec))
                append("　剩余 ").append(eta)
                append("\n配速 ").append(pace).append("　状态 ").append(s.phase)
                if (s.serverMileage >= 0) {
                    append("\n服务端 ").append("%.2f km".format(s.serverMileage))
                }
            }
            refreshRunButton()
        }
    }

    override fun log(line: String) {
        // 主页不铺日志，完整日志在【日志】页
    }

    private fun logStack(where: String, t: Throwable) {
        RunController.logFromUi("✗ $where 异常：${t.javaClass.name}: ${t.message}")
    }

    // ---------------------------------------------------------------- 电池

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (t: Throwable) {
            true
        }
    }

    private fun updateBatteryHint() {
        val ok = isIgnoringBatteryOptimizations()
        tvBattery.text =
            if (ok) "✓ 已在电池白名单，息屏可放心兜里放"
            else "⚠ 未加电池白名单 · 点这里设置"
        tvBattery.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.danger))
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

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val REPO_URL = "https://github.com/WilliTourt/CQUPT-MockRunning"
    }
}
