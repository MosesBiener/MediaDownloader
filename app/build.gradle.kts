plugins {
    id("com.android.application")
}

android {
    namespace = "com.webary.mediadownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webary.mediadownloader"
        minSdk = 24
        targetSdk = 35
        // CI run number makes every build an upgrade over the last one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.1.$build"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Fixed key so builds from fresh CI machines can install over each other.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
}
