package com.tajiduo.attendance.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 运行状态存储：按“账号 + 日期”去重，保存最近运行记录。
 * 去重键与 TS 端一致：attendance:<accountId>:<yyyy-MM-dd>（Asia/Shanghai）。
 */
class StateStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("tajiduo_state", Context.MODE_PRIVATE)
    private val runsFile = File(context.filesDir, "runs.json")

    fun isSignedToday(accountId: String, date: String): Boolean =
        prefs.contains(stateKey(accountId, date))

    fun markSigned(accountId: String, accountName: String, date: String) {
        val payload = JSONObject().apply {
            put("status", "success")
            put("accountId", accountId)
            put("accountName", accountName)
            put("date", date)
            put("updatedAt", System.currentTimeMillis())
        }
        // 必须用 commit() 同步落盘：定时任务结束后会立即自杀进程，
        // apply() 的异步写入来不及刷盘就会丢失（导致去重键失效、当晚重复签到）。
        prefs.edit().putString(stateKey(accountId, date), payload.toString()).commit()
    }

    fun lastSummary(): String = prefs.getString("last-summary", "") ?: ""

    fun saveSummary(summary: String) {
        // 同上：同步落盘，否则定时运行的摘要会被自杀进程吞掉
        prefs.edit().putString("last-summary", summary).commit()
    }

    /**
     * 邮件发送结果记录（排查用）：scope 区分签到邮件（sign）/夜间检查邮件（check）。
     * 同步落盘：进程可能随后立即自杀，异步写入会丢失。
     */
    fun recordEmailStatus(scope: String, date: String, ok: Boolean, detail: String) {
        val value = if (ok) "sent" else "failed: $detail"
        prefs.edit().putString("email:$scope:$date", value).commit()
    }

    fun lastRecord(): RunRecord? = loadRuns().firstOrNull()

    fun appendRun(record: RunRecord) {
        val records = loadRuns().toMutableList()
        records.add(0, record)
        val trimmed = records.take(MAX_RECORDS)
        runsFile.writeText(Json.runsToJson(trimmed), Charsets.UTF_8)
    }

    fun loadRuns(): List<RunRecord> {
        if (!runsFile.exists()) return emptyList()
        return try {
            Json.runsFromJson(runsFile.readText(Charsets.UTF_8))
        }
        catch (_: Exception) {
            emptyList()
        }
    }

    private fun stateKey(accountId: String, date: String) = "attendance:$accountId:$date"

    private companion object {
        const val MAX_RECORDS = 30
    }
}