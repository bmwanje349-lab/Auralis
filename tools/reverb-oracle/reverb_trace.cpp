// Frame-by-frame write trace of Poweramp's reverb buffers after a unit impulse.
// usage: reverb_trace FRAMES P0..P6 [left|right]; prints, for each frame, the buffer slots whose
// value changed (ctx offset of the buffer pointer, float index, old -> new).
#include <dlfcn.h>
#include <link.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>
#include <algorithm>
using Init = void (*)(void*, uint32_t);
using Set = void (*)(void*, uint32_t, float);
using Run = uint32_t (*)(void*, float*, uint32_t);
struct Range { uintptr_t lo, hi; };
struct Buf { unsigned off; const float* p; size_t n; std::vector<float> prev; };
int main(int argc, char** argv) {
  if (argc < 9) return 9;
  const unsigned FRAMES = atoi(argv[1]);
  const char* r = "/data/data/com.termux/files/home/tmp/auralis_poweramp_reference/";
  char path[512];
  snprintf(path, 512, "%slibffmpeg_neon.so", r);
  dlopen(path, RTLD_NOW | RTLD_GLOBAL);
  snprintf(path, 512, "%slibpowerampcore.so", r);
  void* h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!h) { puts(dlerror()); return 1; }
  Dl_info di{};
  dladdr(dlsym(h, "JNI_OnLoad"), &di);
  auto b = (uintptr_t)di.dli_fbase;
  auto init = (Init)(b + 0x16bce0);
  auto set = (Set)(b + 0x16bc00);
  auto run = (Run)(b + 0x167d20);
  unsigned char* c = (unsigned char*)calloc(1, 0x4c8);
  init(c, getenv("RATE") ? (uint32_t)atoi(getenv("RATE")) : 48000u);
  for (unsigned i = 0; i < 7; i++) set(c, i, strtof(argv[2 + i], nullptr));
  const bool right = argc > 9 && std::string(argv[9]) == "right";
  float x[2048];
  for (unsigned w = 0; w < 12; w++) { std::fill(x, x + 2048, 0.f); run(c, x, 1024); }
  // find buffers
  std::vector<Range> ranges;
  { FILE* f = fopen("/proc/self/maps", "r"); char line[512];
    while (f && fgets(line, sizeof line, f)) { unsigned long lo, hi; char pm[8];
      if (sscanf(line, "%lx-%lx %7s", &lo, &hi, pm) == 3 && pm[0] == 'r' && pm[1] == 'w') ranges.push_back({lo, hi}); }
    if (f) fclose(f); }
  std::vector<Buf> bufs;
  std::vector<uintptr_t> seen;
  for (unsigned off = 0; off + 8 <= 0x4c8; off += 8) {
    uintptr_t v; memcpy(&v, c + off, 8); v &= 0x00ffffffffffffffULL;
    if (v < 0x10000 || (v & 3)) continue;
    if (v >= (uintptr_t)c && v < (uintptr_t)c + 0x4c8) continue;
    if (std::find(seen.begin(), seen.end(), v) != seen.end()) continue;
    // ONLYOFF=<ctx offset> watches a single buffer (fast mode for long runs).
    if (getenv("ONLYOFF") && off != (unsigned)strtoul(getenv("ONLYOFF"), nullptr, 0)) continue;
    for (auto& rg : ranges) if (v >= rg.lo && v < rg.hi) {
      Buf bf; bf.off = off; bf.p = (const float*)v; bf.n = std::min<size_t>((rg.hi - v) / 4, 16384);
      bf.prev.assign(bf.p, bf.p + bf.n); bufs.push_back(std::move(bf)); seen.push_back(v); break; }
  }
  for (unsigned n = 0; n < FRAMES; n++) {
    float fr[2] = {0.f, 0.f};
    if (n == 0) fr[right ? 1 : 0] = 1.f;
    run(c, fr, 1);
    printf("frame %u out=(% .6g, % .6g)\n", n, fr[0], fr[1]);
    for (auto& bf : bufs) {
      for (size_t i = 0; i < bf.n; i++) {
        const float v = bf.p[i];
        if (memcmp(&v, &bf.prev[i], 4) != 0) {
          printf("   buf@0x%03x[%zu] %.7g -> %.7g\n", bf.off, i, bf.prev[i], v);
          bf.prev[i] = v;
        }
      }
    }
  }
  return 0;
}
