package com.gcmodai.app.data.analytics

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics

/**
 * 应用内埋点接口。
 *
 * 为什么不让业务代码直接用 FirebaseAnalytics：国内 ROM 上 Google Play 服务经常缺失或被裁剪，
 * Firebase 的任何调用都可能抛 `SecurityException: Binder invocation to an incorrect interface`
 * 或 `IllegalStateException: Default FirebaseApp is not initialized`，而这类异常发生在
 * 启动与消息发送路径上会直接把 App 打死（线上已复现）。
 * 所有埋点都必须经过这里，失败只允许降级、不允许冒泡。
 */
interface AppAnalytics {
    fun event(name: String)
}

/** 什么都不做的实现，用于 Firebase 不可用的环境。 */
object NoOpAnalytics : AppAnalytics {
    override fun event(name: String) = Unit
}

/**
 * 安全包装：首次调用失败即永久降级为 no-op。
 *
 * 只在第一次真正需要上报时才触碰 Firebase，因此就算整台设备没有 Google 服务，
 * 也只是一次被吞掉的异常，不会影响启动。
 */
class SafeAppAnalytics(@Suppress("UNUSED_PARAMETER") context: Context) : AppAnalytics {

    @Volatile
    private var disabled = false

    override fun event(name: String) {
        if (disabled) return
        try {
            Firebase.analytics.logEvent(name, null)
        } catch (t: Throwable) {
            disabled = true
            Log.w(TAG, "埋点不可用，已降级为 no-op：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "AppAnalytics"
    }
}
