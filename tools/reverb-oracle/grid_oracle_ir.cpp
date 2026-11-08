// Capture Poweramp's native reverb impulse response at given UI parameters.
// usage: grid_oracle_ir out.f32 P0 P1 P2 P3 P4 P5 P6 [left|right]
#include <dlfcn.h>
#include <link.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>
#include <algorithm>
using Init = void (*)(void*, uint32_t);
using Set = void (*)(void*, uint32_t, float);
using Run = uint32_t (*)(void*, float*, uint32_t);
int main(int argc, char** argv) {
  if (argc < 9) return 9;
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
  void* c = calloc(1, 0x4c8);
  init(c, 48000);
  // Native update flags: Size (0) and Pre-delay (3) write flag 2, all others write 1, so the last
  // setter wins. SIZELAST=1 sets Size last so the structural (flag 2) update is the pending one.
  if (getenv("SIZELAST")) {
    for (unsigned i = 1; i < 7; i++) set(c, i, strtof(argv[2 + i], nullptr));
    set(c, 0, strtof(argv[2], nullptr));
  } else {
    for (unsigned i = 0; i < 7; i++) set(c, i, strtof(argv[2 + i], nullptr));
  }
  if (getenv("FLAG7")) set(c, 7, 1.0f);
  const bool right = argc > 9 && std::string(argv[9]) == "right";
  constexpr unsigned N = 1024, BLOCKS = 32; const unsigned WARM = getenv("WARM") ? atoi(getenv("WARM")) : 12;
  float x[2048];
  for (unsigned w = 0; w < WARM; w++) { std::fill(x, x + 2048, 0.f); run(c, x, N); }
  std::vector<float> out((size_t)N * BLOCKS * 2);
  for (unsigned k = 0; k < BLOCKS; k++) {
    std::fill(x, x + 2048, 0.f);
    if (k == 0) x[right ? 1 : 0] = 1.f;
    run(c, x, N);
    memcpy(out.data() + (size_t)k * 2048, x, sizeof(x));
  }
  FILE* f = fopen(argv[1], "wb");
  if (!f) return 6;
  fwrite(out.data(), 4, out.size(), f);
  fclose(f);
  return 0;
}
