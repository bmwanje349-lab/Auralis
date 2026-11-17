#pragma once

#include <cstddef>
#include <cstdint>

namespace auralis {

/**
 * Two indexed fade contexts matching the recovered Poweramp crossfade model.
 * The recovered request path calculates fading_frames from sample rate and
 * duration, then stores a positive fading_delta_per_block.
 */
class PowerampTransitionMixer {
public:
    explicit PowerampTransitionMixer(int sampleRate) noexcept;

    bool startCrossfade(int durationMs, int blockFrames) noexcept;
    bool startSingleFade(float startGain, float endGain, int durationMs,
                         int blockFrames) noexcept;
    void reset() noexcept;

    void processCrossfade(const float* outgoing, const float* incoming,
                          float* output, std::size_t frames) noexcept;
    void processSingle(const float* input, float* output,
                       std::size_t frames) noexcept;

    bool crossfadeComplete() const noexcept { return crossfadeComplete_; }
    bool singleFadeComplete() const noexcept { return singleFadeComplete_; }
    float outgoingGain() const noexcept { return contexts_[0].gain; }
    float incomingGain() const noexcept { return contexts_[1].gain; }
    float singleGain() const noexcept { return contexts_[0].gain; }
    std::uint64_t fadingFrames() const noexcept { return fadingFrames_; }
    float fadingDeltaPerBlock() const noexcept { return fadingDeltaPerBlock_; }
    int sampleRate() const noexcept { return sampleRate_; }

private:
    enum class ContextState : std::uint8_t { Idle, Fading, Complete };
    struct FadeContext {
        int index = 0;
        ContextState state = ContextState::Idle;
        float gain = 1.0f;
        float deltaPerBlock = 0.0f;
        float direction = -1.0f;
    };

    bool begin(std::uint64_t totalFrames, int blockFrames) noexcept;
    void advanceCrossfadeBlock() noexcept;
    void advanceSingleBlock() noexcept;

    int sampleRate_;
    int blockFrames_ = 1024;
    std::uint64_t fadingFrames_ = 0;
    std::uint64_t processedFrames_ = 0;
    int framesIntoBlock_ = 0;
    float fadingDeltaPerBlock_ = 0.0f;
    float singleEndGain_ = 0.0f;
    FadeContext contexts_[2]{};
    bool crossfadeComplete_ = true;
    bool singleFadeComplete_ = true;
    bool singleFadeActive_ = false;
};

} // namespace auralis
