// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

#define LOG_TAG "ActivityTriggerService"

#include <dlfcn.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/Log.h>

#include <string>

#include "jni.h"

namespace android {
namespace {

using SetInfo = void (*)(const char*, const char*, int*);

SetInfo loadSetInfo() {
    void* handle = dlopen("libtrigger-handler.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        ALOGI("Process registration disabled: %s", dlerror());
        return nullptr;
    }
    auto setInfo = reinterpret_cast<SetInfo>(dlsym(handle, "set_info"));
    if (setInfo == nullptr) {
        ALOGI("Process registration disabled: %s", dlerror());
        dlclose(handle);
        return nullptr;
    }
    // Keep the library loaded for the lifetime of the function pointer.
    return setInfo;
}

void notifyActionNative(JNIEnv* env, jclass, jstring packageName, jlong versionCode,
                        jstring /* processName */, jint processId, jint event) {
    if (packageName == nullptr) {
        return;
    }
    static const SetInfo setInfo = loadSetInfo();
    if (setInfo == nullptr) {
        return;
    }
    ScopedUtfChars package(env, packageName);
    if (package.c_str() == nullptr) {
        return;
    }
    const std::string versionEvent = std::to_string(versionCode) + std::to_string(event);
    int pid = processId;
    setInfo(package.c_str(), versionEvent.c_str(), &pid);
}

const JNINativeMethod gMethods[] = {
        {"notifyAction_native", "(Ljava/lang/String;JLjava/lang/String;II)V",
         reinterpret_cast<void*>(notifyActionNative)},
};

} // namespace

int register_android_server_ActivityTriggerService(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/ActivityTriggerService", gMethods,
                                    sizeof(gMethods) / sizeof(gMethods[0]));
}

} // namespace android
