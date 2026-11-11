#include "audio_engine.h"
#include "replaygain_tags.h"
#include <jni.h>
#include <new>
#include <vector>

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

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetBalance(JNIEnv*, jclass, jlong handle, jboolean enabled, jdouble normalized) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (p) p->setBalance(enabled == JNI_TRUE, normalized);
}
extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetBassBoost(JNIEnv*, jclass, jlong handle, jboolean enabled, jdouble amount) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (p) p->setBassBoost(enabled == JNI_TRUE, amount);
}
extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetMono(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (p) p->setMono(enabled == JNI_TRUE);
}
extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetLimiter(JNIEnv*, jclass, jlong handle, jboolean enabled, jint path) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (!p || path < 0 || path > 1) return;
    p->setLimiter(enabled == JNI_TRUE, static_cast<auralis::LimiterPath>(path));
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetReverbEnabled(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (p) p->setReverbEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetReverbParameter(JNIEnv*, jclass, jlong handle, jint index, jdouble value) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (p && index >= 0 && index <= 6) p->setReverbParameter(index, value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetReplayGain(JNIEnv*, jclass, jlong handle,
                                                        jint type, jint source,
                                                        jint defaultGainMb, jint preampMb) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (p) p->setReplayGain(type, source, defaultGainMb, preampMb);
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetMasterGainDb(JNIEnv*, jclass, jlong handle, jdouble db) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (p) p->setMasterGainDb(db);
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_NativeAudioEngine_nativeSetReplayGainMetadata(JNIEnv*, jclass, jlong handle,
                                                                 jboolean albumAvailable,
                                                                 jdouble albumGainDb,
                                                                 jdouble albumPeakLinear,
                                                                 jboolean trackAvailable,
                                                                 jdouble trackGainDb,
                                                                 jdouble trackPeakLinear) {
    auto* p = reinterpret_cast<AudioPipeline*>(handle);
    if (!p) return;
    auralis::ReplayGainMetadata metadata{};
    metadata.albumAvailable = albumAvailable == JNI_TRUE;
    metadata.trackAvailable = trackAvailable == JNI_TRUE;
    metadata.albumGainDb = albumGainDb;
    metadata.albumPeakLinear = albumPeakLinear;
    metadata.trackGainDb = trackGainDb;
    metadata.trackPeakLinear = trackPeakLinear;
    p->setReplayGainMetadata(metadata);
}

// Parses ReplayGain tags from the leading bytes of a file (or an MP4 moov box). Returns
// {albumAvailable, albumGainDb, albumPeak, trackAvailable, trackGainDb, trackPeak} as doubles
// (availability is 1.0 or 0.0), or null when no ReplayGain tag was found.
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_auralis_NativeAudioEngine_nativeParseReplayGain(JNIEnv* env, jclass, jbyteArray bytes, jint length) {
    if (bytes == nullptr || length <= 0) return nullptr;
    const jsize available = env->GetArrayLength(bytes);
    const jsize count = length < available ? length : available;
    std::vector<jbyte> buffer(static_cast<std::size_t>(count));
    env->GetByteArrayRegion(bytes, 0, count, buffer.data());
    if (env->ExceptionCheck()) return nullptr;
    auralis::ReplayGainMetadata metadata{};
    if (!auralis::parseReplayGainTags(reinterpret_cast<const std::uint8_t*>(buffer.data()),
                                      buffer.size(), metadata)) return nullptr;
    const jdouble values[6] = {metadata.albumAvailable ? 1.0 : 0.0, metadata.albumGainDb, metadata.albumPeakLinear,
                               metadata.trackAvailable ? 1.0 : 0.0, metadata.trackGainDb, metadata.trackPeakLinear};
    jdoubleArray result = env->NewDoubleArray(6);
    if (result == nullptr) return nullptr;
    env->SetDoubleArrayRegion(result, 0, 6, values);
    return result;
}
