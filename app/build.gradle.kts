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
        versionCode = 2
        versionName = "1.1.0"
        resourceConfigurations += listOf("en", "fa")
    }

    signingConfigs {
        create("release") {
            // A private keystore injected by CI wins. Without one, the committed
            // development key is used so that the signature stays constant across
            // builds — Android refuses to update an app whose signature changed,
            // and a rotating key means every update wipes the user's servers and
            // settings. See signing/README.md for the trade-off and how to move
            // to a private key.
            val injectedStore = System.getenv("KEYSTORE_PATH")?.let { file(it) }
            if (injectedStore != null && injectedStore.exists()) {
                storeFile = injectedStore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("signing/dev-signing.jks")
                storePassword = "gozar-dev-key"
                keyAlias = "gozar"
                keyPassword = "gozar-dev-key"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    // Parsers, source list, probing and config generation — shared verbatim
    // with the desktop app so the two cannot drift.
    implementation(project(":shared"))

    // The sing-box core, compiled by CI and intentionally not committed.
    // Only one gomobile-generated AAR may be present: each bundles its own copy
    // of the `go.*` runtime, so a second one collides on `go.Seq`.
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

tasks.register("checkCores") {
    group = "gozar"
    description = "Verifies the native proxy core is present in app/libs."
    doLast {
        val singBox = File(file("libs"), "libbox.aar")
        if (!singBox.exists()) {
            logger.warn(
                "libbox.aar is missing. sing-box publishes no prebuilt Android\n" +
                    "artifact, so build it from the pinned tag:\n" +
                    "  git clone --depth 1 -b v1.13.15 https://github.com/SagerNet/sing-box\n" +
                    "  cd sing-box && make lib_install && make lib_android\n" +
                    "then copy libbox.aar into app/libs/"
            )
        } else {
            logger.lifecycle("libbox.aar present (${singBox.length()} bytes)")
        }
    }
}
