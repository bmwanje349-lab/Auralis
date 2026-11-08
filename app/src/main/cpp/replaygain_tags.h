#pragma once

#include "audio_engine.h"

#include <cstddef>
#include <cstdint>
#include <string>

namespace auralis {

// Reads ReplayGain tags from the leading bytes of an audio file, or from an MP4 'moov' box.
// Supported: ID3v2.2/2.3/2.4 (MP3, also in front of FLAC), FLAC and Ogg Vorbis/Opus comments,
// and MP4/M4A iTunes freeform atoms. Opus R128_*_GAIN tags are converted to the ReplayGain
// reference (+5 dB). Returns true when at least one gain value was found; a missing peak is 0.
bool parseReplayGainTags(const std::uint8_t* data, std::size_t size, ReplayGainMetadata& out) noexcept;

// Parses values such as "-6.50 dB", "+3.2", "0.987654" or "-6,5 dB" without using the C locale.
bool parseReplayGainNumber(const std::string& text, double& value) noexcept;

}  // namespace auralis
