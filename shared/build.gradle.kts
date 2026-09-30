import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS / Kotlin-Native targets only resolve on macOS hosts.
    val isMac = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
    if (isMac) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        ).forEach { target ->
            target.binaries.framework {
                baseName = "RelayShared"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json.client)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.junit)
        }
    }
    // Named "iosMain" only when iOS targets exist (macOS hosts).
    sourceSets.matching { it.name == "iosMain" }.configureEach {
        dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.example.relay.shared"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 23 }
}
