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
        // Bcore 依赖的 FreeReflection（隐藏 API 绕过）只发布在 JitPack。
        // 限定 group，避免 JitPack 参与其它依赖的解析而拖慢构建。
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "DoobleDouble"

include(":app")
include(":engine")
include(":gms")
include(":native")
