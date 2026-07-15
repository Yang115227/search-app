pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // PaddleOCR 本地 AAR 仓库
        flatDir {
            dirs("app/libs")
        }
    }
}

rootProject.name = "SmartSearch"
include(":app")