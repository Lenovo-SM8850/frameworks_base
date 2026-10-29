/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "TaskFpsCallbackController"

#include <android/gui/BnFpsListener.h>
#include <android/gui/BnGameFrameListener.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include "android_util_Binder.h"
#include "core_jni_helpers.h"

namespace android {

namespace {

struct {
    jclass mClass;
    jmethodID mDispatchOnFpsReported;
} gCallbackClassInfo;

jmethodID gGameFrameReported;

struct GameFrameCallback : public gui::BnGameFrameListener {
    GameFrameCallback(JNIEnv* env, jobject callback) : mCallback(env->NewWeakGlobalRef(callback)) {}
    binder::Status onFrameReported(const gui::GameFrameStats& stats) override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        if (!env) return binder::Status::ok();
        jobject callback = env->NewLocalRef(mCallback);
        if (!callback) return binder::Status::ok();
        env->CallVoidMethod(callback, gGameFrameReported, static_cast<jfloat>(stats.fps),
                            static_cast<jint>(stats.appJankFrames), static_cast<jint>(stats.totalFrames),
                            static_cast<jint>(stats.windowMs), static_cast<jboolean>(stats.layerChanged),
                            static_cast<jboolean>(stats.stalled));
        env->DeleteLocalRef(callback);
        if (env->ExceptionCheck()) {
            ALOGE("Game frame callback failed");
            env->ExceptionClear();
        }
        return binder::Status::ok();
    }
    ~GameFrameCallback() override {
        AndroidRuntime::getJNIEnv()->DeleteWeakGlobalRef(mCallback);
    }
    jweak mCallback;
};

jlong nativeRegisterFrameListener(JNIEnv* env, jclass, jobject listener, jint taskId, jint uid) {
    sp<GameFrameCallback> callback = sp<GameFrameCallback>::make(env, listener);
    if (SurfaceComposerClient::addGameFrameListener(taskId, uid, callback) != OK) return 0;
    callback->incStrong((void*)nativeRegisterFrameListener);
    return reinterpret_cast<jlong>(callback.get());
}

void nativeUnregisterFrameListener(JNIEnv*, jclass, jlong ptr) {
    sp<GameFrameCallback> callback = reinterpret_cast<GameFrameCallback*>(ptr);
    SurfaceComposerClient::removeGameFrameListener(callback);
    callback->decStrong((void*)nativeRegisterFrameListener);
}

static const JNINativeMethod gGameFrameMethods[] = {
        {"nativeRegisterFrameListener", "(Lcom/android/server/app/GamePolicyClient$FrameCallback;II)J",
         (void*)nativeRegisterFrameListener},
        {"nativeUnregisterFrameListener", "(J)V", (void*)nativeUnregisterFrameListener},
};

struct TaskFpsCallback : public gui::BnFpsListener {
    TaskFpsCallback(JNIEnv* env, jobject listener) : mListener(env->NewWeakGlobalRef(listener)) {}

    binder::Status onFpsReported(float fps) override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        LOG_ALWAYS_FATAL_IF(env == nullptr, "Unable to retrieve JNIEnv in onFpsReported.");

        jobject listener = env->NewGlobalRef(mListener);
        if (listener == NULL) {
            // Weak reference went out of scope
            return binder::Status::ok();
        }
        env->CallStaticVoidMethod(gCallbackClassInfo.mClass,
                                  gCallbackClassInfo.mDispatchOnFpsReported, listener,
                                  static_cast<jfloat>(fps));
        env->DeleteGlobalRef(listener);

        if (env->ExceptionCheck()) {
            ALOGE("TaskFpsCallback.onFpsReported() failed.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        return binder::Status::ok();
    }

protected:
    virtual ~TaskFpsCallback() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteWeakGlobalRef(mListener);
    }

private:
    jweak mListener;
};

jlong nativeRegister(JNIEnv* env, jclass clazz, jobject obj, jint taskId) {
    TaskFpsCallback* callback = new TaskFpsCallback(env, obj);

    if (SurfaceComposerClient::addFpsListener(taskId, callback) != OK) {
        constexpr auto error_msg = "Couldn't addFpsListener";
        ALOGE(error_msg);
        jniThrowRuntimeException(env, error_msg);
    }
    callback->incStrong((void*)nativeRegister);

    return reinterpret_cast<jlong>(callback);
}

void nativeUnregister(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<TaskFpsCallback> callback = reinterpret_cast<TaskFpsCallback*>(ptr);

    if (SurfaceComposerClient::removeFpsListener(callback) != OK) {
        constexpr auto error_msg = "Couldn't removeFpsListener";
        ALOGE(error_msg);
        jniThrowRuntimeException(env, error_msg);
    }

    callback->decStrong((void*)nativeRegister);
}

static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeRegister", "(Landroid/window/ITaskFpsCallback;I)J", (void*)nativeRegister},
        {"nativeUnregister", "(J)V", (void*)nativeUnregister}};

} // namespace

int register_com_android_server_wm_TaskFpsCallbackController(JNIEnv* env) {
    int frameRes = jniRegisterNativeMethods(env, "com/android/server/app/GamePolicyClient",
                                           gGameFrameMethods, NELEM(gGameFrameMethods));
    LOG_ALWAYS_FATAL_IF(frameRes < 0, "Unable to register game frame native methods");
    jclass frameCallback = env->FindClass("com/android/server/app/GamePolicyClient$FrameCallback");
    gGameFrameReported = env->GetMethodID(frameCallback, "onFrameReported", "(FIIIZZ)V");
    LOG_ALWAYS_FATAL_IF(!gGameFrameReported, "Unable to find game frame callback");
    int res = jniRegisterNativeMethods(env, "com/android/server/wm/TaskFpsCallbackController",
                                       gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/window/TaskFpsCallback");
    gCallbackClassInfo.mClass = MakeGlobalRefOrDie(env, clazz);
    gCallbackClassInfo.mDispatchOnFpsReported =
            env->GetStaticMethodID(clazz, "dispatchOnFpsReported",
                                   "(Landroid/window/ITaskFpsCallback;F)V");
    return 0;
}

} // namespace android
