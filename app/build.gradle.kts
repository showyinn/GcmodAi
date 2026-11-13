import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.gcmodai.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.gcmodai.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 268
        versionName = "3.9.33"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
            // 浠ｇ爜鍘嬬缉 + 璧勬簮鍘嬬缉锛歮anifest/浠ｇ爜閲屾病鐢ㄥ埌鐨勮祫婧愶紙鍥剧墖銆佸竷灞€銆佸瓧绗︿覆锛夌洿鎺ヤ笉鎵撳寘
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
    }
    // 鎸夊垎鍙戞笭閬撳尯鍒嗭細github 鏄?GitHub Release / 姣忔棩鏋勫缓鐨?APK锛宲lay 鏄笂鏋?Google Play 鐨?AAB
    // 涓や釜娓犻亾鍏辩敤鍚屼竴涓?applicationId锛孭lay 鐗堜笉鑳借嚜鏇存柊锛岀浉鍏冲叆鍙ｉ€氳繃 BuildConfig.IS_PLAY_STORE 鍏抽棴
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "IS_PLAY_STORE", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_STORE", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
        // 鍙繚鐣欎富骞茶瑷€锛氬涓€濂楄瑷€灏卞涓€浠?strings/plurals/arrays锛?
        // 鏈鐩栫殑璇█鑷姩鍥炶惤鍒伴粯璁わ紙鑻辨枃锛変笌涓枃锛屼笉浼氬嚭鐜扮┖鐧界晫闈?
        localeFilters += listOf("en", "zh", "zh-rTW")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // BouncyCastle 鐨?Picnic 鍚庨噺瀛愮鍚嶆煡鎵捐〃锛堢害 1.2MB锛夛紝JSch 鐢ㄤ笉鍒?
            excludes += "org/bouncycastle/pqc/crypto/picnic/**"
            // 渚濊禆搴撳杩涙潵浣嗚繍琛屾椂鐢ㄤ笉鍒扮殑鍏冩暟鎹細鍙湁鏋勫缓鏈熶环鍊?
            excludes += "META-INF/*.version"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/*.kotlin_builtins"
            excludes += "META-INF/versions/9/OSGI-INF/**"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "kotlin/**"
            // kotlinx-coroutines 鐨勮皟璇曟帰閽堬紝release 涓嶉渶瑕?
            excludes += "DebugProbesKt.bin"
            // 搴撹嚜甯︾殑鏂囨。/绀轰緥
            excludes += "**/*.md"
            excludes += "**/README*"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

// 涓や釜娓犻亾鐨勫惎鍔ㄨ矾寰勪竴鏍凤紝profile 鍚堝苟鍒?src/main 鍏辩敤锛屼笉鎸夊彉浣撳悇瀛樹竴浠?
baselineProfile {
    mergeIntoMain = true
}

// baselineprofile 鎻掍欢鎸?release 澶嶅埗鍑?nonMinifiedRelease 鏃跺彧鍏虫帀 isMinifyEnabled锛屽叧涓嶆帀 optimization锛?
// 涓嶆墜鍔ㄥ叧鐨勮瘽鐢熸垚鐢ㄧ殑鍖呬細琚?R8 鍐呰仈銆佸悎骞剁被锛宲rofile 閲岃涓嬬殑鏄紭鍖栧悗鐨勭被鍜屾柟娉?
androidComponents {
    finalizeDsl { extension ->
        extension.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleGithubRelease", "bundlePlayRelease")
    description = "Build GitHub APK and Play AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// kotlin-reflect 鍙敱 ktor-server-core 浼犻€掑紩鍏ワ紝鐢ㄥ埌瀹冪殑鍙湁鎸夐厤缃姞杞芥ā鍧椼€佸紑鍙戞ā寮忕儹閲嶈浇绛夌敤涓嶅埌鐨勮矾寰勩€?
// 瀹冨湪 classpath 涓婃椂 stdlib 鐨?Reflection 浼?Class.forName 鍔犺浇 ReflectionFactoryImpl锛孯8 鍥犳淇濈暀鏁翠釜瀹炵幇锛堢害 0.7MB dex锛?
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// Local JVM tests need the desktop native library instead of the Android AAR.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(libs.quickjs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view) {
        // terminal-emulator 鐢变粨搴撳唴鐨?:terminal 妯″潡鎻愪緵, 涓嶅啀寮曞叆涓婃父 aar
        exclude(group = "com.termux.termux-app", module = "terminal-emulator")
    }
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // EXIF orientation for picked images
    implementation(libs.androidx.exifinterface)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur and glass)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)
    implementation(libs.haze.glass)
    implementation(libs.haze.glass.material3)


    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // zxing + CameraX (qrcode scanner)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // media3 (video playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // icons
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/re-ovo/bettertext
    implementation(platform(libs.bettertext.bom))
    implementation(libs.bettertext.text)
    implementation(libs.bettertext.markdown)
    implementation(libs.bettertext.latex)
    implementation(libs.bettertext.highlight)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding 鈥?routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":mediagen"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":ui"))
    implementation(project(":workspace"))
    implementation(project(":terminal"))
    implementation(project(":oauth"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Crashlytics 会把混淆映射上传到 firebasecrashlyticssymbols.googleapis.com，
// 国内链路必然连接超时并让整轮 assemble 失败（APK 其实早就打好了）。
// 产物不依赖这次上传，本地 mapping.txt 仍会生成，所以直接关掉上传任务。
tasks.matching { it.name.startsWith("uploadCrashlyticsMappingFile") }.configureEach {
    enabled = false
}
