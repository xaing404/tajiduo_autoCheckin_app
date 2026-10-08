package com.tajiduo.attendance

import android.app.Application

/**
 * 进程级初始化：QQ 邮箱 SMTP（JavaMail）在深度休眠唤醒、超级省电等
 * 极端网络场景下曾出现连接卡死，统一让 Java 网络栈优先 IPv4，
 * 规避不可达的 IPv6 路径造成的长时间阻塞。
 */
class TajiduoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        System.setProperty("java.net.preferIPv4Stack", "true")
    }
}