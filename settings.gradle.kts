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

rootProject.name = "synesthesia-android"

// :core — the shared Rust core, built for Android, and its generated Kotlin.
// :app  — the application.
include(":core", ":app")
