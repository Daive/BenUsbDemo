import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 打包时间戳版本号（每次打包自动生成，格式：yyyyMMdd.HHmm）
val buildTime = SimpleDateFormat("yyyyMMdd.HHmm", Locale.US).format(Date())
// versionCode 按打包日期生成（yyyyMMdd）
val buildDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()

android {
    namespace = "com.ben.usbdemo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ben.usbdemo"
        minSdk = 24
        targetSdk = 36
        versionCode = buildDate
        versionName = "1.0.$buildTime"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    // USB 读取库（本地 maven 坐标，依赖自动传递，app 无需关心底层依赖）
    implementation("com.ben:libbenusb:1.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}