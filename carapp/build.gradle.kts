import java.util.Properties
import java.util.zip.ZipFile

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
        versionCode = 16
        versionName = "1.5"

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

// ---------------------------------------------------------------- 第三方厂商文件
// 1) 厂商 HIDL Java 类（recon/libs/soabridge/*.dex）
val copyVendorDex = tasks.register<Copy>("copyVendorDex") {
    from(rootProject.file("recon/libs/soabridge")) { include("*.dex") }
    into(layout.projectDirectory.dir("src/main/assets"))
}

// 2) 厂商 FFmpeg 播放器（侧摄像头 H.265 RTSP），从 CaEvsAppService.apk 里抽
val extractFfmpeg = tasks.register("extractFfmpeg") {
    val apkFile = rootProject.file("recon/apks-manual/com.changan.evs.appservice/base.apk")
    val outDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a").asFile
    inputs.file(apkFile)
    outputs.dir(outDir)
    doLast {
        val so = File(outDir, "libffmpeg.so")
        if (so.exists() && so.length() > 0) return@doLast
        outDir.mkdirs()
        ZipFile(apkFile).use { z ->
            val e = z.getEntry("lib/arm64-v8a/libffmpeg.so")
                ?: error("APK 里没有 lib/arm64-v8a/libffmpeg.so")
            z.getInputStream(e).use { i -> so.outputStream().use { o -> i.copyTo(o) } }
        }
        logger.lifecycle("extractFfmpeg: ${so.length() / 1024 / 1024} MB -> ${so.path}")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(copyVendorDex, extractFfmpeg)
}
