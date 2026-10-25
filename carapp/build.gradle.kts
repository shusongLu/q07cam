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
 * 2026-10-17：从 `de.dsa.ford.otaflash` 换成 `com.changan.acscenter` ——
 * 实测闭锁时车机会停掉非系统应用（recon/lock-kill-mechanism.md），
 * 而 `com.changan.acscenter` 同为 CAKJ 白名单空位，且该名字出现在
 * scenepower 的豁免名单字符串里，期望能躲过闭锁清理。可逆：改回即恢复。
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
        applicationId = "com.changan.acscenter"
        minSdk = 30
        targetSdk = 30
        versionCode = 27
        versionName = "2.8"

        ndk {
            abiFilters += "arm64-v8a"
        }

        // 长安开发者平台的凭据从 keystore.properties 注入（该文件已在 .gitignore 里），
        // 避免把 client secret 提交到公开仓库。
        // 注意官方文档：ca_dev_client_id 是纯数字，**必须在前面加 \ 强制按字符串处理**，
        // 否则会被解析成数字、SDK 的 getString 读不到。这里统一拼一个反斜杠。
        // 正式/pre 两套由 SDK 依据 Settings.Global 的 ENVI_NET 选择
        // （==1 走正式 https://huappDomain；≠1 走预发 http://pre-huapp.sda.changan.com.cn）。
        manifestPlaceholders["caClientId"] =
            "\\" + (keystoreProps.getProperty("caClientId") ?: "guest")
        manifestPlaceholders["caClientSecret"] = keystoreProps.getProperty("caClientSecret") ?: "guest"
        manifestPlaceholders["preCaClientId"] =
            "\\" + (keystoreProps.getProperty("preCaClientId")
                    ?: keystoreProps.getProperty("caClientId") ?: "guest")
        manifestPlaceholders["preCaClientSecret"] =
            keystoreProps.getProperty("preCaClientSecret")
                    ?: keystoreProps.getProperty("caClientSecret") ?: "guest"
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

// ---------------------------------------------------------------- 长安官方 SDK
/**
 * 「长安开发者平台」下发的 CaSdkManager（客户端）：
 * 通过 AIDL 绑定车机上的 com.incall.apps.sdkservice（系统服务）。
 *
 * 注意：init() 要求 AndroidManifest 里配置 ca_dev_client_id / ca_dev_client_secret
 * （平台 → 开发文档 → 接入指南 → SDK服务集成），否则直接失败。
 * 现在填的是占位值，只用来验证"链路能不能绑上"。
 */
dependencies {
    // AAR 里已经自带 libs/*.jar（fastjson / libdatacenter / navi_sdk / voice_sdk），
    // 不要再单独加一遍，否则 Duplicate class
    implementation(files("libs/CaSdkManager-0.5.52_developer_ca.aar"))
}
