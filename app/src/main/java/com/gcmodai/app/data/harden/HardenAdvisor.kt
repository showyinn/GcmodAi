package com.gcmodai.app.data.harden

/**
 * 加固方案的智能推荐。
 *
 * 为什么需要它：加固参数有十几个，普通用户不知道「空壳力度」和「SO 加密」
 * 该怎么配，更不知道配错的代价（启动变慢、某个功能崩、包大 80%）。
 * 这个推荐器根据 APK 的客观特征给出**一个具体方案 + 理由**，
 * 再让 AI 把方案讲给用户、让用户确认或改。
 *
 * 判定只用能从 APK 里直接读出来的事实：体积、DEX 数、SO 数、
 * 是否含 native 库、包名。不猜用户意图。
 */
object HardenAdvisor {

    /** 从 APK 里能读到的事实，用于推荐 */
    data class ApkProfile(
        val sizeBytes: Long = 0,
        val dexCount: Int = 0,
        val soCount: Int = 0,
        val soBytes: Long = 0,
        val abis: List<String> = emptyList(),
        val packageName: String = "",
        /** 是否为 Kotlin/Compose 类应用（DEX 里 com/ + androidx/ 占比高） */
        val looksModern: Boolean = false,
    ) {
        val sizeMb: Double get() = sizeBytes / 1048576.0
        val soRatio: Double get() = if (sizeBytes > 0) soBytes.toDouble() / sizeBytes else 0.0
    }

    /** 推荐结果：一套可直接提交的参数 + 给用户看的理由 */
    data class Recommendation(
        val engine: String,
        val xopProfile: String,
        val protectSoMode: String,
        val soDecryptMode: String,
        val encryptAssets: Boolean,
        val resProtect: Boolean,
        val raspAction: String,
        val stringFogPreset: String,
        /** 一句话结论，例如「推荐：加壳引擎 · 平衡 · SO 安全」 */
        val title: String,
        /** 为什么这么选，逐条 */
        val reasons: List<String>,
        /** 预计代价 */
        val costHint: String,
        /** 保守 / 均衡 / 激进 三档，供用户改主意 */
        val alternatives: List<Alternative>,
    )

    data class Alternative(
        val label: String,
        val description: String,
        val engine: String,
        val xopProfile: String,
        val protectSoMode: String,
    )

    /**
     * 给一个 [ApkProfile] 出方案。
     *
     * 决策逻辑（按优先级）：
     * 1. 体积大 + SO 占比高 → SO 用 safe 档并给预算，避免包体爆炸
     * 2. 体积小 + DEX 少 → 可以直接上激进档，收益明显
     * 3. 有 native 库 → 开启 SO 加密；纯 Java 应用开了也没用
     * 4. 现代 Kotlin/Compose 应用 → 空壳用 balanced，启动最快
     * 5. 多 ABI → 提醒耗时翻倍
     */
    fun recommend(profile: ApkProfile): Recommendation {
        val reasons = mutableListOf<String>()

        // ---- 空壳力度 ----
        val xopProfile = when {
            profile.sizeMb < 8 && profile.dexCount <= 2 -> {
                reasons += "包体 ${"%.1f".format(profile.sizeMb)}MB、DEX ${profile.dexCount} 个，规模小，空壳可以直接上「激进」拿到更高防护"
                "aggressive"
            }
            profile.sizeMb > 60 -> {
                reasons += "包体 ${"%.0f".format(profile.sizeMb)}MB 偏大，空壳用「平衡」，避免抽空过多类导致冷启动变慢"
                "balanced"
            }
            profile.looksModern -> {
                reasons += "看起来是 Kotlin/Compose 应用（androidx 占比高），空壳用「平衡」：只抽业务包，保留 AOT 友好"
                "balanced"
            }
            else -> {
                reasons += "常规规模，空壳用「平衡」作为起点，实测无问题再往上调"
                "balanced"
            }
        }

        // ---- SO 加密 ----
        val protectSo = when {
            profile.soCount == 0 -> {
                reasons += "这个包没有业务 .so，SO 加密开了也没东西可加密，直接关闭"
                "off"
            }
            profile.soRatio > 0.5 || profile.sizeMb > 60 -> {
                reasons += "SO 占总体积 ${"%.0f".format(profile.soRatio * 100)}%，用「安全」档并限制体积预算，防止加密后包体膨胀"
                "safe"
            }
            profile.soCount <= 5 -> {
                reasons += "只有 ${profile.soCount} 个 .so，可以上「激进」档把它们的 .text 全加密"
                "aggressive"
            }
            else -> {
                reasons += "${profile.soCount} 个 .so，用「安全」档（自动跳过重定位补丁过的库）最稳"
                "safe"
            }
        }

        // ---- SQL 解密时机 ----
        val soDecrypt = if (profile.sizeMb > 40) {
            reasons += "包体较大，SO 解密用「延迟」：首次加载时才解密，冷启动更快"
            "lazy"
        } else {
            "eager"
        }

        // ---- 引擎 ----
        val engine = if (protectSo == "off" && profile.dexCount <= 1) {
            reasons += "单 DEX + 无 native 库，用「瘦身链路」就够：去调试信息 + 字符串加密 + 重签"
            "classic"
        } else {
            reasons += "用「两者叠加」：先瘦身去掉调试信息与冗余符号，再加壳保护，两层互补"
            "both"
        }

        // ---- 字符串加密 ----
        val fogPreset = if (profile.sizeMb > 80) {
            reasons += "包体很大，字符串加密只覆盖业务包，避免构建时间过长"
            "app"
        } else {
            "app"
        }

        // ---- 代价提示 ----
        val cost = buildString {
            append("预计耗时 ")
            append(
                when {
                    engine == "both" && profile.sizeMb > 40 -> "3–5 分钟"
                    engine == "both" -> "1–3 分钟"
                    else -> "40–90 秒"
                }
            )
            if (profile.abis.size > 1) {
                append("；包内有 ${profile.abis.size} 个 ABI（${profile.abis.joinToString("/")}），每个 ABI 都要单独处理")
            }
            if (protectSo != "off") append("；SO 加密会让包体增大约 10–30%")
        }

        val title = buildString {
            append("推荐：")
            append(
                when (engine) {
                    "both" -> "瘦身 + 加壳"
                    "xop" -> "仅加壳"
                    else -> "仅瘦身"
                }
            )
            append(" · ")
            append(
                when (xopProfile) {
                    "aggressive" -> "激进"
                    "max" -> "最大"
                    "industry" -> "行业"
                    "perf" -> "性能"
                    else -> "平衡"
                }
            )
            if (protectSo != "off") {
                append(" · SO ")
                append(
                    when (protectSo) {
                        "aggressive" -> "激进"
                        "max" -> "最大"
                        else -> "安全"
                    }
                )
            }
        }

        return Recommendation(
            engine = engine,
            xopProfile = xopProfile,
            protectSoMode = protectSo,
            soDecryptMode = soDecrypt,
            encryptAssets = profile.sizeMb < 40,
            resProtect = profile.sizeMb < 40,
            raspAction = "report",
            stringFogPreset = fogPreset,
            title = title,
            reasons = reasons,
            costHint = cost,
            alternatives = listOf(
                Alternative(
                    label = "最保守",
                    description = "只去调试信息 + 重签，几乎不会出问题，适合线上包紧急加固",
                    engine = "classic",
                    xopProfile = "balanced",
                    protectSoMode = "off",
                ),
                Alternative(
                    label = "均衡（推荐）",
                    description = "瘦身 + 加壳，兼顾防护与兼容性",
                    engine = engine,
                    xopProfile = xopProfile,
                    protectSoMode = protectSo,
                ),
                Alternative(
                    label = "最强",
                    description = "空壳最大 + SO 最大 + 全部加密，防护最强但包体会明显变大、启动变慢",
                    engine = "both",
                    xopProfile = "max",
                    protectSoMode = "max",
                ),
            ),
        )
    }

    /**
     * 把推荐结果渲染成给 AI 看的文本。
     *
     * 这一个字符串就是 AI 回答用户的原料：它包含结论、理由、代价、备选，
     * AI 不需要自己再推理参数，只要把它讲清楚并等用户确认。
     */
    fun renderForAi(profile: ApkProfile, rec: Recommendation): String = buildString {
        appendLine("【APK 特征】")
        appendLine("- 体积：${"%.1f".format(profile.sizeMb)} MB")
        appendLine("- DEX：${profile.dexCount} 个")
        appendLine("- 业务 SO：${profile.soCount} 个（占总体积 ${"%.0f".format(profile.soRatio * 100)}%）")
        if (profile.abis.isNotEmpty()) appendLine("- ABI：${profile.abis.joinToString(" / ")}")
        if (profile.packageName.isNotBlank()) appendLine("- 包名：${profile.packageName}")
        appendLine()
        appendLine("【推荐方案】${rec.title}")
        appendLine()
        appendLine("【为什么这么选】")
        rec.reasons.forEach { appendLine("- $it") }
        appendLine()
        appendLine("【代价】${rec.costHint}")
        appendLine()
        appendLine("【可选档位】")
        rec.alternatives.forEach { appendLine("- ${it.label}：${it.description}") }
        appendLine()
        appendLine("【可直接提交的参数】")
        appendLine("engine=${rec.engine} xop_profile=${rec.xopProfile} " +
            "protect_so_mode=${rec.protectSoMode} so_decrypt_mode=${rec.soDecryptMode} " +
            "encrypt_assets=${rec.encryptAssets} res_protect=${rec.resProtect} " +
            "string_fog=${rec.stringFogPreset} rasp_action=${rec.raspAction}")
    }
}
