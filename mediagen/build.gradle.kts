import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("gcmodai.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.gcmodai.mediagen"
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.core.ktx)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
