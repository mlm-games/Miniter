plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.apk.dist)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.set(listOf(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi"
        ))
    }
}

android {
    namespace = "org.mlm.miniter"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.mlm.miniter"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 95
        versionName = "0.5.10"

        val enableApkSplits = (providers.gradleProperty("enableApkSplits").orNull ?: "true").toBoolean()
        val includeUniversalApk = (providers.gradleProperty("includeUniversalApk").orNull ?: "true").toBoolean()
        val targetAbi = providers.gradleProperty("targetAbi").orNull

        splits {
            abi {
                isEnable = enableApkSplits
                reset()
                if (enableApkSplits) {
                    if (targetAbi != null) include(targetAbi)
                    else include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
                isUniversalApk = includeUniversalApk && enableApkSplits
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootProject.projectDir}/release.keystore")
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isShrinkResources = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/lib/**/*"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

apkDist {
    artifactNamePrefix = "miniter"
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.kmp.settings.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.filekit.core)
    implementation(libs.filekit.dialogs.compose)
    implementation(libs.androidx.datastore.preferences.core)
}
