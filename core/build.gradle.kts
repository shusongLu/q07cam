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

// 只编译期需要 Android API；运行期由壳 APK 提供这些类
val androidHome: String = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: error("ANDROID_HOME / ANDROID_SDK_ROOT 未设置")
val androidJar = file("$androidHome/platforms/android-37.0/android.jar")

dependencies {
    check(androidJar.exists()) { "找不到 android.jar: $androidJar" }
    compileOnly(files(androidJar))
}
