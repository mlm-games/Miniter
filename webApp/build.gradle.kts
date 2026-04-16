@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val webAppGeneratedWasmResources = layout.buildDirectory.dir("generated/wasmJsApp/wasm")
val sharedGeneratedWasmBindings = project(":shared").layout.buildDirectory.dir("generated/rustWasmBindings")

val syncWasmAppResources = tasks.register<Sync>("syncWasmAppResources") {
    dependsOn(project(":shared").tasks.named("generateRustWasmBindings"))
    from(sharedGeneratedWasmBindings)
    into(webAppGeneratedWasmResources)
}

kotlin {
    wasmJs {
        outputModuleName = "miniter"
        browser {
            commonWebpackConfig {
                outputFileName = "miniter.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kmp.settings.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(syncWasmAppResources)
    from(webAppGeneratedWasmResources) {
        into("wasm")
    }
}
