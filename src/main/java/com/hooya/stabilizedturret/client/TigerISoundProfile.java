package com.hooya.stabilizedturret.client;

/** Tiger I exterior event only. XML group labels differ from the asset suffixes. */
public final class TigerISoundProfile {
    public static final String[] LOOPS = {"idle_load", "slow_not_load", "med_not_load", "fast_not_load",
            "slow_load", "med_load", "fast_load"};
    public static final float[] ROOT = {14.5F,25,50,75,25,55,100};
    public static final float[] MINIMUM = {.955000043F,.800000012F,.600000024F,.699999988F,
            .800000012F,.539999962F,.579999983F};
    public static final float[] STATIC_DB = {8,2,1,4,-4,-2,0};
    public static final float[] START = {0,1.43498F,33.0942F,67.629F,1.43498F,25,60};
    public static final float[] END = {16.5919018F,55.5156822F,83.6772079F,100,40,75,100};
    public static final float RELEASE_SECONDS = .35F;
    public static float clamp(float v,float lo,float hi) {
        return Float.isFinite(v) ? Math.max(lo,Math.min(hi,v)) : lo;
    }
    public static float normalizedRpm(float rpm,float max) {
        return max>0 && Float.isFinite(max) ? clamp(Math.abs(rpm)/max*100,0,100) : 0;
    }
    public static float nativePitch(int layer,float rpm) {
        return MINIMUM[layer]+(1-MINIMUM[layer])*rpm/ROOT[layer];
    }
    public static float pitch(int layer,float rpm,float unusedLoad) {
        // Muted sources stay ready at the nearest audible boundary. All audible rates fit MC .5..2.
        return nativePitch(layer,clamp(rpm,START[layer],END[layer]));
    }
    public static float[] rawWeights(float rpm) {
        float r=clamp(rpm,0,100);
        return new float[]{
            TigerIFmodFade.value(0,(r-2)/(16.5919F-2)),
            TigerIFmodFade.value(1,(r-1.43498F)/(15-1.43498F))*TigerIFmodFade.value(2,(r-33.0942F)/(55.5156822F-33.0942F)),
            TigerIFmodFade.value(3,(r-33.0942F)/(55.5156822F-33.0942F))*TigerIFmodFade.value(4,(r-67.629F)/(83.6772079F-67.629F)),
            TigerIFmodFade.value(5,(r-67.629F)/(83.6772079F-67.629F)),
            TigerIFmodFade.value(6,(r-1.43498F)/(15-1.43498F))*TigerIFmodFade.value(7,(r-25)/15),
            TigerIFmodFade.value(8,(r-25)/15)*TigerIFmodFade.value(9,(r-60)/15),
            TigerIFmodFade.value(10,(r-60)/15)};
    }
    public static float[] weights(float rpm) {
        float[] w=rawWeights(rpm);
        // Equal-power correction within each family; keep the loaded family's idle entrance intact.
        normalize(w,0,4,1);
        float loadedEntrance=TigerIFmodFade.value(6,(clamp(rpm,0,100)-1.43498F)/(15-1.43498F));
        normalize(w,4,7,loadedEntrance);
        return w;
    }
    public static float[] referenceWeights(float rpm) {
        float[] result=new float[7];
        for(int i=0;i<7;i++)result[i]=TigerIReferenceFade.value(i,rpm);
        return result;
    }
    public static float climbSemitones(float climb) {
        return 6*clamp((climb-.004237288F)/(.995762712F-.004237288F),0,1);
    }
    public static float referencePitch(int layer,float rpm,float climb) {
        return pitch(layer,rpm,0)*(layer>=4?(float)Math.pow(2,climbSemitones(climb)/12):1);
    }
    private static void normalize(float[] w,int begin,int end,float target) {
        float power=0;for(int i=begin;i<end;i++) power+=w[i]*w[i];
        float gain=power>0?target/(float)Math.sqrt(power):0;
        for(int i=begin;i<end;i++)w[i]*=gain;
    }
    public static float startupEnvelope(float elapsed,float duration) {
        float t=clamp((elapsed-Math.max(0,duration-1.25F))/.75F,0,1);
        return t*t*(3-2*t);
    }
    /** XML shared RPM compensator, not a SU curve. */
    public static float rpmCompensatorDb(float rpm) { return -3+3*clamp(rpm/9.7457627F,0,1); }
    /** Measured track correction is separate from the retained official static gain. */
    public static float calibrationDb(int layer) {
        float target=layer<4?.12F:.15F;
        float nativeRms=TigerIAudioCalibration.rms(LOOPS[layer])*TigerIGain.amplitude(STATIC_DB[layer]);
        return clamp(20*(float)Math.log10(target/Math.max(.0001F,nativeRms)),-12,12);
    }
    private TigerISoundProfile() {}
}
