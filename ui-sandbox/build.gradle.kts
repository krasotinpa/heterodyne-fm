import com.android.build.api.artifact.SingleArtifact
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "fm.heterodyne.ui.sandbox"
    compileSdk = libs.versions.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.build.tools.get()
    defaultConfig {
        applicationId = "fm.heterodyne.ui.sandbox"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.compile.sdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    }
    // The screens use Android Canvas/PathMeasure, not AndroidX's native path iterator.
    packaging.jniLibs.excludes += "**/libandroidx.graphics.path.so"
}

kotlin { jvmToolchain(libs.versions.jdk.get().toInt()) }

dependencies {
    implementation(project(":ui-compose"))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":ui-contract")))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.runner)
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val apk = variant.artifacts.get(SingleArtifact.APK)
    tasks.register("checkSandboxIsolation") {
        inputs.file(manifest)
        inputs.dir(apk)
        doLast {
            val runtime = configurations.getByName("debugRuntimeClasspath").incoming
            val components = runtime.resolutionResult.allComponents.map { it.id }.toSet() +
                runtime.artifactView { attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar") }
                    .artifacts.artifacts.map { it.id.componentIdentifier }
            val allowedProjects = setOf(":ui-sandbox", ":ui-compose", ":ui-contract")
            val libraryGroups = setOf(
                "androidx.activity", "androidx.annotation", "androidx.arch.core", "androidx.autofill",
                "androidx.collection", "androidx.concurrent", "androidx.core", "androidx.customview",
                "androidx.documentfile", "androidx.dynamicanimation", "androidx.emoji2", "androidx.graphics",
                "androidx.interpolator", "androidx.legacy", "androidx.lifecycle", "androidx.loader",
                "androidx.localbroadcastmanager", "androidx.print", "androidx.profileinstaller", "androidx.savedstate",
                "androidx.startup", "androidx.tracing", "androidx.transition", "androidx.versionedparcelable",
                "androidx.window", "com.google.guava", "org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jspecify",
            )
            for (id in components) {
                check(when (id) {
                    is ProjectComponentIdentifier -> id.projectPath in allowedProjects && id.build.buildPath == ":"
                    is ModuleComponentIdentifier -> id.group in libraryGroups || id.group == "androidx.compose" ||
                        id.group.startsWith("androidx.compose.")
                    else -> false
                }) { "Unexpected Sandbox runtime dependency: $id" }
            }
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest.get().asFile)
            val elements = xml.getElementsByTagName("*")
            val packageName = xml.documentElement.getAttribute("package")
            check(packageName == "fm.heterodyne.ui.sandbox")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as Element
                val name = element.getAttribute("android:name")
                check(!name.contains("usb", ignoreCase = true) && !name.startsWith("fm.heterodyne.app.")) { name }
                when {
                    element.tagName == "application" -> check(name.isEmpty()) { "Unexpected Application: $name" }
                    element.tagName.startsWith("uses-permission") ->
                        check(name == "$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") { name }
                    element.tagName == "service" -> error("Unexpected service: $name")
                }
            }
            ZipFile(apk.get().asFile.listFiles()!!.single { it.extension == "apk" }).use { archive ->
                check(archive.entries().asSequence().none { it.name.endsWith(".so") }) { "Native library in Sandbox APK" }
            }
            println("Sandbox isolation OK: UI runtime, merged manifest, APK without native libraries")
        }
    }
}
