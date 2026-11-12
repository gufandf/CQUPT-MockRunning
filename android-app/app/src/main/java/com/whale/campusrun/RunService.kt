/*
 * 前台服务：只为"保命"。
 *
 * 手机揣兜里跑 11 分钟，App 在后台很容易被系统回收。挂上前台服务 + 常驻通知，
 * 进程就不会被随手杀掉。
 *
 * 通知本身交给 [LiveCapsule] —— 走 Android 16 的 Live Updates（ProgressStyle +
 * 提升为实时活动），ColorOS 16 的流体云会把同一套 API 的通知收成胶囊。
 * 所以既不需要 OPPO 私有 SDK，也不需要厂商白名单。
 *
 * 旧版那条「通知栏里铺三行文字」的进度提示已经删掉了：
 * 同样的信息现在在胶囊和展开后的实时活动里，铺两遍只是噪音。
 *
 * 不申请任何定位权限。
 */
package com.whale.campusrun

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat

class RunService : Service(), RunListener {

    companion object {
        const val CHANNEL = LiveCapsule.CHANNEL
        const val NOTI_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, RunService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (_: Throwable) {
                // 拿不到前台服务也不致命，只是退化成普通后台线程
            }
        }
    }

    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 上一次发出去的整数百分比。
     *
     * 状态机一秒能推好几次，但实时活动每发一次都要走一遍系统渲染，
     * 而且流体云胶囊对高频刷新并不友好。只在百分比真的变了才重发。
     * -1 表示「还没发过」，第一次一定发。
     */
    private var lastPct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        LiveCapsule.ensureChannel(this)
        // startForeground 必须给一条通知；起始状态就是「准备中」的胶囊
        val first = LiveCapsule.build(this, RunController.state)
        startForeground(NOTI_ID, first)
        // 一次性把「这台机器到底能不能出胶囊」写进日志，省得用户对着通知栏猜
        RunController.logFromUi("流体云：${LiveCapsule.diagnose(this, first)}")
        RunController.addListener(this)
        acquireWakeLock()
    }

    /**
     * 拿一把 CPU 锁。
     *
     * 前台服务能保证进程不被回收，但**挡不住 CPU 被挂起** ——
     * 手机揣兜里息屏之后，系统会把 CPU 停掉，Thread.sleep 就会睡过头，
     * 整轮打卡被拖成一倍长。这把锁让 CPU 一直醒着。
     *
     * 注意：深度 Doze 下系统会连 wake lock 一起忽略，
     * 所以还需要在界面里引导用户把本 App 加进电池优化白名单。
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "campusrun:run").apply {
                setReferenceCounted(false)
                acquire(70 * 60 * 1000L)      // 兜底 70 分钟，够跑好几轮
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RunController.running) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RunController.removeListener(this)
        releaseWakeLock()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App 也不要停
        if (!RunController.running) stopSelf()
    }

    override fun log(line: String) {
        // 通知里不显示日志，忽略
    }

    override fun onState(s: RunState) {
        if (!RunController.running) {
            stopSelf()
            return
        }
        val pct = if (s.metersTotal > 0)
            (s.metersDone / s.metersTotal * 100).toInt().coerceIn(0, 100) else -1
        val mustSend = s.finished || s.failed || pct != lastPct
        if (!mustSend) return
        lastPct = pct
        try {
            nm.notify(NOTI_ID, LiveCapsule.build(this, s))
        } catch (_: Throwable) {
        }
    }
}
