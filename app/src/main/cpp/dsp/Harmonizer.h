// The harmony engine: pitch/key analysis ("brain") + PSOLA harmony voices + lead FX + mix.
// Shared by the realtime monitor and the offline renderer so both sound the same.
#pragma once

#include "DspUtil.h"
#include "PitchTracker.h"
#include "MusicTheory.h"
#include "Reverb.h"
#include "Psola.h"

#include <random>

namespace hc {

constexpr int kMaxVoices = 4;

enum HarmonyStyle { kStyleChord = 0, kStyleIntervals = 1 };

// Everything the user controls (gestures + settings). Logged as automation while recording,
// so the offline render reproduces exactly what was performed.
struct Controls {
    float voices = 4;          // 0..4 harmony voices
    float height = 0.5f;       // 0 (low) .. 1 (high) → harmonies below / around / above melody
    float mute = 0;            // 1 = harmonies muted (fist)
    float fx = 0.3f;           // 0..1 pinch → reverb + effect intensity
    float keyRoot = -1;        // -1 auto-detect, 0..11 = C..B
    float scale = 0;           // 0 major, 1 minor
    float harmonyLevel = 0.8f; // 0..1
    float dryLevel = 1.0f;     // 0..1
    float humanize = 0.3f;     // 0..1 timing/pitch/timbre variation between voices
    float tightness = 0.6f;    // 0..1 (interval style) how much harmonies correct sustained tuning offset
    float reverb = 0.35f;      // 0..1 base reverb amount (pinch adds on top)
    float width = 0.8f;        // 0..1 stereo spread of harmony voices
    float correction = 0;      // 0..1 lead-vocal pitch correction amount
    float retune = 0.5f;       // 0 = hard/robotic (instant) .. 1 = natural (slow, keeps vibrato)
    float doubler = 0;         // 0..1 double-tracked lead
    float echo = 0;            // 0..1 stereo echo
    float bypass = 0;          // 1 = all sound effects off: plain recording
    float style = 0;           // HarmonyStyle: 0 = digital chord (hard-tuned), 1 = classic intervals
};
constexpr int kNumControls = 18;
static_assert(sizeof(Controls) == kNumControls * sizeof(float), "Controls must be plain floats");

struct NoteState {
    bool voiced = false;
    bool hasNote = false;
    float midi = 0;      // detected (smoothed) pitch in MIDI units
    int note = 60;       // melody note snapped to the key
    float devSlow = 0;   // sustained deviation of the singer from `note` (semitones)
    float f0 = 0;        // latest raw f0 (Hz)
    int keyRoot = -1;
    int scale = kMajor;
    float rmsDb = -120;
};

struct VoiceTargets {
    bool active[kMaxVoices] = {false, false, false, false};
    float shift[kMaxVoices] = {4, -3, 7, -5};   // semitones relative to the singer (natural)
    float targetMidi[kMaxVoices] = {0, 0, 0, 0}; // > 0: lock the voice to this exact note (hard)
    int midi[kMaxVoices] = {0, 0, 0, 0};         // resulting harmony notes (for visuals)
    bool isBass[kMaxVoices] = {false, false, false, false};
    int chordRoot = -1;                          // pitch class of the current chord (chord style)
    bool chordMinor = false;
};

inline float midiToHz(float m) { return 440.0f * std::pow(2.0f, (m - 69.0f) / 12.0f); }

// ───────────────────────────── Analysis ─────────────────────────────
class HarmonyBrain {
public:
    void setup(float sr) {
        sampleRate = sr;
        int factor = std::max(1, (int) std::lround(sr / 16000.0f));
        decimator.setup(sr, factor);
        decimatedRate = sr / factor;
        pitch.setup(decimatedRate);
        hopDecimated = (int) (decimatedRate * 0.005f); // 5 ms
        scratch.assign(8192, 0.0f);
        reset();
    }
    void reset() {
        st = NoteState{};
        keyDetector.reset();
        sinceHop = 0; medCount = 0; pending = 0; zone = 2;
        chordDegree = -1; chordNote = -1000; fallbackKey = -1;
    }
    float getDecimatedRate() const { return decimatedRate; }
    int hopInputSamples() const { return hopDecimated * decimator.getFactor(); }
    int analysisLagInputSamples() const { return pitch.analysisLag() * decimator.getFactor(); }
    const NoteState &state() const { return st; }

    // Analyse a block. onDecimated(const float*, int) receives the ~16 kHz stream (for speech recognition).
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
        float zf = clampf(c.height, 0, 1) * 4.999f;
        if (zf > zone + 1 + 0.12f || zf < zone - 0.12f) zone = std::min(4, std::max(0, (int) zf));
        int voices = (int) std::lround(clampf(c.voices, 0, kMaxVoices));
        bool muted = c.mute > 0.5f;
        for (int v = 0; v < kMaxVoices; ++v) t.active[v] = !muted && v < voices;
        if (c.style < 0.5f) chordTargets(s, c, t);
        else intervalTargets(s, c, t);
        return t;
    }

private:
    // Classic: diatonic intervals relative to the melody, following the singer's pitch.
    void intervalTargets(const NoteState &s, const Controls &c, VoiceTargets &t) {
        static const int degrees[5][kMaxVoices] = {
            {-2, -4, -7, -9}, {-2, -4, +2, -7}, {+2, -2, +4, -3}, {+2, +4, -2, +7}, {+2, +4, +7, +9},
        };
        for (int v = 0; v < kMaxVoices; ++v) {
            int deg = degrees[zone][v];
            if (s.hasNote) {
                int nh = stepScale(s.note, deg, s.keyRoot, s.scale);
                t.shift[v] = (float) (nh - s.note) - clampf(c.tightness, 0, 1) * s.devSlow;
                t.midi[v] = nh;
            } else {
                t.shift[v] = (float) stepScale(60, deg, -1, kChromatic) - 60;
            }
        }
    }

    // "Digital chord" (as in the reel): a diatonic chord that contains the sung note, voiced as a
    // deep bass on the root plus chord tones around the melody, every voice locked to its note.
    void chordTargets(const NoteState &s, const Controls &, VoiceTargets &t) {
        if (!s.hasNote) {
            for (int v = 0; v < kMaxVoices; ++v) t.shift[v] = (float) stepScale(60, v % 2 ? -2 : 2, -1, kChromatic) - 60;
            return;
        }
        int key = s.keyRoot;
        int scale = s.scale == kMinor ? kMinor : kMajor;
        if (key < 0) { if (fallbackKey < 0) fallbackKey = pitchClass(s.note); key = fallbackKey; scale = kMajor; }
        const auto &iv = scaleIntervals(scale);
        int rel = pitchClass(s.note - key);

        // choose (or keep) the chord when the melody note changes
        if (s.note != chordNote || chordDegree < 0) {
            static const float wMajor[7] = {1.0f, 0.55f, 0.5f, 0.9f, 0.9f, 0.8f, 0.05f};
            static const float wMinor[7] = {1.0f, 0.05f, 0.7f, 0.85f, 0.6f, 0.8f, 0.75f};
            const float *w = scale == kMinor ? wMinor : wMajor;
            int best = -1; float bestScore = -1;
            for (int d = 0; d < 7; ++d) {
                int r = iv[d], th = iv[(d + 2) % 7], fi = iv[(d + 4) % 7];
                bool contains = rel == r || rel == th || rel == fi;
                if (!contains) continue;
                float score = w[d] + (d == chordDegree ? 0.35f : 0.0f) + (rel == r ? 0.08f : 0.0f);
                if (score > bestScore) { bestScore = score; best = d; }
            }
            if (best < 0) best = chordDegree >= 0 ? chordDegree : 0; // chromatic note: keep chord
            chordDegree = best;
            chordNote = s.note;
        }
        int pcs[3] = {pitchClass(key + iv[chordDegree]), pitchClass(key + iv[(chordDegree + 2) % 7]), pitchClass(key + iv[(chordDegree + 4) % 7])};
        t.chordRoot = pcs[0];
        t.chordMinor = pitchClass(pcs[1] - pcs[0]) == 3;
        auto isTone = [&](int m) { int pc = pitchClass(m); return pc == pcs[0] || pc == pcs[1] || pc == pcs[2]; };

        const int m = s.note;
        int below[3] = {0, 0, 0}, above[2] = {0, 0};
        { int k = 0; for (int n = m - 2; n > m - 30 && k < 3; --n) if (isTone(n)) below[k++] = n; }
        { int k = 0; for (int n = m + 2; n < m + 30 && k < 2; ++n) if (isTone(n)) above[k++] = n; }
        int bass = 0;
        for (int n = 38; n <= 49; ++n) if (pitchClass(n) == pcs[0]) { bass = n; break; }
        while (bass > m - 10 && bass - 12 >= 28) bass -= 12; // keep the bass well under the melody

        int layout[5][kMaxVoices] = {
            {below[0], below[1], bass, below[2]},
            {below[0], bass, below[1], above[0]},
            {below[0], above[0], bass, below[1]},
            {above[0], below[0], bass, above[1]},
            {above[0], above[1], bass, below[0]},
        };
        for (int v = 0; v < kMaxVoices; ++v) {
            int note = layout[zone][v];
            if (note <= 0) note = below[0] > 0 ? below[0] : m - 5;
            t.targetMidi[v] = (float) note;
            t.shift[v] = (float) note - (s.voiced ? s.midi : (float) m);
            t.midi[v] = note;
            t.isBass[v] = note == bass;
        }
    }

    void step(const Controls &c) {
        PitchResult r = pitch.analyze();
        st.rmsDb = r.rmsDb;
        const float hopSec = hopDecimated / decimatedRate;
        if (r.voiced) {
            float m = 69.0f + 12.0f * std::log2(r.f0 / 440.0f);
            if (st.hasNote && medCount > 0 && std::fabs(std::fabs(m - st.midi) - 12.0f) < 0.6f && r.clarity < 0.93f) {
                m = st.midi; // octave-error guard
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
                // a big jump switches at once; small drifts (vibrato, a flat singer) must clearly
                // cross into the neighbouring note and stay there for 30 ms
                float away = std::fabs(mm - st.note);
                if (away > 0.95f || (away > 0.7f && ++pending >= 6)) {
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
    int medCount = 0, pending = 0, sinceHop = 0, hopDecimated = 80, zone = 2;
    int chordDegree = -1, chordNote = -1000, fallbackKey = -1;
};

// ───────────────────────────── Voices ─────────────────────────────
class VoiceBank {
public:
    enum Quality { Realtime, Studio };

    void setup(float sr, Quality q, uint32_t seed = 1234) {
        sampleRate = sr;
        quality = q;
        marker.setup(sr);
        std::mt19937 rng(seed);
        std::uniform_real_distribution<float> u(0, 1);
        static const float pans[kMaxVoices] = {-0.45f, 0.45f, -0.8f, 0.8f};
        static const float delaysStudioMs[kMaxVoices] = {9, 14, 19, 24};
        static const float delaysLiveMs[kMaxVoices] = {1, 3, 5, 7};
        for (int v = 0; v < kMaxVoices; ++v) {
            Voice &vc = voices[v];
            vc.psola.setup(sr, &marker);
            vc.delay.init((int) (sr * 0.05f));
            vc.tone.setLowpass(10500, sr, 0.6f);
            vc.body.setPeak(250, sr, -1.5f, 0.8f);
            vc.bassTone.setLowpass(2600, sr, 0.6f);
            vc.env = 0; vc.idle = 0; vc.sleeping = false;
            vc.phase1 = u(rng) * 2 * kPi; vc.phase2 = u(rng) * 2 * kPi;
            vc.rate1 = 0.11f + 0.08f * u(rng); vc.rate2 = 0.31f + 0.15f * u(rng);
            vc.persona = (u(rng) - 0.5f) * 0.06f;   // ±3% formant: different "singers"
            vc.gainTrim = 0.88f + 0.12f * u(rng);
            vc.pan = pans[v];
            vc.baseDelayMs = (q == Studio ? delaysStudioMs : delaysLiveMs)[v];
            vc.lastShift = 0;
        }
        tmp.assign(kMaxBlock, 0.0f);
    }

    const PitchMarker *pitchMarker() const { return &marker; }
    int latencySamples() const { return marker.latency(); }
    int inputLatency() const { return marker.latency(); }

    // Adds the harmony voices into outL/outR. `in` is the conditioned vocal.
    void process(const float *in, int n, const VoiceTargets &t, const Controls &c, const NoteState &ns,
                 float *outL, float *outR) {
        if (n > kMaxBlock) {
            for (int o = 0; o < n; o += kMaxBlock)
                process(in + o, std::min(kMaxBlock, n - o), t, c, ns, outL + o, outR + o);
            return;
        }
        float period = (ns.voiced && ns.f0 > 0) ? sampleRate / ns.f0 : 0.0f;
        marker.push(in, n, period);

        const float humanize = clampf(c.humanize, 0, 1);
        const float width = clampf(c.width, 0, 1);
        // fast but click-free: gestures (finger count, fist) take effect within ~10–50 ms
        const float attack = smoothCoef(0.008f, sampleRate), release = smoothCoef(0.015f, sampleRate);
        const float level = clampf(c.harmonyLevel, 0, 1);
        const float dt = n / sampleRate;
        const bool chord = c.style < 0.5f;

        for (int v = 0; v < kMaxVoices; ++v) {
            Voice &vc = voices[v];
            bool active = t.active[v];
            if (!active && vc.env < 1e-4f) {
                vc.idle += n;
                if (vc.idle > sampleRate * 2.0f) { if (!vc.sleeping) { vc.sleeping = true; vc.psola.reset(); vc.delay.reset(); } continue; }
            } else {
                vc.idle = 0;
                vc.sleeping = false;
            }
            vc.phase1 += 2 * kPi * vc.rate1 * dt; vc.phase2 += 2 * kPi * vc.rate2 * dt;
            float driftCents = humanize * (chord ? 0.5f : 1.0f) * (4.0f * std::sin(vc.phase1) + 2.5f * std::sin(vc.phase2));
            float formant = 1.0f + vc.persona * humanize + (t.isBass[v] ? -0.04f : 0.0f);
            if (chord && t.targetMidi[v] > 0 && ns.voiced) {
                vc.psola.setPitch(0, midiToHz(t.targetMidi[v] + driftCents / 100.0f), formant);
                vc.lastShift = t.targetMidi[v] - ns.midi;
            } else {
                float sh = (chord && t.targetMidi[v] > 0) ? vc.lastShift : t.shift[v];
                vc.psola.setPitch(sh + driftCents / 100.0f, 0, formant);
                if (!chord) vc.lastShift = t.shift[v];
            }
            std::fill(tmp.begin(), tmp.begin() + n, 0.0f);
            vc.psola.render(tmp.data(), n, 1.0f);

            int delaySamples = std::max(1, (int) (vc.baseDelayMs * (0.3f + 0.7f * humanize) * 0.001f * sampleRate));
            float pan = t.isBass[v] ? 0.0f : vc.pan * width;
            float angle = (pan * 0.5f + 0.5f) * kPi * 0.5f;
            float gl = std::cos(angle), gr = std::sin(angle);
            float bassGain = t.isBass[v] ? 1.15f : 1.0f;
            for (int i = 0; i < n; ++i) {
                vc.env += ((active ? 1.0f : 0.0f) - vc.env) * (active ? attack : release);
                vc.delay.write(tmp[i]);
                float s = vc.delay.read(delaySamples);
                s = vc.body.process(vc.tone.process(s));
                if (t.isBass[v]) s = vc.bassTone.process(s);
                s *= vc.env * vc.gainTrim * level * kVoiceGain * bassGain;
                outL[i] += s * gl;
                outR[i] += s * gr;
            }
        }
    }

    float envelope(int v) const { return voices[v].env; }
    static constexpr int kMaxBlock = 4096;

private:
    struct Voice {
        PsolaVoice psola;
        DelayLine delay;
        Biquad tone, body, bassTone;
        float env = 0, pan = 0, persona = 0, gainTrim = 1, baseDelayMs = 5, lastShift = 0;
        float phase1 = 0, phase2 = 0, rate1 = 0.1f, rate2 = 0.3f, idle = 0;
        bool sleeping = false;
    };
    static constexpr float kVoiceGain = 0.6f;
    PitchMarker marker;
    Voice voices[kMaxVoices];
    float sampleRate = 48000;
    Quality quality = Realtime;
    std::vector<float> tmp;
};

// ───────────────────────────── Lead vocal FX ─────────────────────────────
// Pitch correction (PSOLA, formants preserved), doubler and stereo echo for the lead vocal.
class LeadProcessor {
public:
    // marker: the voice bank's pitch marker (same input). studio: delay the direct path by the
    // PSOLA latency so the lead lines up with the harmonies in the final render.
    void setup(float sr, bool studio, const PitchMarker *marker) {
        sampleRate = sr;
        isStudio = studio;
        corrector.setup(sr, marker);
        corrLatency = marker->latency();
        directDelay = studio ? corrLatency : 0;
        directLine.init(corrLatency + 16);
        dbl.init((int) (sr * 0.06f));
        echoL.init((int) (sr * 0.8f)); echoR.init((int) (sr * 0.8f));
        echoToneL.setLowpass(4200, sr); echoToneR.setLowpass(4200, sr);
        echoHpL.setHighpass(250, sr); echoHpR.setHighpass(250, sr);
        dblTone.setLowpass(8000, sr);
        tmp.assign(4096, 0.0f);
        corrEnv = 0; corrShift = 0; devFiltered = 0; phase = 0;
    }

    // monitorTuning (live only): false = the singer hears the dry lead with zero delay.
    void process(const float *in, int n, const NoteState &ns, const Controls &c, float *lead, float *L, float *R,
                 bool monitorTuning = true) {
        if (n > 4096) {
            for (int o = 0; o < n; o += 4096) process(in + o, std::min(4096, n - o), ns, c, lead + o, L + o, R + o, monitorTuning);
            return;
        }
        const float amount = clampf(c.correction, 0, 1);
        const float dt = n / sampleRate;
        if (ns.voiced && ns.hasNote) {
            float dev = ns.midi - (float) ns.note;
            float tau = 0.004f + clampf(c.retune, 0, 1) * 0.30f;
            devFiltered += (dev - devFiltered) * (1.0f - std::exp(-dt / tau));
            float target = -amount * devFiltered;
            corrShift += (target - corrShift) * (1.0f - std::exp(-dt / std::max(0.002f, tau * 0.5f)));
        } else {
            corrShift *= std::exp(-dt / 0.15f);
        }
        const bool want = amount > 0.001f && (isStudio || monitorTuning);
        std::fill(tmp.begin(), tmp.begin() + n, 0.0f);
        if (want || corrEnv > 1e-4f) {
            // hard tune: lock to the exact note when the style is fully "robotic"
            if (amount > 0.98f && c.retune < 0.02f && ns.voiced && ns.hasNote) corrector.setPitch(0, midiToHz((float) ns.note));
            else corrector.setPitch(corrShift, 0);
            corrector.render(tmp.data(), n, 1.0f);
        } else {
            corrector.reset();
        }
        const float xf = smoothCoef(0.03f, sampleRate);
        for (int i = 0; i < n; ++i) {
            directLine.write(in[i]);
            float direct = directDelay > 0 ? directLine.read(directDelay + 1) : in[i];
            corrEnv += ((want ? 1.0f : 0.0f) - corrEnv) * xf;
            lead[i] = direct + (tmp[i] - direct) * corrEnv;
        }

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
                L[i] += dblTone.process(dbl.readFrac(d1)) * dblAmt * 0.55f;
                R[i] += dbl.readFrac(d2) * dblAmt * 0.55f;
            }
            if (echoAmt > 0.001f) {
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

private:
    PsolaVoice corrector;
    DelayLine directLine, dbl, echoL, echoR;
    Biquad echoToneL, echoToneR, echoHpL, echoHpR, dblTone;
    std::vector<float> tmp;
    float sampleRate = 48000, corrEnv = 0, corrShift = 0, devFiltered = 0, phase = 0;
    int corrLatency = 0, directDelay = 0;
    bool isStudio = false;
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
