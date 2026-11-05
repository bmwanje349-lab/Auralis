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

The native engine calculates normalized coefficients for low-pass, high-pass, band-pass, peaking, low-shelf, and high-shelf filters. It uses the supplied definitions for `w`, `alpha`, `A`, and `beta`, the coefficient normalization by `a0`, the 1–24,000 Hz / Q 0.1–12 / ±15 dB limits, and channel modes (both, left, right). EQ processing state and coefficients use `double` precision. The player UI exposes the two recovered Bass/Treble defaults; the native engine supports 64 band slots.

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
