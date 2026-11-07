#include "audio_engine.h"

#include <array>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <string>

using namespace auralis;

namespace {
int failures = 0;
int passed = 0;
void check(bool ok, const std::string& label) {
    if (!ok) { std::cerr << "FAIL: " << label << '\n'; ++failures; }
    else { std::cout << "PASS: " << label << '\n'; ++passed; }
}
bool near(double a, double b, double eps = 1e-9) { return std::abs(a - b) <= eps; }
bool same(const BiquadCoefficients& a, const BiquadCoefficients& b, double eps=1e-12) {
    return near(a.b0,b.b0,eps) && near(a.b1,b.b1,eps) && near(a.b2,b.b2,eps) &&
           near(a.a1,b.a1,eps) && near(a.a2,b.a2,eps);
}
BiquadCoefficients normalized(double b0,double b1,double b2,double a0,double a1,double a2) {
    return {b0/a0,b1/a0,b2/a0,a1/a0,a2/a0};
}
void powerampStereoWideFloatReference(float& left, float& right, float gain) {
    const float mid = (left + right) * 0.5f;
    left = std::fma(left - mid, gain, mid);
    right = std::fma(right - mid, gain, mid);
}
}

int main() {
    // StereoWide: exact Mid/Side transform and direct percent-to-side-gain law.
    StereoWide wide;
    const double slider[] = {0.0,25.0,50.0,75.0,100.0};
    const double expectedGain[] = {1.0,1.5,2.0,2.5,3.0};
    for (int i=0;i<5;++i) {
        wide.configure(true,slider[i]);
        const float p = static_cast<float>(slider[i]) / 100.0f;
        const float nativeGain = std::fma(2.0f,p,1.0f);
        check(wide.sideGain()==static_cast<double>(nativeGain) &&
              near(wide.sideGain(),expectedGain[i]),
              "StereoWide Float32 FMA slider mapping "+std::to_string(static_cast<int>(slider[i]))+"%");
    }
    wide.configure(true,37.0);
    const float gain37 = std::fma(2.0f,37.0f/100.0f,1.0f);
    check(wide.sideGain()==static_cast<double>(gain37),
          "StereoWide retains the native Float32 context gain at non-binary slider values");

    wide.configure(true,100.0);
    float f[] = {1.0f,0.0f};
    wide.process(f,1);
    check(near(f[0],2.0) && near(f[1],-1.0),"StereoWide 100% exact L'=2L-R, R'=2R-L");
    float nativeFloat[] = {0.73123455f,-0.21765433f};
    float expectedLeft = nativeFloat[0], expectedRight = nativeFloat[1];
    powerampStereoWideFloatReference(expectedLeft,expectedRight,3.0f);
    wide.process(nativeFloat,1);
    check(nativeFloat[0]==expectedLeft && nativeFloat[1]==expectedRight,
          "StereoWide Float32 path matches Poweramp midpoint, per-channel side and FMLA rounding");

    double d[] = {0.75,-0.25};
    wide.process(d,1);
    check(near(d[0],1.75) && near(d[1],-1.25),"StereoWide double callback uses the same Float32 context gain");
    wide.configure(true,37.0);
    const double gain37Double = static_cast<double>(gain37);
    double nativeDouble[] = {0.712345678901,-0.198765432109};
    const double midDouble = (nativeDouble[0]+nativeDouble[1])*0.5;
    const double expectedDoubleLeft = std::fma(nativeDouble[0]-midDouble,gain37Double,midDouble);
    const double expectedDoubleRight = std::fma(nativeDouble[1]-midDouble,gain37Double,midDouble);
    wide.process(nativeDouble,1);
    check(nativeDouble[0]==expectedDoubleLeft && nativeDouble[1]==expectedDoubleRight,
          "StereoWide double path uses the Float32 native gain converted to double and fused output");

    wide.configure(true,-25.0);
    check(wide.sideGain()==1.0,"StereoWide finite values below the slider range clamp to zero width");
    wide.configure(true,125.0);
    check(wide.sideGain()==3.0,"StereoWide finite values above the slider range clamp to maximum width");
    wide.configure(true,std::numeric_limits<double>::quiet_NaN());
    check(wide.sideGain()==1.0,"StereoWide native parameter NaN clamps to zero width");
    wide.configure(true,-std::numeric_limits<double>::infinity());
    check(wide.sideGain()==1.0,"StereoWide negative infinity clamps to zero width");
    wide.configure(true,std::numeric_limits<double>::infinity());
    check(wide.sideGain()==3.0,"StereoWide positive infinity clamps to maximum width");

    wide.configure(true,100.0);
    float mono[] = {0.25f,0.25f};
    wide.process(mono,1);
    check(near(mono[0],0.25) && near(mono[1],0.25),"StereoWide mono side is zero and remains unchanged");
    wide.configure(false,100.0);
    float bypass[] = {0.75f,-0.25f};
    wide.process(bypass,1);
    check(near(bypass[0],0.75) && near(bypass[1],-0.25),"StereoWide disabled bypass leaves samples unchanged");

    // Balance: positive balance attenuates left; negative balance attenuates right.
    Balance balance;
    balance.configure(true,0.25);
    check(near(balance.leftGain(),std::pow(10.0,-12.0/20.0)) && near(balance.rightGain(),1.0),
          "Balance +0.25 attenuates left by 12 dB and leaves right at unity");
    float positiveBalance[] = {1.0f,1.0f};
    balance.process(positiveBalance,1);
    check(near(positiveBalance[0],std::pow(10.0,-12.0/20.0),1e-7) && near(positiveBalance[1],1.0),
          "Balance positive sign routes attenuation to the left channel (Float32 tolerance)");
    balance.configure(true,-1.0);
    float negativeBalance[] = {1.0f,1.0f};
    balance.process(negativeBalance,1);
    check(near(negativeBalance[0],1.0) && near(negativeBalance[1],std::pow(10.0,-48.0/20.0)),
          "Balance negative sign routes full attenuation to the right channel");
    balance.configure(false,1.0);
    float balanceBypass[] = {0.5f,-0.25f};
    balance.process(balanceBypass,1);
    check(near(balanceBypass[0],0.5) && near(balanceBypass[1],-0.25),
          "Disabled Balance bypasses samples");

    Mono monoProcessor;
    monoProcessor.configure(true);
    float monoInput[] = {1.0f,-0.5f};
    monoProcessor.process(monoInput,1);
    check(near(monoInput[0],0.25) && near(monoInput[1],0.25),
          "Mono uses (L+R)/2 and writes the average to both channels");

    // Limiter: native linked stereo peak detector, shared envelope/applied-gain state,
    // scalar tail, four-frame vector update and gain-protection ceiling.
    Limiter limiter;
    limiter.configure(true,LimiterPath::HardCeiling,1.0);
    for(int i=0;i<128;++i) limiter.advance(0.5);
    check(near(limiter.targetGain(),0.5) && near(limiter.gain(),0.5),
          "Limiter scalar attack reaches the 0.5 target in 128 frames at 1/256 per frame");
    const double beforeRelease=limiter.gain();
    limiter.advance(1.0);
    check(near(limiter.targetGain(),0.5+1.0/16384.0) &&
          near(limiter.gain(),beforeRelease+1.0/16384.0),
          "Limiter envelope and applied gain recover at 1/16384 per scalar frame");
    limiter.reset();
    float linked[] = {0.25f,2.0f};
    limiter.process(linked,1);
    check(near(limiter.gain(),1.0-1.0/256.0) &&
          near(linked[0],0.25*(1.0-1.0/256.0)) &&
          near(linked[1],2.0*(1.0-1.0/256.0)),
          "Limiter links left/right peak and applies the same scalar gain to both channels");
    limiter.reset();
    float vectorGroup[] = {2.0f,0.25f,0.5f,1.0f,0.25f,0.1f,0.3f,0.5f};
    limiter.process(vectorGroup,4);
    const double vectorGain=1.0-4.0/256.0;
    check(near(limiter.targetGain(),0.5) && near(limiter.gain(),vectorGain) &&
          near(vectorGroup[0],2.0*vectorGain) && near(vectorGroup[7],0.5*vectorGain),
          "Limiter four-frame vector path uses linked eight-sample peak and four scalar attack steps");
    limiter.reset();
    limiter.configure(true,LimiterPath::ConfigurableThreshold,0.5);
    float configurable[] = {1.0f,0.25f};
    limiter.process(configurable,1);
    check(near(limiter.targetGain(),0.5) && near(limiter.gain(),1.0-1.0/256.0),
          "Limiter configurable path derives target as threshold divided by linked peak");
    limiter.configure(true,LimiterPath::HardCeiling,1.0);
    check(near(limiter.gain(),1.0-1.0/256.0),
          "Limiter hard and configurable paths share one native envelope/applied-gain state");
    check(near(Limiter::kAttackStep,1.0/256.0) &&
          near(Limiter::kReleaseStep,1.0/16384.0) &&
          near(Limiter::kVectorAttackStep,4.0/256.0) &&
          near(Limiter::kVectorReleaseStep,4.0/16384.0),
          "Limiter scalar and four-frame vector coefficients match recovered processing widths");

    // EQ: calculate each supplied coefficient family and compare normalized coefficients.
    constexpr double pi=3.1415926535897932384626433832795;
    const int sr=48000, hz=1000;
    const double q=0.707, gain=6.0;
    const double w=2.0*pi*hz/sr, c=std::cos(w), s=std::sin(w);
    const double alpha=s/(2.0*q), A=std::pow(10.0,gain/40.0), beta=2.0*std::sqrt(A)*alpha;
    BiquadCoefficients coeff{}, expected{};
    check(calculateBiquad(sr,hz,EqFilterType::LowPass,q,0.0,coeff),"EQ accepts low-pass");
    expected=normalized((1-c)/2,1-c,(1-c)/2,1+alpha,-2*c,1-alpha);
    check(same(coeff,expected),"EQ low-pass coefficients match supplied equations");
    check(calculateBiquad(sr,hz,EqFilterType::HighPass,q,0.0,coeff),"EQ accepts high-pass");
    expected=normalized((1+c)/2,-(1+c),(1+c)/2,1+alpha,-2*c,1-alpha);
    check(same(coeff,expected),"EQ high-pass coefficients match supplied equations");
    check(calculateBiquad(sr,hz,EqFilterType::BandPass,q,0.0,coeff),"EQ accepts band-pass");
    expected=normalized(alpha,0,-alpha,1+alpha,-2*c,1-alpha);
    check(same(coeff,expected),"EQ band-pass coefficients match supplied equations");
    check(calculateBiquad(sr,hz,EqFilterType::Peaking,q,gain,coeff),"EQ accepts peaking");
    expected=normalized(1+alpha*A,-2*c,1-alpha*A,1+alpha/A,-2*c,1-alpha/A);
    check(same(coeff,expected),"EQ peaking coefficients match supplied equations");
    check(calculateBiquad(sr,hz,EqFilterType::LowShelf,q,gain,coeff),"EQ accepts low-shelf");
    expected=normalized(A*((A+1)-(A-1)*c+beta),2*A*((A-1)-(A+1)*c),
                       A*((A+1)-(A-1)*c-beta),(A+1)+(A-1)*c+beta,
                       -2*((A-1)+(A+1)*c),(A+1)+(A-1)*c-beta);
    check(same(coeff,expected),"EQ low-shelf coefficients match supplied equations");
    check(calculateBiquad(sr,hz,EqFilterType::HighShelf,q,gain,coeff),"EQ accepts high-shelf");
    expected=normalized(A*((A+1)+(A-1)*c+beta),-2*A*((A-1)+(A+1)*c),
                       A*((A+1)+(A-1)*c-beta),(A+1)-(A-1)*c+beta,
                       2*((A-1)-(A+1)*c),(A+1)-(A-1)*c-beta);
    check(same(coeff,expected),"EQ high-shelf coefficients match supplied equations");
    check(!calculateBiquad(sr,0,EqFilterType::Peaking,q,0,coeff),"EQ rejects frequency below 1 Hz");
    check(!calculateBiquad(sr,24001,EqFilterType::Peaking,q,0,coeff),"EQ rejects frequency above 24 kHz");
    check(!calculateBiquad(sr,hz,EqFilterType::Peaking,0.09,0,coeff),"EQ rejects Q below 0.1");
    check(!calculateBiquad(sr,hz,EqFilterType::Peaking,12.01,0,coeff),"EQ rejects Q above 12");
    check(!calculateBiquad(sr,hz,EqFilterType::Peaking,q,15.01,coeff),"EQ rejects gain above +15 dB");
    check(!calculateBiquad(sr,hz,EqFilterType::Peaking,q,-15.01,coeff),"EQ rejects gain below -15 dB");

    // ReplayGain: album preference fallback, track-only source, default/preamp and peak cap.
    ReplayGain rg;
    ReplayGainMetadata m{};
    m.albumAvailable=true; m.albumGainDb=3.0; m.albumPeakLinear=0.8;
    m.trackAvailable=true; m.trackGainDb=6.0; m.trackPeakLinear=2.0;
    rg.configure(1,0,0,0);
    check(near(rg.linearGain(m),std::pow(10.0,3.0/20.0)),"ReplayGain album source selects album tags when present");
    m.albumAvailable=false;
    check(near(rg.linearGain(m),std::pow(10.0,6.0/20.0)),"ReplayGain album source falls back to track tags");
    rg.configure(1,1,0,0);
    m.albumAvailable=true;
    check(near(rg.linearGain(m),std::pow(10.0,6.0/20.0)),"ReplayGain track source selects track tags");
    m.trackAvailable=false;
    check(near(rg.linearGain(m),1.0),"ReplayGain track source does not fall back to album tags");
    rg.configure(1,1,200,-100);
    check(near(rg.linearGain(m),std::pow(10.0,1.0/20.0)),"ReplayGain missing metadata uses default gain plus preamp");
    rg.configure(0,0,0,0);
    check(near(rg.linearGain(m),1.0),"ReplayGain mode None applies unity gain");
    m.trackAvailable=true; m.trackPeakLinear=2.0;
    rg.configure(2,1,0,0);
    check(near(rg.linearGain(m),0.5,1e-6),"ReplayGain Apply Peak uses min(requested, peak-derived gain)");

    // Reverb: exact native parameter clamps/transforms and native ring-capacity families.
    ReverbControlModel defaultReverb(48000);
    check(near(defaultReverb.parameters().size,0.01),
          "Reverb default UI Size 0 transforms to the native internal minimum 0.01");
    ReverbControlModel reverb(48000);
    reverb.setNativeParameter(0,1.0);
    check(near(reverb.parameters().size,1.0) && reverb.lastNativeUpdateFlag()==2,
          "Reverb Size maps UI 1 to native 1 and requests native update flag 2");
    const auto lengths48=reverb.nativeBufferLengthsSamples();
    check(lengths48==std::array<int,4>{610,4272,7200,9600},
          "Reverb allocator capacities at 48 kHz round the exact native Float32 capacity constants with FCVTAS");
    check(ReverbControlModel::kNativeBufferFamilies[0].count==4 &&
          ReverbControlModel::kNativeBufferFamilies[1].count==6 &&
          ReverbControlModel::kNativeBufferFamilies[2].count==4 &&
          ReverbControlModel::kNativeBufferFamilies[3].count==1,
          "Reverb native allocator has 4 short, 6 medium, 4 long and 1 predelay ring buffers");
    check(reverb.nativeBufferStorageFloats()==std::array<int,15>{
              624,624,624,624,8544,8544,8544,8544,9600,
              14400,14400,14400,14400,8544,8544},
          "Reverb native allocator reproduces short mono, medium stereo, pre-delay, long stereo and packed stereo storage strides");
    reverb.setSampleRate(44100);
    check(reverb.nativeBufferLengthsSamples()==std::array<int,4>{560,3925,6615,8820},
          "Reverb allocator capacities at 44.1 kHz use native Float32 multiply and FCVTAS rounding");
    check(reverb.nativeBufferStorageFloats()==std::array<int,15>{
              560,560,560,560,7856,7856,7856,7856,8820,
              13232,13232,13232,13232,7856,7856},
          "Reverb native channel-plane strides retain 16-float alignment at 44.1 kHz");
    reverb.setSampleRate(48000);
    reverb.setNativeParameter(0,0.0);
    check(near(reverb.parameters().size,0.01),"Reverb Size transform maps UI zero to 0.01");
    reverb.setNativeParameter(1,1.25);
    reverb.setNativeParameter(2,-0.35);
    reverb.setNativeParameter(3,0.45);
    check(reverb.lastNativeUpdateFlag()==2,"Reverb Pre-delay requests native update flag 2");
    reverb.setNativeParameter(4,-0.55);
    reverb.setNativeParameter(5,0.65);
    check(near(reverb.parameters().filter,1.0) && near(reverb.parameters().fade,0.0) &&
          near(reverb.parameters().preDelay,static_cast<double>(0.45f)) && near(reverb.parameters().mix,0.0) &&
          near(reverb.parameters().preDelayMix,static_cast<double>(0.65f)) && reverb.lastNativeUpdateFlag()==1,
          "Reverb native parameter setter clamps each UI value to [0,1] and preserves Float32 values");
    reverb.setNativeParameter(6,0.25);
    check(near(reverb.parameters().damp,0.75) && reverb.lastNativeUpdateFlag()==1,
          "Reverb Damp transform equals 1-UI and requests native update flag 1");
    ReverbControlModel reverbTiming(48000);
    check(reverbTiming.nativeDelayLengthsSamples()==std::array<int,15>{230,172,609,446,9,28,14,42,0,72,57,67,52,4272,3312},
          "Reverb first four delays are fixed; later delay lines scale with native minimum Size");
    reverbTiming.setNativeParameter(0,1.0);
    check(reverbTiming.nativeDelayLengthsSamples()==std::array<int,15>{230,172,609,446,960,2880,1440,4272,0,7200,5760,6720,5280,4272,3312},
          "Reverb eight Size-dependent and two fixed tail delays match native 48 kHz Float32 timing constants");
    reverbTiming.setNativeParameter(3,0.5);
    check(reverbTiming.nativeDelayLengthsSamples()[8]==100,
          "Reverb Pre-Delay uses the native 200-sample control range independent of sample rate");
    ReverbControlModel reverbTapTiming(48000);
    reverbTapTiming.setNativeParameter(0,0.55);
    const std::array<int,24> expectedNativeTaps48k{{
        1783,292,3220, 958,2368,0, 236,2634,0, 1783,109,0,
        955,1051,1699,1867,1987,3321, 475,528,873,907,1022,2068
    }};
    check(reverbTapTiming.nativeTapOffsetsSamples()==expectedNativeTaps48k,
          "Reverb four Size-dependent late-ring tap triplets and two six-tap banks match Poweramp at 48 kHz");
    ReverbControlModel reverbTapTiming441(44100);
    reverbTapTiming441.setNativeParameter(0,0.55);
    const std::array<int,24> expectedNativeTaps441{{
        1638,268,2958, 880,2176,0, 217,2420,0, 1638,100,0,
        877,965,1561,1715,1825,3051, 436,485,802,833,939,1900
    }};
    check(reverbTapTiming441.nativeTapOffsetsSamples()==expectedNativeTaps441,
          "Reverb auxiliary tap geometry scales with native 44.1 kHz sample-rate constants");
    PowerampReverb reverbTapRuntime(48000);
    reverbTapRuntime.setNativeParameter(0,0.55);
    check(reverbTapRuntime.activeTapOffsetsSamples()==expectedNativeTaps48k,
          "PowerampReverb refreshes the native tap map whenever Size reconfigures its delay network");
    PowerampReverb reverbFilterProbe(48000);
    check(near(reverbFilterProbe.filterCutoffHz(0),100.0) &&
          near(reverbFilterProbe.filterCutoffHz(1),18600.0),
          "Reverb Filter and inverted Damp establish independent native low-pass cutoffs");
    reverbFilterProbe.setNativeParameter(6,0.5);
    check(near(reverbFilterProbe.filterCutoffHz(1),9350.0),
          "Reverb Damp UI value maps through native inverse storage to the second cutoff");
    reverbFilterProbe.setNativeParameter(2,0.5);
    check(reverbFilterProbe.controlModel().lastNativeUpdateFlag()==1,
          "Reverb Fade index 2 requests native update flag 1, unlike structural Size/Pre-Delay changes");
    check(near(reverbFilterProbe.lateAllpassCoefficient(),0.5,1e-7),
          "Reverb Fade 50% drives the native later all-pass coefficient to the 0.5 upper bound");
    reverbFilterProbe.setNativeParameter(2,0.2);
    check(near(reverbFilterProbe.lateAllpassCoefficient(),0.3149,1e-6),
          "Reverb Fade uses the native FMA affine mapping and 0.25–0.5 all-pass clamp");
    reverbFilterProbe.setNativeParameter(2,0.0);
    check(near(reverbFilterProbe.lateAllpassCoefficient(),0.25,1e-7),
          "Reverb Fade zero reaches the native 0.25 all-pass coefficient floor");
    std::vector<float> nativeAllpassProbe(1, 0.0f);
    std::size_t nativeAllpassCursor = 0;
    const float nativeAllpassFirst = PowerampReverb::processNativeAllpassSample(
        nativeAllpassProbe, 1, nativeAllpassCursor, 1.0f, 0.75f);
    check(near(nativeAllpassFirst,0.75) && near(nativeAllpassProbe[0],1.5625),
          "Reverb early all-pass uses native coefficient*input-minus-delay and fused feedback write");
    const float nativeAllpassSecond = PowerampReverb::processNativeAllpassSample(
        nativeAllpassProbe, 1, nativeAllpassCursor, 0.0f, 0.75f);
    check(near(nativeAllpassSecond,-1.5625) && near(nativeAllpassProbe[0],-1.171875),
          "Reverb all-pass feedback polarity matches Poweramp on a wrapped one-sample delay");
    PowerampReverb reverbRuntime(48000);
    float reverbBypass[] = {0.25f, -0.5f};
    reverbRuntime.process(reverbBypass, 1);
    check(reverbBypass[0] == 0.25f && reverbBypass[1] == -0.5f,
          "Reverb disabled path is an exact no-op");
    reverbRuntime.setNativeParameter(4, 1.0);
    reverbRuntime.setNativeParameter(5, 0.5);
    reverbRuntime.setEnabled(true);
    std::array<float, 4096> reverbImpulseBlock{};
    std::array<float, 4096> reverbTailBlock{};
    reverbImpulseBlock[0] = 1.0f;
    reverbRuntime.process(reverbImpulseBlock.data(), 2048);
    reverbRuntime.process(reverbTailBlock.data(), 2048);
    bool reverbFinite = true;
    bool reverbTailAudible = false;
    for (float sample : reverbImpulseBlock) reverbFinite = reverbFinite && std::isfinite(sample);
    for (float sample : reverbTailBlock) {
        reverbFinite = reverbFinite && std::isfinite(sample);
        reverbTailAudible = reverbTailAudible || std::abs(sample) > 1.0e-9f;
    }
    check(reverbFinite, "Reverb measured steady-state engine produces only finite samples across consecutive blocks");
    check(reverbTailAudible, "Reverb measured steady-state engine retains delay state and produces a tail in the following silent block");

    // Measured Poweramp steady-state 48 kHz MIMO impulse-response checks.
    // These samples were captured from the native Float32 core after its two
    // zero-input transition blocks; this intentionally tests the steady core,
    // not the separate cold-start/transition callback route.
    const auto setReferenceReverbProfile = [](PowerampReverb& engine) {
        engine.setNativeParameter(0, 0.55);
        engine.setNativeParameter(1, 0.50);
        engine.setNativeParameter(2, 0.45);
        engine.setNativeParameter(3, 0.15);
        engine.setNativeParameter(4, 1.00);
        engine.setNativeParameter(5, 0.50);
        engine.setNativeParameter(6, 0.50);
        engine.setEnabled(true);
    };
    PowerampReverb measuredLeftImpulse(48000);
    setReferenceReverbProfile(measuredLeftImpulse);
    std::array<float, 16384> measuredLeftBlock{}; // 8,192 interleaved stereo frames
    measuredLeftBlock[0] = 1.0f;
    measuredLeftImpulse.process(measuredLeftBlock.data(), 8192);
    const std::array<std::array<double, 3>, 10> leftImpulseCheckpoints{{
        {{0, 0.01982086897, 0.00991043821}},
        {{1, 0.04773608595, 0.02386804298}},
        {{951, 0.01193402149, 0.0}},
        {{1443, 0.01027562842, 0.01027562842}},
        {{2052, -0.01011572592, -0.01011572592}},
        {{2405, 0.03709747642, 0.02277664840}},
        {{2784, 0.01022319216, 0.05318567157}},
        {{3313, 0.0005054753274, 0.07740864903}},
        {{4273, 0.1267852038, -0.001386739081}},
        {{7168, -0.0003596625174, -0.0003596625174}}
    }};
    for (const auto& checkpoint : leftImpulseCheckpoints) {
        const std::size_t frame = static_cast<std::size_t>(checkpoint[0]);
        check(near(measuredLeftBlock[2 * frame], checkpoint[1], 3.0e-6) &&
              near(measuredLeftBlock[2 * frame + 1], checkpoint[2], 3.0e-6),
              "Reverb left-input impulse matches measured Poweramp steady-state MIMO response");
    }

    PowerampReverb measuredRightImpulse(48000);
    setReferenceReverbProfile(measuredRightImpulse);
    std::array<float, 16384> measuredRightBlock{};
    measuredRightBlock[1] = 1.0f;
    measuredRightImpulse.process(measuredRightBlock.data(), 8192);
    const std::array<std::array<double, 3>, 8> rightImpulseCheckpoints{{
        {{0, 0.00991043821, 0.01982086897}},
        {{1, 0.02386804298, 0.04773608595}},
        {{951, 0.007160413545, 0.0}},
        {{1443, 0.01027562842, 0.01027562842}},
        {{2405, 0.02277664840, 0.03709747642}},
        {{2784, 0.01022319216, 0.08182732761}},
        {{3313, 0.0005054753274, 0.1286774129}},
        {{4273, 0.07551643252, -0.001386739081}}
    }};
    for (const auto& checkpoint : rightImpulseCheckpoints) {
        const std::size_t frame = static_cast<std::size_t>(checkpoint[0]);
        check(near(measuredRightBlock[2 * frame], checkpoint[1], 3.0e-6) &&
              near(measuredRightBlock[2 * frame + 1], checkpoint[2], 3.0e-6),
              "Reverb right-input impulse matches measured Poweramp cross-channel response");
    }

    // A deterministic, mixed-channel input verifies the full MIMO convolution,
    // not just two independent impulse paths.
    PowerampReverb measuredMixed(48000);
    setReferenceReverbProfile(measuredMixed);
    std::array<float, 2048> deterministicStereo{};
    for (int frame = 0; frame < 1024; ++frame) {
        const int leftCode = (frame * 37 + 13) % 101 - 50;
        const int rightCode = (frame * 17 + 7) % 83 - 41;
        deterministicStereo[2 * frame] = static_cast<float>(leftCode) * 0.004f;
        deterministicStereo[2 * frame + 1] = static_cast<float>(rightCode) * (1.0f / 300.0f);
    }
    measuredMixed.process(deterministicStereo.data(), 1024);
    const std::array<std::array<double, 3>, 12> mixedCheckpoints{{
        {{0, -0.004056662321, -0.003713116050}},
        {{1, -0.01033157762, -0.01006574556}},
        {{3, 0.003498114645, 0.0006802119315}},
        {{7, 0.002289116383, -0.003216722980}},
        {{15, 0.006050474942, 0.008481822908}},
        {{31, -0.007033168338, -0.009605157189}},
        {{63, 0.006650149822, 0.008746743202}},
        {{127, 0.006417058408, 0.007415071130}},
        {{255, -0.001339780167, -0.001818574965}},
        {{511, 0.007406242192, 0.003058761358}},
        {{767, 0.002918854356, 0.007415972650}},
        {{1023, -0.001587495208, -0.004352688789}}
    }};
    for (const auto& checkpoint : mixedCheckpoints) {
        const std::size_t frame = static_cast<std::size_t>(checkpoint[0]);
        check(near(deterministicStereo[2 * frame], checkpoint[1], 4.0e-6) &&
              near(deterministicStereo[2 * frame + 1], checkpoint[2], 4.0e-6),
              "Reverb deterministic mixed-stereo render matches Poweramp native MIMO output");
    }
    reverb.setNativeParameter(1,std::numeric_limits<double>::quiet_NaN());
    check(near(reverb.parameters().filter,1.0),
          "Reverb FMINNM/FMAXNM parameter clamp selects the numeric endpoint for NaN");
    const auto beforeIndex7=reverb.parameters();
    reverb.setNativeParameter(7,1.0);
    check(near(reverb.parameters().preDelayMix,beforeIndex7.preDelayMix) &&
          near(reverb.parameters().size,beforeIndex7.size) &&
          near(reverb.parameters().damp,beforeIndex7.damp) &&
          reverb.lastNativeUpdateFlag()==1,
          "Reverb eighth native parameter slot is a flag-only operation with no p8 field");
    check(ReverbControlModel::kRecoveredConstants[0]==0.75 &&
          ReverbControlModel::kRecoveredConstants[1]==0.75 &&
          ReverbControlModel::kRecoveredConstants[2]==0.625 &&
          ReverbControlModel::kRecoveredConstants[3]==0.625 &&
          ReverbControlModel::kRecoveredConstants[4]==0.25 &&
          ReverbControlModel::kRecoveredConstants[5]==0.25,
          "Reverb four all-pass and two late-network feedback constants retained");

    // Remaining exact numeric settings and values from the supplied reference.
    check(near(clampRecoveredTempo(0.1),0.5) && near(clampRecoveredTempo(4.0),3.0),"Tempo range is 0.5x–3.0x");
    check(clampRecoveredResamplerCutoff(20)==80 && clampRecoveredResamplerCutoff(100)==99 &&
          kDefaultResamplerCutoffPercent==97,"Resampler cutoff bounds and 97% default retained");
    check(recoveredNoDvcHeadroomMb(-973)==-950 && recoveredNoDvcHeadroomMb(50)==0 &&
          kDefaultNoDvcHeadroomMb==-600,"No-DVC bounds, 50 mB steps and -600 mB default retained");
    check(near(recoveredBorderGainCeilingDb(0.0),12.5) &&
          near(recoveredBorderGainCeilingDb(120.0),11.5) && kBorderGainThresholdMb==-298,
          "Smooth EQ border-gain ceiling and approximate threshold retained");
    check(near(recoveredBalanceAttenuation(0.25),std::pow(10.0,-12.0/20.0)) &&
          near(recoveredBalanceAttenuation(1.0),std::pow(10.0,-48.0/20.0)),
          "Balance attenuation is 10^(-48*abs(b)/20)");
    check(near(recoveredDsdCutoff(0.85),0.85),"DSD cutoff uses 0.85 when ratio is >=0.833");
    check(recoveredDsdCutoff(0.5)==std::nextafter(0.5/0.98,0.0),
          "DSD lower cutoff branch uses nextafter toward zero");
    check(kDsd44Family.dsd64Hz==2822400 && kDsd44Family.dsd1024Hz==45158400 &&
          kDsd48Family.dsd64Hz==3072000 && kDsd48Family.dsd1024Hz==49152000,
          "DSD64–DSD1024 rates retained in both rate families");
    check(near(kDsd64Coefficients[0],0.000037487688268165004,1e-18) &&
          near(kDsd64Coefficients[5],0.648930895686424810000,1e-15) &&
          near(kDsd128Coefficients[0],0.000014693280767999990,1e-18) &&
          near(kDsd128Coefficients[5],0.540000000000002260000,1e-15),
          "Supplied DSD64 and DSD128 coefficient endpoints retained");
    check(kP2dResamplerR==std::array<int,5>{8,16,32,64,128} &&
          kP2dResamplerN==std::array<int,2>{3,4},"P2D R/N families retained exactly");
    check(static_cast<int>(DitherMode::None)==1 && static_cast<int>(DitherMode::LowShibata)==9,
          "Dither mode IDs retained as 1–9");

    // SPSC queue must retain a partially consumed block rather than drop its tail.
    AudioRing ring;
    float in[]={0.2f,-0.1f,0.3f,-0.2f,0.4f,-0.3f};
    check(ring.push(in,3),"Audio ring accepts a three-frame block");
    float first[2]{}, rest[4]{};
    check(ring.pop(first,1)==1 && near(first[0],0.2,1e-6) && near(first[1],-0.1,1e-6),
          "Audio ring returns the first frame on a partial read");
    check(ring.pop(rest,2)==2 && near(rest[0],0.3,1e-6) && near(rest[1],-0.2,1e-6) &&
          near(rest[2],0.4,1e-6) && near(rest[3],-0.3,1e-6),"Audio ring preserves and returns unread tail");
    check(ring.empty(),"Audio ring empties only after the whole block is consumed");

    AudioPipeline pipeline(48000);
    pipeline.setStereoWide(true,100.0);
    float block[]={1.0f,0.0f};
    check(pipeline.setEqBand(0,false,4,90,0.8,6.0,0),"Pipeline accepts an in-range EQ update");
    check(!pipeline.setEqBand(0,true,4,90,0.01,6.0,0),"Pipeline rejects EQ Q outside recovered range");
    pipeline.setMono(false);
    pipeline.setBalance(false,0.0);
    pipeline.setLimiter(false,LimiterPath::HardCeiling);
    check(pipeline.enqueueInput(block,1),"Pipeline accepts decoded audio");
    check(pipeline.processOne()==1,"Native DSP stage processes one block");
    float rendered[2]{};
    check(pipeline.dequeueOutput(rendered,1)==1 && near(rendered[0],2.0) && near(rendered[1],-1.0),
          "Pipeline output matches recovered StereoWide transform");

    // Poweramp's internal DSPI callback order is StereoWide -> Mono -> Balance -> Limiter.
    AudioPipeline spatialOrderPipeline(48000);
    spatialOrderPipeline.setStereoWide(true,100.0);
    spatialOrderPipeline.setMono(true);
    spatialOrderPipeline.setBalance(true,1.0);
    spatialOrderPipeline.setLimiter(false,LimiterPath::HardCeiling);
    float spatialOrderInput[]={1.0f,0.0f};
    check(spatialOrderPipeline.enqueueInput(spatialOrderInput,1) &&
          spatialOrderPipeline.processOne()==1,
          "Spatial chain accepts a block with StereoWide, Mono and Balance enabled");
    float spatialOrderOutput[2]{};
    check(spatialOrderPipeline.dequeueOutput(spatialOrderOutput,1)==1 &&
          near(spatialOrderOutput[0],0.5*std::pow(10.0,-48.0/20.0),1e-8) &&
          near(spatialOrderOutput[1],0.5,1e-7),
          "Spatial chain applies StereoWide before Mono fold-down and Balance attenuation");

    AudioPipeline widenedLimiterPipeline(48000);
    widenedLimiterPipeline.setStereoWide(true,100.0);
    widenedLimiterPipeline.setMono(false);
    widenedLimiterPipeline.setBalance(false,0.0);
    widenedLimiterPipeline.setLimiter(true,LimiterPath::HardCeiling);
    float widenedPeakInput[]={1.0f,0.0f};
    check(widenedLimiterPipeline.enqueueInput(widenedPeakInput,1) &&
          widenedLimiterPipeline.processOne()==1,
          "Widened signal reaches the downstream native limiter stage");
    float widenedPeakOutput[2]{};
    const float firstLimiterGain=1.0f-1.0f/256.0f;
    check(widenedLimiterPipeline.dequeueOutput(widenedPeakOutput,1)==1 &&
          near(widenedPeakOutput[0],2.0*firstLimiterGain,1e-7) &&
          near(widenedPeakOutput[1],-firstLimiterGain,1e-7),
          "Limiter detects and links the post-StereoWide left/right peak");

    AudioPipeline controlPipeline(48000);
    controlPipeline.setBalance(true,1.0);
    controlPipeline.setMono(false);
    controlPipeline.setLimiter(false,LimiterPath::HardCeiling);
    float balancedBlock[]={1.0f,1.0f};
    check(controlPipeline.enqueueInput(balancedBlock,1) && controlPipeline.processOne()==1,
          "Pipeline accepts a block with queued Balance controls");
    float balancedRender[2]{};
    check(controlPipeline.dequeueOutput(balancedRender,1)==1 &&
          near(balancedRender[0],std::pow(10.0,-48.0/20.0)) && near(balancedRender[1],1.0),
          "Pipeline applies native Balance routing on the DSP thread");

    AudioPipeline replayGainPipeline(48000);
    replayGainPipeline.setStereoWide(false,0.0);
    replayGainPipeline.setMono(false);
    replayGainPipeline.setBalance(false,0.0);
    replayGainPipeline.setLimiter(false,LimiterPath::HardCeiling);
    ReplayGainMetadata pipelineTags{};
    pipelineTags.albumAvailable=true; pipelineTags.albumGainDb=-6.0; pipelineTags.albumPeakLinear=0.8;
    pipelineTags.trackAvailable=true; pipelineTags.trackGainDb=9.0; pipelineTags.trackPeakLinear=0.5;
    replayGainPipeline.setReplayGain(1,0,0,0);
    replayGainPipeline.setReplayGainMetadata(pipelineTags);
    float replayGainInput[]={0.5f,-0.5f};
    check(replayGainPipeline.enqueueInput(replayGainInput,1) && replayGainPipeline.processOne()==1,
          "ReplayGain settings and track metadata traverse the native control queue");
    float replayGainOutput[2]{};
    const double albumLinear=std::pow(10.0,-6.0/20.0);
    check(replayGainPipeline.dequeueOutput(replayGainOutput,1)==1 &&
          near(replayGainOutput[0],0.5*albumLinear,1e-7) && near(replayGainOutput[1],-0.5*albumLinear,1e-7),
          "Pipeline applies selected album ReplayGain before the EQ and spatial stages");

    AudioPipeline peakCappedPipeline(48000);
    peakCappedPipeline.setStereoWide(false,0.0);
    peakCappedPipeline.setMono(false);
    peakCappedPipeline.setBalance(false,0.0);
    peakCappedPipeline.setLimiter(false,LimiterPath::HardCeiling);
    pipelineTags.albumGainDb=9.0; pipelineTags.albumPeakLinear=0.5;
    peakCappedPipeline.setReplayGain(2,0,0,0);
    peakCappedPipeline.setReplayGainMetadata(pipelineTags);
    float peakCapInput[]={0.25f,-0.25f};
    check(peakCappedPipeline.enqueueInput(peakCapInput,1) && peakCappedPipeline.processOne()==1,
          "ReplayGain Apply Peak control path processes through the native queue");
    float peakCapOutput[2]{};
    check(peakCappedPipeline.dequeueOutput(peakCapOutput,1)==1 &&
          near(peakCapOutput[0],0.5,1e-7) && near(peakCapOutput[1],-0.5,1e-7),
          "ReplayGain Apply Peak limits requested gain to the album peak headroom");

    if (failures!=0) {
        std::cerr << failures << " failed; " << passed << " passed\n";
        return EXIT_FAILURE;
    }
    std::cout << "All " << passed << " Poweramp-reference checks passed.\n";
    return EXIT_SUCCESS;
}
