pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ui-compose-standalone"
include(":ui-contract", ":ui-compose")
project(":ui-contract").projectDir = file("../../ui-contract")
project(":ui-compose").projectDir = file("../../ui-compose")
