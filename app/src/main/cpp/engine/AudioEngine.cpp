#include "AudioEngine.h"

#include <android/log.h>
#include <time.h>

#define LOG_TAG "HarmonyEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace hc {

AudioEngine::AudioEngine() {
    Controls def;
    const float *d = reinterpret_cast<const float *>(&def);
    for (int i = 0; i < kNumControls; ++i) controls[i].store(d[i]);
    for (auto &m : meters) m.store(0.0f);
    meters[kMeterKeyRoot].store(-1.0f);
}

AudioEngine::~AudioEngine() {
    stopRecording();
    stopEngine();
}

int64_t AudioEngine::nowNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void AudioEngine::setControl(int index, float value) {
    if (index >= 0 && index < kNumControls) controls[index].store(value);
}

void AudioEngine::getMeters(float *out, int n) {
    for (int i = 0; i < n && i < kNumMeters; ++i) out[i] = meters[i].load(std::memory_order_relaxed);
}

void AudioEngine::prepareDsp() {
    brain.setup((float) sampleRate);
    bank.setup((float) sampleRate, VoiceBank::Realtime);
    bus.setup((float) sampleRate, false);
    lead.setup((float) sampleRate, false);
    const int maxFrames = 8192;
    leadBuf.assign(maxFrames, 0.0f);
    dry.assign(maxFrames, 0.0f); cond.assign(maxFrames, 0.0f);
    L.assign(maxFrames, 0.0f); R.assign(maxFrames, 0.0f);
    TL.assign(maxFrames, 0.0f); TR.assign(maxFrames, 0.0f);
    asrTmp.assign(maxFrames, 0);
    autoHop = std::max(1, sampleRate / 100);
}

bool AudioEngine::openStreams() {
    oboe::AudioStreamBuilder out;
    out.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(48000)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (outDeviceId > 0) out.setDeviceId(outDeviceId);
    oboe::Result r = out.openStream(outStream);
    if (r != oboe::Result::OK && outDeviceId > 0) {
        LOGE("output on device %d failed (%s), using default route", outDeviceId, oboe::convertToText(r));
        out.setDeviceId(oboe::kUnspecified);
        r = out.openStream(outStream);
    }
    if (r != oboe::Result::OK) { LOGE("output open failed: %s", oboe::convertToText(r)); return false; }
    sampleRate = outStream->getSampleRate();
    // two bursts of buffering: low latency without glitches on most devices
    outStream->setBufferSizeInFrames(outStream->getFramesPerBurst() * 2);

    // A USB interface like the UM2 delivers its XLR mic on channel 1 of a stereo stream; open
    // stereo and pick the channel, rather than letting Android average both inputs together.
    const int wantChannels = (inDeviceId > 0 && inChannelMode != 2) || inChannelMode == 1 ? 2 : 1;
    auto openInput = [&](oboe::InputPreset preset, int channels, int deviceId) {
        oboe::AudioStreamBuilder in;
        in.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(channels)
            ->setSampleRate(sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setInputPreset(preset)
            ->setBufferCapacityInFrames(outStream->getBufferCapacityInFrames() * 2);
        if (deviceId > 0) in.setDeviceId(deviceId);
        return in.openStream(inStream);
    };
    oboe::Result r2 = oboe::Result::ErrorInternal;
    const oboe::InputPreset presets[3] = {oboe::InputPreset::VoicePerformance, oboe::InputPreset::Unprocessed, oboe::InputPreset::Generic};
    for (int attempt = 0; attempt < 4 && r2 != oboe::Result::OK; ++attempt) {
        int dev = attempt < 3 ? inDeviceId : 0;            // last resort: default mic
        int ch = attempt < 2 ? wantChannels : 1;
        r2 = openInput(presets[std::min(attempt, 2)], ch, dev);
    }
    r = r2;
    // VoicePerformance = low latency with no AGC / noise suppression: the cleanest capture for singing
    if (r != oboe::Result::OK) {
        LOGE("input open failed: %s", oboe::convertToText(r));
        outStream->close(); outStream.reset();
        return false;
    }
    inChannels = std::max(1, inStream->getChannelCount());
    meters[kMeterInputChannels].store((float) inChannels);
    setSharedInputStream(inStream);
    setSharedOutputStream(outStream);
    LOGI("streams open: %d Hz, burst %d, in-preset %d, in-dev %d (%d ch), out-dev %d", sampleRate,
         outStream->getFramesPerBurst(), (int) inStream->getInputPreset(), inStream->getDeviceId(), inChannels,
         outStream->getDeviceId());
    return true;
}

void AudioEngine::closeStreams() {
    if (outStream) { outStream->stop(); outStream->close(); }
    if (inStream) { inStream->stop(); inStream->close(); }
    outStream.reset();
    inStream.reset();
}

bool AudioEngine::startEngine() {
    wantRunning.store(true);
    std::lock_guard<std::mutex> lock(lifecycle);
    if (running.load()) return true;
    if (!openStreams()) return false;
    prepareDsp();
    if (FullDuplexStream::start() != oboe::Result::OK) {
        LOGE("start failed");
        closeStreams();
        return false;
    }
    running.store(true);
    return true;
}

void AudioEngine::stopEngine() {
    wantRunning.store(false);
    std::lock_guard<std::mutex> lock(lifecycle);
    if (!running.load()) return;
    running.store(false);
    closeStreams();
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    // Typically a device change (headphones plugged/unplugged). Re-open on the new route.
    LOGI("stream error %s, restarting", oboe::convertToText(error));
    std::thread([this]() {
        {
            std::lock_guard<std::mutex> lock(lifecycle);
            running.store(false);
            closeStreams();
        }
        if (wantRunning.load()) startEngine();
    }).detach();
}

oboe::DataCallbackResult AudioEngine::onBothStreamsReady(const void *inputData, int numInputFrames,
                                                         void *outputData, int numOutputFrames) {
    const int n = std::min(numOutputFrames, (int) dry.size());
    const float *in = static_cast<const float *>(inputData);
    float *out = static_cast<float *>(outputData);

    const float gain = inputGain.load(std::memory_order_relaxed);
    const int ch = inChannels;
    float inPeak = 0;
    for (int i = 0; i < n; ++i) {
        float v = 0;
        if (i < numInputFrames) {
            const float *frame = in + (size_t) i * ch;
            if (ch == 1) v = frame[0];
            else if (inChannelMode == 2) { for (int k = 0; k < ch; ++k) v += frame[k]; v /= ch; }
            else v = frame[std::min(inChannelMode, ch - 1)];
        }
        inPeak = std::max(inPeak, std::fabs(v));   // pre-gain: shows how hot the interface's gain knob is
        dry[i] = v * gain;
    }
    if (numInputFrames < n) xruns.fetch_add(1);
    {
        float prev = meters[kMeterInputPeak].load(std::memory_order_relaxed);
        meters[kMeterInputPeak].store(std::max(inPeak, prev * 0.9f), std::memory_order_relaxed);
    }

    Controls c;
    float *cf = reinterpret_cast<float *>(&c);
    for (int i = 0; i < kNumControls; ++i) cf[i] = controls[i].load(std::memory_order_relaxed);

    // ── recording of the raw vocal + automation
    int rs = recState.load(std::memory_order_acquire);
    if (rs == Starting) {
        int64_t first = 0;
        if (inStream) {
            auto ts = inStream->getTimestamp(CLOCK_MONOTONIC);
            int64_t framesRead = inStream->getFramesRead();
            int64_t chunkStart = framesRead - numInputFrames;
            if (ts) first = ts.value().timestamp + (chunkStart - ts.value().position) * 1000000000LL / sampleRate;
        }
        if (first <= 0) first = nowNanos() - (int64_t) (n + outStream->getFramesPerBurst() * 2) * 1000000000LL / sampleRate;
        recFirstFrameNs.store(first);
        recFrames.store(0);
        autoCounter = 0;
        // The karaoke track restarts with the take, sample-accurately in this callback.
        trackStartNs.store(0);
        if (track.load(std::memory_order_acquire)) {
            trackPos.store(0);
            trackPlaying.store(true);
            int64_t when = 0;
            if (outStream) {
                auto ts = outStream->getTimestamp(CLOCK_MONOTONIC);
                int64_t written = outStream->getFramesWritten();
                if (ts) when = ts.value().timestamp + (written - ts.value().position) * 1000000000LL / sampleRate;
                if (when <= 0) {
                    auto lat = outStream->calculateLatencyMillis();
                    when = nowNanos() + (int64_t) ((lat ? lat.value() : 20.0) * 1e6);
                }
            }
            trackStartNs.store(when);
        }
        recState.store(Recording, std::memory_order_release);
        rs = Recording;
    } else if (rs == Stopping) {
        trackPlaying.store(false);
        recState.store(Stopped, std::memory_order_release);
        rs = Stopped;
    }
    if (rs == Recording) {
        recRing.write(dry.data(), (size_t) n);
        for (int i = 0; i < n; ++i) {
            if (autoCounter == 0) autoRing.write(&c, 1);
            if (++autoCounter >= autoHop) autoCounter = 0;
        }
        int64_t total = recFrames.fetch_add(n) + n;
        meters[kMeterRecSeconds].store((float) total / sampleRate, std::memory_order_relaxed);
    }

    // ── harmony processing
    const bool asr = asrEnabled.load(std::memory_order_relaxed);
    auto feedAsr = [&](const float *d, int m) {
        if (!asr) return;
        for (int i = 0; i < m; ++i) asrTmp[i] = (int16_t) (clampf(d[i] * 1.6f, -1.0f, 1.0f) * 32767.0f);
        asrRing.write(asrTmp.data(), (size_t) m);
    };
    BackingTrack *bt = track.load(std::memory_order_acquire);
    const bool withTrack = bt && trackPlaying.load(std::memory_order_relaxed);
    std::fill(TL.begin(), TL.begin() + n, 0.0f);
    std::fill(TR.begin(), TR.begin() + n, 0.0f);
    if (withTrack) {
        int64_t pos = trackPos.load(std::memory_order_relaxed);
        if (pos >= 0) bt->mixInto((size_t) pos, TL.data(), TR.data(), n, trackGain.load(std::memory_order_relaxed));
        pos += n;
        trackPos.store(pos, std::memory_order_relaxed);
        if (pos >= (int64_t) bt->numFrames()) trackPlaying.store(false);
    }

    VoiceTargets tg;
    const bool bypass = c.bypass > 0.5f;
    if (bypass) {
        // "all effects off": plain mic (+ karaoke track); keep analysing for meters & lyric tracking
        brain.analyze(dry.data(), n, c, feedAsr, []() {});
        for (int i = 0; i < n; ++i) {
            L[i] = clampf(dry[i] + TL[i], -1.0f, 1.0f);
            R[i] = clampf(dry[i] + TR[i], -1.0f, 1.0f);
        }
        for (int v = 0; v < kMaxVoices; ++v) { tg.active[v] = false; tg.midi[v] = 0; }
    } else {
        std::copy(dry.begin(), dry.begin() + n, cond.begin());
        bus.conditionDry(cond.data(), n);
        brain.analyze(cond.data(), n, c, feedAsr, []() {});
        const NoteState &ns0 = brain.state();
        tg = brain.targets(ns0, c);
        std::fill(L.begin(), L.begin() + n, 0.0f);
        std::fill(R.begin(), R.begin() + n, 0.0f);
        bank.process(cond.data(), n, tg, c, ns0.voiced ? ns0.f0 : 0.0f, L.data(), R.data());
        lead.process(cond.data(), n, ns0, c, leadBuf.data(), L.data(), R.data());
        bus.mix(leadBuf.data(), L.data(), R.data(), n, c, TL.data(), TR.data());
    }
    const NoteState &ns = brain.state();

    const float g = monitorGain.load(std::memory_order_relaxed);
    float peak = 0;
    for (int i = 0; i < n; ++i) {
        out[2 * i] = L[i] * g;
        out[2 * i + 1] = R[i] * g;
        peak = std::max(peak, std::max(std::fabs(L[i]), std::fabs(R[i])));
    }
    for (int i = n; i < numOutputFrames; ++i) { out[2 * i] = 0; out[2 * i + 1] = 0; }

    // ── meters for UI / visuals
    meters[kMeterRmsDb].store(ns.rmsDb, std::memory_order_relaxed);
    meters[kMeterF0].store(ns.voiced ? ns.f0 : 0.0f, std::memory_order_relaxed);
    meters[kMeterVoiced].store(ns.voiced ? 1.0f : 0.0f, std::memory_order_relaxed);
    meters[kMeterNote].store(ns.hasNote ? (float) ns.note : 0.0f, std::memory_order_relaxed);
    meters[kMeterMidi].store(ns.voiced ? ns.midi : 0.0f, std::memory_order_relaxed);
    meters[kMeterKeyRoot].store((float) ns.keyRoot, std::memory_order_relaxed);
    meters[kMeterScale].store((float) ns.scale, std::memory_order_relaxed);
    for (int v = 0; v < kMaxVoices; ++v) {
        meters[kMeterVoiceMidi0 + v].store((float) tg.midi[v], std::memory_order_relaxed);
        meters[kMeterEnv0 + v].store(bank.envelope(v), std::memory_order_relaxed);
    }
    meters[kMeterOutPeak].store(peak, std::memory_order_relaxed);
    meters[kMeterXruns].store((float) xruns.load(), std::memory_order_relaxed);
    if (bt) {
        meters[kMeterTrackPos].store((float) trackPos.load(std::memory_order_relaxed) / sampleRate, std::memory_order_relaxed);
        meters[kMeterTrackPlaying].store(trackPlaying.load(std::memory_order_relaxed) ? 1.0f : 0.0f, std::memory_order_relaxed);
        meters[kMeterTrackLength].store((float) bt->numFrames() / sampleRate, std::memory_order_relaxed);
    } else {
        meters[kMeterTrackPlaying].store(0.0f, std::memory_order_relaxed);
        meters[kMeterTrackLength].store(0.0f, std::memory_order_relaxed);
    }
    return oboe::DataCallbackResult::Continue;
}

// ───────────────────────── recording ─────────────────────────

bool AudioEngine::startRecording(const std::string &wavPath, const std::string &automationPath) {
    stopRecording();
    if (!running.load()) return false;
    recRing.clear();
    autoRing.clear();
    if (!wav.open(wavPath, sampleRate, 1)) return false;
    autoFile = std::fopen(automationPath.c_str(), "wb");
    if (!autoFile) { wav.close(); return false; }
    AutomationHeader h;
    h.sampleRate = sampleRate;
    h.hopSamples = std::max(1, sampleRate / 100);
    h.numControls = kNumControls;
    std::fwrite(&h, sizeof(h), 1, autoFile);
    writerRun.store(true);
    writer = std::thread(&AudioEngine::writerLoop, this);
    recState.store(Starting, std::memory_order_release);
    return true;
}

void AudioEngine::writerLoop() {
    std::vector<float> buf(8192);
    std::vector<Controls> cbuf(256);
    auto drain = [&]() {
        size_t got;
        while ((got = recRing.read(buf.data(), buf.size())) > 0) wav.write(buf.data(), got);
        while ((got = autoRing.read(cbuf.data(), cbuf.size())) > 0) std::fwrite(cbuf.data(), sizeof(Controls), got, autoFile);
    };
    while (writerRun.load()) {
        drain();
        std::this_thread::sleep_for(std::chrono::milliseconds(15));
    }
    drain();
}

std::pair<int64_t, int64_t> AudioEngine::stopRecording() {
    int s = recState.load();
    if (s == Idle) return {0, 0};
    if (s == Starting || s == Recording) {
        recState.store(Stopping);
        // wait for the audio thread to acknowledge (max ~300 ms; if the stream died just proceed)
        for (int i = 0; i < 60 && recState.load() != Stopped && running.load(); ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    writerRun.store(false);
    if (writer.joinable()) writer.join();
    wav.close();
    if (autoFile) { std::fclose(autoFile); autoFile = nullptr; }
    recState.store(Idle);
    return {recFrames.load(), recFirstFrameNs.load()};
}

} // namespace hc

namespace hc {

bool AudioEngine::loadTrack(const std::string &path, int &keyRoot, int &keyScale, double &seconds) {
    auto t = std::make_unique<BackingTrack>();
    if (!t->load(path, sampleRate)) return false;
    keyRoot = t->keyRoot(); keyScale = t->keyScale();
    seconds = (double) t->numFrames() / sampleRate;
    std::lock_guard<std::mutex> lock(trackMutex);
    trackPlaying.store(false);
    trackPos.store(0);
    track.store(t.get(), std::memory_order_release);
    // keep the previous track alive (the audio thread may still be reading it); free older ones
    while (trackStore.size() > 1) trackStore.erase(trackStore.begin());
    trackStore.push_back(std::move(t));
    return true;
}

void AudioEngine::unloadTrack() {
    std::lock_guard<std::mutex> lock(trackMutex);
    trackPlaying.store(false);
    track.store(nullptr, std::memory_order_release);
    // freed lazily on the next load, after the audio thread has long stopped touching it
}

} // namespace hc
