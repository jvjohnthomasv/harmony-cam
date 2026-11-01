// Time-domain PSOLA pitch shifting for monophonic voice.
//
// Grains of two pitch periods are cut from the input around analysis pitch marks and
// overlap-added at a new spacing. Because each grain is a real slice of the voice, the vowel
// (formants), breath and texture are preserved exactly — no phase-vocoder "phasiness" — and
// the latency is only ~2 pitch periods.
#pragma once

#include "DspUtil.h"

namespace hc {

// Shared input history + analysis pitch marks (one per input; all voices read from it).
class PitchMarker {
public:
    void setup(float sr, float maxPeriodSec = 0.0105f) {
        sampleRate = sr;
        maxPeriod = (int) (maxPeriodSec * sr);
        minPeriod = (int) (sr / 1100.0f);
        unvoicedPeriod = (int) (0.005f * sr);
        buf.assign(kBufSize, 0.0f);
        env.assign(kBufSize, 0.0f);
        lp.setLowpass(900, sr, 0.7f);
        reset();
    }
    void reset() {
        std::fill(buf.begin(), buf.end(), 0.0f);
        std::fill(env.begin(), env.end(), 0.0f);
        written = 0; markCount = 0; lastMark = 0; lastPeriod = unvoicedPeriod;
    }
    int maxPeriodSamples() const { return maxPeriod; }
    // Output latency of every PSOLA voice reading from this marker.
    int latency() const { return 2 * maxPeriod + 16; }

    // Append input and place new marks. period: current pitch period in samples (0 = unvoiced).
    void push(const float *x, int n, float period) {
        for (int i = 0; i < n; ++i) {
            float s = x[i];
            buf[(written + i) & kMask] = s;
            env[(written + i) & kMask] = lp.process(s);
        }
        written += n;
        int T = period > 0 ? (int) clampf(period, (float) minPeriod, (float) maxPeriod) : unvoicedPeriod;
        bool voiced = period > 0;
        // place marks while there is enough look-ahead to search around the predicted position
        while (true) {
            long predicted = lastMark + T;
            int radius = voiced ? T / 3 : 0;
            if (predicted + radius >= written) break;
            long best = predicted;
            if (voiced) {
                float bestVal = -1e9f;
                for (long p = predicted - radius; p <= predicted + radius; ++p) {
                    float v = env[p & kMask];
                    if (v > bestVal) { bestVal = v; best = p; }
                }
            }
            if (best <= lastMark) best = lastMark + std::max(1, T / 2);
            addMark(best, T);
            lastMark = best;
        }
        lastPeriod = T;
    }

    long inputEnd() const { return written; }

    // Mark nearest to `t` whose grain (±period) is fully available.
    bool nearest(double t, long &pos, int &period) const {
        if (markCount == 0) return false;
        int bestI = -1;
        double bestD = 1e18;
        int count = std::min(markCount, kMarks);
        for (int k = 0; k < count; ++k) {
            const Mark &m = marks[(markCount - 1 - k) & (kMarks - 1)];
            if (m.pos + m.period > written) continue;
            double d = std::fabs((double) m.pos - t);
            if (d < bestD) { bestD = d; bestI = (markCount - 1 - k) & (kMarks - 1); }
            if ((double) m.pos < t - 4.0 * maxPeriod) break;
        }
        if (bestI < 0) return false;
        pos = marks[bestI].pos; period = marks[bestI].period;
        return true;
    }

    inline float sampleAt(double t) const {
        long i = (long) std::floor(t);
        float f = (float) (t - (double) i);
        if (i < written - (long) kBufSize + 4 || i + 1 >= written) return 0.0f;
        float a = buf[i & kMask], b = buf[(i + 1) & kMask];
        return a + (b - a) * f;
    }

private:
    struct Mark { long pos; int period; };
    void addMark(long pos, int period) { marks[markCount & (kMarks - 1)] = {pos, period}; ++markCount; }

    static constexpr int kBufSize = 1 << 15, kMask = kBufSize - 1, kMarks = 256;
    std::vector<float> buf, env;
    Biquad lp;
    Mark marks[kMarks];
    int markCount = 0;
    long written = 0, lastMark = 0;
    int lastPeriod = 240, maxPeriod = 504, minPeriod = 44, unvoicedPeriod = 240;
    float sampleRate = 48000;
};

// One output voice. Either follows the input pitch with a semitone shift (natural), or locks
// to an absolute frequency (hard tuning — the "digital harmony" sound).
class PsolaVoice {
public:
    void setup(float sr, const PitchMarker *m) {
        sampleRate = sr;
        marker = m;
        out.assign(kRing, 0.0f);
        nextCenter = -1;
        written = 0;
    }
    void reset() { std::fill(out.begin(), out.end(), 0.0f); nextCenter = -1; }

    // shiftSemis: used when targetHz <= 0. targetHz > 0: output pitch locked to targetHz.
    // formantRatio: >1 reads grains faster (brighter/smaller voice), <1 darker/bigger.
    void setPitch(float shiftSemis, float targetHz, float formantRatio = 1.0f) {
        shift = shiftSemis; target = targetHz; formant = formantRatio;
    }

    // Produces n samples (added to dst with gain). Call after the marker has been pushed with
    // the same n input samples.
    void render(float *dst, int n, float gain) {
        const long blockStart = written;
        const long horizon = blockStart + n + marker->maxPeriodSamples();
        const int D = marker->latency();
        if (nextCenter < 0) nextCenter = (double) blockStart + marker->maxPeriodSamples();
        while (nextCenter < horizon) {
            long ta; int T;
            double srcTime = nextCenter - D;
            if (!marker->nearest(srcTime, ta, T)) { nextCenter += 0.005 * sampleRate; continue; }
            float inHz = sampleRate / (float) T;
            float outHz = target > 0 ? target : inHz * std::pow(2.0f, shift / 12.0f);
            outHz = clampf(outHz, 30.0f, 2000.0f);
            float P = sampleRate / outHz;                      // synthesis period
            int half = T;                                      // grain half length
            // energy normalisation for the overlap density (Hann overlap sum = half / P)
            float g = clampf(P / (float) half, 0.5f, 1.6f);
            long c = (long) std::lround(nextCenter);
            for (int k = -half; k < half; ++k) {
                long o = c + k;
                if (o < blockStart) continue;
                float w = 0.5f + 0.5f * std::cos(kPi * (float) k / (float) half);
                float s = marker->sampleAt((double) ta + (double) k * formant);
                out[o & kMask] += s * w * g;
            }
            nextCenter += P;
        }
        for (int i = 0; i < n; ++i) {
            long o = blockStart + i;
            dst[i] += out[o & kMask] * gain;
            out[o & kMask] = 0.0f;
        }
        written += n;
    }

private:
    static constexpr int kRing = 1 << 14, kMask = kRing - 1;
    const PitchMarker *marker = nullptr;
    std::vector<float> out;
    double nextCenter = -1;
    long written = 0;
    float sampleRate = 48000, shift = 0, target = 0, formant = 1;
};

} // namespace hc
