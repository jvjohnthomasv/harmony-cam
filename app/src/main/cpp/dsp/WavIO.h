// Minimal streaming WAV reader/writer (32-bit float or 16-bit PCM), plus the automation file format.
#pragma once

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace hc {

class WavWriter {
public:
    ~WavWriter() { close(); }
    bool open(const std::string &path, int sampleRate, int channels) {
        close();
        f = std::fopen(path.c_str(), "wb");
        if (!f) return false;
        rate = sampleRate; ch = channels; frames = 0;
        writeHeader();
        return true;
    }
    void write(const float *interleaved, size_t nFrames) {
        if (!f) return;
        std::fwrite(interleaved, sizeof(float), nFrames * ch, f);
        frames += nFrames;
    }
    void close() {
        if (!f) return;
        std::fseek(f, 0, SEEK_SET);
        writeHeader();
        std::fclose(f);
        f = nullptr;
    }
    bool isOpen() const { return f != nullptr; }
    uint64_t framesWritten() const { return frames; }
private:
    void put32(uint32_t v) { std::fwrite(&v, 4, 1, f); }
    void put16(uint16_t v) { std::fwrite(&v, 2, 1, f); }
    void writeHeader() {
        uint32_t dataBytes = (uint32_t) std::min<uint64_t>(frames * ch * 4, 0xFFFFFFF0ull);
        std::fwrite("RIFF", 1, 4, f); put32(36 + dataBytes);
        std::fwrite("WAVE", 1, 4, f);
        std::fwrite("fmt ", 1, 4, f); put32(16);
        put16(3); // IEEE float
        put16((uint16_t) ch); put32((uint32_t) rate); put32((uint32_t) (rate * ch * 4));
        put16((uint16_t) (ch * 4)); put16(32);
        std::fwrite("data", 1, 4, f); put32(dataBytes);
    }
    FILE *f = nullptr;
    int rate = 48000, ch = 1;
    uint64_t frames = 0;
};

class WavReader {
public:
    ~WavReader() { if (f) std::fclose(f); }
    bool open(const std::string &path) {
        f = std::fopen(path.c_str(), "rb");
        if (!f) return false;
        char id[4]; uint32_t size;
        if (std::fread(id, 1, 4, f) != 4 || std::memcmp(id, "RIFF", 4)) return false;
        std::fread(&size, 4, 1, f);
        if (std::fread(id, 1, 4, f) != 4 || std::memcmp(id, "WAVE", 4)) return false;
        bool haveFmt = false;
        while (std::fread(id, 1, 4, f) == 4 && std::fread(&size, 4, 1, f) == 1) {
            if (!std::memcmp(id, "fmt ", 4)) {
                uint16_t fmt, chans, align, bits; uint32_t sr, br;
                std::fread(&fmt, 2, 1, f); std::fread(&chans, 2, 1, f); std::fread(&sr, 4, 1, f);
                std::fread(&br, 4, 1, f); std::fread(&align, 2, 1, f); std::fread(&bits, 2, 1, f);
                if (size > 16) std::fseek(f, size - 16, SEEK_CUR);
                format = fmt; channels = chans; rate = (int) sr; bitsPerSample = bits;
                haveFmt = true;
            } else if (!std::memcmp(id, "data", 4)) {
                dataBytes = size;
                bytesPerFrame = channels * bitsPerSample / 8;
                totalFrames = bytesPerFrame ? dataBytes / bytesPerFrame : 0;
                return haveFmt && (format == 3 || (format == 1 && bitsPerSample == 16));
            } else {
                std::fseek(f, size + (size & 1), SEEK_CUR);
            }
        }
        return false;
    }
    // Reads up to n frames, mixed down to mono float. Returns frames read.
    size_t readMono(float *out, size_t n) {
        if (!f) return 0;
        n = std::min<size_t>(n, totalFrames - pos);
        raw.resize(n * bytesPerFrame);
        size_t got = std::fread(raw.data(), bytesPerFrame, n, f);
        for (size_t i = 0; i < got; ++i) {
            float acc = 0;
            for (int c = 0; c < channels; ++c) {
                if (format == 3) { float v; std::memcpy(&v, &raw[(i * channels + c) * 4], 4); acc += v; }
                else { int16_t v; std::memcpy(&v, &raw[(i * channels + c) * 2], 2); acc += v / 32768.0f; }
            }
            out[i] = acc / channels;
        }
        pos += got;
        return got;
    }
    int sampleRate() const { return rate; }
    size_t frames() const { return totalFrames; }
private:
    FILE *f = nullptr;
    int format = 3, channels = 1, rate = 48000, bitsPerSample = 32, bytesPerFrame = 4;
    uint32_t dataBytes = 0;
    size_t totalFrames = 0, pos = 0;
    std::vector<uint8_t> raw;
};

// Automation file: header then one Controls frame (kNumControls floats) every `hopSamples`.
struct AutomationHeader {
    char magic[4] = {'H', 'C', 'A', '1'};
    int32_t sampleRate = 48000;
    int32_t hopSamples = 480;
    int32_t numControls = 12;
};

} // namespace hc
