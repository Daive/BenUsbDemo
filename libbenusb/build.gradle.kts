plugins {
    // AGP 9 内置 Kotlin 支持，无需单独应用 kotlin-android 插件
    alias(libs.plugins.android.library)
    `maven-publish`
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

    publishing {
        singleVariant("release")
    }
}

// 发布到 maven 仓库（本地 repo / JitPack）
// JitPack 构建时使用其环境变量（GROUP/ARTIFACT/VERSION），本地构建回退到 com.ben:1.0.0
publishing {
    publications {
        create<MavenPublication>("release") {
            val jpGroup = System.getenv("GROUP")
            val jpArtifact = System.getenv("ARTIFACT")
            val jpVersion = System.getenv("VERSION")
            groupId = if (jpGroup != null && jpArtifact != null) "$jpGroup.$jpArtifact" else "com.ben"
            artifactId = "libbenusb"
            version = jpVersion ?: "1.0.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
    repositories {
        maven {
            name = "localRepo"
            url = uri("${rootDir}/repo")
        }
    }
}

dependencies {
    // USB Mass Storage 基础库（对外 api 暴露，随 pom 传递）
    api("me.jahnen.libaums:core:0.10.0")
    // jnode 日志（内部实现，运行时随 pom 传递）
    implementation("log4j:log4j:1.2.17")
    implementation("de.mindpipe.android:android-logging-log4j:1.0.3")
}
