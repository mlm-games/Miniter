@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.rustUniffi)
}

rustUniffi {
    libraryName.set("miniter_ffi")
    cargoNdkExtraArgs.set(listOf("--link-libcxx-shared"))
}

val cargoBuildWasm = tasks.named("cargoBuildWasm")
val rustDirDefault = rootProject.layout.projectDirectory.dir("rust")

@org.gradle.work.DisableCachingByDefault(because = "Invokes external Rust tooling")
abstract class GenerateRustWasmBindingsTask @javax.inject.Inject constructor(
    private val execOps: org.gradle.process.ExecOperations,
) : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    abstract val rustProjectDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun run() {
        execOps.exec {
            workingDir = rustProjectDir.get().asFile
            commandLine("cargo", "build", "--target", "wasm32-unknown-unknown", "--release")
        }

        outputDir.get().asFile.mkdirs()

        execOps.exec {
            workingDir = rustProjectDir.get().asFile
            commandLine(
                "wasm-bindgen",
                "target/wasm32-unknown-unknown/release/miniter_ffi.wasm",
                "--target", "bundler",
                "--out-dir", outputDir.get().asFile.absolutePath,
                "--out-name", "miniter_ffi",
                "--typescript",
            )
        }
    }
}

val generateRustWasmBindings = tasks.register<GenerateRustWasmBindingsTask>("generateRustWasmBindings") {
    rustProjectDir.set(rustDirDefault)
    outputDir.set(layout.buildDirectory.dir("generated/rustWasmBindings"))
}

@org.gradle.work.DisableCachingByDefault(because = "Parses generated TypeScript declarations")
abstract class GenerateWasmExternsTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val dtsFile: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val outputKt: org.gradle.api.file.RegularFileProperty

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val content = dtsFile.get().asFile.readText()

        val classMatch = Regex(
            """export class WasmEditorHandle\s*\{([^}]+(?:\{[^}]*\}[^}]*)*)\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(content)

        if (classMatch == null) {
            outputKt.get().asFile.parentFile?.mkdirs()
            outputKt.get().asFile.writeText("// Could not find WasmEditorHandle class in d.ts")
            return
        }

        val classBody = classMatch.groupValues[1]

        val sb = StringBuilder()
        sb.appendLine("// AUTO-GENERATED from ${dtsFile.get().asFile.name} — do not edit")
        sb.appendLine("@file:JsModule(\"./wasm/miniter_ffi.js\")")
        sb.appendLine()
        sb.appendLine("package org.mlm.miniter.rust")
        sb.appendLine()
        sb.appendLine("import kotlin.js.JsName")
        sb.appendLine()

        val methods = extractMethods(classBody)
        val staticMethods = methods.filter { it.isStatic }
        val instanceMethods = methods.filter { !it.isStatic && it.name != "free" }
        val ctorParams = Regex("""constructor\s*\(([^)]*)\)\s*;""")
            .find(classBody)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val ctorKt = convertParams(ctorParams)

        if (ctorKt.isBlank()) {
            sb.appendLine("external class WasmEditorHandle {")
        } else {
            sb.appendLine("external class WasmEditorHandle($ctorKt) {")
        }

        if (staticMethods.isNotEmpty()) {
            sb.appendLine("    companion object {")
            for (m in staticMethods) {
                val params = convertParams(m.params)
                val ret = convertReturnType(m.returnType)
                sb.appendLine("        fun ${m.name}($params): $ret")
            }
            sb.appendLine("    }")
            sb.appendLine()
        }

        sb.appendLine("    fun free()")
        for (m in instanceMethods) {
            val params = convertParams(m.params)
            val ret = convertReturnType(m.returnType)
            sb.appendLine("    fun ${m.name}($params): $ret")
        }

        sb.appendLine("}")
        sb.appendLine()
        sb.appendLine("@JsName(\"probeAudio\")")
        sb.appendLine("external fun wasmProbeAudio(path: String): String")
        sb.appendLine("@JsName(\"extractWaveform\")")
        sb.appendLine("external fun wasmExtractWaveform(path: String, buckets: Double): String")
        sb.appendLine("@JsName(\"probeVideo\")")
        sb.appendLine("external fun wasmProbeVideo(path: String): String")
        sb.appendLine("@JsName(\"extractThumbnail\")")
        sb.appendLine("external fun wasmExtractThumbnail(path: String, targetUs: Double): String")
        sb.appendLine("@JsName(\"extractThumbnails\")")
        sb.appendLine("external fun wasmExtractThumbnails(path: String, count: Double, durationUs: Double): String")
        sb.appendLine("@JsName(\"registerFile\")")
        sb.appendLine("external fun wasmRegisterFile(path: String, bytesBase64: String, extension: String?): Boolean")
        sb.appendLine("@JsName(\"exportProjectJson\")")
        sb.appendLine("external fun wasmExportProjectJson(projectJson: String, outputPath: String): Boolean")
        sb.appendLine("@JsName(\"exportProgress\")")
        sb.appendLine("external fun wasmExportProgress(): Double")
        sb.appendLine("@JsName(\"cancelExport\")")
        sb.appendLine("external fun wasmCancelExport()")

        outputKt.get().asFile.parentFile?.mkdirs()
        outputKt.get().asFile.writeText(sb.toString())
    }

    companion object {
        data class TsMethod(
            val isStatic: Boolean,
            val name: String,
            val params: String,
            val returnType: String,
        )

        fun extractMethods(classBody: String): List<TsMethod> {
            val methods = mutableListOf<TsMethod>()
            val text = classBody.replace("\n", " ").replace(Regex("\\s+"), " ")
            var i = 0
            val methodHead = Regex("""(static\s+)?(\w+)\s*\(""")

            while (i < text.length) {
                val head = methodHead.find(text, i) ?: break
                if (head.range.first < i) {
                    i++
                    continue
                }
                val isStatic = head.groupValues[1].isNotBlank()
                val name = head.groupValues[2]

                var depth = 1
                var j = head.range.last + 1
                while (j < text.length && depth > 0) {
                    if (text[j] == '(') depth++
                    if (text[j] == ')') depth--
                    j++
                }
                if (depth != 0) {
                    i = j
                    continue
                }

                val params = text.substring(head.range.last + 1, j - 1).trim()
                val afterParens = text.substring(j).trimStart()
                val retMatch = Regex("""^:\s*([^;]+);""").find(afterParens)

                if (retMatch != null) {
                    methods.add(
                        TsMethod(
                            isStatic = isStatic,
                            name = name,
                            params = params,
                            returnType = retMatch.groupValues[1].trim(),
                        )
                    )
                    i = j + retMatch.range.last + 1
                } else {
                    i = j
                }
            }

            return methods
        }

        fun splitBalanced(text: String, delimiter: Char): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0

            for (idx in text.indices) {
                when (text[idx]) {
                    '(', '<', '{', '[' -> depth++
                    ')', '>', '}', ']' -> depth--
                    delimiter -> if (depth == 0) {
                        parts.add(text.substring(start, idx))
                        start = idx + 1
                    }
                }
            }
            parts.add(text.substring(start))

            return parts
        }

        fun convertType(ts: String): String {
            val t = ts.trim()
            return when {
                t == "string" -> "String"
                t == "number" -> "Double"
                t == "boolean" -> "Boolean"
                t == "void" -> "Unit"
                t.startsWith("Promise<") -> "Any"
                t.startsWith("Result<") -> "JsAny"
                t.startsWith("WasmEditorHandle") -> "WasmEditorHandle"
                t.endsWith("[]") -> "JsAny"
                t.contains("|") -> {
                    val parts = t.split("|").map { it.trim() }.toSet()
                    val nonNull = parts - setOf("null", "undefined")
                    val nullable = parts.contains("null") || parts.contains("undefined")
                    val base = nonNull.singleOrNull()
                    when (base) {
                        "string" -> if (nullable) "String?" else "String"
                        "number" -> if (nullable) "Double?" else "Double"
                        "boolean" -> if (nullable) "Boolean?" else "Boolean"
                        "void" -> "Unit"
                        else -> "JsAny"
                    }
                }
                else -> "JsAny"
            }
        }

        fun convertReturnType(ts: String): String {
            val t = ts.trim()
            return when {
                t.startsWith("Result<") -> {
                    val inner = t.removePrefix("Result<").removeSuffix(">")
                    convertType(inner)
                }
                t == "WasmEditorHandle" -> "WasmEditorHandle"
                else -> convertType(t)
            }
        }

        fun convertParams(ts: String): String {
            if (ts.isBlank()) return ""

            return splitBalanced(ts, ',').joinToString(", ") { part ->
                val p = part.trim()
                val colonIdx = p.indexOf(':')
                if (colonIdx < 0) {
                    "param: JsAny"
                } else {
                    val rawName = p.substring(0, colonIdx).trim().removeSuffix("?")
                    val rawType = p.substring(colonIdx + 1).trim()
                    "$rawName: ${convertType(rawType)}"
                }
            }
        }
    }
}

val generateWasmExterns = tasks.register<GenerateWasmExternsTask>("generateWasmExterns") {
    dependsOn(generateRustWasmBindings)
    dtsFile.set(generateRustWasmBindings.flatMap { it.outputDir.file("miniter_ffi.d.ts") })
    outputKt.set(
        layout.buildDirectory.file(
            "generated/wasmJs/kotlin/org/mlm/miniter/rust/MiniterWasmExterns.generated.kt"
        )
    )
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.set(
            listOf(
                "androidx.compose.material3.ExperimentalMaterial3Api",
                "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "androidx.compose.foundation.ExperimentalFoundationApi",
                "androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "kotlin.js.ExperimentalWasmJsInterop",

                )
        )
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "org.mlm.miniter.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }
    }

    jvm()

    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "miniter-shared.js"
            }
        }
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui.tooling.preview)

            implementation(libs.navigation3.ui)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.adaptive.navigation3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kmp.settings.ui.compose)
            implementation(libs.kmp.settings.core)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.navigation3)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.compose.media.player)
            implementation(libs.okio)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.koin.android)
            implementation(libs.okio)
            //noinspection UseTomlInstead
            implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.net.jna)
        }

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kmp.settings.core)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.navigation3.ui)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.adaptive.navigation3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kmp.settings.ui.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.koin.compose.navigation3)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
        }

        named("wasmJsMain") {
            kotlin.exclude("org/mlm/miniter/ffi/**")
            kotlin.srcDir(layout.buildDirectory.dir("generated/wasmJs/kotlin"))
        }
    }

    wasmJs {
        compilations.all {
            compileTaskProvider.configure {
                dependsOn(cargoBuildWasm)
                dependsOn(generateWasmExterns)
            }
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", libs.kmp.settings.ksp)
    add("kspAndroid", libs.kmp.settings.ksp)
    add("kspJvm", libs.kmp.settings.ksp)
    add("kspWasmJs", libs.kmp.settings.ksp)
}

tasks.matching { it.name == "genUniFFIWasm" }.configureEach {
    enabled = false
}

tasks.matching { it.name.startsWith("kspWasmJs") || it.name == "kspKotlinWasmJs" }.configureEach {
    dependsOn(generateWasmExterns)
}

compose.resources {
    publicResClass = true
}
