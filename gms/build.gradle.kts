plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dooble.gms"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }

    // 让 JVM 单测里调到的 Android 方法返回默认值而不是抛 "Stub!"，
    // 这样纯逻辑（列表过滤、格式编解码）可以就地测试，不必搬到别的模块。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// 把 Bcore 补丁目录声明成单测的输入。
//
// 为什么需要：`BcorePatchContractTest` 会在**运行期**读
// `tools/bcore-patch/*.patch` 的内容做断言。但 Gradle 只跟踪它自己知道的
// 输入（源码、classpath），**不会**知道测试在运行期读了这个文件。
// 于是改了补丁之后本地重跑测试，Gradle 判定 `:gms:testDebugUnitTest`
// UP-TO-DATE 直接跳过 —— **本地假绿，CI 上一跑就红**。
//
// 这个坑真实发生过：补丁从 35KB 精简到 167 行（`resolveStoreIntent` 由新增行
// 变成上下文行），本地测试"通过"，CI 报
// `BcorePatchContractTest > 改道点必须在 null 兜底分支里 FAILED`。
//
// `.optional()`：补丁目录不存在时（比如只 checkout 了部分目录）不要让配置阶段
// 就失败 —— 测试里有 Assume 会自己跳过。
// ---------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("tools/bcore-patch"))
        .withPropertyName("bcorePatchFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
}
