package com.openminis.app.a2a

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启：把 A2A 网关的前台服务拉回来。
 *
 * ── 为什么需要 ────────────────────────────────────────────────
 * 用户开了「保活」之后，心理预期是「重启手机也不用手动开」。但前台服务
 * 不会随开机自启，AlarmManager 的闹钟也在重启时被清空 —— 结果就是开机后
 * 网关静悄悄没起来，面板显示未运行。
 *
 * BOOT_COMPLETED 是唯一可靠的信号；部分国产 ROM（Honor / 华为 EMUI）在
 * 「快速启动」场景下不发 LOCKED_BOOT_COMPLETED / BOOT_COMPLETED 而是发
 * 厂商私有的 QUICKBOOT_POWERON（HTC 遗留，被大量 ROM 沿用），所以两个都监听。
 *
 * 启动一律 runCatching 静默失败：开机阶段系统极易拒绝 startForegroundService
 * （后台启动限制 / 用户关闭自启动权限），抛异常会让接收器崩在系统进程里并且
 * 没有任何补救价值 —— 下一个 ACTION_WATCHDOG 闹钟照样能把服务叫回来。
 */
class A2aBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) return
        // 用户关掉了保活开关就别自作主张拉起：设置页里那个开关是唯一契约。
        if (!A2aPrefs.keepAlive(context)) return
        // 用户在面板点过停止：开机不复活。
        if (!A2aPrefs.gatewayWanted(context)) return
        runCatching {
            val app = context.applicationContext
            val service = Intent(app, A2aKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(service)
            } else {
                app.startService(service)
            }
            // 开机后系统把所有闹钟清了，这里顺手把兜底自唤醒重新排上。
            A2aKeepAliveService.scheduleWatchdog(app)
        }
    }

    companion object {
        /** 部分 ROM（Honor / EMUI / 旧 HTC 衍生）在快速启动时发的替代广播。 */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
