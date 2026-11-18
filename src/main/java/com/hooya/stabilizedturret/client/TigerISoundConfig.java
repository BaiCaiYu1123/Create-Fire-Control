package com.hooya.stabilizedturret.client;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.*;

/** Independent Tiger I client TOML. All numeric entries have finite bounds and safe fallbacks. */
public final class TigerISoundConfig {
    public static final ModConfigSpec SPEC;
    private static final Map<String,ModConfigSpec.DoubleValue> VALUES=new LinkedHashMap<>(),TRIMS=new LinkedHashMap<>();
    private static final Map<String,Double> DEFAULT_VALUES=new LinkedHashMap<>();
    private static final ModConfigSpec.BooleanValue REVV,RELEASE,BLIPS,DEBUG;
    private static final ModConfigSpec.IntValue MAX_SHOTS;
    public enum Mode { FMOD_REFERENCE, LEGACY }
    public enum Layers { BASE_ONLY, WITH_CLIMB, WITH_REVV, FULL }
    private static final ModConfigSpec.EnumValue<Mode> MODE;
    private static final ModConfigSpec.EnumValue<Layers> LAYERS;
    static {
        var b=new ModConfigSpec.Builder();
        b.push("reference");
        MODE=b.comment("JVM override: -Dcreate_fire_control.tigerSoundMode=FMOD_REFERENCE or LEGACY").defineEnum("mode",Mode.FMOD_REFERENCE);
        LAYERS=b.comment("JVM override: -Dcreate_fire_control.tigerSoundLayers=BASE_ONLY / WITH_CLIMB / WITH_REVV / FULL").defineEnum("layers",Layers.FULL);
        value(b,"referenceMasterDb",0,-80,12);value(b,"referenceRevvDb",0,-80,12);value(b,"referenceStartStopDb",0,-80,12);
        value(b,"climbFullAcceleration",35,1,200);value(b,"climbAttackSeconds",.12,.05,3);value(b,"climbReleaseSeconds",.65,.1,5);
        value(b,"loadswitchFullDemand",25,1,100);
        b.comment("Installed bank's measured random pitch range is about +/-1.2 st; XML serialized amount=5 is not assumed to mean 5 st.");
        value(b,"revvRandomSemitones",1.2,0,12);
        b.pop().push("spatial");
        value(b,"distanceScale",1,.1,10);value(b,"cullDistance",256,32,1024);
        value(b,"highFrequencyLossDb",-12,-40,0);
        b.pop();
        b.comment("Tiger I only. Existing masterDb and family controls apply to Legacy; Reference uses referenceMasterDb and healthy broken=0.").push("mix");
        value(b,"masterDb",-12,-80,12);value(b,"loopsDb",0,-80,12);
        value(b,"revvDb",-14,-80,12);value(b,"releaseDb",-18,-80,12);value(b,"blipsDb",-20,-80,12);
        value(b,"startStopDb",-4,-80,12);value(b,"peakCeiling",.8,.1,.95);
        b.pop().push("smoothing");
        value(b,"rpmSeconds",.18,.05,5);value(b,"rpmSeekPerSecond",100,5,200);
        value(b,"pitchSeconds",.08,.05,2);value(b,"suLoadSeconds",.5,.05,10);
        value(b,"gainSeconds",.12,.05,5);value(b,"maxDbPerSecond",24,1,120);
        b.pop().push("suMapping");
        value(b,"utilizationExponent",1,.25,4);
        value(b,"unloadedGroupDb",-2,-80,12);value(b,"loadedGroupDb",6,-80,12);
        b.pop().push("takes");
        REVV=b.define("enableRevv",true);
        RELEASE=b.comment("Optional directory take; not directly referenced by the exterior engine event.").define("enableRelease",false);
        BLIPS=b.comment("Optional directory take; not directly referenced by the exterior engine event.").define("enableBlips",false);
        value(b,"triggerRpmPerSecond",15,2,100);value(b,"cooldownSeconds",4,1,60);value(b,"blipCooldownSeconds",12,2,120);
        MAX_SHOTS=b.defineInRange("maxConcurrent",1,1,2);
        b.pop().push("debug");DEBUG=b.define("enabled",false);b.pop().push("trackTrimDb");
        for(String sample:TigerIAudioCalibration.SAMPLES) if(!sample.equals("idle_not_load"))
            TRIMS.put(sample,b.defineInRange(sample,0.,-80.,12.));
        b.pop();SPEC=b.build();
    }
    private static void value(ModConfigSpec.Builder b,String name,double def,double lo,double hi) {
        DEFAULT_VALUES.put(name,def);VALUES.put(name,b.defineInRange(name,def,lo,hi));
    }
    private static float get(String name) {
        double def=DEFAULT_VALUES.get(name),v=SPEC.isLoaded()?VALUES.get(name).get():def;
        return Double.isFinite(v)?(float)v:(float)def;
    }
    public static TigerIGain.Parameters parameters() {
        Map<String,Float> trims=new LinkedHashMap<>();
        if(SPEC.isLoaded())TRIMS.forEach((key,value)->{double v=value.get();trims.put(key,Double.isFinite(v)?(float)v:0);});
        return new TigerIGain.Parameters(reference()?get("referenceMasterDb"):get("masterDb"),get("loopsDb"),get("rpmSeconds"),get("suLoadSeconds"),
                get("gainSeconds"),get("maxDbPerSecond"),get("rpmSeekPerSecond"),get("pitchSeconds"),
                get("utilizationExponent"),get("unloadedGroupDb"),get("loadedGroupDb"),Map.copyOf(trims));
    }
    public static float categoryDb(String sample) {
        if(reference())return get(sample.startsWith("revv")?"referenceRevvDb":"referenceStartStopDb");
        return get(sample.startsWith("revv")?"revvDb":sample.startsWith("release")?"releaseDb":sample.startsWith("blips")?"blipsDb":"startStopDb");
    }
    public static boolean takeEnabled(String family) {
        if(!SPEC.isLoaded())return family.equals("revv");
        return family.equals("revv")?REVV.get():family.equals("release")?RELEASE.get():BLIPS.get();
    }
    public static float peakCeiling(){return get("peakCeiling");}
    public static float triggerSpeed(){return get("triggerRpmPerSecond");}
    public static float transientCooldown(){return get("cooldownSeconds");}
    public static float blipCooldown(){return get("blipCooldownSeconds");}
    public static int maxShots(){return reference()?2:SPEC.isLoaded()?MAX_SHOTS.get():1;}
    public static boolean debug(){return SPEC.isLoaded()&&DEBUG.get();}
    public static Mode mode() {
        return override(Mode.class,"create_fire_control.tigerSoundMode",SPEC.isLoaded()?MODE.get():Mode.FMOD_REFERENCE);
    }
    public static Layers layers() {
        return override(Layers.class,"create_fire_control.tigerSoundLayers",SPEC.isLoaded()?LAYERS.get():Layers.FULL);
    }
    private static <E extends Enum<E>> E override(Class<E> type,String key,E fallback) {
        String value=System.getProperty(key,"").trim().toUpperCase(Locale.ROOT);
        try{return value.isEmpty()?fallback:Enum.valueOf(type,value);}catch(IllegalArgumentException ignored){return fallback;}
    }
    public static boolean reference(){return mode()==Mode.FMOD_REFERENCE;}
    public static boolean rpmLoudnessCompensation(){return false;}
    public static boolean climbEnabled(){return layers()==Layers.WITH_CLIMB || layers()==Layers.FULL;}
    public static boolean revvEnabled(){return takeEnabled("revv")&&(layers()==Layers.WITH_REVV || layers()==Layers.FULL);}
    public static float climbFullAcceleration(){return get("climbFullAcceleration");}
    public static float climbAttack(){return get("climbAttackSeconds");}
    public static float climbRelease(){return get("climbReleaseSeconds");}
    public static float loadswitchFullDemand(){return get("loadswitchFullDemand");}
    public static float randomSemitones(){return get("revvRandomSemitones");}
    public static float distanceScale(){return get("distanceScale");}
    public static float cullDistance(){return get("cullDistance")*distanceScale();}
    public static float highFrequencyLoss(){return get("highFrequencyLossDb");}
    private TigerISoundConfig() {}
}
