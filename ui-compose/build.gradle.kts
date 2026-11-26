plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "fm.heterodyne.ui.compose"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()
    defaultConfig { minSdk = libs.versions.min.sdk.get().toInt() }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    }
}

kotlin { jvmToolchain(libs.versions.jdk.get().toInt()) }

dependencies {
    api(project(":ui-contract"))
    api(platform(libs.compose.bom))
    api("androidx.compose.ui:ui")
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.compose.ui.test.manifest)
}
