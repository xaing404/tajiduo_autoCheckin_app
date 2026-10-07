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
        const val ONGOING_ID = 1001
        const val RESULT_ID = 1002
    }
}