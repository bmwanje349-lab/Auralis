// Probe Poweramp's reverb context: feed an impulse, then report every heap buffer the
// context points to (first/last nonzero float index, peak) so stage structure can be read
// from the delay-line contents. usage: reverb_probe P0..P6 [left|right]  (UI values 0..1)
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
static std::vector<Range> readRanges() {
  std::vector<Range> v;
  FILE* f = fopen("/proc/self/maps", "r");
  char line[512];
  while (f && fgets(line, sizeof line, f)) {
    unsigned long lo, hi; char perms[8];
    if (sscanf(line, "%lx-%lx %7s", &lo, &hi, perms) == 3 && perms[0] == 'r' && perms[1] == 'w') v.push_back({lo, hi});
  }
  if (f) fclose(f);
  return v;
}
static const Range* find(const std::vector<Range>& r, uintptr_t a) {
  for (auto& x : r) if (a >= x.lo && a < x.hi) return &x;
  return nullptr;
}
int main(int argc, char** argv) {
  if (argc < 8) return 9;
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
  auto b = (uintptr_t)di.dli_fbase;
  auto init = (Init)(b + 0x16bce0);
  auto set = (Set)(b + 0x16bc00);
  auto run = (Run)(b + 0x167d20);
  unsigned char* c = (unsigned char*)calloc(1, 0x4c8);
  init(c, 48000);
  for (unsigned i = 0; i < 7; i++) set(c, i, strtof(argv[1 + i], nullptr));
  const bool right = argc > 8 && std::string(argv[8]) == "right";
  float x[2048];
  for (unsigned w = 0; w < 12; w++) { std::fill(x, x + 2048, 0.f); run(c, x, 1024); }
  std::fill(x, x + 2048, 0.f);
  x[right ? 1 : 0] = 1.f;
  run(c, x, 1024);
  auto ranges = readRanges();
  const uintptr_t cbase = (uintptr_t)c;
  for (unsigned off = 0; off + 8 <= 0x4c8; off += 8) {
    uintptr_t v; memcpy(&v, c + off, 8);
    v &= 0x00ffffffffffffffULL;  // strip the pointer tag in the top byte
    if (v < 0x10000 || (v & 3)) continue;
    const Range* rg = find(ranges, v);
    if (!rg) continue;
    if (v >= cbase && v < cbase + 0x4c8) continue;
    const size_t maxFloats = std::min<size_t>((rg->hi - v) / 4, 16384);
    const float* p = (const float*)v;
    long first = -1, last = -1; double peak = 0; size_t nz = 0;
    for (size_t i = 0; i < maxFloats; i++) {
      float f = p[i];
      if (f != 0.f && std::isfinite(f)) { if (first < 0) first = i; last = i; nz++; peak = std::fmax(peak, std::fabs(f)); }
    }
    printf("ctx+0x%03x -> %p  nonzero=%zu first=%ld last=%ld peak=%.6g\n", off, (void*)v, nz, first, last, peak);
  }
  return 0;
}
