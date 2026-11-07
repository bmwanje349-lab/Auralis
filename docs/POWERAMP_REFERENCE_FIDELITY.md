# Poweramp reference fidelity and conformance record

This document is the implementation contract for the supplied research record. It distinguishes a recovered equation or mapping from an item for which only names, ranges, constants, or partial structure were supplied. Auralis does not label an inferred substitute as Poweramp-equivalent.

## Implemented and covered by native tests

### StereoWide — native Float32 arithmetic and DSPI integration

- Native descriptor/configuration region: `0x220380`; Float32 callback: `0x21CBC0`; Float64 callback: `0x21EBA0`; parameter handler: `0x21FFB8`; built-in DSPI plug-in index: 2.
- The parameter handler clamps the normalized Float32 control to `[0,1]`, computes `g = fma(2,p,1)` in Float32, stores that Float32 gain at context `+0xC0`, and sets/clears enable flag bit `0x04` in context `+0x6C`. The width gain is written directly; the recovered handler does not apply a separate width ramp.
- User-facing percentage mapping: `p = clamp(Float32(sliderPercent)/100, 0, 1)`; `g = fma(Float32(2), p, Float32(1))`. Thus 0%→1.0, 25%→1.5, 50%→2.0, 75%→2.5, and 100%→3.0.
- Per frame, the native callback computes `M=(L+R)*0.5`, then independently computes `S_L=L-M` and `S_R=R-M`, and writes `L'=fma(S_L,g,M)`, `R'=fma(S_R,g,M)`. This is algebraically equivalent to the usual Mid/Side equations but preserves the native operation order and fused Float32 rounding. The Float64 callback uses the same Float32 context gain converted to double for its wider sample path.
- At 100%, the result is `L'=2L-R`, `R'=2R-L`; a dual-mono input has zero side and is unchanged. Auralis now matches the Float32 gain storage, midpoint/side operation grouping, FMA output, and shared gain used by its Float32/Float64 entry points.
- Verified against the native code (2026-11-07) with `tools/reverb-oracle/stereowide_oracle.cpp`, which calls the installed Float32 (`0x21CBC0`) and Float64 (`0x21EBA0`) callbacks directly: 624 randomized cases per width (sliders 0, 1e-7, 1e-5, 10, 25, 33.3, 50, 75, 99.9, 100, 150, -20 and NaN; 16 block lengths covering vector and tail paths; three input levels) are bit-identical. The native handler at `0x21FFB8` sets flag `0x04` only when the clamped width is not exactly 0, even when `g` rounds to 1.0f, so Auralis bypasses on exactly-zero width rather than on `g == 1.0f`.
- Auralis tests verify all five slider points, a non-binary slider value, Float32 and Float64 arithmetic against the corresponding native operation order, NaN/infinity clamping, bypass, and mono invariance.

### Built-in DSPI order and separate Reverb plug-in

The native `0x21CBC0` callback first invokes its registered DSP plug-in callback list, then runs its built-in DSPI stages. The recovered DSPI order is **StereoWide → Mono → Balance → Limiter**: StereoWide uses context flag bit `0x04` and gain `+0xC0`; Mono follows under flag bit `0x08`; Balance follows under its channel attenuation flags; the limiter branches follow Balance. Auralis already uses this same relative order in `AuralisDsp::process()` and now has integration tests proving that Mono/Balance see the widened samples and that the limiter detects the post-widener peak.

Poweramp's built-in plug-in registry lists `com.maxmpz.audioplayer/plugin.reverb` as a separate hidden `PA_PLUGIN_TYPE_INTERNAL_DSP`, while `com.maxmpz.audioplayer/plugin.dspi` is the internal DSPI processor. Reverb is not part of the StereoWide algorithm; it is a separate upstream DSP plug-in callback. The recovered callback dispatch precedes the built-in DSPI operations. Auralis now has an audible, stateful experimental Reverb stage wired through its UI, service, JNI, and native audio pipeline ahead of StereoWide. Its late stereo matrix is not yet a reconstruction of Poweramp's native matrix; see the comparison and explicit non-claims in the Reverb section below.

### Parametric EQ — recovered six biquad equations

Verified against the native coefficient kernel (2026-11-07) with `tools/reverb-oracle/eq_coeff_oracle.cpp`, which calls `0x306FD8` directly (`int kernel(double out[5], int sampleRate, int freqHz, int type, float q, float gainDb)`): all six filter types are bit-identical in 34,398 cases (sample rates 8 kHz to 192 kHz, frequencies 1 Hz to 24 kHz including values at or above Nyquist, Q 0.1 to 12, gain -15 to +15 dB). What the comparison showed: the kernel receives Q and gain as Float32; clamps the frequency to `[0, sampleRate/2 - 1]`; uses `A = pow(10, gain * 0.025)`; builds the shelf `beta` as `sqrt(A) * (alpha + alpha)`; and normalizes by multiplying every coefficient by `1 / a0` rather than dividing, each of which moves the result by a few ulps compared with the textbook form. Auralis now follows that exact operation order. Not verified: the per-sample filter recurrence that runs these coefficients, the range checks in the JNI wrapper (`0x30750C`), and the channel numbering.

The native engine calculates normalized coefficients for low-pass, high-pass, band-pass, peaking, low-shelf, and high-shelf filters. It uses the supplied definitions for `w`, `alpha`, `A`, and `beta`, the coefficient normalization by `a0`, the 1–24,000 Hz / Q 0.1–12 / ±15 dB limits, and channel codes in the recovered range 0–2. The supplied record confirms left, right and combined operation but does not identify which numeric code selects each. Auralis uses a documented local mapping (0=both, 1=left, 2=right) for its own band model; that mapping is not claimed to be the Poweramp enum mapping. EQ processing state and coefficients use `double` precision. The player UI exposes the two recovered Bass/Treble defaults; the native engine supports 64 band slots.

### Limiter — linked-peak detector and two protection paths implemented

- Native plugin index: 1; shared DSPI float and double processing callbacks.
- The hard path compares the linked stereo peak to 1.0; the configurable path compares it to the context ceiling at `+0xF4`.
- The linked peak is `max(abs(L), abs(R))`; the four-frame native path takes the maximum across both channels in all four frames before changing the envelope.
- On over-ceiling input the target is `G_target = ceiling / peak`. The target envelope drops immediately to a lower target; the applied gain moves down by the attack increment and is clamped not to undershoot the target.
- When the peak is below the ceiling, target and applied gain recover toward unity by the release increment.
- Scalar attack/release increments are 1/256 and 1/16384. The four-frame vector path uses four times those increments per four-frame group.
- The two native protection modes select different ceilings but share the same applied-gain and envelope state; they are not separate retained limiter instances.
- Verified against the native code (2026-11-07) with `tools/reverb-oracle/limiter_oracle.cpp`, which loads the context fields (`+0xEC`, `+0xF0`, `+0xF4`, `+0xF8..+0x104`) and calls the installed Float32 (`0x21CBC0`) and Float64 (`0x21EBA0`) callbacks over a 40-second-class signal with loud bursts, one-sided overs and long recoveries, using random block lengths 1 to 1024. Output samples and both state floats are bit-identical in all 10 scenarios (hard ceiling, configurable 0.5, 0.8, 2.0 and the 999.999 unbounded sentinel).
- Native detail that mattered: all limiter state and arithmetic is Float32 (the earlier double-precision implementation drifted by a few ulps). An overload step lowers the target to `min(target, ceiling/peak)` and does not release; a non-overload step releases the target by the increment only while it is below 1, clamped at 1; the applied gain then moves down by the attack increment or up by the release increment and is clamped to the target. The peak is an FMAXNM across the samples, and a gain of exactly 1.0 skips the multiply.
- Not independently verified: the values stored in `+0xF8..+0x104` (the 1/16384 and 1/256 increments and their four-frame multiples) come from the earlier recovery; the comparison loads those values into the native context rather than reading them from native initialization. The function that writes them has not been located.

Native context layout: `+0xEC` applied gain; `+0xF0` target envelope; `+0xF4` configurable ceiling; `+0xF8` scalar release increment; `+0xFC` scalar attack increment; `+0x100` four-frame release increment; `+0x104` four-frame attack increment. Enable flags are in `+0x6C`: hard path bit `0x02`, configurable path bit `0x100`; the capability selector is `+0x128 bit 7`.

The configurable ceiling is recalculated from the accumulated upstream linear gain: when that gain exceeds 0.001 the setter writes its reciprocal to `+0xF4`; otherwise it writes the unbounded sentinel 999.999. Auralis currently derives that gain from the ReplayGain, EQ-preamp and master-gain values represented in its playback graph. The native Poweramp graph also includes no-DVC and per-track gain fields; those two gain sources have not yet been connected to Auralis's player metadata/settings.

### Balance and Mono — native DSPI sample processing

- Balance input is clamped to `[-1,+1]`.
- Gain law (superseded, see below): earlier notes gave `G=10^(-48*abs(b)/20)`; the native handler (`0x21FC10`, plugin type 3, case at `0x21FE5C`) computes in Float32: the value clamps to [-1, 1] (NaN and values below -1 become -1); positive values set flag `0x10` and multiply the left channel by `exp2f(b * -7.97263)` stored at `+0x108`; negative values set flag `0x20` and multiply the right channel by `exp2f(b * 7.97263)` stored at `+0x10C` (constants `0xC0FF1FC4` / `0x40FF1FC4`); exactly +1 or -1 stores a gain of exactly 0, so that channel is silent rather than 48 dB down; 0 clears both flags. Mono (flag `0x08`) runs first and writes `(L+R)*0.5` to both channels. Auralis matches this bit for bit; checked by `tools/reverb-oracle/balance_mono_oracle.cpp` against the installed library (528 cases incl. NaN, infinity, 0, +/-1).
- Positive balance activates the left-channel attenuation branch; negative balance activates the right-channel branch.
- Mono uses `M=(L+R)/2` and writes `M` to both output channels.
- The native DSPI callback applies Mono before Balance; Auralis preserves that relative order.
- Both controls are connected to the audio path and covered by native unit tests.

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

Auralis's Reverb control model follows the native setter's parameter contract. Inputs are clamped to `[0,1]` using Float32 semantics; Size maps to `0.01 + 0.99 × UI`; Damp is stored as `1 - UI`; Filter, Fade, Pre-Delay, Mix, and Pre-Delay Mix are identity values. The eighth native setter slot is flag-only and does not store a p8 field. The app exposes seven controls and persists their values across track sessions.

The native allocator at `0x16BF40` creates 15 ring buffers in four aligned-capacity groups: 4 × 12.6953125 ms, 6 × 88.8671875 ms, 4 × 149.4140625 ms, and 1 × 199.21875 ms. Capacity allocation uses Float32 `Fs × seconds` and ARM `FCVTAS` rounding; the backing allocations use aligned storage and padded strides. These are capacities, not the active tap geometry. The first four active all-pass delays are fixed at 4.8, 3.6, 12.7, and 9.3 ms, with feedback gains `0.75, 0.75, 0.625, 0.625`. Eight further active periods scale with Size (`20, 60, 30, 89, 150, 120, 140, 110` ms); two additional tail periods are fixed at 89 and 69 ms. **Pre-delay is not a millisecond value**: the active input-ring length is `FCVTZS(Float32(PreDelay × 200))`, a 0–200-sample control independent of sample rate, then bounded by its sample-rate-dependent allocated capacity. For example, UI 15% gives 30 active samples at 44.1, 48, and 96 kHz.

The `0x169A30` reconfiguration helper also computes 24 auxiliary tap offsets. Four Size-scaled late-ring triplets use Float32 ratios `{.067,.011,.121}`, `{.036,.089,0}`, `{.0089,.099,0}`, and `{.067,.0041,0}` multiplied by `Fs × Size`, truncated with `FCVTZS`, and clamped to each ring's active length. Two six-tap packed banks use fixed sample-rate ratios `{.0199,.0219,.0354,.0389,.0414,.0692}` and `{.0099,.011,.0182,.0189,.0213,.0431}`; these do not scale with Size. Auralis's control model now calculates the 15 active lengths and all 24 offsets with the recovered Float32/truncation/clamping behavior. Native-oracle tests cover the full 24-value map at 48 kHz and 44.1 kHz.

The active audible Float32 sample processor is the path at `0x16847C`; its per-block parameter/delay update helper is `0x169A30`. The separate wide-sample conversion adapter passes through `0x1684A0` and the alternate processing core at `0x168940`. These are distinct paths. The first four serial all-pass cells, the two later branches using a Fade-derived coefficient, and the separate Filter/Damp low-pass controls are now represented in Auralis. Fade's native late all-pass coefficient is `clamp(fma(0.7994999886, Fade, 0.0049999999) + 0.150000006, 0.25, 0.5)`. Pre-delay length and Fade coefficient are ramped across DSP blocks in Auralis.

The later stereo field is not a bank of six independent delays. Poweramp reads multiple tap offsets across the paired late delay buffers and combines them with lane reversal and a weighted stereo SIMD feedback matrix, then writes back into packed stereo buffers. Auralis now has the native delay and tap geometry as a verified control model, but its current audible late field is still a captured-profile MIMO convolution rather than the native per-sample feedback recurrence. The tap map is not yet driving the convolution engine. This is deliberately not claimed as full Poweramp DSP parity. The auxiliary callback at `0x16C3A0` and its `+0xD44` analysis-mode branches process 512-sample visualization/analysis blocks; they are not the audible Reverb kernel.

A direct 48 kHz Float32 impulse comparison was run with matching default parameter values and 8,192 stereo frames. Poweramp measured left/right energies `1.08809689 / 0.06436194` and peak `0.99906212`; the current Auralis prototype measured `1.00176166 / 0.00135193` and peak `1.0`. Normalized sample RMSE was approximately `0.36934` (36.9%). The small Auralis right-channel tail demonstrates that its current late stereo network does not match Poweramp. The setter, active delay timing, all auxiliary tap positions, and selected transition/update behavior are recovered; the late packed-tap/matrix recurrence and its exact per-sample feedback state remain unfinished. Do not claim full Reverb parity until that matrix is ported and its impulse/block output is shown to converge against the native oracle.

## Retained recovered settings and constants

The native reference model also retains:

- Balance law and sign routing are implemented and verified against the native code: positive normalized balance attenuates left; negative attenuates right. The native handler (`0x21FC10`, plugin type 3, case at `0x21FE5C`) computes in Float32: the value clamps to [-1, 1] (NaN and values below -1 become -1); positive values set flag `0x10` and multiply the left channel by `exp2f(b * -7.97263)` stored at `+0x108`; negative values set flag `0x20` and multiply the right channel by `exp2f(b * 7.97263)` stored at `+0x10C` (constants `0xC0FF1FC4` / `0x40FF1FC4`); exactly +1 or -1 stores a gain of exactly 0, so that channel is silent rather than 48 dB down; 0 clears both flags. Mono (flag `0x08`) runs first and writes `(L+R)*0.5` to both channels. Auralis matches this bit for bit; checked by `tools/reverb-oracle/balance_mono_oracle.cpp` against the installed library (528 cases incl. NaN, infinity, 0, +/-1).
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

The Android player uses separate decoder, DSP and output worker threads. The native DSP pipeline uses preallocated fixed-size stereo buffers and lock-free SPSC queues. Settings cross to the DSP worker through a bounded control queue; the DSP worker does not take a UI mutex. The recovered built-in DSPI relative order is StereoWide, Mono fold-down, Balance, then Limiter; the separate DSP plug-in callback list is dispatched before those internal DSPI operations. Auralis applies ReplayGain/EQ-preamp/master gain before EQ, then its experimental Reverb stage, followed by StereoWide, Mono, Balance and Limiter. The Reverb is not combined with StereoWide. Auralis does not claim full Poweramp pipeline parity because the native late Reverb matrix, remaining proprietary modules and all output-backend-specific insertion points have not been reproduced. Android `MediaCodec` and `AudioTrack` remain platform I/O components, not copies of Poweramp decoders or output backends.

## Deliberate non-claims

The supplied record does not contain enough information to reproduce the full proprietary engine exactly: the complete Reverb packed-tap stereo recurrence (still not ported in Auralis), time-stretch implementation, exact SoX/P2D resampler kernel, dither noise-shaping filters, compressor algorithm, complete DSD engine, ReplayGain tag parser, full output driver selection logic, and exact complete DSP stage ordering are not specified. Auralis does not replace those with unrelated DSP while calling them equivalent.

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
| `+0x6C` | DSPI enable flags: hard limiter `0x02`, StereoWide `0x04`, Mono `0x08`, positive Balance direction `0x10`, negative Balance direction `0x20`, configurable limiter `0x100` |
| `+0x8C + 32*i` | Per-track volume millibels for record i |
| `+0xC0` | StereoWide width field |
| `+0xC4` | EQ preamp |
| `+0xC8` | No-DVC headroom |
| `+0xCC` | Master-volume millibels |
| `+0xD8` | Derived linear master gain |
| `+0xEC` | Limiter applied gain |
| `+0xF0` | Limiter target envelope gain |
| `+0xF4` | Configurable limiter ceiling; reciprocal accumulated gain or 999.999 sentinel |
| `+0xF8` | Scalar release increment |
| `+0xFC` | Scalar attack increment |
| `+0x100` | Four-frame vector release increment |
| `+0x104` | Four-frame vector attack increment |
| `+0x128 bit 7` | Limiter protection-path selection |
| `+0x134`, `+0x13C` | Additional dynamic gain context fields |

### Limiter state and timing

The hard-ceiling path uses a 1.0 detector ceiling; the configurable path uses `+0xF4`. Both share the same `+0xEC` applied gain and `+0xF0` target envelope. The native linked detector measures the maximum absolute sample across L/R; the SIMD path measures eight scalar samples for a four-frame stereo group. On overload, target gain is `ceiling/peak`; the target envelope decreases to that value and the applied gain is pulled toward it by the attack increment. Below ceiling, the envelope and applied gain recover toward 1.0 by the release increment. Scalar attack/release are 1/256 and 1/16384; four-frame vector increments are 4/256 and 4/16384. At 48 kHz, the scalar full-range times are 5.333 ms and 341.333 ms. The branch is therefore implemented as an actual native-linked peak limiter in Auralis, rather than an externally supplied target-only envelope helper.

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
| 7 | native flag operation | No parameter-field write; writes update flag value 1 |

Every service value sent to the native setter is clamped to `[0,1]` using native min-number/max-number operations. Update flags: indices 0 (Size) and 3 (Pre-delay) write value 2; indices 1,2,4,5,6 and 7 write value 1. The Java service's eight stored float slots default to zero; the visible panel exposes p1–p7 only. Slot 8 maps to the flag-only native setter index 7, not an eighth audible control.

The allocator initializes 15 delay rings across four capacity families: 4 × `roundFloat32(Fs*0.0126953125)`, 6 × `roundFloat32(Fs*0.0888671875)`, 4 × `roundFloat32(Fs*0.1494140625)`, and 1 × `roundFloat32(Fs*0.19921875)` samples. Allocation strides are padded to 16 samples and storage is obtained as a 64-byte-aligned block. The first four active all-pass delays are fixed at 4.8, 3.6, 12.7, and 9.3 ms; eight later active periods scale with Size and two final tail periods are fixed. Fade controls the two later all-pass coefficients; Filter and Damp feed the two low-pass cutoff controls.

Initialization message type 1 carries enable/configuration, type 2 carries the one-based parameter slot; reset clears and disables; parameter edits clear the selected-preset reference. The active audible Float32 sample processor is `0x16847C`; its parameter/delay update helper is `0x169A30`. The wide-sample adapter is `0x1684A0` and calls the alternate processing core at `0x168940`. The `+0xD44` mode selector with values 1–3 is in the auxiliary 512-sample analysis callback at `0x16C3A0`, whose output is consumed by visualization. It is not a three-mode wet-audio selector. Auralis contains an audible experimental Reverb processor with UI/service/JNI/native control wiring, but its late packed-tap stereo feedback matrix is still a simplified surrogate; the measured reference mismatch is documented in the primary Reverb section above.

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

Balance: the native handler (`0x21FC10`, plugin type 3, case at `0x21FE5C`) computes in Float32: the value clamps to [-1, 1] (NaN and values below -1 become -1); positive values set flag `0x10` and multiply the left channel by `exp2f(b * -7.97263)` stored at `+0x108`; negative values set flag `0x20` and multiply the right channel by `exp2f(b * 7.97263)` stored at `+0x10C` (constants `0xC0FF1FC4` / `0x40FF1FC4`); exactly +1 or -1 stores a gain of exactly 0, so that channel is silent rather than 48 dB down; 0 clears both flags. Mono (flag `0x08`) runs first and writes `(L+R)*0.5` to both channels. Auralis matches this bit for bit; checked by `tools/reverb-oracle/balance_mono_oracle.cpp` against the installed library (528 cases incl. NaN, infinity, 0, +/-1). Mono and Balance share descriptor slot 3.

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


### Reverb parameter sensitivity (measured 2026-11-07)

The impulse-response comparison near the top of the Reverb section (36.9% RMSE) predates the captured-response engine. At the captured preset Auralis now reproduces Poweramp's steady-state output, and the native-oracle tests pass. The capture is valid for that one preset only.

`tools/reverb-oracle/grid_oracle_ir.cpp` and `grid_sweep.py` load the installed Poweramp library, capture the left-input impulse response at chosen settings (12 warm-up blocks), and report normalized RMSE against the preset's response over 16,384 frames. Changing one native parameter at a time from the preset gave:

| Native index | Effect on the response |
|---|---|
| 0 | None in this harness at any value tested (0, 0.3, 0.55, 1) |
| 1 (Filter) | 55% to 100% error |
| 2 (Fade) | 19% to 69% error |
| 3 | Reshapes the late field and tail; at 1.0 the tail past 16,384 frames is audible; error above 100% |
| 5 (Pre-delay Mix) | 50% to 100% error |
| 6 (Damp) | 31% to 77% error |

A single captured response therefore cannot follow these controls. Matching Poweramp across settings requires porting the native per-sample recurrence: the parameter-to-filter conversion (uses `sincosf`) at `0x16847C`, the reconfiguration helper at `0x169A30`, the four serial all-pass cells, and the late tap/matrix network. That port is not done. Index 0 showing no effect is unexplained and should be checked before relying on the index-to-control mapping.
