import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 실버패스 차량 앱. Pleos Vehicle SDK로 기어(정차/주행) 상태를 읽는다.
android {
    namespace = "com.silverpass.vehicle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silverpass.vehicle"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // release 서명: secrets/release-signing.properties(저장소 밖)가 있으면 그 키, 없으면 debug 키로 서명해 설치는 되게 한다.
    val signingProps = Properties().apply {
        rootProject.file("secrets/release-signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (signingProps.getProperty("STORE_FILE") != null) {
                storeFile = rootProject.file(signingProps.getProperty("STORE_FILE"))
                storePassword = signingProps.getProperty("STORE_PASSWORD")
                keyAlias = signingProps.getProperty("KEY_ALIAS")
                keyPassword = signingProps.getProperty("KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.vehicle.sdk)
    implementation(libs.navi.helper)
}
