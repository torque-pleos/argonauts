plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zigae.argonauts"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zigae.argonauts"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // 친구용 휴대폰 웹 주소 (QR·초대 링크)
        buildConfigField("String", "WEB_URL", "\"https://argonauts-crew.vercel.app\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "META-INF/*"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Pleos Connect SDK (Playground에서 사용 가능한 것만)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // 크루 실시간 동기화 (MQTT), 지도, QR
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
