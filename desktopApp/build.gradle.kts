import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.kmp.settings.core)
    implementation(libs.filekit.core)
    implementation(libs.filekit.dialogs.compose)
}

compose.desktop {
    application {
        mainClass = "org.mlm.miniter.DesktopMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Miniter"
            
            // Common base version (strip leading zeros, needed for mac)
            val rawVersion = System.getenv("APP_VERSION") ?: "1.0.1"
            val strippedVersion = rawVersion.replace(Regex("^0+"), "")
            packageVersion = if (strippedVersion.isEmpty()) "1.0.1" else strippedVersion   // or just strippedVersion / rawVersion?
            
            description = "Miniter Video Editor"
            vendor = "MLM Games"

            modules("java.instrument", "jdk.security.auth", "jdk.unsupported", "jdk.httpserver")

            windows {
                iconFile.set(project.file("../packaging/icon.ico"))
                menuGroup = "Miniter"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }

            macOS {
                iconFile.set(project.file("../packaging/icon.icns"))
                bundleID = "org.mlm.miniter"
                
                // Replace with 1 when it starts with . or is empty
                packageVersion = strippedVersion.let { 
                    if (it.startsWith(".") || it.isEmpty()) "1$it" else it 
                }
            }

            linux {
                iconFile.set(project.file("../fastlane/metadata/android/en-US/images/icon.png"))
                packageName = "miniter"
                menuGroup = "AudioVideo;Video"
                appCategory = "AudioVideo"
            }
        }
    }
}
