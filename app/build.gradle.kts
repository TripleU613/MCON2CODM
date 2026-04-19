plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tripleu.mcon2codm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tripleu.mcon2codm"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
        ndk { abiFilters += "arm64-v8a" }
    }

    // androidx.graphics:graphics-path ships a libandroidx.graphics.path.so
    // that isn't 16KB aligned and we don't actually use (it's only needed for
    // Path#asAndroidPath interop which we never invoke). Drop it.
    androidResources {
        noCompress += "libmcon_bridge.so"
    }

    // Release signing — values come from env vars / CI secrets.
    // Locally, leave them unset and use a debug build.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (System.getenv("KEYSTORE_PATH").isNullOrBlank())
                signingConfigs.getByName("debug")
            else
                signingConfigs.getByName("release")
        }
        debug {
            // Also minify debug so user's installed APK is small
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // AGP + zipalign -p 16 will align .so LOAD segments for 16KB page
        // devices if we extract them (legacy packaging). Set useLegacyPackaging
        // to true so our mcon_bridge binary is extracted to the app's native
        // lib dir where the shell user can exec it.
        jniLibs.useLegacyPackaging = true
        // Don't strip our bundled executable (the aarch64 clang build).
        jniLibs.keepDebugSymbols += "**/libmcon_bridge.so"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Shizuku — runs commands as shell user (uid 2000) without root.
    // User pairs Shizuku once via ADB; then our app uses its binder service.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // libadb-android — embed an ADB client so this APK can pair with the
    // device's own wireless debugging service and run commands as shell user.
    implementation("io.github.muntashirakon:libadb-android:3.1.1")
    implementation("io.github.muntashirakon:sun-security-android:1.1")
    // External Conscrypt — required on Android 14+/16 where the internal
    // com.android.org.conscrypt.Conscrypt lacks the exportKeyingMaterial
    // signature libadb-android reflects on. With this on the classpath,
    // libadb-android's SslUtils.getSslContext switches to org.conscrypt.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// --- 16KB page-size compatibility ---
// Strip the androidx.graphics.path native library (we don't use any API that
// needs it) and ensure all remaining .so files + APK alignment target 16KB.
androidComponents {
    onVariants(selector().all()) { variant ->
        // Exclude the unused graphics-path .so from the APK build.
        variant.packaging.jniLibs.excludes.add("**/libandroidx.graphics.path.so")
    }
}
