import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 只编译期需要 Android API；运行期由壳 APK 提供这些类。
// SDK 定位顺序与环境变量/local.properties 一致：ANDROID_HOME → ANDROID_SDK_ROOT → local.properties 的 sdk.dir
val androidSdkDir: String =
    System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
        ?: run {
            val propsFile = rootProject.file("local.properties")
            val fromProps = if (propsFile.exists()) {
                Properties().apply { propsFile.inputStream().use { load(it) } }
                    .getProperty("sdk.dir")
            } else {
                null
            }
            fromProps?.takeIf { it.isNotBlank() }
                ?: error("找不到 Android SDK：设置 ANDROID_HOME/ANDROID_SDK_ROOT，或在本仓库 local.properties 写 sdk.dir")
        }
val androidJar = file("$androidSdkDir/platforms/android-37.0/android.jar")

dependencies {
    check(androidJar.exists()) { "找不到 android.jar: $androidJar" }
    compileOnly(files(androidJar))
}
