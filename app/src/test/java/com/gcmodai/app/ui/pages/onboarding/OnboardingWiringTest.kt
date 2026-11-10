package com.gcmodai.app.ui.pages.onboarding

import com.gcmodai.app.AppScope
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新手引导的 Koin 注入契约。
 *
 * 这组断言存在的原因是一次真实的线上闪退：
 * `OnboardingVM` 的作用域参数一度写成 `CoroutineScope`，而 AppModule 只注册了
 * 具体类型 `single { AppScope() }`，没有 `CoroutineScope` 的定义。
 * 编译器不会报错，Koin 也只在**真正解析时**抛 NoDefinitionFoundException ——
 * 结果是「一进引导页就闪退」，而引导页正是新用户的启动页。
 *
 * 所以这里用反射把参数类型钉住：谁把它改回 `CoroutineScope`，单测立刻红。
 * 反射只看类型、不实例化，因此不需要 Android 运行环境。
 */
class OnboardingWiringTest {

    private val ctorParams: Set<Class<*>> =
        OnboardingVM::class.java.declaredConstructors
            .map { it.parameterTypes.toSet() }
            .reduce { a, b -> a + b }

    @Test
    fun `scope parameter must be the concrete AppScope that Koin registers`() {
        assertTrue(
            "scope 参数必须是具体的 AppScope（AppModule 只注册了这个类型）",
            ctorParams.contains(AppScope::class.java),
        )
    }

    @Test
    fun `scope parameter must not be the unregistered CoroutineScope type`() {
        assertFalse(
            "Koin 没有注册 CoroutineScope，声明成它会抛 NoDefinitionFoundException 导致闪退",
            ctorParams.contains(CoroutineScope::class.java),
        )
    }

    @Test
    fun `all injected dependencies are types registered in appModule`() {
        // 与 AppModule 里的注册一一对应：Context 由 androidContext() 提供
        val expected = setOf(
            android.content.Context::class.java,
            com.gcmodai.app.data.user.UserRepository::class.java,
            com.gcmodai.app.data.datastore.SettingsStore::class.java,
            com.gcmodai.app.data.user.OfficialProviderManager::class.java,
            AppScope::class.java,
        )
        assertTrue(
            "构造函数出现了未在 appModule 注册的依赖类型: ${ctorParams - expected}",
            expected.containsAll(ctorParams),
        )
    }
}
