plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 차량 앱과 모바일 앱이 함께 쓰는 조건 판정·모의 할인권·로컬 저장·공통 UI
android {
    namespace = "com.silverpass.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        // 동기화 서버 주소. 다른 서버로 시험하려면 gradle.properties나 -Psilverpass.syncUrl=… 로 바꾼다.
        val syncUrl = (project.findProperty("silverpass.syncUrl") as String?) ?: "https://silverpass-server.kodori.dev"
        buildConfigField("String", "SYNC_BASE_URL", "\"$syncUrl\"")
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
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.activity.compose)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)
    api(libs.androidx.material3)
    debugApi(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
