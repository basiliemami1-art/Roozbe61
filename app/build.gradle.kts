import java.io.FileOutputStream
import java.net.URL

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gozar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gozar.app"
        // 26 keeps adaptive icons and modern VpnService behaviour available without
        // shipping legacy raster icon sets; it still covers ~98% of active devices.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en", "fa")
    }

    signingConfigs {
        create("release") {
            // CI injects a keystore via env vars; falls back to the debug key locally.
            val storePath = System.getenv("KEYSTORE_PATH")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // Each ABI carries ~94 MB of native core (60 MB sing-box + 34 MB
            // Xray), so a universal APK would be roughly 380 MB uncompressed.
            // Per-ABI outputs keep the download to one architecture's worth.
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Native proxy cores. Both are produced/fetched by `:app:fetchCores` (see below)
    // and by the CI workflow; they are intentionally not committed to the repo.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    // AppCompat backports per-app locales (AppCompatDelegate.setApplicationLocales).
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}

/**
 * Downloads the Xray core AAR. The sing-box core (libbox.aar) has no official prebuilt
 * release, so CI compiles it from source with gomobile — see .github/workflows/build.yml.
 */
val xrayCoreVersion = "v26.7.31"

tasks.register("fetchCores") {
    group = "gozar"
    description = "Downloads prebuilt native proxy cores into app/libs."
    val libsDir = file("libs")
    val xrayAar = File(libsDir, "libv2ray.aar")
    outputs.file(xrayAar)
    doLast {
        libsDir.mkdirs()
        if (xrayAar.exists() && xrayAar.length() > 0) {
            logger.lifecycle("libv2ray.aar already present, skipping.")
        } else {
            val url =
                "https://github.com/2dust/AndroidLibXrayLite/releases/download/$xrayCoreVersion/libv2ray.aar"
            logger.lifecycle("Downloading $url")
            URL(url).openStream().use { input ->
                FileOutputStream(xrayAar).use { output -> input.copyTo(output) }
            }
            logger.lifecycle("Saved ${xrayAar.absolutePath} (${xrayAar.length()} bytes)")
        }
        val singBox = File(libsDir, "libbox.aar")
        if (!singBox.exists()) {
            logger.warn(
                "libbox.aar is missing. Build it with:\n" +
                    "  git clone --depth 1 -b v1.13.15 https://github.com/SagerNet/sing-box\n" +
                    "  cd sing-box && make lib_install && make lib_android\n" +
                    "then copy libbox.aar into app/libs/"
            )
        }
    }
}
