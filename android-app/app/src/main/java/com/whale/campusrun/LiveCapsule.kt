/*
 * 流体云 / 实时活动胶囊的构造器。
 *
 * 为什么单独拆出来：
 *   前台服务（跑步时）和设置页（发一条测试胶囊）都要构造同一条通知，
 *   逻辑放两份就会漂。这里只有「怎么把 RunState 变成一条胶囊」这一件事。
 *
 * ColorOS 16 的流体云完整接入了原生 Android 16 Live Updates，
 * 所以**不依赖任何 OPPO 私有 SDK**，按谷歌规范写就能被系统收成胶囊：
 *   ① 有标题            setContentTitle
 *   ② 常驻              setOngoing(true)
 *   ③ ProgressStyle     setStyle(ProgressStyle)
 *   ④ 申请提升          setRequestPromotedOngoing(true)
 *   ⑤ 清单声明          POST_PROMOTED_NOTIFICATIONS
 * 反面约束：不能是分组摘要、不能 setColorized、不能用自定义 RemoteViews、
 * 通道重要性不能是 IMPORTANCE_MIN —— 踩中任一条就退回普通通知。
 */
package com.whale.campusrun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

object LiveCapsule {

    const val CHANNEL = "campusrun.progress"

    /** 设置页「测试胶囊」用的固定 id，和跑步那条分开，互不覆盖。 */
    const val PREVIEW_ID = 1002

    /** 测试胶囊活多久后自己消失，避免留一条假的「进行中」在通知栏里。 */
    const val PREVIEW_MS = 8000L

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_MIN 会直接让通知失去「可提升为实时活动」的资格，
        // 所以这里最低只到 LOW —— 安静但仍有资格进胶囊。
        val ch = NotificationChannel(
            CHANNEL, "打卡进度", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "校园跑打卡时的实时进度（流体云胶囊）"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 当前这台机器能不能把通知提升成实时活动 / 流体云胶囊。
     *
     * API 36 以下根本没有这套 API；API 36+ 还要用户没关掉「实时通知」开关。
     * 设置页据此显示状态，而不是让用户自己去猜为什么没胶囊。
     */
    fun supported(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.canPostPromotedNotifications()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 一条胶囊通知。
     *
     * 文案分两层：胶囊上那条窄栏只放 setShortCriticalText 的短句
     * （"1.24 / 2.00 km"），展开后的实时活动里才是标题 + 副标题 + 进度条。
     * 旧版在这里铺三行长文案，胶囊根本塞不下。
     */
    fun build(
        ctx: Context,
        s: RunState,
        timeoutMs: Long = 0L,
        preview: Boolean = false
    ): Notification {
        ensureChannel(ctx)

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pct = percentOf(s)
        val doneKm = s.metersDone / 1000.0
        val totalKm = s.metersTotal / 1000.0
        val eta = if (s.etaSec >= 0) RunEngine.fmtDuration(s.etaSec) else "--:--"

        // 胶囊窄栏只留两段：左边剩余时间、右边预计完成时刻。
        // 旧版放的是「1.24 / 2.00 km」，里程在展开的进度条上已经有了，
        // 收起来的时候用户真正想知道的是「还要多久」和「几点结束」。
        val etaClock = if (s.etaSec >= 0) clockAfter(s.etaSec) else "--:--"
        val chip = if (s.etaSec >= 0) "剩 $eta　·　$etaClock" else s.phase

        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle(titleOf(s, pct, preview))
            .setContentText("剩余 $eta　·　预计 $etaClock 完成")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // ④ 主动申请提升为实时活动 → 流体云据此把它收成胶囊
            .setRequestPromotedOngoing(true)
            // 胶囊上那一行短文案
            .setShortCriticalText(chip)

        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        b.setStyle(styleOf(ctx, s, pct))
        return b.build()
    }

    /**
     * 设置页的「测试胶囊」：一条 8 秒后自消的假进度，不挂前台服务。
     *
     * 标题明确写「预览」，免得用户以为真的开跑了一轮。
     */
    fun preview(ctx: Context): Notification {
        val fake = RunState(
            phase = "预览",
            metersDone = 1240.0,
            metersTotal = 2000.0,
            etaSec = 260,
            paceSecKm = 345.0
        )
        return build(ctx, fake, PREVIEW_MS, preview = true)
    }

    /**
     * 一条人类可读的诊断结论，写进运行日志。
     *
     * 流体云「没出现在胶囊里」有四五种原因，光看现象分不出来；
     * 把 canPostPromotedNotifications 和 hasPromotableCharacteristics 分开报，
     * 才能一眼看出该去改系统设置还是该改代码。
     */
    fun diagnose(ctx: Context, n: Notification): String {
        if (Build.VERSION.SDK_INT < 36) return "系统不支持实时活动（需 Android 16+）"
        val canPost = supported(ctx)
        val promotable = try {
            n.hasPromotableCharacteristics()
        } catch (_: Throwable) {
            false
        }
        return when {
            canPost && promotable -> "流体云可用，进度会收成胶囊"
            canPost -> "系统允许，但这条通知不满足提升条件"
            promotable -> "通知本身合格，系统侧实时通知被关闭"
            else -> "实时通知不可用（权限或系统开关）"
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 再过 sec 秒是几点几分 —— 胶囊右侧那个「预计完成时刻」。 */
    private fun clockAfter(sec: Int): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(System.currentTimeMillis() + sec * 1000L))

    private fun percentOf(s: RunState): Int =
        if (s.metersTotal > 0)
            (s.metersDone / s.metersTotal * 100).toInt().coerceIn(0, 100) else 0

    private fun titleOf(s: RunState, pct: Int, preview: Boolean): String = when {
        preview -> "校园跑（预览）　$pct%"
        s.failed -> "打卡失败"
        s.finished -> "打卡完成　$pct%"
        s.metersTotal > 0 -> "校园跑进行中　$pct%"
        else -> "校园跑　${s.phase}"
    }

    /**
     * 进度条样式。
     *
     * 四段 + 三个里程碑点，颜色取自 App 主题（@color/live_*），
     * 在流体云里就是一条和 App 同色系的细进度条。
     * 还没拉到目标里程时用「不确定」态，免得显示一条 0% 的死条。
     */
    private fun styleOf(ctx: Context, s: RunState, pct: Int): NotificationCompat.ProgressStyle {
        val style = NotificationCompat.ProgressStyle()
        if (s.metersTotal > 0) {
            style.setProgress(pct)
            val segColor = ContextCompat.getColor(ctx, R.color.live_segment)
            val pointColor = ContextCompat.getColor(ctx, R.color.live_track)
            style.setProgressSegments(
                (1..4).map { NotificationCompat.ProgressStyle.Segment(25).setColor(segColor) }
            )
            style.setProgressPoints(
                listOf(25, 50, 75).map {
                    NotificationCompat.ProgressStyle.Point(it).setColor(pointColor)
                }
            )
        } else {
            style.setProgressIndeterminate(true)
        }
        style.setProgressTrackerIcon(IconCompat.createWithResource(ctx, R.drawable.ic_run))
        return style
    }
}
