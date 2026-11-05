#include "audio_engine.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace auralis {
namespace {
constexpr double kPi = 3.1415926535897932384626433832795;
inline double clampd(double v, double lo, double hi) noexcept { return std::max(lo, std::min(hi, v)); }
}

double BiquadState::process(double x, const BiquadCoefficients& c) noexcept {
    const double y = c.b0 * x + z1;
    z1 = c.b1 * x - c.a1 * y + z2;
    z2 = c.b2 * x - c.a2 * y;
    return y;
}

bool calculateBiquad(int sampleRate, int frequencyHz, EqFilterType type,
                     double q, double gainDb, BiquadCoefficients& out) noexcept {
    if (sampleRate <= 0 || frequencyHz < 1 || frequencyHz > 24000 ||
        q < 0.1 || q > 12.0 || gainDb < -15.0 || gainDb > 15.0) return false;
    const double w = 2.0 * kPi * static_cast<double>(frequencyHz) / static_cast<double>(sampleRate);
    const double c = std::cos(w);
    const double s = std::sin(w);
    const double alpha = s / (2.0 * q);
    const double A = std::pow(10.0, gainDb / 40.0);
    const double beta = 2.0 * std::sqrt(A) * alpha;
    double b0, b1, b2, a0, a1, a2;
    switch (type) {
        case EqFilterType::LowPass:
            b0 = (1.0 - c) / 2.0; b1 = 1.0 - c; b2 = (1.0 - c) / 2.0;
            a0 = 1.0 + alpha; a1 = -2.0 * c; a2 = 1.0 - alpha; break;
        case EqFilterType::HighPass:
            b0 = (1.0 + c) / 2.0; b1 = -(1.0 + c); b2 = (1.0 + c) / 2.0;
            a0 = 1.0 + alpha; a1 = -2.0 * c; a2 = 1.0 - alpha; break;
        case EqFilterType::BandPass:
            b0 = alpha; b1 = 0.0; b2 = -alpha;
            a0 = 1.0 + alpha; a1 = -2.0 * c; a2 = 1.0 - alpha; break;
        case EqFilterType::Peaking:
            b0 = 1.0 + alpha * A; b1 = -2.0 * c; b2 = 1.0 - alpha * A;
            a0 = 1.0 + alpha / A; a1 = -2.0 * c; a2 = 1.0 - alpha / A; break;
        case EqFilterType::LowShelf:
            b0 = A * ((A + 1.0) - (A - 1.0) * c + beta);
            b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * c);
            b2 = A * ((A + 1.0) - (A - 1.0) * c - beta);
            a0 = (A + 1.0) + (A - 1.0) * c + beta;
            a1 = -2.0 * ((A - 1.0) + (A + 1.0) * c);
            a2 = (A + 1.0) + (A - 1.0) * c - beta; break;
        case EqFilterType::HighShelf:
            b0 = A * ((A + 1.0) + (A - 1.0) * c + beta);
            b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * c);
            b2 = A * ((A + 1.0) + (A - 1.0) * c - beta);
            a0 = (A + 1.0) - (A - 1.0) * c + beta;
            a1 = 2.0 * ((A - 1.0) - (A + 1.0) * c);
            a2 = (A + 1.0) - (A - 1.0) * c - beta; break;
        default: return false;
    }
    if (!std::isfinite(a0) || std::abs(a0) < std::numeric_limits<double>::epsilon()) return false;
    out = {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    return true;
}

double evaluateBiquadMagnitudeDb(const BiquadCoefficients& c, double frequencyHz,
                                  double sampleRate) noexcept {
    if (sampleRate <= 0.0) return -std::numeric_limits<double>::infinity();
    const double w = 2.0 * kPi * frequencyHz / sampleRate;
    const double cr = std::cos(w), ci = -std::sin(w);
    const double cr2 = std::cos(2.0 * w), ci2 = -std::sin(2.0 * w);
    const double nr = c.b0 + c.b1 * cr + c.b2 * cr2;
    const double ni = c.b1 * ci + c.b2 * ci2;
    const double dr = 1.0 + c.a1 * cr + c.a2 * cr2;
    const double di = c.a1 * ci + c.a2 * ci2;
    const double den = dr * dr + di * di;
    if (den <= 0.0) return 0.0;
    const double mag = std::sqrt((nr * nr + ni * ni) / den);
    return 20.0 * std::log10(std::max(mag, 1e-15));
}

void ReplayGain::configure(int type, int source, int defaultGainMb, int preampMb) noexcept {
    type_ = (type >= 0 && type <= 2) ? type : 0;
    source_ = (source == 1) ? 1 : 0;
    defaultGainMb_ = std::clamp(defaultGainMb, -20000, 16000);
    preampMb_ = std::clamp(preampMb, -20000, 16000);
}

double ReplayGain::dbToLinear(double db) noexcept { return std::pow(10.0, db / 20.0); }
double ReplayGain::millibelsToLinear(double millibels) noexcept { return std::pow(10.0, millibels / 2000.0); }

double ReplayGain::linearGain(const ReplayGainMetadata& m) const noexcept {
    if (type_ == 0) return 1.0;
    bool available = false;
    double tagGainDb = 0.0;
    double peak = 0.0;
    if (source_ == 0) {
        if (m.albumAvailable) { available = true; tagGainDb = m.albumGainDb; peak = m.albumPeakLinear; }
        else if (m.trackAvailable) { available = true; tagGainDb = m.trackGainDb; peak = m.trackPeakLinear; }
    } else if (m.trackAvailable) {
        available = true; tagGainDb = m.trackGainDb; peak = m.trackPeakLinear;
    }
    const double requestedMb = (available ? tagGainDb * 100.0 : static_cast<double>(defaultGainMb_)) + preampMb_;
    double appliedMb = requestedMb;
    if (type_ == 2 && available && peak > 0.0 && std::isfinite(peak)) {
        const double peakMb = 2000.0 * std::log10(1.0 / peak);
        appliedMb = std::min(requestedMb, peakMb);
    }
    return millibelsToLinear(appliedMb);
}

void Limiter::configure(bool enabled, LimiterPath path, double configurableCeiling) noexcept {
    enabled_ = enabled;
    path_ = path;
    configurableCeiling_ = clampd(configurableCeiling, 0.01, 1.0);
}
void Limiter::reset() noexcept { hardGain_ = 1.0; thresholdGain_ = 1.0; }
void Limiter::process(float* x, std::size_t frames) noexcept {
    if (!enabled_ || x == nullptr) return;
    const double ceiling = path_ == LimiterPath::HardCeiling ? 1.0 : configurableCeiling_;
    double& gain = path_ == LimiterPath::HardCeiling ? hardGain_ : thresholdGain_;
    for (std::size_t i = 0; i < frames; ++i) {
        const double peak = std::max(std::abs(static_cast<double>(x[2 * i])), std::abs(static_cast<double>(x[2 * i + 1])));
        const double target = peak > ceiling ? ceiling / peak : 1.0;
        if (target < gain) gain = std::max(target, gain - kAttackStep);
        else gain = std::min(1.0, gain + kReleaseStep);
        x[2 * i] = static_cast<float>(static_cast<double>(x[2 * i]) * gain);
        x[2 * i + 1] = static_cast<float>(static_cast<double>(x[2 * i + 1]) * gain);
    }
}

void StereoWide::configure(bool enabled, double sliderPercent) noexcept {
    enabled_ = enabled;
    const double p = clampd(sliderPercent, 0.0, 100.0) / 100.0;
    sideGain_ = 1.0 + 2.0 * p;
}
void StereoWide::process(float* x, std::size_t frames) const noexcept {
    if (!enabled_ || x == nullptr || sideGain_ == 1.0) return;
    const double g = sideGain_;
    for (std::size_t i = 0; i < frames; ++i) {
        const double l = x[2 * i], r = x[2 * i + 1];
        const double mid = (l + r) * 0.5;
        const double side = (l - r) * 0.5;
        x[2 * i] = static_cast<float>(mid + g * side);
        x[2 * i + 1] = static_cast<float>(mid - g * side);
    }
}
void StereoWide::process(double* x, std::size_t frames) const noexcept {
    if (!enabled_ || x == nullptr || sideGain_ == 1.0) return;
    const double g = sideGain_;
    for (std::size_t i = 0; i < frames; ++i) {
        const double l = x[2 * i], r = x[2 * i + 1];
        const double mid = (l + r) * 0.5;
        const double side = (l - r) * 0.5;
        x[2 * i] = mid + g * side;
        x[2 * i + 1] = mid - g * side;
    }
}

ReverbControlModel::ReverbControlModel(int sampleRate) noexcept { setSampleRate(sampleRate); }
void ReverbControlModel::setSampleRate(int sampleRate) noexcept {
    sampleRate_ = std::max(1, sampleRate);
    recalculateDelays();
}
void ReverbControlModel::setNativeParameter(int index, double value) noexcept {
    value = clampd(value, 0.0, 1.0);
    switch (index) {
        case 0: params_.size = 0.01 + 0.99 * value; break;
        case 1: params_.filter = value; break;
        case 2: params_.fade = value; break;
        case 3: params_.preDelay = value; break;
        case 4: params_.mix = value; break;
        case 5: params_.preDelayMix = value; break;
        case 6: params_.damp = 1.0 - value; break;
        case 7: params_.flag = value >= 0.5; break;
        default: return;
    }
    recalculateDelays();
}
void ReverbControlModel::recalculateDelays() noexcept {
    for (std::size_t i = 0; i < kDelaySeconds.size(); ++i) {
        const double raw = static_cast<double>(sampleRate_) * kDelaySeconds[i] * params_.size;
        params_.delaySamples[i] = std::clamp(static_cast<int>(raw), 1, kMaxDelaySamples);
    }
}

AuralisDsp::AuralisDsp(int sampleRate) : sampleRate_(std::max(1, sampleRate)), reverb_(sampleRate_) {}
void AuralisDsp::setStereoWide(bool enabled, double percent) noexcept { stereoWide_.configure(enabled, percent); }
void AuralisDsp::setLimiter(bool enabled, int path, double threshold) noexcept {
    limiter_.configure(enabled, path == 1 ? LimiterPath::ConfigurableThreshold : LimiterPath::HardCeiling, threshold);
}
bool AuralisDsp::setEqBand(int index, bool enabled, int type, int frequencyHz, double q,
                           double gainDb, int channel) noexcept {
    if (index < 0 || index >= kMaxEqBands || type < 0 || type > 5 || channel < 0 || channel > 2) return false;
    EqBand candidate{};
    candidate.enabled = enabled;
    candidate.type = static_cast<EqFilterType>(type);
    candidate.frequencyHz = frequencyHz;
    candidate.q = q;
    candidate.gainDb = gainDb;
    candidate.channel = channel;
    if (!calculateBiquad(sampleRate_, frequencyHz, candidate.type, q, gainDb, candidate.coefficients)) return false;
    bands_[static_cast<std::size_t>(index)] = candidate;
    return true;
}
void AuralisDsp::setReplayGain(int type, int source, int defaultGainMb, int preampMb) noexcept {
    replayGain_.configure(type, source, defaultGainMb, preampMb);
}
void AuralisDsp::setReplayGainMetadata(const ReplayGainMetadata& m) noexcept { replayGainMetadata_ = m; }
void AuralisDsp::setEqPreampDb(double db) noexcept { eqPreampLinear_ = ReplayGain::dbToLinear(clampd(db, -15.0, 15.0)); }
void AuralisDsp::setMasterGainDb(double db) noexcept { masterGainLinear_ = ReplayGain::dbToLinear(clampd(db, -120.0, 12.0)); }
void AuralisDsp::process(float* x, std::size_t frames) noexcept {
    if (x == nullptr) return;
    const double trackGain = replayGain_.linearGain(replayGainMetadata_);
    const double preGain = trackGain * eqPreampLinear_ * masterGainLinear_;
    if (preGain != 1.0) {
        for (std::size_t i = 0; i < frames * 2; ++i) x[i] = static_cast<float>(static_cast<double>(x[i]) * preGain);
    }
    for (EqBand& band : bands_) {
        if (!band.enabled) continue;
        for (std::size_t i = 0; i < frames; ++i) {
            if (band.channel != 2) x[2 * i] = static_cast<float>(band.left.process(x[2 * i], band.coefficients));
            if (band.channel != 1) x[2 * i + 1] = static_cast<float>(band.right.process(x[2 * i + 1], band.coefficients));
        }
    }
    stereoWide_.process(x, frames);
    limiter_.process(x, frames);
}

AudioRing::AudioRing() = default;
bool AudioRing::push(const float* x, std::size_t frames) noexcept {
    if (x == nullptr || frames == 0 || frames > kChunkFrames) return false;
    const std::uint32_t head = head_.load(std::memory_order_relaxed);
    const std::uint32_t tail = tail_.load(std::memory_order_acquire);
    if (head - tail >= kQueueSlots) return false;
    Slot& slot = slots_[head % kQueueSlots];
    std::copy_n(x, frames * 2, slot.samples.data());
    slot.frames = static_cast<std::uint32_t>(frames);
    head_.store(head + 1, std::memory_order_release);
    return true;
}
std::size_t AudioRing::pop(float* x, std::size_t capacityFrames) noexcept {
    if (x == nullptr) return 0;
    const std::uint32_t tail = tail_.load(std::memory_order_relaxed);
    const std::uint32_t head = head_.load(std::memory_order_acquire);
    if (tail == head) return 0;
    Slot& slot = slots_[tail % kQueueSlots];
    const std::size_t frames = std::min<std::size_t>(slot.frames, capacityFrames);
    std::copy_n(slot.samples.data(), frames * 2, x);
    tail_.store(tail + 1, std::memory_order_release);
    return frames;
}
bool AudioRing::empty() const noexcept {
    return tail_.load(std::memory_order_acquire) == head_.load(std::memory_order_acquire);
}
bool AudioRing::full() const noexcept {
    const std::uint32_t head = head_.load(std::memory_order_acquire);
    const std::uint32_t tail = tail_.load(std::memory_order_acquire);
    return head - tail >= kQueueSlots;
}

AudioPipeline::AudioPipeline(int sampleRate) : dsp_(sampleRate) {}
bool AudioPipeline::publish(const ControlCommand& c) noexcept {
    const std::uint32_t head = controlHead_.load(std::memory_order_relaxed);
    const std::uint32_t tail = controlTail_.load(std::memory_order_acquire);
    if (head - tail >= controls_.size()) return false;
    controls_[head % controls_.size()] = c;
    controlHead_.store(head + 1, std::memory_order_release);
    return true;
}
void AudioPipeline::setStereoWide(bool enabled, double percent) noexcept {
    ControlCommand c{}; c.kind = 1; c.b0 = enabled; c.d0 = percent; (void)publish(c);
}
void AudioPipeline::setLimiter(bool enabled, int path, double threshold) noexcept {
    ControlCommand c{}; c.kind = 2; c.b0 = enabled; c.i0 = path; c.d0 = threshold; (void)publish(c);
}
bool AudioPipeline::setEqBand(int index, bool enabled, int type, int frequencyHz, double q, double gainDb, int channel) noexcept {
    if (index < 0 || index >= kMaxEqBands) return false;
    ControlCommand c{}; c.kind = 3; c.i0 = index; c.i1 = type; c.i2 = frequencyHz; c.b0 = enabled;
    c.d0 = q; c.d1 = gainDb;
    // Channel is kept in the top byte of the command's otherwise unused signed field.
    c.i2 = (frequencyHz & 0x00FFFFFF) | ((channel & 0xFF) << 24);
    return publish(c);
}
void AudioPipeline::applyPendingControls() noexcept {
    for (;;) {
        const std::uint32_t tail = controlTail_.load(std::memory_order_relaxed);
        const std::uint32_t head = controlHead_.load(std::memory_order_acquire);
        if (tail == head) break;
        const ControlCommand c = controls_[tail % controls_.size()];
        controlTail_.store(tail + 1, std::memory_order_release);
        if (c.kind == 1) dsp_.setStereoWide(c.b0, c.d0);
        else if (c.kind == 2) dsp_.setLimiter(c.b0, c.i0, c.d0);
        else if (c.kind == 3) {
            const int frequency = c.i2 & 0x00FFFFFF;
            const int channel = (c.i2 >> 24) & 0xFF;
            (void)dsp_.setEqBand(c.i0, c.b0, c.i1, frequency, c.d0, c.d1, channel);
        }
    }
}
bool AudioPipeline::enqueueInput(const float* x, std::size_t frames) noexcept { return input_.push(x, frames); }
int AudioPipeline::processOne() noexcept {
    applyPendingControls();
    if (output_.full()) return -1;
    const std::size_t frames = input_.pop(scratch_.data(), kChunkFrames);
    if (frames == 0) return 0;
    dsp_.process(scratch_.data(), frames);
    const bool queued = output_.push(scratch_.data(), frames);
    return queued ? static_cast<int>(frames) : -1;
}
std::size_t AudioPipeline::dequeueOutput(float* x, std::size_t capacityFrames) noexcept {
    return output_.pop(x, capacityFrames);
}

double clampRecoveredTempo(double factor) noexcept { return clampd(factor, 0.5, 3.0); }
int clampRecoveredResamplerCutoff(int cutoffPercent) noexcept { return std::clamp(cutoffPercent, 80, 99); }
int recoveredNoDvcHeadroomMb(int valueMb) noexcept {
    const int clamped = std::clamp(valueMb, -1000, 0);
    return static_cast<int>(std::lround(static_cast<double>(clamped) / 50.0)) * 50;
}
double recoveredBorderGainCeilingDb(double s) noexcept { return 12.5 - s / 120.0; }

double recoveredBalanceAttenuation(double magnitude) noexcept {
    return std::pow(10.0, -48.0 * clampd(std::abs(magnitude), 0.0, 1.0) / 20.0);
}
double recoveredDsdCutoff(double ratio) noexcept {
    if (ratio >= 0.833) return 0.85;
    return std::nextafter(ratio / 0.98, 0.0);
}

} // namespace auralis
