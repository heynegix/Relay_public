plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.kover)
}

application { mainClass.set("com.example.relay.broker.MainKt") }
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.netty.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.sqlite.jdbc)
    implementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.toxiproxy)
    testImplementation(libs.toxiproxy.java)
}
