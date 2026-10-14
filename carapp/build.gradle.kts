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
 * Q07 车机正式 App（安装版）。
 *
 * applicationId 使用 CAKJ 安装校验白名单里的空位名，
 * 这样 adb install 能放行（见 recon/install-bypass.md）。
 *
 * 运行期能力（均已在车上实测）：
 *  - SOA 厂商 HIDL：普通 App 就能拿到（soatest 已验证）
 *  - 侧摄像头 H.265 RTSP：用厂商 libffmpeg.so
 *  - 悬浮窗：TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW appop
 */
android {
    namespace = "com.lushu.q07car"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.dsa.ford.otaflash"
        minSdk = 30
        targetSdk = 30
        versionCode = 20
        versionName = "2.1"

        ndk {
            abiFilters += "arm64-v8a"
        }
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// ---------------------------------------------------------------- 厂商文件
// 厂商 HIDL Java 类（recon/libs/soabridge/*.dex），构建时从 recon 拷贝
val copyVendorDex = tasks.register<Copy>("copyVendorDex") {
    from(rootProject.file("recon/libs/soabridge")) { include("*.dex") }
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(copyVendorDex)
}
