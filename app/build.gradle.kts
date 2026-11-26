import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing key details live in keystore.properties (git-ignored), not in this file.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.projecty.app"
    // GeckoView 160 must be compiled against Android API 37.2. This does not change which phones
    // can install the app (that's minSdk below).
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "dev.projecty.app"
        minSdk = 26 // GeckoView requires Android 8.0+
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.0"
    }

    // GeckoView ships a native engine per CPU type. Building one APK per type keeps each
    // download around 70-90 MB instead of one ~250 MB "universal" APK.
    //   arm64-v8a   -> almost all phones from the last ~8 years
    //   armeabi-v7a -> old/cheap 32-bit phones
    //   x86_64      -> the Android emulator on your PC
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // By default Android's packager silently drops asset folders whose name starts with "_".
    // uBlock Origin needs its "_locales" folder, so keep the default rules minus that one.
    androidResources {
        ignoreAssetsPatterns.clear()
        ignoreAssetsPatterns.addAll(
            listOf("!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~")
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Store GeckoView's native libraries compressed inside the APK. Most of the APK is the engine's
    // native code, so this roughly halves the download. Trade-off: Android unpacks them at install
    // time, so installed size on the phone is about the same as before.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            // Code shrinking (R8) is off: our own code is tiny, and the savings aren't worth the
            // risk of breaking GeckoView. The native libraries above are what make the APK big.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    // Firefox's browser engine as a library (MPL-2.0). Bump this every ~4 weeks for security fixes.
    implementation("org.mozilla.geckoview:geckoview:160.0.20261116130455")
    // Back-button handling (OnBackPressedDispatcher).
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Edge-to-edge / immersive full-screen helpers (WindowInsetsControllerCompat).
    implementation("androidx.core:core-ktx:1.16.0")

    testImplementation("junit:junit:4.13.2")
}
