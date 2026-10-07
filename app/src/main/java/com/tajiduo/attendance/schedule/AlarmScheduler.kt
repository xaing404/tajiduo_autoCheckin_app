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
    const val ACTION_ALARM = "com.tajiduo.attendance.action.ALARM"
    const val ACTION_FAILSAFE = "com.tajiduo.attendance.action.FAILSAFE"

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
        Log.i(TAG, "alarm cancelled")
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