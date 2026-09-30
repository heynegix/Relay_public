plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.pitest)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}

pitest {
    pitestVersion.set(libs.versions.pitest.get())
    targetClasses.set(setOf("com.example.relay.gateway.protocol.*"))
    // Default would mirror targetClasses and silently skip GatewayProtocolTest
    // (package com.example.relay.gateway), producing false NO_COVERAGE.
    targetTests.set(setOf("com.example.relay.gateway.*"))
    // kotlinx.serialization compiler-generated serializers are not hand-written
    // code; their descriptor/decode-loop mutants are noise for this gate.
    excludedClasses.set(setOf("*\$\$serializer"))
    threads.set(4)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    // Suppress junk mutations in Kotlin compiler-generated null checks.
    avoidCallsTo.set(setOf("kotlin.jvm.internal"))
    // Fail-closed gate. Verified locally: 113/114 killed (99%); the single
    // survivor is an equivalent mutant (NoOpGatewayMessageSigner.keyId already
    // returns "", PIT replaces it with ""), so 100 is not reachable.
    mutationThreshold.set(95)
}
