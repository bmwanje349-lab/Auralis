#include "audio_engine.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <string>

using namespace auralis;

namespace {
int failures = 0;
void check(bool ok, const std::string& label) {
    if (!ok) { std::cerr << "FAIL: " << label << '\n'; ++failures; }
    else { std::cout << "PASS: " << label << '\n'; }
}
bool near(double a, double b, double eps = 1e-6) { return std::abs(a - b) <= eps; }
}

int main() {
    // Poweramp StereoWide: direct percentage mapping and exact Mid-Side transform.
    StereoWide wide;
    wide.configure(true, 0.0);
    check(near(wide.sideGain(), 1.0), "StereoWide 0% maps to side gain 1.0");
    wide.configure(true, 25.0); check(near(wide.sideGain(), 1.5), "StereoWide 25% maps to 1.5");
    wide.configure(true, 50.0); check(near(wide.sideGain(), 2.0), "StereoWide 50% maps to 2.0");
    wide.configure(true, 75.0); check(near(wide.sideGain(), 2.5), "StereoWide 75% maps to 2.5");
    wide.configure(true, 100.0); check(near(wide.sideGain(), 3.0), "StereoWide 100% maps to 3.0");
    float stereo[] = {1.0f, 0.0f};
    wide.process(stereo, 1);
    check(near(stereo[0], 2.0) && near(stereo[1], -1.0), "StereoWide 100% gives L'=2L-R and R'=2R-L");
    double stereo64[] = {0.75, -0.25};
    wide.process(stereo64, 1);
    check(near(stereo64[0], 1.75) && near(stereo64[1], -1.25), "StereoWide double-precision callback uses the same recovered transform");
    float mono[] = {0.25f, 0.25f};
    wide.process(mono, 1);
    check(near(mono[0], 0.25) && near(mono[1], 0.25), "StereoWide leaves mono unchanged");

    // Limiter attack/release increments are measured per scalar frame.
    Limiter limiter;
    limiter.configure(true, LimiterPath::HardCeiling, 0.7);
    float hot[] = {2.0f, -2.0f};
    limiter.process(hot, 1);
    check(near(limiter.gain(), 1.0 - 1.0 / 256.0), "Limiter attack decrement is exactly 1/256 per frame");
    float quiet[] = {0.1f, -0.1f};
    limiter.process(quiet, 1);
    check(near(limiter.gain(), 1.0 - 1.0 / 256.0 + 1.0 / 16384.0), "Limiter recovery advances by exactly 1/16384 per frame");
    limiter.reset();
    limiter.process(quiet, 1);
    check(near(limiter.gain(), 1.0), "Limiter recovery is capped at unity");
    check(near(Limiter::kReleaseStep, 1.0 / 16384.0), "Limiter recovery increment constant is exactly 1/16384");
    Limiter dualPath;
    dualPath.configure(true, LimiterPath::HardCeiling, 1.0);
    float hardHot[260]; for (float& v : hardHot) v = 2.0f; dualPath.process(hardHot, 130);
    const double hardState = dualPath.gain();
    dualPath.configure(true, LimiterPath::ConfigurableThreshold, 0.5);
    float softHot[260]; for (float& v : softHot) v = 2.0f; dualPath.process(softHot, 130);
    const double thresholdState = dualPath.gain();
    dualPath.configure(true, LimiterPath::HardCeiling, 1.0);
    check(near(dualPath.gain(), hardState) && !near(hardState, thresholdState), "Limiter hard-ceiling and configurable paths keep separate envelope states");

    // Six documented EQ coefficient families share normalized b0/a0... arrangement.
    BiquadCoefficients coeff{};
    check(calculateBiquad(48000, 1000, EqFilterType::LowPass, 0.707, 0.0, coeff), "EQ low-pass coefficients calculate");
    check(near(coeff.b0, (1.0 - std::cos(2.0 * 3.14159265358979323846 * 1000.0 / 48000.0)) / 2.0 /
               (1.0 + std::sin(2.0 * 3.14159265358979323846 * 1000.0 / 48000.0) / (2.0 * 0.707)), 1e-12),
          "EQ low-pass b0 matches recovered equation");
    check(calculateBiquad(48000, 1000, EqFilterType::HighPass, 0.707, 0.0, coeff), "EQ high-pass coefficients calculate");
    check(calculateBiquad(48000, 1000, EqFilterType::BandPass, 1.0, 0.0, coeff), "EQ band-pass coefficients calculate");
    check(calculateBiquad(48000, 1000, EqFilterType::Peaking, 1.0, 6.0, coeff), "EQ peaking coefficients calculate");
    check(calculateBiquad(48000, 90, EqFilterType::LowShelf, 0.8, 6.0, coeff), "EQ low-shelf coefficients calculate");
    check(calculateBiquad(48000, 10000, EqFilterType::HighShelf, 0.6, -6.0, coeff), "EQ high-shelf coefficients calculate");
    check(!calculateBiquad(48000, 1000, EqFilterType::Peaking, 0.01, 0.0, coeff), "EQ Q lower bound is enforced");

    // ReplayGain source fallback and Apply Peak cap.
    ReplayGain rg;
    ReplayGainMetadata metadata{};
    metadata.trackAvailable = true; metadata.trackGainDb = 6.0; metadata.trackPeakLinear = 2.0;
    rg.configure(1, 0, 0, 0);
    check(near(rg.linearGain(metadata), std::pow(10.0, 6.0 / 20.0), 1e-9), "ReplayGain album source falls back to track when album tags are absent");
    rg.configure(1, 1, 0, 0);
    check(near(rg.linearGain(metadata), std::pow(10.0, 6.0 / 20.0), 1e-9), "ReplayGain track source uses track tags");
    metadata.trackAvailable = false;
    check(near(rg.linearGain(metadata), 1.0), "ReplayGain track source does not fall back to album or default gain is zero");
    metadata.trackAvailable = true;
    rg.configure(2, 1, 0, 0);
    check(near(rg.linearGain(metadata), 0.5, 0.001), "ReplayGain Apply Peak caps gain at the peak-derived ceiling");

    // Reverb setter mapping and sample-rate/Size delay families.
    ReverbControlModel reverb(48000);
    reverb.setNativeParameter(0, 1.0);
    check(near(reverb.parameters().size, 1.0), "Reverb Size maps to 0.01+0.99*UI");
    check(reverb.parameters().delaySamples[0] == 960 && reverb.parameters().delaySamples[1] == 2880,
          "Reverb delay families scale with sample rate and transformed Size");
    reverb.setNativeParameter(6, 0.25);
    check(near(reverb.parameters().damp, 0.75), "Reverb Damp uses 1-UI transformation");
    reverb.setNativeParameter(5, 0.3);
    const double p6 = reverb.parameters().preDelayMix;
    reverb.setNativeParameter(7, 1.0);
    check(reverb.parameters().flag && near(reverb.parameters().preDelayMix, p6), "Reverb native index 7 is a flag operation, not a p6 alias");

    check(near(clampRecoveredTempo(0.1), 0.5) && near(clampRecoveredTempo(4.0), 3.0), "Tempo is constrained to the documented 0.5x–3.0x range");
    check(clampRecoveredResamplerCutoff(20) == 80 && clampRecoveredResamplerCutoff(100) == 99, "Resampler cutoff is constrained to 80–99%");
    check(recoveredNoDvcHeadroomMb(-973) == -950 && recoveredNoDvcHeadroomMb(50) == 0, "No-DVC headroom is bounded and quantized to 50 mB");
    check(near(recoveredBorderGainCeilingDb(0.0), 12.5) && near(recoveredBorderGainCeilingDb(120.0), 11.5), "Smooth EQ border-gain ceiling matches recovered equation");
    check(kDsd44Family.dsd1024Hz == 45158400 && kDsd48Family.dsd1024Hz == 49152000, "All DSD rate-family maxima are retained");
    check(near(recoveredBalanceAttenuation(0.25), std::pow(10.0, -12.0 / 20.0), 1e-9), "Balance attenuation matches recovered -48 dB full-scale law");
    check(near(recoveredDsdCutoff(0.85), 0.85), "DSD cutoff uses 0.85 at ratio >= 0.833");
    check(recoveredDsdCutoff(0.5) < (0.5 / 0.98), "DSD lower branch uses nextafter toward zero");
    check(kP2dResamplerR[0] == 8 && kP2dResamplerR[4] == 128 && kP2dResamplerN[0] == 3 && kP2dResamplerN[1] == 4,
          "Recovered P2D resampler R/N families are retained");
    check(near(kDsd64Coefficients[5], 0.648930895686424810000, 1e-15) &&
          near(kDsd128Coefficients[5], 0.540000000000002260000, 1e-15), "Recovered DSD64/DSD128 coefficient sets are retained");

    // Queue and full DSP pipeline smoke checks.
    AudioRing ring;
    float in[] = {0.2f, -0.1f};
    check(ring.push(in, 1), "Audio ring accepts a valid block");
    float out[2]{};
    check(ring.pop(out, 1) == 1 && near(out[0], 0.2) && near(out[1], -0.1), "Audio ring preserves interleaved stereo data");

    AudioPipeline pipeline(48000);
    pipeline.setStereoWide(true, 100.0);
    float block[] = {1.0f, 0.0f};
    check(pipeline.enqueueInput(block, 1), "Pipeline accepts decoded audio");
    check(pipeline.processOne() == 1, "Dedicated DSP stage processes one block");
    float rendered[2]{};
    check(pipeline.dequeueOutput(rendered, 1) == 1 && near(rendered[0], 2.0) && near(rendered[1], -1.0),
          "Pipeline output matches exact StereoWide transform");

    if (failures != 0) {
        std::cerr << failures << " test(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "All reference DSP tests passed.\n";
    return EXIT_SUCCESS;
}
