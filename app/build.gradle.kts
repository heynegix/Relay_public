import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.net.URI
import java.util.Collections
import java.util.zip.ZipFile
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val relayReleaseStoreFile = providers.gradleProperty("relay.release.store.file").orNull
val relayReleaseStorePassword = providers.gradleProperty("relay.release.store.password").orNull
val relayReleaseKeyAlias = providers.gradleProperty("relay.release.key.alias").orNull
val relayReleaseKeyPassword = providers.gradleProperty("relay.release.key.password").orNull
val relayReleaseSigningConfigured = listOf(
    relayReleaseStoreFile,
    relayReleaseStorePassword,
    relayReleaseKeyAlias,
    relayReleaseKeyPassword,
).all { !it.isNullOrBlank() }
val relayBrokerEndpoint = providers.gradleProperty("relay.broker.endpoint").orNull?.trim()?.trimEnd('/') ?: ""
// The shelter/gateway id a mobile-only phone uses to fetch the recipient manifest from the Broker.
// Defaults to the standalone launcher's default Gateway id so a baked APK works out of the box.
val relayShelterId = providers.gradleProperty("relay.shelter.id").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: "development-pc-gateway"

if (relayBrokerEndpoint.isNotEmpty()) {
    val brokerUri = runCatching { URI(relayBrokerEndpoint) }.getOrElse {
        throw GradleException("relay.broker.endpoint must be a valid HTTPS URL")
    }
    require(brokerUri.scheme.equals("https", ignoreCase = true) && !brokerUri.host.isNullOrBlank() && brokerUri.userInfo == null) {
        "relay.broker.endpoint must be an HTTPS URL without embedded credentials"
    }
}

android {
    namespace = "com.example.relay"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.relay"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Production/pilot variants deliberately ship no trust anchor until the operator supplies
        // an approved public bundle. A variant may opt into a public-only asset by overriding this.
        buildConfigField("String", "REGIONAL_ROOT_BUNDLE_ASSET", "\"\"")
        // A test Root is never a release/pilot trust anchor, even if a misconfigured asset is
        // accidentally packaged. Debug/local development may explicitly allow TEST fixtures.
        buildConfigField("String", "REGIONAL_ROOT_BUNDLE_ENVIRONMENTS", "\"PILOT,PRODUCTION\"")
        // The public Broker location is build-time configuration, never editable in a release
        // APK. An empty value deliberately disables mobile-network Broker delivery.
        resValue("string", "broker_endpoint", relayBrokerEndpoint)
        // The shelter id used to fetch the recipient manifest from the Broker over mobile data.
        resValue("string", "rescue_shelter_id", relayShelterId)
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    defaultConfig {
        // Deterministic build metadata: prefer SOURCE_DATE_EPOCH, then GITHUB_SHA/CI env,
        // then git commit timestamp for reproducibility. Fall back to current time only for debug.
        val gitCommit: String = providers.environmentVariable("GITHUB_SHA").orNull
            ?: runCatching {
                providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
            }.getOrElse { "unknown" }

        val buildTimestamp: String = run {
            // SOURCE_DATE_EPOCH is the standard reproducibility variable
            val sourceDateEpoch = providers.environmentVariable("SOURCE_DATE_EPOCH").orNull
            if (!sourceDateEpoch.isNullOrBlank()) {
                return@run Instant.ofEpochSecond(sourceDateEpoch.toLong())
                    .atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }
            // Next: use git commit timestamp for release reproducibility
            val gitTimestamp = runCatching {
                providers.exec { commandLine("git", "log", "-1", "--format=%cI") }.standardOutput.asText.get().trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (gitTimestamp != null) return@run gitTimestamp
            // Fallback for non-git source archives or debug
            Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }

        // Allow explicit version overrides from properties (CI, release tags)
        val overrideVersionName = providers.gradleProperty("relay.version.name").orNull
        val overrideVersionCode = providers.gradleProperty("relay.version.code").orNull
        if (!overrideVersionName.isNullOrBlank()) versionName = overrideVersionName
        if (!overrideVersionCode.isNullOrBlank()) versionCode = overrideVersionCode.toInt()

        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
    }

    val releaseSigningConfig = if (relayReleaseSigningConfigured) {
        signingConfigs.create("organizationRelease") {
            storeFile = file(requireNotNull(relayReleaseStoreFile))
            storePassword = requireNotNull(relayReleaseStorePassword)
            keyAlias = requireNotNull(relayReleaseKeyAlias)
            keyPassword = requireNotNull(relayReleaseKeyPassword)
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            // Optional local developer asset; it is absent by default and must never contain a
            // private key. Release-derived variants inherit the empty production setting.
            buildConfigField("String", "REGIONAL_ROOT_BUNDLE_ASSET", "\"relay-regional-roots.debug.json\"")
            buildConfigField("String", "REGIONAL_ROOT_BUNDLE_ENVIRONMENTS", "\"TEST,PILOT\"")
            buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "true")
        }
        getByName("release") {
            buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "false")
            signingConfig = releaseSigningConfig
        }
        create("localDev") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "true")
        }
        create("pilotRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "ALLOW_HTTP_GATEWAY", "false")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        // Core library desugaring backports java.util.concurrent / java.util default methods
        // (ConcurrentHashMap.newKeySet, Map.computeIfAbsent/remove(k,v), etc.) so the transport,
        // nearby, and rescue-session code runs on minSdk 23 devices instead of throwing NoSuchMethodError.
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Instrumented tests never depend on animation timing; disabling animations keeps
        // headless emulator runs deterministic.
        animationsDisabled = true
        // Reproducible instrumentation environment (API 36).
        // Gradle provisions and tears down the emulator, so the task runs identically on a
        // developer workstation and in CI. Run with:
        //   ./gradlew :app:mediumPhoneApi36DebugAndroidTest
        managedDevices {
            localDevices {
                create("mediumPhoneApi36") {
                    device = "Medium Phone"
                    apiLevel = 36
                    systemImageSource = "google_apis_playstore"
                }
                // AGP 9 build-managed devices require API 27 or newer. minSdk remains 23;
                // API 23 coverage is retained for physical-device validation instead.
                create("mediumPhoneApi27") {
                    device = "Medium Phone"
                    apiLevel = 27
                    systemImageSource = "google"
                }
            }
        }
    }

    sourceSets {
        // localDev shares the debug-only cleartext policy and test-only manifest components.
        getByName("localDev") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            res.srcDir("src/debug/res")
        }
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// A local unsigned/locally signed assembleRelease is useful for engineering inspection, but is
// never a formal release. CI turns this explicit gate on and fails before publication when the
// organization signing material has not been supplied through the approved secret store.
val requireOrganizationReleaseSigning = providers.gradleProperty("relay.require.release.signing")
    .orNull
    ?.equals("true", ignoreCase = true) == true
tasks.matching { it.name == "assembleRelease" || it.name == "assemblePilotRelease" }.configureEach {
    doFirst {
        if (requireOrganizationReleaseSigning && !relayReleaseSigningConfigured) {
            throw GradleException(
                "Organization Android signing material is required for a formal release; do not publish an unsigned or locally signed APK.",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * Packaging guardrail for Phase 0A. A future approved public Root may legitimately be packaged,
 * but test fixtures and private Root material must never cross into release-derived APKs.
 */
tasks.register("verifyNoTestTrustArtifactsInReleaseApks") {
    dependsOn("assembleRelease", "assemblePilotRelease")
    doLast {
        val apks = fileTree(layout.buildDirectory.dir("outputs/apk").get().asFile) {
            include("release/*.apk", "pilotRelease/*.apk")
        }.files
        check(apks.size >= 2) { "Expected release and pilotRelease APKs for trust-artifact inspection" }
        apks.forEach { apk ->
            ZipFile(apk).use { archive ->
                Collections.list(archive.entries())
                    .asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.startsWith("assets/") }
                    .forEach { entry ->
                        check(!entry.name.contains("test", ignoreCase = true)) {
                            "Test asset must not be packaged in ${apk.name}: ${entry.name}"
                        }
                        if (entry.size in 1L..(512L * 1024)) {
                            val content = archive.getInputStream(entry).use { input ->
                                input.readBytes().toString(Charsets.UTF_8)
                            }
                            check(!content.contains("TEST ONLY", ignoreCase = true)) {
                                "Test trust material must not be packaged in ${apk.name}: ${entry.name}"
                            }
                            check(!content.contains("rootSigningPrivateKey") &&
                                !content.contains("privateKey", ignoreCase = true)
                            ) {
                                "Private Root material must not be packaged in ${apk.name}: ${entry.name}"
                            }
                        }
                    }
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":relay-protocol"))
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.google.play.services.nearby)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.app.cash.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}