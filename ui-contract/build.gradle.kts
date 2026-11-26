import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

group = "fm.heterodyne"
version = "0.1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}

tasks.check {
    val classpaths = listOf(configurations.compileClasspath, configurations.runtimeClasspath)
    doLast {
        val allowed = setOf(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
            "org.jetbrains:annotations",
        )
        for (classpath in classpaths) {
            val incoming = classpath.get().incoming
            val resolution = incoming.resolutionResult
            val root = resolution.rootComponent.get().id
            val componentIds = resolution.allComponents.map { it.id }.toSet() +
                incoming.artifacts.artifacts.map { it.id.componentIdentifier }
            val unexpected = componentIds.filter { id ->
                id != root && (id !is ModuleComponentIdentifier || "${id.group}:${id.module}" !in allowed)
            }
            check(unexpected.isEmpty()) {
                "Unexpected contract dependencies on ${classpath.name}: ${unexpected.map { it.displayName }}"
            }
        }
    }
}
