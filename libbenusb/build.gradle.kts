plugins {
    // AGP 9 内置 Kotlin 支持，无需单独应用 kotlin-android 插件
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ben.libbenusb"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // USB Mass Storage 基础库（对外 api 暴露）
    api("me.jahnen.libaums:core:0.10.0")
    // jnode 日志（内部实现）
    implementation("log4j:log4j:1.2.17")
    implementation("de.mindpipe.android:android-logging-log4j:1.0.3")
}
