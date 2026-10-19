// Top-level build file（版本号统一在仓库根目录 gradle/libs.versions.toml 管理，与 plugin 共用）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
