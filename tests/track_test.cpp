#include "../app/src/main/cpp/dsp/OfflineRenderer.h"
#include <cstdio>
using namespace hc;
static void writeWav16(const char *p, const std::vector<float> &L, const std::vector<float> &R, int sr) {
    FILE *f = fopen(p, "wb"); uint32_t n = L.size(), db = n * 4;
    auto p32 = [&](uint32_t v){ fwrite(&v,4,1,f); }; auto p16 = [&](uint16_t v){ fwrite(&v,2,1,f); };
    fwrite("RIFF",1,4,f); p32(36+db); fwrite("WAVEfmt ",1,8,f); p32(16); p16(1); p16(2); p32(sr); p32(sr*4); p16(4); p16(16);
    fwrite("data",1,4,f); p32(db);
    for (uint32_t i=0;i<n;++i){ int16_t a=(int16_t)(L[i]*32767), b=(int16_t)(R[i]*32767); fwrite(&a,2,1,f); fwrite(&b,2,1,f);} fclose(f);
}
int main() {
    int fails = 0;
    // 1) resampler: 1 kHz sine at 44.1k → 48k, check frequency & THD+N
    {
        int sr = 44100; std::vector<float> L(sr*2), R(sr*2);
        for (int i=0;i<sr*2;++i){ L[i]=R[i]=0.5f*std::sin(2*kPi*1000.f*i/sr); }
        writeWav16("/tmp/t_sine.wav", L, R, sr);
        BackingTrack t; t.load("/tmp/t_sine.wav", 48000);
        // fit: correlate with ideal 1 kHz sine at 48k in middle second
        double num=0, den=0, err=0; long n0=24000, n1=72000;
        // estimate phase by least squares with sin & cos
        double ss=0,sc=0,cc=0,xs=0,xc=0;
        for (long i=n0;i<n1;++i){ float l,r; t.sample(i,l,r); double s=std::sin(2*kPi*1000.0*i/48000), c=std::cos(2*kPi*1000.0*i/48000); ss+=s*s; cc+=c*c; sc+=s*c; xs+=l*s; xc+=l*c; }
        double det=ss*cc-sc*sc, a=(xs*cc-xc*sc)/det, b=(xc*ss-xs*sc)/det;
        for (long i=n0;i<n1;++i){ float l,r; t.sample(i,l,r); double s=std::sin(2*kPi*1000.0*i/48000), c=std::cos(2*kPi*1000.0*i/48000); double e=l-(a*s+b*c); err+=e*e; num+= (a*s+b*c)*(a*s+b*c);} (void)den;
        double snr = 10*std::log10(num/err);
        printf("resampler 44.1k->48k: %zu frames (expect 96000), amplitude %.3f, SNR %.1f dB\n", t.numFrames(), std::sqrt(a*a+b*b), snr);
        if (std::abs((long)t.numFrames()-96000) > 2 || snr < 70) { fails++; printf("  FAIL\n"); }
    }
    // 2) key detection: I-V-vi-IV in G major (G D Em C), and A minor (Am F C G -> relative ambiguity, use Am Dm E Am)
    struct Prog { const char *name; std::vector<std::vector<int>> chords; int root, scale; };
    std::vector<Prog> progs = {
        {"G major", {{55,59,62,67},{50,54,57,62},{52,55,59,64},{48,52,55,60}}, 7, kMajor},
        {"A minor", {{57,60,64,69},{50,53,57,62},{52,56,59,64},{57,60,64,69}}, 9, kMinor},
        {"E♭ major", {{51,55,58,63},{46,50,53,58},{48,51,55,60},{44,48,51,56}}, 3, kMajor},
    };
    for (auto &p : progs) {
        int sr = 44100; std::vector<float> L, R;
        for (int rep=0; rep<3; ++rep) for (auto &ch : p.chords) for (int i=0;i<sr*2;++i) {
            float v=0; for (int m: ch){ double f=440*std::pow(2.0,(m-69)/12.0); for(int h=1;h<=4;++h) v+=0.06f/h*std::sin(2*kPi*f*h*i/sr);} 
            // add bass root an octave down
            double fb=440*std::pow(2.0,(ch[0]-12-69)/12.0); v+=0.1f*std::sin(2*kPi*fb*i/sr);
            L.push_back(v*0.6f); R.push_back(v*0.6f); }
        writeWav16("/tmp/t_prog.wav", L, R, sr);
        BackingTrack t; t.load("/tmp/t_prog.wav", 48000);
        // relative major/minor share the same notes -> identical harmonies; accept either
        int relRoot = p.scale==kMajor ? (p.root+9)%12 : (p.root+3)%12;
        bool ok = (t.keyRoot()==p.root && t.keyScale()==p.scale) || (t.keyRoot()==relRoot && t.keyScale()!=p.scale);
        printf("key of %s progression: root %d scale %d %s\n", p.name, t.keyRoot(), t.keyScale(), ok?"OK":"FAIL");
        if (!ok) fails++;
    }
    // 3) render with track offset: dry = click at 1.0 s; track = click at 0.0 s; offset 48000 → both clicks at 1.0 s in output
    {
        int sr=48000; std::vector<float> dry(sr*3,0.f); for(int i=0;i<200;++i) dry[sr+i]=0.5f*std::sin(2*kPi*500*i/sr);
        WavWriter w; w.open("/tmp/t_dry.wav", sr, 1); w.write(dry.data(), dry.size()); w.close();
        std::vector<float> L(sr*3,0.f), R(sr*3,0.f); for(int i=0;i<200;++i){ L[i]=R[i]=0.5f*std::sin(2*kPi*3000*i/sr);} 
        writeWav16("/tmp/t_track.wav", L, R, sr);
        Controls c; c.voices=0; c.reverb=0; c.fx=0; c.dryLevel=0; // only track audible
        auto res = renderPerformance("/tmp/t_dry.wav", "/nonexistent", "/tmp/t_out.wav", c, nullptr, "/tmp/t_track.wav", sr, 1.0f);
        WavReader rd; rd.open("/tmp/t_out.wav"); std::vector<float> x(rd.frames()); rd.readMono(x.data(), x.size());
        long on=-1; for(size_t i=0;i<x.size();++i) if(std::fabs(x[i])>0.05f){on=i;break;}
        printf("track click placed at %ld (expect 48000), render ok=%d\n", on, res.ok);
        if (std::labs(on-48000) > 3) fails++;
    }
    printf(fails? "FAILURES %d\n":"ALL TRACK TESTS PASSED\n", fails);
    return fails;
}
