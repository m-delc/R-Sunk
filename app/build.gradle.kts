plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.gradleProperty("ANDROID_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("ANDROID_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("ANDROID_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("ANDROID_RELEASE_KEY_PASSWORD").orNull

android {
    namespace = "com.rsunk.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rsunk.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 20
        versionName = "2.0.0"
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
