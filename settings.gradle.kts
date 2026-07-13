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
include(":core:player")
include(":core:database")
include(":core:sources-api")
include(":sources:example")
include(":feature:sources")
include(":core:sources-scripting")
include(":core:sources-stremio")
include(":feature:library")
include(":feature:tracking")
