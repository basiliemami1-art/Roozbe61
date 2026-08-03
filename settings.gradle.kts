pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "Gozar"

// :shared holds every piece of logic that is not tied to a platform — the
// parsers, the source list, latency probing and sing-box config generation —
// so the Android app and the Windows app cannot drift apart.
include(":shared")
include(":app")
// include(":desktop")  // added in the next commit
