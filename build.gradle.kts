// Root build file. Plugins are declared here with `apply false` so that the
// version catalogue stays the single source of truth for plugin versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
}
