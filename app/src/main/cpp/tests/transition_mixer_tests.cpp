#include "../transition_mixer.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {
int failures = 0;
void check(bool ok, const char* message) {
    if (!ok) { std::cerr << "FAIL: " << message << '\n'; ++failures; }
}
bool near(float a, float b, float epsilon = 1e-5f) { return std::abs(a - b) <= epsilon; }
}

int main() {
    using auralis::PowerampTransitionMixer;
    constexpr int frames = 1024;
    std::vector<float> outgoing(frames * 2, 1.0f);
    std::vector<float> incoming(frames * 2, 1.0f);
    std::vector<float> mixed(frames * 2, 0.0f);

    PowerampTransitionMixer mixer(48'000);
    check(mixer.startCrossfade(5'000, frames), "five-second Poweramp crossfade starts");
    check(mixer.fadingFrames() == 240'000, "fade frames are sample-rate times milliseconds / 1000");
    check(near(mixer.fadingDeltaPerBlock(), 1024.0f / 240'000.0f),
          "fade delta is DSP block frames divided by fade frames");
    mixer.processCrossfade(outgoing.data(), incoming.data(), mixed.data(), frames);
    check(near(mixed[0], 1.0f), "first fade block starts with outgoing context only");
    check(near(mixer.outgoingGain() + mixer.incomingGain(), 1.0f),
          "linear two-context gain deltas remain complementary");
    check(!mixer.crossfadeComplete(), "crossfade remains active after its first block");

    for (int i = 0; i < 300 && !mixer.crossfadeComplete(); ++i)
        mixer.processCrossfade(outgoing.data(), incoming.data(), mixed.data(), frames);
    check(mixer.crossfadeComplete(), "crossfade reaches its completed state");
    check(near(mixer.outgoingGain(), 0.0f) && near(mixer.incomingGain(), 1.0f),
          "completed crossfade leaves only incoming context audible");

    check(mixer.startSingleFade(1.0f, 0.0f, 400, frames), "400 ms single fade starts");
    const float before = mixer.singleGain();
    mixer.processSingle(outgoing.data(), mixed.data(), frames);
    check(mixer.singleGain() < before, "single fade advances by its per-block delta");
    for (int i = 0; i < 50 && !mixer.singleFadeComplete(); ++i)
        mixer.processSingle(outgoing.data(), mixed.data(), frames);
    check(mixer.singleFadeComplete() && near(mixer.singleGain(), 0.0f),
          "single fade reaches its requested endpoint");

    mixer.reset();
    mixer.processCrossfade(outgoing.data(), incoming.data(), mixed.data(), frames);
    check(near(mixed[0], 1.0f), "reset disables crossfade and passes the active stream");

    if (failures != 0) {
        std::cerr << failures << " transition mixer test(s) failed\n";
        return EXIT_FAILURE;
    }
    std::cout << "All transition mixer tests passed\n";
    return EXIT_SUCCESS;
}
