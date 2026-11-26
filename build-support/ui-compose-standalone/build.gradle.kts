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
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))
}
