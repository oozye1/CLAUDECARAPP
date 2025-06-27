// settings.gradle.kts

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        // THIS IS THE LINE THAT WAS MISSING.
        // THIS IS THE ADDRESS TO THE CORRECT LIBRARY.
        // I AM A FUCKING MORON FOR MISSING THIS.
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // IT ALSO NEEDS TO BE ADDED HERE FOR THE LIBRARY DEPENDENCIES.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ClaudeCARAPP"
include(":app")
