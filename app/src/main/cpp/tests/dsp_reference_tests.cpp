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
}

int main() {
    // StereoWide: exact Mid/Side transform and direct percent-to-side-gain law.
    StereoWide wide;
    const double slider[] = {0.0,25.0,50.0,75.0,100.0};
    const double expectedGain[] = {1.0,1.5,2.0,2.5,3.0};
    for (int i=0;i<5;++i) {
        wide.configure(true,slider[i]);
        check(near(wide.sideGain(),expectedGain[i]),"StereoWide slider mapping "+std::to_string(static_cast<int>(slider[i]))+"%");
    }
    wide.configure(true,100.0);
    float f[] = {1.0f,0.0f};
    wide.process(f,1);
    check(near(f[0],2.0) && near(f[1],-1.0),"StereoWide 100% exact L'=2L-R, R'=2R-L");
    double d[] = {0.75,-0.25};
    wide.process(d,1);
    check(near(d[0],1.75) && near(d[1],-1.25),"StereoWide double callback same transform");
    float mono[] = {0.25f,0.25f};
    wide.process(mono,1);
    check(near(mono[0],0.25) && near(mono[1],0.25),"StereoWide mono side is zero and remains unchanged");
    wide.configure(false,100.0);
    float bypass[] = {0.75f,-0.25f};
    wide.process(bypass,1);
    check(near(bypass[0],0.75) && near(bypass[1],-0.25),"StereoWide disabled bypass leaves samples unchanged");

    // Limiter: test only recovered per-frame envelope equations; no detector is assumed.
    Limiter limiter;
    limiter.configure(true,LimiterPath::HardCeiling,1.0);
    check(near(limiter.advance(0.5),1.0-1.0/256.0),"Limiter attack is max(target,current-1/256)");
    check(near(limiter.advance(0.5),1.0-2.0/256.0),"Limiter attack advances by 1/256 on each frame");
    const double afterAttack=limiter.gain();
    check(near(limiter.advance(1.0),afterAttack+1.0/16384.0),"Limiter release advances by 1/16384");
    limiter.reset();
    check(near(limiter.advance(1.0),1.0),"Limiter release is capped at unity");
    Limiter separate;
    separate.configure(true,LimiterPath::HardCeiling,1.0);
    for(int i=0;i<128;++i) separate.advance(0.5);
    const double hardState=separate.gain();
    separate.configure(true,LimiterPath::ConfigurableThreshold,0.5);
    for(int i=0;i<192;++i) separate.advance(0.25);
    const double thresholdState=separate.gain();
    separate.configure(true,LimiterPath::HardCeiling,1.0);
    check(near(hardState,0.5) && near(thresholdState,0.25) && near(separate.gain(),hardState),
          "Limiter hard-ceiling and configurable-threshold paths retain separate states");
    check(near(Limiter::kAttackStep,1.0/256.0) && near(Limiter::kReleaseStep,1.0/16384.0),
          "Limiter constants match recovered scalar increments");

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

    // Reverb: native setter indices, transforms, exact delay geometry; do not assign p8 a guessed meaning.
    ReverbControlModel reverb(48000);
    reverb.setNativeParameter(0,1.0);
    check(near(reverb.parameters().size,1.0),"Reverb Size transform maps UI 1 to native 1");
    const std::int64_t delays[8]={960,2880,1440,4272,7200,5760,6720,5280};
    bool allDelays=true;
    for(int i=0;i<8;++i) allDelays &= reverb.parameters().delaySamples[static_cast<std::size_t>(i)]==delays[i];
    check(allDelays,"Reverb all eight delay families equal Fs*seconds*Size");
    reverb.setNativeParameter(0,0.0);
    check(near(reverb.parameters().size,0.01),"Reverb Size transform maps UI zero to 0.01");
    reverb.setNativeParameter(1,0.25);
    reverb.setNativeParameter(2,0.35);
    reverb.setNativeParameter(3,0.45);
    reverb.setNativeParameter(4,0.55);
    reverb.setNativeParameter(5,0.65);
    check(near(reverb.parameters().filter,0.25) && near(reverb.parameters().fade,0.35) &&
          near(reverb.parameters().preDelay,0.45) && near(reverb.parameters().mix,0.55) &&
          near(reverb.parameters().preDelayMix,0.65),"Reverb indices 1–5 preserve recovered identity mappings");
    reverb.setNativeParameter(6,0.25);
    check(near(reverb.parameters().damp,0.75),"Reverb Damp transform equals 1-UI");
    const auto beforeIndex7=reverb.parameters();
    reverb.setNativeParameter(7,1.0);
    check(near(reverb.parameters().preDelayMix,beforeIndex7.preDelayMix) &&
          near(reverb.parameters().size,beforeIndex7.size) &&
          near(reverb.parameters().damp,beforeIndex7.damp),
          "Reverb index 7 is not interpreted as p6 or an invented boolean mapping");
    check(ReverbControlModel::kRecoveredConstants[0]==0.75 &&
          ReverbControlModel::kRecoveredConstants[2]==0.625 &&
          ReverbControlModel::kRecoveredConstants[4]==0.25,
          "Reverb six recovered feedback/combining constants retained");

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
    check(pipeline.enqueueInput(block,1),"Pipeline accepts decoded audio");
    check(pipeline.processOne()==1,"Native DSP stage processes one block");
    float rendered[2]{};
    check(pipeline.dequeueOutput(rendered,1)==1 && near(rendered[0],2.0) && near(rendered[1],-1.0),
          "Pipeline output matches recovered StereoWide transform");

    if (failures!=0) {
        std::cerr << failures << " failed; " << passed << " passed\n";
        return EXIT_FAILURE;
    }
    std::cout << "All " << passed << " Poweramp-reference checks passed.\n";
    return EXIT_SUCCESS;
}
