// Karaoke backing track: loads a decoded WAV, resamples it to the engine rate with a
// high-quality windowed-sinc resampler, estimates the song's key, and plays it back.
#pragma once

#include "DspUtil.h"
#include "MusicTheory.h"
#include "WavIO.h"

#include <cstdio>
#include <string>
#include <vector>

namespace hc {

// Krumhansl–Schmuckler key estimate from a 12-bin chroma vector.
inline void estimateKey(const float chroma[12], int &root, int &scale) {
    static const float maj[12] = {6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f};
    static const float mnr[12] = {6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f};
    float best = -2; root = 0; scale = kMajor;
    for (int s = 0; s < 2; ++s) {
        const float *prof = s == 0 ? maj : mnr;
        for (int r = 0; r < 12; ++r) {
            float mx = 0, my = 0;
            for (int i = 0; i < 12; ++i) { mx += chroma[(i + r) % 12]; my += prof[i]; }
            mx /= 12; my /= 12;
            float sxy = 0, sxx = 0, syy = 0;
            for (int i = 0; i < 12; ++i) {
                float x = chroma[(i + r) % 12] - mx, y = prof[i] - my;
                sxy += x * y; sxx += x * x; syy += y * y;
            }
            float c = sxx > 0 ? sxy / std::sqrt(sxx * syy) : -1;
            if (c > best) { best = c; root = r; scale = s; }
        }
    }
}

class BackingTrack {
public:
    // Loads any 16-bit/float WAV (mono or stereo) and converts to stereo at `targetRate`.
    bool load(const std::string &path, int targetRate) {
        WavReaderStereo r;
        if (!r.open(path)) return false;
        std::vector<float> L, R;
        if (!r.readAll(L, R)) return false;
        rate = targetRate;
        if (r.sampleRate() != targetRate) {
            resample(L, r.sampleRate(), targetRate);
            resample(R, r.sampleRate(), targetRate);
        }
        frames = L.size();
        data.resize(frames * 2);
        for (size_t i = 0; i < frames; ++i) {
            data[2 * i] = toI16(L[i]);
            data[2 * i + 1] = toI16(R[i]);
        }
        analyseKey(L, R);
        return frames > 0;
    }

    size_t numFrames() const { return frames; }
    int sampleRate() const { return rate; }
    int keyRoot() const { return key; }
    int keyScale() const { return keyMode; }

    // Adds track samples [pos, pos+n) * gain to L/R. Returns frames actually available.
    inline void mixInto(size_t pos, float *outL, float *outR, int n, float gain) const {
        const float s = gain / 32768.0f;
        for (int i = 0; i < n; ++i) {
            size_t p = pos + i;
            if (p >= frames) break;
            outL[i] += data[2 * p] * s;
            outR[i] += data[2 * p + 1] * s;
        }
    }
    inline void sample(long pos, float &l, float &r) const {
        if (pos < 0 || (size_t) pos >= frames) { l = r = 0; return; }
        l = data[2 * pos] / 32768.0f; r = data[2 * pos + 1] / 32768.0f;
    }

private:
    static int16_t toI16(float v) {
        v = clampf(v, -1.0f, 1.0f);
        return (int16_t) std::lround(v * 32767.0f);
    }

    // Polyphase windowed-sinc resampler (32 taps, 256 phases, linear phase interpolation).
    static void resample(std::vector<float> &x, int inRate, int outRate) {
        const int taps = 32, half = taps / 2, phases = 256;
        const double ratio = (double) inRate / outRate;
        const double cutoff = 0.94 * std::min(1.0, 1.0 / ratio); // relative to input Nyquist
        std::vector<float> table((phases + 1) * taps);
        for (int ph = 0; ph <= phases; ++ph) {
            double frac = (double) ph / phases;
            double sum = 0;
            for (int k = 0; k < taps; ++k) {
                double t = (k - half + 1) - frac;
                double sinc = std::fabs(t) < 1e-9 ? 1.0 : std::sin(kPi * cutoff * t) / (kPi * cutoff * t);
                double w = 0.42 + 0.5 * std::cos(kPi * t / half) + 0.08 * std::cos(2 * kPi * t / half); // Blackman
                if (std::fabs(t) > half) w = 0;
                table[ph * taps + k] = (float) (sinc * w);
                sum += sinc * w;
            }
            for (int k = 0; k < taps; ++k) table[ph * taps + k] /= (float) sum;
        }
        size_t outLen = (size_t) ((double) x.size() / ratio);
        std::vector<float> y(outLen);
        for (size_t i = 0; i < outLen; ++i) {
            double pos = i * ratio;
            long base = (long) pos;
            double fp = (pos - base) * phases;
            int ph = (int) fp;
            float a = (float) (fp - ph);
            const float *c0 = &table[ph * taps];
            const float *c1 = &table[(ph + 1) * taps];
            float acc = 0;
            for (int k = 0; k < taps; ++k) {
                long idx = base + k - half + 1;
                if (idx < 0 || idx >= (long) x.size()) continue;
                acc += x[idx] * (c0[k] + a * (c1[k] - c0[k]));
            }
            y[i] = acc;
        }
        x.swap(y);
    }

    // Chroma via Goertzel over MIDI 40..88, one 8192-sample frame per second of audio.
    void analyseKey(const std::vector<float> &L, const std::vector<float> &R) {
        float chroma[12] = {0};
        const int N = 8192;
        const size_t hop = (size_t) rate;
        for (size_t start = 0; start + N < L.size(); start += hop) {
            float frame[12] = {0};
            float energy = 0;
            for (int m = 40; m <= 88; ++m) {
                double f = 440.0 * std::pow(2.0, (m - 69) / 12.0);
                double w = 2 * kPi * f / rate, coeff = 2 * std::cos(w);
                double s1 = 0, s2 = 0;
                for (int i = 0; i < N; ++i) {
                    double win = 0.5 - 0.5 * std::cos(2 * kPi * i / (N - 1));
                    double x = 0.5 * (L[start + i] + R[start + i]) * win;
                    double s0 = x + coeff * s1 - s2;
                    s2 = s1; s1 = s0;
                }
                double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
                float v = (float) std::log1p(power * 100.0);
                frame[m % 12] += v;
                energy += v;
            }
            if (energy <= 0) continue;
            for (int k = 0; k < 12; ++k) chroma[k] += frame[k] / energy;
        }
        estimateKey(chroma, key, keyMode);
    }

    // Stereo-capable WAV reader (16-bit PCM or 32-bit float).
    class WavReaderStereo {
    public:
        ~WavReaderStereo() { if (f) std::fclose(f); }
        bool open(const std::string &path) {
            f = std::fopen(path.c_str(), "rb");
            if (!f) return false;
            char id[4]; uint32_t size;
            if (std::fread(id, 1, 4, f) != 4 || std::memcmp(id, "RIFF", 4)) return false;
            if (std::fread(&size, 4, 1, f) != 1) return false;
            if (std::fread(id, 1, 4, f) != 4 || std::memcmp(id, "WAVE", 4)) return false;
            while (std::fread(id, 1, 4, f) == 4 && std::fread(&size, 4, 1, f) == 1) {
                if (!std::memcmp(id, "fmt ", 4)) {
                    uint8_t buf[64] = {0};
                    size_t n = std::min<size_t>(size, sizeof(buf));
                    if (std::fread(buf, 1, n, f) != n) return false;
                    if (size > n) std::fseek(f, (long) (size - n), SEEK_CUR);
                    std::memcpy(&format, buf, 2); std::memcpy(&channels, buf + 2, 2);
                    std::memcpy(&srate, buf + 4, 4); std::memcpy(&bits, buf + 14, 2);
                    if (format == 0xFFFE && size >= 26) { uint16_t sub; std::memcpy(&sub, buf + 24, 2); format = sub; }
                } else if (!std::memcmp(id, "data", 4)) {
                    dataBytes = size;
                    return channels >= 1 && ((format == 1 && bits == 16) || (format == 3 && bits == 32));
                } else {
                    std::fseek(f, (long) (size + (size & 1)), SEEK_CUR);
                }
            }
            return false;
        }
        bool readAll(std::vector<float> &L, std::vector<float> &R) {
            const int bps = bits / 8;
            size_t frames = dataBytes / (bps * channels);
            L.resize(frames); R.resize(frames);
            std::vector<uint8_t> buf(65536 * bps * channels);
            size_t done = 0;
            while (done < frames) {
                size_t want = std::min<size_t>(65536, frames - done);
                size_t got = std::fread(buf.data(), bps * channels, want, f);
                if (got == 0) break;
                for (size_t i = 0; i < got; ++i) {
                    float v[2] = {0, 0};
                    for (int c = 0; c < std::min<int>(channels, 2); ++c) {
                        const uint8_t *p = &buf[(i * channels + c) * bps];
                        if (format == 3) std::memcpy(&v[c], p, 4);
                        else { int16_t s; std::memcpy(&s, p, 2); v[c] = s / 32768.0f; }
                    }
                    L[done + i] = v[0];
                    R[done + i] = channels > 1 ? v[1] : v[0];
                }
                done += got;
            }
            L.resize(done); R.resize(done);
            return done > 0;
        }
        int sampleRate() const { return (int) srate; }
    private:
        FILE *f = nullptr;
        uint16_t format = 1, channels = 2, bits = 16;
        uint32_t srate = 44100, dataBytes = 0;
    };

    std::vector<int16_t> data;
    size_t frames = 0;
    int rate = 48000, key = 0, keyMode = kMajor;
};

} // namespace hc
