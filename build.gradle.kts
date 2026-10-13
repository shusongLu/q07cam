plugins {
    // Android 端：AGP 9 内置 Kotlin，不能再应用 org.jetbrains.kotlin.android
    id("com.android.application") version "9.4.1" apply false
    // 纯 JVM 模块（core / plugin）用 Kotlin JVM 插件
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}
