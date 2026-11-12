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
 */
package com.whale.campusrun

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), RunListener {

    private lateinit var mapView: MapCanvasView
    private lateinit var btnRun: LiquidButton
    private lateinit var ringProgress: RunProgressView
    private lateinit var tvRemainLabel: TextView
    private lateinit var tvRemain: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvPace: TextView
    private lateinit var tvPhase: TextView
    private lateinit var countdown: CountdownView

    private val api = Api()
    private lateinit var engine: RunEngine
    private val ui = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("campusrun", MODE_PRIVATE) }

    /** 自动补拉是否在进行中，防止每次 onResume 都起一条线程。 */
    @Volatile
    private var autoFetching = false

    /** 串行化「连接 + 拉场地」，避免自动补拉和点开始同时换 token。 */
    private val fetchLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        RunController.attachApp(this)
        engine = RunEngine(api, this)

        mapView = findViewById(R.id.mapView)
        btnRun = findViewById(R.id.btnRun)
        ringProgress = findViewById(R.id.ringProgress)
        tvRemainLabel = findViewById(R.id.tvRemainLabel)
        tvRemain = findViewById(R.id.tvRemain)
        tvDistance = findViewById(R.id.tvDistance)
        tvPace = findViewById(R.id.tvPace)
        tvPhase = findViewById(R.id.tvPhase)
        countdown = findViewById(R.id.countdown)

        findViewById<LiquidButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<LiquidButton>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        btnRun.setOnClickListener { onRunButtonClicked() }

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
        // 倒计时期间切走（锁屏 / 回桌面）：直接取消。
        // 倒计时是「我坐在这儿准备开跑」的确认动作，人都不看了就不该继续倒数，
        // 否则回来时会发现已经在跑了，或者看到一段跑到一半的轨迹。
        if (countingDown) {
            cancelCountdown()
            RunController.logFromUi("已取消（倒计时中离开页面）")
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来：场地可能换了 / openid 可能换了，只重画边界，不动正在跑的打卡
        if (!RunController.running) {
            dropIfOpenidChanged()
            showArea()
            autoFetchIfNeeded()
        }
    }

    private fun restoreOrInit() {
        dropIfOpenidChanged()
        restoreAreas()
        if (RunController.running) {
            RunController.logFromUi("（已重新附着到正在进行的打卡）")
        } else {
            showArea()
            autoFetchIfNeeded()
        }
    }

    /**
     * 设置页里把 openid 改了、但还没点【连接】：内存和磁盘里那份场地都属于上一个账号。
     * 不丢的话主页会先画出别人的场地，等补拉回来才换掉。
     */
    private fun dropIfOpenidChanged() {
        val oid = openid()
        if (AreaStore.publishedOpenid.isNotEmpty() && AreaStore.publishedOpenid != oid) {
            AreaStore.invalidate()
            AreaCache.clear(this)
        }
    }

    /**
     * 冷启动先把上次落盘的场地还原进 [AreaStore]。
     *
     * 不还原的话，进程被杀之后主页地图是空的、设置页下拉框也回到「先连接拉取场地」，
     * 用户以为上次拉的场地白拉了。这里只认同一个 openid 的缓存 —— 换账号不能拿旧场地来画。
     */
    private fun restoreAreas() {
        val oid = openid()
        if (oid.isEmpty() || AreaStore.hasResultFor(oid)) return
        val cached = AreaCache.load(this) ?: return
        if (cached.first != oid) return
        AreaStore.publish(cached.first, cached.second)
        bindPlaceFromCache()
    }

    /** 缓存里有上次选的场地就继续用它；没有的话退回列表第一个（并记住）。 */
    private fun bindPlaceFromCache() {
        val list = AreaStore.snapshot()
        if (list.isEmpty()) return
        val code = prefs.getString("placeCode", "").orEmpty()
        if (list.none { it.placeCode == code }) {
            prefs.edit().putString("placeCode", list.first().placeCode).apply()
        }
    }

    /**
     * 启动时如果没有场地（首次安装 / 升级后第一次跑 / 缓存被清），
     * 在后台静默补拉一次，拉到就落盘。用户不用先跑一趟设置页。
     */
    private fun autoFetchIfNeeded() {
        if (RunController.running || countingDown || autoFetching) return
        val oid = openid()
        if (oid.isEmpty()) return
        if (AreaStore.hasResultFor(oid)) return
        if (AreaStore.isBadOpenid(oid) || AreaStore.inCooldown(oid)) return

        autoFetching = true
        Thread {
            try {
                fetchAreasFor(oid, silent = true)
            } catch (t: Throwable) {
                logStack("自动拉取场地", t)
            } finally {
                autoFetching = false
                ui.post {
                    if (!RunController.running) {
                        bindPlaceFromCache()
                        showArea()
                    }
                }
            }
        }.start()
    }

    /** 用上次选中的场地画边界。没场地就空着，等用户去设置页连。 */
    private fun showArea() {
        val code = prefs.getString("placeCode", "").orEmpty()
        val list = AreaStore.snapshot()
        val area = list.firstOrNull { it.placeCode == code }
            ?: list.firstOrNull()
            ?: return
        // 选中的这个就是上次那个，没有的话把兜底选的写回去，下次启动才能原样还原
        if (area.placeCode != code) {
            prefs.edit().putString("placeCode", area.placeCode).apply()
        }
        mapView.setArea(area.fence, area.excludes)
    }

    private fun welcome() {
        RunController.logFromUi("鲸鲸约跑 v${BuildConfig.VERSION_NAME}")
        RunController.logFromUi("① 右上角【设置】填 openid、连场地、设配速")
        RunController.logFromUi("② 回主页点【开始打卡】")
        RunController.logFromUi("开始后可以锁屏/切走，别从最近任务划掉")
    }

    private fun openid(): String = prefs.getString("openid", "").orEmpty().trim()

    // ---------------------------------------------------------------- 打卡
    //
    // 打卡分两段：**3 秒倒计时** + **数据准备**，两者并行跑。
    //
    // 为什么不再「点一下就直接开跑」：
    //   点下去之后引擎要先换 token、拉场地、生成轨迹，几百毫秒到一两秒不等，
    //   这段时间界面毫无反馈，人会以为没点上，再点一下 —— 那一下就可能是重复触发。
    //   现在点下去地图上立刻盖一层倒计时，3 秒走完才真正开跑；
    //   这 3 秒同时是「反悔窗口」：再点一次按钮就取消。
    //
    // 数据准备和倒计时是**并行**的：3 秒足够把 token 换完、轨迹算好，
    // 于是倒计时一结束就立刻开跑，不会出现「倒计时走完还要再等两秒」。
    // 万一下载慢（弱网），倒计时结束时就把状态改成「准备中…」继续等，
    // 数据到了再启动 —— 不为了凑这 3 秒去砍掉准备步骤。

    /** 当前这一段「倒计时 + 准备」的编号。取消时 +1，让在途的结果自己作废。 */
    private var runSeq = 0

    private var countingDown = false

    /** 倒计时 3 秒是否已经走完（数字归零）。 */
    private var countdownDone = false

    /** 数据准备是否已经完成（成功或失败都算完成，失败时 [preparedArea] 为 null）。 */
    private var prepDone = false
    private var preparedArea: Area? = null
    private var preparedTrack: List<DoubleArray> = emptyList()

    private fun onRunButtonClicked() {
        when {
            RunController.running -> doStop()
            countingDown -> cancelCountdown()
            else -> beginCountdown()
        }
    }

    private fun beginCountdown() {
        if (countingDown) return

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
        // 轨迹毛刺开关（设置 → 跑步参数）。开跑之前读一次，跑的过程中不再变。
        val burrs = prefs.getBoolean("burrs", false)

        val seq = ++runSeq
        countingDown = true
        countdownDone = false
        prepDone = false
        preparedArea = null
        preparedTrack = emptyList()

        // 按钮变成【取消】：这 3 秒里它是「反悔」，不是「再来一次」
        btnRun.setText("取消")
        btnRun.tone = LiquidButton.Tone.ACCENT
        btnRun.setTextColor(ContextCompat.getColor(this, R.color.on_accent))

        // 先把界面上跟"上一次跑完"有关的东西清干净，倒计时背景才不像糊了一层脏数据
        resetDashboardForCountdown()

        countdown.onFinished = {
            countdownDone = true
            maybeLaunchRun(seq, oid, pace, dist, tol, burrs)
        }
        countdown.start()
        startPreparing(seq, oid, pace, dist, tol, burrs)
    }

    /** 倒计时期间把仪表盘归零，否则旧数据会隔着半透明遮罩透出来。 */
    private fun resetDashboardForCountdown() {
        mapView.setProgress(0f)
        // 占位提示会和倒计时的大号秒数叠字，先收起来
        mapView.setHintVisible(false)
        ringProgress.setProgress(0f, "0%")
        tvRemainLabel.text = "状态"
        tvRemain.text = "准备中"
        tvDistance.text = "0.00 / 0.00 km"
        tvPace.text = "配速 --"
        tvPhase.text = "倒计时中，再点一次可取消"
    }

    /** 后台准备：连场地 + 生成轨迹。结果按 [seq] 落格，取消后自然作废。 */
    private fun startPreparing(
        seq: Int,
        oid: String,
        pace: Double,
        dist: Double,
        tol: Double,
        burrs: Boolean
    ) {
        Thread {
            var area: Area? = null
            var traj: List<DoubleArray> = emptyList()
            try {
                if (!AreaStore.hasAreasFor(oid)) fetchAreasFor(oid, silent = true)
                val code = prefs.getString("placeCode", "").orEmpty()
                area = AreaStore.snapshot().firstOrNull { it.placeCode == code }
                    ?: AreaStore.snapshot().firstOrNull()
                // 兜底选中的场地也记下来，下次启动直接用
                area?.let { a ->
                    if (a.placeCode != code) {
                        prefs.edit().putString("placeCode", a.placeCode).apply()
                    }
                }

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
                            paceToleranceSec = tol,
                            burrs = burrs
                        )
                        traj = res.points.map { doubleArrayOf(it.lat, it.lon) }
                    } catch (t: Throwable) {
                        RunController.logFromUi("✗ 轨迹生成失败：${t.message}；改由引擎内部生成")
                    }
                }
            } catch (t: Throwable) {
                logStack("连接", t)
            } finally {
                ui.post {
                    // 中途被取消了：结果直接丢掉，别把「上一轮的准备」跑到下一轮头上
                    if (seq != runSeq) return@post
                    preparedArea = area
                    preparedTrack = traj
                    prepDone = true
                    maybeLaunchRun(seq, oid, pace, dist, tol, burrs)
                }
            }
        }.start()
    }

    /**
     * 倒计时走完 **且** 数据准备好，才真正开跑。
     *
     * 两个条件谁先到都行，后到的那个负责启动；已经在跑或者被取消就直接返回。
     */
    private fun maybeLaunchRun(
        seq: Int,
        oid: String,
        pace: Double,
        dist: Double,
        tol: Double,
        burrs: Boolean
    ) {
        if (seq != runSeq || !countingDown) return
        if (!countdownDone) return
        if (!prepDone) {
            // 弱网：倒计时先走完了，就把状态改成"准备中"，继续等数据
            tvRemain.text = "准备中"
            tvPhase.text = "正在拉取场地 / 生成轨迹…"
            return
        }

        countingDown = false
        mapView.setHintVisible(true)
        val a = preparedArea
        if (a == null) {
            // 准备失败：退回空闲态，让用户可以重试
            btnRun.setText(R.string.b_run)
            refreshRunButton()
            tvRemain.text = "未开始"
            tvPhase.text = ""
            toast("没有可用场地，去设置里连接并选择")
            return
        }

        mapView.setArea(a.fence, a.excludes)
        mapView.setTrack(preparedTrack)
        mapView.setProgress(0f)

        RunController.start(
            openid = oid,
            area = a,
            paceSecKm = pace,
            targetM = dist * 1000.0,
            noise = 0.6,
            seed = (System.currentTimeMillis() % 100000).toInt(),
            paceToleranceSec = tol,
            burrs = burrs
        )
        refreshRunButton()
    }

    /** 倒计时里再点一次：取消，回到空闲。已经在跑的那一轮不受影响。 */
    private fun cancelCountdown() {
        if (!countingDown) return
        countingDown = false
        runSeq++                  // 让在途的准备结果作废
        countdown.cancel()
        mapView.setHintVisible(true)
        countdown.onFinished = null
        btnRun.setText(R.string.b_run)
        refreshRunButton()
        tvRemain.text = "未开始"
        tvPhase.text = ""
        RunController.logFromUi("已取消（倒计时内）")
        toast("已取消")
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
            // 落盘：下次冷启动不用先打网络就能画出边界、自动选中同一个场地
            AreaCache.save(applicationContext, oid, list)
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
        // 倒计时中按钮固定是【取消】：这段时间里 running 还是 false，
        // 走下面的分支会被状态广播反复改回【开始打卡】，看着像没点上。
        if (countingDown) return

        val running = RunController.running
        btnRun.setText(if (running) R.string.b_stop else R.string.b_run)
        // 液体按钮自己画背景，切态只改 tone —— 不再 setBackgroundResource
        btnRun.tone = if (running) LiquidButton.Tone.DANGER else LiquidButton.Tone.ACCENT
        btnRun.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.danger else R.color.on_accent)
        )
    }

    // ---------------------------------------------------------------- 状态

    override fun onState(s: RunState) {
        // 倒计时 / 准备阶段：仪表盘已经由 resetDashboardForCountdown() 接管，
        // 状态广播这时候大多是上一轮的收尾（"完成"/"中断"），不该盖回来。
        if (countingDown) return

        ui.post {
            val frac = if (s.metersTotal > 0)
                (s.metersDone / s.metersTotal).toFloat().coerceIn(0f, 1f) else 0f
            mapView.setProgress(if (s.finished || s.failed) 1f else frac)

            val pct = (frac * 100).toInt()
            val eta = if (s.etaSec >= 0) RunEngine.fmtDuration(s.etaSec) else "--:--"
            val pace = if (s.paceSecKm > 0) RunEngine.fmtPace(s.paceSecKm) + "/km" else "配速 --"

            ringProgress.setProgress(
                if (s.finished || s.failed) 1f else frac,
                when {
                    s.failed -> "中断"
                    s.finished -> "完成"
                    s.metersTotal > 0f -> "$pct%"
                    else -> "0%"
                },
                done = s.finished
            )

            val idle = s.etaSec < 0 && !s.finished && !s.failed
            tvRemainLabel.text = if (idle) "状态" else getString(R.string.dash_remain_label)
            tvRemain.text = when {
                s.finished -> "已跑完"
                s.failed -> "已中断"
                s.etaSec >= 0 -> eta
                else -> "未开始"
            }
            tvDistance.text = "%.2f / %.2f km".format(s.metersDone / 1000.0, s.metersTotal / 1000.0)
            tvPace.text = buildString {
                append(pace)
                append("　·　用时 ").append(RunEngine.fmtDuration(s.elapsedSec))
                if (s.serverMileage >= 0) {
                    append("　·　服务端 ").append("%.2f km".format(s.serverMileage))
                }
            }
            // 空闲时「状态 空闲」和上面那行「未开始」重复，直接留空
            tvPhase.text = if (idle) "" else s.phase
            refreshRunButton()
        }
    }

    override fun log(line: String) {
        // 主页不铺日志，完整日志在【日志】页
    }

    private fun logStack(where: String, t: Throwable) {
        RunController.logFromUi("✗ $where 异常：${t.javaClass.name}: ${t.message}")
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
        /** 本项目仓库（设置 → 关于 里的「本项目仓库」打开 / 复制都指向它）。 */
        const val REPO_URL = "https://github.com/gufandf/CQUPT-MockRunning"

        /** 原仓库，只作出处说明。 */
        const val REPO_URL_ORIGIN = "https://github.com/WilliTourt/CQUPT-MockRunning"
    }
}
