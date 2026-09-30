import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    // Fuzz the real production decoders, not copies.
    implementation(project(":shared"))
    implementation(project(":relay-protocol"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jazzer.api)

    // Jazzer's JUnit5 integration. In regression mode (the default here) each @FuzzTest runs the
    // committed seed corpus deterministically on the JVM; continuous libFuzzer fuzzing is opt-in via
    // the JAZZER_FUZZ environment variable and only supported on Linux/macOS drivers.
    testImplementation(libs.jazzer.junit)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
    // Keep the deterministic regression lane hermetic and quick; real fuzzing is opt-in.
    systemProperty("jazzer.instrument", "com.example.relay.**")
}

// ClusterFuzzLite runs standalone Jazzer entry points. Flatten the complete JVM runtime
// into one directory so the generated OSS-Fuzz-compatible launchers need no project-local paths.
tasks.register("prepareClusterFuzzLite") {
    dependsOn(tasks.named("jar"))
    doLast {
        val output = layout.buildDirectory.dir("clusterfuzzlite-runtime").get().asFile
        project.delete(output)
        output.mkdirs()
        configurations.runtimeClasspath.get().files.forEach { dependency ->
            project.copy {
                from(if (dependency.isDirectory) dependency else project.zipTree(dependency))
                into(output)
            }
        }
        project.copy {
            from(project.zipTree(tasks.named<Jar>("jar").get().archiveFile.get().asFile))
            into(output)
        }
    }
}
