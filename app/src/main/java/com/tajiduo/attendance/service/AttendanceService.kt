package com.tajiduo.attendance.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.tajiduo.attendance.data.AccountStore
import com.tajiduo.attendance.data.SettingsStore
import com.tajiduo.attendance.data.StateStore
import com.tajiduo.attendance.notify.DeviceStatus
import com.tajiduo.attendance.notify.EmailNotifier
import com.tajiduo.attendance.notify.NotificationHelper
import com.tajiduo.attendance.notify.NotifyWebhook
import com.tajiduo.attendance.runner.AttendanceRunner
import com.tajiduo.attendance.runner.RunnerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 前台签到服务（dataSync）：
 * 常驻通知 + WakeLock → 执行签到 → 写状态/历史 → 广播结果 → 发送通知 →
 * 释放资源 → 定时任务完成后自杀了结进程（需求 4）。
 */
class AttendanceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var nightCheckRun = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            return START_NOT_STICKY
        }
        running = true
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        val killAfter = intent?.getBooleanExtra(EXTRA_KILL_AFTER, false) ?: false
        nightCheckRun = intent?.getBooleanExtra(EXTRA_NIGHT_CHECK, false) ?: false
        val checkReport = intent?.getStringExtra(EXTRA_CHECK_REPORT)
        val middayRemind = intent?.getStringExtra(EXTRA_MIDDAY_REMIND)
        val weeklyReport = intent?.getStringExtra(EXTRA_WEEKLY_REPORT)
        Log.i(TAG, "service start: force=$force killAfter=$killAfter checkReport=${checkReport != null}")

        val helper = NotificationHelper(this)
        try {
            helper.ensureChannel()
            ServiceCompat.startForeground(
                this,
                NotificationHelper.ONGOING_ID,
                helper.buildOngoing(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            acquireWakeLock()
        }
        catch (startError: Exception) {
            // 启动阶段失败（前台服务被 ROM 拒绝、通知通道异常、WakeLock 获取失败）：
            // 这属于「签到失败」，必须发邮件提醒，不能静默吞掉。该异常在 scope.launch 之前，
            // execute 的 catch 接不住，所以在这里独立补发失败邮件。
            Log.e(TAG, "service start failed", startError)
            val now = System.currentTimeMillis()
            scope.launch {
                val settings = SettingsStore(this@AttendanceService)
                sendEmailSafe(
                    settings = settings,
                    summary = "签到服务启动失败：${startError.message ?: startError.javaClass.simpleName}",
                    successCount = 0,
                    failedCount = 1,
                    skippedCount = 0,
                    startedAt = now,
                    finishedAt = now,
                )
                releaseWakeLock()
                stopSelf()
                if (killAfter && settings.killAfterRun) {
                    Log.i(TAG, "kill process for power saving")
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
            return START_NOT_STICKY
        }

        scope.launch {
            when {
                checkReport != null -> runCheckReport(checkReport, killAfter)
                middayRemind != null -> runMiddayRemind(middayRemind, killAfter)
                weeklyReport != null -> runWeeklyReport(killAfter)
                else -> execute(force, killAfter)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun execute(force: Boolean, killAfter: Boolean) {
        val settings = SettingsStore(this)
        val runStartedAt = System.currentTimeMillis()
        var summary = "签到未执行"
        var success = false
        try {
            val runner = AttendanceRunner(AccountStore(this), StateStore(this))
            val result = runner.run(
                RunnerOptions(
                    force = force,
                    coinTasks = settings.coinTasks,
                    cloudDuration = settings.cloudDuration,
                    sharePlatform = settings.sharePlatform,
                    maxRetries = settings.maxRetries,
                ),
            )
            summary = result.summary
            success = result.failedCount == 0
            settings.lastRunDate = shanghaiDate()
            Log.i(
                TAG,
                "run finished: success=$success successCount=${result.successCount} " +
                    "failedCount=${result.failedCount} skippedCount=${result.skippedCount}",
            )
            if (settings.notifyEnabled) {
                NotificationHelper(this).notifyResult(summary, success)
            }
            if (!success && nightCheckRun) {
                // 夜间 23:30 补签最终仍有账号失败：发带提示音 + 锁屏可见原因的醒目提醒
                Log.i(TAG, "night check failed, alert with sound")
                NotificationHelper(this).notifyNightFail(summary)
            }
            if (!success && !nightCheckRun) {
                // 早上主签到失败：排一个今天 12:30 的待补签提醒（到点 Receiver 再核对状态）
                com.tajiduo.attendance.schedule.AlarmScheduler.scheduleMiddayReminder(this)
                Log.i(TAG, "midday remind scheduled due to morning failure")
            }
            sendWebhook(settings, summary)
            sendEmailSafe(
                settings = settings,
                summary = summary,
                successCount = result.successCount,
                failedCount = result.failedCount,
                skippedCount = result.skippedCount,
                startedAt = result.startedAt,
                finishedAt = result.finishedAt,
            )
        }
        catch (error: Exception) {
            summary = "签到执行失败：${error.message}"
            success = false
            Log.e(TAG, "run failed", error)
            try {
                NotificationHelper(this).notifyError(summary)
            }
            catch (_: Exception) {
                // 忽略通知失败
            }
            if (nightCheckRun) {
                // 夜间补签流程整体异常：同样发提示音提醒
                NotificationHelper(this).notifyNightFail(summary)
            }
            sendEmailSafe(
                settings = settings,
                summary = summary,
                successCount = 0,
                failedCount = 1,
                skippedCount = 0,
                startedAt = runStartedAt,
                finishedAt = System.currentTimeMillis(),
            )
        }
        finally {
            broadcastFinished(summary, success)
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (killAfter && settings.killAfterRun) {
                Log.i(TAG, "kill process for power saving")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * 夜间检查（全部已签）报告：仅发送结果通知与邮件，
     * 不执行签到、不改写状态与历史，结束流程与常规运行一致（释放资源 + 可选自杀）。
     */
    private fun runCheckReport(summary: String, killAfter: Boolean) {
        val settings = SettingsStore(this)
        try {
            Log.i(TAG, "check report: send notification=${settings.notifyEnabled}")
            if (settings.notifyEnabled) {
                NotificationHelper(this).notifyFailsafeResult(summary)
            }
            sendCheckEmailSafe(settings, summary)
        }
        catch (error: Exception) {
            Log.e(TAG, "check report failed", error)
        }
        finally {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (killAfter && settings.killAfterRun) {
                Log.i(TAG, "kill process for power saving")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * 中午 12:30 待补签提醒：早上失败后排的一次性闹钟，到点仍有未签才触发。
     * 只发通知与邮件提醒，不自动补签（留给 23:30 兜底）。
     */
    private fun runMiddayRemind(summary: String, killAfter: Boolean) {
        val settings = SettingsStore(this)
        try {
            Log.i(TAG, "midday remind: send notification=${settings.notifyEnabled}")
            if (settings.notifyEnabled) {
                NotificationHelper(this).notifyMiddayRemind(summary)
            }
            sendMiddayEmailSafe(settings, summary)
        }
        catch (error: Exception) {
            Log.e(TAG, "midday remind failed", error)
        }
        finally {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (killAfter && settings.killAfterRun) {
                Log.i(TAG, "kill process for power saving")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * 每周日 21:00 周报邮件：读 runs.json 统计本周表现与连续签到天数。
     */
    private fun runWeeklyReport(killAfter: Boolean) {
        val settings = SettingsStore(this)
        try {
            val state = StateStore(this)
            val report = com.tajiduo.attendance.notify.WeeklyReportBuilder.build(state)
            Log.i(TAG, "weekly report generated, ${report.length} chars")
            sendWeeklyEmailSafe(settings, report)
        }
        catch (error: Exception) {
            Log.e(TAG, "weekly report failed", error)
        }
        finally {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (killAfter && settings.killAfterRun) {
                Log.i(TAG, "kill process for power saving")
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun sendWebhook(settings: SettingsStore, summary: String) {
        val urls = settings.notificationUrls
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (urls.isEmpty()) return
        try {
            NotifyWebhook.send(urls, "塔吉多每日签到", summary)
        }
        catch (_: Exception) {
            // 可选功能，失败不影响主流程
        }
    }

    /**
     * 邮件通知（QQ 邮箱）：同步发送，位于杀进程之前完成；
     * 未配置时静默跳过，失败仅记录日志，不影响签到主流程与日志脱敏。
     */
    private fun sendEmailSafe(
        settings: SettingsStore,
        summary: String,
        successCount: Int,
        failedCount: Int,
        skippedCount: Int,
        startedAt: Long,
        finishedAt: Long,
    ) {
        if (!settings.emailConfigured) return
        val recipient = settings.emailRecipient.ifBlank { settings.emailSender }
        val status = when {
            failedCount > 0 -> "失败"
            successCount > 0 -> "成功"
            else -> "跳过"
        }
        val subject = "塔吉多签到 | $status | ${formatShortTime(startedAt)} | 电量：${batteryPercentText()}"
        val content = buildString {
            appendLine("塔吉多每日签到结果（自动发送）")
            appendLine()
            appendLine("签到开始：${formatEmailTime(startedAt, withSeconds = true)}")
            appendLine("签到结束：${formatEmailTime(finishedAt, withSeconds = true)}")
            appendLine("结果状态：$status（成功 $successCount / 失败 $failedCount / 跳过 $skippedCount）")
            appendLine(DeviceStatus.batteryLine(this@AttendanceService))
            appendLine()
            appendLine("——————————")
            appendLine(summary)
            appendLine("——————————")
            append("本邮件由「塔吉多签到」App 自动发送，请勿直接回复。")
        }
        try {
            val errors = EmailNotifier.send(settings.emailSender, settings.emailAuthCode, recipient, subject, content)
            if (errors.isEmpty()) {
                Log.i(TAG, "email sent")
                StateStore(this).recordEmailStatus("sign", shanghaiDate(), true, "")
            } else {
                val detail = errors.joinToString("; ")
                Log.e(TAG, "email send failed: $detail")
                StateStore(this).recordEmailStatus("sign", shanghaiDate(), false, detail)
            }
        }
        catch (error: Exception) {
            Log.e(TAG, "email send failed: ${error.message}")
            StateStore(this).recordEmailStatus("sign", shanghaiDate(), false, error.message ?: "unknown")
        }
    }

    /**
     * 夜间检查报告邮件（全部已签）：与签到结果邮件同通道（QQ 邮箱 SMTP），
     * 未配置时静默跳过，失败仅记录日志。
     */
    private fun sendCheckEmailSafe(settings: SettingsStore, summary: String) {
        if (!settings.emailConfigured) return
        val recipient = settings.emailRecipient.ifBlank { settings.emailSender }
        val now = System.currentTimeMillis()
        val subject = "塔吉多签到 | 夜间检查·全部已签 | ${formatShortTime(now)} | 电量：${batteryPercentText()}"
        val content = buildString {
            appendLine("塔吉多夜间签到检查（自动发送）")
            appendLine()
            appendLine("检查时间：${formatEmailTime(now, withSeconds = true)}")
            appendLine("检查结果：全部账号今日已签到，无需补签")
            appendLine(DeviceStatus.batteryLine(this@AttendanceService))
            appendLine()
            appendLine("——————————")
            appendLine(summary)
            appendLine("——————————")
            append("本邮件由「塔吉多签到」App 自动发送，请勿直接回复。")
        }
        try {
            val errors = EmailNotifier.send(settings.emailSender, settings.emailAuthCode, recipient, subject, content)
            if (errors.isEmpty()) {
                Log.i(TAG, "check email sent")
                StateStore(this).recordEmailStatus("check", shanghaiDate(), true, "")
            } else {
                val detail = errors.joinToString("; ")
                Log.e(TAG, "check email send failed: $detail")
                StateStore(this).recordEmailStatus("check", shanghaiDate(), false, detail)
            }
        }
        catch (error: Exception) {
            Log.e(TAG, "check email send failed: ${error.message}")
            StateStore(this).recordEmailStatus("check", shanghaiDate(), false, error.message ?: "unknown")
        }
    }

    /** 中午待补签提醒邮件：早上失败后 12:30 仍未签时发送，提醒用户手动补签。 */
    private fun sendMiddayEmailSafe(settings: SettingsStore, summary: String) {
        if (!settings.emailConfigured) return
        val recipient = settings.emailRecipient.ifBlank { settings.emailSender }
        val now = System.currentTimeMillis()
        val subject = "塔吉多签到 | 待补签 | ${formatShortTime(now)} | 电量：${batteryPercentText()}"
        val content = buildString {
            appendLine("塔吉多待补签提醒（自动发送）")
            appendLine()
            appendLine("提醒时间：${formatEmailTime(now, withSeconds = true)}")
            appendLine("说明：今日早上签到后，仍有账号未完成签到。")
            appendLine(DeviceStatus.batteryLine(this@AttendanceService))
            appendLine()
            appendLine("——————————")
            appendLine(summary)
            appendLine("——————————")
            append("本邮件由「塔吉多签到」App 自动发送，请勿直接回复。")
        }
        try {
            val errors = EmailNotifier.send(settings.emailSender, settings.emailAuthCode, recipient, subject, content)
            if (errors.isEmpty()) {
                Log.i(TAG, "midday remind email sent")
            } else {
                Log.e(TAG, "midday remind email send failed: ${errors.joinToString("; ")}")
            }
        }
        catch (error: Exception) {
            Log.e(TAG, "midday remind email send failed: ${error.message}")
        }
    }

    /** 每周周报邮件：标题「塔吉多签到 | 周报 | MM-dd HH:mm | 电量：xx%」。 */
    private fun sendWeeklyEmailSafe(settings: SettingsStore, report: String) {
        if (!settings.emailConfigured) return
        val recipient = settings.emailRecipient.ifBlank { settings.emailSender }
        val now = System.currentTimeMillis()
        val subject = "塔吉多签到 | 周报 | ${formatShortTime(now)} | 电量：${batteryPercentText()}"
        val content = buildString {
            appendLine("塔吉多签到周报（自动发送）")
            appendLine()
            appendLine(DeviceStatus.batteryLine(this@AttendanceService))
            appendLine()
            appendLine("——————————")
            appendLine(report)
            appendLine("——————————")
            append("本邮件由「塔吉多签到」App 自动发送，请勿直接回复。")
        }
        try {
            val errors = EmailNotifier.send(settings.emailSender, settings.emailAuthCode, recipient, subject, content)
            if (errors.isEmpty()) {
                Log.i(TAG, "weekly report email sent")
            } else {
                Log.e(TAG, "weekly report email send failed: ${errors.joinToString("; ")}")
            }
        }
        catch (error: Exception) {
            Log.e(TAG, "weekly report email send failed: ${error.message}")
        }
    }

    private fun formatEmailTime(timestamp: Long, withSeconds: Boolean = false): String =
        SimpleDateFormat(if (withSeconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date(timestamp))

    /** 邮件标题用的短时间（MM-dd HH:mm，上海时区），避免标题过长在邮箱列表被截断。 */
    private fun formatShortTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date(timestamp))

    /** 邮件标题用的电量百分比文本，读不到时显示「未知」。 */
    private fun batteryPercentText(): String {
        val percent = DeviceStatus.battery(this).percent
        return if (percent >= 0) "$percent%" else "未知"
    }

    private fun broadcastFinished(summary: String, success: Boolean) {
        val intent = Intent(ACTION_RUN_FINISHED).apply {
            setPackage(packageName)
            putExtra(EXTRA_SUMMARY, summary)
            putExtra(EXTRA_SUCCESS, success)
        }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TajiduoAttendance:run").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    private fun shanghaiDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date())

    companion object {
        private const val TAG = "TajiduoAttendance"
        const val EXTRA_FORCE = "force"
        const val EXTRA_KILL_AFTER = "kill_after"

        /** 非空时进入"夜间检查报告"模式：仅发送通知与邮件，不执行签到。 */
        const val EXTRA_CHECK_REPORT = "check_report"
        /** 非空时进入"中午待补签提醒"模式：仅发送提醒通知与邮件，不执行签到。 */
        const val EXTRA_MIDDAY_REMIND = "midday_remind"
        /** 非空时进入"每周周报"模式：仅发送周报邮件，不执行签到。 */
        const val EXTRA_WEEKLY_REPORT = "weekly_report"
        /** true 表示本次由 23:30 夜间补签触发；最终失败时发带提示音的锁屏提醒。 */
        const val EXTRA_NIGHT_CHECK = "night_check"
        const val ACTION_RUN_FINISHED = "com.tajiduo.attendance.action.RUN_FINISHED"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_SUCCESS = "success"

        fun buildIntent(context: Context, force: Boolean, killAfter: Boolean, nightCheck: Boolean = false): Intent =
            Intent(context, AttendanceService::class.java).apply {
                putExtra(EXTRA_FORCE, force)
                putExtra(EXTRA_KILL_AFTER, killAfter)
                putExtra(EXTRA_NIGHT_CHECK, nightCheck)
            }

        fun buildCheckReportIntent(context: Context, summary: String): Intent =
            Intent(context, AttendanceService::class.java).apply {
                putExtra(EXTRA_KILL_AFTER, true)
                putExtra(EXTRA_CHECK_REPORT, summary)
            }

        fun buildMiddayRemindIntent(context: Context, summary: String): Intent =
            Intent(context, AttendanceService::class.java).apply {
                putExtra(EXTRA_KILL_AFTER, true)
                putExtra(EXTRA_MIDDAY_REMIND, summary)
            }

        fun buildWeeklyReportIntent(context: Context): Intent =
            Intent(context, AttendanceService::class.java).apply {
                putExtra(EXTRA_KILL_AFTER, true)
                putExtra(EXTRA_WEEKLY_REPORT, "1")
            }
    }
}