package com.tajiduo.attendance.notify

import com.tajiduo.attendance.data.RunRecord
import com.tajiduo.attendance.data.StateStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 每周签到周报：读 runs.json（最近 30 条）统计本周表现与各账号连续签到天数。
 * 纯只读计算，不写状态。
 */
object WeeklyReportBuilder {

    private val zone: TimeZone get() = TimeZone.getTimeZone("Asia/Shanghai")
    private val dayFormat: SimpleDateFormat get() =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }

    fun build(state: StateStore): String {
        val runs = state.loadRuns()
        val now = Calendar.getInstance(zone)
        val today = dayFormat.format(now.time)

        // 1) 每个账号的成功日期集合（一天内多次运行，只要有一次 success 即算当天签成）
        val successDays = HashMap<String, MutableSet<String>>()
        val displayName = HashMap<String, String>()
        for (run in runs) {
            val day = dayFormat.format(Date(run.finishedAt))
            for (acc in run.accounts) {
                displayName[acc.id] = acc.name
                if (acc.status == "success") {
                    successDays.getOrPut(acc.id) { mutableSetOf() }.add(day)
                }
            }
        }

        // 2) 本周（周一 ~ 周日）概览
        val weekStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val diff = (get(Calendar.DAY_OF_WEEK) + 5) % 7 // 周一=0
            add(Calendar.DAY_OF_YEAR, -diff)
        }
        val weekEnd = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
        var successDaysCount = 0
        var failDaysCount = 0
        var idleDaysCount = 0
        for (i in 0..6) {
            val day = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }.time
            val dayStr = dayFormat.format(day)
            if (dayStr > today) continue // 未来的日子不算
            val hadRun = runs.any { dayFormat.format(Date(it.finishedAt)) == dayStr }
            val allSuccess = runs.any { r ->
                dayFormat.format(Date(r.finishedAt)) == dayStr &&
                    r.accounts.isNotEmpty() && r.accounts.all { it.status == "success" }
            }
            when {
                !hadRun -> idleDaysCount++
                allSuccess -> successDaysCount++
                else -> failDaysCount++
            }
        }
        val rangeLabel = "${SimpleDateFormat("MM-dd", Locale.US).format(weekStart.time)} ~ " +
            SimpleDateFormat("MM-dd", Locale.US).format(weekEnd.time)

        // 3) 各账号连续签到天数：从今天往前连续 success
        val streakLines = successDays.map { (id, days) ->
            var streak = 0
            val cursor = Calendar.getInstance(zone)
            // 今天没签成从今天算 0；从今天往前数
            while (true) {
                val d = dayFormat.format(cursor.time)
                if (days.contains(d)) streak++ else break
                cursor.add(Calendar.DAY_OF_YEAR, -1)
                if (streak > 366) break
            }
            "- ${displayName[id] ?: id}：连续签到 $streak 天"
        }

        // 4) 本周失败记录
        val failLines = runs
            .filter { run ->
                val day = dayFormat.format(Date(run.finishedAt))
                dayFormat.format(weekStart.time) <= day && day <= today
            }
            .filter { it.accounts.any { acc -> acc.status == "failed" } }
            .take(5)
            .map { run ->
                val day = dayFormat.format(Date(run.finishedAt)).substring(5)
                val who = run.accounts.filter { it.status == "failed" }
                    .joinToString("、") { it.name }
                "- $day：$who 失败"
            }

        return buildString {
            appendLine("塔吉多签到周报（$rangeLabel）")
            appendLine()
            appendLine("本周概览：")
            appendLine("- 全部成功：$successDaysCount 天")
            appendLine("- 有账号失败：$failDaysCount 天")
            appendLine("- 未运行：$idleDaysCount 天")
            appendLine()
            if (streakLines.isNotEmpty()) {
                appendLine("账号连续签到：")
                streakLines.forEach { appendLine(it) }
                appendLine()
            }
            if (failLines.isNotEmpty()) {
                appendLine("本周失败记录：")
                failLines.forEach { appendLine(it) }
            }
        }.trimEnd()
    }
}
