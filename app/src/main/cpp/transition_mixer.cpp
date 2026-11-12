#include "transition_mixer.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace auralis {

PowerampTransitionMixer::PowerampTransitionMixer(int sampleRate) noexcept
    : sampleRate_(std::max(1, sampleRate)) {
    reset();
}

bool PowerampTransitionMixer::begin(std::uint64_t totalFrames, int blockFrames) noexcept {
    blockFrames_ = std::max(1, blockFrames);
    fadingFrames_ = totalFrames;
    processedFrames_ = 0;
    framesIntoBlock_ = 0;
    if (fadingFrames_ == 0) {
        fadingDeltaPerBlock_ = 1.0f;
        return false;
    }
    fadingDeltaPerBlock_ = static_cast<float>(static_cast<double>(blockFrames_) /
                                              static_cast<double>(fadingFrames_));
    if (!std::isfinite(fadingDeltaPerBlock_) || fadingDeltaPerBlock_ <= 0.0f) {
        fadingDeltaPerBlock_ = 1.0f;
    }
    return true;
}

bool PowerampTransitionMixer::startCrossfade(int durationMs, int blockFrames) noexcept {
    const auto safeDuration = std::max(0, durationMs);
    const std::uint64_t totalFrames = static_cast<std::uint64_t>(sampleRate_) *
                                      static_cast<std::uint64_t>(safeDuration) / 1000ULL;
    contexts_[0] = FadeContext{0, ContextState::Fading, 1.0f, 0.0f, -1.0f};
    contexts_[1] = FadeContext{1, ContextState::Fading, 0.0f, 0.0f, 1.0f};
    singleFadeActive_ = false;
    crossfadeComplete_ = totalFrames == 0;
    singleFadeComplete_ = true;
    const bool valid = begin(totalFrames, blockFrames);
    if (!valid) {
        contexts_[0].gain = 0.0f;
        contexts_[1].gain = 1.0f;
        contexts_[0].state = ContextState::Complete;
        contexts_[1].state = ContextState::Complete;
        crossfadeComplete_ = true;
        return false;
    }
    contexts_[0].deltaPerBlock = fadingDeltaPerBlock_;
    contexts_[1].deltaPerBlock = fadingDeltaPerBlock_;
    return true;
}

bool PowerampTransitionMixer::startSingleFade(float startGain, float endGain,
                                               int durationMs, int blockFrames) noexcept {
    const auto safeDuration = std::max(0, durationMs);
    const std::uint64_t totalFrames = static_cast<std::uint64_t>(sampleRate_) *
                                      static_cast<std::uint64_t>(safeDuration) / 1000ULL;
    const float start = std::clamp(startGain, 0.0f, 1.0f);
    const float end = std::clamp(endGain, 0.0f, 1.0f);
    contexts_[0] = FadeContext{0, ContextState::Fading, start, 0.0f, end >= start ? 1.0f : -1.0f};
    contexts_[1] = FadeContext{1, ContextState::Idle, 0.0f, 0.0f, 1.0f};
    singleFadeActive_ = true;
    singleEndGain_ = end;
    crossfadeComplete_ = true;
    singleFadeComplete_ = totalFrames == 0 || start == end;
    const bool valid = begin(totalFrames, blockFrames);
    if (!valid || start == end) {
        contexts_[0].gain = end;
        contexts_[0].state = ContextState::Complete;
        singleFadeComplete_ = true;
        return false;
    }
    contexts_[0].deltaPerBlock = fadingDeltaPerBlock_;
    return true;
}

void PowerampTransitionMixer::reset() noexcept {
    contexts_[0] = FadeContext{0, ContextState::Idle, 1.0f, 0.0f, -1.0f};
    contexts_[1] = FadeContext{1, ContextState::Idle, 0.0f, 0.0f, 1.0f};
    fadingFrames_ = 0;
    processedFrames_ = 0;
    framesIntoBlock_ = 0;
    fadingDeltaPerBlock_ = 0.0f;
    singleEndGain_ = 0.0f;
    blockFrames_ = 1024;
    crossfadeComplete_ = true;
    singleFadeComplete_ = true;
    singleFadeActive_ = false;
}

void PowerampTransitionMixer::advanceCrossfadeBlock() noexcept {
    if (crossfadeComplete_) return;
    contexts_[0].gain = std::max(0.0f, contexts_[0].gain - contexts_[0].deltaPerBlock);
    contexts_[1].gain = std::min(1.0f, contexts_[1].gain + contexts_[1].deltaPerBlock);
    if (processedFrames_ >= fadingFrames_ ||
        (contexts_[0].gain == 0.0f && contexts_[1].gain == 1.0f)) {
        contexts_[0].gain = 0.0f;
        contexts_[1].gain = 1.0f;
        contexts_[0].state = ContextState::Complete;
        contexts_[1].state = ContextState::Complete;
        crossfadeComplete_ = true;
    }
}

void PowerampTransitionMixer::advanceSingleBlock() noexcept {
    if (singleFadeComplete_) return;
    const float end = singleEndGain_;
    contexts_[0].gain = std::clamp(contexts_[0].gain +
                                   contexts_[0].direction * contexts_[0].deltaPerBlock,
                                   0.0f, 1.0f);
    if (processedFrames_ >= fadingFrames_ || contexts_[0].gain == end) {
        contexts_[0].gain = end;
        contexts_[0].state = ContextState::Complete;
        singleFadeComplete_ = true;
    }
}

void PowerampTransitionMixer::processCrossfade(const float* outgoing, const float* incoming,
                                                float* output, std::size_t frames) noexcept {
    if (output == nullptr || frames == 0) return;
    if (crossfadeComplete_) {
        if (incoming != nullptr) std::copy_n(incoming, frames * 2, output);
        else std::fill_n(output, frames * 2, 0.0f);
        return;
    }
    // A finished decoder contributes silence, not an unattenuated bypass. Both
    // fade contexts keep advancing on the same DSP block clock.
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const std::size_t i = frame * 2;
        const float outL = outgoing != nullptr ? outgoing[i] : 0.0f;
        const float outR = outgoing != nullptr ? outgoing[i + 1] : 0.0f;
        const float inL = incoming != nullptr ? incoming[i] : 0.0f;
        const float inR = incoming != nullptr ? incoming[i + 1] : 0.0f;
        output[i] = outL * contexts_[0].gain + inL * contexts_[1].gain;
        output[i + 1] = outR * contexts_[0].gain + inR * contexts_[1].gain;
        ++processedFrames_;
        ++framesIntoBlock_;
        if (framesIntoBlock_ >= blockFrames_) {
            framesIntoBlock_ = 0;
            advanceCrossfadeBlock();
        }
    }
    if (processedFrames_ >= fadingFrames_) {
        contexts_[0].gain = 0.0f;
        contexts_[1].gain = 1.0f;
        contexts_[0].state = ContextState::Complete;
        contexts_[1].state = ContextState::Complete;
        crossfadeComplete_ = true;
    }
}

void PowerampTransitionMixer::processSingle(const float* input, float* output,
                                             std::size_t frames) noexcept {
    if (output == nullptr || frames == 0) return;
    if (input == nullptr) {
        std::fill_n(output, frames * 2, 0.0f);
        return;
    }
    if (!singleFadeActive_ || singleFadeComplete_) {
        std::copy_n(input, frames * 2, output);
        return;
    }
    for (std::size_t frame = 0; frame < frames; ++frame) {
        const std::size_t i = frame * 2;
        output[i] = input[i] * contexts_[0].gain;
        output[i + 1] = input[i + 1] * contexts_[0].gain;
        ++processedFrames_;
        ++framesIntoBlock_;
        if (framesIntoBlock_ >= blockFrames_) {
            framesIntoBlock_ = 0;
            advanceSingleBlock();
        }
    }
    if (processedFrames_ >= fadingFrames_) {
        contexts_[0].gain = contexts_[0].direction > 0.0f ? 1.0f : 0.0f;
        contexts_[0].state = ContextState::Complete;
        singleFadeComplete_ = true;
    }
}

} // namespace auralis
