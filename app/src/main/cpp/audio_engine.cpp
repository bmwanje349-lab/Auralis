#include "audio_engine.h"
#include "poweramp_reverb_ir_48k.h"

#include <algorithm>
#include <cmath>
#include <cstring>
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
    defaultGainMb_ = defaultGainMb;
    preampMb_ = preampMb;
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

void Limiter::configure(bool enabled, LimiterPath path, double configurableThreshold) noexcept {
    enabled_ = enabled;
    path_ = path;
    setConfigurableThreshold(configurableThreshold);
}
void Limiter::setConfigurableThreshold(double threshold) noexcept {
    configurableThreshold_ = (std::isfinite(threshold) && threshold > 0.0)
        ? threshold : kUnboundedThreshold;
}
void Limiter::reset() noexcept {
    appliedGain_ = 1.0;
    envelopeGain_ = 1.0;
}
double Limiter::update(double peak, double ceiling, double attackStep, double releaseStep) noexcept {
    if (peak > ceiling && peak > 0.0) {
        const double target = ceiling / peak;
        if (target < envelopeGain_) envelopeGain_ = target;
    } else {
        envelopeGain_ = std::min(1.0, envelopeGain_ + releaseStep);
    }
    if (envelopeGain_ < appliedGain_) {
        appliedGain_ = std::max(envelopeGain_, appliedGain_ - attackStep);
    } else if (envelopeGain_ > appliedGain_) {
        appliedGain_ = std::min(envelopeGain_, appliedGain_ + releaseStep);
    }
    return appliedGain_;
}
double Limiter::advance(double targetGain) noexcept {
    if (!enabled_) return appliedGain_;
    if (std::isfinite(targetGain) && targetGain < 1.0) {
        const double target = clampd(targetGain, 0.0, 1.0);
        if (target < envelopeGain_) envelopeGain_ = target;
    } else {
        envelopeGain_ = std::min(1.0, envelopeGain_ + kReleaseStep);
    }
    if (envelopeGain_ < appliedGain_) {
        appliedGain_ = std::max(envelopeGain_, appliedGain_ - kAttackStep);
    } else if (envelopeGain_ > appliedGain_) {
        appliedGain_ = std::min(envelopeGain_, appliedGain_ + kReleaseStep);
    }
    return appliedGain_;
}
void Limiter::process(float* x, std::size_t frames) noexcept {
    if (!enabled_ || x == nullptr || frames == 0) return;
    const double ceiling = path_ == LimiterPath::HardCeiling ? 1.0 : configurableThreshold_;
    std::size_t frame = 0;
    // Native SIMD path: one linked peak over four stereo frames, then one envelope
    // update and one gain value for the eight interleaved samples in that group.
    for (; frame + 4 <= frames; frame += 4) {
        double peak = 0.0;
        for (std::size_t j = 0; j < 4; ++j) {
            peak = std::max(peak, std::abs(static_cast<double>(x[2 * (frame + j)])));
            peak = std::max(peak, std::abs(static_cast<double>(x[2 * (frame + j) + 1])));
        }
        const double gain = update(peak, ceiling, kVectorAttackStep, kVectorReleaseStep);
        const float g = static_cast<float>(gain);
        for (std::size_t j = 0; j < 8; ++j) x[2 * frame + j] *= g;
    }
    // Native scalar tail: update the linked detector and envelope once per stereo frame.
    for (; frame < frames; ++frame) {
        const double peak = std::max(
            std::abs(static_cast<double>(x[2 * frame])),
            std::abs(static_cast<double>(x[2 * frame + 1])));
        const float gain = static_cast<float>(update(peak, ceiling, kAttackStep, kReleaseStep));
        x[2 * frame] *= gain;
        x[2 * frame + 1] *= gain;
    }
}

void StereoWide::configure(bool enabled, double sliderPercent) noexcept {
    enabled_ = enabled;

    // The native parameter handler receives a normalized Float32 slider value,
    // clamps it to [0,1], then computes g = fma(p, 2, 1) in Float32 and stores
    // that Float32 result at context +0xC0. Reproduce those rounding points.
    const float percent = static_cast<float>(sliderPercent);
    const float normalized = std::isnan(percent)
        ? 0.0f
        : std::max(0.0f, std::min(percent / 100.0f, 1.0f));
    sideGain_ = std::fma(2.0f, normalized, 1.0f);
    widthActive_ = normalized != 0.0f;
}
void StereoWide::process(float* x, std::size_t frames) const noexcept {
    if (!enabled_ || !widthActive_ || x == nullptr) return;
    const float g = sideGain_;
    for (std::size_t i = 0; i < frames; ++i) {
        const float l = x[2 * i], r = x[2 * i + 1];

        // Poweramp computes the common-mode sample first, derives each side
        // independently as channel - mid, and uses fused multiply-add for output.
        // This is algebraically equivalent to (L-R)/2 but matches native Float32
        // rounding and FMLA behavior more closely.
        const float mid = (l + r) * 0.5f;
        x[2 * i] = std::fma(l - mid, g, mid);
        x[2 * i + 1] = std::fma(r - mid, g, mid);
    }
}
void StereoWide::process(double* x, std::size_t frames) const noexcept {
    if (!enabled_ || !widthActive_ || x == nullptr) return;
    // Poweramp's double callback converts its Float32 context gain to double;
    // it does not derive a separate higher-precision slider coefficient.
    const double g = static_cast<double>(sideGain_);
    for (std::size_t i = 0; i < frames; ++i) {
        const double l = x[2 * i], r = x[2 * i + 1];
        const double mid = (l + r) * 0.5;
        x[2 * i] = std::fma(l - mid, g, mid);
        x[2 * i + 1] = std::fma(r - mid, g, mid);
    }
}

void Balance::configure(bool enabled, double normalized) noexcept {
    enabled_ = enabled;
    // Native handler (plugin type 3, 0x21FE5C): the value is a Float32; > 1 clamps to 1,
    // while < -1 and NaN both clamp to -1. Positive attenuates the left channel and
    // negative the right, with gain = exp2f(value * +/-7.97263) (log2(10) * 2.4, from
    // 0xC0FF1FC4 / 0x40FF1FC4). The extremes +/-1 store an exact zero gain, so that
    // channel is silenced rather than reduced by 48 dB.
    float p = static_cast<float>(normalized);
    if (p > 1.0f) p = 1.0f;
    else if (!(p >= -1.0f)) p = -1.0f;
    balance_ = static_cast<double>(p);
    leftGain_ = 1.0;
    rightGain_ = 1.0;
    const auto bits = [](std::uint32_t b) { float f; std::memcpy(&f, &b, 4); return f; };
    if (p > 0.0f) {
        leftGain_ = (p == 1.0f) ? 0.0 : static_cast<double>(std::exp2(p * bits(0xC0FF1FC4u)));
    } else if (p < 0.0f) {
        rightGain_ = (p == -1.0f) ? 0.0 : static_cast<double>(std::exp2(p * bits(0x40FF1FC4u)));
    }
}
void Balance::process(float* x, std::size_t frames) const noexcept {
    if (!enabled_ || x == nullptr || balance_ == 0.0) return;
    const float left = static_cast<float>(leftGain_);
    const float right = static_cast<float>(rightGain_);
    for (std::size_t i = 0; i < frames; ++i) {
        x[2 * i] *= left;
        x[2 * i + 1] *= right;
    }
}
void Mono::process(float* x, std::size_t frames) const noexcept {
    if (!enabled_ || x == nullptr) return;
    for (std::size_t i = 0; i < frames; ++i) {
        const float mid = (x[2 * i] + x[2 * i + 1]) * 0.5f;
        x[2 * i] = mid;
        x[2 * i + 1] = mid;
    }
}

ReverbControlModel::ReverbControlModel(int sampleRate) noexcept { setSampleRate(sampleRate); }
void ReverbControlModel::setSampleRate(int sampleRate) noexcept {
    sampleRate_ = std::max(1, sampleRate);
}
std::array<int, 4> ReverbControlModel::nativeBufferLengthsSamples() const noexcept {
    std::array<int, 4> lengths{};
    const float rate = static_cast<float>(sampleRate_);
    for (std::size_t i = 0; i < kNativeBufferFamilies.size(); ++i) {
        // The allocator uses Float32 multiplication followed by FCVTAS
        // (round-to-nearest, ties away from zero) for capacity allocation.
        const float samples = rate * static_cast<float>(kNativeBufferFamilies[i].seconds);
        lengths[i] = static_cast<int>(std::lround(static_cast<double>(samples)));
    }
    return lengths;
}

std::array<int, 15> ReverbControlModel::nativeBufferStorageFloats() const noexcept {
    const auto capacities = nativeBufferLengthsSamples();
    const auto align16 = [](int value) noexcept { return (value + 15) & ~15; };
    const int shortMono = align16(capacities[0]);
    const int mediumStereo = align16(2 * capacities[1]);
    const int longStereo = align16(2 * capacities[2]);
    const int preDelayMono = capacities[3];
    // Native context pointer order: four short mono APs, four stereo AP/FDN
    // objects, one mono pre-delay, four long stereo lines, two packed stereo banks.
    return {{shortMono, shortMono, shortMono, shortMono,
             mediumStereo, mediumStereo, mediumStereo, mediumStereo,
             preDelayMono,
             longStereo, longStereo, longStereo, longStereo,
             mediumStereo, mediumStereo}};
}
std::array<int, 15> ReverbControlModel::nativeDelayLengthsSamples() const noexcept {
    std::array<int, 15> lengths{};
    const float rate = static_cast<float>(sampleRate_);
    const float size = static_cast<float>(params_.size); // native context +0x4C4
    const float preDelay = static_cast<float>(params_.preDelay); // native context +0x4B4
    // Context offsets +0x34/+0x4C/+0x64/+0x7C: these first four
    // all-pass delays use fixed seconds and do not follow the Size slider.
    for (std::size_t i = 0; i < kNativeFixedDelaySeconds.size(); ++i) {
        lengths[i] = static_cast<int>(rate * kNativeFixedDelaySeconds[i]);
    }
    // The remaining delay lines use independent Size multipliers. FCVTZS in
    // 0x169A30 truncates the Float32 sample count; it is not FCVTAS.
    constexpr std::array<std::size_t, 8> destinations{{4,5,6,7,9,10,11,12}};
    for (std::size_t i = 0; i < kNativeSizeDelaySeconds.size(); ++i) {
        const float samples = rate * (size * kNativeSizeDelaySeconds[i]);
        lengths[destinations[i]] = static_cast<int>(samples);
    }
    // The last two stereo output lines at +0x1C0/+0x1F0 are fixed at
    // 89 ms and 69 ms; they do not scale with Size.
    lengths[13] = static_cast<int>(rate * kNativeFixedTailDelaySeconds[0]);
    lengths[14] = static_cast<int>(rate * kNativeFixedTailDelaySeconds[1]);
    // Native +0x128 ring active length is FCVTZS(PreDelay * 200.0f). It is
    // a 0..200-sample control, not 0..200 ms; only the allocated capacity at
    // +0x138 scales with the sample rate.
    const float predelayTarget = preDelay * 200.0f;
    const float predelayCapacity = static_cast<float>(nativeBufferLengthsSamples()[3]);
    lengths[8] = static_cast<int>(std::min(predelayTarget, predelayCapacity));
    return lengths;
}

std::array<int, 24> ReverbControlModel::nativeTapOffsetsSamples() const noexcept {
    std::array<int, 24> offsets{};
    const float rate = static_cast<float>(sampleRate_);
    const float size = static_cast<float>(params_.size);
    const float sizeRate = rate * size;
    const auto clampTap = [](float raw, int activeLength) noexcept {
        if (activeLength <= 0 || !(raw > 0.0f)) return 0;
        const int truncated = static_cast<int>(raw); // native FCVTZS
        return std::clamp(truncated, 0, activeLength - 1);
    };

    // Four Size-dependent late stereo rings. These ratios are the native
    // Float32 constants loaded by the 0x169A30 reconfiguration routine.
    constexpr std::array<float, 12> lateRatios{{
        0.067f, 0.011f, 0.121f,
        0.036f, 0.089f, 0.0f,
        0.0089f, 0.099f, 0.0f,
        0.067f, 0.0041f, 0.0f
    }};
    constexpr std::array<std::size_t, 4> lateRingIndices{{9, 10, 11, 12}};
    const auto lengths = nativeDelayLengthsSamples();
    for (std::size_t group = 0; group < lateRingIndices.size(); ++group) {
        const int activeLength = lengths[lateRingIndices[group]];
        for (std::size_t tap = 0; tap < 3; ++tap) {
            const std::size_t index = group * 3 + tap;
            offsets[index] = clampTap(sizeRate * lateRatios[index], activeLength);
        }
    }

    // The two packed stereo banks use fixed ratios of the native sample rate;
    // they do not follow Size. Their six tap offsets are independently clamped.
    constexpr std::array<float, 6> bank0Ratios{{
        0.0199f, 0.0219f, 0.0354f, 0.0389f, 0.0414f, 0.0692f
    }};
    constexpr std::array<float, 6> bank1Ratios{{
        0.0099f, 0.0110f, 0.0182f, 0.0189f, 0.0213f, 0.0431f
    }};
    const int bank0Length = lengths[13];
    const int bank1Length = lengths[14];
    for (std::size_t tap = 0; tap < 6; ++tap) {
        offsets[12 + tap] = clampTap(rate * bank0Ratios[tap], bank0Length);
        offsets[18 + tap] = clampTap(rate * bank1Ratios[tap], bank1Length);
    }
    return offsets;
}

void ReverbControlModel::setNativeParameter(int index, double value) noexcept {
    if (index == kNativeFlagOperationIndex) {
        nativeUpdateFlag_ = 1;
        return;
    }

    // The Java plugin sends Float32 values and the native setter uses FMINNM/FMAXNM.
    // Carry out the clamp and transforms in Float32 as the AArch64 instructions do.
    const float input = static_cast<float>(value);
    const float ui = std::isnan(input) ? 1.0f : std::max(0.0f, std::min(input, 1.0f));
    switch (index) {
        // Setter index 0 writes Size to +0x4C4 (not +0x4AC).
        case 0: params_.size = static_cast<double>(std::fma(0.99f, ui, 0.01f)); nativeUpdateFlag_ = 2; break;
        case 1: params_.filter = static_cast<double>(ui); nativeUpdateFlag_ = 1; break;
        case 2: params_.fade = static_cast<double>(ui); nativeUpdateFlag_ = 1; break;
        case 3: params_.preDelay = static_cast<double>(ui); nativeUpdateFlag_ = 2; break;
        case 4: params_.mix = static_cast<double>(ui); nativeUpdateFlag_ = 1; break;
        case 5: params_.preDelayMix = static_cast<double>(ui); nativeUpdateFlag_ = 1; break;
        // Setter index 6 writes Damp to +0x4AC as (1 - UI).
        case 6: params_.damp = static_cast<double>(1.0f - ui); nativeUpdateFlag_ = 1; break;
        default: return;
    }
}

PowerampReverb::PowerampReverb(int sampleRate)
    : sampleRate_(std::clamp(sampleRate, 8000, 192000)), controls_(sampleRate_) {
    initialiseFft();
    configureDelays();
    configureFilters();
    configureKernel();
}

void PowerampReverb::initialiseFft() noexcept {
    constexpr float kTwoPi = 6.28318530717958647692f;
    for (std::size_t i = 0; i < twiddles_.size(); ++i) {
        const float phase = -kTwoPi * static_cast<float>(i) / static_cast<float>(kFftSize);
        twiddles_[i] = {std::cos(phase), std::sin(phase)};
    }
}

PowerampReverb::Complex PowerampReverb::multiply(Complex a, Complex b) noexcept {
    return {a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re};
}

void PowerampReverb::transform(FftBuffer& values, bool inverse) const noexcept {
    std::size_t j = 0;
    for (std::size_t i = 1; i < kFftSize; ++i) {
        std::size_t bit = kFftSize >> 1;
        while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
        j ^= bit;
        if (i < j) std::swap(values[i], values[j]);
    }
    for (std::size_t length = 2; length <= kFftSize; length <<= 1) {
        const std::size_t half = length >> 1;
        const std::size_t stride = kFftSize / length;
        for (std::size_t base = 0; base < kFftSize; base += length) {
            for (std::size_t k = 0; k < half; ++k) {
                Complex w = twiddles_[k * stride];
                if (inverse) w.im = -w.im;
                const Complex even = values[base + k];
                const Complex odd = multiply(values[base + k + half], w);
                values[base + k] = {even.re + odd.re, even.im + odd.im};
                values[base + k + half] = {even.re - odd.re, even.im - odd.im};
            }
        }
    }
    if (inverse) {
        constexpr float scale = 1.0f / static_cast<float>(kFftSize);
        for (Complex& value : values) { value.re *= scale; value.im *= scale; }
    }
}

void PowerampReverb::configureKernel() {
    const double rateRatio = static_cast<double>(sampleRate_) /
                             static_cast<double>(detail::kPowerampReverbIrSampleRate);
    kernelFrames_ = std::max<std::size_t>(1, static_cast<std::size_t>(std::llround(
        static_cast<double>(detail::kPowerampReverbIrFrames) * rateRatio)));
    partitions_ = (kernelFrames_ + kBlockFrames - 1) / kBlockFrames;
    historyHead_ = partitions_ - 1;
    for (auto& kernel : kernelSpectra_) kernel.assign(partitions_ * kFftSize, Complex{});
    for (auto& history : inputHistory_) history.assign(partitions_ * kFftSize, Complex{});

    const std::array<const std::array<float, detail::kPowerampReverbIrFrames>*, 4> impulse{{
        &detail::kPowerampReverbIrLL, &detail::kPowerampReverbIrLR,
        &detail::kPowerampReverbIrRL, &detail::kPowerampReverbIrRR
    }};
    FftBuffer work{};
    for (std::size_t path = 0; path < impulse.size(); ++path) {
        for (std::size_t partition = 0; partition < partitions_; ++partition) {
            work.fill(Complex{});
            const std::size_t first = partition * kBlockFrames;
            for (std::size_t i = 0; i < kBlockFrames; ++i) {
                const std::size_t outputIndex = first + i;
                if (outputIndex >= kernelFrames_) break;
                const double sourcePosition = static_cast<double>(outputIndex) / rateRatio;
                const std::size_t i0 = std::min(static_cast<std::size_t>(sourcePosition),
                                                detail::kPowerampReverbIrFrames - 1);
                const std::size_t i1 = std::min(i0 + 1, detail::kPowerampReverbIrFrames - 1);
                const float fraction = static_cast<float>(sourcePosition - static_cast<double>(i0));
                const float a = (*impulse[path])[i0];
                const float b = (*impulse[path])[i1];
                work[i].re = std::fma(b - a, fraction, a);
            }
            transform(work, false);
            std::copy(work.begin(), work.end(),
                      kernelSpectra_[path].begin() + static_cast<std::ptrdiff_t>(partition * kFftSize));
        }
    }
    previousInputLeft_.fill(0.0f);
    previousInputRight_.fill(0.0f);
    mixCurrent_ = static_cast<float>(controls_.parameters().mix);
    hasProcessed_ = false;
}

void PowerampReverb::setEnabled(bool enabled) noexcept {
    if (enabled_ == enabled) return;
    enabled_ = enabled;
    historyHead_ = partitions_ > 0 ? partitions_ - 1 : 0;
    for (auto& history : inputHistory_) std::fill(history.begin(), history.end(), Complex{});
    previousInputLeft_.fill(0.0f);
    previousInputRight_.fill(0.0f);
    mixCurrent_ = static_cast<float>(controls_.parameters().mix);
    hasProcessed_ = false;
}

void PowerampReverb::setNativeParameter(int index, double value) noexcept {
    controls_.setNativeParameter(index, value);
    if (index < 0 || index > 6) return;
    if (index == 0 || index == 3) configureDelays();
    if (index == 1 || index == 6) configureFilters();
    if (index == 4 && !hasProcessed_) mixCurrent_ = static_cast<float>(controls_.parameters().mix);
}

void PowerampReverb::configureDelays() noexcept {
    activeLengths_ = controls_.nativeDelayLengthsSamples();
    activeTapOffsets_ = controls_.nativeTapOffsetsSamples();
}

void PowerampReverb::configureFilters() noexcept {
    // Preserve the recovered parameter/cutoff model for diagnostics and future
    // profile morphing. The measured reference kernels already include the 50%
    // Filter and 50% Damp response used by the captured Poweramp preset.
    const auto& p = controls_.parameters();
    filterCoefficients_[0] = {static_cast<float>(p.filter), 0.0f, 0.0f, 0.0f, 0.0f};
    filterCoefficients_[1] = {static_cast<float>(p.damp), 0.0f, 0.0f, 0.0f, 0.0f};
}

double PowerampReverb::filterCutoffHz(int index) const noexcept {
    if (index < 0 || index > 1) return 0.0;
    const auto& p = controls_.parameters();
    return 100.0 + 18500.0 * (index == 0 ? p.filter : p.damp);
}

float PowerampReverb::lateAllpassCoefficient() const noexcept {
    const float fade = static_cast<float>(controls_.parameters().fade);
    const float fadeTerm = std::fma(0.799499988556f, fade, 0.00499999988824f);
    return std::clamp(fadeTerm + 0.15000000596f, 0.25f, 0.5f);
}

float PowerampReverb::processNativeAllpassSample(std::vector<float>& ring, std::size_t length,
                                                   std::size_t& cursor, float input,
                                                   float coefficient) noexcept {
    if (length == 0 || ring.empty()) return input;
    const std::size_t activeLength = std::min(length, ring.size());
    const std::size_t index = cursor % activeLength;
    const float delayed = ring[index];
    const float output = std::fma(coefficient, input, -delayed);
    ring[index] = std::fma(coefficient, output, input);
    cursor = (index + 1) % activeLength;
    return output;
}

void PowerampReverb::processBlock(float* x, std::size_t frames) noexcept {
    if (x == nullptr || frames == 0 || partitions_ == 0) return;
    frames = std::min(frames, kBlockFrames);
    std::array<float, kBlockFrames> currentLeft{};
    std::array<float, kBlockFrames> currentRight{};
    for (std::size_t i = 0; i < frames; ++i) {
        currentLeft[i] = x[2 * i];
        currentRight[i] = x[2 * i + 1];
    }

    historyHead_ = (historyHead_ + 1) % partitions_;
    const std::size_t historyOffset = historyHead_ * kFftSize;
    for (std::size_t i = 0; i < kBlockFrames; ++i) {
        workLeft_[i] = {previousInputLeft_[i], 0.0f};
        workLeft_[kBlockFrames + i] = {currentLeft[i], 0.0f};
        workRight_[i] = {previousInputRight_[i], 0.0f};
        workRight_[kBlockFrames + i] = {currentRight[i], 0.0f};
    }
    transform(workLeft_, false);
    transform(workRight_, false);
    std::copy(workLeft_.begin(), workLeft_.end(), inputHistory_[0].begin() + static_cast<std::ptrdiff_t>(historyOffset));
    std::copy(workRight_.begin(), workRight_.end(), inputHistory_[1].begin() + static_cast<std::ptrdiff_t>(historyOffset));

    sumLeft_.fill(Complex{});
    sumRight_.fill(Complex{});
    for (std::size_t partition = 0; partition < partitions_; ++partition) {
        const std::size_t inputIndex = (historyHead_ + partitions_ - partition) % partitions_;
        const std::size_t inputOffset = inputIndex * kFftSize;
        const std::size_t kernelOffset = partition * kFftSize;
        const Complex* leftSpectrum = inputHistory_[0].data() + inputOffset;
        const Complex* rightSpectrum = inputHistory_[1].data() + inputOffset;
        const Complex* hLL = kernelSpectra_[0].data() + kernelOffset;
        const Complex* hLR = kernelSpectra_[1].data() + kernelOffset;
        const Complex* hRL = kernelSpectra_[2].data() + kernelOffset;
        const Complex* hRR = kernelSpectra_[3].data() + kernelOffset;
        for (std::size_t bin = 0; bin < kFftSize; ++bin) {
            const Complex ll = multiply(hLL[bin], leftSpectrum[bin]);
            const Complex lr = multiply(hLR[bin], rightSpectrum[bin]);
            const Complex rl = multiply(hRL[bin], leftSpectrum[bin]);
            const Complex rr = multiply(hRR[bin], rightSpectrum[bin]);
            sumLeft_[bin].re += ll.re + lr.re;
            sumLeft_[bin].im += ll.im + lr.im;
            sumRight_[bin].re += rl.re + rr.re;
            sumRight_[bin].im += rl.im + rr.im;
        }
    }
    transform(sumLeft_, true);
    transform(sumRight_, true);

    const float targetMix = static_cast<float>(controls_.parameters().mix);
    const float mixStep = (targetMix - mixCurrent_) / static_cast<float>(frames);
    for (std::size_t i = 0; i < frames; ++i) {
        const float mix = mixCurrent_ + mixStep * static_cast<float>(i + 1);
        x[2 * i] = std::fma(sumLeft_[kBlockFrames + i].re - currentLeft[i], mix, currentLeft[i]);
        x[2 * i + 1] = std::fma(sumRight_[kBlockFrames + i].re - currentRight[i], mix, currentRight[i]);
    }
    mixCurrent_ = targetMix;
    previousInputLeft_ = currentLeft;
    previousInputRight_ = currentRight;
    hasProcessed_ = true;
}

void PowerampReverb::process(float* x, std::size_t frames) noexcept {
    if (!enabled_ || x == nullptr || frames == 0) return;
    std::size_t offset = 0;
    while (offset < frames) {
        const std::size_t blockFrames = std::min(kBlockFrames, frames - offset);
        processBlock(x + offset * 2, blockFrames);
        offset += blockFrames;
    }
}

AuralisDsp::AuralisDsp(int sampleRate) : sampleRate_(std::max(1, sampleRate)), reverb_(sampleRate_) {}
void AuralisDsp::setStereoWide(bool enabled, double percent) noexcept { stereoWide_.configure(enabled, percent); }
void AuralisDsp::setBalance(bool enabled, double normalized) noexcept { balance_.configure(enabled, normalized); }
void AuralisDsp::setMono(bool enabled) noexcept { mono_.configure(enabled); }
void AuralisDsp::setLimiter(bool enabled, LimiterPath path) noexcept {
    limiter_.configure(enabled, path, Limiter::kUnboundedThreshold);
}
void AuralisDsp::setReverbEnabled(bool enabled) noexcept { reverb_.setEnabled(enabled); }
void AuralisDsp::setReverbParameter(int index, double value) noexcept { reverb_.setNativeParameter(index, value); }
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
void AuralisDsp::setEqPreampDb(double db) noexcept { eqPreampLinear_ = ReplayGain::dbToLinear(db); }
void AuralisDsp::setMasterGainDb(double db) noexcept { masterGainLinear_ = ReplayGain::dbToLinear(db); }
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
    // Poweramp Reverb is a separate upstream DSP plug-in callback, before the
    // built-in DSPI chain; StereoWide remains independent and follows it.
    reverb_.process(x, frames);
    stereoWide_.process(x, frames);
    mono_.process(x, frames);
    balance_.process(x, frames);
    // Poweramp's configurable limiter ceiling is the inverse of accumulated linear
    // gain when that gain exceeds 0.001; otherwise the native context uses 999.999.
    if (preGain > 0.001 && std::isfinite(preGain)) {
        limiter_.setConfigurableThreshold(1.0 / preGain);
    } else {
        limiter_.setConfigurableThreshold(Limiter::kUnboundedThreshold);
    }
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
    slot.readOffsetFrames = 0;
    head_.store(head + 1, std::memory_order_release);
    return true;
}
std::size_t AudioRing::pop(float* x, std::size_t capacityFrames) noexcept {
    if (x == nullptr) return 0;
    const std::uint32_t tail = tail_.load(std::memory_order_relaxed);
    const std::uint32_t head = head_.load(std::memory_order_acquire);
    if (tail == head) return 0;
    Slot& slot = slots_[tail % kQueueSlots];
    const std::size_t remaining = static_cast<std::size_t>(slot.frames - slot.readOffsetFrames);
    const std::size_t frames = std::min(remaining, capacityFrames);
    std::copy_n(slot.samples.data() + static_cast<std::size_t>(slot.readOffsetFrames) * 2, frames * 2, x);
    slot.readOffsetFrames += static_cast<std::uint32_t>(frames);
    if (slot.readOffsetFrames == slot.frames) tail_.store(tail + 1, std::memory_order_release);
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
void AudioPipeline::setBalance(bool enabled, double normalized) noexcept {
    ControlCommand c{}; c.kind = 3; c.b0 = enabled; c.d0 = normalized; (void)publish(c);
}
void AudioPipeline::setMono(bool enabled) noexcept {
    ControlCommand c{}; c.kind = 4; c.b0 = enabled; (void)publish(c);
}
void AudioPipeline::setLimiter(bool enabled, LimiterPath path) noexcept {
    ControlCommand c{}; c.kind = 5; c.b0 = enabled; c.i0 = static_cast<int>(path); (void)publish(c);
}
void AudioPipeline::setReverbEnabled(bool enabled) noexcept {
    ControlCommand c{}; c.kind = 6; c.b0 = enabled; (void)publish(c);
}
void AudioPipeline::setReverbParameter(int index, double value) noexcept {
    if (index < 0 || index > 6) return;
    ControlCommand c{}; c.kind = 7; c.i0 = index; c.d0 = value; (void)publish(c);
}
bool AudioPipeline::setEqBand(int index, bool enabled, int type, int frequencyHz, double q, double gainDb, int channel) noexcept {
    if (index < 0 || index >= kMaxEqBands || type < 0 || type > 5 ||
        frequencyHz < 1 || frequencyHz > 24000 || q < 0.1 || q > 12.0 ||
        gainDb < -15.0 || gainDb > 15.0 || channel < 0 || channel > 2) return false;
    ControlCommand c{}; c.kind = 2; c.i0 = index; c.i1 = type; c.i2 = frequencyHz; c.i3 = channel; c.b0 = enabled;
    c.d0 = q; c.d1 = gainDb;
    return publish(c);
}
void AudioPipeline::setReplayGain(int type, int source, int defaultGainMb, int preampMb) noexcept {
    ControlCommand c{}; c.kind = 8; c.i0 = type; c.i1 = source; c.i2 = defaultGainMb; c.i3 = preampMb;
    (void)publish(c);
}
void AudioPipeline::setReplayGainMetadata(const ReplayGainMetadata& metadata) noexcept {
    ControlCommand c{}; c.kind = 9;
    c.b0 = metadata.albumAvailable; c.b1 = metadata.trackAvailable;
    c.d0 = metadata.albumGainDb; c.d1 = metadata.albumPeakLinear;
    c.d2 = metadata.trackGainDb; c.d3 = metadata.trackPeakLinear;
    (void)publish(c);
}
void AudioPipeline::applyPendingControls() noexcept {
    for (;;) {
        const std::uint32_t tail = controlTail_.load(std::memory_order_relaxed);
        const std::uint32_t head = controlHead_.load(std::memory_order_acquire);
        if (tail == head) break;
        const ControlCommand c = controls_[tail % controls_.size()];
        controlTail_.store(tail + 1, std::memory_order_release);
        if (c.kind == 1) dsp_.setStereoWide(c.b0, c.d0);
        else if (c.kind == 2) (void)dsp_.setEqBand(c.i0, c.b0, c.i1, c.i2, c.d0, c.d1, c.i3);
        else if (c.kind == 3) dsp_.setBalance(c.b0, c.d0);
        else if (c.kind == 4) dsp_.setMono(c.b0);
        else if (c.kind == 5) dsp_.setLimiter(c.b0, static_cast<LimiterPath>(c.i0));
        else if (c.kind == 6) dsp_.setReverbEnabled(c.b0);
        else if (c.kind == 7) dsp_.setReverbParameter(c.i0, c.d0);
        else if (c.kind == 8) dsp_.setReplayGain(c.i0, c.i1, c.i2, c.i3);
        else if (c.kind == 9) {
            ReplayGainMetadata metadata{};
            metadata.albumAvailable = c.b0; metadata.trackAvailable = c.b1;
            metadata.albumGainDb = c.d0; metadata.albumPeakLinear = c.d1;
            metadata.trackGainDb = c.d2; metadata.trackPeakLinear = c.d3;
            dsp_.setReplayGainMetadata(metadata);
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
    return std::pow(10.0, -48.0 * std::abs(magnitude) / 20.0);
}
double recoveredDsdCutoff(double ratio) noexcept {
    if (ratio >= 0.833) return 0.85;
    return std::nextafter(ratio / 0.98, 0.0);
}

} // namespace auralis
