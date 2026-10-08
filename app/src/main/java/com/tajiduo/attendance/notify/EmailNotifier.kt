package com.tajiduo.attendance.notify

import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * QQ 邮箱 SMTP 邮件通知（smtp.qq.com:465，SSL）：
 * - 认证账号 = 发件邮箱，密码 = SMTP 授权码（非 QQ 密码）
 * - 纯文本 UTF-8 正文，单次连接/读/写各 15 秒超时
 * - 与 NotifyWebhook 风格一致：返回错误列表、不抛出，失败不影响签到主流程
 *
 * 硬超时保护（重要）：超级省电 + 深度休眠唤醒等极端网络场景下，
 * JavaMail 内部超时曾失效导致整个调用卡死数小时（进程被冻结、邮件丢失、
 * 无法自杀退出）。现在实际发送放在独立线程执行并限定总时长，
 * 超时即放弃等待并返回错误，由调用方继续后续流程（定时任务随后自杀回收进程）。
 */
object EmailNotifier {

    private const val SMTP_HOST = "smtp.qq.com"
    private const val SMTP_PORT = "465"
    private const val TIMEOUT_MS = "15000"

    /** 整个 send() 调用的硬性时长上限（含最多两次尝试）。 */
    private const val HARD_TIMEOUT_MS = 45_000L

    /** 两次尝试之间的短暂等待，给网络栈缓冲机会。 */
    private const val RETRY_DELAY_MS = 2_000L

    /** 返回空列表表示发送成功；否则每项为一条错误描述（不含授权码等敏感信息）。 */
    fun send(sender: String, authCode: String, recipient: String, subject: String, content: String): List<String> {
        val resultRef = AtomicReference<List<String>?>(null)
        val worker = Thread(
            { resultRef.set(attemptSend(sender, authCode, recipient, subject, content)) },
            "tajiduo-smtp",
        ).apply { isDaemon = true }
        worker.start()
        try {
            worker.join(HARD_TIMEOUT_MS)
        }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (worker.isAlive) {
            // 放弃等待：继续流程比无限卡死更重要（进程随后的自杀会回收卡住的线程）。
            return listOf("发送超时：超过 ${HARD_TIMEOUT_MS / 1000} 秒未完成，已放弃等待")
        }
        return resultRef.get() ?: listOf("发送状态未知")
    }

    /** 最多两次尝试：首次失败后重建连接再试一次（应对深度休眠唤醒后的瞬时网络异常）。 */
    private fun attemptSend(
        sender: String,
        authCode: String,
        recipient: String,
        subject: String,
        content: String,
    ): List<String> {
        var errors = trySend(sender, authCode, recipient, subject, content)
        if (errors.isEmpty()) return emptyList()
        try {
            Thread.sleep(RETRY_DELAY_MS)
        }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return errors
        }
        errors = trySend(sender, authCode, recipient, subject, content)
        return errors
    }

    private fun trySend(
        sender: String,
        authCode: String,
        recipient: String,
        subject: String,
        content: String,
    ): List<String> {
        val errors = ArrayList<String>()
        try {
            val props = Properties().apply {
                put("mail.smtp.host", SMTP_HOST)
                put("mail.smtp.port", SMTP_PORT)
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", TIMEOUT_MS)
                put("mail.smtp.timeout", TIMEOUT_MS)
                put("mail.smtp.writetimeout", TIMEOUT_MS)
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(sender, authCode)
            })
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(sender, "塔吉多签到", "UTF-8"))
                setRecipient(Message.RecipientType.TO, InternetAddress(recipient))
                setSubject(subject, "UTF-8")
                setText(content, "UTF-8")
            }
            Transport.send(message)
        }
        catch (error: Exception) {
            errors.add(error.message ?: error.javaClass.simpleName)
        }
        return errors
    }
}