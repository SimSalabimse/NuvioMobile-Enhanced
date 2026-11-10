plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.sentry.android.gradle) apply false
}

tasks.register<Exec>("checkRepoText") {
    group = "verification"
    description = "Fails on a personal name, a hardcoded TheIntroDB key, or an addon URL. A fix commit with no test file is a warning."
    workingDir = rootDir
    commandLine("python3", "scripts/check-repo-text.py")
}
