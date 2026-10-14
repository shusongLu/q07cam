import java.util.Properties

plugins {
    id("com.android.application")
}

// release 签名信息从仓库根的 keystore.properties 读取（该文件不入库，见 .gitignore）
// 没提供时，release 构建产出未签名 APK —— 用于渠道分发前请务必填好
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists() && !keystoreProps.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "com.lushu.q07battery"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lushu.q07battery"
        minSdk = 30
        targetSdk = 30
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 渠道校验器偏保守：v1/v2/v3 全开，兼容性最好
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 渠道会拒绝 debuggable 的包
            isDebuggable = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // 车辆信号走厂商 HAL，需要零 car 权限；不再需要按权限切变体

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 保持 30 以对齐车机（Android 11）；
        // 关掉只针对 Google Play 上架策略的 release lint 门槛
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // 壳只依赖接口模块；业务逻辑在可热更的 plugin.dex 里
    implementation(project(":core"))
}
