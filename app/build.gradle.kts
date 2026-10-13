plugins {
    id("com.android.application")
}

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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 壳只依赖接口模块；业务逻辑在可热更的 plugin.dex 里
    implementation(project(":core"))
}
