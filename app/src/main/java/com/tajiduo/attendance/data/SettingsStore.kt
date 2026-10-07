package com.tajiduo.attendance.data

import android.content.Context

/** 用户设置（默认值与 TS 端 runtime 配置保持一致）。 */
class SettingsStore(context: Context) {

    // 使用设备加密存储（direct boot）：手机重启后即使还停留在锁屏未解锁，
    // BootReceiver 也能读到签到时间并重排闹钟。
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("tajiduo_settings", Context.MODE_PRIVATE)

    var hour: Int
        get() = prefs.getInt("hour", 8)
        set(value) = prefs.edit().putInt("hour", value).apply()

    var minute: Int
        get() = prefs.getInt("minute", 0)
        set(value) = prefs.edit().putInt("minute", value).apply()

    var coinTasks: Boolean
        get() = prefs.getBoolean("coin_tasks", true)
        set(value) = prefs.edit().putBoolean("coin_tasks", value).apply()

    var cloudDuration: Boolean
        get() = prefs.getBoolean("cloud_duration", true)
        set(value) = prefs.edit().putBoolean("cloud_duration", value).apply()

    var sharePlatform: String
        get() = prefs.getString("share_platform", "qq") ?: "qq"
        set(value) = prefs.edit().putString("share_platform", value).apply()

    var maxRetries: Int
        get() = prefs.getInt("max_retries", 3)
        set(value) = prefs.edit().putInt("max_retries", value).apply()

    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify_enabled", true)
        set(value) = prefs.edit().putBoolean("notify_enabled", value).apply()

    /** 签到完成后自我了结进程以省电（需求 4）。 */
    var killAfterRun: Boolean
        get() = prefs.getBoolean("kill_after_run", true)
        set(value) = prefs.edit().putBoolean("kill_after_run", value).apply()

    /** 可选：额外的 webhook 通知地址，多个用英文逗号分隔。 */
    var notificationUrls: String
        get() = prefs.getString("notification_urls", "") ?: ""
        set(value) = prefs.edit().putString("notification_urls", value).apply()

    // ---------------- 邮件通知（QQ 邮箱） ----------------

    /** 发件 QQ 邮箱（SMTP 认证账号）。 */
    var emailSender: String
        get() = prefs.getString("email_sender", "") ?: ""
        // 授权码类配置需确保落盘，避免进程被清理时丢失
        set(value) { prefs.edit().putString("email_sender", value).commit() }

    /** SMTP 授权码（非 QQ 密码）。 */
    var emailAuthCode: String
        get() = prefs.getString("email_auth_code", "") ?: ""
        set(value) { prefs.edit().putString("email_auth_code", value).commit() }

    /** 收件人邮箱，留空则发送到发件邮箱。 */
    var emailRecipient: String
        get() = prefs.getString("email_recipient", "") ?: ""
        set(value) { prefs.edit().putString("email_recipient", value).commit() }

    /** 邮件通知是否已配置完整（发件邮箱 + 授权码均非空即启用）。 */
    val emailConfigured: Boolean
        get() = emailSender.isNotBlank() && emailAuthCode.isNotBlank()

    // ---------------- 夜间补签检查 ----------------

    /** 夜间检查时间（默认 23:30）：当天未签到成功则重新触发一次签到。 */
    var failsafeHour: Int
        get() = prefs.getInt("failsafe_hour", 23)
        set(value) = prefs.edit().putInt("failsafe_hour", value).apply()

    var failsafeMinute: Int
        get() = prefs.getInt("failsafe_minute", 30)
        set(value) = prefs.edit().putInt("failsafe_minute", value).apply()

    /** 最近一次成功运行的日期（yyyy-MM-dd），用于 UI 提示。 */
    var lastRunDate: String
        get() = prefs.getString("last_run_date", "") ?: ""
        // 该字段在服务收尾时写入、紧随其后就是自杀进程，必须同步落盘
        set(value) {
            prefs.edit().putString("last_run_date", value).commit()
        }
}