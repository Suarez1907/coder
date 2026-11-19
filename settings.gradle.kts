pluginManagement {
    repositories {
        google()
        mavenCentral()
        // Maven Central yanıt vermezse Google'ın aynası kullanılır.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Maven Central yanıt vermezse Google'ın aynası kullanılır.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "Coder"
include(":app")
