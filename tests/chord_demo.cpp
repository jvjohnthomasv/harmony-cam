#include "../app/src/main/cpp/dsp/OfflineRenderer.h"
using namespace hc;
int main(){
  Controls c; c.keyRoot=8; c.scale=0; c.style=0; c.voices=4; c.height=0.5f; c.humanize=0.3f; c.reverb=0.35f; c.fx=0.2f; c.dryLevel=1;
  FILE*f=fopen("/tmp/hc_auto.bin","wb"); AutomationHeader h; fwrite(&h,sizeof h,1,f); for(int i=0;i<2000;++i) fwrite(&c,sizeof c,1,f); fclose(f);
  auto r=renderPerformance("/tmp/hc_sing.wav","/tmp/hc_auto.bin","/tmp/hc_chordmix.wav",c,nullptr);
  printf("ok=%d peak=%.2f\n", r.ok, r.peak);
}
