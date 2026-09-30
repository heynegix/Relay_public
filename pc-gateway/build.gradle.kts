plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.kover)
}

application { mainClass.set("com.example.relay.pcgateway.MainKt") }
kotlin { jvmToolchain(17) }

/**
 * Offline-only entry point for public-root/directory preparation. It is separate from `run`, does
 * not start the Gateway, and must be executed from a non-Git offline key directory.
 */
tasks.register<JavaExec>("regionalTrustProvisioning") {
    group = "relay security"
    description = "Run the offline regional trust provisioning CLI (never normal Gateway startup)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.relay.pcgateway.rescue.provisioning.RegionalTrustProvisioningCliKt")
}

/**
 * Test-only launcher for the Playwright staff-console browser E2E. It boots the real operator
 * console (static SPA + real gatewayModule routes) with a seeded admin and rescue requests.
 * Configuration comes entirely from the environment set by staff-console-e2e/playwright.config.ts.
 */
tasks.register<JavaExec>("staffConsoleE2eServer") {
    group = "relay verification"
    description = "Launch the real staff console for the Playwright browser E2E (test-only harness)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.example.relay.pcgateway.e2e.StaffConsoleE2eServer")
}

dependencies {
    implementation(project(":relay-protocol"))
    implementation(project(":shared"))
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json.client)
    implementation(libs.sqlite.jdbc)
    // Windows DPAPI (CryptProtectData) for at-rest rescue key protection; inert on other platforms.
    implementation(libs.jna)
    implementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    // Test-only: architecture rules over all JVM modules (this test classpath sees them all).
    testImplementation(libs.archunit.junit4)
    // Test-only: exercise the real Broker HTTP server in the end-to-end intake flow.
    testImplementation(project(":broker"))
}
