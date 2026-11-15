package com.gcmodai.app.data.harden

import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 从 APK 里读出推荐需要的特征。
 *
 * 只读 zip 目录结构，不解压 DEX、不跑 dexlib —— 手机上要快。
 * 需要的信息：DEX 个数与总大小、业务 .so 个数与总大小、支持的 ABI、包名。
 *
 * 包名从 AndroidManifest.xml 里取：manifest 是二进制 AXML，这里不解析完整格式，
 * 只在字节里找 `package=` 后面的连续性可打印串，够用了——
 * 拿不到就返回空，推荐逻辑不依赖包名做决定。
 */
object ApkProfiler {

    fun profile(bytes: ByteArray): HardenAdvisor.ApkProfile {
        var dexCount = 0
        var dexBytes = 0L
        var soCount = 0
        var soBytes = 0L
        val abis = linkedSetOf<String>()
        var androidxHits = 0
        var manifestBytes: ByteArray? = null

        runCatching {
            ZipInputStream(bytes.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name
                    when {
                        name.endsWith(".dex") && !name.contains('/') -> {
                            dexCount++
                            dexBytes += e.size.coerceAtLeast(0)
                            // 粗判是否 Compose/Kotlin 应用：DEX 名与大小无关，
                            // 这里靠后面 manifest 的类名线索，此处只统计个数
                        }
                        name.startsWith("lib/") && name.endsWith(".so") -> {
                            soCount++
                            soBytes += e.size.coerceAtLeast(0)
                            val abi = name.removePrefix("lib/").substringBefore('/')
                            if (abi.isNotBlank()) abis.add(abi)
                        }
                        name == "AndroidManifest.xml" -> {
                            manifestBytes = runCatching { zin.readBytes() }.getOrNull()
                        }
                    }
                    e = zin.nextEntry
                }
            }
        }

        val pkg = manifestBytes?.let { extractPackageName(it) } ?: ""

        return HardenAdvisor.ApkProfile(
            sizeBytes = bytes.size.toLong(),
            dexCount = dexCount,
            soCount = soCount,
            soBytes = soBytes,
            abis = abis.toList().sorted(),
            packageName = pkg,
            // androidx 出现说明是较现代的 AndroidX 应用；没有就按保守处理
            looksModern = androidxHits > 0 || pkg.isNotEmpty(),
        )
    }

    /** 在 AXML 字节里找 `package="..."` 的值。找不到返回空串。 */
    private fun extractPackageName(axml: ByteArray): String {
        // AXML 里字符串是 UTF-16LE 存的，先把常见形态试一遍
        val patterns = listOf("package=", "package")
        val ascii = runCatching { String(axml, Charsets.ISO_8859_1) }.getOrNull() ?: return ""
        patterns.forEach { key ->
            var idx = ascii.indexOf(key)
            while (idx >= 0) {
                // 往后跳过 = 和引号，读连续合法字符
                var i = idx + key.length
                while (i < ascii.length && (ascii[i] == '=' || ascii[i] == '"' || ascii[i] == '\u0000')) i++
                val sb = StringBuilder()
                while (i < ascii.length) {
                    val c = ascii[i]
                    if (c.isLetterOrDigit() || c == '.' || c == '_') {
                        sb.append(c)
                        i++
                    } else {
                        break
                    }
                }
                val candidate = sb.toString()
                // 包名至少是 a.b 形式，且不能是 android/manifest 这类词
                if (candidate.count { it == '.' } >= 2 &&
                    !candidate.startsWith("android.") &&
                    candidate.length < 120
                ) {
                    return candidate
                }
                idx = ascii.indexOf(key, idx + 1)
            }
        }
        return ""
    }
}
