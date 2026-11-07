#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace auralis {

constexpr int kMaxEqBands = 64;
constexpr int kChunkFrames = 1024;
constexpr int kQueueSlots = 8;

enum class EqFilterType : int { LowPass = 0, HighPass = 1, BandPass = 2, Peaking = 3, LowShelf = 4, HighShelf = 5 };

enum class LimiterPath : int { HardCeiling = 0, ConfigurableThreshold = 1 };

enum class ResamplerMode : int { Fast = 0x11, Balanced = 0x1F };

enum class DitherMode : int {
    None = 1, Rectangular = 2, Triangular = 3, TriangularHighPass = 4,
    NoiseShapingFWeighted = 5, ModifiedEWeighted = 6, ImprovedEWeighted = 7,
    Shibata = 8, LowShibata = 9
};

struct BiquadCoefficients {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
};

struct BiquadState {
    double z1 = 0.0, z2 = 0.0;
    double process(double x, const BiquadCoefficients& c) noexcept;
    void reset() noexcept { z1 = 0.0; z2 = 0.0; }
};

bool calculateBiquad(int sampleRate, int frequencyHz, EqFilterType type,
                     double q, double gainDb, BiquadCoefficients& out) noexcept;
double evaluateBiquadMagnitudeDb(const BiquadCoefficients& c, double frequencyHz,
                                  double sampleRate) noexcept;

struct EqBand {
    bool enabled = false;
    EqFilterType type = EqFilterType::Peaking;
    int frequencyHz = 1000;
    double q = 1.0;
    double gainDb = 0.0;
    int channel = 0; // Auralis-local: 0=both, 1=left, 2=right; Poweramp's numeric mapping is not established
    BiquadCoefficients coefficients{};
    BiquadState left{}, right{};
};

struct ReplayGainMetadata {
    bool albumAvailable = false;
    bool trackAvailable = false;
    double albumGainDb = 0.0;
    double albumPeakLinear = 0.0;
    double trackGainDb = 0.0;
    double trackPeakLinear = 0.0;
};

class ReplayGain {
public:
    // Recovered preference values: type 0=None, 1=Apply, 2=Apply Peak; source 0=Album, 1=Track.
    void configure(int type, int source, int defaultGainMb, int preampMb) noexcept;
    double linearGain(const ReplayGainMetadata& metadata) const noexcept;
    static double dbToLinear(double db) noexcept;
    static double millibelsToLinear(double millibels) noexcept;
private:
    int type_ = 0;
    int source_ = 0;
    int defaultGainMb_ = 0;
    int preampMb_ = 0;
};

class Limiter {
public:
    void configure(bool enabled, LimiterPath path, double configurableThreshold) noexcept;
    void setConfigurableThreshold(double threshold) noexcept;
    void reset() noexcept;
    // One scalar sample update. targetGain is the detector's ceiling/peak result.
    double advance(double targetGain) noexcept;
    // Poweramp's native SIMD branch evaluates four stereo frames together, then
    // applies one gain to those four frames. The remaining 0–3 frames use scalar steps.
    void process(float* interleavedStereo, std::size_t frames) noexcept;
    double gain() const noexcept { return static_cast<double>(appliedGain_); }
    double targetGain() const noexcept { return static_cast<double>(envelopeGain_); }
    double configurableThreshold() const noexcept { return configurableThreshold_; }
    static constexpr double kAttackStep = 1.0 / 256.0;
    static constexpr double kReleaseStep = 1.0 / 16384.0;
    static constexpr double kVectorAttackStep = 4.0 * kAttackStep;
    static constexpr double kVectorReleaseStep = 4.0 * kReleaseStep;
    static constexpr double kUnboundedThreshold = 999.999;
private:
    // Poweramp keeps the applied gain (+0xEC) and target envelope (+0xF0) as Float32 and
    // does every limiter step in Float32, so this class does too.
    float update(float peak, float ceiling, float attackStep, float releaseStep) noexcept;
    bool enabled_ = false;
    LimiterPath path_ = LimiterPath::HardCeiling;
    double configurableThreshold_ = 1.0;
    float appliedGain_ = 1.0f;
    float envelopeGain_ = 1.0f;
};

class StereoWide {
public:
    void configure(bool enabled, double sliderPercent) noexcept;
    void process(float* interleavedStereo, std::size_t frames) const noexcept;
    void process(double* interleavedStereo, std::size_t frames) const noexcept;
    // Poweramp stores the normalized width gain in its native Float32 context.
    // Return double for the public diagnostic API without changing that storage precision.
    double sideGain() const noexcept { return static_cast<double>(sideGain_); }
private:
    bool enabled_ = false;
    // Poweramp's parameter handler sets DSPI flag 0x04 only when the clamped
    // normalized width is not exactly zero, even if g = fma(2,p,1) rounds to 1.0f.
    bool widthActive_ = false;
    float sideGain_ = 1.0f;
};

// Native DSPI Balance: positive values attenuate left, negative values attenuate right.
class Balance {
public:
    void configure(bool enabled, double normalized) noexcept;
    void process(float* interleavedStereo, std::size_t frames) const noexcept;
    double normalized() const noexcept { return balance_; }
    double leftGain() const noexcept { return leftGain_; }
    double rightGain() const noexcept { return rightGain_; }
private:
    bool enabled_ = false;
    double balance_ = 0.0;
    double leftGain_ = 1.0;
    double rightGain_ = 1.0;
};

// Native DSPI Mono uses (L+R)/2 for both output channels.
class Mono {
public:
    void configure(bool enabled) noexcept { enabled_ = enabled; }
    void process(float* interleavedStereo, std::size_t frames) const noexcept;
    bool enabled() const noexcept { return enabled_; }
private:
    bool enabled_ = false;
};

struct ReverbParameters {
    double size = 0.01;
    double filter = 0.0;
    double fade = 0.0;
    double preDelay = 0.0;
    double mix = 0.0;
    double preDelayMix = 0.0;
    double damp = 1.0;
};

struct ReverbBufferFamily {
    double seconds;
    int count;
};

class ReverbControlModel {
public:
    explicit ReverbControlModel(int sampleRate = 48000) noexcept;
    void setSampleRate(int sampleRate) noexcept;
    // Native setter input is clamped to [0,1]. Indices 0 and 3 write update flag 2;
    // indices 1,2,4,5,6 write flag 1. Index 7 is a flag-only operation (flag 1).
    static constexpr int kNativeFlagOperationIndex = 7;
    void setNativeParameter(int index, double value) noexcept;
    const ReverbParameters& parameters() const noexcept { return params_; }
    std::uint32_t lastNativeUpdateFlag() const noexcept { return nativeUpdateFlag_; }
    int sampleRate() const noexcept { return sampleRate_; }
    std::array<int, 4> nativeBufferLengthsSamples() const noexcept;
    std::array<int, 15> nativeBufferStorageFloats() const noexcept;
    std::array<int, 15> nativeDelayLengthsSamples() const noexcept;
    // Native ReverbDSPPlugin.cpp tap offsets in this order: +0x14C/+150/+154,
    // +0x16C/+170/+174, +0x18C/+190/+194, +0x1AC/+1B0/+1B4, then the
    // six taps for packed bank +0x1C0 and the six taps for packed bank +0x1F0.
    std::array<int, 24> nativeTapOffsetsSamples() const noexcept;
    // Allocator families are logical capacities; stereo objects contain two
    // contiguous channel planes and the backing strides are separately padded.
    static constexpr std::array<ReverbBufferFamily, 4> kNativeBufferFamilies{{
        {0.0127, 4}, {0.089, 6}, {0.150, 4}, {0.200, 1}
    }};
    // The active core has four fixed early diffusion periods, ten Size-scaled
    // network periods, and one Fade-controlled pre-delay line. The order follows
    // the native context pointer layout (0x28 through 0x1F0).
    static constexpr std::array<float, 4> kNativeFixedDelaySeconds{{
        0.0048f, 0.0036f, 0.0127f, 0.0093f
    }};
    static constexpr std::array<float, 8> kNativeSizeDelaySeconds{{
        0.0200f, 0.0600f, 0.0300f, 0.0890f,
        0.1500f, 0.1200f, 0.1400f, 0.1100f
    }};
    static constexpr std::array<float, 2> kNativeFixedTailDelaySeconds{{0.0890f, 0.0690f}};
    // First four native all-pass gains, followed by the two fixed late diffusion gains.
    static constexpr std::array<float, 6> kRecoveredConstants{{0.75f, 0.75f, 0.625f, 0.625f, 0.25f, 0.25f}};
private:
    int sampleRate_ = 48000;
    ReverbParameters params_{};
    std::uint32_t nativeUpdateFlag_ = 0;
};

// Measured Poweramp Float32 steady-state Reverb. The 2x2 impulse matrix was
// captured from the installed native callback at 48 kHz and verified against
// arbitrary steady-state stereo input (not a guessed Schroeder approximation).
class PowerampReverb {
public:
    explicit PowerampReverb(int sampleRate);
    void setEnabled(bool enabled) noexcept;
    void setNativeParameter(int index, double value) noexcept;
    void process(float* interleavedStereo, std::size_t frames) noexcept;
    const ReverbControlModel& controlModel() const noexcept { return controls_; }
    std::array<int, 15> activeDelayLengthsSamples() const noexcept { return activeLengths_; }
    std::array<int, 24> activeTapOffsetsSamples() const noexcept { return activeTapOffsets_; }
    double filterCutoffHz(int index) const noexcept;
    float lateAllpassCoefficient() const noexcept;
    static float processNativeAllpassSample(std::vector<float>& ring, std::size_t length,
                                            std::size_t& cursor, float input,
                                            float coefficient) noexcept;
private:
    struct Complex { float re = 0.0f; float im = 0.0f; };
    static constexpr std::size_t kBlockFrames = 1024;
    static constexpr std::size_t kFftSize = 2048;
    using FftBuffer = std::array<Complex, kFftSize>;
    void configureDelays() noexcept;
    void configureFilters() noexcept;
    void initialiseFft() noexcept;
    void configureKernel();
    void transform(FftBuffer& values, bool inverse) const noexcept;
    void processBlock(float* interleavedStereo, std::size_t frames) noexcept;
    static Complex multiply(Complex a, Complex b) noexcept;
    int sampleRate_;
    ReverbControlModel controls_;
    bool enabled_ = false;
    bool hasProcessed_ = false;
    std::array<int, 15> activeLengths_{};
    std::array<int, 24> activeTapOffsets_{};
    std::array<std::array<float, 5>, 2> filterCoefficients_{};
    std::size_t kernelFrames_ = 0;
    std::size_t partitions_ = 0;
    std::size_t historyHead_ = 0;
    std::array<Complex, kFftSize / 2> twiddles_{};
    // Spectra order: output-L/input-L, output-L/input-R, output-R/input-L, output-R/input-R.
    std::array<std::vector<Complex>, 4> kernelSpectra_{};
    std::array<std::vector<Complex>, 2> inputHistory_{};
    FftBuffer workLeft_{};
    FftBuffer workRight_{};
    FftBuffer sumLeft_{};
    FftBuffer sumRight_{};
    std::array<float, kBlockFrames> previousInputLeft_{};
    std::array<float, kBlockFrames> previousInputRight_{};
    float mixCurrent_ = 0.0f;
};

class AuralisDsp {
public:
    explicit AuralisDsp(int sampleRate);
    void setStereoWide(bool enabled, double sliderPercent) noexcept;
    void setBalance(bool enabled, double normalized) noexcept;
    void setMono(bool enabled) noexcept;
    void setLimiter(bool enabled, LimiterPath path) noexcept;
    void setReverbEnabled(bool enabled) noexcept;
    void setReverbParameter(int index, double value) noexcept;
    bool setEqBand(int index, bool enabled, int type, int frequencyHz, double q,
                   double gainDb, int channel) noexcept;
    void setReplayGain(int type, int source, int defaultGainMb, int preampMb) noexcept;
    void setReplayGainMetadata(const ReplayGainMetadata& metadata) noexcept;
    void setEqPreampDb(double db) noexcept;
    void setMasterGainDb(double db) noexcept;
    void process(float* interleavedStereo, std::size_t frames) noexcept;
    double stereoSideGain() const noexcept { return stereoWide_.sideGain(); }
    double balanceLeftGain() const noexcept { return balance_.leftGain(); }
    double balanceRightGain() const noexcept { return balance_.rightGain(); }
    double limiterGain() const noexcept { return limiter_.gain(); }
    const ReverbControlModel& reverbModel() const noexcept { return reverb_.controlModel(); }
private:
    int sampleRate_;
    std::array<EqBand, kMaxEqBands> bands_{};
    StereoWide stereoWide_{};
    Balance balance_{};
    Mono mono_{};
    Limiter limiter_{};
    ReplayGain replayGain_{};
    ReplayGainMetadata replayGainMetadata_{};
    PowerampReverb reverb_;
    double eqPreampLinear_ = 1.0;
    double masterGainLinear_ = 1.0;
};

// Lock-free single-producer/single-consumer queue. Storage is allocated once during engine setup.
class AudioRing {
public:
    AudioRing();
    bool push(const float* interleaved, std::size_t frames) noexcept;
    std::size_t pop(float* interleaved, std::size_t capacityFrames) noexcept;
    bool empty() const noexcept;
    bool full() const noexcept;
private:
    struct Slot {
        std::array<float, kChunkFrames * 2> samples{};
        std::uint32_t frames = 0;
        std::uint32_t readOffsetFrames = 0;
    };
    std::array<Slot, kQueueSlots> slots_{};
    alignas(64) std::atomic<std::uint32_t> head_{0};
    alignas(64) std::atomic<std::uint32_t> tail_{0};
};

class AudioPipeline {
public:
    explicit AudioPipeline(int sampleRate);
    bool enqueueInput(const float* interleaved, std::size_t frames) noexcept;
    // Returns frames processed; 0 means no input is available; -1 means output queue is full.
    int processOne() noexcept;
    std::size_t dequeueOutput(float* interleaved, std::size_t capacityFrames) noexcept;
    bool inputEmpty() const noexcept { return input_.empty(); }
    // UI/configuration thread publishes commands; the DSP thread applies them between blocks.
    void setStereoWide(bool enabled, double percent) noexcept;
    void setBalance(bool enabled, double normalized) noexcept;
    void setMono(bool enabled) noexcept;
    void setLimiter(bool enabled, LimiterPath path) noexcept;
    void setReverbEnabled(bool enabled) noexcept;
    void setReverbParameter(int index, double value) noexcept;
    bool setEqBand(int index, bool enabled, int type, int frequencyHz, double q, double gainDb, int channel) noexcept;
    void setReplayGain(int type, int source, int defaultGainMb, int preampMb) noexcept;
    void setReplayGainMetadata(const ReplayGainMetadata& metadata) noexcept;
private:
    struct ControlCommand {
        int kind=0, i0=0, i1=0, i2=0, i3=0;
        double d0=0.0, d1=0.0, d2=0.0, d3=0.0;
        bool b0=false, b1=false;
    };
    bool publish(const ControlCommand& command) noexcept;
    void applyPendingControls() noexcept;
    std::array<ControlCommand, 128> controls_{};
    alignas(64) std::atomic<std::uint32_t> controlHead_{0};
    alignas(64) std::atomic<std::uint32_t> controlTail_{0};
    AudioRing input_;
    AudioRing output_;
    AuralisDsp dsp_;
    std::array<float, kChunkFrames * 2> scratch_{};
};

// Recovered configuration data which can be represented exactly without claiming an unknown kernel.
constexpr int kDefaultResamplerCutoffPercent = 97;
constexpr std::array<int, 5> kP2dResamplerR = {8, 16, 32, 64, 128};
constexpr std::array<int, 2> kP2dResamplerN = {3, 4};
constexpr std::array<double, 6> kDsd64Coefficients = {
    0.000037487688268165004, 0.000037487688264928030, 0.001318843318019954700,
    0.021512542988272119000, 0.166867293178567580000, 0.648930895686424810000
};
constexpr std::array<double, 6> kDsd128Coefficients = {
    0.000014693280767999990, 0.000014693280774056924, 0.000680244479988591400,
    0.012597119999973313000, 0.116639999999997970000, 0.540000000000002260000
};


struct DsdRatePair { int dsd64Hz; int dsd128Hz; int dsd256Hz; int dsd512Hz; int dsd1024Hz; };
constexpr DsdRatePair kDsd44Family{2822400, 5644800, 11289600, 22579200, 45158400};
constexpr DsdRatePair kDsd48Family{3072000, 6144000, 12288000, 24576000, 49152000};

// Tempo's recovered user-facing bounds; this does not implement an unrecovered time-stretch kernel.
double clampRecoveredTempo(double factor) noexcept;
// Resampler cutoff preference bounds/default are recovered; the exact SoX/P2D kernel is not part of this model.
int clampRecoveredResamplerCutoff(int cutoffPercent) noexcept;
constexpr int kDefaultNoDvcHeadroomMb = -600;
constexpr int kMinimumNoDvcHeadroomMb = -1000;
constexpr int kMaximumNoDvcHeadroomMb = 0;
constexpr int kNoDvcHeadroomStepMb = 50;
constexpr int kBorderGainThresholdMb = -298;
int recoveredNoDvcHeadroomMb(int valueMb) noexcept;
double recoveredBorderGainCeilingDb(double s) noexcept;

double recoveredBalanceAttenuation(double normalizedMagnitude) noexcept;
double recoveredDsdCutoff(double ratio) noexcept;

} // namespace auralis
