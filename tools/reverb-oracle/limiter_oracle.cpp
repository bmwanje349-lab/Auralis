// Compare Auralis Limiter against Poweramp's native DSPI limiter branches.
// The native context is loaded with limiter state (+0xEC applied gain, +0xF0 target envelope),
// ceiling (+0xF4) and the increments Auralis claims (+0xF8 scalar release, +0xFC scalar attack,
// +0x100 vector release, +0x104 vector attack); flag 0x02 selects the hard-ceiling path and
// 0x100 the configurable path. Persistent state is compared after every block.
// build: c++ -std=c++17 -O2 -I app/src/main/cpp tools/reverb-oracle/limiter_oracle.cpp \
//        app/src/main/cpp/audio_engine.cpp -ldl -o build/limiter_oracle
#include <dlfcn.h>
#include <link.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <random>
#include <vector>
#include "audio_engine.h"
template <class T> using Cb = void (*)(void*, T*, uint32_t, void*, void*, uint32_t);

struct Totals { size_t blocks = 0, badBlocks = 0, badSamples = 0, badState = 0; double maxAbs = 0; int firstBad = -1; };

template <class T>
static void scenario(Cb<T> cb, bool configurable, float ceiling, int seed, Totals& tot, const char* label) {
  using auralis::Limiter;
  using auralis::LimiterPath;
  alignas(16) unsigned char ctx[0x400] = {};
  const float one = 1.0f;
  const float relS = static_cast<float>(Limiter::kReleaseStep), atkS = static_cast<float>(Limiter::kAttackStep);
  const float relV = static_cast<float>(Limiter::kVectorReleaseStep), atkV = static_cast<float>(Limiter::kVectorAttackStep);
  const uint32_t flags = configurable ? 0x100u : 0x02u;
  memcpy(ctx + 0x6c, &flags, 4);
  memcpy(ctx + 0xec, &one, 4);
  memcpy(ctx + 0xf0, &one, 4);
  memcpy(ctx + 0xf4, &ceiling, 4);
  memcpy(ctx + 0xf8, &relS, 4);
  memcpy(ctx + 0xfc, &atkS, 4);
  memcpy(ctx + 0x100, &relV, 4);
  memcpy(ctx + 0x104, &atkV, 4);

  Limiter lim;
  lim.configure(true, configurable ? LimiterPath::ConfigurableThreshold : LimiterPath::HardCeiling,
                configurable ? static_cast<double>(ceiling) : Limiter::kUnboundedThreshold);

  std::mt19937 rng(seed);
  std::uniform_real_distribution<float> u(-1.f, 1.f);
  const uint32_t sizes[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 13, 16, 31, 64, 100, 257, 1024};
  // Timeline: quiet, loud burst, quiet recovery, moderate overs, one-sided overs, quiet.
  const struct { uint32_t frames; float amp; int mode; } seg[] = {
      {3000, 0.2f, 0}, {500, 3.0f, 0}, {20000, 0.2f, 0}, {400, 1.4f, 0},
      {5000, 0.5f, 0}, {300, 8.0f, 1}, {40000, 0.1f, 0}};
  int blockIndex = 0;
  for (const auto& s : seg) {
    uint32_t done = 0;
    while (done < s.frames) {
      uint32_t n = std::min<uint32_t>(sizes[rng() % (sizeof(sizes) / sizeof(sizes[0]))], s.frames - done);
      std::vector<T> a(n * 2);
      for (uint32_t i = 0; i < n; i++) {
        float l = u(rng) * s.amp, r = u(rng) * s.amp;
        if (s.mode == 1) r *= 0.1f;  // loud on left only
        a[2 * i] = static_cast<T>(l);
        a[2 * i + 1] = static_cast<T>(r);
      }
      std::vector<T> b = a;
      cb(ctx, b.data(), n, nullptr, nullptr, 0);
      // Auralis processes float data; widen through float for the double test.
      std::vector<float> af(a.begin(), a.end());
      lim.process(af.data(), n);
      tot.blocks++;
      bool bad = false;
      for (size_t i = 0; i < af.size(); i++) {
        T mine = static_cast<T>(af[i]);
        if (memcmp(&mine, &b[i], sizeof(T)) != 0) {
          bad = true; tot.badSamples++;
          tot.maxAbs = std::fmax(tot.maxAbs, std::fabs((double)mine - (double)b[i]));
        }
      }
      float nativeApplied, nativeTarget;
      memcpy(&nativeApplied, ctx + 0xec, 4);
      memcpy(&nativeTarget, ctx + 0xf0, 4);
      if (static_cast<float>(lim.gain()) != nativeApplied || static_cast<float>(lim.targetGain()) != nativeTarget) {
        tot.badState++; bad = true;
      }
      if (bad) { tot.badBlocks++; if (tot.firstBad < 0) tot.firstBad = blockIndex; }
      blockIndex++;
      done += n;
    }
  }
  printf("%s: blocks=%zu bad_blocks=%zu bad_samples=%zu bad_state=%zu max_abs=%.3g first_bad_block=%d\n",
         label, tot.blocks, tot.badBlocks, tot.badSamples, tot.badState, tot.maxAbs, tot.firstBad);
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
  size_t bad = 0;
  struct Case { bool cfg; float ceil; const char* name; };
  const Case cases[] = {{false, 1.0f, "hard ceiling 1.0"}, {true, 0.5f, "configurable 0.5"},
                        {true, 0.8f, "configurable 0.8"}, {true, 2.0f, "configurable 2.0"},
                        {true, 999.999f, "configurable unbounded"}};
  for (const auto& c : cases) {
    Totals t32;
    char lab[96];
    snprintf(lab, sizeof lab, "Float32 %s", c.name);
    scenario<float>((Cb<float>)(base + 0x21cbc0), c.cfg, c.ceil, 4242, t32, lab);
    bad += t32.badBlocks;
    Totals t64;
    snprintf(lab, sizeof lab, "Float64 %s", c.name);
    scenario<double>((Cb<double>)(base + 0x21eba0), c.cfg, c.ceil, 4242, t64, lab);
    bad += t64.badBlocks;
  }
  return bad ? 2 : 0;
}
