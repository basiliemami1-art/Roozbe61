import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    // Required since Kotlin 2.0: the Compose compiler moved out of the
    // Multiplatform plugin into its own, and the desktop plugin refuses to
    // configure without it.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.gozar.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Gozar"
            packageVersion = "1.0.0"
            description = "Free internet, simply"
            vendor = "Gozar"
            copyright = "GPL-3.0"

            // The sing-box binary is dropped here by CI before packaging and
            // ends up beside the app so it can be launched as a child process.
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))

            windows {
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed so upgrades replace the installation instead of piling
                // up alongside it.
                upgradeUuid = "0f2a7d1e-3c65-4a2b-9d18-6a1f5e7c0b34"
                iconFile.set(project.file("icons/gozar.ico"))
            }
        }

        buildTypes.release.proguard {
            // The UI is reflective enough that shrinking costs more than it saves.
            isEnabled.set(false)
        }
    }
}
