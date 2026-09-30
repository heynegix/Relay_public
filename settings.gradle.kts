pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// AGP 9.3.0 still declares several obsolete transitive build-tool versions. Keep the resolved
// plugin and project classpaths on maintained releases so a vulnerable toolchain cannot enter
// either the build or the dependency-verification metadata.
val safeDependencyVersions = mapOf(
    "org.apache.commons:commons-lang3" to "3.20.0",
    "org.apache.httpcomponents:httpclient" to "4.5.14",
    "org.apache.httpcomponents:httpcomponents-client" to "4.5.14",
    "org.apache.httpcomponents:httpmime" to "4.5.14",
    "org.bitbucket.b_c:jose4j" to "0.9.6",
    "org.bouncycastle:bcpkix-jdk18on" to "1.85",
    "org.bouncycastle:bcprov-jdk18on" to "1.85",
    "org.bouncycastle:bcutil-jdk18on" to "1.85",
    "org.jdom:jdom2" to "2.0.6.1",
)

gradle.beforeProject {
    buildscript.configurations.configureEach {
        resolutionStrategy.eachDependency {
            safeDependencyVersions["${requested.group}:${requested.name}"]?.let { useVersion(it) }
        }
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            safeDependencyVersions["${requested.group}:${requested.name}"]?.let { useVersion(it) }
        }
    }
}

rootProject.name = "Relay"
include(":app")
include(":relay-protocol")
include(":pc-gateway")
include(":shared")
include(":composeApp")
include(":broker")
include(":fuzz-jvm")
