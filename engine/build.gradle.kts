plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dooble.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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

    lint {
        abortOnError = false
    }

    // 引擎里有一批**纯逻辑**（落盘布局、事件日志格式、GMS 判定）值得单测，
    // 但它们散落在各个类里，不单独成模块。没有这个配置时，测试里只要碰到
    // 任何一个 Android 方法就会抛 "Stub!"，逼得人把测试挪到别的模块去。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// Bcore 虚拟化引擎的接入开关。
//
// `libs/bcore.aar` 存在 -> 编译 src/bcore 源集，启用真正的虚拟化能力；
// 不存在               -> 整个源集不参与编译，工程照常构建，运行时降级到沙箱引擎。
//
// 用 fileTree 而不是 files()：fileTree 在无匹配文件时得到空集合，不会让 Gradle
// 因「找不到文件」而报错，这样从零克隆仓库的人不必先编一遍 Bcore 才能构建工程。
//
// 为什么是 compileOnly：AAR 由 :app 通过 implementation 打进 APK，engine 模块
// 只需要它的**类型信息**来编译 BcoreEngine。若这里用 implementation，AAR 会被
// 打进 engine 的 AAR 再被 app 依赖，造成重复打包。
// ---------------------------------------------------------------------------
val bcoreAar = fileTree("libs") { include("*.aar") }
val hasBcore = bcoreAar.files.isNotEmpty()

android.sourceSets {
    if (hasBcore) {
        getByName("main").java.srcDir("src/bcore/java")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    if (hasBcore) {
        compileOnly(bcoreAar)
    }

    testImplementation(libs.junit)
}
