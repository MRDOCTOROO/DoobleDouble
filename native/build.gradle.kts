plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dooble.nativehook"
    compileSdk = 35
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 24

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-fno-rtti")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }

        ndk {
            // 与 :app 的 ndk.abiFilters 保持一致。
            //
            // 这里**不能**编 x86_64：Bcore 的 libblackbox.so 上游只提供
            // arm64-v8a / armeabi-v7a。只要 APK 里出现 lib/x86_64/ 目录，
            // 安装时 primaryCpuAbi 就会选 x86_64，于是 nativeLibraryDir 指向
            // lib/x86_64，System.loadLibrary("blackbox") 必然失败：
            //   UnsatisfiedLinkError: dlopen failed: library "libblackbox.so" not found
            // 而且 lib/x86_64 里只要有一个 .so 就会触发，哪怕那个 .so 是
            // 第三方库（libandroidx.graphics.path.so）带进来的。
            //
            // 所以 x86_64 模拟器（含 MuMu）走 MuMu 自带的 ARM 翻译层：
            // ro.dalvik.vm.native.bridge=libnb.so，abilist=x86_64,arm64-v8a,x86，
            // 去掉 x86_64/x86 后 primaryCpuAbi 落到 arm64-v8a，三个 .so 齐全。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
