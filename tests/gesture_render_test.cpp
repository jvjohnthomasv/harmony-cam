#include "../app/src/main/cpp/dsp/OfflineRenderer.h"
using namespace hc;
int main(){
  const int sr=48000;
  // steady sung note (synthetic) for 4.5 s
  std::vector<float> x(sr*4.5,0.f); double ph=0;
  for(size_t i=0;i<x.size();++i){ double f=196*std::pow(2.0,0.2*std::sin(2*kPi*5.5*i/sr)/12); ph+=f/sr; double p=ph-std::floor(ph); x[i]=0.2f*(float)(std::sin(2*kPi*p)+0.5*std::sin(4*kPi*p)+0.3*std::sin(6*kPi*p)); }
  WavWriter w; w.open("/tmp/g_dry.wav",sr,1); w.write(x.data(),x.size()); w.close();
  // automation: harmonies on, fist (mute) at 2.000 s, open hand (1 voice) at 3.000 s
  FILE*f=fopen("/tmp/g_auto.bin","wb"); AutomationHeader h; h.numControls=kNumControls; fwrite(&h,sizeof h,1,f);
  for(int k=0;k<450;++k){ Controls c; c.keyRoot=7; c.style=0; c.voices=4; c.dryLevel=0; c.reverb=0; c.fx=0; c.humanize=0;
    double t=k*0.01; if(t>=2.0 && t<3.0) c.mute=1; if(t>=3.0) c.voices=1; fwrite(&c,sizeof c,1,f);} fclose(f);
  Controls fb; renderPerformance("/tmp/g_dry.wav","/tmp/g_auto.bin","/tmp/g_out.wav",fb,nullptr);
  WavReader r; r.open("/tmp/g_out.wav"); std::vector<float> y(r.frames()); r.readMono(y.data(),y.size());
  auto rmsAt=[&](double t){ size_t a=(size_t)(t*sr), n=sr/200; double e=0; for(size_t i=a;i<a+n&&i<y.size();++i) e+=y[i]*y[i]; return 10*std::log10(e/n+1e-12); };
  double on=rmsAt(1.5);
  double tOff=-1, tOn=-1;
  for(double t=1.9;t<2.5;t+=0.001) if(rmsAt(t) < on-30){ tOff=t; break; }
  for(double t=2.9;t<3.5;t+=0.001) if(rmsAt(t) > on-12){ tOn=t; break; }
  printf("harmonies level %.1f dB; fist at 2.000s -> silent at %.3fs (%.0f ms); open at 3.000s -> back at %.3fs (%.0f ms)\n", on, tOff, (tOff-2.0)*1000, tOn, (tOn-3.0)*1000);
  bool ok = tOff>0 && tOff-2.0 < 0.08 && tOn>0 && tOn-3.0 < 0.08;
  printf(ok? "GESTURE TIMING OK\n":"GESTURE TIMING FAIL\n"); return ok?0:1;
}
