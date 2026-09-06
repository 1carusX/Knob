plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cn.icarus.knob"
    compileSdk = 34

    defaultConfig {
        // generic 变体用原包名；byd 变体加 .byd 后缀（见下方 productFlavors）
        applicationId = "cn.icarus.knob"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // 按目标平台分两个变体：比亚迪车机（含 BYDAUTO 权限，见 src/byd/）vs
    // 通用手机（无任何 BYD 权限，仅蓝牙通路）。运行时无法动态追加权限声明，
    // 权限只能编译期决定，故用 flavor + manifest overlay 在构建时区分。
    flavorDimensions += "platform"
    productFlavors {
        create("byd") {
            dimension = "platform"
            // 加后缀不影响 BYDAUTO_* 权限授予——已确认权限门槛就是签名本身，不存在
            // 额外的包名白名单机制（见 docs/验证结论.md），只要用车机厂商签名签这个
            // applicationId 就行。
            applicationIdSuffix = ".byd"
        }
        create("generic") {
            dimension = "platform"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

// Kotlin 编译目标（compilerOptions DSL，替代已废弃的 kotlinOptions）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
}
