package com.gcmodai.app.ui.pages.harden

/**
 * 字符串加密（StringFog）的规则预设。
 *
 * 为什么需要预设：规则语法是 `+com.foo.*` / `-com.foo.Bar`，
 * 让每个用户自己写正则式前缀不现实，而且写错的表现是「开了但没加密任何东西」——
 * 静默失效最难查。预设把常见取舍做成一选项，自定义留给确实需要的人。
 *
 * 每个预设都给出「加密范围」和「代价」，让用户能自己判断。
 */
internal data class StringFogPreset(
    val key: String,
    val label: String,
    /** 落到 string_fog_rules 的规则行 */
    val rules: List<String>,
    /** 一行说明：加密谁、有什么代价 */
    val hint: String,
)

internal object StringFogPresets {

    val ALL: List<StringFogPreset> = listOf(
        StringFogPreset(
            key = "off",
            label = "关闭",
            rules = emptyList(),
            hint = "不改任何字符串字面量。包体不变、启动最快，静态搜索能看到明文。",
        ),
        StringFogPreset(
            key = "app",
            label = "仅业务包",
            rules = listOf("+com.gcmodai.app.*"),
            hint = "只加密本 App 自己的类（com.gcmodai.app 包下）。命中率最高、副作用最小，推荐默认。",
        ),
        StringFogPreset(
            key = "app_plus",
            label = "业务 + 自研模块",
            rules = listOf(
                "+com.gcmodai.app.*",
                "+com.gcmodai.ai.*",
                "+com.gcmodai.workspace.*",
                "+com.gcmodai.search.*",
            ),
            hint = "把 AI SDK、工作区、搜索这几个自研模块一起加密。防护更全，构建时间略长。",
        ),
        StringFogPreset(
            key = "all_but_sdk",
            label = "全部（排除三方 SDK）",
            rules = listOf(
                "+*",
                "-com.google.*",
                "-androidx.*",
                "-kotlin.*",
                "-kotlinx.*",
                "-okhttp3.*",
                "-okio.*",
                "-org.jetbrains.*",
                "-com.google.firebase.*",
            ),
            hint = "除主流 SDK 外全部加密。防护最强，但任何依赖字符串反射的库都可能受影响，务必真机回归。",
        ),
        StringFogPreset(
            key = "custom",
            label = "自定义",
            rules = emptyList(),
            hint = "自己写规则：+ 包含、- 排除，每行一条。留空等于关闭。",
        ),
    )

    fun byKey(key: String): StringFogPreset =
        ALL.firstOrNull { it.key == key } ?: ALL[1]

    /**
     * 反向识别：给一组规则，判断它属于哪个预设。
     *
     * 认不出来一律返回 custom —— 用户手改过规则时必须落到自定义，
     * 否则选中态会停在某个预设上，让人以为「我选的还是业务包」，
     * 而实际提交的是改过的规则。这个错位是最难自查的一类问题。
     */
    fun match(rules: List<String>): StringFogPreset {
        val normalized = rules.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return byKey("off")
        return ALL.firstOrNull { preset ->
            preset.key != "custom" && preset.rules.map { it.trim() } == normalized
        } ?: byKey("custom")
    }
}
