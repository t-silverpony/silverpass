import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 실버패스 모바일 앱. 일반 Android 휴대전화용.
android {
    namespace = "com.silverpass.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silverpass.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        // 네이버 지도 Client ID는 저장소 밖 파일에서 읽는다(secrets/naver-map.properties, gitignore). 없으면 지도 대신 경로 그림만 보인다.
        val mapProps = Properties().apply {
            rootProject.file("secrets/naver-map.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val mapId = mapProps.getProperty("MAP_CLIENT_ID", "")
        manifestPlaceholders["naverMapClientId"] = mapId
        buildConfigField("boolean", "HAS_NAVER_MAP", mapId.isNotBlank().toString())
        // 네이버 지도 네이티브 라이브러리가 ABI마다 20MB 넘게 커서 64비트 휴대전화(arm64)와 에뮬레이터(x86_64)만 넣는다(GitHub 100MB 제한).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.naver.map)
}
