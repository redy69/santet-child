plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val copySantetWallpaper by tasks.registering {
    val wallpaperSource = rootProject.file("docs/wallpaper.jpg")
    val wallpaperTarget = layout.projectDirectory.file("src/main/res/drawable-nodpi/santethp_wallpaper.jpg")
    inputs.file(wallpaperSource)
    outputs.file(wallpaperTarget)
    doLast {
        if (!wallpaperSource.exists()) throw GradleException("File docs/wallpaper.jpg tidak ditemukan")
        wallpaperTarget.asFile.parentFile.mkdirs()
        wallpaperSource.copyTo(wallpaperTarget.asFile, overwrite = true)
    }
}
tasks.named("preBuild").configure { dependsOn(copySantetWallpaper) }

val appVersionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
val meteredTurnApiKey = System.getenv("METERED_TURN_API_KEY")
    ?: providers.gradleProperty("meteredTurnApiKey").orElse("").get()

android {
    buildFeatures { buildConfig = true }
    namespace = "com.santethp"

    // Build an ARM64 test APK plus a universal fallback for size comparison.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
    compileSdk = 35
    flavorDimensions += "role"
    productFlavors {
        create("ortu") {
            dimension = "role"
            applicationId = "com.santethp.parent"
            buildConfigField("String", "APP_ROLE", "\"parent\"")
            resValue("string", "app_name", "SantetHP Ortu")
        }
        create("anak") {
            dimension = "role"
            applicationId = "com.santethp"
            buildConfigField("String", "APP_ROLE", "\"child\"")
            resValue("string", "app_name", "SantetHP Anak")
        }
    }
    defaultConfig {
        applicationId = "com.santethp"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
        buildConfigField("String", "METERED_TURN_API_KEY", "\"\${meteredTurnApiKey}\"")
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) storeFile = file(keystorePath)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("io.livekit:livekit-android:2.30.0")
    implementation("io.livekit:livekit-android-camerax:2.30.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-auth:21.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
}
