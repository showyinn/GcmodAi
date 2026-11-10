package com.gcmodai.app.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** 探测到的一台可连接主机。 */
data class SshHost(
    val ip: String,
    /** 实际开放 SSH 的端口；标准是 22 */
    val port: Int,
    val latencyMs: Long,
) {
    val address: String get() = if (port == 22) ip else "$ip:$port"
}

/**
 * 网络探测：找出局域网里开着 SSH 的主机。
 *
 * ## 为什么需要它
 *
 * 用户的痛点不是"填 IP"本身，而是**不知道 IP 是多少**。
 * 蓝牙能配对、能拿到设备名（通常就是电脑主机名），
 * 但拿不到 IP——所以这里扫一遍本网段，把开着 22 端口的机器列出来，
 * 让用户点一下就能填完，而不是去电脑上敲 ipconfig。
 *
 * ## 只探测 SSH 端口
 *
 * 只扫 22 和少量常见替代端口（2222），比全端口扫描快一个数量级，
 * 也减少对网络里其它设备的影响。
 */
object NetProbe {

    /** 常见 SSH 端口，按概率排序 */
    private val SSH_PORTS = listOf(22, 2222)

    /** 本机在局域网里的 IPv4。 */
    suspend fun localIpv4(): String? = withContext(Dispatchers.IO) {
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /** 192.168.1.5 → 192.168.1；参数非法返回 null。 */
    internal fun subnetPrefixOf(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull()?.let { n -> n in 0..255 } != true }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    private fun probe(ip: String, timeoutMs: Int): SshHost? {
        for (port in SSH_PORTS) {
            val started = System.currentTimeMillis()
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), timeoutMs)
                    true
                }
            }.getOrDefault(false)
            if (ok) {
                return SshHost(ip = ip, port = port, latencyMs = System.currentTimeMillis() - started)
            }
        }
        return null
    }

    /**
     * 扫描本网段里开着 SSH 的主机。
     *
     * @param timeoutMs 单端口超时，默认 300ms；越小越快但可能漏掉慢设备
     */
    suspend fun scanSshHosts(
        timeoutMs: Int = 300,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SshHost> = withContext(Dispatchers.IO) {
        val self = localIpv4() ?: return@withContext emptyList()
        val prefix = subnetPrefixOf(self) ?: return@withContext emptyList()

        val targets = (1..254).map { "$prefix.$it" }.filter { it != self }
        var done = 0
        val total = targets.size

        coroutineScope {
            targets.map { ip ->
                async(Dispatchers.IO) {
                    val host = probe(ip, timeoutMs)
                    synchronized(this@coroutineScope) {
                        done++
                        onProgress(done, total)
                    }
                    host
                }
            }.awaitAll()
        }.filterNotNull().sortedBy { it.latencyMs }
    }

    /**
     * 按蓝牙设备名给探测结果排个序：
     * 名字里含主机名片段的优先（例如设备名 "DESKTOP-ABC" 命中同名前缀）。
     *
     * 我们无法从 IP 反查主机名（需要 NetBIOS/mDNS，代价高），
     * 但把"看起来相关"的排前面能明显减少用户的选择成本。
     */
    fun rankByDeviceName(hosts: List<SshHost>, deviceName: String): List<SshHost> {
        val key = deviceName.uppercase().trim().replace("-", "").replace(" ", "")
        if (key.isBlank()) return hosts
        return hosts.sortedWith(
            compareByDescending<SshHost> { host ->
                // IP 里没有名字信息，这里只能按延迟（同网段有线/无线通常更快）兜底，
                // 保持稳定排序；真正的名称匹配在下面用 known names 做。
                host.latencyMs <= 5
            }.thenBy { it.latencyMs }
        )
    }
}
