package com.gcmodai.app.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 后台保活工具箱。
 *
 * 为什么需要单独一个文件：聊天流式生成、内嵌 Web 服务、SSH 云工作区、APK 加固轮询
 * 都是「用户锁屏/切到别的 App 之后还要继续跑」的活。Android 默认会把这些进程按
 * Doze / 省电模式掐掉，光有前台服务还不够——厂商 ROM 的「后台限制」会在前台服务
 * 之上再杀一层。所以这里集中处理三件事：
 *
 * 1. 申请电池优化白名单（Doze 豁免）；
 * 2. 跳到厂商的自启动/后台管理页面（这些页面没有标准 Intent，只能逐个试）；
 * 3. 生成期间持有 PARTIAL_WAKE_LOCK，防止 CPU 睡着导致流式中断。
 */
object BackgroundKeepAlive {

    private const val TAG = "BackgroundKeepAlive"

    /** 是否已在电池优化白名单里（Doze 豁免）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * 弹出系统的「允许后台运行/忽略电池优化」对话框。
     *
     * 部分 ROM（小米/华为）把这个 Intent 干掉了，会抛 ActivityNotFoundException，
     * 此时退回到电池优化设置列表页，用户还能自己找。
     *
     * @return true 表示成功拉起了某个系统页面
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return true
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch(context, direct)) return true
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(context, fallback)
    }

    /** 厂商 ROM 名字，用于给用户提示（原生 Android 返回 null）。 */
    fun vendorAutoStartHint(): String? = when {
        isMiui() -> "小米/红米"
        isEmui() -> "华为/荣耀"
        isColorOs() -> "OPPO/一加/realme"
        isVivo() -> "vivo/iQOO"
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> "三星"
        Build.MANUFACTURER.equals("meizu", ignoreCase = true) -> "魅族"
        else -> null
    }

    /**
     * 打开厂商「自启动管理 / 后台运行」页面。
     *
     * 这些页面没有公开的 Intent 常量，只能按已知的 ComponentName 逐个尝试；
     * 全部失败就退回当前 App 的应用详情页（用户可以在那里手动放行）。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = buildList {
            // 小米
            add(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            // 华为 / 荣耀
            add(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            add(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            // OPPO / 一加 / realme
            add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
            add(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
            add(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"))
            // vivo / iQOO
            add(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            add(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"))
            // 三星
            add(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
            // 魅族
            add(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))
            // 联想
            add(ComponentName("com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"))
        }
        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launch(context, intent)) return true
        }
        // 兜底：应用详情页，用户点「电池 → 不受限制」也能达到同样效果
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(context, details)
    }

    private fun launch(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.onFailure {
        Log.d(TAG, "页面拉不起来：${intent.component ?: intent.action}", it)
    }.getOrDefault(false)

    private fun isMiui(): Boolean = runCatching {
        val prop = getSystemProperty("ro.miui.ui.version.name")
        !prop.isNullOrBlank()
    }.getOrDefault(false)

    private fun isEmui(): Boolean = runCatching {
        val prop = getSystemProperty("ro.build.version.emui")
        !prop.isNullOrBlank()
    }.getOrDefault(false)

    private fun isColorOs(): Boolean = runCatching {
        val prop = getSystemProperty("ro.build.version.opporom")
        !prop.isNullOrBlank()
    }.getOrDefault(false)

    private fun isVivo(): Boolean = runCatching {
        val prop = getSystemProperty("ro.vivo.os.version")
        !prop.isNullOrBlank()
    }.getOrDefault(false)

    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    }.getOrNull()
}

/**
 * 生成期间的 CPU 唤醒锁。
 *
 * 只持 `PARTIAL_WAKE_LOCK`（只保 CPU，不亮屏），并且带着 10 分钟超时：
 * 万一 release 没被调用（进程被杀、异常路径），锁也会自己过期，
 * 不会变成那种「一装这 App 手机就特别费电」的差评来源。
 */
class GenerationWakeLock(context: Context) {

    private val appContext = context.applicationContext
    private var lock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "GenerationWakeLock"
        private const val TIMEOUT_MS = 10 * 60 * 1000L
    }

    fun acquire() {
        if (lock?.isHeld == true) return
        runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GcmodAi:generation")
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(TIMEOUT_MS)
            lock = wakeLock
        }.onFailure {
            Log.w(TAG, "获取唤醒锁失败，后台生成可能被系统挂起", it)
        }
    }

    fun release() {
        runCatching {
            val wakeLock = lock ?: return
            if (wakeLock.isHeld) wakeLock.release()
        }.onFailure {
            Log.w(TAG, "释放唤醒锁失败", it)
        }
        lock = null
    }
}
