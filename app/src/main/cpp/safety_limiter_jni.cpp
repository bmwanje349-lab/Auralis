#include "safety_limiter.h"

#include <jni.h>
#include <new>

using auralis::SafetyLimiter;

extern "C" JNIEXPORT jlong JNICALL
Java_com_auralis_NativeSafetyLimiter_nativeCreate(JNIEnv*, jclass, jint sampleRate) {
    return reinterpret_cast<jlong>(new (std::nothrow) SafetyLimiter(sampleRate));
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeSafetyLimiter_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<SafetyLimiter*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeSafetyLimiter_nativeProcess(JNIEnv* env, jclass, jlong handle,
                                                    jobject buffer, jint frames) {
    auto* limiter = reinterpret_cast<SafetyLimiter*>(handle);
    auto* samples = buffer ? static_cast<float*>(env->GetDirectBufferAddress(buffer)) : nullptr;
    const jlong capacity = buffer ? env->GetDirectBufferCapacity(buffer) : 0;
    const jlong required = static_cast<jlong>(frames) * 2 * static_cast<jlong>(sizeof(float));
    if (!limiter || !samples || frames <= 0 || capacity < required) return;
    limiter->process(samples, static_cast<std::size_t>(frames));
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeSafetyLimiter_nativeReset(JNIEnv*, jclass, jlong handle) {
    auto* limiter = reinterpret_cast<SafetyLimiter*>(handle);
    if (limiter) limiter->reset();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_auralis_NativeSafetyLimiter_nativeMaxReductionDb(JNIEnv*, jclass, jlong handle) {
    auto* limiter = reinterpret_cast<SafetyLimiter*>(handle);
    return limiter ? limiter->maxReductionDb() : 0.0;
}
