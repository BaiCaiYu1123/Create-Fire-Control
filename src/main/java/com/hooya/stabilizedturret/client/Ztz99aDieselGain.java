package com.hooya.stabilizedturret.client;

import java.util.Map;

/** Diesel-only dB automation. This class has no kinetic or audio-device side effects. */
public final class Ztz99aDieselGain {
    public record Curve(float startLoad, float startDb, float endLoad, float endDb, float shape) {
        public float db(float load) {
            float t = Ztz99aDieselSoundProfile.clamp((finite(load) - startLoad)
                    / Math.max(.0001F, endLoad - startLoad), 0, 1);
            // Authored shapes use 1001 samples of the native FMOD bus response, with its fixed
            // baseline removed and its range mapped to the XML endpoints. Interpolate in dB.
            boolean idle = startDb > endDb;
            float authoredShape = idle ? .822146177F : -.450167418F;
            float ratio = shape/authoredShape;
            float nativeFraction = Ztz99aFmodLoadResponse.fraction(idle,t);
            float fraction;
            // Small tuning changes must not abruptly switch from native to an unrelated curve.
            // Custom shapes approximate between a straight dB curve and the native response;
            // stronger shapes warp its parameter, and opposite shapes use a monotone fallback.
            if (ratio>=0 && ratio<=1) fraction=t+(nativeFraction-t)*ratio;
            else if (ratio>1) fraction=Ztz99aFmodLoadResponse.fraction(idle,shaped(t,shape-authoredShape));
            else fraction=shaped(t,shape);
            return startDb + (endDb-startDb)*Ztz99aDieselSoundProfile.clamp(fraction,0,1);
        }
        private static float shaped(float t,float shape) {
            double k=Ztz99aDieselSoundProfile.clamp(shape,-.99F,.99F)*6;
            return (float)(Math.abs(k)<.00001?t:Math.expm1(k*t)/Math.expm1(k));
        }
    }

    // ztz99/engine: layer00 curve {892a599c-8523-44d7-b8ec-132013fbe939},
    // layer06 curve {da743148-90b1-48e5-a1ef-83e0cc1d7899}; parameter {5ef70fb5-...}.
    public static final Curve OFFICIAL_IDLE = new Curve(0, 2, 1, -80, .822146177F);
    public static final Curve OFFICIAL_LOADED = new Curve(0, 0, .99717514124293782F, 6, -.450167418F);

    public record Parameters(float masterDb, float loopDb, float rpmSeconds, float loadSeconds,
            float gainSeconds, float maxDbPerSecond, Curve idle, Curve loaded, Map<String, Float> trimsDb,
            float rpmSeekPerSecond, float pitchSeconds) {
        public Parameters(float masterDb,float loopDb,float rpmSeconds,float loadSeconds,float gainSeconds,
                float maxDbPerSecond,Curve idle,Curve loaded,Map<String,Float> trimsDb) {
            this(masterDb,loopDb,rpmSeconds,loadSeconds,gainSeconds,maxDbPerSecond,idle,loaded,trimsDb,50,.12F);
        }
        public float sampleDb(String sample) {
            return Ztz99aAudioCalibration.gainDb(sample) + trimsDb.getOrDefault(sample, 0F);
        }
    }
    public static final Parameters DEFAULTS = new Parameters(-12, 0, .25F, 1.975F,
            .20F, 24, OFFICIAL_IDLE, OFFICIAL_LOADED, Map.of());

    public static float amplitude(float db) {
        return (float)Math.pow(10, Ztz99aDieselSoundProfile.clamp(finite(db), -100, 48) / 20);
    }
    public static float finite(float value) { return Float.isFinite(value) ? value : 0; }
    public static float smooth(float current, float target, float dt, float seconds) {
        float alpha = (float)-Math.expm1(-Math.max(0, dt) / Math.max(.001F, seconds));
        return current + (finite(target) - current) * alpha;
    }
    public static float smoothDb(float current, float target, float dt, Parameters p) {
        float delta = smooth(current, target, dt, p.gainSeconds()) - current;
        float limit = p.maxDbPerSecond() * Math.max(0, dt);
        return current + Ztz99aDieselSoundProfile.clamp(delta, -limit, limit);
    }
    public static float loopDb(int layer, float load, Parameters p) {
        return p.masterDb() + p.loopDb() + p.sampleDb(Ztz99aDieselSoundProfile.LOOPS[layer])
                + (layer == 0 ? p.idle() : p.loaded()).db(load);
    }
    public static float shotDb(String sample, float categoryDb, Parameters p) {
        return p.masterDb() + categoryDb + p.sampleDb(sample);
    }
    /** Worst aligned source peaks: one continuously looping voice per instrument. */
    public static float loopPeak(float[] gains) {
        float peak = 0;
        for (int i = 0; i < gains.length; i++)
            peak += gains[i] * Ztz99aAudioCalibration.peak(Ztz99aDieselSoundProfile.LOOPS[i]);
        return peak;
    }
    public static float headroom(float peak, float ceiling) {
        return peak <= 0 ? 1 : Math.min(1, ceiling / peak);
    }
    public static float shotEnvelope(float age, float remaining) {
        float t = Ztz99aDieselSoundProfile.clamp(Math.min(age / .22F, remaining / .45F), 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static final class State {
        private boolean initialized;
        private float rpm, load;
        private final float[] db = new float[4];
        private final float[] gains = new float[4];
        private final float[] pitches = new float[4];
        private final float[] fadeWeights = new float[4];
        private float loadEnergy;

        public void tick(float targetRpm, float actualSuLoad, float dt, Parameters p) {
            targetRpm = Ztz99aDieselSoundProfile.clamp(finite(targetRpm), 0, 100);
            actualSuLoad = Ztz99aDieselSoundProfile.clamp(finite(actualSuLoad), 0, 1);
            if (!initialized) {
                initialized = true; rpm = targetRpm; load = actualSuLoad;
                for (int i = 0; i < 4; i++) {
                    db[i] = loopDb(i, load, p);
                    pitches[i] = Ztz99aDieselSoundProfile.pitch(i,rpm,load);
                }
                float[] w=Ztz99aDieselSoundProfile.weights(rpm);
                System.arraycopy(w,0,fadeWeights,0,4);
                loadEnergy=(float)Math.sqrt(w[1]*w[1]+w[2]*w[2]+w[3]*w[3]);
            } else {
                float delta=smooth(rpm, targetRpm, dt, p.rpmSeconds())-rpm;
                float limit=p.rpmSeekPerSecond()*Math.max(0,dt);
                rpm += Ztz99aDieselSoundProfile.clamp(delta,-limit,limit);
                load = smooth(load, actualSuLoad, dt, p.loadSeconds());
                for (int i = 0; i < 4; i++) db[i] = smoothDb(db[i], loopDb(i, load, p), dt, p);
            }
            float[] weights = Ztz99aDieselSoundProfile.weights(rpm);
            // Smooth the fade control, then express its gain in dB. Normalize the load
            // vector to a smoothed target energy so smoothing doesn't add crossover dips.
            float targetEnergy=(float)Math.sqrt(weights[1]*weights[1]+weights[2]*weights[2]+weights[3]*weights[3]);
            loadEnergy=smooth(loadEnergy,targetEnergy,dt,p.gainSeconds());
            float power=0;
            for(int i=1;i<4;i++) { fadeWeights[i]=smooth(fadeWeights[i],weights[i],dt,p.gainSeconds());power+=fadeWeights[i]*fadeWeights[i]; }
            float correction=power>0 ? loadEnergy/(float)Math.sqrt(power) : 0;
            fadeWeights[0]=1;
            for (int i = 0; i < 4; i++) {
                float w=i==0?1:fadeWeights[i]*correction;
                gains[i] = w<.000001F ? 0 : amplitude(db[i]+20*(float)Math.log10(w));
                float target=Ztz99aDieselSoundProfile.pitch(i,rpm,load);
                pitches[i]=(float)Math.exp(smooth((float)Math.log(pitches[i]),(float)Math.log(target),dt,p.pitchSeconds()));
            }
        }
        public float rpm() { return rpm; }
        public float load() { return load; }
        public float gain(int layer) { return gains[layer]; }
        public float db(int layer) { return db[layer]; }
        public float pitch(int layer) { return pitches[layer]; }
        public float weight(int layer) {
            return gains[layer]<=0?0:gains[layer]/amplitude(db[layer]);
        }
        public float peak() { return loopPeak(gains); }
    }
    private Ztz99aDieselGain() {}
}
