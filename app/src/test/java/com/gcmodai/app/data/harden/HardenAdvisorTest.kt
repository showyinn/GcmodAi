package com.gcmodai.app.data.harden

import com.gcmodai.app.ui.pages.harden.StringFogPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加固推荐逻辑的契约测试。
 *
 * 为什么值得测：推荐结果是直接交给用户的方案，判错的代价是
 * 「包体涨 80%」「启动变慢」「某个 SO 加载失败」这类真实问题，
 * 而且用户在安装前没法自己发现。这里把关键判断固定住。
 */
class HardenAdvisorTest {

    private fun profile(
        sizeMb: Double,
        dexCount: Int,
        soCount: Int,
        soRatio: Double = 0.0,
        abis: List<String> = listOf("arm64-v8a"),
    ) = HardenAdvisor.ApkProfile(
        sizeBytes = (sizeMb * 1048576).toLong(),
        dexCount = dexCount,
        soCount = soCount,
        soBytes = (sizeMb * 1048576 * soRatio).toLong(),
        abis = abis,
    )

    @Test
    fun `a small single-dex app can take the aggressive profile`() {
        val rec = HardenAdvisor.recommend(profile(sizeMb = 5.0, dexCount = 1, soCount = 0))

        assertEquals("aggressive", rec.xopProfile)
        assertTrue(rec.reasons.any { it.contains("规模小") })
    }

    @Test
    fun `a large app stays on balanced to protect cold start`() {
        val rec = HardenAdvisor.recommend(profile(sizeMb = 80.0, dexCount = 4, soCount = 10, soRatio = 0.4))

        assertEquals("balanced", rec.xopProfile)
        assertTrue(rec.reasons.any { it.contains("偏大") })
    }

    @Test
    fun `no native libraries means so encryption is pointless`() {
        val rec = HardenAdvisor.recommend(profile(sizeMb = 20.0, dexCount = 2, soCount = 0))

        assertEquals("off", rec.protectSoMode)
        assertTrue(rec.reasons.any { it.contains("没有业务 .so") })
    }

    @Test
    fun `so heavy apps use the safe mode with a budget`() {
        val rec = HardenAdvisor.recommend(profile(sizeMb = 70.0, dexCount = 3, soCount = 12, soRatio = 0.6))

        assertEquals("safe", rec.protectSoMode)
        assertTrue(rec.reasons.any { it.contains("占总体积") })
    }

    @Test
    fun `a few small so files can be fully encrypted`() {
        val rec = HardenAdvisor.recommend(profile(sizeMb = 15.0, dexCount = 2, soCount = 3, soRatio = 0.2))

        assertEquals("aggressive", rec.protectSoMode)
        assertTrue(rec.reasons.any { it.contains("只有 3 个") })
    }

    @Test
    fun `a single dex app without native code uses classic only`() {
        val rec = HardenAdvisor.recommend(profile(sizeMb = 6.0, dexCount = 1, soCount = 0))

        // 没有 native 库、只有单个 DEX，加壳收益低，瘦身链路就够
        assertEquals("classic", rec.engine)
    }

    @Test
    fun `a normal app combines slimming and packing`() {
        val rec = HardenAdvisor.recommend(profile(sizeMb = 25.0, dexCount = 3, soCount = 8, soRatio = 0.3))

        assertEquals("both", rec.engine)
    }

    @Test
    fun `large apps decrypt so lazily for faster cold start`() {
        val big = HardenAdvisor.recommend(profile(sizeMb = 50.0, dexCount = 4, soCount = 9, soRatio = 0.3))
        val small = HardenAdvisor.recommend(profile(sizeMb = 12.0, dexCount = 2, soCount = 4, soRatio = 0.2))

        assertEquals("lazy", big.soDecryptMode)
        assertEquals("eager", small.soDecryptMode)
    }

    @Test
    fun `multi abi is called out in the cost hint`() {
        val rec = HardenAdvisor.recommend(
            profile(sizeMb = 30.0, dexCount = 3, soCount = 6, soRatio = 0.25,
                abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        )

        assertTrue("多 ABI 必须在代价里说明", rec.costHint.contains("3 个 ABI"))
    }

    @Test
    fun `every recommendation offers three alternatives`() {
        val rec = HardenAdvisor.recommend(profile(sizeMb = 20.0, dexCount = 2, soCount = 4, soRatio = 0.2))

        assertEquals(3, rec.alternatives.size)
        assertEquals("最保守", rec.alternatives[0].label)
        assertEquals("最强", rec.alternatives[2].label)
    }

    @Test
    fun `the rendered text carries everything the ai needs`() {
        val p = profile(sizeMb = 22.0, dexCount = 3, soCount = 7, soRatio = 0.3)
        val text = HardenAdvisor.renderForAi(p, HardenAdvisor.recommend(p))

        // AI 需要：特征、结论、理由、代价、备选、可直接提交的参数
        assertTrue(text.contains("APK 特征"))
        assertTrue(text.contains("推荐方案"))
        assertTrue(text.contains("为什么这么选"))
        assertTrue(text.contains("代价"))
        assertTrue(text.contains("可选档位"))
        assertTrue(text.contains("engine="))
        assertTrue(text.contains("string_fog="))
    }

    // ---- 字符串加密预设 ----

    @Test
    fun `empty rules map to the off preset`() {
        assertEquals("off", StringFogPresets.match(emptyList()).key)
    }

    @Test
    fun `a preset's own rules map back to that preset`() {
        StringFogPresets.ALL.filter { it.key != "custom" }.forEach { preset ->
            val matched = StringFogPresets.match(preset.rules)
            assertNotNull("预设 ${preset.key} 无法反查", matched)
            assertEquals("预设 ${preset.key} 反查错位", preset.key, matched.key)
        }
    }

    @Test
    fun `hand edited rules fall through to custom`() {
        // 用户手改后不能被误判成某个预设，否则选中态会错位
        val edited = listOf("+com.example.only.*")
        assertEquals("custom", StringFogPresets.match(edited).key)
    }

    @Test
    fun `every preset explains its tradeoff`() {
        StringFogPresets.ALL.forEach { preset ->
            assertTrue("${preset.key} 缺少说明", preset.hint.isNotBlank())
        }
    }
}
