// Small DSP building blocks shared by the realtime engine and the offline renderer.
#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <algorithm>

namespace hc {

constexpr float kPi = 3.14159265358979f;

inline float dbToGain(float db) { return std::pow(10.0f, db / 20.0f); }
inline float gainToDb(float g) { return 20.0f * std::log10(std::max(g, 1e-9f)); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Time-constant smoothing coefficient for a one-pole filter updated every `stepSamples`.
inline float smoothCoef(float timeSec, float sampleRate, float stepSamples = 1.0f) {
    if (timeSec <= 0.0f) return 1.0f;
    return 1.0f - std::exp(-stepSamples / (timeSec * sampleRate));
}

// RBJ biquad, transposed direct form II.
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1 = 0, z2 = 0;

    void reset() { z1 = z2 = 0; }

    void setHighpass(float freq, float sr, float q = 0.7071f) {
        float w = 2 * kPi * freq / sr, c = std::cos(w), s = std::sin(w), alpha = s / (2 * q);
        float a0 = 1 + alpha;
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0;
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0;
    }
    void setLowpass(float freq, float sr, float q = 0.7071f) {
        float w = 2 * kPi * freq / sr, c = std::cos(w), s = std::sin(w), alpha = s / (2 * q);
        float a0 = 1 + alpha;
        b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = (1 - c) / 2 / a0;
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0;
    }
    void setPeak(float freq, float sr, float gainDb, float q) {
        float A = std::pow(10.0f, gainDb / 40.0f);
        float w = 2 * kPi * freq / sr, c = std::cos(w), s = std::sin(w), alpha = s / (2 * q);
        float a0 = 1 + alpha / A;
        b0 = (1 + alpha * A) / a0; b1 = -2 * c / a0; b2 = (1 - alpha * A) / a0;
        a1 = -2 * c / a0; a2 = (1 - alpha / A) / a0;
    }
    void setHighShelf(float freq, float sr, float gainDb) {
        float A = std::pow(10.0f, gainDb / 40.0f);
        float w = 2 * kPi * freq / sr, c = std::cos(w), s = std::sin(w);
        float alpha = s / 2 * std::sqrt(2.0f);
        float sq = 2 * std::sqrt(A) * alpha;
        float a0 = (A + 1) - (A - 1) * c + sq;
        b0 = A * ((A + 1) + (A - 1) * c + sq) / a0;
        b1 = -2 * A * ((A - 1) + (A + 1) * c) / a0;
        b2 = A * ((A + 1) + (A - 1) * c - sq) / a0;
        a1 = 2 * ((A - 1) - (A + 1) * c) / a0;
        a2 = ((A + 1) - (A - 1) * c - sq) / a0;
    }
    inline float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

// Fractional-free delay line (integer delays, power-of-two buffer).
struct DelayLine {
    std::vector<float> buf;
    uint32_t mask = 0, pos = 0;
    void init(int maxSamples) {
        uint32_t n = 1;
        while (n < (uint32_t) maxSamples + 1) n <<= 1;
        buf.assign(n, 0.0f);
        mask = n - 1;
        pos = 0;
    }
    void reset() { std::fill(buf.begin(), buf.end(), 0.0f); pos = 0; }
    inline void write(float x) { buf[pos] = x; pos = (pos + 1) & mask; }
    // delay >= 1: sample written `delay` writes ago
    inline float read(int delay) const { return buf[(pos - (uint32_t) delay) & mask]; }
    // fractional delay (>= 1), linear interpolation
    inline float readFrac(float delay) const {
        int di = (int) delay;
        float f = delay - di;
        float a = read(di), b = read(di + 1);
        return a + (b - a) * f;
    }
};

// Single-producer / single-consumer lock-free ring buffer (safe for audio thread → worker thread).
template<typename T>
class SpscRing {
public:
    explicit SpscRing(size_t capacityPow2 = 1 << 16) { resize(capacityPow2); }
    void resize(size_t cap) {
        size_t n = 1;
        while (n < cap) n <<= 1;
        data.assign(n, T{});
        mask = n - 1;
        head.store(0); tail.store(0);
    }
    size_t write(const T *src, size_t count) {
        size_t h = head.load(std::memory_order_relaxed);
        size_t t = tail.load(std::memory_order_acquire);
        size_t freeSpace = data.size() - (h - t);
        size_t n = std::min(count, freeSpace);
        for (size_t i = 0; i < n; ++i) data[(h + i) & mask] = src[i];
        head.store(h + n, std::memory_order_release);
        return n;
    }
    size_t read(T *dst, size_t count) {
        size_t t = tail.load(std::memory_order_relaxed);
        size_t h = head.load(std::memory_order_acquire);
        size_t n = std::min(count, h - t);
        for (size_t i = 0; i < n; ++i) dst[i] = data[(t + i) & mask];
        tail.store(t + n, std::memory_order_release);
        return n;
    }
    size_t available() const { return head.load(std::memory_order_acquire) - tail.load(std::memory_order_acquire); }
    void clear() { tail.store(head.load()); }
private:
    std::vector<T> data;
    size_t mask = 0;
    std::atomic<size_t> head{0}, tail{0};
};

// Feed-forward compressor with soft knee, used to polish the lead vocal in the final mix.
struct Compressor {
    float thresholdDb = -20, ratio = 3, kneeDb = 6, makeupDb = 4;
    float attackCoef = 0, releaseCoef = 0, envDb = -120;
    void setup(float sr, float attackMs = 6, float releaseMs = 140) {
        attackCoef = std::exp(-1.0f / (attackMs * 0.001f * sr));
        releaseCoef = std::exp(-1.0f / (releaseMs * 0.001f * sr));
        envDb = -120;
    }
    inline float process(float x) {
        float levelDb = gainToDb(std::fabs(x));
        float coef = levelDb > envDb ? attackCoef : releaseCoef;
        envDb = coef * envDb + (1 - coef) * levelDb;
        float over = envDb - thresholdDb;
        float grDb;
        if (over <= -kneeDb / 2) grDb = 0;
        else if (over >= kneeDb / 2) grDb = over * (1 / ratio - 1);
        else { float t = over + kneeDb / 2; grDb = (1 / ratio - 1) * t * t / (2 * kneeDb); }
        return x * dbToGain(grDb + makeupDb);
    }
};

// Smooth stereo peak limiter (instant attack, smooth release) followed by a gentle soft clip.
struct Limiter {
    float ceiling = 0.89f, gain = 1.0f, releaseCoef = 0.0f;
    void setup(float sr, float ceilingDb = -1.0f, float releaseMs = 80) {
        ceiling = dbToGain(ceilingDb);
        releaseCoef = 1.0f - std::exp(-1.0f / (releaseMs * 0.001f * sr));
        gain = 1.0f;
    }
    inline void process(float &l, float &r) {
        float peak = std::max(std::fabs(l), std::fabs(r));
        float target = peak * gain > ceiling ? ceiling / std::max(peak, 1e-9f) : 1.0f;
        if (target < gain) gain = target; else gain += (target - gain) * releaseCoef;
        l = softClip(l * gain);
        r = softClip(r * gain);
    }
    static inline float softClip(float x) {
        // transparent below ~0.9, smooth saturation above
        float ax = std::fabs(x);
        if (ax < 0.9f) return x;
        float s = x > 0 ? 1.0f : -1.0f;
        float over = ax - 0.9f;
        return s * (0.9f + 0.1f * std::tanh(over / 0.1f));
    }
};

} // namespace hc
