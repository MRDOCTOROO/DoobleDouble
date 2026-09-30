import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// release 签名。
//
// 为什么必须用**固定密钥**而不是默认的 debug 签名：
// Android 只允许「同签名」的 APK 覆盖安装。GitHub Actions 每次跑都是全新
// runner，`~/.android/debug.keystore` 是当场生成的 —— 也就是**每次签名都不同**。
// 用 debug 签名发版，用户装了第一版之后再也装不上第二版
// （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装，空间和账号全丢。
//
// 密钥来源两条路，按优先级：
//  1. `DOOBLE_KEYSTORE_PATH` 指向的文件（CI 用：把 Secrets 里的 base64 解到
//     `$RUNNER_TEMP` 下 —— **不能放 app/build/**，因为 `:app:clean` 会把它删掉），
//     口令走 DOOBLE_STORE_PASSWORD / DOOBLE_KEY_PASSWORD；
//  2. 本地 keystore/dooble-release.jks + keystore/keystore.properties
//     （两者都在 .gitignore 里，不入库）。
//
// 两者都没有时不配置签名 —— release 变体产出**未签名** APK 而不是构建失败，
// 这样「clone 下来先跑一次 assembleDebug」的新人不会被卡住。
// ---------------------------------------------------------------------------
val localKeystore = rootProject.file("keystore/dooble-release.jks")
val localKeystoreProps = rootProject.file("keystore/keystore.properties")
val envKeystore = System.getenv("DOOBLE_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)

val localProps = Properties().apply {
    if (localKeystoreProps.isFile) localKeystoreProps.inputStream().use { load(it) }
}

val signingKeystore = when {
    envKeystore != null && envKeystore.isFile -> envKeystore
    localKeystore.isFile -> localKeystore
    else -> null
}
val signingStorePass =
    System.getenv("DOOBLE_STORE_PASSWORD") ?: localProps.getProperty("storePassword")
val signingKeyAlias =
    System.getenv("DOOBLE_KEY_ALIAS") ?: localProps.getProperty("keyAlias") ?: "dooble"
val signingKeyPass =
    System.getenv("DOOBLE_KEY_PASSWORD") ?: localProps.getProperty("keyPassword")
val hasSigning = signingKeystore != null && !signingStorePass.isNullOrBlank()

android {
    namespace = "com.dooble.space"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dooble.space"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        // -------------------------------------------------------------------
        // 只打 arm64-v8a / armeabi-v7a，**绝不出现 lib/x86_64 或 lib/x86**。
        //
        // 原因：Bcore 的 libblackbox.so 上游只提供这两个 ABI。Android 在安装时
        // 按 ro.product.cpu.abilist 的顺序挑第一个「APK 里存在」的 ABI 作为
        // primaryCpuAbi，之后 nativeLibraryDir 就固定成 lib/<该 ABI>。
        //
        // 一旦 APK 里有 lib/x86_64/（哪怕只躺着一个第三方 .so，比如
        // libandroidx.graphics.path.so），primaryCpuAbi 就会是 x86_64，
        // 于是 libblackbox.so 永远找不到：
        //   UnsatisfiedLinkError: dlopen failed: library "libblackbox.so" not found
        //   at NativeCore.<clinit> -> BlackBoxCore.setEssentialProperties
        // 这个错发生在 attachBaseContext 里，表现为「引擎静默降级」，
        // 极易被误判成「模拟器架构不支持」。
        //
        // 所以这里用 defaultConfig.ndk.abiFilters（它会过滤**合并后**的全部
        // native 库，包括第三方传递进来的），而不是只在 :native 模块里配。
        //
        // x86_64 模拟器（含 MuMu）走宿主的 ARM 翻译层：MuMu 的
        // ro.dalvik.vm.native.bridge=libnb.so、abilist=x86_64,arm64-v8a,x86，
        // 去掉 x86_64/x86 后 primaryCpuAbi 落到 arm64-v8a，三个 .so 齐全。
        //
        // 将来若 Bcore 提供 x86_64 的 libblackbox.so，把这里和
        // native/build.gradle.kts 的 abiFilters 一起加回 "x86_64" 即可。
        // -------------------------------------------------------------------
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    /**
     * 两种发行形态：
     *  - lite：宿主包体 ~8MB，microG 在首次装配时按需下载（默认）
     *  - full：把 microG 组件打进 APK，实现真正的「内置 GMS」，离线可用
     *
     * 二者业务代码完全一致，只差 assets 里有没有 microG 的 APK。
     */
    flavorDimensions += "gms"

    productFlavors {
        create("lite") {
            dimension = "gms"
            buildConfigField("boolean", "GMS_BUNDLED", "false")
        }
        create("full") {
            dimension = "gms"
            buildConfigField("boolean", "GMS_BUNDLED", "true")
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("dooble") {
                storeFile = signingKeystore
                storePassword = signingStorePass
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有密钥才挂签名；没有就产出未签名 APK，不阻断构建
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("dooble")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // microG 的 APK 本身已是压缩格式，再压一遍纯属浪费构建时间
    androidResources {
        noCompress += listOf("apk")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    implementation(project(":engine"))
    implementation(project(":gms"))
    implementation(project(":native"))

    // Bcore 虚拟化引擎的实体。engine 模块只用它编译（compileOnly），
    // 真正的打包发生在这里，避免 AAR 被重复打进产物。
    //
    // 用 fileTree 而非 files()：AAR 缺失时得到空集合而不是报错，
    // 这样从零克隆仓库的人不必先编一遍 Bcore 就能构建（运行时降级到沙箱引擎）。
    //
    // 必须同时收 *.jar：Bcore 依赖上游的 :black-reflection 模块（纯 JVM 的
    // java-library，产出 jar）。它是**本地源码模块**，没有 Maven 坐标，
    // 所以不像 FreeReflection 那样能用 libs.xxx 声明；而 Bcore 对它是
    // implementation 依赖，AAR 不传递 —— 漏掉它会在 BlackBoxCore 初始化时抛
    // NoClassDefFoundError: top/niunaijun/blackreflection/BlackReflection。
    // 详见 tools/build-bcore.sh 里 build_black_reflection() 的说明。
    implementation(fileTree("../engine/libs") { include("*.aar", "*.jar") })

    // Bcore 运行时必需，但其 AAR 未携带该依赖（本地 AAR 不传递依赖元数据）。
    // 缺少它会在 BlackBoxCore 初始化时抛 NoClassDefFoundError。
    implementation(libs.free.reflection)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ---------------------------------------------------------------------------
// 「内置 GMS」的实现：把 microG 组件下载到 full 变体专属的 assets 目录。
//
// 为什么放在 app 模块而不是 gms 模块：flavor 源集必须和被声明 flavor 的模块
// 一致。GmsProvisioner 通过 context.assets 读取，asset 最终会合并进 APK，
// 所以放哪一层都能读到——放 app 的 flavor 源集最省事。
//
// 只在构建 full 变体时触发；lite 变体完全不受影响。
// 已存在则跳过，所以重复构建不会反复下载 100MB。
// ---------------------------------------------------------------------------

val microgAssetsDir = layout.projectDirectory.dir("src/full/assets/microg")

val microgArtifacts = mapOf(
    "com.google.android.gms.apk" to
        "https://github.com/microg/GmsCore/releases/download/v0.3.16.252432/com.google.android.gms-252432032.apk",
    "com.android.vending.apk" to
        "https://github.com/microg/GmsCore/releases/download/v0.3.16.252432/com.android.vending-84022632.apk",
)

val fetchMicroG by tasks.registering {
    group = "dooble"
    description = "下载 microG 组件到 full 变体的 assets 目录，实现内置 GMS（离线可用）"

    val outDir = microgAssetsDir.asFile
    outputs.dir(outDir)

    doLast {
        outDir.mkdirs()
        microgArtifacts.forEach { (name, directUrl) ->
            val target = File(outDir, name)
            if (target.exists() && target.length() > 1_000_000L) {
                logger.lifecycle("microG 组件已存在，跳过：$name（${target.length() / 1048576} MB）")
                return@forEach
            }

            var ok = false
            for (prefix in listOf("", "https://ghfast.top/", "https://gh-proxy.com/")) {
                try {
                    logger.lifecycle("下载 microG 组件：$name（镜像前缀 '${prefix.ifEmpty { "直连" }}'）")
                    val conn = URI(prefix + directUrl).toURL().openConnection()
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 900_000
                    conn.getInputStream().use { input: java.io.InputStream ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (target.length() > 1_000_000L) {
                        logger.lifecycle("  完成：${target.length() / 1048576} MB")
                        ok = true
                        break
                    }
                } catch (t: Throwable) {
                    logger.lifecycle("  镜像失败：${t.message}")
                    target.delete()
                }
            }

            if (!ok) {
                target.delete()
                throw GradleException(
                    "microG 组件下载失败：$name\n" +
                        "可选方案：\n" +
                        "  1. 构建 lite 变体（按需下载）：./gradlew :app:assembleLiteDebug\n" +
                        "  2. 用 tools/fetch-microg.sh 预置后重试\n" +
                        "  3. 手动把 APK 放到 $outDir"
                )
            }
        }
    }
}

tasks.matching { it.name.startsWith("mergeFull") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(fetchMicroG) }
