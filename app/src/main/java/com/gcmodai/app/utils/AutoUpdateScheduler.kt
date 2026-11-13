package com.gcmodai.app.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gcmodai.app.R
import com.gcmodai.app.AppScope
import com.gcmodai.app.data.datastore.Settings
import com.gcmodai.app.utils.UiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 自动更新调度器。
 *
 * 用户要的是「不用自己去下载」：所以流程做成
 *   启动 → 静默检查 → 有新版本就在后台下好 → 通知栏提示「点按安装」。
 * 用户全程只在最后点一下（这是 Android 的硬限制：安装必须由用户确认，
 * 想完全静默安装需要系统签名/设备管理员权限，普通应用做不到）。
 *
 * 几个刻意的取舍：
 *  · 只在 Wi-Fi 下默认下载（可关），避免把用户流量吃光；
 *  · 热更新和 APK 更新分开：热更新是界面/配置，静默即可生效；
 *  · 下载失败不打扰用户（只在通知里留一条），下次启动再试。
 */
class AutoUpdateScheduler(
    private val context: Context,
    private val updateChecker: UpdateChecker,
    private val hotUpdateManager: HotUpdateManager,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "AutoUpdate"
        private const val CHANNEL_ID = "gcmodai_update"
        private const val NOTIFY_ID = 2003
        private const val PREF = "auto_update"
        private const val KEY_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L   // 6 小时一次
    }

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 启动时调用一次：按设置决定要不要检查 / 要不要静默下载 */
    fun runIfDue(settingsProvider: suspend () -> Settings) {
        appScope.launch {
            val settings = runCatching { settingsProvider() }.getOrNull() ?: return@launch
            if (!settings.autoCheckUpdates) return@launch

            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (now - last < CHECK_INTERVAL_MS) return@launch
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

            // 1) 热更新：不需要安装，直接静默应用
            if (settings.autoHotUpdate) {
                hotUpdateManager.checkAndApply(autoApply = true)
            }

            // 2) APK 更新
            updateChecker.refreshIfStale(minIntervalMs = 0)
            val info = (updateChecker.updateState.first() as? UiState.Success)?.data ?: return@launch
            if (!isNewer(info)) return@launch

            if (!settings.autoDownloadUpdates) {
                Log.i(TAG, "new version ${info.version} available, auto download disabled")
                return@launch
            }
            if (settings.autoUpdateWifiOnly && !isOnWifi()) {
                Log.i(TAG, "skip auto download: not on wifi")
                return@launch
            }

            val target = pickDownload(info) ?: return@launch
            Log.i(TAG, "auto downloading ${target.name}")
            updateChecker.downloadUpdate(context, target)
            // 下载完成后由 UpdateChecker 的状态流驱动通知
            watchDownloadFinished()
        }
    }

    private fun isNewer(info: com.gcmodai.app.utils.UpdateInfo): Boolean = runCatching {
        Version(info.version) > Version(com.gcmodai.app.BuildConfig.VERSION_NAME)
    }.getOrDefault(false)

    /** 优先 arm64，其次 universal：体积更小、装得上 */
    private fun pickDownload(info: com.gcmodai.app.utils.UpdateInfo): UpdateDownload? {
        val downloads = info.downloads
        if (downloads.isEmpty()) return null
        val abis = Build.SUPPORTED_ABIS.toList()
        return downloads.firstOrNull { d ->
            abis.any { abi -> d.name.contains(abi, ignoreCase = true) }
        } ?: downloads.first()
    }

    private fun isOnWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    private fun watchDownloadFinished() {
        appScope.launch {
            updateChecker.downloadState.collect { state ->
                if (state is UpdateDownloadState.Downloaded) {
                    notifyReady(state.name)
                }
            }
        }
    }

    private fun notifyReady(name: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "应用更新",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "新版本下载完成后的安装提醒" }
            )
        }
        // 点通知 -> 拉起安装（走 UpdateChecker 的安装逻辑）
        val intent = Intent(context, com.gcmodai.app.RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("gcmod_install_update", true)
        }
        val pi = PendingIntent.getActivity(
            context, NOTIFY_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gcmodai)
            .setContentTitle("新版本已下载")
            .setContentText("点按安装 $name（无需再等待下载）")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIFY_ID, notification) }
    }

    /** 用户点了通知：直接走安装 */
    fun installPending() {
        appScope.launch {
            updateChecker.installDownloadedApk(context)
        }
    }
}
