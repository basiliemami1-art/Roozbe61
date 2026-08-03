// Every plugin any module uses is declared once here, with `apply false`.
// Gradle refuses a versioned plugin request from a subproject when the plugin
// is already on the build classpath from another module — which is what
// happens the moment a Kotlin/JVM module joins an Android build.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.desktop) apply false
}
