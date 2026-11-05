#include "audio_engine.h"
#include <jni.h>
#include <new>

using auralis::AudioPipeline;

extern "C" JNIEXPORT jlong JNICALL
Java_com_auralis_NativeAudioEngine_nativeCreate(JNIEnv*, jclass, jint sampleRate) {
    auto* pipeline = new (std::nothrow) AudioPipeline(sampleRate);
    return reinterpret_cast<jlong>(pipeline);
}
extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<AudioPipeline*>(handle);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_auralis_NativeAudioEngine_nativeEnqueueInput(JNIEnv* env, jclass, jlong handle, jobject buffer, jint frames) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    const jlong capacityBytes = env->GetDirectBufferCapacity(buffer);
    if (!p || !data || frames <= 0 || frames > auralis::kChunkFrames ||
        capacityBytes < static_cast<jlong>(frames) * 2 * static_cast<jlong>(sizeof(float))) return JNI_FALSE;
    return p->enqueueInput(data, static_cast<std::size_t>(frames)) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_auralis_NativeAudioEngine_nativeProcessOne(JNIEnv*, jclass, jlong handle) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    return p ? p->processOne() : 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_auralis_NativeAudioEngine_nativeDequeueOutput(JNIEnv* env, jclass, jlong handle, jobject buffer, jint capacityFrames) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    const jlong capacityBytes = env->GetDirectBufferCapacity(buffer);
    if (!p || !data || capacityFrames <= 0 ||
        capacityBytes < static_cast<jlong>(capacityFrames) * 2 * static_cast<jlong>(sizeof(float))) return 0;
    return static_cast<jint>(p->dequeueOutput(data, static_cast<std::size_t>(capacityFrames)));
}
extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetStereoWide(JNIEnv*, jclass, jlong handle, jboolean enabled, jdouble percent) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (p) p->setStereoWide(enabled == JNI_TRUE, percent);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetEqBand(JNIEnv*, jclass, jlong handle, jint index, jboolean enabled,
                                                    jint type, jint frequency, jdouble q, jdouble gain, jint channel) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (!p) return JNI_FALSE;
    return p->setEqBand(index, enabled == JNI_TRUE, type, frequency, q, gain, channel) ? JNI_TRUE : JNI_FALSE;
}
