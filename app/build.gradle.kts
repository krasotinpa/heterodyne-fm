plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun gitOutput(vararg args: String): String {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.waitFor() == 0) { "Git command failed" }
    return output
}

val commitLabel = try {
    val sha = gitOutput("rev-parse", "--verify", "HEAD").take(7)
    check(sha.isNotEmpty()) { "Git HEAD is empty" }
    val dirty = gitOutput("status", "--porcelain", "--untracked-files=all").isNotEmpty()
    sha + if (dirty) "-dirty" else ""
} catch (_: Exception) {
    "unknown"
}

android {
    namespace = "fm.heterodyne.app"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "fm.heterodyne.app"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0+$commitLabel"
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    lint {
        // ADR 0002 deliberately targets API 30 for sideloading on the head unit.
        // This check enforces Google Play publication policy.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.bouncycastle)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
