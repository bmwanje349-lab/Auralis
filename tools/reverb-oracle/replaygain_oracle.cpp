// Compare Auralis ReplayGain::appliedMillibels with Poweramp's slot-gain routine (0x320348).
// The routine reads the global config at 0x47A250 (+0 type, +4 source, +8 default mB float,
// +0xC preamp mB float) and a slot record (+0x38 availability mask, +0x3C track gain, +0x40 album
// gain, +0x44 track peak, +0x48 album peak), then tail-calls a receiver: [[ctx+0x108]+0x58](obj, 2, slot, track)
// with the gain in millibels in s0. The harness supplies a receiver that records s0.
// build: c++ -std=c++17 -O2 -I app/src/main/cpp tools/reverb-oracle/replaygain_oracle.cpp \
//        app/src/main/cpp/audio_engine.cpp -ldl -o build/replaygain_oracle
#include <dlfcn.h>
#include <link.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <limits>
#include <random>
#include "audio_engine.h"
using Fn = void (*)(void*, void*, int);
static float g_captured = 0.0f;
static int g_calls = 0;
extern "C" void capture_receiver(float gain, void*, int, int, int) { g_captured = gain; g_calls++; }

int main() {
  const char* r = "/data/data/com.termux/files/home/tmp/auralis_poweramp_reference/";
  char path[512];
  snprintf(path, 512, "%slibffmpeg_neon.so", r);
  dlopen(path, RTLD_NOW | RTLD_GLOBAL);
  snprintf(path, 512, "%slibpowerampcore.so", r);
  void* h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!h) { puts(dlerror()); return 1; }
  Dl_info di{};
  void* j = dlsym(h, "JNI_OnLoad");
  if (!j || !dladdr(j, &di)) return 3;
  const uintptr_t base = (uintptr_t)di.dli_fbase;
  auto fn = (Fn)(base + 0x320348);
  auto* cfg = reinterpret_cast<unsigned char*>(base + 0x47a250);

  alignas(16) unsigned char ctx[0x400] = {};
  void* table[32] = {};
  table[0x58 / 8] = reinterpret_cast<void*>(&capture_receiver);
  void* tablePtr = table;
  memcpy(ctx + 0x108, &tablePtr, 8);

  std::mt19937 rng(99);
  std::uniform_real_distribution<float> gdist(-20.f, 12.f), pdist(0.01f, 2.5f);
  const float inf = std::numeric_limits<float>::infinity();
  const float specialPeaks[] = {0.0f, -1.0f, 1.0f, 0.5f, 2.0f, inf, std::numeric_limits<float>::quiet_NaN()};
  size_t cases = 0, bad = 0, noCall = 0;
  double maxAbs = 0;
  for (int type = 0; type <= 2; type++)
    for (int source = 0; source <= 1; source++)
      for (int mask = 0; mask <= 3; mask++)
        for (int iter = 0; iter < 60; iter++) {
          const int defMb = (int)(rng() % 3001) - 1500;
          const int preMb = (int)(rng() % 3001) - 1500;
          const uint32_t t = type, s = source;
          const float defF = (float)defMb, preF = (float)preMb;
          memcpy(cfg + 0, &t, 4);
          memcpy(cfg + 4, &s, 4);
          memcpy(cfg + 8, &defF, 4);
          memcpy(cfg + 0xc, &preF, 4);
          alignas(16) unsigned char slot[0x80] = {};
          const uint32_t m = mask;
          float tg = gdist(rng), ag = gdist(rng);
          float tp = iter < 7 ? specialPeaks[iter] : pdist(rng);
          float ap = (iter >= 7 && iter < 14) ? specialPeaks[iter - 7] : pdist(rng);
          memcpy(slot + 0x38, &m, 4);
          memcpy(slot + 0x3c, &tg, 4);
          memcpy(slot + 0x40, &ag, 4);
          memcpy(slot + 0x44, &tp, 4);
          memcpy(slot + 0x48, &ap, 4);
          g_captured = 12345.f; g_calls = 0;
          fn(ctx, slot, 0);
          if (g_calls != 1) { noCall++; continue; }
          auralis::ReplayGain rg;
          rg.configure(type, source, defMb, preMb);
          auralis::ReplayGainMetadata md;
          md.albumAvailable = (mask & 1) != 0;
          md.trackAvailable = (mask & 2) != 0;
          md.albumGainDb = ag; md.albumPeakLinear = ap;
          md.trackGainDb = tg; md.trackPeakLinear = tp;
          const float mine = rg.appliedMillibels(md);
          cases++;
          if (memcmp(&mine, &g_captured, 4) != 0 && !(std::isnan(mine) && std::isnan(g_captured))) {
            bad++;
            if (bad <= 6)
              printf("  diff type=%d source=%d mask=%d def=%d pre=%d tg=%g ag=%g tp=%g ap=%g native=%.9g auralis=%.9g\n",
                     type, source, mask, defMb, preMb, tg, ag, tp, ap, g_captured, mine);
            if (std::isfinite(mine) && std::isfinite(g_captured)) maxAbs = std::fmax(maxAbs, std::fabs((double)mine - g_captured));
          }
        }
  printf("cases=%zu mismatching=%zu receiver_not_called=%zu max_abs_diff(finite)=%.3g\n", cases, bad, noCall, maxAbs);
  return bad ? 2 : 0;
}
