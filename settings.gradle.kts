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

rootProject.name = "Otaku-Stream"

include(":app")
include(":core:common")
include(":core:ui")
include(":core:network")
include(":core:player")
include(":core:database")
include(":core:sources-api")
include(":core:torrent")
include(":core:download")
include(":sources:example")
include(":feature:sources")
include(":core:sources-scripting")
include(":core:sources-stremio")
include(":core:sources-mangayomi")
include(":feature:library")
include(":feature:tracking")
