package com.tajiduo.attendance.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tajiduo.attendance.MainActivity
import com.tajiduo.attendance.R
import com.tajiduo.attendance.permission.PermissionHelper

/** 系统通知：常驻进行中通知 + 结果通知。 */
class NotificationHelper(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "签到状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "塔吉多自动签到状态通知"
        }
        manager.createNotificationChannel(channel)
        // 夜间失败提醒渠道：仅 23:30 补签最终失败时使用。
        // HIGH = 横幅短暂弹出 + 默认提示音 + 短震动 + 锁屏可见内容；但不设置全屏 Intent，
        // 因此只是“叮一下提醒你看一眼”，不是闹钟那种强制全屏打断。
        val nightChannel = NotificationChannel(
            CHANNEL_NIGHT_FAIL,
            "夜间失败提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "夜间补签确认失败时的提示音与锁屏提醒"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150, 100, 150)
        }
        manager.createNotificationChannel(nightChannel)
    }

    /** 构建进行中通知（前台服务必须无条件构建，不依赖通知权限）。 */
    fun buildOngoing(text: String = "正在签到…"): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(contentIntent())
            .build()

    /** 进行中（前台服务常驻通知）。 */
    fun notifyOngoing(text: String = "正在签到…") {
        if (!PermissionHelper.hasNotificationPermission(context)) return
        notifyCompat(ONGOING_ID, buildOngoing(text))
    }

    /** 结果通知（成功/失败 + 完整摘要）。 */
    fun notifyResult(summary: String, success: Boolean) {
        if (!PermissionHelper.hasNotificationPermission(context)) return
        val title = if (success) "塔吉多签到完成" else "塔吉多签到失败"
        val preview = summary.lineSequence().firstOrNull { it.isNotBlank() } ?: summary
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        notifyCompat(RESULT_ID, notification)
    }

    fun notifyError(message: String) {
        notifyResult(message, success = false)
    }

    /**
     * 夜间补签最终失败提醒：用独立的 HIGH 重要性渠道，带提示音 + 短震动 + 锁屏可见原因。
     * 仅在 23:30 夜间检查确认今天仍有账号未签成功时调用，与白天常规结果通知区分。
     */
    fun notifyNightFail(summary: String) {
        if (!PermissionHelper.hasNotificationPermission(context)) return
        val preview = summary.lineSequence().firstOrNull { it.isNotBlank() } ?: summary
        val notification = NotificationCompat.Builder(context, CHANNEL_NIGHT_FAIL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("塔吉多签到失败（夜间补签未成功）")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent())
            .build()
        notifyCompat(NIGHT_FAIL_ID, notification)
    }

    /** 夜间检查结果通知（全部账号已签到，无需补签）。 */
    fun notifyFailsafeResult(summary: String) {
        if (!PermissionHelper.hasNotificationPermission(context)) return
        val preview = summary.lineSequence().firstOrNull { it.isNotBlank() } ?: summary
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("塔吉多夜间检查完成")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        notifyCompat(RESULT_ID, notification)
    }

    /** 中午 12:30 待补签提醒通知。 */
    fun notifyMiddayRemind(summary: String) {
        if (!PermissionHelper.hasNotificationPermission(context)) return
        val preview = summary.lineSequence().firstOrNull { it.isNotBlank() } ?: summary
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("塔吉多待补签：今日仍有账号未签")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        notifyCompat(RESULT_ID, notification)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifyCompat(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
        catch (_: SecurityException) {
            // 未授予通知权限时静默忽略
        }
    }

    companion object {
        const val CHANNEL_ID = "attendance_status"
        const val CHANNEL_NIGHT_FAIL = "night_fail_alert"
        const val ONGOING_ID = 1001
        const val RESULT_ID = 1002
        const val NIGHT_FAIL_ID = 1003
    }
}