import java.io.File
import java.util.Properties
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.Input

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        versionName = "1.2.1"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // libxposed API 102：运行时由框架提供，compileOnly（借鉴 lsp_CycleBattery 的依赖方式）
    compileOnly(libs.libxposed.api)
    // 模块 App 与 LSPosed 框架通信（RemotePreferences 配置同步）
    implementation(libs.libxposed.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
