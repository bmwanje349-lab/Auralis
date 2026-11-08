# Auralis

Auralis is an Android music player with a native C++ stereo DSP engine. The implementation is based on the supplied Poweramp reverse-engineering reference, with exact recovered equations kept separate from parameters or algorithms that the reference does not fully specify.

## Build

Requirements: JDK 17, Android SDK platform 35, Android build-tools 35.0.0, NDK 27.2.12479018, and CMake 3.22.1.

```bash
gradle assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Player

- Open a local audio file through Android's document picker.
- Decode using Android `MediaExtractor` and `MediaCodec`.
- Keep decode, native DSP, and `AudioTrack` output on separate worker threads.
- Process interleaved stereo Float32 blocks using preallocated lock-free SPSC queues.
- Apply the recovered StereoWide Mid/Side mapping and the six recovered biquad EQ families.
- Apply native-routed Mono fold-down and signed Balance attenuation.
- ReplayGain: tags are read from MP3, FLAC, Ogg Vorbis/Opus and M4A files when a track loads; choose mode, source, preamp and the gain for untagged files in the ReplayGain section.
- Apply the linked-stereo peak Limiter with hard-ceiling and gain-derived protection paths, scalar tail processing and four-frame vector envelope updates.
- Reverb is an audible, stateful stage (UI -> service -> JNI -> native). Its late field is a captured Poweramp response at one preset (Size 0.55, Filter 0.50, Fade 0.45, Pre-delay 0.15, Mix 1.0, Pre-delay Mix 0.50, Damp 0.50). Changing those controls does not yet reshape the sound the way Poweramp does; see the fidelity doc.
- Keep UI-to-DSP changes in a bounded lock-free control queue so the DSP worker does not take a UI mutex.

## Reference fidelity

See [`docs/POWERAMP_REFERENCE_FIDELITY.md`](docs/POWERAMP_REFERENCE_FIDELITY.md) for the exact recovered rules, implemented tests, and boundaries where the supplied research gives parameters but not enough information to reproduce the proprietary processing kernel or routing.

## Native verification

```bash
cmake -S app/src/main/cpp -B build-native -DCMAKE_BUILD_TYPE=Release
cmake --build build-native -j2
ctest --test-dir build-native --output-on-failure
```
