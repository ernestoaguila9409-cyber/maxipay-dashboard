pluginManagement {
    repositories {
        // Full Google Maven (not content-filtered) so Android/Firebase plugins and metadata resolve reliably.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    // Allow subprojects to declare repositories too (some IDE/Gradle setups resolve Firebase more reliably).
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "My Application"
include(":app")
include(":kds")
