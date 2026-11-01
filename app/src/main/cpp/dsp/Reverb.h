// Stereo plate-style reverb (Freeverb topology with pre-delay, damping and tone shaping).
#pragma once

#include "DspUtil.h"

namespace hc {

class Reverb {
public:
    void setup(float sr) {
        sampleRate = sr;
        float scale = sr / 44100.0f;
        static const int combTuning[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int apTuning[kAllpasses] = {556, 441, 341, 225};
        const int spread = 23;
        for (int i = 0; i < kCombs; ++i) {
            combL[i].init((int) (combTuning[i] * scale));
            combR[i].init((int) ((combTuning[i] + spread) * scale));
        }
        for (int i = 0; i < kAllpasses; ++i) {
            apL[i].init((int) (apTuning[i] * scale));
            apR[i].init((int) ((apTuning[i] + spread) * scale));
        }
        preDelay.init((int) (0.1f * sr));
        inputHp.setHighpass(180, sr);
        inputLp.setLowpass(9000, sr);
        setSize(0.82f);
        setDamping(0.35f);
    }
    void setSize(float s) { feedback = 0.70f + 0.27f * clampf(s, 0, 1); }
    void setDamping(float d) { damp = clampf(d, 0, 1) * 0.6f; }
    void setPreDelayMs(float ms) { preDelaySamples = std::max(1, (int) (ms * 0.001f * sampleRate)); }

    // mono send in → stereo wet out (added to outL/outR)
    inline void process(float in, float &outL, float &outR) {
        preDelay.write(inputLp.process(inputHp.process(in)));
        float x = preDelay.read(preDelaySamples) * 0.015f;
        float l = 0, r = 0;
        for (int i = 0; i < kCombs; ++i) { l += combL[i].process(x, feedback, damp); r += combR[i].process(x, feedback, damp); }
        for (int i = 0; i < kAllpasses; ++i) { l = apL[i].process(l); r = apR[i].process(r); }
        outL = l; outR = r;
    }
    void reset() {
        for (auto &c : combL) c.reset();
        for (auto &c : combR) c.reset();
        for (auto &a : apL) a.reset();
        for (auto &a : apR) a.reset();
        preDelay.reset();
        inputHp.reset(); inputLp.reset();
    }

private:
    struct Comb {
        std::vector<float> buf; int pos = 0; float store = 0;
        void init(int n) { buf.assign(std::max(n, 1), 0.0f); pos = 0; store = 0; }
        void reset() { std::fill(buf.begin(), buf.end(), 0.0f); store = 0; }
        inline float process(float in, float fb, float damp) {
            float out = buf[pos];
            store = out * (1 - damp) + store * damp;
            buf[pos] = in + store * fb;
            if (++pos >= (int) buf.size()) pos = 0;
            return out;
        }
    };
    struct Allpass {
        std::vector<float> buf; int pos = 0;
        void init(int n) { buf.assign(std::max(n, 1), 0.0f); pos = 0; }
        void reset() { std::fill(buf.begin(), buf.end(), 0.0f); }
        inline float process(float in) {
            float b = buf[pos];
            float out = b - in;
            buf[pos] = in + b * 0.5f;
            if (++pos >= (int) buf.size()) pos = 0;
            return out;
        }
    };
    static constexpr int kCombs = 8, kAllpasses = 4;
    Comb combL[kCombs], combR[kCombs];
    Allpass apL[kAllpasses], apR[kAllpasses];
    DelayLine preDelay;
    Biquad inputHp, inputLp;
    float sampleRate = 48000, feedback = 0.84f, damp = 0.2f;
    int preDelaySamples = 960;
};

} // namespace hc
