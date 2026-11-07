// Compare Auralis calculateBiquad against Poweramp's native EQ coefficient kernel (0x306FD8).
// native: int kernel(double out[5], int sampleRate, int freqHz, int type, float q, float gainDb)
// out = {b0, b1, b2, a1, a2} already divided by a0. Q and gain are Float32 arguments.
// build: c++ -std=c++17 -O2 -I app/src/main/cpp tools/reverb-oracle/eq_coeff_oracle.cpp \
//        app/src/main/cpp/audio_engine.cpp -ldl -o build/eq_coeff_oracle
#include <dlfcn.h>
#include <link.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <map>
#include "audio_engine.h"
using Kernel = int (*)(double*, int, int, int, float, float);

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
  auto kernel = (Kernel)((uintptr_t)di.dli_fbase + 0x306fd8);

  const int rates[] = {8000, 22050, 44100, 48000, 88200, 96000, 192000};
  const int freqs[] = {1, 20, 31, 63, 100, 250, 1000, 4000, 10000, 16000, 20000, 22000, 24000};
  const float qs[] = {0.1f, 0.5f, 0.707f, 1.0f, 2.0f, 4.5f, 12.0f};
  const float gains[] = {-15.f, -12.3f, -6.f, -1.f, 0.f, 0.5f, 3.f, 9.9f, 15.f};
  const char* names[] = {"LowPass", "HighPass", "BandPass", "Peaking", "LowShelf", "HighShelf"};
  size_t cases[6] = {}, bad[6] = {}, skipped = 0;
  double maxRel[6] = {};
  for (int type = 0; type < 6; type++)
    for (int rate : rates)
      for (int f : freqs)
        for (float q : qs)
          for (float g : gains) {
            double mine[5] = {};
            auralis::BiquadCoefficients c{};
            // Auralis rejects out-of-range input; native's kernel does not, so only compare accepted cases.
            if (!auralis::calculateBiquad(rate, f, static_cast<auralis::EqFilterType>(type),
                                          static_cast<double>(q), static_cast<double>(g), c)) { skipped++; continue; }
            double nat[8] = {};
            kernel(nat, rate, f, type, q, g);
            mine[0] = c.b0; mine[1] = c.b1; mine[2] = c.b2; mine[3] = c.a1; mine[4] = c.a2;
            cases[type]++;
            bool diff = false;
            for (int k = 0; k < 5; k++) {
              if (memcmp(&mine[k], &nat[k], 8) != 0) {
                diff = true;
                const double den = std::fabs(nat[k]) > 1e-300 ? std::fabs(nat[k]) : 1.0;
                maxRel[type] = std::fmax(maxRel[type], std::fabs(mine[k] - nat[k]) / den);
              }
            }
            if (diff) bad[type]++;
          }
  size_t total = 0;
  for (int t = 0; t < 6; t++) {
    printf("%-9s cases=%zu mismatching=%zu max_rel=%.3g\n", names[t], cases[t], bad[t], maxRel[t]);
    total += bad[t];
  }
  printf("skipped(outside Auralis input range)=%zu\n", skipped);
  return total ? 2 : 0;
}
