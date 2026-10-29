plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.firstdj.hdjd"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.firstdj.hdjd"
        minSdk = 24          // Android 7.0 - ~98% of active devices, still maintainable
        targetSdk = 36       // Meets current Google Play target-API requirement
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Release signing is configured ONLY when the secrets are present (CI) or
    // keystore.properties exists locally. Nothing sensitive lives in the repo.
    val ksFile = System.getenv("HDJD_KEYSTORE_FILE")
    signingConfigs {
        if (!ksFile.isNullOrBlank() && file(ksFile).exists()) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = System.getenv("HDJD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HDJD_KEY_ALIAS")
                keyPassword = System.getenv("HDJD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""   // keep package com.firstdj.hdjd for debug too
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    lint {
        // Non-blocking so a stray lint finding never stops an APK from being produced;
        // the report is uploaded by CI. Flip to true once the first clean run is confirmed.
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
