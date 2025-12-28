pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        maven(
            url = "https://maven.mozilla.org/maven2/"
        )
    }
}

rootProject.name = "super-video-downloader"
include(":app")
