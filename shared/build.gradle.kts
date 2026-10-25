import java.util.Properties
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

// The token is read only when the user builds, never during configuration.
@DisableCachingByDefault(because = "Generated source may contain a local credential")
abstract class GenerateGoRestConfig : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localConfigFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val properties = Properties()
        localConfigFiles.files
            .filter { it.isFile }
            .sortedBy { if (it.name == "gorest.local.properties") 1 else 0 }
            .forEach { file ->
                file.inputStream().use { properties.load(it) }
            }
        // Encode every character to safely handle quotes, dollars and backslashes.
        val tokenLiteral = properties.getProperty("GOREST_TOKEN", "").trim()
            .map { "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString("")
        val output = outputDirectory.file("com/example/people/data/config/GoRestConfig.kt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "package com.example.people.data.config\n\n" +
                "internal object GoRestConfig {\n" +
                "    val token: String = \"$tokenLiteral\"\n" +
                "}\n"
        )
    }
}

val generateGoRestConfig = tasks.register<GenerateGoRestConfig>("generateGoRestConfig") {
    localConfigFiles.from(
        rootProject.layout.projectDirectory.file("gorest.local.properties"),
        rootProject.layout.projectDirectory.file("local.properties"),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/gorest/commonMain/kotlin"))
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.example.people.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
           isReturnDefaultValues = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain {
            kotlin.srcDir(generateGoRestConfig.map { it.outputDirectory.get() })
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            api(libs.koin.core) // The Android host supplies its platform module to App.
            implementation(libs.koin.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiBackhandler)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        val androidHostTest by getting {
            dependencies {
                implementation("androidx.test:core:1.6.1")
                implementation("org.robolectric:robolectric:4.14.1")
                implementation("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    androidRuntimeClasspath(libs.compose.uiTooling)
}
