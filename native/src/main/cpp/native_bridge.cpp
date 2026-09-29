//
// native_bridge.cpp —— JNI 出口
//
// Kotlin 层通过 NativeBridge 调用这里的函数。命名必须与
// com.dooble.nativehook.NativeBridge 的包名/类名严格对应。
//
#include <jni.h>
#include <stdio.h>
#include <string>

#include "io_redirect.h"
#include "plt_hook.h"

#define DOOBLE_NATIVE_VERSION "1.0.0"

namespace {

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_dooble_nativehook_NativeBridge_nativeInit(JNIEnv* env, jclass,
                                                   jstring spaceRoot, jstring virtualPackage) {
    const std::string root = jstr(env, spaceRoot);
    const std::string pkg = jstr(env, virtualPackage);
    if (root.empty() || pkg.empty()) return -1;
    return dooble::io_redirect_install(root.c_str(), pkg.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_dooble_nativehook_NativeBridge_nativeRedirectPath(JNIEnv* env, jclass, jstring path) {
    const std::string p = jstr(env, path);
    if (p.empty()) return env->NewStringUTF("");
    char buf[4096];
    const char* out = dooble::io_redirect_path(p.c_str(), buf, sizeof(buf));
    return env->NewStringUTF(out != nullptr ? out : "");
}

JNIEXPORT jstring JNICALL
Java_com_dooble_nativehook_NativeBridge_nativeSummary(JNIEnv* env, jclass) {
    return env->NewStringUTF(dooble::io_redirect_summary());
}

JNIEXPORT jstring JNICALL
Java_com_dooble_nativehook_NativeBridge_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(DOOBLE_NATIVE_VERSION);
}

JNIEXPORT jstring JNICALL
Java_com_dooble_nativehook_NativeBridge_nativePltTarget(JNIEnv* env, jclass, jstring lib,
                                                        jstring symbol) {
    const std::string l = jstr(env, lib);
    const std::string s = jstr(env, symbol);
    if (l.empty() || s.empty()) return env->NewStringUTF("");

    void* target = dooble::find_plt_target(l.c_str(), s.c_str());
    if (target == nullptr) return env->NewStringUTF("");

    char buf[64];
    snprintf(buf, sizeof(buf), "%p", target);
    return env->NewStringUTF(buf);
}

JNIEXPORT jint JNICALL
Java_com_dooble_nativehook_NativeBridge_nativeHookSymbol(JNIEnv* env, jclass, jstring lib,
                                                         jstring symbol, jlong replacement) {
    const std::string l = jstr(env, lib);
    const std::string s = jstr(env, symbol);
    if (l.empty() || s.empty()) return -1;
    return dooble::hook_plt(l.c_str(), s.c_str(), reinterpret_cast<void*>(replacement), nullptr);
}

}  // extern "C"
