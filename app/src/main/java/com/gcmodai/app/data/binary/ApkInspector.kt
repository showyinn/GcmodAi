package com.gcmodai.app.data.binary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * APK / ZIP 结构解析：清单（AXML）+ 多 dex + 各 ABI 的 .so 盘点。
 *
 * 只读不执行：解开 zip 条目、读出 AndroidManifest.xml 与 classes*.dex，
 * 再把 dex 摘要与 so 清单拼进报告。真实安装包里的 .so 会单独走 ELF 分析。
 */
object ApkInspector {

    private const val MAX_ENTRY_READ = 64L * 1024 * 1024   // 单个条目最多读 64MB
    private const val MAX_ENTRIES = 4000

    fun inspect(data: ByteArray, fileName: String, hash: String): BinaryReport? {
        val entries = ArrayList<ApkEntry>()
        var manifestBytes: ByteArray? = null
        val dexEntries = ArrayList<Pair<String, ByteArray>>()
        val notes = ArrayList<String>()

        runCatching {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (count++ > MAX_ENTRIES) break
                    val name = entry.name
                    entries.add(ApkEntry(name, entry.size.coerceAtLeast(0), entry.compressedSize.coerceAtLeast(0)))
                    val interesting = name == "AndroidManifest.xml" ||
                        name.matches(Regex("classes\\d*\\.dex")) ||
                        (name.startsWith("lib/") && name.endsWith(".so"))
                    if (!interesting || entry.size > MAX_ENTRY_READ) continue
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_ENTRY_READ) break
                    }
                    val bytes = out.toByteArray()
                    when {
                        name == "AndroidManifest.xml" -> manifestBytes = bytes
                        name.matches(Regex("classes\\d*\\.dex")) && dexEntries.size < 4 ->
                            dexEntries.add(name to bytes)
                    }
                }
            }
        }.onFailure { notes.add("解压失败：${it.message}（文件可能被加密或损坏）") }

        if (entries.isEmpty()) return null

        val manifest = manifestBytes?.let { runCatching { AxmlParser.readManifest(it) }.getOrNull() }
        if (manifest == null) notes.add("AndroidManifest.xml 解析失败：可能不是 APK，或清单被改坏")

        // dex 摘要：只解析前几个，避免大包把内存吃满
        val classes = ArrayList<DexClassInfo>()
        val strings = ArrayList<String>()
        for ((name, bytes) in dexEntries) {
            runCatching {
                val (cls, str) = DexParser.parse(ByteReader(bytes))
                classes.addAll(cls)
                if (strings.size < 300) strings.addAll(str.take(300 - strings.size))
                notes.add("$name：${cls.size} 个类，${str.size} 条字符串")
            }.onFailure { notes.add("$name 解析失败：${it.message}") }
        }

        val soList = entries.filter { it.path.startsWith("lib/") && it.path.endsWith(".so") }
        val soByAbi = soList.groupBy { it.path.split("/").getOrNull(1) ?: "?" }
        val abiText = soByAbi.entries.joinToString("、") { "${it.key}(${it.value.size} 个)" }

        val summary = buildString {
            append("APK 归档，${entries.size} 个条目")
            manifest?.let {
                append("，包名 ${it.packageName}")
                if (it.versionName.isNotBlank()) append(" v${it.versionName}")
            }
            append("，dex ${dexEntries.size} 个（类 ${classes.size}）")
            if (soList.isNotEmpty()) append("，原生库 $abiText")
        }

        return BinaryReport(
            fileName = fileName,
            sizeBytes = data.size.toLong(),
            sha256 = hash,
            format = BinaryFormat.ZIP,
            arch = if (soByAbi.isNotEmpty()) soByAbi.keys.joinToString("/") else "通用",
            bits = 64,
            endian = "-",
            summary = summary,
            dexClasses = classes,
            apkEntries = entries,
            manifest = manifest,
            strings = strings.distinct().take(300),
            overallEntropy = ByteTools.entropy(data, 0, minOf(data.size, 1024 * 1024)),
            notes = notes,
        )
    }

    /** 从 APK 里取出指定的 .so / dex 原始字节，供单独分析。 */
    fun extractEntry(data: ByteArray, path: String): ByteArray? {
        runCatching {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == path) {
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        return out.toByteArray()
                    }
                }
            }
        }
        return null
    }
}
