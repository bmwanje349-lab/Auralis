// Compare Auralis Mono + Balance against Poweramp's native DSPI code.
// Drives the real parameter handler (0x21FC10, plugin type 3) and then the real
// Float32 callback (0x21CBC0), so flag selection, clamping and gain math are native.
// build: c++ -std=c++17 -O2 -I app/src/main/cpp tools/reverb-oracle/balance_mono_oracle.cpp \
//        app/src/main/cpp/audio_engine.cpp -ldl -o build/balance_mono_oracle
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
using Handler = int (*)(void*, void*);
using Cb = void (*)(void*, float*, uint32_t, void*, void*, uint32_t);

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
  auto handler = (Handler)(base + 0x21fc10);
  auto cb = (Cb)(base + 0x21cbc0);

  const double inf = std::numeric_limits<double>::infinity();
  const double nan = std::numeric_limits<double>::quiet_NaN();
  const double values[] = {0, 1e-9, 0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.999, 1.0,
                           -1e-9, -0.01, -0.1, -0.25, -0.5, -0.75, -0.9, -0.999, -1.0,
                           1.5, -1.5, inf, -inf, nan};
  std::mt19937 rng(777);
  std::uniform_real_distribution<float> u(-1.f, 1.f);
  const uint32_t frameCounts[] = {1, 2, 3, 4, 7, 8, 9, 16, 17, 33, 1024};
  size_t cases = 0, bad = 0, rcBad = 0;
  std::map<std::pair<double, int>, int> badAt;
  double maxrel = 0;
  for (int mono = 0; mono <= 1; mono++) {
    for (double b : values) {
      alignas(16) unsigned char ctx[0x400] = {};
      alignas(16) unsigned char param[0x80] = {};
      int type = 3, size = 0x10;
      memcpy(param + 0x14, &type, 4);
      memcpy(param + 0x1c, &size, 4);
      param[0x20] = (unsigned char)mono;
      float bf = static_cast<float>(b);
      memcpy(param + 0x28, &bf, 4);
      int rc = handler(ctx, param);
      if (rc != 0) rcBad++;
      auralis::Mono monoStage;
      monoStage.configure(mono != 0);
      auralis::Balance bal;
      bal.configure(true, b);
      for (uint32_t frames : frameCounts) {
        std::vector<float> a(frames * 2);
        for (auto& v : a) v = u(rng);
        std::vector<float> n = a;
        cb(ctx, n.data(), frames, nullptr, nullptr, 0);
        monoStage.process(a.data(), frames);
        bal.process(a.data(), frames);
        cases++;
        bool diff = false;
        for (size_t i = 0; i < a.size(); i++) {
          if (memcmp(&a[i], &n[i], 4) != 0) {
            diff = true;
            if (n[i] != 0.f) maxrel = std::fmax(maxrel, std::fabs((double)a[i] - n[i]) / std::fabs((double)n[i]));
          }
        }
        if (diff) { bad++; badAt[{b, mono}]++; }
      }
    }
  }
  printf("cases=%zu mismatching_cases=%zu handler_nonzero_returns=%zu max_rel_diff=%.3g\n",
         cases, bad, rcBad, maxrel);
  for (auto& kv : badAt) printf("  balance %g mono %d: %d cases differ\n", kv.first.first, kv.first.second, kv.second);
  return bad ? 2 : 0;
}
