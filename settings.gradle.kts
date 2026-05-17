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
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        flatDir { dirs("maxi-mobile/libs") }
    }
}

rootProject.name = "My Application"
include(":app")
include(":kds")
include(":maxi-mobile")
include(":shared")
