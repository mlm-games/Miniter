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
    implementation(libs.javacv.platform)
}

compose.desktop {
    application {
        mainClass = "org.mlm.miniter.DesktopMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Miniter"
            packageVersion = System.getenv("APP_VERSION") ?: "1.0.1"
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
