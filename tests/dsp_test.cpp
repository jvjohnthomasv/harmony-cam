// Host-side test of the harmony DSP: synthesises a sung melody, renders harmonies and measures them.
#include "../app/src/main/cpp/dsp/OfflineRenderer.h"
#include <chrono>
#include <cstdio>
using namespace hc;

static float midiToHz(float m) { return 440.f * std::pow(2.f, (m - 69) / 12.f); }

// glottal-ish source through 3 vowel formants
static std::vector<float> synthVoice(int sr, const std::vector<std::pair<float,float>> &notes, float detune) {
    std::vector<float> out;
    Biquad f1, f2, f3;
    f1.setPeak(700, sr, 14, 6); f2.setPeak(1220, sr, 12, 8); f3.setPeak(2600, sr, 9, 10);
    Biquad lp; lp.setLowpass(5000, sr);
    double phase = 0; float t = 0;
    for (auto &nd : notes) {
        int len = (int) (nd.second * sr);
        for (int i = 0; i < len; ++i, t += 1.f / sr) {
            float env = std::min(1.f, i / (0.03f * sr)) * std::min(1.f, (len - i) / (0.05f * sr));
            float m = nd.first + detune + 0.25f * std::sin(2 * kPi * 5.5f * t) * std::min(1.f, i / (0.3f * sr));
            if (nd.first <= 0) { out.push_back(0); continue; }
            phase += midiToHz(m) / sr;
            if (phase >= 1) phase -= 1;
            float g = (float) (phase < 0.6 ? std::sin(kPi * phase / 0.6) : 0.0); // pulse
            float s = g * g - 0.25f;
            s = lp.process(f3.process(f2.process(f1.process(s))));
            out.push_back(0.12f * s * env);
        }
    }
    return out;
}

static void writeMono(const std::string &p, const std::vector<float> &x, int sr) {
    WavWriter w; w.open(p, sr, 1); w.write(x.data(), x.size()); w.close();
}
static void writeAutomation(const std::string &p, const Controls &c, size_t frames, int hop) {
    FILE *f = fopen(p.c_str(), "wb"); AutomationHeader h; h.hopSamples = hop; fwrite(&h, sizeof h, 1, f);
    for (size_t i = 0; i < frames / hop + 1; ++i) fwrite(&c, sizeof c, 1, f);
    fclose(f);
}

// measure median pitch (midi) of a stereo file between t0..t1 seconds
static float measure(const std::string &p, float t0, float t1) {
    WavReader r; r.open(p); int sr = r.sampleRate();
    std::vector<float> x(r.frames()); r.readMono(x.data(), x.size());
    Decimator d; d.setup(sr, 3); PitchTracker pt; pt.setup(sr / 3.f);
    std::vector<float> dec(x.size() / 3 + 10); int nd = d.process(x.data(), (int) x.size(), dec.data());
    std::vector<float> ms;
    int hop = 160;
    for (int i = 0; i + hop <= nd; i += hop) {
        pt.push(dec.data() + i, hop);
        float tc = (i + hop - pt.analysisLag()) / (sr / 3.f);
        if (tc < t0 || tc > t1) continue;
        auto res = pt.analyze();
        if (res.voiced) ms.push_back(69 + 12 * std::log2(res.f0 / 440));
    }
    if (ms.empty()) return -1;
    std::sort(ms.begin(), ms.end());
    return ms[ms.size() / 2];
}

int main() {
    const int sr = 48000;
    // C major melody (C4 E4 G4 A4 F4 D4 C4), singer 0.2 semitone flat
    std::vector<std::pair<float,float>> mel = {{0,0.3f},{60,0.8f},{64,0.8f},{67,0.8f},{69,0.8f},{65,0.8f},{62,0.8f},{60,1.0f},{0,0.3f}};
    auto dry = synthVoice(sr, mel, -0.2f);
    writeMono("/tmp/hc_dry.wav", dry, sr);

    Controls c; c.keyRoot = 0; c.scale = 0; c.dryLevel = 0; c.reverb = 0; c.fx = 0; c.humanize = 0; c.voices = 1; c.tightness = 1;
    struct Case { const char *name; float height; int degree; } cases[] = {{"3rd above", 0.5f, +2}, {"3rd below", 0.25f, -2}, {"5th above(zone4 v2)", 0.95f, +4}};
    int failures = 0;
    for (auto &cs : cases) {
        Controls cc = c; cc.height = cs.height;
        if (cs.degree == 4) { cc.voices = 2; }
        writeAutomation("/tmp/hc_auto.bin", cc, dry.size(), 480);
        auto t0 = std::chrono::steady_clock::now();
        auto res = renderPerformance("/tmp/hc_dry.wav", "/tmp/hc_auto.bin", "/tmp/hc_out.wav", cc, nullptr);
        double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        printf("[%s] ok=%d frames=%llu (dry %zu) peak=%.3f render %.2fs for %.2fs audio\n", cs.name, res.ok,
               (unsigned long long) res.frames, dry.size(), res.peak, secs, dry.size() / (double) sr);
        float tcur = 0.3f;
        for (size_t k = 1; k + 1 < mel.size(); ++k) {
            int note = (int) mel[k].first;
            int expect = stepScale(note, cs.degree == 4 ? 2 : cs.degree, 0, kMajor); // voice0 only measured when 1 voice
            float m = measure("/tmp/hc_out.wav", tcur + 0.25f, tcur + mel[k].second - 0.1f);
            if (cs.degree == 4) { tcur += mel[k].second; continue; }
            bool ok = std::fabs(m - expect) < 0.35f;
            if (!ok) failures++;
            printf("   melody %d -> harmony expected %d measured %.2f %s\n", note, expect, m, ok ? "OK" : "FAIL");
            tcur += mel[k].second;
        }
    }
    // alignment check: dry only, compare onset of output vs input
    {
        Controls cc = c; cc.dryLevel = 1; cc.voices = 0;
        writeAutomation("/tmp/hc_auto.bin", cc, dry.size(), 480);
        renderPerformance("/tmp/hc_dry.wav", "/tmp/hc_auto.bin", "/tmp/hc_out.wav", cc, nullptr);
        WavReader r; r.open("/tmp/hc_out.wav"); std::vector<float> x(r.frames()); r.readMono(x.data(), x.size());
        auto onset = [](const std::vector<float> &v){ for (size_t i=0;i<v.size();++i) if (std::fabs(v[i])>0.01f) return (long) i; return -1L; };
        printf("dry onset in=%ld out=%ld (diff %ld samples)\n", onset(dry), onset(x), onset(x) - onset(dry));
    }
    // harmony alignment vs dry (onset of voice with humanize 0)
    {
        Controls cc = c; cc.voices = 1; cc.height = 0.5f;
        writeAutomation("/tmp/hc_auto.bin", cc, dry.size(), 480);
        renderPerformance("/tmp/hc_dry.wav", "/tmp/hc_auto.bin", "/tmp/hc_out.wav", cc, nullptr);
        WavReader r; r.open("/tmp/hc_out.wav"); std::vector<float> x(r.frames()); r.readMono(x.data(), x.size());
        long a=-1,b=-1; for (size_t i=0;i<x.size();++i) if (std::fabs(x[i])>0.01f){a=i;break;}
        for (size_t i=0;i<dry.size();++i) if (std::fabs(dry[i])>0.01f){b=i;break;}
        printf("harmony onset %ld vs dry %ld => lag %.1f ms (expected ~%.1f ms humanize floor)\n", a, b, (a-b)*1000.0/sr, 14*0.35);
    }
    // realtime CPU estimate: 4 voices, realtime config
    {
        VoiceBank bank; bank.setup(sr, VoiceBank::Realtime); HarmonyBrain brain; brain.setup(sr); MixBus bus; bus.setup(sr, false);
        Controls cc; cc.voices = 4; cc.keyRoot = 0;
        std::vector<float> L(192), R(192), buf(192);
        auto t0 = std::chrono::steady_clock::now();
        size_t n = 0;
        for (int rep = 0; rep < 3; ++rep)
        for (size_t o = 0; o + 192 <= dry.size(); o += 192) {
            std::copy(dry.begin() + o, dry.begin() + o + 192, buf.begin());
            bus.conditionDry(buf.data(), 192);
            brain.analyze(buf.data(), 192, cc, [](const float*, int){}, [](){});
            auto tg = brain.targets(brain.state(), cc);
            std::fill(L.begin(), L.end(), 0.f); std::fill(R.begin(), R.end(), 0.f);
            bank.process(buf.data(), 192, tg, cc, brain.state().f0, L.data(), R.data());
            bus.mix(buf.data(), L.data(), R.data(), 192, cc);
            n += 192;
        }
        double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        printf("realtime engine: %.1f%% of one core (x86 host), live latency %d samples (%.1f ms)\n",
               100.0 * secs / (n / (double) sr), bank.latencySamples(), bank.latencySamples() * 1000.0 / sr);
    }
    printf(failures ? "FAILURES: %d\n" : "ALL PITCH CHECKS PASSED\n", failures);
    return failures ? 1 : 0;
}
