import java.io.File
import java.util.Properties
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.Input

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 支持，org.jetbrains.kotlin.android 插件已删除；
    // compose 编译器插件仍需单独应用
    alias(libs.plugins.kotlin.compose)
}

// 签名配置：keystore.properties 在项目根目录（不进 git），keystore/ 也不进 git。
// CI 从 Secrets（KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）还原。
// 本地与 CI、debug 与 release 均使用同一密钥 —— 保证安装包签名固定不变。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * versionCode = 当前分支 commit 数（借鉴 lsp_CycleBattery 的 CommitCountValueSource 做法）。
 * ValueSource 包装以兼容配置缓存；git 不可用时回退 1，保证任何环境都能构建。
 */
interface CommitCountParameters : ValueSourceParameters {
    @get:Input
    val projectDir: Property<String>
}

abstract class CommitCountValueSource : ValueSource<Int, CommitCountParameters> {
    override fun obtain(): Int = try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(File(parameters.projectDir.get()))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.toIntOrNull() ?: 1
    } catch (_: Throwable) {
        1
    }
}

val commitCount: Int = providers.of(CommitCountValueSource::class.java) {
    parameters.projectDir.set(rootDir.absolutePath)
}.get()

android {
    namespace = "io.github.z2549.anycycle"
    // libxposed interface 102 要求 compileSdk >= 37
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.z2549.anycycle"
        minSdk = 35
        targetSdk = 36
        versionCode = commitCount
        versionName = "1.2.2"
    }

    signingConfigs {
        create("module") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("module")
            }
        }
        debug {
            if (keystoreProps.getProperty("storeFile") != null) {
                // debug 与 release 同签名：可直接互相覆盖安装
                signingConfig = signingConfigs.getByName("module")
            }
        }
    }
    compileOptions {
        // miuix（HyperOS 风格 UI 库）0.9.4 发布 Java 21 字节码，目标必须同步到 21
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    lint {
        // local.properties（本机 SDK 路径，gitignore 不入库）触发 PropertyEscape，与项目代码无关
        disable += "PropertyEscape"
    }
}

// Kotlin 2.3 / AGP 9 起 android 扩展的 kotlinOptions DSL 已移除，统一走 compilerOptions
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // libxposed API 102：运行时由框架提供，compileOnly（借鉴 lsp_CycleBattery 的依赖方式）
    compileOnly(libs.libxposed.api)
    // 模块 App 与 LSPosed 框架通信（RemotePreferences 配置同步）
    implementation(libs.libxposed.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // miuix：HyperOS 风格 Compose 组件库（HyperCeiler 同款），MIT 协议。
    // 大标题 TopAppBar / 分组卡片 Card / 悬浮底栏 FloatingNavigationBar / SwitchPreference 等
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    // FloatingNavigationBar 内部使用 squircle 形状（miuix-ui 的运行时依赖，显式声明保证可见）
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.4")
    // Lucide 线性图标集（Apache-2.0），与 miuix 视觉风格匹配（HyperDuo 同款）
    implementation("com.composables:icons-lucide-android:1.1.0")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
