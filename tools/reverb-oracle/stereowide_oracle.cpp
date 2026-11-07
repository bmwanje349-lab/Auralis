// Bit-exact comparison of Auralis StereoWide against Poweramp's native DSPI
// callbacks: Float32 0x21CBC0 and Float64 0x21EBA0. The native parameter handler
// (0x21FFB8) clamps p to [0,1] (NaN -> 0), stores g = fma(p,2,1) at +0xC0 and sets
// flag 0x04 at +0x6C only when p != 0; this harness reproduces exactly that.
// build: c++ -std=c++17 -O2 -I app/src/main/cpp tools/reverb-oracle/stereowide_oracle.cpp \
//        app/src/main/cpp/audio_engine.cpp -ldl -o build/stereowide_oracle
#include <dlfcn.h>
#include <link.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <limits>
#include <map>
#include <random>
#include <vector>
#include "audio_engine.h"
template <class T> using Cb = void (*)(void*, T*, uint32_t, void*, void*, uint32_t);

template <class T>
static void run(Cb<T> cb, double pct, std::mt19937& rng, size_t& cases, size_t& bad,
                size_t& bitdiff, double& maxabs, std::map<double, int>& perSlider) {
  // Native handler arithmetic (Float32).
  const float f = static_cast<float>(pct) / 100.0f;
  const float p = std::isnan(f) ? 0.0f : (f > 1.0f ? 1.0f : (f < 0.0f ? 0.0f : f));
  const float g = std::fma(p, 2.0f, 1.0f);
  const uint32_t flags = (p != 0.0f) ? 0x04u : 0u;
  auralis::StereoWide sw;
  sw.configure(true, pct);
  std::uniform_real_distribution<float> u(-1.f, 1.f);
  const uint32_t frameCounts[] = {1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 33, 100, 1024, 4099};
  for (uint32_t frames : frameCounts) {
    for (int rep = 0; rep < 3; rep++) {
      std::vector<T> a(frames * 2);
      for (auto& v : a) {
        float s = u(rng);
        v = static_cast<T>(rep == 1 ? s * 1e-3f : (rep == 2 ? s * 30.f : s));
      }
      std::vector<T> b = a;
      alignas(16) unsigned char ctx[0x400] = {};
      memcpy(ctx + 0x6c, &flags, 4);
      memcpy(ctx + 0xc0, &g, 4);
      cb(ctx, b.data(), frames, nullptr, nullptr, 0);
      sw.process(a.data(), frames);
      cases++;
      bool diff = false;
      for (size_t i = 0; i < a.size(); i++) {
        if (memcmp(&a[i], &b[i], sizeof(T)) != 0) {
          diff = true; bitdiff++;
          maxabs = std::fmax(maxabs, std::fabs((double)a[i] - (double)b[i]));
        }
      }
      if (diff) { bad++; perSlider[pct]++; }
    }
  }
}

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
  const double sliders[] = {0, 1e-7, 1e-5, 10, 25, 33.3, 50, 75, 99.9, 100, 150, -20,
                            std::numeric_limits<double>::quiet_NaN()};
  for (int bits : {32, 64}) {
    std::mt19937 rng(12345);
    size_t cases = 0, bad = 0, bitdiff = 0;
    double maxabs = 0;
    std::map<double, int> perSlider;
    for (double pct : sliders) {
      if (bits == 32)
        run<float>((Cb<float>)(base + 0x21cbc0), pct, rng, cases, bad, bitdiff, maxabs, perSlider);
      else
        run<double>((Cb<double>)(base + 0x21eba0), pct, rng, cases, bad, bitdiff, maxabs, perSlider);
    }
    printf("Float%d: cases=%zu mismatching_cases=%zu mismatching_samples=%zu max_abs_diff=%.3g\n",
           bits, cases, bad, bitdiff, maxabs);
    for (auto& kv : perSlider) printf("  slider %g: %d mismatching cases\n", kv.first, kv.second);
    if (bad) return 2;
  }
  return 0;
}
