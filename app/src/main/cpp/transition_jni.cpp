#include "transition_mixer.h"

#include <jni.h>
#include <new>

using auralis::PowerampTransitionMixer;

extern "C" JNIEXPORT jlong JNICALL
Java_com_auralis_NativeTransitionMixer_nativeCreate(JNIEnv*, jclass, jint sampleRate) {
    auto* mixer = new (std::nothrow) PowerampTransitionMixer(sampleRate);
    return reinterpret_cast<jlong>(mixer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeTransitionMixer_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<PowerampTransitionMixer*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_auralis_NativeTransitionMixer_nativeStartCrossfade(JNIEnv*, jclass, jlong handle,
                                                             jint durationMs, jint blockFrames) {
    auto* mixer = reinterpret_cast<PowerampTransitionMixer*>(handle);
    return mixer && mixer->startCrossfade(durationMs, blockFrames) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_auralis_NativeTransitionMixer_nativeStartSingleFade(JNIEnv*, jclass, jlong handle,
                                                              jfloat startGain, jfloat endGain,
                                                              jint durationMs, jint blockFrames) {
    auto* mixer = reinterpret_cast<PowerampTransitionMixer*>(handle);
    return mixer && mixer->startSingleFade(startGain, endGain, durationMs, blockFrames)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeTransitionMixer_nativeProcessCrossfade(JNIEnv* env, jclass, jlong handle,
                                                               jobject outgoingBuffer,
                                                               jobject incomingBuffer,
                                                               jobject outputBuffer, jint frames) {
    auto* mixer = reinterpret_cast<PowerampTransitionMixer*>(handle);
    auto* outgoing = outgoingBuffer ? static_cast<float*>(env->GetDirectBufferAddress(outgoingBuffer)) : nullptr;
    auto* incoming = incomingBuffer ? static_cast<float*>(env->GetDirectBufferAddress(incomingBuffer)) : nullptr;
    auto* output = outputBuffer ? static_cast<float*>(env->GetDirectBufferAddress(outputBuffer)) : nullptr;
    const jlong outBytes = outgoingBuffer ? env->GetDirectBufferCapacity(outgoingBuffer) : 0;
    const jlong inBytes = incomingBuffer ? env->GetDirectBufferCapacity(incomingBuffer) : 0;
    const jlong dstBytes = outputBuffer ? env->GetDirectBufferCapacity(outputBuffer) : 0;
    const jlong required = static_cast<jlong>(frames) * 2 * static_cast<jlong>(sizeof(float));
    if (!mixer || !output || frames <= 0 || dstBytes < required ||
        (outgoing != nullptr && outBytes < required) || (incoming != nullptr && inBytes < required)) return;
    mixer->processCrossfade(outgoing, incoming, output, static_cast<std::size_t>(frames));
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeTransitionMixer_nativeProcessSingle(JNIEnv* env, jclass, jlong handle,
                                                            jobject inputBuffer,
                                                            jobject outputBuffer, jint frames) {
    auto* mixer = reinterpret_cast<PowerampTransitionMixer*>(handle);
    auto* input = inputBuffer ? static_cast<float*>(env->GetDirectBufferAddress(inputBuffer)) : nullptr;
    auto* output = outputBuffer ? static_cast<float*>(env->GetDirectBufferAddress(outputBuffer)) : nullptr;
    const jlong inBytes = inputBuffer ? env->GetDirectBufferCapacity(inputBuffer) : 0;
    const jlong outBytes = outputBuffer ? env->GetDirectBufferCapacity(outputBuffer) : 0;
    const jlong required = static_cast<jlong>(frames) * 2 * static_cast<jlong>(sizeof(float));
    if (!mixer || !output || frames <= 0 || outBytes < required ||
        (input != nullptr && inBytes < required)) return;
    mixer->processSingle(input, output, static_cast<std::size_t>(frames));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_auralis_NativeTransitionMixer_nativeCrossfadeComplete(JNIEnv*, jclass, jlong handle) {
    auto* mixer = reinterpret_cast<PowerampTransitionMixer*>(handle);
    return mixer && mixer->crossfadeComplete() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_auralis_NativeTransitionMixer_nativeSingleFadeComplete(JNIEnv*, jclass, jlong handle) {
    auto* mixer = reinterpret_cast<PowerampTransitionMixer*>(handle);
    return mixer && mixer->singleFadeComplete() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeTransitionMixer_nativeReset(JNIEnv*, jclass, jlong handle) {
    auto* mixer = reinterpret_cast<PowerampTransitionMixer*>(handle);
    if (mixer) mixer->reset();
}
