# Poweramp reference fidelity and conformance record

This document is the implementation contract for the supplied research record. It distinguishes a recovered equation or mapping from an item for which only names, ranges, constants, or partial structure were supplied. Auralis does not label an inferred substitute as Poweramp-equivalent.

## Implemented and covered by native tests

### StereoWide — exact recovered behavior

- Mid/Side definitions: `M=(L+R)/2`, `S=(L-R)/2`.
- Output: `L'=M+gS`, `R'=M-gS`.
- Direct UI mapping: `g=1+2*(sliderPercent/100)`.
- Tested points: 0%→1.0, 25%→1.5, 50%→2.0, 75%→2.5, 100%→3.0.
- Tested 100% result: `L'=2L-R`, `R'=2R-L`; mono remains unchanged.

### Parametric EQ — recovered six biquad equations

The native engine calculates normalized coefficients for low-pass, high-pass, band-pass, peaking, low-shelf, and high-shelf filters. It uses the supplied definitions for `w`, `alpha`, `A`, and `beta`, the coefficient normalization by `a0`, the 1–24,000 Hz / Q 0.1–12 / ±15 dB limits, and channel codes in the recovered range 0–2. The supplied record confirms left, right and combined operation but does not identify which numeric code selects each. Auralis uses a documented local mapping (0=both, 1=left, 2=right) for its own band model; that mapping is not claimed to be the Poweramp enum mapping. EQ processing state and coefficients use `double` precision. The player UI exposes the two recovered Bass/Treble defaults; the native engine supports 64 band slots.

### Limiter — recovered envelope law only

- Native plugin index: 1.
- Hard-ceiling path has a recovered ceiling of 1.0.
- A separate configurable-threshold path and its envelope state are identified.
- Gain reduction: `G_next = max(G_target, G_current - 1/256)`.
- Gain recovery: `G_next = min(1, G_current + 1/16384)`.
- The two paths retain separate envelope-state pairs.

The reference does **not** fully specify the detector that derives `G_target`, or every detail of the configurable threshold. The code therefore implements the exact state-update law as `Limiter::advance(G_target)` but does not guess a peak detector or put an invented limiter in the playback signal path. This is intentionally not advertised as a complete Poweramp-equivalent limiter.

### ReplayGain — recovered preference and gain law

- Type: none / apply / apply-peak.
- Source: album / track.
- Album source falls back to track metadata when album metadata is unavailable.
- Track source does not fall back to album metadata.
- Requested millibels: `tagGainDb*100 + preampMb`, or the configured default plus preamp when metadata is absent.
- Apply Peak cap: `2000*log10(1/peakLinear)` millibels; applied gain is the minimum of requested and peak cap.
- Conversion: `10^(mB/2000)`.

The player currently delegates decoding to Android's media stack; it does not yet contain a format-independent ReplayGain tag extractor. The native gain model accepts exact album/track metadata values when supplied. The supplied reference does not give ReplayGain preference bounds, so Auralis does not add arbitrary clamping.

### Reverb parameter model and delay geometry

The native control model preserves the recovered indices 0–6, size transform `0.01+0.99*UI`, damp transform `1-UI`, identity mappings for the other parameter indices, all eight delay-time families, and all six recovered constants. It records sample counts calculated from `Fs × delaySeconds × transformedSize`. The reference says native code clamps to allocated buffer capacity, but that capacity is not supplied; Auralis does not invent one.

Index 7 is recorded as a flag operation with no parameter-field write. Its exact bit operation is not supplied, so Auralis intentionally does not toggle an invented boolean or alter p6.

The supplied record does not specify the complete delay-line wiring, per-line read/write recurrence, or exact feedback/combination routing. For that reason this build does not label a guessed reverb network as the recovered Poweramp reverb, and the player does not expose a misleading “Poweramp Reverb” effect switch.

## Retained recovered settings and constants

The native reference model also retains:

- Balance attenuation magnitude law `10^(-48*abs(b)/20)`; left/right sign routing is intentionally not guessed.
- Tempo's user-facing bounds of 0.5× to 3.0×; no time-stretch kernel is supplied by the reference record.
- Resampler cutoff bounds 80–99% and default 97, plus the P2D `R={8,16,32,64,128}`, `N={3,4}` families and FAST/BALANCED flags. The exact resampler kernel is not supplied.
- Dither choices 1–9 by name; their native indices are retained as 1–9. The noise-shaping coefficient sets are not supplied.
- DSD64–DSD1024 rates in both 44.1 kHz and 48 kHz families, the recovered cutoff rule, and the exact DSD64/DSD128 coefficient arrays. The complete DSD processing pipeline and remaining coefficient tables are not supplied.
- No-DVC headroom default −600 mB; bounds −1000 to 0 mB in 50 mB increments.
- ReplayGain metadata equations are implemented, but a general ReplayGain tag parser is not included in this player build.
- Smooth EQ border ceiling expression `12.5 - S/120` and separation of `dsp_border_gain` from `eq_limiter_enabled`.
- The named output volume modes without inventing numeric native IDs.
- Native plugin indices: limiter 1, StereoWide 2, Balance 3.

These values are retained as reference data and tested where an equation or numeric range is available. They are not represented as complete audio effects where the processing kernel is absent.

## Processing and threading

The Android player uses separate decoder, DSP, and output worker threads. The native DSP pipeline uses preallocated fixed-size stereo buffers and lock-free SPSC queues. Settings cross to the DSP worker through a bounded control queue; no UI mutex is taken by the DSP worker. Android `MediaCodec` and `AudioTrack` are platform I/O components, not copies of Poweramp's proprietary decoder or output backends. The active Auralis playback graph combines its implemented modules in its own integration order; the supplied Poweramp record does not identify the complete original DSP stage order, so this is not claimed as an exact reproduction of Poweramp's internal routing.

## Deliberate non-claims

The supplied record does not contain enough information to reproduce the full proprietary engine exactly: the complete reverb recurrence, time-stretch implementation, exact SoX/P2D resampler kernel, dither noise-shaping filters, compressor algorithm, complete DSD engine, ReplayGain tag parser, full output driver selection logic, and exact complete DSP stage ordering are not specified. Auralis does not replace those with unrelated DSP while calling them equivalent.

## Appendix A — Complete recovered binary reference data

The addresses and offsets below belong to the investigated Poweramp ARM64 binary identified in the supplied research, not to Auralis. Auralis records them for traceability only. They are not function pointers to call from Auralis: using another app's private library or treating its offsets as stable public APIs would be incorrect.

### Binary identity

| Property | Recovered value |
|---|---|
| Library | `libpowerampcore.so` |
| Architecture | ARM64 / AArch64 |
| SHA-256 | `c22a29d4e71723b1f7e8cb5dc6cd7e461aa5133b65e73ca4115928fa980f9d9e` |
| ELF Build ID | `0bae7041c349a593cf7a4fa8ba1718c46461d665` |
| Native disassembly source | `native/full.dis` |
| Native library copy | `native/libpowerampcore.so` |
| Java decompilation | `jadx/sources/` |
| Resource decompilation | `jadx/resources/` |
| Additional Java bridge | `~/tmp/xl0.java` |

Recovered plugin indices: Limiter=1, StereoWide=2, Balance=3. Mono and Balance share descriptor slot 3. Other identified modules include DSPI, `resampler3`, Milk DSP, Reverb, Tempo, parametric/graphic EQ, ReplayGain and DSD output processing.

### Native addresses

| Component | Native address | Recovered role |
|---|---:|---|
| StereoWide descriptor/config | `0x220380` | Descriptor/configuration |
| StereoWide float callback | `0x21CBC0` | Float processing entry |
| StereoWide double callback | `0x21EBA0` | Double processing entry |
| StereoWide parameter handler | `0x21FFB8` | Parameter handling |
| Reverb parameter setter | `0x16BC00` | Native parameter-index setter |
| ReplayGain receiver | `0x3295E8` | Metadata/config message receiver |
| ReplayGain handler | `0x320534` | Message handler |
| ReplayGain slot-gain routine | `0x320348` | Slot gain calculation |
| ReplayGain global configuration | `0x47A250` | Global configuration |
| EQ shared coefficient kernel | `0x306FD8` | Coefficient generator |
| EQ single-filter JNI | `0x30750C` | Single coefficient calculation |
| EQ multi-band JNI | `0x3076AC` | Multi-band coefficient calculation |
| EQ frequency-response JNI | `0x307AD4` | Frequency-response evaluation |
| EQ coefficient gain modifier | `0x307F40` | Coefficient gain modification |
| Smooth EQ / gain handler | `0x21FECC` | Gain message handler |
| Smooth EQ gain instructions | `0x21FF2C–0x21FF38` | Master-gain conversion path |

### Native context offsets

| Offset | Recovered meaning |
|---|---|
| `+0x4AC` | Reverb Damp |
| `+0x4B0` | Reverb Filter |
| `+0x4B4` | Reverb Pre-delay |
| `+0x4B8` | Reverb Fade |
| `+0x4BC` | Reverb Mix |
| `+0x4C0` | Reverb p6 / Pre-delay Mix destination |
| `+0x4C4` | Reverb Size |
| `+0x6C` | Limiter enable flags |
| `+0x8C + 32*i` | Per-track volume millibels for record i |
| `+0xC0` | StereoWide width field |
| `+0xC4` | EQ preamp |
| `+0xC8` | No-DVC headroom |
| `+0xCC` | Master-volume millibels |
| `+0xD8` | Derived linear master gain |
| `+0xF8 / +0xFC` | Configurable-threshold limiter envelope pair |
| `+0x100 / +0x104` | Hard-ceiling limiter envelope pair |
| `+0x128 bit 7` | Limiter protection-path selection |
| `+0x134`, `+0x13C` | Additional dynamic gain context fields |

### Limiter state and timing

The two recovered paths are: hard ceiling 1.0, state pair `+0x100/+0x104); and configurable threshold, state pair `+0xF8/+0xFC`. The scalar state update is:
`G_next = max(G_target, G_current - 1/256)` for gain reduction, and
`G_next = min(1, G_current + 1/16384)` for recovery.
At 48 kHz, the full-range ramp times are approximately 5.333 ms and 341.333 ms. The vector path processes four frames with four-times-larger increments per batch, preserving the aggregate ramp. The complete target detector and configurable-threshold predicates are not specified; Auralis implements the recovered envelope law in isolation and does not invent a peak detector.

### ReplayGain configuration and metadata

Preferences: `rg_type` (0=None, 1=Apply, 2=Apply Peak), `rg_source` (0=Album, 1=Track), `rg_default_mb`, and `rg_preamp_mb`. The global base is `0x47A250`: offsets `+0x00` type, `+0x04` source, `+0x08` default gain mB, `+0x0C` preamp mB.

Metadata route: `Pipeline2.k → route code 258 → receiver 0x3295E8 → handler 0x320534 → player-slot ReplayGain fields`. Slot fields: `+0x38` availability mask, `+0x40` album gain, `+0x48` album peak, `+0x3C` track gain, `+0x44` track peak. Availability bit 0 indicates album data; bit 1 indicates track data. Related record notes: parent offsets `+0x350` and `+0x5B8`, corresponding records `+0x140` and `+0x3A8`, recomputation gate at record offset `+0x21C`, bit 3.

The gain law and album/track fallback are implemented in the native reference model. File tag extraction is not: this Android playback build does not yet read ReplayGain tags from media metadata.

### Reverb setter and initialization

Java service: `com.maxmpz.audioplayer.plugin.reverb.ReverbPluginService`; base class `BaseFloatParamsPluginService`; native setter `0x16BC00`.

| Native index | Parameter | Destination / transform |
|---:|---|---|
| 0 | p1 Size | `+0x4C4`; `0.01 + 0.99 × UI_Size` |
| 1 | p2 Filter | `+0x4B0`; identity |
| 2 | p3 Fade | `+0x4B8`; identity |
| 3 | p4 Pre-delay | `+0x4B4`; identity |
| 4 | p5 Mix | `+0x4BC`; identity |
| 5 | p6 Pre-delay Mix | `+0x4C0`; identity |
| 6 | p7 Damp | `+0x4AC`; `1 - UI_Damp` |
| 7 | native flag operation | No parameter-field write; exact bit operation unspecified |

The eight Size-scaled time constants in seconds are {0.020, 0.060, 0.030, 0.089, 0.150, 0.120, 0.140, 0.110}. The six recovered combining constants are {0.75, 0.75, 0.625, 0.625, 0.25, 0.25}. Delay sample counts in Auralis are stored as exact `Fs × seconds × transformedSize` products; integer conversion and allocated-buffer capacity clamping are not guessed. Initialization message type 1 carries configuration, type 2 carries parameters; reset zeros parameters and disables; parameter changes clear selected-preset state; float/double entry points share the recovered float-state core. Full recursive wiring is not in the reference, so no reverb effect is claimed.

### EQ Java model, JNI and serialization

Recovered classes: `com.maxmpz.dsp.data.Band`, `com.maxmpz.audioplayer.equalizer.Coefs`, `hj.java`, `gj.java`, `ij.java`, `xl0.java`, and `FrequencyResponseScroller.java`.

The Java model stores frequency 1–24,000 Hz, Q 0.1–12, type 0–5, channel 0–2, up to 64 bands, normalized gain −1 to +1 (nominal ×15 dB), enabled and custom-band state. Type IDs: 0 low-pass, 1 high-pass, 2 band-pass, 3 peaking, 4 low shelf, 5 high shelf. Bass default 90 Hz/Q 0.8; treble default 10,000 Hz/Q 0.6. The response display evaluates 20–24,000 Hz at nominal 48 kHz.

Recovered JNI signatures:
```cpp
native_calc_coefs(int sampleRate, int frequency, int type, float Q, double[] coefficients);
native_calc_bands_coefs(int sampleRate, Band[] bands, double[][] coefficients);
native_filter_evaluate_freqs(int sampleRate, int frequency, int type, float Q,
    float minFreq, float maxFreq, int pointCount, float gain, float[] response);
```
Single-filter coefficient calculation invokes the shared kernel with zero gain; frequency-response calculation receives an explicit gain. EQ preset serialization begins with `PaP\x01`; recovered records include filter type, frequency, Q, enabled, channel, reserved bytes and band index.

Auralis uses the supplied coefficient equations and normalization. Its per-sample biquad state recurrence is a standard implementation choice; the pasted research did not give the exact native per-sample state-update instruction sequence.

### Smooth EQ, master volume and DVC

Recovered master-volume route:
`xl0.s() → le0.H(...,27,0,2) → payload[8]=131072 & changeFlags → payload[9]=raw float bits of gj.H → Pipeline2.m265() → native_route_msg() → message 27 → handler 0x21FECC → 0x21FF2C–0x21FF38`.

The native handler stores master volume mB at `+0xCC`, converts it using `exp2f`, and stores linear master gain at `+0xD8`. Message 28 carries EQ preamp mB (`hj.f2591`) to `+0xC4`.

No-DVC setter `equ5_float_set_no_dvc_headroom_mb`; preference `no_dvc_gain_mb`; default −600 mB; min −1000, max 0, step 50; field `+0xC8`. Per-track setter `equ5_float_set_track_volume_linear`; record field `+0x8C + 32*i`; range −20,000 to +1,600 mB. This track-volume range is not applied to ReplayGain preferences.

Combined gain inputs are master, EQ preamp, no-DVC and track gain. The recovered border-gain ceiling expression is `C_dB = 12.5 - S/120`, with a 15 dB fallback and a reduced-ceiling condition around −298 mB. The supplied material does not fully define `S` and the full branch predicates, so Auralis preserves the known values without manufacturing the whole protection branch.

### Balance, volume modes, resampling and dither

Balance attenuation magnitude is `10^(-48 × abs(b) / 20)`. Sign-to-channel routing is not recovered. Mono and Balance share descriptor slot 3.

Volume-mode names from `im.java` and `el1.java`: NORMAL, NORMAL_SOFTWARE, CUSTOM, SOFTWARE, HARDWARE, HARDWARE_DAP, FIXED, UNDECIDED. Numeric native IDs were not recovered. The volume-level list positions 1–101 map to values 0–100, not mode IDs. Preferences include `dvc_enabled=true` and `compensate_dvc_vol=false` in some Android 15+ contexts.

Resampler preferences: `resampler_type` default 0; `resampler_cutoff` default 97, range 80–99. P2D families R={8,16,32,64,128}, N={3,4}; FAST flag 0x11, BALANCED flag 0x1F. The official description names a SoX resampler; the exact kernel is not given here.

Dither modes 1–9: None, Rectangular, Triangular, Triangular high-pass, Noise shaping F-weighted, Modified E-weighted, Improved E-weighted, Shibata, Low-Shibata. Noise-shaping coefficient tables and recurrence are not supplied.

### DSD, Tempo, decoders, outputs and compressor

| DSD format | 44.1 kHz family | 48 kHz family |
|---|---:|---:|
| DSD64 | 2.8224 MHz | 3.072 MHz |
| DSD128 | 5.6448 MHz | 6.144 MHz |
| DSD256 | 11.2896 MHz | 12.288 MHz |
| DSD512 | 22.5792 MHz | 24.576 MHz |
| DSD1024 | 45.1584 MHz | 49.152 MHz |

Cutoff: if `r >= 0.833`, `c=0.85`; otherwise `c=nextafter(r/0.98,0)`. Exact DSD64 coefficients:
```text
0.000037487688268165004
0.000037487688264928030
0.001318843318019954700
0.021512542988272119000
0.166867293178567580000
0.648930895686424810000
```

Exact DSD128 coefficients:
```text
0.000014693280767999990
0.000014693280774056924
0.000680244479988591400
0.012597119999973313000
0.116639999999997970000
0.540000000000002260000
```

These arrays and all five DSD rate families are retained in Auralis's native reference header. They do not by themselves constitute a full DSD processing engine.

Tempo user range is 0.5×–3.0×; native strings include `tempo_process`, `play.tempo_factor`, `invalid tempo factor`. The time-stretch kernel is not specified.

Identified decoders: FFmpeg, Android AMediaCodec, WavPack, Musepack and OpenMPT. Identified outputs: AudioTrack, OpenSL ES, AAudio, Hi-Res, Chromecast, USB Exclusive and hidden benchmark path. Auralis currently uses Android MediaExtractor/MediaCodec and AudioTrack only. The Poweramp description reports separate decoder/DSP/output threads, optional 64-bit processing, and no allocation/locking/blocking on its audio path; Auralis uses separate worker threads and preallocated native queues but does not reproduce the proprietary backends.

Compressor evidence is UI/configuration only: `p000/wm0.java`, `dsp_compressor_enabled`, `peq_compr`, `msg_dsp_compressor_changed`. It does not specify a native compressor algorithm or DSP-chain position.

### Current Poweramp product capabilities from the supplied reference

The pasted reference's product summary lists MP3, FLAC, ALAC, APE, DSD, WavPack, Opus, WMA, TTA, MKA, TAK and tracker formats; Hi-Res PCM up to 768 kHz where hardware allows; DSD64–DSD1024; USB DAC and Bluetooth; 64-band parametric EQ and 5–32-band graphic EQ; AutoEQ; ReplayGain; limiter, Reverb and Tempo; AudioTrack, OpenSL ES, AAudio, Hi-Res, USB Exclusive and Chromecast; 64-bit processing, resampling and dither. These are Poweramp capabilities as summarized in the supplied reference, not claims that the current Auralis app already implements all of them.
