/*
 * 全屏轨迹页：一条历史记录的地图 + 摘要 + 导出 GPX。
 *
 * 数据来源是 `GET /sportPoint/listAll/{sportRecordNo}`（见 refs/FINDINGS.md §4.4）：
 *   · 返回**时间倒序**，画图前必须 reverse；
 *   · 经纬度是**字符串**，直接参与运算会炸；
 *   · 有的记录服务端就是没有点（62 条里 32 条空），那不叫出错，要如实说明。
 *
 * 为什么用记录号去拉，而不是从成绩列表里带过来：
 *   成绩列表和轨迹点是两份存储（同一记录可能「列表有里程但拉不到轨迹」）。
 *   这里只认记录号 —— 列表里的距离/用时是服务端算的，轨迹是另一份实测点，
 *   两者对不上是常态，所以界面上分开显示（摘要标题来自列表，轨迹统计来自 listAll）。
 *
 * 导出走 FileProvider + ACTION_SEND：和「分享脚本」同一条路，
 * 不申请存储权限，也不往用户相册里塞文件。
 */
package com.whale.campusrun

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class TrackActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_NO = "recordNo"
        private const val EXTRA_PLACE = "placeName"
        private const val EXTRA_CODE = "placeCode"
        private const val EXTRA_DATE = "dateLabel"

        fun start(
            ctx: Context,
            recordNo: String,
            placeName: String,
            placeCode: String,
            dateLabel: String
        ) {
            ctx.startActivity(
                Intent(ctx, TrackActivity::class.java)
                    .putExtra(EXTRA_NO, recordNo)
                    .putExtra(EXTRA_PLACE, placeName)
                    .putExtra(EXTRA_CODE, placeCode)
                    .putExtra(EXTRA_DATE, dateLabel)
            )
        }
    }

    private lateinit var map: MapCanvasView
    private lateinit var tvSummary: TextView
    private lateinit var tvSub: TextView
    private lateinit var btnExport: LiquidButton

    private var recordNo: String = ""
    private var placeName: String = ""

    /** 场地编号。从记录号里也能拆出来，但能拿到就用拿到的。 */
    private var placeCode: String = ""

    /** 拉回来的点，按时间**正序**（listAll 给的是倒序，这里已经 reverse 过）。 */
    private var points: List<GpxExport.Point> = emptyList()

    /** 首点时间（毫秒）。GPX 的时间轴以它为基准。 */
    private var startMs: Long = 0L

    private val api = Api()

    /** 换 token / 拉点这两步只关心成败，不需要往日志页写字。 */
    private val quiet = object : RunListener {
        override fun log(line: String) {}
        override fun onState(s: RunState) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track)

        recordNo = intent.getStringExtra(EXTRA_NO).orEmpty()
        placeName = intent.getStringExtra(EXTRA_PLACE).orEmpty()
        placeCode = intent.getStringExtra(EXTRA_CODE).orEmpty()
            .ifEmpty { recordNo.split("_").getOrNull(1).orEmpty() }
        val dateLabel = intent.getStringExtra(EXTRA_DATE).orEmpty()

        map = findViewById(R.id.mapTrack)
        tvSummary = findViewById(R.id.tvTrackSummary)
        tvSub = findViewById(R.id.tvTrackSub)
        btnExport = findViewById(R.id.btnExport)

        findViewById<TextView>(R.id.tvTrackTitle).text =
            if (placeName.isNotEmpty()) placeName else getString(R.string.track_title)
        findViewById<LiquidButton>(R.id.btnBack).setOnClickListener { finish() }

        tvSummary.text = if (dateLabel.isNotEmpty()) dateLabel else recordNo
        tvSub.text = getString(R.string.track_loading)
        setExportEnabled(false)

        // 场地边界用缓存里那份（同一个 openid 的），没有就只画轨迹。
        // 先看内存快照；进程被杀后直接进本页时内存是空的，再退到磁盘缓存 ——
        // 不这么做的话，同一个场地的轨迹在首页有边界、在历史页却没边界。
        val cached = AreaStore.snapshot().ifEmpty {
            val oid = getSharedPreferences("campusrun", MODE_PRIVATE)
                .getString("openid", "").orEmpty()
            AreaCache.load(this)?.takeIf { it.first == oid }?.second.orEmpty()
        }
        // 按 placeCode 找围栏：它是记录号里的稳定段，而 placeName 可能被服务端改叫法
        // （"风华运动场" / "风华运动场(东)"）。找不到再退回按名字匹配。
        val area = cached.firstOrNull { placeCode.isNotEmpty() && it.placeCode == placeCode }
            ?: cached.firstOrNull { placeName.isNotEmpty() && it.placeName == placeName }
        area?.let { map.setArea(it.fence, it.excludes) }

        btnExport.setOnClickListener { exportGpx() }
        loadPoints()
    }

    private fun setExportEnabled(on: Boolean) {
        btnExport.isEnabled = on
        btnExport.alpha = if (on) 1f else 0.5f
    }

    /**
     * 拉轨迹点。
     *
     * 需要 token，所以要先 checkBinding 一次 —— 这个 App 里唯一
     * 「只读一条历史数据也要换 token」的地方，但接口就是这么设计的。
     */
    private fun loadPoints() {
        if (recordNo.isEmpty()) {
            tvSub.text = getString(R.string.track_no_record_no)
            return
        }
        val openid = getSharedPreferences("campusrun", MODE_PRIVATE)
            .getString("openid", "").orEmpty()

        Thread({
            // 用 RunEngine.connect 而不是直接调 api.checkBinding：换 token 有一层
            // 业务判断（isBind / token 是否为空 / 网络层 vs 业务层失败），
            // 这里复制一遍必然和打卡链路漂。
            val engine = RunEngine(api, quiet)
            val result = try {
                if (openid.isEmpty() || !engine.connect(openid)) {
                    val why = if (engine.netFail) getString(R.string.track_fetch_failed)
                    else getString(R.string.track_need_openid)
                    Result.failure(Throwable(why))
                } else {
                    val r = Resp.of(api.getPointsByNo(recordNo))
                    val arr = r.data as? JSONArray
                        ?: return@Thread runOnUiThread { fail(getString(R.string.track_fetch_failed)) }
                    parsePoints(arr)
                }
            } catch (t: Throwable) {
                Result.failure(t)
            }

            result.onSuccess { (pts, first) ->
                runOnUiThread { if (!isFinishing && !isDestroyed) show(pts, first) }
            }.onFailure { e ->
                val msg = e.message?.takeIf { it.isNotEmpty() }
                    ?: getString(R.string.track_fetch_failed)
                runOnUiThread { if (!isFinishing && !isDestroyed) fail(msg) }
            }
        }, "campusrun-track").start()
    }

    /**
     * 把 listAll 的数组拍成「按时间正序的点 + 首点时间」。
     *
     * 两趟：第一趟只求出首点时间（倒序数组的最后一个就是最早的点），
     * 第二趟才算每个点的秒偏移 —— 一次遍历算不出来，因为偏移要相对首点。
     */
    private fun parsePoints(arr: JSONArray): Result<Pair<List<GpxExport.Point>, Long>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        fun timeOf(o: org.json.JSONObject): Long = try {
            fmt.parse(o.optString("collectTime"))?.time ?: 0L
        } catch (_: Throwable) {
            0L
        }

        if (arr.length() == 0) {
            return Result.failure(Throwable(getString(R.string.track_empty)))
        }

        // listAll 是时间倒序 → 从尾往头扫就是正序
        var firstMs = 0L
        for (i in arr.length() - 1 downTo 0) {
            val t = timeOf(arr.optJSONObject(i) ?: continue)
            if (t > 0L) {
                firstMs = t
                break
            }
        }
        if (firstMs == 0L) {
            return Result.failure(Throwable(getString(R.string.track_fetch_failed)))
        }

        val pts = ArrayList<GpxExport.Point>(arr.length())
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optString("latitude").toDoubleOrNull() ?: continue
            val lon = o.optString("longitude").toDoubleOrNull() ?: continue
            val sec = ((timeOf(o).takeIf { it > 0L } ?: firstMs) - firstMs) / 1000
            pts.add(GpxExport.Point(lat, lon, sec.toInt()))
        }
        if (pts.size < 2) {
            return Result.failure(Throwable(getString(R.string.track_empty)))
        }
        return Result.success(pts to firstMs)
    }

    private fun show(pts: List<GpxExport.Point>, firstMs: Long) {
        points = pts
        startMs = firstMs

        // 整条轨迹一次画完（progress = 1），不做逐段显现 —— 这是历史记录
        map.setTrack(pts.map { doubleArrayOf(it.lat, it.lon) })
        map.setProgress(1f)
        map.setHintVisible(false)

        val meters = pts.zipWithNext().sumOf {
            Geo.hav(it.first.lat, it.first.lon, it.second.lat, it.second.lon)
        }
        val sec = pts.last().secOffset
        // 摘要行是给用户读的界面文本（「N 个点 · X km · mm:ss · 配速」），
        // 分隔符和单位都走资源；距离原来用 "%.2f km".format()，还顺手踩了
        // 默认 Locale（小数点会变成逗号），现在由 %1$.2f 在资源里定死
        val sep = getString(R.string.track_sep)
        tvSub.text = buildString {
            append(getString(R.string.track_points, pts.size))
            append(sep).append(getString(R.string.value_km, meters / 1000.0))
            append(sep).append(RunEngine.fmtDuration(sec))
            if (sec > 0 && meters > 0) {
                append(sep).append(RunEngine.fmtPace(sec / (meters / 1000.0)))
            }
        }
        setExportEnabled(true)
    }

    private fun fail(msg: String) {
        tvSub.text = msg
        // 拉不到点就别把地图留成一块空蓝色 —— 那看起来像"画崩了"而不是"没有轨迹"。
        // MapCanvasView 的占位提示本来就是给这种情况用的，只是文案是主页那两句，
        // 这里换成本页要说的。
        map.setHintText(getString(R.string.track_empty_hint), "")
        map.setHintVisible(true)
        setExportEnabled(false)
    }

    /**
     * 导出 GPX。
     *
     * 写到 cache/share/ 再走 FileProvider 发出去 —— 不申请存储权限，
     * 也不会在用户相册里留一堆文件。文件名带记录号，导多次不会互相覆盖。
     */
    private fun exportGpx() {
        if (points.size < 2 || startMs <= 0L) return
        try {
            val dir = File(cacheDir, "share")
            if (!dir.exists()) dir.mkdirs()
            val safe = recordNo.ifEmpty { "track" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val f = File(dir, "$safe.gpx")
            f.writeText(
                GpxExport.toGpx(recordNo, placeName, startMs, points),
                Charsets.UTF_8
            )
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, f.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, getString(R.string.track_export)))
        } catch (t: Throwable) {
            // 这行是直接写进摘要 TextView 给用户看的失败原因（不是日志），走资源
            tvSub.text = getString(R.string.track_export_failed, t.javaClass.simpleName)
        }
    }
}
