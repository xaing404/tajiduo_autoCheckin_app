package com.tajiduo.attendance

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tajiduo.attendance.data.AccountStore
import com.tajiduo.attendance.data.SettingsStore
import com.tajiduo.attendance.data.StateStore
import com.tajiduo.attendance.databinding.ActivityMainBinding
import com.tajiduo.attendance.databinding.DialogLoginBinding
import com.tajiduo.attendance.login.LoginManager
import com.tajiduo.attendance.notify.NotificationHelper
import com.tajiduo.attendance.permission.PermissionHelper
import com.tajiduo.attendance.schedule.AlarmScheduler
import com.tajiduo.attendance.schedule.AttendanceAlarmReceiver
import com.tajiduo.attendance.service.AttendanceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private lateinit var stateStore: StateStore
    private lateinit var accountStore: AccountStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var codeCountdownJob: Job? = null
    private var isRunning = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val runFinishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            setRunning(false)
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用主题模式（需在 inflate 前设置，确保首帧就用正确配色）
        settings = SettingsStore(this)
        applyThemeMode(settings.themeMode)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stateStore = StateStore(this)
        accountStore = AccountStore(this)

        accountStore.ensureSeeded()
        NotificationHelper(this).ensureChannel()
        AlarmScheduler.scheduleFromSettings(this)

        bindUi()
        ContextCompat.registerReceiver(
            this,
            runFinishedReceiver,
            IntentFilter().apply {
                addAction(AttendanceService.ACTION_RUN_FINISHED)
                // 跨日重置闹钟（00:00）触发时刷新首页今日状态
                addAction(AttendanceAlarmReceiver.ACTION_DAY_RESET_TICK)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(runFinishedReceiver)
        }
        catch (_: Exception) {
            // 未注册时忽略
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 初始化 ----------------

    private fun bindUi() {
        // 先赋值初始值，再挂监听，避免触发回调
        binding.switchCoinTasks.isChecked = settings.coinTasks
        binding.switchCloudDuration.isChecked = settings.cloudDuration
        binding.switchKillAfterRun.isChecked = settings.killAfterRun
        binding.switchNotify.isChecked = settings.notifyEnabled
        binding.tvSignTime.text = formatHourMinute(settings.hour, settings.minute)
        binding.tvTheme.text = themeLabel(settings.themeMode)
        binding.tvSharePlatform.text = settings.sharePlatform
        binding.etMaxRetries.setText(settings.maxRetries.toString())
        binding.etWebhook.setText(settings.notificationUrls)
        binding.etEmailSender.setText(settings.emailSender)
        binding.etEmailAuthCode.setText(settings.emailAuthCode)
        binding.etEmailRecipient.setText(settings.emailRecipient)

        binding.rowSignTime.setOnClickListener { showTimePicker() }
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.rowSharePlatform.setOnClickListener { showPlatformDialog() }
        // 高级设置折叠：默认收起，点 rowAdvanced 在 ▸/▾ 间切换
        binding.rowAdvanced.setOnClickListener {
            val expanded = binding.advancedContent.visibility == View.VISIBLE
            binding.advancedContent.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.tvAdvancedArrow.text = if (expanded) "▸" else "▾"
        }

        binding.switchCoinTasks.setOnCheckedChangeListener { _, value -> settings.coinTasks = value }
        binding.switchCloudDuration.setOnCheckedChangeListener { _, value -> settings.cloudDuration = value }
        binding.switchKillAfterRun.setOnCheckedChangeListener { _, value -> settings.killAfterRun = value }
        binding.switchNotify.setOnCheckedChangeListener { _, value ->
            settings.notifyEnabled = value
            if (value && !PermissionHelper.hasNotificationPermission(this)) {
                maybeRequestNotificationPermission()
            }
        }

        binding.btnSaveSettings.setOnClickListener { saveTextSettings() }
        binding.btnSignNow.setOnClickListener { startRun() }
        binding.btnPermission.setOnClickListener { PermissionHelper.openAutoStartSettings(this) }
        binding.btnLogin.setOnClickListener { showLoginDialog() }

        binding.btnPermNotify.setOnClickListener { onNotifyPermissionClick() }
        binding.btnPermAlarm.setOnClickListener { PermissionHelper.requestExactAlarmPermission(this) }
        binding.btnPermBattery.setOnClickListener { PermissionHelper.requestIgnoreBatteryOptimizations(this) }
        binding.btnPermAutostart.setOnClickListener { PermissionHelper.openAutoStartSettings(this) }

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onNotifyPermissionClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        else {
            PermissionHelper.openNotificationSettings(this)
        }
    }

    // ---------------- 刷新 UI ----------------

    private fun refresh() {
        updateNextRun()
        updateStatus()
        updatePermissions()
        updateDetail()
        updateAccount()
        updateHistory()
        updateCalendar()
    }

    private fun updateNextRun() {
        binding.tvNextRun.text = "下次自动签到：${AlarmScheduler.formatNextTrigger(settings.hour, settings.minute)}"
    }

    private fun updateStatus() {
        val record = stateStore.lastRecord()
        if (isRunning) {
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_unknown)
            binding.tvStatus.text = getString(R.string.status_running)
            binding.tvStatusTime.visibility = android.view.View.GONE
            return
        }
        if (record == null) {
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_unknown)
            binding.tvStatus.text = getString(R.string.status_unknown)
            binding.tvStatusTime.visibility = android.view.View.GONE
            return
        }
        // 已跨日：最新一条运行记录不属于今天，则今日尚未签到（昨天的成功不延续到今天）。
        if (shanghaiDate(record.finishedAt) != shanghaiDate(System.currentTimeMillis())) {
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_warn)
            binding.tvStatus.text = getString(R.string.status_unsigned_today)
            binding.tvStatusTime.text = "最近运行：${formatDateTime(record.finishedAt)}"
            binding.tvStatusTime.visibility = android.view.View.VISIBLE
            return
        }
        val (labelRes, colorRes) = when {
            record.failedCount > 0 -> R.string.status_failed to R.color.state_bad
            record.successCount > 0 -> R.string.status_success to R.color.state_ok
            else -> R.string.status_skipped to R.color.state_warn
        }
        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
        binding.tvStatus.text = getString(labelRes)
        binding.tvStatusTime.text = "最近运行：${formatDateTime(record.finishedAt)}"
        binding.tvStatusTime.visibility = android.view.View.VISIBLE
    }

    private fun updatePermissions() {
        val notifyOk = PermissionHelper.hasNotificationPermission(this)
        val alarmOk = PermissionHelper.canScheduleExactAlarms(this)
        val batteryOk = PermissionHelper.isIgnoringBatteryOptimizations(this)

        setPermissionStatus(binding.tvPermNotifyStatus, notifyOk)
        setPermissionStatus(binding.tvPermAlarmStatus, alarmOk)
        setPermissionStatus(binding.tvPermBatteryStatus, batteryOk)
        binding.tvPermAutostartStatus.text = "需在系统设置中手动开启"

        val allOk = notifyOk && alarmOk && batteryOk
        binding.tvPermissionHint.text = if (allOk) {
            getString(R.string.permission_hint_ok)
        }
        else {
            getString(R.string.permission_hint_missing)
        }
    }

    private fun setPermissionStatus(textView: android.widget.TextView, granted: Boolean) {
        textView.text = getString(if (granted) R.string.state_granted else R.string.state_denied)
        val colorRes = if (granted) R.color.state_ok else R.color.state_bad
        textView.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateDetail() {
        // 优先读 runs.json 的最新记录（同步写入、可靠），
        // 仅在历史缺失时回退到 last-summary，避免与状态卡出现数据源不一致
        val summary = stateStore.lastRecord()?.summary ?: stateStore.lastSummary()
        binding.tvDetail.text = summary.ifBlank { getString(R.string.no_detail) }
    }

    private fun updateAccount() {
        val accounts = try {
            accountStore.readAccounts()
        }
        catch (_: Exception) {
            emptyList()
        }
        // 只展示真正带凭据的账号；未配置的占位/空模板不算已登录
        val configured = accounts.filter {
            !it.accessToken.isNullOrBlank() || !it.refreshToken.isNullOrBlank()
        }
        if (configured.isEmpty()) {
            binding.tvAccount.text = getString(R.string.account_empty)
            return
        }
        val lines = configured.map { account ->
            buildString {
                append("${account.name}（${account.id}）\n")
                append("UID：${account.uid}\n")
                if (!account.roleName.isNullOrBlank()) {
                    append("角色：${account.roleName}（${account.roleId ?: "-"}）\n")
                }
                account.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    append("手机：${maskPhone(phone)}")
                }
            }.trim()
        }
        binding.tvAccount.text = lines.joinToString("\n\n")
    }

    private fun updateHistory() {
        val records = stateStore.loadRuns().take(5)
        if (records.isEmpty()) {
            binding.rvHistory.visibility = android.view.View.GONE
            binding.tvEmptyHistory.visibility = android.view.View.VISIBLE
            binding.rvHistory.adapter = null
            return
        }
        binding.rvHistory.visibility = android.view.View.VISIBLE
        binding.tvEmptyHistory.visibility = android.view.View.GONE
        binding.rvHistory.adapter = HistoryAdapter(records) { record ->
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.history_dialog_title))
                .setMessage(record.summary)
                .setPositiveButton(getString(R.string.dialog_close), null)
                .show()
        }
    }

    /** 渲染本月签到月历 + 各账号连续签到天数。数据源 runs.json（最近 30 条）。 */
    private fun updateCalendar() {
        val runs = stateStore.loadRuns()
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        val now = Calendar.getInstance(zone)
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH)
        val todayDay = now.get(Calendar.DAY_OF_MONTH)

        val dayStatus = HashMap<String, String>() // day -> "ok"/"bad"
        val accSuccess = HashMap<String, MutableSet<String>>()
        val accName = HashMap<String, String>()
        // runs 是新→旧（appendRun 插到索引 0）。月历按"当天最终结果"着色：
        // 每天第一次遇到的 run 即最新一次运行，定色后不再被同天更早的老记录覆盖，
        // 避免"今天最终成功但早先跑过失败"时整天被标红。
        for (run in runs) {
            val day = dayFmt.format(Date(run.finishedAt))
            if (!dayStatus.containsKey(day)) {
                val hasFail = run.accounts.any { it.status == "failed" }
                val hasOk = run.accounts.any { it.status == "success" }
                when {
                    hasFail -> dayStatus[day] = "bad"
                    hasOk -> dayStatus[day] = "ok"
                }
            }
            for (acc in run.accounts) {
                accName[acc.id] = acc.name
                if (acc.status == "success") accSuccess.getOrPut(acc.id) { mutableSetOf() }.add(day)
            }
        }

        // 连签天数
        val streakLines = accSuccess.map { (id, days) ->
            var s = 0
            val c = Calendar.getInstance(zone)
            while (true) {
                val d = dayFmt.format(c.time)
                if (days.contains(d)) s++ else break
                c.add(Calendar.DAY_OF_YEAR, -1)
                if (s > 366) break
            }
            "${accName[id] ?: id}：连续 $s 天"
        }
        binding.tvStreak.text = streakLines.ifEmpty { listOf("暂无签到记录") }.joinToString("　")

        // 填月历
        binding.gridCalendar.removeAllViews()
        val dp = resources.displayMetrics.density
        val headers = arrayOf("日", "一", "二", "三", "四", "五", "六")
        for (h in headers) addCalendarCell(h, dp, header = true)
        val firstCal = Calendar.getInstance(zone).apply {
            set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, 1)
        }
        val offset = firstCal.get(Calendar.DAY_OF_WEEK) - 1
        repeat(offset) { addCalendarCell("", dp) }
        val daysInMonth = firstCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (d in 1..daysInMonth) {
            val dayStr = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, d)
            addCalendarCell(d.toString(), dp, status = dayStatus[dayStr], isToday = d == todayDay)
        }
    }

    private fun addCalendarCell(
        text: String,
        dp: Float,
        header: Boolean = false,
        status: String? = null,
        isToday: Boolean = false,
    ) {
        val size = (36 * dp).toInt()
        val tv = android.widget.TextView(this).apply {
            this.text = text
            textSize = if (header) 12f else 13f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(2, 2, 2, 2)
            }
        }
        when {
            header -> {
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            status == "ok" -> {
                tv.setBackgroundResource(R.drawable.calendar_ok_bg)
                tv.setTextColor(android.graphics.Color.WHITE)
            }
            status == "bad" -> {
                tv.setBackgroundResource(R.drawable.calendar_bad_bg)
                tv.setTextColor(android.graphics.Color.WHITE)
            }
            isToday -> {
                tv.setBackgroundResource(R.drawable.calendar_today_bg)
                tv.setTextColor(ContextCompat.getColor(this, R.color.brand))
            }
            else -> {
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
        binding.gridCalendar.addView(tv)
    }

    private fun setRunning(running: Boolean) {
        isRunning = running
        binding.progress.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnSignNow.isEnabled = !running
        binding.btnSignNow.text = if (running) getString(R.string.status_running) else getString(R.string.btn_sign_now)
        if (running) {
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_unknown)
            binding.tvStatus.text = getString(R.string.status_running)
            binding.tvStatusTime.visibility = android.view.View.GONE
        }
    }

    // ---------------- 交互 ----------------

    private fun startRun() {
        if (isRunning) return
        setRunning(true)
        ContextCompat.startForegroundService(
            this,
            AttendanceService.buildIntent(this, force = true, killAfter = false),
        )
    }

    // ---------------- 登录 ----------------

    /** 短信验证码登录：无需 adb 注入凭据，任何人都能用自己的账号上手。 */
    private fun showLoginDialog() {
        val dialogBinding = DialogLoginBinding.inflate(layoutInflater)
        val loginManager = LoginManager(accountStore)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.login_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.dialog_close, null)
            .create()

        var busy = false

        fun setBusy(value: Boolean) {
            busy = value
            dialogBinding.progressLogin.visibility = if (value) View.VISIBLE else View.GONE
            dialogBinding.btnDoLogin.isEnabled = !value
        }

        fun showStatus(text: String, isError: Boolean) {
            dialogBinding.tvLoginStatus.visibility = View.VISIBLE
            dialogBinding.tvLoginStatus.text = text
            dialogBinding.tvLoginStatus.setTextColor(
                ContextCompat.getColor(this, if (isError) R.color.state_bad else R.color.state_ok),
            )
        }

        dialogBinding.btnSendCode.setOnClickListener {
            if (busy) return@setOnClickListener
            val phone = dialogBinding.etPhone.text?.toString()?.trim().orEmpty()
            if (phone.length != 11) {
                showStatus(getString(R.string.login_phone_invalid), true)
                return@setOnClickListener
            }
            setBusy(true)
            showStatus(getString(R.string.login_sending), false)
            scope.launch {
                try {
                    loginManager.sendCaptcha(phone)
                    showStatus(getString(R.string.login_sent), false)
                    startCodeCountdown(dialogBinding.btnSendCode)
                }
                catch (error: Exception) {
                    showStatus(getString(R.string.login_send_failed, error.message.orEmpty()), true)
                }
                finally {
                    setBusy(false)
                }
            }
        }

        dialogBinding.btnDoLogin.setOnClickListener {
            if (busy) return@setOnClickListener
            val phone = dialogBinding.etPhone.text?.toString()?.trim().orEmpty()
            val code = dialogBinding.etCode.text?.toString()?.trim().orEmpty()
            if (phone.length != 11) {
                showStatus(getString(R.string.login_phone_invalid), true)
                return@setOnClickListener
            }
            if (code.isBlank()) {
                showStatus(getString(R.string.login_code_required), true)
                return@setOnClickListener
            }
            setBusy(true)
            showStatus(getString(R.string.login_logging_in), false)
            scope.launch {
                try {
                    val account = loginManager.login(phone, code)
                    showStatus(getString(R.string.login_success, account.name), false)
                    refresh()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.login_success, account.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    dialog.dismiss()
                }
                catch (error: Exception) {
                    showStatus(getString(R.string.login_failed, error.message.orEmpty()), true)
                }
                finally {
                    setBusy(false)
                }
            }
        }

        dialog.setOnDismissListener { codeCountdownJob?.cancel() }
        dialog.show()
    }

    /** 「获取验证码」按钮的 60 秒重发倒计时。 */
    private fun startCodeCountdown(button: MaterialButton) {
        codeCountdownJob?.cancel()
        codeCountdownJob = scope.launch {
            for (remaining in 60 downTo 1) {
                button.isEnabled = false
                button.text = getString(R.string.login_resend, remaining)
                delay(1000)
            }
            button.isEnabled = true
            button.text = getString(R.string.login_send_code)
        }
    }

    private fun themeLabel(mode: Int): String = when (mode) {
        1 -> "浅色"
        2 -> "深色"
        else -> "跟随系统"
    }

    private fun showThemeDialog() {
        val labels = arrayOf("跟随系统", "浅色", "深色")
        MaterialAlertDialogBuilder(this)
            .setTitle("主题模式")
            .setSingleChoiceItems(labels, settings.themeMode) { dialog, which ->
                settings.themeMode = which
                binding.tvTheme.text = themeLabel(which)
                applyThemeMode(which)
                dialog.dismiss()
            }
            .show()
    }

    private fun applyThemeMode(mode: Int) {
        val nightMode = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun showTimePicker() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(settings.hour)
            .setMinute(settings.minute)
            .setTitleText(getString(R.string.setting_sign_time))
            .build()
        picker.addOnPositiveButtonClickListener {
            settings.hour = picker.hour
            settings.minute = picker.minute
            binding.tvSignTime.text = formatHourMinute(picker.hour, picker.minute)
            AlarmScheduler.scheduleFromSettings(this)
            updateNextRun()
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        }
        picker.show(supportFragmentManager, "sign_time_picker")
    }

    private fun showPlatformDialog() {
        val platforms = arrayOf("qq", "wechat", "weibo")
        val labels = arrayOf("QQ", "微信", "微博")
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_share_platform))
            .setItems(labels) { _, which ->
                val value = platforms[which]
                settings.sharePlatform = value
                binding.tvSharePlatform.text = value
            }
            .show()
    }

    private fun saveTextSettings() {
        val retries = binding.etMaxRetries.text?.toString()?.trim()?.toIntOrNull() ?: settings.maxRetries
        settings.maxRetries = retries.coerceIn(1, 10)
        settings.notificationUrls = binding.etWebhook.text?.toString()?.trim() ?: ""
        settings.emailSender = binding.etEmailSender.text?.toString()?.trim() ?: ""
        settings.emailAuthCode = binding.etEmailAuthCode.text?.toString()?.trim() ?: ""
        settings.emailRecipient = binding.etEmailRecipient.text?.toString()?.trim() ?: ""
        binding.etMaxRetries.setText(settings.maxRetries.toString())
        // 发件邮箱与授权码必须成对填写，否则邮件通知不会生效（其余设置照常保存）
        if (settings.emailSender.isBlank() != settings.emailAuthCode.isBlank()) {
            Toast.makeText(this, R.string.toast_email_incomplete, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 工具 ----------------

    private fun formatHourMinute(hour: Int, minute: Int): String =
        String.format(Locale.CHINA, "%02d:%02d", hour, minute)

    private fun formatDateTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date(timestamp))

    /** 当天日期（Asia/Shanghai，yyyy-MM-dd），与去重键、闹钟判断口径保持一致。 */
    private fun shanghaiDate(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date(timestamp))

    private fun maskPhone(phone: String): String =
        if (phone.length >= 7) "${phone.take(3)}****${phone.takeLast(4)}" else phone
}