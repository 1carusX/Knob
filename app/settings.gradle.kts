pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // 注意：settings 脚本顶部的 plugins 块无法引用 libs（catalog 尚未加载），
    // 所以这里用字面量版本号。仅此一处；项目内的 plugins/dependencies 都用 alias/libs。
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // 版本号统一放在仓库根目录的 gradle/libs.versions.toml，app 和 plugin 共用。
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "KnobApp"
include(":app")
