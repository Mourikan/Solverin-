pluginManagement {
    repositories {
        google()
        // Google's official Maven Central mirror, resolved before repo.maven.apache.org (Gradle binds each artifact to the
        // repository that served its metadata, so a trailing fallback would never be used for 404 artifacts).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's official Maven Central mirror, resolved before repo.maven.apache.org (Gradle binds each artifact to the
        // repository that served its metadata, so a trailing fallback would never be used for 404 artifacts).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Solverin"
include(":engine", ":app")
