// Low-latency full-duplex engine: mic → harmony engine → headphones, plus raw-vocal capture
// (for the studio re-render), automation logging and a 16 kHz feed for lyric tracking.
#pragma once

#include <cstring>
#include <oboe/Oboe.h>
#include <oboe/FullDuplexStream.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

#include "../dsp/Harmonizer.h"
#include "../dsp/WavIO.h"
#include "../dsp/BackingTrack.h"
#include <memory>
#include <vector>

namespace hc {

enum MeterIndex {
    kMeterRmsDb = 0, kMeterF0, kMeterVoiced, kMeterNote, kMeterKeyRoot, kMeterScale,
    kMeterVoiceMidi0, kMeterVoiceMidi1, kMeterVoiceMidi2, kMeterVoiceMidi3,
    kMeterEnv0, kMeterEnv1, kMeterEnv2, kMeterEnv3,
    kMeterOutPeak, kMeterXruns, kMeterRecSeconds, kMeterMidi, kMeterInputPeak, kMeterInputChannels,
    kMeterTrackPos, kMeterTrackPlaying, kMeterTrackLength,
    kNumMeters
};

class AudioEngine : public oboe::FullDuplexStream, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    bool startEngine();
    void stopEngine();
    bool isRunning() const { return running.load(); }
    int getSampleRate() const { return sampleRate; }

    // Device routing (0 = system default). inputChannel: 0 = first channel (e.g. UM2 XLR mic),
    // 1 = second channel, 2 = mix of all channels. Takes effect on the next start.
    void setRouting(int inputDeviceId, int outputDeviceId, int inputChannel) {
        inDeviceId = inputDeviceId; outDeviceId = outputDeviceId; inChannelMode = inputChannel;
    }
    void setInputGainDb(float db) { inputGain.store(dbToGain(db)); }
    // false: hear your own voice dry (zero delay) even when pitch correction is on; the
    // correction is still applied to the recording.
    void setMonitorTuning(bool on) { monitorTuning.store(on); }

    void setControl(int index, float value);
    void setMonitorGain(float g) { monitorGain.store(g); }
    void setAsrEnabled(bool on) { asrEnabled.store(on); if (!on) asrRing.clear(); }

    bool startRecording(const std::string &wavPath, const std::string &automationPath);
    // returns {framesWritten, firstFrameMonotonicNanos}
    std::pair<int64_t, int64_t> stopRecording();
    // Monotonic time at which backing-track sample 0 reached the headphones for the last take (0 = none).
    int64_t lastTrackStartNs() const { return trackStartNs.load(); }

    // Backing track (karaoke). Loading resamples to the engine rate; returns false on failure.
    bool loadTrack(const std::string &path, int &keyRoot, int &keyScale, double &seconds);
    void unloadTrack();
    void setTrackPlaying(bool on) { if (track.load()) trackPlaying.store(on); }
    void seekTrack(double seconds) { trackPos.store((int64_t) (seconds * sampleRate)); }
    void setTrackGain(float g) { trackGain.store(g); }

    int readAsr(int16_t *dst, int maxSamples) { return (int) asrRing.read(dst, (size_t) maxSamples); }
    void getMeters(float *out, int n);

    // oboe callbacks
    oboe::DataCallbackResult onBothStreamsReady(const void *inputData, int numInputFrames,
                                                void *outputData, int numOutputFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    bool openStreams();
    void closeStreams();
    void prepareDsp();
    void writerLoop();
    static int64_t nowNanos();

    std::mutex lifecycle;
    std::shared_ptr<oboe::AudioStream> inStream, outStream;
    std::atomic<bool> running{false};
    std::atomic<bool> wantRunning{false};
    int sampleRate = 48000;
    int inDeviceId = 0, outDeviceId = 0, inChannelMode = 0, inChannels = 1;
    std::atomic<float> inputGain{1.0f};
    std::atomic<bool> monitorTuning{false};

    // DSP (audio thread only)
    HarmonyBrain brain;
    VoiceBank bank;
    MixBus bus;
    LeadProcessor lead;
    std::vector<float> leadBuf;
    std::vector<float> dry, cond, L, R, asrTmpF;
    std::vector<int16_t> asrTmp;

    std::atomic<float> controls[kNumControls];
    std::atomic<float> meters[kNumMeters];
    std::atomic<float> monitorGain{1.0f};
    std::atomic<bool> asrEnabled{false};
    std::atomic<int> xruns{0};

    // recording
    enum RecState { Idle = 0, Starting, Recording, Stopping, Stopped };
    std::atomic<int> recState{Idle};
    std::atomic<int64_t> recFirstFrameNs{0};
    std::atomic<int64_t> recFrames{0};
    int autoHop = 480, autoCounter = 0;
    SpscRing<float> recRing{1 << 19};       // ~10 s at 48 kHz
    SpscRing<Controls> autoRing{1 << 12};
    SpscRing<int16_t> asrRing{1 << 17};     // ~8 s at 16 kHz
    std::atomic<BackingTrack *> track{nullptr};
    std::vector<std::unique_ptr<BackingTrack>> trackStore; // owns tracks; old ones freed on next load
    std::mutex trackMutex;
    std::atomic<bool> trackPlaying{false};
    std::atomic<int64_t> trackPos{0};
    std::atomic<float> trackGain{0.7f};
    std::atomic<int64_t> trackStartNs{0};
    std::vector<float> TL, TR;

    std::thread writer;
    std::atomic<bool> writerRun{false};
    WavWriter wav;
    FILE *autoFile = nullptr;
};

} // namespace hc
