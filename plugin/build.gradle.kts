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

// SDK 定位顺序：ANDROID_HOME → ANDROID_SDK_ROOT → local.properties 的 sdk.dir（与 AGP 一致）
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
// AAOS 的 android.car 不在标准 android.jar 里，单独放在 optional/ 下；运行期由车机 BOOTCLASSPATH 提供
val androidCarJar = file("$androidSdkDir/platforms/android-37.0/optional/android.car.jar")

dependencies {
    // core 只编译期引用：运行时由壳 APK 提供，绝不能打进插件
    compileOnly(project(":core"))
    check(androidJar.exists()) { "找不到 android.jar: $androidJar" }
    check(androidCarJar.exists()) { "找不到 android.car.jar: $androidCarJar" }
    compileOnly(files(androidJar))
    compileOnly(files(androidCarJar))
}

tasks.jar {
    archiveFileName.set("plugin.jar")
}
