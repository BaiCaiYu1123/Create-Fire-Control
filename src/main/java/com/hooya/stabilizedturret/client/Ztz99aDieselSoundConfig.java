package com.hooya.stabilizedturret.client;

import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/** Separate client tuning file; values only affect the 99A mixer. Gains are in dB. */
public final class Ztz99aDieselSoundConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.DoubleValue MASTER, LOOP, TRANSIENT, START_STOP, BLIP;
    private static final ModConfigSpec.DoubleValue RPM_SECONDS, LOAD_SECONDS, GAIN_SECONDS, DB_SLEW, PEAK_CEILING;
    private static final ModConfigSpec.DoubleValue TRANSIENT_COOLDOWN, BLIP_COOLDOWN;
    private static final ModConfigSpec.DoubleValue RPM_SEEK, PITCH_SECONDS, TRIGGER_SPEED;
    private static final ModConfigSpec.EnumValue<Ztz99aDieselTakes.Audition> AUDITION;
    private static final ModConfigSpec.BooleanValue REVV, RELEASE, BLIPS;
    private static final ModConfigSpec.IntValue MAX_SHOTS;
    private static final ModConfigSpec.DoubleValue[] IDLE, LOADED;
    private static final Map<String, ModConfigSpec.DoubleValue> TRIMS = new LinkedHashMap<>();
    private static Ztz99aDieselGain.Parameters snapshot;

    static {
        var b = new ModConfigSpec.Builder();
        b.comment("99A diesel audio only. Never changes Create RPM, SU capacity or M1A2 audio.").push("mix");
        MASTER = db(b, "masterDb", -12, "Total diesel gain before the GUI volume and distance attenuation.");
        LOOP = db(b, "loopsDb", 0, "Additional gain for the four RPM loops.");
        TRANSIENT = db(b, "transientsDb", -14, "Additional gain for state-matched revv and release takes.");
        BLIP = db(b, "blipsDb", -13.75, "Additional gain for short blips.");
        START_STOP = db(b, "startStopDb", -4, "Additional gain for start and stop takes.");
        PEAK_CEILING = b.comment("Per-engine worst-case peak budget, including GUI volume, continuous loops and shots.")
                .defineInRange("peakCeiling", .8, .1, .95);
        b.pop().push("smoothing");
        RPM_SECONDS = b.defineInRange("rpmSeconds", .25, .05, 5);
        RPM_SEEK = b.comment("Maximum normalized audio RPM units/sec. Official rpm_result seek speed is 50.")
                .defineInRange("rpmSeekPerSecond", 50., 5., 200.);
        PITCH_SECONDS = b.comment("Log playback-rate smoothing, independent of actual Create RPM and SU.")
                .defineInRange("pitchSeconds", .12, .05, 2.);
        LOAD_SECONDS = b.defineInRange("suLoadSeconds", 1.975, .05, 10);
        GAIN_SECONDS = b.defineInRange("gainSeconds", .20, .05, 5);
        DB_SLEW = b.comment("Maximum dB change per second for each track, including config edits.")
                .defineInRange("maxDbPerSecond", 24., 1., 120.);
        b.pop().push("loadAutomation");
        b.comment("XML endpoints; defaults use native FMOD response tables. Custom shapes smoothly blend/warp those tables (approximate).");
        IDLE = curve(b, "layer00", Ztz99aDieselGain.OFFICIAL_IDLE);
        LOADED = curve(b, "layer06", Ztz99aDieselGain.OFFICIAL_LOADED);
        b.pop().push("takes");
        AUDITION=b.defineEnum("auditionMode",Ztz99aDieselTakes.Audition.FULL);
        REVV=b.define("enableRevv",true); RELEASE=b.define("enableRelease",true); BLIPS=b.define("enableBlips",true);
        TRIGGER_SPEED=b.comment("Normalized audio RPM/sec; slow ramps use only continuous loops.")
                .defineInRange("triggerRpmPerSecond",12.,2.,100.);
        MAX_SHOTS = b.defineInRange("maxConcurrent", 2, 1, 2);
        TRANSIENT_COOLDOWN = b.defineInRange("revvReleaseCooldownSeconds", 5.5, 1., 60.);
        BLIP_COOLDOWN = b.defineInRange("blipCooldownSeconds", 12., 2., 120.);
        b.pop().push("trackTrimDb");
        b.comment("Per-original-file dB offsets, added to measured RMS/peak calibration. Zero retains calibration.");
        for (String sample : Ztz99aAudioCalibration.SAMPLES) TRIMS.put(sample, db(b, sample, 0, ""));
        b.pop(); SPEC = b.build();
    }
    private static ModConfigSpec.DoubleValue db(ModConfigSpec.Builder b, String key, double value, String comment) {
        if (!comment.isEmpty()) b.comment(comment);
        return b.defineInRange(key, value, -80, 24);
    }
    private static ModConfigSpec.DoubleValue[] curve(ModConfigSpec.Builder b, String key, Ztz99aDieselGain.Curve c) {
        b.push(key);
        var values = new ModConfigSpec.DoubleValue[]{
                b.defineInRange("startLoad", (double)c.startLoad(), 0, .49), db(b, "startDb", c.startDb(), ""),
                b.defineInRange("endLoad", (double)c.endLoad(), .5, 1), db(b, "endDb", c.endDb(), ""),
                b.defineInRange("curveShape", (double)c.shape(), -.99, .99)};
        b.pop(); return values;
    }
    private static float value(ModConfigSpec.DoubleValue v) { return v.get().floatValue(); }
    private static Ztz99aDieselGain.Curve curve(ModConfigSpec.DoubleValue[] v) {
        return new Ztz99aDieselGain.Curve(value(v[0]), value(v[1]), value(v[2]), value(v[3]), value(v[4]));
    }
    /** Called on the client tick thread. Cache maps and curves until the config actually changes. */
    public static Ztz99aDieselGain.Parameters parameters() {
        if (!SPEC.isLoaded()) return Ztz99aDieselGain.DEFAULTS;
        boolean changed = snapshot == null || snapshot.masterDb() != value(MASTER) || snapshot.loopDb() != value(LOOP)
                || snapshot.rpmSeconds() != value(RPM_SECONDS) || snapshot.loadSeconds() != value(LOAD_SECONDS)
                || snapshot.gainSeconds() != value(GAIN_SECONDS) || snapshot.maxDbPerSecond() != value(DB_SLEW)
                || snapshot.rpmSeekPerSecond()!=value(RPM_SEEK) || snapshot.pitchSeconds()!=value(PITCH_SECONDS)
                || !snapshot.idle().equals(curve(IDLE)) || !snapshot.loaded().equals(curve(LOADED));
        if (!changed) for (var entry : TRIMS.entrySet())
            if (snapshot.trimsDb().get(entry.getKey()) != value(entry.getValue())) { changed = true; break; }
        if (changed) {
            Map<String, Float> trims = new LinkedHashMap<>();
            TRIMS.forEach((name, v) -> trims.put(name, value(v)));
            snapshot = new Ztz99aDieselGain.Parameters(value(MASTER), value(LOOP), value(RPM_SECONDS),
                    value(LOAD_SECONDS), value(GAIN_SECONDS), value(DB_SLEW), curve(IDLE), curve(LOADED), Map.copyOf(trims),
                    value(RPM_SEEK),value(PITCH_SECONDS));
        }
        return snapshot;
    }
    public static float categoryDb(String sample) {
        if (!SPEC.isLoaded()) return sample.startsWith("engine_blips") ? -13.75F
                : sample.equals("engine_start") || sample.equals("engine_stop") ? -4 : -14;
        return value(sample.startsWith("engine_blips") ? BLIP
                : sample.equals("engine_start") || sample.equals("engine_stop") ? START_STOP : TRANSIENT);
    }
    public static float peakCeiling() { return SPEC.isLoaded() ? value(PEAK_CEILING) : .8F; }
    public static float transientCooldown() { return SPEC.isLoaded() ? value(TRANSIENT_COOLDOWN) : 5.5F; }
    public static float blipCooldown() { return SPEC.isLoaded() ? value(BLIP_COOLDOWN) : 12F; }
    public static int maxShots() { return SPEC.isLoaded() ? MAX_SHOTS.get() : 2; }
    public static boolean takeEnabled(String family) {
        var mode=SPEC.isLoaded()?AUDITION.get():Ztz99aDieselTakes.Audition.FULL;
        boolean enabled=!SPEC.isLoaded() || (family.equals("revv")?REVV.get():family.equals("release")?RELEASE.get():BLIPS.get());
        return enabled && Ztz99aDieselTakes.allowed(mode,family);
    }
    public static float triggerSpeed() { return SPEC.isLoaded()?value(TRIGGER_SPEED):12; }
    private Ztz99aDieselSoundConfig() {}
}
