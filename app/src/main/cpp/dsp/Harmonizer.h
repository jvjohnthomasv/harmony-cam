// The harmony engine: pitch/key analysis ("brain") + formant-preserving harmony voices + mix.
// Shared by the realtime monitor and the offline high-quality renderer so both sound the same.
#pragma once

#include "DspUtil.h"
#include "PitchTracker.h"
#include "MusicTheory.h"
#include "Reverb.h"
#include "../third_party/signalsmith-stretch.h"

#include <random>

namespace hc {

constexpr int kMaxVoices = 4;

// Everything the user controls (gestures + settings). Logged as automation while recording,
// so the offline render reproduces exactly what was performed.
struct Controls {
    float voices = 2;          // 0..4 harmony voices
    float height = 0.5f;       // 0 (low) .. 1 (high) → harmonies below / around / above melody
    float mute = 0;            // 1 = harmonies muted (fist)
    float fx = 0.3f;           // 0..1 pinch → reverb + effect intensity
    float keyRoot = -1;        // -1 auto-detect, 0..11 = C..B
    float scale = 0;           // 0 major, 1 minor
    float harmonyLevel = 0.8f; // 0..1
    float dryLevel = 1.0f;     // 0..1
    float humanize = 0.7f;     // 0..1 timing/pitch/timbre variation between voices
    float tightness = 0.6f;    // 0..1 how much harmonies correct the singer's sustained tuning offset
    float reverb = 0.35f;      // 0..1 base reverb amount (pinch adds on top)
    float width = 0.8f;        // 0..1 stereo spread of harmony voices
    float correction = 0;      // 0..1 lead-vocal pitch correction amount
    float retune = 0.5f;       // 0 = hard/robotic (instant) .. 1 = natural (slow, keeps vibrato)
    float doubler = 0;         // 0..1 double-tracked lead
    float echo = 0;            // 0..1 stereo echo
    float bypass = 0;          // 1 = all sound effects off: plain recording
};
constexpr int kNumControls = 17;
static_assert(sizeof(Controls) == kNumControls * sizeof(float), "Controls must be plain floats");

struct NoteState {
    bool voiced = false;
    bool hasNote = false;
    float midi = 0;      // detected (smoothed) pitch in MIDI units
    int note = 60;       // melody note snapped to the key
    float devSlow = 0;   // sustained deviation of the singer from `note` (semitones)
    float f0 = 0;
    int keyRoot = -1;
    int scale = kMajor;
    float rmsDb = -120;
};

struct VoiceTargets {
    bool active[kMaxVoices] = {false, false, false, false};
    float shift[kMaxVoices] = {4, -3, 7, -5}; // semitones relative to the singer
    int midi[kMaxVoices] = {0, 0, 0, 0};      // resulting harmony notes (for visuals)
};

// ───────────────────────────── Analysis ─────────────────────────────
class HarmonyBrain {
public:
    void setup(float sr) {
        sampleRate = sr;
        int factor = std::max(1, (int) std::lround(sr / 16000.0f));
        decimator.setup(sr, factor);
        decimatedRate = sr / factor;
        pitch.setup(decimatedRate);
        hopDecimated = (int) (decimatedRate * 0.01f); // 10 ms
        scratch.assign(8192, 0.0f);
        reset();
    }
    void reset() {
        st = NoteState{};
        keyDetector.reset();
        sinceHop = 0; medCount = 0; pending = 0; zone = 2;
    }
    float getDecimatedRate() const { return decimatedRate; }
    int hopInputSamples() const { return hopDecimated * decimator.getFactor(); }
    int analysisLagInputSamples() const { return pitch.analysisLag() * decimator.getFactor(); }
    const NoteState &state() const { return st; }

    // Analyse a block. onDecimated(const float*, int) receives the ~16 kHz stream (for speech recognition).
    // onHop() is invoked after every 10 ms analysis step.
    template<class DecimatedFn, class HopFn>
    void analyze(const float *in, int n, const Controls &c, DecimatedFn &&onDecimated, HopFn &&onHop) {
        int offset = 0;
        while (offset < n) {
            int chunk = std::min(n - offset, 2048);
            int produced = decimator.process(in + offset, chunk, scratch.data());
            onDecimated(scratch.data(), produced);
            int i = 0;
            while (i < produced) {
                int take = std::min(produced - i, hopDecimated - sinceHop);
                pitch.push(scratch.data() + i, take);
                sinceHop += take; i += take;
                if (sinceHop >= hopDecimated) { sinceHop = 0; step(c); onHop(); }
            }
            offset += chunk;
        }
    }

    // Harmony targets from the current note state and controls.
    VoiceTargets targets(const NoteState &s, const Controls &c) {
        VoiceTargets t;
        // zone with hysteresis so a slightly shaky hand doesn't flip the harmony layout
        float zf = clampf(c.height, 0, 1) * 4.999f;
        if (zf > zone + 1 + 0.12f || zf < zone - 0.12f) zone = std::min(4, std::max(0, (int) zf));
        static const int degrees[5][kMaxVoices] = {
            {-2, -4, -7, -9},   // low hand: harmonies below the melody
            {-2, -4, +2, -7},
            {+2, -2, +4, -3},   // middle: around the melody
            {+2, +4, -2, +7},
            {+2, +4, +7, +9},   // high hand: harmonies above
        };
        int voices = (int) std::lround(clampf(c.voices, 0, kMaxVoices));
        bool muted = c.mute > 0.5f;
        int root = s.keyRoot, scale = s.scale;
        for (int v = 0; v < kMaxVoices; ++v) {
            int deg = degrees[zone][v];
            t.active[v] = !muted && v < voices;
            if (s.hasNote) {
                int nh = stepScale(s.note, deg, root, scale);
                t.shift[v] = (float) (nh - s.note) - clampf(c.tightness, 0, 1) * s.devSlow;
                t.midi[v] = nh;
            } else {
                t.shift[v] = (float) stepScale(60, deg, -1, kChromatic) - 60;
                t.midi[v] = 0;
            }
        }
        return t;
    }

private:
    void step(const Controls &c) {
        PitchResult r = pitch.analyze();
        st.rmsDb = r.rmsDb;
        const float hopSec = hopDecimated / decimatedRate;
        if (r.voiced) {
            float m = 69.0f + 12.0f * std::log2(r.f0 / 440.0f);
            // octave-error guard: YIN occasionally jumps an octave for one frame
            if (st.hasNote && medCount > 0 && std::fabs(std::fabs(m - st.midi) - 12.0f) < 0.6f && r.clarity < 0.93f) {
                m = st.midi;
            }
            med[medCount % 5] = m; ++medCount;
            float mm = median();
            st.midi = mm;
            st.f0 = r.f0;
            st.voiced = true;

            if (c.keyRoot >= 0) {
                st.keyRoot = ((int) c.keyRoot) % 12;
                st.scale = c.scale > 0.5f ? kMinor : kMajor;
            } else {
                keyDetector.observe(mm, hopSec);
                st.keyRoot = keyDetector.getRoot();
                st.scale = keyDetector.confident() ? keyDetector.getScale() : kChromatic;
                if (st.keyRoot < 0) st.scale = kChromatic;
            }
            int effScale = st.keyRoot < 0 ? kChromatic : st.scale;
            int candidate = snapToScale(mm, st.keyRoot, effScale);
            if (!st.hasNote) {
                st.note = candidate; st.hasNote = true; st.devSlow = clampf(mm - candidate, -0.5f, 0.5f); pending = 0;
            } else if (candidate != st.note) {
                // hysteresis: a big jump switches at once; small drifts (vibrato, a flat singer)
                // must clearly cross into the neighbouring note and stay there for 30 ms
                float away = std::fabs(mm - st.note);
                if (away > 0.95f || (away > 0.7f && ++pending >= 3)) {
                    st.note = candidate; pending = 0;
                    st.devSlow = clampf(mm - candidate, -0.5f, 0.5f);
                }
            } else {
                pending = 0;
            }
            st.devSlow += (clampf(mm - st.note, -0.6f, 0.6f) - st.devSlow) * smoothCoef(0.35f, 1.0f / hopSec);
        } else {
            st.voiced = false;
            medCount = 0;
        }
    }
    float median() const {
        int n = std::min(medCount, 5);
        float v[5];
        for (int i = 0; i < n; ++i) {
            float x = med[i]; int j = i - 1;
            while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; --j; }
            v[j + 1] = x;
        }
        return v[n / 2];
    }

    float sampleRate = 48000, decimatedRate = 16000;
    Decimator decimator;
    PitchTracker pitch;
    KeyDetector keyDetector;
    NoteState st;
    std::vector<float> scratch;
    float med[5] = {};
    int medCount = 0, pending = 0, sinceHop = 0, hopDecimated = 160, zone = 2;
};

// ───────────────────────────── Voices ─────────────────────────────
class VoiceBank {
public:
    enum Quality { Realtime, Studio };

    void setup(float sr, Quality q, uint32_t seed = 1234) {
        sampleRate = sr;
        quality = q;
        std::mt19937 rng(seed);
        std::uniform_real_distribution<float> u(0, 1);
        for (int v = 0; v < kMaxVoices; ++v) {
            Voice &vc = voices[v];
            if (q == Studio) vc.stretch.presetDefault(1, sr);           // 120 ms blocks: best quality
            else vc.stretch.configure(1, (int) (sr * 0.032f), (int) (sr * 0.008f), true); // ~40 ms latency
            vc.delay.init((int) (sr * 0.08f));
            vc.tone.setLowpass(9500, sr, 0.6f);
            vc.body.setPeak(220, sr, -1.5f, 0.8f);   // tame low-mid build-up when voices stack
            vc.air.setHighShelf(7000, sr, -2.0f);
            vc.env = 0; vc.curShift = 0; vc.initShift = true; vc.sleeping = false; vc.idle = 0;
            vc.phase1 = u(rng) * 2 * kPi; vc.phase2 = u(rng) * 2 * kPi;
            vc.rate1 = 0.11f + 0.08f * u(rng); vc.rate2 = 0.31f + 0.15f * u(rng);
            vc.persona = (u(rng) - 0.5f) * 0.7f;       // timbre offset in formant semitones
            vc.gainTrim = 0.86f + 0.14f * u(rng);
        }
        static const float pans[kMaxVoices] = {-0.45f, 0.45f, -0.8f, 0.8f};
        static const float delaysStudioMs[kMaxVoices] = {14, 21, 28, 35};
        static const float delaysLiveMs[kMaxVoices] = {3, 7, 11, 15};
        for (int v = 0; v < kMaxVoices; ++v) {
            voices[v].pan = pans[v];
            voices[v].baseDelayMs = (q == Studio ? delaysStudioMs : delaysLiveMs)[v];
        }
        tmpOut.assign(kMaxBlock, 0.0f);
        timeSec = 0;
    }

    int latencySamples() const {
        return voices[0].stretch.inputLatency() + voices[0].stretch.outputLatency();
    }
    int inputLatency() const { return voices[0].stretch.inputLatency(); }

    // Adds the harmony voices into outL/outR. `in` is the (high-passed) dry vocal.
    // n must be <= kMaxBlock.
    void process(const float *in, int n, const VoiceTargets &t, const Controls &c, float f0Hint,
                 float *outL, float *outR) {
        if (n > kMaxBlock) {
            for (int o = 0; o < n; o += kMaxBlock)
                process(in + o, std::min(kMaxBlock, n - o), t, c, f0Hint, outL + o, outR + o);
            return;
        }
        const float humanize = clampf(c.humanize, 0, 1);
        const float width = clampf(c.width, 0, 1);
        const float glide = smoothCoef(0.028f, sampleRate, (float) n);
        const float attack = smoothCoef(0.045f, sampleRate), release = smoothCoef(0.14f, sampleRate);
        const float level = clampf(c.harmonyLevel, 0, 1);
        const float dt = n / sampleRate;
        timeSec += dt;

        for (int v = 0; v < kMaxVoices; ++v) {
            Voice &vc = voices[v];
            bool active = t.active[v];

            // Sleep voices that have been silent for a while to save battery.
            if (!active && vc.env < 1e-4f) {
                vc.idle += n;
                if (vc.idle > sampleRate * 1.5f) {
                    if (!vc.sleeping) { vc.sleeping = true; }
                    vc.initShift = true;
                    continue;
                }
            } else {
                vc.idle = 0;
                if (vc.sleeping) { vc.stretch.reset(); vc.delay.reset(); vc.sleeping = false; }
            }

            if (vc.initShift) { vc.curShift = t.shift[v]; vc.initShift = false; }
            vc.curShift += (t.shift[v] - vc.curShift) * glide;

            // slow, independent pitch drift per voice (a few cents) — real singers never lock perfectly
            vc.phase1 += 2 * kPi * vc.rate1 * dt; vc.phase2 += 2 * kPi * vc.rate2 * dt;
            float driftCents = humanize * (4.0f * std::sin(vc.phase1) + 2.5f * std::sin(vc.phase2));
            float shift = vc.curShift + driftCents / 100.0f;

            vc.stretch.setTransposeSemitones(shift);
            // keep the singer's vowel/formants, plus a small per-voice offset so voices sound like different people
            float formant = humanize * vc.persona + clampf(shift * 0.06f, -0.8f, 0.8f);
            vc.stretch.setFormantSemitones(formant, true);
            if (f0Hint > 0) vc.stretch.setFormantBase(f0Hint / sampleRate);

            const float *ins[1] = {in};
            float *outs[1] = {tmpOut.data()};
            vc.stretch.process(ins, n, outs, n);

            int delaySamples = std::max(1, (int) (vc.baseDelayMs * (0.35f + 0.65f * humanize) * 0.001f * sampleRate));
            float angle = (vc.pan * width * 0.5f + 0.5f) * kPi * 0.5f;
            float gl = std::cos(angle), gr = std::sin(angle);
            for (int i = 0; i < n; ++i) {
                vc.env += ((active ? 1.0f : 0.0f) - vc.env) * (active ? attack : release);
                vc.delay.write(tmpOut[i]);
                float s = vc.delay.read(delaySamples);
                s = vc.air.process(vc.body.process(vc.tone.process(s)));
                s *= vc.env * vc.gainTrim * level * kVoiceGain;
                outL[i] += s * gl;
                outR[i] += s * gr;
            }
        }
    }

    float envelope(int v) const { return voices[v].env; }
    static constexpr int kMaxBlock = 4096;

private:
    struct Voice {
        signalsmith::stretch::SignalsmithStretch<float> stretch;
        DelayLine delay;
        Biquad tone, body, air;
        float env = 0, curShift = 0, pan = 0, persona = 0, gainTrim = 1, baseDelayMs = 10;
        float phase1 = 0, phase2 = 0, rate1 = 0.1f, rate2 = 0.3f;
        bool initShift = true, sleeping = false;
        float idle = 0;
    };
    static constexpr float kVoiceGain = 0.62f;
    Voice voices[kMaxVoices];
    float sampleRate = 48000, timeSec = 0;
    Quality quality = Realtime;
    std::vector<float> tmpOut;
};


// ───────────────────────────── Lead vocal FX ─────────────────────────────
// Pitch correction (formant-preserving), doubler and stereo echo for the lead vocal.
class LeadProcessor {
public:
    // alignLatency (studio only): the direct path is delayed by this many samples so it lines
    // up with the harmony voices; the corrected path is padded to the same total.
    void setup(float sr, bool studio, int alignLatency = 0) {
        sampleRate = sr;
        isStudio = studio;
        if (studio) corrector.presetDefault(1, sr);
        else corrector.configure(1, (int) (sr * 0.021f), (int) (sr * 0.0053f), false); // ~21 ms
        corrLatency = corrector.inputLatency() + corrector.outputLatency();
        directDelay = studio ? alignLatency : 0;
        corrPad = studio ? std::max(0, alignLatency - corrLatency) : 0;
        directLine.init(std::max(directDelay, 1) + 8);
        corrLine.init(std::max(corrPad, 1) + 8);
        dbl.init((int) (sr * 0.06f));
        echoL.init((int) (sr * 0.8f)); echoR.init((int) (sr * 0.8f));
        echoToneL.setLowpass(4200, sr); echoToneR.setLowpass(4200, sr);
        echoHpL.setHighpass(250, sr); echoHpR.setHighpass(250, sr);
        dblTone.setLowpass(8000, sr);
        tmp.assign(4096, 0.0f);
        corrEnv = 0; corrShift = 0; devFiltered = 0; phase = 0; corrActive = false;
    }

    // in: conditioned lead. lead: output mono lead. L/R: doubler + echo added (stereo).
    void process(const float *in, int n, const NoteState &ns, const Controls &c, float *lead, float *L, float *R) {
        if (n > 4096) {
            for (int o = 0; o < n; o += 4096) process(in + o, std::min(4096, n - o), ns, c, lead + o, L + o, R + o);
            return;
        }
        const float amount = clampf(c.correction, 0, 1);
        const float dt = n / sampleRate;
        // ── pitch-correction target
        if (ns.voiced && ns.hasNote) {
            float dev = ns.midi - (float) ns.note;
            float tau = 0.004f + clampf(c.retune, 0, 1) * 0.30f;
            devFiltered += (dev - devFiltered) * (1.0f - std::exp(-dt / tau));
            float target = -amount * devFiltered;
            corrShift += (target - corrShift) * (1.0f - std::exp(-dt / std::max(0.002f, tau * 0.5f)));
        } else {
            corrShift *= std::exp(-dt / 0.15f);
        }
        const bool want = amount > 0.001f;
        if (want && !corrActive) { corrector.reset(); corrActive = true; }
        if (corrActive) {
            corrector.setTransposeSemitones(corrShift);
            corrector.setFormantSemitones(0, true);
            if (ns.voiced && ns.f0 > 0) corrector.setFormantBase(ns.f0 / sampleRate);
            const float *ins[1] = {in};
            float *outs[1] = {tmp.data()};
            corrector.process(ins, n, outs, n);
        }
        const float xf = smoothCoef(0.03f, sampleRate);
        for (int i = 0; i < n; ++i) {
            directLine.write(in[i]);
            float direct = directDelay > 0 ? directLine.read(directDelay + 1) : in[i];
            float corrected = 0;
            if (corrActive) {
                corrLine.write(tmp[i]);
                corrected = corrPad > 0 ? corrLine.read(corrPad + 1) : tmp[i];
            }
            corrEnv += ((want ? 1.0f : 0.0f) - corrEnv) * xf;
            lead[i] = direct + (corrected - direct) * corrEnv;
        }
        if (!want && corrEnv < 1e-4f) corrActive = false;

        // ── doubler: two slowly-modulated short delays panned left/right
        const float dblAmt = clampf(c.doubler, 0, 1);
        const float echoAmt = clampf(c.echo, 0, 1);
        const int echoSamples = (int) (0.34f * sampleRate);
        for (int i = 0; i < n; ++i) {
            float x = lead[i];
            dbl.write(x);
            if (dblAmt > 0.001f) {
                phase += 1.0f / sampleRate;
                float d1 = (0.019f + 0.0016f * std::sin(2 * kPi * 0.31f * phase)) * sampleRate;
                float d2 = (0.027f + 0.0019f * std::sin(2 * kPi * 0.23f * phase + 1.7f)) * sampleRate;
                float a = dblTone.process(dbl.readFrac(d1));
                float b = dbl.readFrac(d2);
                L[i] += a * dblAmt * 0.55f;
                R[i] += b * dblAmt * 0.55f;
            }
            if (echoAmt > 0.001f) {
                // ping-pong: left feeds right and vice versa
                float el = echoL.read(echoSamples), er = echoR.read(echoSamples);
                float fb = 0.38f;
                echoL.write(echoHpL.process(echoToneL.process(x * 0.7f + er * fb)));
                echoR.write(echoHpR.process(echoToneR.process(el * fb)));
                L[i] += el * echoAmt * 0.6f;
                R[i] += er * echoAmt * 0.6f;
            } else {
                echoL.write(0); echoR.write(0);
            }
        }
    }

    int latencySamples() const { return isStudio ? directDelay : 0; }

private:
    signalsmith::stretch::SignalsmithStretch<float> corrector;
    DelayLine directLine, corrLine, dbl, echoL, echoR;
    Biquad echoToneL, echoToneR, echoHpL, echoHpR, dblTone;
    std::vector<float> tmp;
    float sampleRate = 48000, corrEnv = 0, corrShift = 0, devFiltered = 0, phase = 0;
    int corrLatency = 0, directDelay = 0, corrPad = 0;
    bool isStudio = false, corrActive = false;
};

// ───────────────────────────── Mix bus ─────────────────────────────
// Lead-vocal conditioning + reverb + limiter.
class MixBus {
public:
    void setup(float sr, bool studio) {
        sampleRate = sr;
        hp.setHighpass(75, sr);
        presence.setPeak(3200, sr, studio ? 1.5f : 0.0f, 0.9f);
        mud.setPeak(300, sr, studio ? -1.5f : 0.0f, 1.0f);
        comp.setup(sr);
        comp.thresholdDb = -22; comp.ratio = studio ? 3.0f : 2.0f; comp.makeupDb = studio ? 5.0f : 2.0f;
        useComp = true;
        reverb.setup(sr);
        reverb.setPreDelayMs(studio ? 28 : 18);
        limiter.setup(sr, -1.0f);
        fxSmoothed = 0.3f;
    }
    // Conditions the dry vocal in place (high-pass, light EQ, compression).
    void conditionDry(float *x, int n) {
        for (int i = 0; i < n; ++i) {
            float s = hp.process(x[i]);
            s = mud.process(presence.process(s));
            x[i] = useComp ? comp.process(s) : s;
        }
    }
    // Final mix: dry (centred) + harmonies (already in L/R) + reverb, then limiter.
    // extraL/extraR (optional): backing track, summed after the reverb but before the limiter.
    void mix(const float *dry, float *L, float *R, int n, const Controls &c,
             const float *extraL = nullptr, const float *extraR = nullptr) {
        float dryLevel = clampf(c.dryLevel, 0, 1);
        float fxTarget = clampf(c.fx, 0, 1);
        float fxCoef = smoothCoef(0.08f, sampleRate);
        for (int i = 0; i < n; ++i) {
            fxSmoothed += (fxTarget - fxSmoothed) * fxCoef;
            float wet = clampf(0.6f * clampf(c.reverb, 0, 1) + 0.6f * fxSmoothed, 0, 1);
            float d = dry[i] * dryLevel;
            float send = d + 0.9f * 0.5f * (L[i] + R[i]);
            float wl, wr;
            reverb.process(send, wl, wr);
            float l = d + L[i] + wl * wet * kWetScale;
            float r = d + R[i] + wr * wet * kWetScale;
            if (extraL) { l += extraL[i]; r += extraR[i]; }
            limiter.process(l, r);
            L[i] = l; R[i] = r;
        }
        reverb.setSize(0.72f + 0.28f * fxSmoothed);
    }
    void reset() { hp.reset(); presence.reset(); mud.reset(); reverb.reset(); }
private:
    static constexpr float kWetScale = 0.9f;
    float sampleRate = 48000, fxSmoothed = 0.3f;
    Biquad hp, presence, mud;
    Compressor comp;
    bool useComp = true;
    Reverb reverb;
    Limiter limiter;
};

} // namespace hc
