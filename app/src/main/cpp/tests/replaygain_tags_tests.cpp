#include "replaygain_tags.h"

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <random>
#include <string>
#include <vector>

using namespace auralis;
using Bytes = std::vector<std::uint8_t>;

namespace {
int failures = 0;
int passed = 0;
void check(bool ok, const std::string& label) {
    if (!ok) { std::cerr << "FAIL: " << label << '\n'; ++failures; }
    else { std::cout << "PASS: " << label << '\n'; ++passed; }
}
bool near(double a, double b, double eps = 1e-9) { return std::abs(a - b) <= eps; }

void put(Bytes& b, const std::string& s) { b.insert(b.end(), s.begin(), s.end()); }
void put(Bytes& b, const Bytes& s) { b.insert(b.end(), s.begin(), s.end()); }
void be32(Bytes& b, std::uint32_t v) { for (int s = 24; s >= 0; s -= 8) b.push_back(static_cast<std::uint8_t>(v >> s)); }
void le32(Bytes& b, std::uint32_t v) { for (int s = 0; s < 32; s += 8) b.push_back(static_cast<std::uint8_t>(v >> s)); }
void syncsafe(Bytes& b, std::uint32_t v) {
    b.push_back(static_cast<std::uint8_t>((v >> 21) & 0x7f)); b.push_back(static_cast<std::uint8_t>((v >> 14) & 0x7f));
    b.push_back(static_cast<std::uint8_t>((v >> 7) & 0x7f)); b.push_back(static_cast<std::uint8_t>(v & 0x7f));
}

// ---- ID3v2 builders ----
Bytes text(unsigned encoding, const std::string& s, bool terminate) {
    Bytes out;
    if (encoding == 1) { out.push_back(0xFF); out.push_back(0xFE); }
    for (char c : s) {
        out.push_back(static_cast<std::uint8_t>(c));
        if (encoding == 1) out.push_back(0);
        if (encoding == 2) { out.insert(out.end() - 1, 0); }
    }
    if (terminate) { out.push_back(0); if (encoding == 1 || encoding == 2) out.push_back(0); }
    return out;
}
Bytes txxxBody(unsigned encoding, const std::string& desc, const std::string& value) {
    Bytes b;
    b.push_back(static_cast<std::uint8_t>(encoding));
    put(b, text(encoding, desc, true));
    // UTF-16 values: a BOM only precedes the first string of the frame in some taggers; keep one per string.
    put(b, text(encoding, value, false));
    return b;
}
Bytes frame(unsigned major, const std::string& id, const Bytes& body) {
    Bytes f;
    put(f, id);
    if (major == 2) { f.push_back(static_cast<std::uint8_t>(body.size() >> 16)); f.push_back(static_cast<std::uint8_t>(body.size() >> 8)); f.push_back(static_cast<std::uint8_t>(body.size())); }
    else if (major == 3) { be32(f, static_cast<std::uint32_t>(body.size())); f.push_back(0); f.push_back(0); }
    else { syncsafe(f, static_cast<std::uint32_t>(body.size())); f.push_back(0); f.push_back(0); }
    put(f, body);
    return f;
}
Bytes id3(unsigned major, const Bytes& frames, std::size_t padding = 0) {
    Bytes t;
    put(t, "ID3");
    t.push_back(static_cast<std::uint8_t>(major)); t.push_back(0); t.push_back(0);
    syncsafe(t, static_cast<std::uint32_t>(frames.size() + padding));
    put(t, frames);
    t.insert(t.end(), padding, 0);
    return t;
}
Bytes mp3Audio() { Bytes a; for (int i = 0; i < 64; ++i) { a.push_back(0xFF); a.push_back(0xFB); a.push_back(0x90); a.push_back(0x00); } return a; }

// ---- Vorbis comment builders ----
Bytes vorbisComments(const std::vector<std::string>& entries) {
    Bytes b;
    const std::string vendor = "test";
    le32(b, static_cast<std::uint32_t>(vendor.size())); put(b, vendor);
    le32(b, static_cast<std::uint32_t>(entries.size()));
    for (const auto& e : entries) { le32(b, static_cast<std::uint32_t>(e.size())); put(b, e); }
    return b;
}
Bytes flacFile(const std::vector<std::string>& entries) {
    Bytes f;
    put(f, "fLaC");
    f.push_back(0x00); f.push_back(0); f.push_back(0); f.push_back(34);  // STREAMINFO, not last
    f.insert(f.end(), 34, 0);
    const Bytes vc = vorbisComments(entries);
    f.push_back(0x84);  // VORBIS_COMMENT, last
    f.push_back(static_cast<std::uint8_t>(vc.size() >> 16)); f.push_back(static_cast<std::uint8_t>(vc.size() >> 8)); f.push_back(static_cast<std::uint8_t>(vc.size()));
    put(f, vc);
    return f;
}
// Splits a payload across pages when asked, with correct lacing values.
Bytes oggPage(const Bytes& payload, std::uint8_t headerType, std::uint32_t seq, bool endsMidPacket) {
    Bytes p;
    put(p, "OggS"); p.push_back(0); p.push_back(headerType);
    for (int i = 0; i < 8; ++i) p.push_back(0);
    le32(p, 1); le32(p, seq); le32(p, 0);
    Bytes segs;
    std::size_t left = payload.size();
    while (left >= 255) { segs.push_back(255); left -= 255; }
    if (!endsMidPacket || left > 0) segs.push_back(static_cast<std::uint8_t>(left));
    p.push_back(static_cast<std::uint8_t>(segs.size()));
    put(p, segs); put(p, payload);
    return p;
}
Bytes oggVorbis(const std::vector<std::string>& entries, bool splitComments) {
    Bytes f;
    Bytes id; id.push_back(0x01); put(id, "vorbis"); id.insert(id.end(), 23, 0);
    put(f, oggPage(id, 0x02, 0, false));
    Bytes c; c.push_back(0x03); put(c, "vorbis"); put(c, vorbisComments(entries)); c.push_back(1);
    if (splitComments && c.size() > 255) {
        // A packet that continues on the next page must end its first page on a full 255-byte segment.
        Bytes first(c.begin(), c.begin() + 255), second(c.begin() + 255, c.end());
        put(f, oggPage(first, 0x00, 1, true));
        put(f, oggPage(second, 0x01, 2, false));
    } else {
        put(f, oggPage(c, 0x00, 1, false));
    }
    return f;
}
Bytes oggOpus(const std::vector<std::string>& entries) {
    Bytes f;
    Bytes head; put(head, "OpusHead"); head.push_back(1); head.push_back(2); head.insert(head.end(), 17, 0);
    put(f, oggPage(head, 0x02, 0, false));
    Bytes tags; put(tags, "OpusTags"); put(tags, vorbisComments(entries));
    put(f, oggPage(tags, 0x00, 1, false));
    return f;
}

// ---- MP4 builders ----
Bytes box(const std::string& type, const Bytes& body) {
    Bytes b; be32(b, static_cast<std::uint32_t>(body.size() + 8)); put(b, type); put(b, body); return b;
}
Bytes freeform(const std::string& name, const std::string& value) {
    Bytes mean; be32(mean, 0); put(mean, "com.apple.iTunes");
    Bytes nm; be32(nm, 0); put(nm, name);
    Bytes data; be32(data, 1); be32(data, 0); put(data, value);
    Bytes body; put(body, box("mean", mean)); put(body, box("name", nm)); put(body, box("data", data));
    return box("----", body);
}
Bytes mp4Moov(const std::vector<std::pair<std::string, std::string>>& items) {
    Bytes ilst;
    for (const auto& it : items) put(ilst, freeform(it.first, it.second));
    Bytes metaBody; be32(metaBody, 0); put(metaBody, box("ilst", ilst));
    return box("moov", box("udta", box("meta", metaBody)));
}
Bytes mp4File(const std::vector<std::pair<std::string, std::string>>& items) {
    Bytes ftyp; put(ftyp, "M4A "); be32(ftyp, 0); put(ftyp, "M4A mp42isom");
    Bytes f = box("ftyp", ftyp);
    put(f, box("mdat", Bytes(100, 0x55)));
    put(f, mp4Moov(items));
    return f;
}

const std::vector<std::string> kStd = {
    "TITLE=Song", "REPLAYGAIN_TRACK_GAIN=-6.50 dB", "REPLAYGAIN_TRACK_PEAK=0.987654",
    "REPLAYGAIN_ALBUM_GAIN=-7.10 dB", "REPLAYGAIN_ALBUM_PEAK=1.000000"};

bool expectStd(const Bytes& file, const std::string& label) {
    ReplayGainMetadata m;
    const bool ok = parseReplayGainTags(file.data(), file.size(), m);
    const bool good = ok && m.trackAvailable && m.albumAvailable && near(m.trackGainDb, -6.5) &&
                      near(m.trackPeakLinear, 0.987654) && near(m.albumGainDb, -7.1) && near(m.albumPeakLinear, 1.0);
    check(good, label);
    return good;
}
}  // namespace

int main() {
    // ---- numbers ----
    double v = 0;
    check(parseReplayGainNumber("-6.50 dB", v) && near(v, -6.5), "number: '-6.50 dB'");
    check(parseReplayGainNumber("+3.2", v) && near(v, 3.2), "number: '+3.2'");
    check(parseReplayGainNumber("  0.987654", v) && near(v, 0.987654), "number: leading spaces");
    check(parseReplayGainNumber("-6,5 dB", v) && near(v, -6.5), "number: decimal comma");
    check(parseReplayGainNumber("1e-1", v) && near(v, 0.1), "number: exponent");
    check(!parseReplayGainNumber("abc", v) && !parseReplayGainNumber("", v) && !parseReplayGainNumber("-", v) &&
          !parseReplayGainNumber("dB", v), "number: rejects non-numbers");

    // ---- ID3v2 ----
    auto txxxFrames = [&](unsigned major, unsigned enc) {
        Bytes fr;
        const std::string tid = major == 2 ? "TXX" : "TXXX";
        put(fr, frame(major, major == 2 ? "TT2" : "TIT2", Bytes{0, 'S', 'o', 'n', 'g'}));
        put(fr, frame(major, tid, txxxBody(enc, "REPLAYGAIN_TRACK_GAIN", "-6.50 dB")));
        put(fr, frame(major, tid, txxxBody(enc, "replaygain_track_peak", "0.987654")));
        put(fr, frame(major, tid, txxxBody(enc, "REPLAYGAIN_ALBUM_GAIN", "-7.10 dB")));
        put(fr, frame(major, tid, txxxBody(enc, "REPLAYGAIN_ALBUM_PEAK", "1.000000")));
        return fr;
    };
    for (unsigned major : {2u, 3u, 4u}) {
        Bytes mp3 = id3(major, txxxFrames(major, 0), 64);
        put(mp3, mp3Audio());
        expectStd(mp3, "ID3v2." + std::to_string(major) + " ISO-8859-1 TXXX (MP3), lower-case key accepted");
    }
    {
        Bytes mp3 = id3(3, txxxFrames(3, 1), 16); put(mp3, mp3Audio());
        expectStd(mp3, "ID3v2.3 UTF-16 with BOM TXXX");
        Bytes mp3b = id3(4, txxxFrames(4, 3)); put(mp3b, mp3Audio());
        expectStd(mp3b, "ID3v2.4 UTF-8 TXXX");
        Bytes mp3c = id3(4, txxxFrames(4, 2)); put(mp3c, mp3Audio());
        expectStd(mp3c, "ID3v2.4 UTF-16BE TXXX");
    }
    {
        Bytes fr; put(fr, frame(4, "TXXX", txxxBody(0, "REPLAYGAIN_TRACK_GAIN", "+2.00 dB")));
        Bytes mp3 = id3(4, fr); put(mp3, mp3Audio());
        ReplayGainMetadata m;
        check(parseReplayGainTags(mp3.data(), mp3.size(), m) && m.trackAvailable && !m.albumAvailable &&
              near(m.trackGainDb, 2.0) && near(m.trackPeakLinear, 0.0), "ID3: track gain only; missing peak is 0, album unavailable");
    }
    {
        Bytes fr; put(fr, frame(3, "TIT2", Bytes{0, 'x'}));
        Bytes mp3 = id3(3, fr); put(mp3, mp3Audio());
        ReplayGainMetadata m;
        check(!parseReplayGainTags(mp3.data(), mp3.size(), m), "ID3 without ReplayGain frames returns false");
    }
    // ---- FLAC ----
    expectStd(flacFile(kStd), "FLAC Vorbis comments");
    {
        Bytes titleOnly = frame(3, "TIT2", Bytes{0, 'x'});
        Bytes f = id3(3, titleOnly); put(f, flacFile({"TITLE=x"}));
        ReplayGainMetadata m;
        check(!parseReplayGainTags(f.data(), f.size(), m), "FLAC behind an ID3 tag without ReplayGain returns false");
        Bytes g = id3(3, Bytes{}, 8); put(g, flacFile(kStd));
        expectStd(g, "FLAC behind an empty ID3 tag");
    }
    // ---- Ogg ----
    expectStd(oggVorbis(kStd, false), "Ogg Vorbis comments");
    {
        std::vector<std::string> longEntries = kStd;
        longEntries.insert(longEntries.begin(), "COMMENT=" + std::string(400, 'x'));
        expectStd(oggVorbis(longEntries, true), "Ogg Vorbis comment packet split across two pages");
    }
    {
        Bytes f = oggOpus({"R128_TRACK_GAIN=-768", "R128_ALBUM_GAIN=-512"});
        ReplayGainMetadata m;
        check(parseReplayGainTags(f.data(), f.size(), m) && m.trackAvailable && m.albumAvailable &&
              near(m.trackGainDb, 2.0) && near(m.albumGainDb, 3.0) && near(m.trackPeakLinear, 0.0),
              "Opus R128 tags convert to ReplayGain reference (+5 dB), no peak");
        Bytes g = oggOpus({"REPLAYGAIN_TRACK_GAIN=-4.0 dB", "R128_TRACK_GAIN=-768"});
        check(parseReplayGainTags(g.data(), g.size(), m) && near(m.trackGainDb, -4.0), "Opus: explicit ReplayGain tag wins over R128");
    }
    // ---- MP4 ----
    expectStd(mp4File({{"replaygain_track_gain", "-6.50 dB"}, {"replaygain_track_peak", "0.987654"},
                       {"replaygain_album_gain", "-7.10 dB"}, {"replaygain_album_peak", "1.000000"}}),
              "MP4 iTunes freeform atoms (moov after mdat)");
    {
        const Bytes moov = mp4Moov({{"replaygain_track_gain", "-3.25 dB"}});
        ReplayGainMetadata m;
        check(parseReplayGainTags(moov.data(), moov.size(), m) && m.trackAvailable && near(m.trackGainDb, -3.25),
              "bare moov box (as the app passes it) is parsed");
    }
    // ---- robustness ----
    {
        ReplayGainMetadata m;
        check(!parseReplayGainTags(nullptr, 0, m) && !parseReplayGainTags(reinterpret_cast<const std::uint8_t*>("abc"), 3, m),
              "empty and tiny input return false");
        std::mt19937 rng(2026);
        const std::vector<Bytes> seeds = {id3(3, txxxFrames(3, 0)), id3(4, txxxFrames(4, 1)), flacFile(kStd),
                                          oggVorbis(kStd, true), oggOpus({"R128_TRACK_GAIN=-1"}),
                                          mp4File({{"replaygain_track_gain", "-1 dB"}})};
        bool survived = true;
        for (const Bytes& seed : seeds) {
            for (std::size_t cut = 0; cut <= seed.size(); ++cut) {  // every truncation
                parseReplayGainTags(seed.data(), cut, m);
            }
            for (int i = 0; i < 400; ++i) {  // random corruption
                Bytes c = seed;
                const int flips = 1 + static_cast<int>(rng() % 6);
                for (int k = 0; k < flips; ++k) c[rng() % c.size()] = static_cast<std::uint8_t>(rng());
                parseReplayGainTags(c.data(), c.size(), m);
            }
        }
        for (int i = 0; i < 3000; ++i) {  // pure noise with valid magic prefixes
            Bytes c(8 + rng() % 400);
            for (auto& b : c) b = static_cast<std::uint8_t>(rng());
            switch (i % 4) {
                case 0: c[0] = 'I'; c[1] = 'D'; c[2] = '3'; c[3] = 3; break;
                case 1: c[0] = 'f'; c[1] = 'L'; c[2] = 'a'; c[3] = 'C'; break;
                case 2: c[0] = 'O'; c[1] = 'g'; c[2] = 'g'; c[3] = 'S'; break;
                default: c[4] = 'f'; c[5] = 't'; c[6] = 'y'; c[7] = 'p'; break;
            }
            parseReplayGainTags(c.data(), c.size(), m);
        }
        check(survived, "truncated, corrupted and random inputs are handled without crashing");
    }
    if (failures != 0) { std::cerr << failures << " failed; " << passed << " passed\n"; return EXIT_FAILURE; }
    std::cout << "All " << passed << " ReplayGain tag checks passed.\n";
    return EXIT_SUCCESS;
}
