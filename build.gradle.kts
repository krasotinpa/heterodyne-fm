buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 provides built-in Kotlin; explicitly upgrade its KGP dependency.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
