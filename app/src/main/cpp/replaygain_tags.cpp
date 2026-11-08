#include "replaygain_tags.h"

#include <cmath>
#include <cstring>
#include <vector>

namespace auralis {
namespace {

struct Found {
    bool trackGain = false, trackPeak = false, albumGain = false, albumPeak = false;
    bool r128Track = false, r128Album = false;
    double trackGainDb = 0.0, trackPeakLin = 0.0, albumGainDb = 0.0, albumPeakLin = 0.0;
    double r128TrackQ78 = 0.0, r128AlbumQ78 = 0.0;
};

char upperAscii(char c) { return (c >= 'a' && c <= 'z') ? static_cast<char>(c - 'a' + 'A') : c; }

void addComment(Found& f, const std::string& rawKey, const std::string& value) {
    std::string key;
    key.reserve(rawKey.size());
    for (char c : rawKey) key.push_back(upperAscii(c));
    double v = 0.0;
    if (key == "REPLAYGAIN_TRACK_GAIN") {
        if (parseReplayGainNumber(value, v)) { f.trackGain = true; f.trackGainDb = v; }
    } else if (key == "REPLAYGAIN_TRACK_PEAK") {
        if (parseReplayGainNumber(value, v)) { f.trackPeak = true; f.trackPeakLin = v; }
    } else if (key == "REPLAYGAIN_ALBUM_GAIN") {
        if (parseReplayGainNumber(value, v)) { f.albumGain = true; f.albumGainDb = v; }
    } else if (key == "REPLAYGAIN_ALBUM_PEAK") {
        if (parseReplayGainNumber(value, v)) { f.albumPeak = true; f.albumPeakLin = v; }
    } else if (key == "R128_TRACK_GAIN") {
        if (parseReplayGainNumber(value, v)) { f.r128Track = true; f.r128TrackQ78 = v; }
    } else if (key == "R128_ALBUM_GAIN") {
        if (parseReplayGainNumber(value, v)) { f.r128Album = true; f.r128AlbumQ78 = v; }
    }
}

std::uint32_t be32(const std::uint8_t* p) {
    return (static_cast<std::uint32_t>(p[0]) << 24) | (static_cast<std::uint32_t>(p[1]) << 16) |
           (static_cast<std::uint32_t>(p[2]) << 8) | static_cast<std::uint32_t>(p[3]);
}
std::uint32_t le32(const std::uint8_t* p) {
    return (static_cast<std::uint32_t>(p[3]) << 24) | (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[1]) << 8) | static_cast<std::uint32_t>(p[0]);
}
std::uint32_t syncsafe32(const std::uint8_t* p) {
    return (static_cast<std::uint32_t>(p[0] & 0x7f) << 21) | (static_cast<std::uint32_t>(p[1] & 0x7f) << 14) |
           (static_cast<std::uint32_t>(p[2] & 0x7f) << 7) | static_cast<std::uint32_t>(p[3] & 0x7f);
}

// ---------- Vorbis comment block (FLAC, Ogg Vorbis, Opus) ----------
void parseVorbisComments(const std::uint8_t* p, std::size_t size, Found& f) {
    if (size < 8) return;
    std::size_t pos = 0;
    const std::uint32_t vendorLen = le32(p);
    pos += 4;
    if (vendorLen > size - pos) return;
    pos += vendorLen;
    if (size - pos < 4) return;
    const std::uint32_t count = le32(p + pos);
    pos += 4;
    for (std::uint32_t i = 0; i < count; ++i) {
        if (size - pos < 4) return;
        const std::uint32_t len = le32(p + pos);
        pos += 4;
        if (len > size - pos) return;
        const std::string entry(reinterpret_cast<const char*>(p + pos), len);
        pos += len;
        const std::size_t eq = entry.find('=');
        if (eq == std::string::npos) continue;
        addComment(f, entry.substr(0, eq), entry.substr(eq + 1));
    }
}

void parseFlac(const std::uint8_t* p, std::size_t size, Found& f) {
    std::size_t pos = 4;  // after "fLaC"
    while (size - pos >= 4) {
        const bool last = (p[pos] & 0x80) != 0;
        const unsigned type = p[pos] & 0x7f;
        const std::size_t len = (static_cast<std::size_t>(p[pos + 1]) << 16) |
                                (static_cast<std::size_t>(p[pos + 2]) << 8) | p[pos + 3];
        pos += 4;
        if (len > size - pos) return;
        if (type == 4) parseVorbisComments(p + pos, len, f);
        pos += len;
        if (last) return;
    }
}

// ---------- Ogg (Vorbis and Opus) ----------
void parseOgg(const std::uint8_t* p, std::size_t size, Found& f) {
    // Join the payloads of the leading pages so a comment packet that spans pages is contiguous.
    std::vector<std::uint8_t> joined;
    std::size_t pos = 0;
    int pages = 0;
    while (size - pos >= 27 && std::memcmp(p + pos, "OggS", 4) == 0 && pages < 64 && joined.size() < (1u << 20)) {
        const std::size_t segs = p[pos + 26];
        if (size - pos < 27 + segs) break;
        std::size_t payload = 0;
        for (std::size_t i = 0; i < segs; ++i) payload += p[pos + 27 + i];
        const std::size_t start = pos + 27 + segs;
        if (payload > size - start) payload = size - start;
        joined.insert(joined.end(), p + start, p + start + payload);
        pos = start + payload;
        ++pages;
    }
    const std::uint8_t* j = joined.data();
    const std::size_t n = joined.size();
    for (std::size_t i = 0; i + 8 <= n; ++i) {
        if (std::memcmp(j + i, "OpusTags", 8) == 0) {
            parseVorbisComments(j + i + 8, n - i - 8, f);
            return;
        }
        if (i + 7 <= n && j[i] == 0x03 && std::memcmp(j + i + 1, "vorbis", 6) == 0) {
            parseVorbisComments(j + i + 7, n - i - 7, f);
            return;
        }
    }
}

// ---------- ID3v2 ----------
// Decodes a text field to ASCII-compatible bytes; non-ASCII UTF-16 code units become '?'.
const std::uint8_t* readId3String(unsigned encoding, const std::uint8_t* p, const std::uint8_t* end, std::string& out) {
    out.clear();
    if (encoding == 1 || encoding == 2) {
        bool bigEndian = (encoding == 2);
        if (encoding == 1 && end - p >= 2) {
            if (p[0] == 0xFE && p[1] == 0xFF) { bigEndian = true; p += 2; }
            else if (p[0] == 0xFF && p[1] == 0xFE) { bigEndian = false; p += 2; }
        }
        while (end - p >= 2) {
            const std::uint8_t lo = bigEndian ? p[1] : p[0];
            const std::uint8_t hi = bigEndian ? p[0] : p[1];
            p += 2;
            if (lo == 0 && hi == 0) break;
            out.push_back(hi == 0 ? static_cast<char>(lo) : '?');
        }
        return p;
    }
    while (p < end && *p != 0) out.push_back(static_cast<char>(*p++));
    if (p < end) ++p;  // terminator
    return p;
}

void parseId3Frame(const std::uint8_t* body, std::size_t len, Found& f) {
    if (len < 2) return;
    const unsigned encoding = body[0];
    const std::uint8_t* p = body + 1;
    const std::uint8_t* end = body + len;
    std::string description, value;
    p = readId3String(encoding, p, end, description);
    readId3String(encoding, p, end, value);
    addComment(f, description, value);
}

// Returns the byte offset just past the tag, or 0 if there is no valid ID3v2 header.
std::size_t parseId3(const std::uint8_t* p, std::size_t size, Found& f) {
    if (size < 10 || std::memcmp(p, "ID3", 3) != 0) return 0;
    const unsigned major = p[3];
    const unsigned flags = p[5];
    if (major < 2 || major > 4 || (p[6] | p[7] | p[8] | p[9]) & 0x80) return 0;
    const std::size_t tagSize = syncsafe32(p + 6);
    const std::size_t total = 10 + tagSize;
    std::size_t avail = total > size ? size : total;
    std::vector<std::uint8_t> body(p + 10, p + avail);
    if ((flags & 0x80) != 0) {  // tag-level unsynchronisation: FF 00 -> FF
        std::vector<std::uint8_t> clean;
        clean.reserve(body.size());
        for (std::size_t i = 0; i < body.size(); ++i) {
            clean.push_back(body[i]);
            if (body[i] == 0xFF && i + 1 < body.size() && body[i + 1] == 0x00) ++i;
        }
        body.swap(clean);
    }
    std::size_t pos = 0;
    if ((flags & 0x40) != 0 && major >= 3 && body.size() >= 4) {  // extended header
        const std::size_t ext = (major == 4) ? syncsafe32(body.data()) : static_cast<std::size_t>(be32(body.data())) + 4;
        if (ext > body.size()) return total;
        pos = ext;
    }
    const std::size_t idLen = (major == 2) ? 3 : 4;
    const std::size_t headLen = (major == 2) ? 6 : 10;
    while (body.size() - pos >= headLen) {
        const std::uint8_t* h = body.data() + pos;
        if (h[0] == 0) break;  // padding
        std::size_t frameSize;
        if (major == 2) frameSize = (static_cast<std::size_t>(h[3]) << 16) | (static_cast<std::size_t>(h[4]) << 8) | h[5];
        else if (major == 3) frameSize = be32(h + 4);
        else frameSize = syncsafe32(h + 4);
        pos += headLen;
        if (frameSize > body.size() - pos) break;
        const std::uint8_t* data = body.data() + pos;
        std::size_t dataLen = frameSize;
        bool usable = true;
        if (major >= 3) {
            const unsigned f1 = h[8], f2 = h[9];
            const bool compressed = major == 3 ? (f1 & 0x80) != 0 : (f2 & 0x08) != 0;
            const bool encrypted = major == 3 ? (f1 & 0x40) != 0 : (f2 & 0x04) != 0;
            if (compressed || encrypted) usable = false;
            if (major == 4 && (f2 & 0x01) != 0) {  // data length indicator
                if (dataLen < 4) usable = false; else { data += 4; dataLen -= 4; }
            }
            if (major == 4 && (f2 & 0x02) != 0 && usable) {  // per-frame unsynchronisation
                std::vector<std::uint8_t> clean;
                for (std::size_t i = 0; i < dataLen; ++i) {
                    clean.push_back(data[i]);
                    if (data[i] == 0xFF && i + 1 < dataLen && data[i + 1] == 0x00) ++i;
                }
                if (usable && std::memcmp(h, "TXXX", idLen) == 0) parseId3Frame(clean.data(), clean.size(), f);
                pos += frameSize;
                continue;
            }
            if (major == 3 && (f1 & 0x20) != 0) usable = false;  // grouping byte: unsupported
        }
        if (usable && std::memcmp(h, idLen == 3 ? "TXX" : "TXXX", idLen) == 0) parseId3Frame(data, dataLen, f);
        pos += frameSize;
    }
    return total;
}

// ---------- MP4 / M4A ----------
std::string boxText(const std::uint8_t* p, std::size_t len, std::size_t skip) {
    if (len < skip) return std::string();
    return std::string(reinterpret_cast<const char*>(p + skip), len - skip);
}

void walkMp4(const std::uint8_t* p, std::size_t size, int depth, Found& f) {
    if (depth > 8) return;
    std::size_t pos = 0;
    while (size - pos >= 8) {
        std::uint64_t boxSize = be32(p + pos);
        const std::uint8_t* type = p + pos + 4;
        std::size_t header = 8;
        if (boxSize == 1) {
            if (size - pos < 16) return;
            boxSize = (static_cast<std::uint64_t>(be32(p + pos + 8)) << 32) | be32(p + pos + 12);
            header = 16;
        } else if (boxSize == 0) {
            boxSize = size - pos;
        }
        if (boxSize < header || boxSize > size - pos) return;
        const std::uint8_t* body = p + pos + header;
        const std::size_t bodyLen = static_cast<std::size_t>(boxSize) - header;
        if (std::memcmp(type, "moov", 4) == 0 || std::memcmp(type, "udta", 4) == 0 || std::memcmp(type, "ilst", 4) == 0) {
            walkMp4(body, bodyLen, depth + 1, f);
        } else if (std::memcmp(type, "meta", 4) == 0) {
            if (bodyLen >= 4) walkMp4(body + 4, bodyLen - 4, depth + 1, f);
        } else if (std::memcmp(type, "----", 4) == 0) {
            std::string name, value;
            std::size_t q = 0;
            while (bodyLen - q >= 8) {
                const std::size_t childSize = be32(body + q);
                if (childSize < 8 || childSize > bodyLen - q) break;
                const std::uint8_t* ct = body + q + 4;
                const std::uint8_t* cb = body + q + 8;
                const std::size_t cl = childSize - 8;
                if (std::memcmp(ct, "name", 4) == 0) name = boxText(cb, cl, 4);
                else if (std::memcmp(ct, "data", 4) == 0) value = boxText(cb, cl, 8);
                q += childSize;
            }
            if (!name.empty()) addComment(f, name, value);
        }
        pos += static_cast<std::size_t>(boxSize);
    }
}

}  // namespace

bool parseReplayGainNumber(const std::string& text, double& value) noexcept {
    try {
        std::size_t i = 0;
        const std::size_t n = text.size();
        while (i < n && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r' || text[i] == '\n')) ++i;
        bool negative = false;
        if (i < n && (text[i] == '+' || text[i] == '-')) { negative = text[i] == '-'; ++i; }
        double result = 0.0;
        bool digits = false;
        while (i < n && text[i] >= '0' && text[i] <= '9') { result = result * 10.0 + (text[i] - '0'); digits = true; ++i; }
        if (i < n && (text[i] == '.' || text[i] == ',')) {
            ++i;
            double scale = 0.1;
            while (i < n && text[i] >= '0' && text[i] <= '9') { result += (text[i] - '0') * scale; scale *= 0.1; digits = true; ++i; }
        }
        if (!digits) return false;
        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
            std::size_t j = i + 1;
            bool expNeg = false;
            if (j < n && (text[j] == '+' || text[j] == '-')) { expNeg = text[j] == '-'; ++j; }
            int e = 0;
            bool expDigits = false;
            while (j < n && text[j] >= '0' && text[j] <= '9' && e < 1000) { e = e * 10 + (text[j] - '0'); expDigits = true; ++j; }
            if (expDigits) result *= std::pow(10.0, expNeg ? -e : e);
        }
        if (!std::isfinite(result)) return false;
        value = negative ? -result : result;
        return true;
    } catch (...) {
        return false;
    }
}

bool parseReplayGainTags(const std::uint8_t* data, std::size_t size, ReplayGainMetadata& out) noexcept {
    if (data == nullptr || size < 8) return false;
    try {
        Found f;
        std::size_t pos = 0;
        if (std::memcmp(data, "ID3", 3) == 0) {
            pos = parseId3(data, size, f);
            if (pos > size) pos = size;
        }
        if (size - pos >= 4 && std::memcmp(data + pos, "fLaC", 4) == 0) parseFlac(data + pos, size - pos, f);
        else if (size - pos >= 4 && std::memcmp(data + pos, "OggS", 4) == 0) parseOgg(data + pos, size - pos, f);
        else if (size - pos >= 12 && (std::memcmp(data + pos + 4, "ftyp", 4) == 0 || std::memcmp(data + pos + 4, "moov", 4) == 0))
            walkMp4(data + pos, size - pos, 0, f);

        ReplayGainMetadata m;
        if (f.albumGain) { m.albumAvailable = true; m.albumGainDb = f.albumGainDb; m.albumPeakLinear = f.albumPeak ? f.albumPeakLin : 0.0; }
        else if (f.r128Album) { m.albumAvailable = true; m.albumGainDb = f.r128AlbumQ78 / 256.0 + 5.0; }
        if (f.trackGain) { m.trackAvailable = true; m.trackGainDb = f.trackGainDb; m.trackPeakLinear = f.trackPeak ? f.trackPeakLin : 0.0; }
        else if (f.r128Track) { m.trackAvailable = true; m.trackGainDb = f.r128TrackQ78 / 256.0 + 5.0; }
        if (!m.albumAvailable && !m.trackAvailable) return false;
        out = m;
        return true;
    } catch (...) {
        return false;
    }
}

}  // namespace auralis
