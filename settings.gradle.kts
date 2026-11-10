plugins {
    // Lets Gradle download a matching JDK (including a native-image capable GraalVM) when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wowfam"
