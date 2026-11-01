// Scales, scale-degree stepping and automatic key detection.
#pragma once

#include "DspUtil.h"
#include <array>

namespace hc {

enum ScaleType { kMajor = 0, kMinor = 1, kChromatic = 2 };

inline const std::array<int, 7> &scaleIntervals(int scale) {
    static const std::array<int, 7> major{0, 2, 4, 5, 7, 9, 11};
    static const std::array<int, 7> minor{0, 2, 3, 5, 7, 8, 10}; // natural minor
    return scale == kMinor ? minor : major;
}

inline int pitchClass(int midi) { return ((midi % 12) + 12) % 12; }

inline bool inScale(int midi, int root, int scale) {
    if (scale == kChromatic || root < 0) return true;
    int pc = pitchClass(midi - root);
    for (int iv : scaleIntervals(scale)) if (iv == pc) return true;
    return false;
}

// Nearest note that belongs to the scale. Ties resolve towards `bias` (+1 up / -1 down).
inline int snapToScale(float midi, int root, int scale) {
    int n = (int) std::lround(midi);
    if (inScale(n, root, scale)) return n;
    int up = n + 1, down = n - 1;
    while (!inScale(up, root, scale)) ++up;
    while (!inScale(down, root, scale)) --down;
    return (up - midi) < (midi - down) ? up : down;
}

// Move `steps` scale degrees from `note` (assumed in scale). For chromatic, fall back to
// the major-key interval sizes so that "a third" still means something musical.
inline int stepScale(int note, int steps, int root, int scale) {
    if (scale == kChromatic || root < 0) {
        static const int majorDegreeSemis[15] = {0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24};
        int a = std::min(std::abs(steps), 14);
        return note + (steps >= 0 ? majorDegreeSemis[a] : -majorDegreeSemis[a]);
    }
    int n = note, dir = steps >= 0 ? 1 : -1, remaining = std::abs(steps);
    while (remaining > 0) {
        n += dir;
        if (inScale(n, root, scale)) --remaining;
    }
    return n;
}

// Krumhansl–Schmuckler key finder over a decaying pitch-class histogram.
class KeyDetector {
public:
    void reset() { hist.fill(0); total = 0; root = -1; scale = kMajor; }
    // weight: seconds of voiced audio represented by this observation
    void observe(float midi, float weight) {
        int pc = pitchClass((int) std::lround(midi));
        for (auto &h : hist) h *= decay;
        total *= decay;
        hist[pc] += weight;
        total += weight;
        if (++sinceUpdate >= 50) { sinceUpdate = 0; update(); }
    }
    int getRoot() const { return root; }
    int getScale() const { return scale; }
    bool confident() const { return root >= 0; }
private:
    void update() {
        if (total < 3.0f) return; // need ~3 s of singing before trusting the key
        static const float maj[12] = {6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f};
        static const float mnr[12] = {6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f};
        float best = -2; int bestRoot = root, bestScale = scale;
        for (int s = 0; s < 2; ++s) {
            const float *prof = s == 0 ? maj : mnr;
            for (int r = 0; r < 12; ++r) {
                float c = corr(prof, r);
                // small preference for the current key avoids flapping between relatives
                if (r == root && s == scale) c += 0.03f;
                if (c > best) { best = c; bestRoot = r; bestScale = s; }
            }
        }
        root = bestRoot; scale = bestScale;
    }
    float corr(const float *prof, int r) const {
        float mx = 0, my = 0;
        for (int i = 0; i < 12; ++i) { mx += hist[(i + r) % 12]; my += prof[i]; }
        mx /= 12; my /= 12;
        float sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < 12; ++i) {
            float x = hist[(i + r) % 12] - mx, y = prof[i] - my;
            sxy += x * y; sxx += x * x; syy += y * y;
        }
        return sxx > 0 ? sxy / std::sqrt(sxx * syy) : -1;
    }
    std::array<float, 12> hist{};
    float total = 0, decay = 0.9997f; // ~30 s memory at ~100 observations/s
    int root = -1, scale = kMajor, sinceUpdate = 0;
};

} // namespace hc
