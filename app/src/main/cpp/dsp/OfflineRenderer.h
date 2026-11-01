// Studio-quality re-render of a performance: takes the raw dry vocal plus the recorded
// gesture/settings automation and renders the final stereo mix with long-window,
// latency-compensated harmony voices (better than what's possible live).
#pragma once

#include "Harmonizer.h"
#include "WavIO.h"

#include <functional>

namespace hc {

struct RenderResult {
    bool ok = false;
    std::string error;
    uint64_t frames = 0;
    float peak = 0;
};

inline std::vector<Controls> loadAutomation(const std::string &path, int &hopSamples) {
    std::vector<Controls> frames;
    hopSamples = 480;
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return frames;
    AutomationHeader h;
    if (std::fread(&h, sizeof(h), 1, f) == 1 && std::memcmp(h.magic, "HCA1", 4) == 0 && h.numControls == kNumControls) {
        hopSamples = std::max(1, (int) h.hopSamples);
        Controls c;
        while (std::fread(&c, sizeof(Controls), 1, f) == 1) frames.push_back(c);
    }
    std::fclose(f);
    return frames;
}

inline RenderResult renderPerformance(const std::string &dryWavPath, const std::string &automationPath,
                                      const std::string &outWavPath, const Controls &fallback,
                                      const std::function<bool(float)> &progress) {
    RenderResult res;
    WavReader reader;
    if (!reader.open(dryWavPath)) { res.error = "cannot read vocal recording"; return res; }
    const int sr = reader.sampleRate();
    const size_t total = reader.frames();
    if (total == 0) { res.error = "recording is empty"; return res; }

    std::vector<float> dry(total);
    reader.readMono(dry.data(), total);

    int autoHop = 480;
    std::vector<Controls> automation = loadAutomation(automationPath, autoHop);
    auto controlsAt = [&](long sample) -> const Controls & {
        if (automation.empty()) return fallback;
        long idx = std::max(0L, sample) / autoHop;
        if (idx >= (long) automation.size()) idx = (long) automation.size() - 1;
        return automation[(size_t) idx];
    };

    // Lead-vocal conditioning (applied once, used for analysis, harmonies and the mix).
    MixBus bus;
    bus.setup((float) sr, true);
    {
        const int chunk = 1024;
        for (size_t o = 0; o < total; o += chunk) bus.conditionDry(dry.data() + o, (int) std::min<size_t>(chunk, total - o));
    }

    // ── Pass 1: analysis (pitch + key + note decisions) over the whole take.
    HarmonyBrain brain;
    brain.setup((float) sr);
    const int hop = brain.hopInputSamples();
    const int lag = brain.analysisLagInputSamples();
    std::vector<NoteState> track;
    track.reserve(total / hop + 2);
    {
        const int chunk = hop;
        for (size_t o = 0; o < total; o += chunk) {
            int n = (int) std::min<size_t>(chunk, total - o);
            brain.analyze(dry.data() + o, n, controlsAt((long) o),
                          [](const float *, int) {},
                          [&]() { track.push_back(brain.state()); });
            if (progress && (o / chunk) % 200 == 0 && !progress(0.25f * o / total)) { res.error = "cancelled"; return res; }
        }
    }
    auto stateAt = [&](long sample) -> NoteState {
        if (track.empty()) return NoteState{};
        long idx = (sample + lag) / hop - 1;
        idx = std::max(0L, std::min(idx, (long) track.size() - 1));
        return track[(size_t) idx];
    };

    // ── Pass 2: harmony voices + mix.
    VoiceBank bank;
    bank.setup((float) sr, VoiceBank::Studio);
    HarmonyBrain targetBrain; // only used for its zone hysteresis in targets()
    targetBrain.setup((float) sr);
    const int L = bank.latencySamples();
    const int inLat = bank.inputLatency();

    WavWriter writer;
    if (!writer.open(outWavPath, sr, 2)) { res.error = "cannot write output"; return res; }

    const int block = 256;
    std::vector<float> in(block), hl(block), hr(block), dryAligned(block), inter(block * 2);
    DelayLine dryDelay;
    dryDelay.init(L + block + 8);
    const size_t totalOut = total + L;
    size_t produced = 0;
    for (size_t t = 0; t < totalOut; t += block) {
        int n = (int) std::min<size_t>(block, totalOut - t);
        for (int i = 0; i < n; ++i) in[i] = (t + i < total) ? dry[t + i] : 0.0f;
        long procTime = (long) t - inLat + n / 2;
        NoteState ns = stateAt(procTime);
        const Controls &c = controlsAt(procTime);
        VoiceTargets tg = targetBrain.targets(ns, c);
        std::fill(hl.begin(), hl.end(), 0.0f);
        std::fill(hr.begin(), hr.end(), 0.0f);
        bank.process(in.data(), n, tg, c, ns.voiced ? ns.f0 : 0.0f, hl.data(), hr.data());
        for (int i = 0; i < n; ++i) { dryDelay.write(in[i]); dryAligned[i] = dryDelay.read(L + 1); }
        // mix uses the controls of the audio actually being heard at this output position
        const Controls &cm = controlsAt((long) t - L);
        bus.mix(dryAligned.data(), hl.data(), hr.data(), n, cm);

        int skip = 0;
        if (t < (size_t) L) skip = (int) std::min<size_t>(n, L - t);
        int count = n - skip;
        for (int i = 0; i < count; ++i) {
            float l = hl[skip + i], r = hr[skip + i];
            inter[2 * i] = l; inter[2 * i + 1] = r;
            res.peak = std::max(res.peak, std::max(std::fabs(l), std::fabs(r)));
        }
        if (count > 0) { writer.write(inter.data(), count); produced += count; }
        if (progress && (t / block) % 400 == 0 && !progress(0.25f + 0.75f * t / totalOut)) {
            writer.close(); res.error = "cancelled"; return res;
        }
    }
    writer.close();
    if (progress) progress(1.0f);
    res.ok = true;
    res.frames = produced;
    return res;
}

} // namespace hc
