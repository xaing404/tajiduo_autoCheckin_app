package com.tajiduo.attendance.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.tajiduo.attendance.data.AccountStore
import com.tajiduo.attendance.data.StateStore
import com.tajiduo.attendance.service.AttendanceService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 闹钟触发：先重排下一次，再启动前台服务执行签到（force=false、完成后自杀）。 */
class AttendanceAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "alarm fired at ${System.currentTimeMillis()}: ${intent.action}")
        AlarmScheduler.scheduleFromSettings(context)
        if (intent.action == AlarmScheduler.ACTION_DAY_RESET) {
            // 跨日重置：仅通知前台界面把「今日状态」刷新为未签到，不启动签到服务、不发通知邮件。
            // 若 App 未在前台，广播无人接收也无副作用，下次打开时会按当天日期重新判定。
            val tick = Intent(ACTION_DAY_RESET_TICK).setPackage(context.packageName)
            context.sendBroadcast(tick)
            return
        }
        if (intent.action == AlarmScheduler.ACTION_FAILSAFE) {
            checkAndRetrigger(context)
            return
        }
        if (intent.action == AlarmScheduler.ACTION_MIDDAY_REMIND) {
            checkAndMiddayRemind(context)
            return
        }
        if (intent.action == AlarmScheduler.ACTION_WEEKLY_REPORT) {
            ContextCompat.startForegroundService(
                context,
                com.tajiduo.attendance.service.AttendanceService.buildWeeklyReportIntent(context),
            )
            return
        }
        ContextCompat.startForegroundService(
            context,
            AttendanceService.buildIntent(context, force = false, killAfter = true),
        )
    }

    /**
     * 夜间补签检查：逐个账号核对当天的去重键（isSignedToday），
     * 有任一账号当天未签到成功就重新触发一次完整签到（已签账号会被运行器自动跳过）；
     * 全部已签则发送"检查正常"的通知与邮件（不执行签到、不改写状态）。
     */
    private fun checkAndRetrigger(context: Context) {
        // 未配置账号（文件缺失、脱敏模板占位、解析失败）时视为无需检查，静默跳过
        val accounts = try {
            AccountStore(context).readAccounts()
        }
        catch (error: Exception) {
            Log.i(TAG, "failsafe: accounts not configured (${error.javaClass.simpleName}), skip")
            return
        }
        if (accounts.isEmpty()) {
            Log.i(TAG, "failsafe: no accounts configured, skip")
            return
        }
        try {
            val state = StateStore(context)
            val date = shanghaiDate()
            val unsigned = accounts.filter { !state.isSignedToday(it.id, date) }
            if (unsigned.isEmpty()) {
                Log.i(TAG, "failsafe: all accounts signed on $date, send check report")
                val summary = buildString {
                    appendLine("夜间检查：${accounts.size}/${accounts.size} 账号今日已签到，无需补签。")
                    appendLine()
                    accounts.forEach { account ->
                        appendLine("- ${account.name}（${account.id}）：已签到")
                    }
                }
                ContextCompat.startForegroundService(
                    context,
                    AttendanceService.buildCheckReportIntent(context, summary),
                )
                return
            }
            Log.w(TAG, "failsafe: ${unsigned.size}/${accounts.size} account(s) unsigned on $date, retrigger sign")
            ContextCompat.startForegroundService(
                context,
                AttendanceService.buildIntent(context, force = false, killAfter = true, nightCheck = true),
            )
        }
        catch (error: Exception) {
            // 检查失败仅记录日志，不影响主流程
            Log.e(TAG, "failsafe check failed", error)
        }
    }

    /**
     * 中午 12:30 待补签提醒（一次性，仅早上失败后排过）：
     * 再次核对当天状态，仍有账号未签才发一封「待补签」邮件+通知；
     * 已签则静默（说明用户手动补了或后续成功）。不自动重跑签到，留给 23:30 兜底。
     */
    private fun checkAndMiddayRemind(context: Context) {
        val accounts = try {
            AccountStore(context).readAccounts()
        }
        catch (error: Exception) {
            Log.i(TAG, "midday remind: accounts not configured, skip")
            return
        }
        if (accounts.isEmpty()) {
            Log.i(TAG, "midday remind: no accounts, skip")
            return
        }
        try {
            val state = StateStore(context)
            val date = shanghaiDate()
            val unsigned = accounts.filter { !state.isSignedToday(it.id, date) }
            if (unsigned.isEmpty()) {
                Log.i(TAG, "midday remind: all signed now, stay silent")
                return
            }
            Log.i(TAG, "midday remind: ${unsigned.size}/${accounts.size} still unsigned, send reminder")
            val summary = buildString {
                appendLine("中午补签提醒：${unsigned.size}/${accounts.size} 个账号今日尚未签到成功。")
                appendLine()
                unsigned.forEach { account ->
                    appendLine("- ${account.name}（${account.id}）：未签到")
                }
                appendLine()
                appendLine("如已手动签到可忽略；今晚 23:30 会自动补签。")
            }
            ContextCompat.startForegroundService(
                context,
                AttendanceService.buildMiddayRemindIntent(context, summary),
            )
        }
        catch (error: Exception) {
            Log.e(TAG, "midday remind check failed", error)
        }
    }

    /** 当天日期（Asia/Shanghai，格式与运行器写入去重键时一致）。 */
    private fun shanghaiDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date())

    companion object {
        private const val TAG = "TajiduoAttendance"

        /** 跨日重置内部广播：仅在 App 前台时由 MainActivity 动态注册接收并刷新界面。 */
        const val ACTION_DAY_RESET_TICK = "com.tajiduo.attendance.action.DAY_RESET_TICK"
    }
}