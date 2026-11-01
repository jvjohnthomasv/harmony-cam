// YIN pitch tracker running on a decimated (~16 kHz) copy of the vocal.
// Also exposes the decimated stream so speech recognition can reuse it.
#pragma once

#include "DspUtil.h"

namespace hc {

// Windowed-sinc low-pass + integer decimation.
class Decimator {
public:
    void setup(float inRate, int factor) {
        this->factor = std::max(1, factor);
        const int taps = 48;
        coeffs.assign(taps, 0.0f);
        float cutoff = 0.45f / this->factor; // normalised to input rate
        float sum = 0;
        for (int i = 0; i < taps; ++i) {
            float m = i - (taps - 1) / 2.0f;
            float sinc = m == 0 ? 2 * cutoff : std::sin(2 * kPi * cutoff * m) / (kPi * m);
            float win = 0.42f - 0.5f * std::cos(2 * kPi * i / (taps - 1)) + 0.08f * std::cos(4 * kPi * i / (taps - 1));
            coeffs[i] = sinc * win;
            sum += coeffs[i];
        }
        for (auto &c : coeffs) c /= sum;
        history.assign(taps * 2, 0.0f);
        histPos = 0;
        phase = 0;
        (void) inRate;
    }
    // Returns number of output samples written to `out`.
    int process(const float *in, int n, float *out) {
        int taps = (int) coeffs.size(), produced = 0;
        for (int i = 0; i < n; ++i) {
            // store sample twice so a contiguous window is always available
            history[histPos] = in[i];
            history[histPos + taps] = in[i];
            histPos = (histPos + 1) % taps;
            if (++phase >= factor) {
                phase = 0;
                const float *h = &history[histPos]; // oldest sample first
                float acc = 0;
                for (int k = 0; k < taps; ++k) acc += h[k] * coeffs[taps - 1 - k];
                out[produced++] = acc;
            }
        }
        return produced;
    }
    int getFactor() const { return factor; }
private:
    std::vector<float> coeffs, history;
    int histPos = 0, phase = 0, factor = 3;
};

struct PitchResult {
    bool voiced = false;
    float f0 = 0;          // Hz
    float clarity = 0;     // 1 - YIN aperiodicity
    float rmsDb = -120;
};

class PitchTracker {
public:
    // dRate: decimated sample rate
    void setup(float dRate) {
        rate = dRate;
        minTau = std::max(2, (int) (dRate / kMaxF0));
        maxTau = (int) (dRate / kMinF0);
        window = (int) (dRate * 0.032f); // 32 ms integration window
        bufLen = window + maxTau + 2;
        buf.assign(bufLen, 0.0f);
        diff.assign(maxTau + 2, 0.0f);
        filled = 0;
    }
    // Push decimated samples. Analysis is run on demand via analyze().
    void push(const float *x, int n) {
        for (int i = 0; i < n; ++i) {
            buf[writePos] = x[i];
            writePos = (writePos + 1) % bufLen;
        }
        filled = std::min(bufLen, filled + n);
    }
    // Lag (in decimated samples) between the newest pushed sample and the centre of the analysed window.
    int analysisLag() const { return (window + maxTau) / 2; }

    PitchResult analyze() {
        PitchResult r;
        if (filled < bufLen) return r;
        // unwrap into linear scratch, oldest first
        lin.resize(bufLen);
        for (int i = 0; i < bufLen; ++i) lin[i] = buf[(writePos + i) % bufLen];

        double energy = 0;
        for (int i = 0; i < window; ++i) energy += (double) lin[i] * lin[i];
        r.rmsDb = gainToDb((float) std::sqrt(energy / window) + 1e-12f);
        if (r.rmsDb < kGateDb) return r;

        // Difference function
        diff[0] = 1;
        float running = 0;
        int bestTau = -1;
        float bestVal = 1e9f;
        for (int tau = 1; tau <= maxTau; ++tau) {
            float d = 0;
            const float *a = lin.data();
            const float *b = lin.data() + tau;
            for (int j = 0; j < window; ++j) { float e = a[j] - b[j]; d += e * e; }
            running += d;
            float cmnd = running > 0 ? d * tau / running : 1.0f;
            diff[tau] = cmnd;
        }
        // absolute threshold: first dip below threshold, then walk to its local minimum
        for (int tau = minTau; tau < maxTau; ++tau) {
            if (diff[tau] < kThreshold) {
                while (tau + 1 < maxTau && diff[tau + 1] < diff[tau]) ++tau;
                bestTau = tau; bestVal = diff[tau];
                break;
            }
        }
        if (bestTau < 0) {
            // fall back to global minimum if it's reasonably periodic
            for (int tau = minTau; tau < maxTau; ++tau)
                if (diff[tau] < bestVal) { bestVal = diff[tau]; bestTau = tau; }
            if (bestVal > kVoicedMax) { r.clarity = 1 - bestVal; return r; }
        }
        // parabolic interpolation
        float t = (float) bestTau;
        if (bestTau > 1 && bestTau < maxTau) {
            float s0 = diff[bestTau - 1], s1 = diff[bestTau], s2 = diff[bestTau + 1];
            float denom = 2 * (2 * s1 - s2 - s0);
            if (std::fabs(denom) > 1e-9f) t = bestTau + (s2 - s0) / denom;
        }
        r.f0 = rate / t;
        r.clarity = 1 - bestVal;
        r.voiced = bestVal < kVoicedMax && r.f0 >= kMinF0 && r.f0 <= kMaxF0;
        return r;
    }

private:
    static constexpr float kMinF0 = 65.0f, kMaxF0 = 1100.0f;
    static constexpr float kThreshold = 0.12f, kVoicedMax = 0.25f, kGateDb = -52.0f;
    float rate = 16000;
    int minTau = 14, maxTau = 246, window = 512, bufLen = 760, writePos = 0, filled = 0;
    std::vector<float> buf, diff, lin;
};

} // namespace hc
