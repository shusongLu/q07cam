import java.util.Properties

plugins {
    id("com.android.application")
}

// 复用仓库根的签名配置
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists() && !keystoreProps.getProperty("storeFile").isNullOrBlank()

/**
 * SOA 可达性测试包�? *
 * 目的：验证「普通安装的 App（非 root、非 system）能不能读厂�?SOA」�? * applicationId 用白名单空位名，adbs install 才放行（�?recon/install-bypass.md）�? */
android {
    namespace = "com.lushu.soatest"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.dsa.ford.otaflash"
        minSdk = 30
        targetSdk = 30
        versionCode = 4
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// 厂商 dex（recon/libs/soabridge/*.dex，从 SoaBridgeTransit.apk 抽出来的 HIDL Java 类）
// 构建时自动拷进 assets，避免在仓库里重复存 19MB。
val copyVendorDex = tasks.register<Copy>("copyVendorDex") {
    from(rootProject.file("recon/libs/soabridge")) { include("*.dex") }
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyVendorDex) }
