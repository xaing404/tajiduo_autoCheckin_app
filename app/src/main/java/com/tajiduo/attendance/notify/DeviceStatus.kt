package com.tajiduo.attendance.notify

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 设备当前状态采集（用于签到结果通知 / 邮件正文）。
 *
 * 电量百分比与充电状态直接读系统粘性广播 [Intent.ACTION_BATTERY_CHANGED]：
 * 这正是 `dumpsys battery` 的数据源，充电状态在各 ROM（尤其 ColorOS）上都最准。
 * 早期版本用 [BatteryManager.isCharging] 读取，在插着 USB 充电时仍会误报「未充电」
 * （isCharging 内部读电池统计缓存、存在陈旧值），故改用粘性广播一次性取 level/scale/status。
 * 全程无需任何运行时权限。
 */
object DeviceStatus {

    /** 电量快照：percent 为 0~100，无法获取时为 -1。 */
    data class BatteryInfo(val percent: Int, val charging: Boolean, val full: Boolean)

    fun battery(context: Context): BatteryInfo {
        // 传 null receiver 仅用于读取粘性广播的当前值，不会真的注册接收器（系统广播，无需导出 flag）。
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky == null) {
            return BatteryInfo(percent = -1, charging = false, full = false)
        }

        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1

        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(percent = percent, charging = charging, full = full)
    }

    /** 邮件正文使用的一行设备状态文本，例如「设备电量：78%（充电中）」。 */
    fun batteryLine(context: Context): String {
        val info = battery(context)
        if (info.percent < 0) return "设备电量：未知"
        val state = when {
            info.full -> "已充满"
            info.charging -> "充电中"
            else -> "未充电"
        }
        return "设备电量：${info.percent}%（$state）"
    }
}
