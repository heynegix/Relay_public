import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
    if (isMac) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    // Desktop target: verify shared Compose UI on Windows/Linux/Mac without a phone.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.maplibre.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.maplibre.compose)
            }
        }
    }
}

android {
    namespace = "com.example.relay.cmp"
    compileSdk = 36
    // Keep the multiplatform Android target on the same known-good SDK used
    // by the main app. Some developer machines also contain a broken 35.x
    // build-tools directory, which AGP may otherwise select implicitly.
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.example.relay.cmp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-ios-bridge"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.example.relay.cmp.MainKt"
        nativeDistributions {
            // DMG requires MAJOR>=1; MSI/DEB used on Windows/Linux package tasks.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Relay"
            packageVersion = "1.0.0"
        }
    }
}
