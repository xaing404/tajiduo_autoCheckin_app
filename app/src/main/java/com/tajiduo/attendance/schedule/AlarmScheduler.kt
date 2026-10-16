package com.tajiduo.attendance.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tajiduo.attendance.MainActivity
import com.tajiduo.attendance.data.SettingsStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日定时（AlarmManager）。
 *
 * 采用 setAlarmClock()：这是系统唯一保证「准点投递」的接口，不会被 Doze 或厂商电源策略
 * 改写投放窗口（实测 setExactAndAllowWhileIdle 在 ColorOS 上会被推迟数分钟，窗口上限约 1 小时）。
 * 代价是状态栏会常驻一个闹钟图标，点击可回到本应用。
 */
object AlarmScheduler {

    private const val TAG = "TajiduoAttendance"
    private const val REQUEST_CODE = 2001
    private const val REQUEST_CODE_SHOW = 2002
    private const val REQUEST_CODE_FAILSAFE = 2003
    private const val REQUEST_CODE_DAY_RESET = 2004
    private const val REQUEST_CODE_MIDDAY = 2005
    private const val REQUEST_CODE_WEEKLY = 2006
    const val ACTION_ALARM = "com.tajiduo.attendance.action.ALARM"
    const val ACTION_FAILSAFE = "com.tajiduo.attendance.action.FAILSAFE"

    /** 跨日重置：每天 00:00 触发，用于把首页「今日签到状态」刷新为未签到。 */
    const val ACTION_DAY_RESET = "com.tajiduo.attendance.action.DAY_RESET"
    private const val DAY_RESET_HOUR = 0
    private const val DAY_RESET_MINUTE = 0

    /** 中午补签提醒：早上签到失败后一次性设置，今天 12:30 触发。 */
    const val ACTION_MIDDAY_REMIND = "com.tajiduo.attendance.action.MIDDAY_REMIND"
    private const val MIDDAY_REMIND_HOUR = 12
    private const val MIDDAY_REMIND_MINUTE = 30

    /** 每周签到周报：每周日 21:00 触发。 */
    const val ACTION_WEEKLY_REPORT = "com.tajiduo.attendance.action.WEEKLY_REPORT"
    private const val WEEKLY_HOUR = 21
    private const val WEEKLY_MINUTE = 0

    fun scheduleNext(context: Context, hour: Int, minute: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerMillis(hour, minute)
        val pending = pendingIntent(context)
        try {
            val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent(context))
            manager.setAlarmClock(info, pending)
            Log.i(TAG, "alarm scheduled: ${formatNextTrigger(hour, minute)}")
        }
        catch (error: Exception) {
            // 个别 ROM 可能限制 setAlarmClock，降级保证定时仍然生效
            Log.w(TAG, "setAlarmClock failed, fallback to setExactAndAllowWhileIdle", error)
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun scheduleFromSettings(context: Context) {
        val settings = SettingsStore(context)
        scheduleNext(context, settings.hour, settings.minute)
        scheduleFailsafe(context, settings.failsafeHour, settings.failsafeMinute)
        scheduleDayReset(context)
        scheduleWeeklyReport(context)
    }

    /**
     * 跨日重置闹钟（固定每天 00:00）：日期翻篇后把首页「今日状态」复位为未签到。
     * 只做轻量处理（广播通知前台界面刷新 + 重排下一次），不启动签到服务、不发通知邮件，
     * 即使 App 进程未在前台，打开时也会按当天日期重新判定状态，此闹钟仅负责实时刷新。
     */
    fun scheduleDayReset(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerMillis(DAY_RESET_HOUR, DAY_RESET_MINUTE)
        val pending = dayResetPendingIntent(context)
        try {
            val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent(context))
            manager.setAlarmClock(info, pending)
            Log.i(TAG, "day reset scheduled: ${formatNextTrigger(DAY_RESET_HOUR, DAY_RESET_MINUTE)}")
        }
        catch (error: Exception) {
            Log.w(TAG, "setAlarmClock failed for day reset, fallback to setExactAndAllowWhileIdle", error)
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /**
     * 夜间补签检查（默认 23:30）：检查当天是否签到成功，未成功则重新触发签到。
     * 同样以 setAlarmClock() 保证准点，使用独立 PendingIntent 与主闹钟并存。
     */
    fun scheduleFailsafe(context: Context, hour: Int, minute: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerMillis(hour, minute)
        val pending = failsafePendingIntent(context)
        try {
            val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent(context))
            manager.setAlarmClock(info, pending)
            Log.i(TAG, "failsafe check scheduled: ${formatNextTrigger(hour, minute)}")
        }
        catch (error: Exception) {
            Log.w(TAG, "setAlarmClock failed for failsafe, fallback to setExactAndAllowWhileIdle", error)
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context))
        manager.cancel(failsafePendingIntent(context))
        manager.cancel(dayResetPendingIntent(context))
        manager.cancel(middayRemindPendingIntent(context))
        manager.cancel(weeklyReportPendingIntent(context))
        Log.i(TAG, "alarm cancelled")
    }

    /**
     * 中午补签提醒（一次性，今天 12:30）：仅在早上主签到失败时调用。
     * 到点后由 Receiver 再核对一次当天状态，仍未签才发提醒邮件；
     * 若用户已手动签到或后续补签成功，则静默。一次性闹钟，触发即失效。
     */
    fun scheduleMiddayReminder(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = middayTriggerMillis()
        if (triggerAt <= System.currentTimeMillis()) {
            Log.i(TAG, "midday remind: already past, skip")
            return
        }
        val pending = middayRemindPendingIntent(context)
        try {
            val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent(context))
            manager.setAlarmClock(info, pending)
            Log.i(TAG, "midday remind scheduled: ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(triggerAt))}")
        }
        catch (error: Exception) {
            Log.w(TAG, "setAlarmClock failed for midday remind, fallback", error)
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /** 今天 12:30 的时间戳（已过则返回 0，调用方判断跳过）。 */
    private fun middayTriggerMillis(): Long {
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, MIDDAY_REMIND_HOUR)
            set(Calendar.MINUTE, MIDDAY_REMIND_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return if (target.timeInMillis <= System.currentTimeMillis()) 0 else target.timeInMillis
    }

    /** 每周日 21:00 周报闹钟。 */
    fun scheduleWeeklyReport(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = weeklyTriggerMillis()
        val pending = weeklyReportPendingIntent(context)
        try {
            val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent(context))
            manager.setAlarmClock(info, pending)
            Log.i(TAG, "weekly report scheduled: ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(triggerAt))}")
        }
        catch (error: Exception) {
            Log.w(TAG, "setAlarmClock failed for weekly report, fallback", error)
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /** 下一个周日 21:00 的时间戳。 */
    private fun weeklyTriggerMillis(): Long {
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, WEEKLY_HOUR)
            set(Calendar.MINUTE, WEEKLY_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Calendar.DAY_OF_WEEK: 周日=1, 周一=2 ... 周六=7
        val daysUntilSunday = (8 - target.get(Calendar.DAY_OF_WEEK)) % 7
        target.add(Calendar.DAY_OF_YEAR, daysUntilSunday)
        if (target.timeInMillis <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 7)
        }
        return target.timeInMillis
    }

    /** 下一次触发时间戳：今天未到则今天，否则明天。 */
    fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun formatNextTrigger(hour: Int, minute: Int): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(nextTriggerMillis(hour, minute)))

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AttendanceAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun failsafePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AttendanceAlarmReceiver::class.java).apply {
            action = ACTION_FAILSAFE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_FAILSAFE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dayResetPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AttendanceAlarmReceiver::class.java).apply {
            action = ACTION_DAY_RESET
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DAY_RESET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun middayRemindPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AttendanceAlarmReceiver::class.java).apply {
            action = ACTION_MIDDAY_REMIND
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_MIDDAY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun weeklyReportPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AttendanceAlarmReceiver::class.java).apply {
            action = ACTION_WEEKLY_REPORT
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_WEEKLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 点击状态栏闹钟图标时打开应用。 */
    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_SHOW,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}