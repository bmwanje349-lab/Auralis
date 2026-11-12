#pragma once

#include <cstddef>
#include <vector>

namespace auralis {

/**
 * Clip protection: a transparent look-ahead true-peak limiter for the final stereo Float32 output.
 *
 * Many downloaded tracks (especially lossy re-encodes of loud masters) decode with peaks several dB
 * above full scale. The audio path is Float32, so nothing clips until the very last step, where the
 * Android mixer or the DAC chops those peaks off flat. That flat-topping is the harsh, gritty
 * "poor quality" sound. Extra DSP (bass boost, EQ, crossfade sums) makes it worse.
 *
 * This stage sits at the end of the chain, after every effect and after crossfades, and guarantees
 * that no sample or inter-sample peak leaves above the ceiling, while leaving quiet material
 * untouched (gain stays exactly 1.0).
 *
 * How it works, and why the guarantee holds:
 *  - The required gain for each frame is min(1, ceiling / peak). The peak is the larger of the
 *    two channels (stereo-linked, so the image never shifts) and also includes 4x-oversampled
 *    values (12-tap windowed-sinc polyphase interpolation) in the gaps on either side of the
 *    frame, so peaks that fall between samples are caught.
 *  - A sliding minimum over the look-ahead window, followed by a moving average over the same
 *    window length, gives a smooth ramp that reaches the required gain no later than the loud
 *    sample arrives (the average of values that are each <= the requirement cannot exceed it).
 *  - Release is a one-pole recovery, so gain can only come back up slowly. A slower-than-the-target
 *    gain can never be higher than the target, so the guarantee is kept.
 *  - A final clamp at +/-1.0 only catches float rounding; it does not shape the sound.
 *
 * Latency is latencyFrames() (about 2 ms). Output length equals input length; the delayed tail is
 * emitted by the next call, so the same instance must process one continuous stream. Not thread-safe;
 * call from the output thread only. process() never allocates.
 */
class SafetyLimiter {
public:
    explicit SafetyLimiter(int sampleRate, double ceilingDb = -0.3);

    /** When disabled the stage is a bit-exact pass-through with no delay. Toggling resets state. */
    void setEnabled(bool enabled) noexcept;
    bool enabled() const noexcept { return enabled_; }

    /** Clears the delay line and gain history (use after a seek, stop or stream change). */
    void reset() noexcept;

    /** In-place on interleaved stereo float frames. */
    void process(float* interleavedStereo, std::size_t frames) noexcept;

    std::size_t latencyFrames() const noexcept { return audioDelay_; }
    double ceilingLinear() const noexcept { return ceiling_; }
    /** Deepest gain reduction seen since reset(), in dB (<= 0). */
    double maxReductionDb() const noexcept;
    /** Current applied gain (1.0 means untouched). */
    double currentGain() const noexcept { return static_cast<double>(gain_); }

private:
    struct Stereo { float l = 0.0f, r = 0.0f; };

    static constexpr int kTapsPerPhase = 12;   // interpolation taps per sub-sample phase
    static constexpr int kDetectDelay = 6;     // detector looks at frame n-6 when frame n arrives

    void step(const Stereo& in, Stereo& out) noexcept;

    bool enabled_ = true;
    int sampleRate_;
    double ceiling_;

    std::size_t window_;      // look-ahead window length L (frames)
    std::size_t audioDelay_;  // audio delay: (L - 1) + kDetectDelay frames
    float releaseCoef_;

    // Interpolation weights for the 1/4, 2/4 and 3/4 sub-sample positions (taps x[m-5..m+6]).
    float taps_[3][kTapsPerPhase]{};

    // Recent input frames, ring of size audioDelay_ + 1.
    std::vector<Stereo> audio_;
    std::size_t audioPos_ = 0;
    // The twelve newest input frames, oldest first: hist_[11] is x[n].
    Stereo hist_[kTapsPerPhase]{};

    // Sliding minimum of the required gain over the last L values (monotonic deque, ring storage).
    std::vector<float> dqValue_;
    std::vector<std::size_t> dqIndex_;
    std::size_t dqHead_ = 0, dqCount_ = 0;
    std::size_t detectIndex_ = 0;

    // Moving average of the sliding minimum over the last L values.
    std::vector<float> avgRing_;
    std::size_t avgPos_ = 0;
    double avgSum_ = 0.0;

    float gain_ = 1.0f;
    float minGain_ = 1.0f;
    float prevGapPeak_ = 0.0f;  // oversampled peak in the gap before the detector frame
};

}  // namespace auralis
