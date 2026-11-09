import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.starwake.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.afloiddev.spaceexploration"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6"
    }

    // One committed dev key for both variants, so debug and release builds install over each other.
    // NOT a production/upload key.
    signingConfigs {
        create("dev") {
            storeFile = rootProject.file("keystore/dev.keystore")
            storePassword = "starwake-dev"
            keyAlias = "dev"
            keyPassword = "starwake-dev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Kotlin metadata only needed by kotlin-reflect / the compiler, never at runtime here.
            excludes += setOf(
                "kotlin/**",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/versions/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    // The dependency-metadata signing block is only useful to Play; keep the sideloaded APK minimal.
    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
}
