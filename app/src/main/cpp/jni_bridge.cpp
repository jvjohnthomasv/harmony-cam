// JNI surface for com.harmonycam.app.audio.NativeEngine
#include <jni.h>
#include <memory>
#include <string>

#include "engine/AudioEngine.h"
#include "dsp/OfflineRenderer.h"

using namespace hc;

static std::unique_ptr<AudioEngine> gEngine;

static AudioEngine &engine() {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    return *gEngine;
}

static std::string toString(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

#define FN(name) Java_com_harmonycam_app_audio_NativeEngine_##name

extern "C" {

JNIEXPORT jboolean JNICALL FN(nStart)(JNIEnv *, jobject) { return engine().startEngine() ? JNI_TRUE : JNI_FALSE; }

JNIEXPORT void JNICALL FN(nStop)(JNIEnv *, jobject) { if (gEngine) gEngine->stopEngine(); }

JNIEXPORT jboolean JNICALL FN(nIsRunning)(JNIEnv *, jobject) { return gEngine && gEngine->isRunning() ? JNI_TRUE : JNI_FALSE; }

JNIEXPORT jint JNICALL FN(nSampleRate)(JNIEnv *, jobject) { return engine().getSampleRate(); }

JNIEXPORT void JNICALL FN(nSetControl)(JNIEnv *, jobject, jint index, jfloat value) { engine().setControl(index, value); }

JNIEXPORT void JNICALL FN(nSetRouting)(JNIEnv *, jobject, jint inDev, jint outDev, jint inChannel) {
    engine().setRouting(inDev, outDev, inChannel);
}

JNIEXPORT void JNICALL FN(nSetInputGainDb)(JNIEnv *, jobject, jfloat db) { engine().setInputGainDb(db); }

JNIEXPORT void JNICALL FN(nSetMonitorGain)(JNIEnv *, jobject, jfloat gain) { engine().setMonitorGain(gain); }

JNIEXPORT void JNICALL FN(nSetAsrEnabled)(JNIEnv *, jobject, jboolean on) { engine().setAsrEnabled(on == JNI_TRUE); }

JNIEXPORT jint JNICALL FN(nReadAsr)(JNIEnv *env, jobject, jshortArray buf) {
    jsize n = env->GetArrayLength(buf);
    jshort *p = env->GetShortArrayElements(buf, nullptr);
    int got = engine().readAsr(reinterpret_cast<int16_t *>(p), n);
    env->ReleaseShortArrayElements(buf, p, 0);
    return got;
}

JNIEXPORT void JNICALL FN(nGetMeters)(JNIEnv *env, jobject, jfloatArray out) {
    jsize n = env->GetArrayLength(out);
    float tmp[kNumMeters];
    engine().getMeters(tmp, kNumMeters);
    env->SetFloatArrayRegion(out, 0, std::min<jsize>(n, kNumMeters), tmp);
}

JNIEXPORT jboolean JNICALL FN(nStartRecording)(JNIEnv *env, jobject, jstring wav, jstring automation) {
    return engine().startRecording(toString(env, wav), toString(env, automation)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL FN(nStopRecording)(JNIEnv *env, jobject) {
    auto r = engine().stopRecording();
    jlong vals[4] = {r.first, r.second, engine().getSampleRate(), engine().lastTrackStartNs()};
    jlongArray arr = env->NewLongArray(4);
    env->SetLongArrayRegion(arr, 0, 4, vals);
    return arr;
}

// Returns [seconds, keyRoot, keyScale] or null on failure.
JNIEXPORT jfloatArray JNICALL FN(nLoadTrack)(JNIEnv *env, jobject, jstring path) {
    int root = 0, scale = 0; double secs = 0;
    if (!engine().loadTrack(toString(env, path), root, scale, secs)) return nullptr;
    jfloat vals[3] = {(jfloat) secs, (jfloat) root, (jfloat) scale};
    jfloatArray arr = env->NewFloatArray(3);
    env->SetFloatArrayRegion(arr, 0, 3, vals);
    return arr;
}

JNIEXPORT void JNICALL FN(nUnloadTrack)(JNIEnv *, jobject) { engine().unloadTrack(); }
JNIEXPORT void JNICALL FN(nSetTrackPlaying)(JNIEnv *, jobject, jboolean on) { engine().setTrackPlaying(on == JNI_TRUE); }
JNIEXPORT void JNICALL FN(nSeekTrack)(JNIEnv *, jobject, jfloat seconds) { engine().seekTrack(seconds); }
JNIEXPORT void JNICALL FN(nSetTrackGain)(JNIEnv *, jobject, jfloat gain) { engine().setTrackGain(gain); }

// Renders the final mix. listener: object with `boolean onProgress(float)`; returning false cancels.
// Returns null on success, or an error message.
JNIEXPORT jstring JNICALL FN(nRender)(JNIEnv *env, jobject, jstring dryWav, jstring automation, jstring outWav,
                                      jfloatArray fallbackControls, jobject listener,
                                      jstring trackPath, jlong trackOffsetSamples, jfloat trackGain) {
    Controls fb;
    if (fallbackControls && env->GetArrayLength(fallbackControls) >= kNumControls)
        env->GetFloatArrayRegion(fallbackControls, 0, kNumControls, reinterpret_cast<float *>(&fb));
    jmethodID onProgress = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        onProgress = env->GetMethodID(cls, "onProgress", "(F)Z");
    }
    auto progress = [&](float p) -> bool {
        if (!listener || !onProgress) return true;
        jboolean keep = env->CallBooleanMethod(listener, onProgress, p);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return true; }
        return keep == JNI_TRUE;
    };
    RenderResult res = renderPerformance(toString(env, dryWav), toString(env, automation), toString(env, outWav), fb, progress,
                                         toString(env, trackPath), (long) trackOffsetSamples, trackGain);
    if (res.ok) return nullptr;
    return env->NewStringUTF(res.error.c_str());
}

} // extern "C"
