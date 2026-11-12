/*
 * 设置首页（分层结构的第一层）。
 *
 * 只做两件事：
 *   ① 列出七个分组入口
 *   ② 每条下面写清「这组现在是什么状态」—— openid 填没填、场地选的哪个、
 *      配速多少、主题跟不跟系统，不用点进去猜
 *
 * 真正的表单在二级页：SettingsGroupActivity + settings_group_*.xml。
 * 分层的理由见 AGENT.md §3：七段表单平铺一页时，滚动条很长、主次也看不出来。
 */
package com.whale.campusrun

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("campusrun", MODE_PRIVATE) }

    private lateinit var rowAccount: SettingsRow
    private lateinit var rowRun: SettingsRow
    private lateinit var rowStats: SettingsRow
    private lateinit var rowTutorial: SettingsRow
    private lateinit var rowLive: SettingsRow
    private lateinit var rowAppearance: SettingsRow
    private lateinit var rowAbout: SettingsRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.tvGroupTitle).setText(R.string.settings)
        findViewById<LiquidButton>(R.id.btnBack).setOnClickListener { finish() }

        rowAccount = findViewById(R.id.rowAccount)
        rowRun = findViewById(R.id.rowRun)
        rowStats = findViewById(R.id.rowStats)
        rowTutorial = findViewById(R.id.rowTutorial)
        rowLive = findViewById(R.id.rowLive)
        rowAppearance = findViewById(R.id.rowAppearance)
        rowAbout = findViewById(R.id.rowAbout)

        bind(R.id.rowAccount, SettingsGroupActivity.G_ACCOUNT)
        bind(R.id.rowRun, SettingsGroupActivity.G_RUN)
        bind(R.id.rowStats, SettingsGroupActivity.G_STATS)
        bind(R.id.rowTutorial, SettingsGroupActivity.G_TUTORIAL)
        bind(R.id.rowLive, SettingsGroupActivity.G_LIVE)
        bind(R.id.rowAppearance, SettingsGroupActivity.G_APPEARANCE)
        bind(R.id.rowAbout, SettingsGroupActivity.G_ABOUT)
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun bind(rowId: Int, group: String) {
        findViewById<android.view.View>(rowId).setOnClickListener {
            SettingsGroupActivity.start(this, group)
        }
    }

    /** 首页摘要：都从本地状态算，不打网络。 */
    private fun refreshSummaries() {
        val oid = prefs.getString("openid", "").orEmpty().trim()
        rowAccount.setSummary(buildString {
            append(if (oid.isEmpty()) "openid 未填" else "openid ${mask(oid)}")
            val area = areaLabel()
            if (area.isNotEmpty()) append("　·　").append(area)
        })

        rowRun.setSummary(buildString {
            append(prefs.getString("pace", "5:45")).append(" /km")
            append("　·　").append(prefs.getString("dist", "2.0")).append(" km")
            append("　·　抖动 ±").append(prefs.getString("paceTol", "20")).append(" 秒")
        })



        rowLive.setValue(if (LiveCapsule.supported(this@SettingsActivity)) "可用" else "不可用")

        rowAppearance.setValue(when (prefs.getInt("themeMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)) {
            AppCompatDelegate.MODE_NIGHT_YES -> "深色"
            AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
            else -> "跟随系统"
        })

        rowAbout.setValue("v${BuildConfig.VERSION_NAME}")
    }

    /** 场地名从缓存里取 —— 进二级页拉过之后，这里也能显示出来。 */
    private fun areaLabel(): String {
        val cached = AreaCache.load(this) ?: return ""
        val code = prefs.getString("placeCode", "").orEmpty()
        return cached.second.firstOrNull { it.placeCode == code }?.placeName
            ?: cached.second.firstOrNull()?.placeName
            ?: ""
    }

    /** openid 是身份凭据，列表里只露头尾，避免顺手截图把整串带出去。 */
    private fun mask(oid: String): String =
        if (oid.length <= 8) "已填" else "${oid.take(4)}…${oid.takeLast(3)}"
}
