package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.hooya.stabilizedturret.registry.ModSounds;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

@OnlyIn(Dist.CLIENT)
public final class GasTurbineSoundManager {
    // JVM selection is read once, so a running engine cannot abruptly switch mix.
    // FMOD Reference is the default; explicit legacy/unrecognized values use
    // the complete pre-audit Legacy algorithm.
    private static final String REQUESTED_MIX = System.getProperty(
            AbramsFmodReference.MODE_PROPERTY, AbramsFmodReference.DEFAULT_MIX);
    private static final boolean FMOD_REFERENCE = AbramsFmodReference.isReference(REQUESTED_MIX);
    private static final float DEBUG_LOAD = AbramsFmodReference.debugLoad(
            System.getProperty(AbramsFmodReference.LOAD_PROPERTY));
    private static final float DEBUG_RPM = AbramsFmodReference.debugRpm(
            System.getProperty(AbramsFmodReference.RPM_PROPERTY));
    private static final float MASTER_ENGINE_VOLUME = 0.15F;
    // Continuous RPM/load layers only. Start/stop one-shots deliberately do
    // not use this gain.
    private static final float LOOP_VOLUME_GAIN = 4.0F;
    // Static FMOD event-path references. These one-shot trims are applied
    // once; they do not reuse the loop master's gain.
    private static final float START_EVENT_TRIM_DB = -3.0F;
    // Start and stop were about 50% louder than the loop mix at the same UI
    // setting. Apply one shared balance correction so their mutual calibration
    // and the 0-300% master-volume scaling remain proportional.
    private static final float ONE_SHOT_BALANCE_GAIN = 1.0F / 1.5F;
    private static final float RPM_00_CALIBRATION_DB = 0.0F;
    private static final float RPM_33_CALIBRATION_DB = 0.0F;
    private static final float RPM_66_CALIBRATION_DB = 0.0F;
    private static final float RPM_99_CALIBRATION_DB = 0.0F;
    private static final float LOAD_00_CALIBRATION_DB = 0.0F;
    private static final float LOAD_33_CALIBRATION_DB = 0.0F;
    private static final float LOAD_66_CALIBRATION_DB = 0.0F;
    private static final float LOAD_99_CALIBRATION_DB = 0.0F;
    private static final float RPM_MIN = 0.0F;
    private static final float RPM_MAX = 100.0F;
    private static final float LOAD_SEEK_SPEED = 0.4F;
    private static final float CLIMB_SEEK_SPEED = 0.89F;
    private static final float CLIMB_MAX_PITCH_ST = 3.0F;
    private static final float LOAD_LFO_RATE_HZ = 0.05F;
    private static final float LOAD_LFO_DEPTH_TOTAL_ST = 5.0F;
    private static final float MASTER_ATTACK_SECONDS = 0.400F;
    private static final float MASTER_RELEASE_SECONDS = 2.1538457F;
    // Presentation timing only; engine state and Create output are never held
    // back by the audio file. The idle layers enter during the start sample's
    // final section. Move the entire idle fade one second earlier while keeping
    // its 1.5-second duration and the start one-shot's original timing.
    private static final float START_SOUND_SECONDS = 16.82F;
    private static final float START_LOOP_HANDOFF_SECONDS = 1.50F;
    private static final float START_LOOP_LEAD_SECONDS = 1.0F;
    // Loop layers are positional mono sounds. Their audible radius follows
    // the turbine's configured volume instead of staying at a fixed 64 blocks.
    private static final float LOOP_FULL_VOLUME_DISTANCE = 3.0F;
    private static final float LOOP_MAX_SILENT_DISTANCE = 32.0F;
    private static final float FULL_VOLUME_DISTANCE = 4.0F;
    private static final float SILENT_DISTANCE = 64.0F;
    private static final float NORMAL_BUS_GAIN = dbToLinear(-6.02F);
    private static final float LOAD_BUS_GAIN = dbToLinear(-4.02F);
    private static final float MASTER_GAIN = dbToLinear(-8.0F);
    private static final float MAX_CROSSFADE_COMPENSATION = dbToLinear(3.0F);
    private static final float[] RPM_CENTERS = {0.0F, 33.0F, 66.0F, 100.0F};
    private static final int LAYER_COUNT = 4;
    private static final int LOOP_COUNT = 8;
    private static final boolean DEBUG_RPM_WEIGHTS =
            Boolean.getBoolean("create_fire_control.debug_engine_sound");
    private static final int DEBUG_INTERVAL_TICKS = Math.max(10,
            Integer.getInteger("create_fire_control.abrams.debug_interval_ticks", 20));
    private static boolean debugIdentityLogged;
    private static final Map<GasTurbineBlockEntity, EngineSoundController> CONTROLLERS =
            new WeakHashMap<>();

    public static void tick(GasTurbineBlockEntity turbine) {
        if (DEBUG_RPM_WEIGHTS && !debugIdentityLogged) {
            debugIdentityLogged = true;
            StabilizedTurret.LOGGER.info(
                    "Abrams diagnostics=reference-audit-v2 mode={} requested={} override={} classSource={} "
                            + "debugRpmPct={} debugLoad={} intervalTicks={} order=[rpm00,rpm33,rpm66,rpm99,load00,load33,load66,load99]",
                    FMOD_REFERENCE ? "fmod_reference" : "legacy", REQUESTED_MIX,
                    System.getProperty(AbramsFmodReference.MODE_PROPERTY) != null,
                    GasTurbineSoundManager.class.getResource("GasTurbineSoundManager.class"),
                    DEBUG_RPM, DEBUG_LOAD, DEBUG_INTERVAL_TICKS);
        }
        EngineSoundController controller = CONTROLLERS.get(turbine);
        if (controller == null || !controller.active) {
            controller = new EngineSoundController();
            CONTROLLERS.put(turbine, controller);
        }
        controller.tick(turbine);
    }

    private static final class EngineSoundController {
        private final TurbineLoopSound[] loops = new TurbineLoopSound[LOOP_COUNT];
        private final float[] volumes = new float[LOOP_COUNT];
        private final float[] pitches = new float[LOOP_COUNT];
        private final float[] rpmWeights = new float[LAYER_COUNT];
        private final float[] loadWeights = new float[LAYER_COUNT];
        private final float[] rpmRawWeights = new float[LAYER_COUNT];
        private final float[] loadRawWeights = new float[LAYER_COUNT];
        private boolean active = true;
        private boolean initialized;
        private boolean engineRunning;
        private boolean startupHandoff;
        private float startupElapsedSeconds;
        private float masterEnvelope;
        private float smoothedLoad;
        private float smoothedClimb;
        private float targetClimb;
        private float previousRpm;
        private boolean hasPreviousRpm;
        private float lfoTimeSeconds;
        private int debugTicks;
        private float rpmCompensation;
        private float loadCompensation;
        private float loadBusGain;
        private float climbGain = 1.0F;
        private float networkLoad;
        private float targetSoundLoad;
        private TurbineOneShotSound transitionSound;
        private TurbineOneShotSound fadingTransitionSound;

        private void tick(GasTurbineBlockEntity turbine) {
            if (!sourceIsValid(turbine)) {
                invalidate();
                return;
            }
            boolean running = turbine.isEngineEnabled();
            if (!initialized) {
                initialized = true;
                engineRunning = running;
                // Initial client observation is state restoration (login,
                // chunk reload, or world switch), not a new start command.
                // An already-running turbine must resume its loop mix now
                // instead of replaying Start and staying silent for ~15 s.
                masterEnvelope = running ? 1.0F : 0.0F;
                previousRpm = turbine.getSoundRpmMagnitude();
                hasPreviousRpm = true;
                smoothedLoad = soundLoad(turbine);
                if (!FMOD_REFERENCE || running) ensureLoops(turbine);
            } else if (running != engineRunning) {
                engineRunning = running;
                if (running) {
                    beginStartupHandoff();
                    playTransition(turbine, ModSounds.ABRAMS_ENGINE_START.get(),
                            START_EVENT_TRIM_DB);
                } else {
                    startupHandoff = false;
                    playTransition(turbine, ModSounds.ABRAMS_ENGINE_STOP.get(), 0.0F);
                }
            }

            updateMasterEnvelope(running);
            if (running) ensureLoops(turbine);

            updateMix(turbine, running);
            debugMix(turbine);

            if (!running && masterEnvelope <= 0.0F) stopLoops();
        }

        private void playTransition(GasTurbineBlockEntity turbine, SoundEvent event, float trimDb) {
            if (!FMOD_REFERENCE) {
                playOneShot(turbine, event, trimDb);
                return;
            }
            // At most one active and one 100 ms fading one-shot per turbine.
            if (fadingTransitionSound != null) fadingTransitionSound.halt();
            fadingTransitionSound = transitionSound;
            if (fadingTransitionSound != null) fadingTransitionSound.fadeOut();
            transitionSound = new TurbineOneShotSound(turbine, event, dbToLinear(trimDb));
            Minecraft.getInstance().getSoundManager().play(transitionSound);
            transitionSound.debugOneShot(trimDb);
        }

        private void beginStartupHandoff() {
            startupHandoff = true;
            startupElapsedSeconds = 0.0F;
            masterEnvelope = 0.0F;
        }

        private void updateMasterEnvelope(boolean running) {
            if (running && startupHandoff) {
                startupElapsedSeconds += 0.05F;
                float handoffEnd = Math.max(0.0F, START_SOUND_SECONDS - START_LOOP_LEAD_SECONDS);
                float handoffStart = Math.max(0.0F,
                        handoffEnd - START_LOOP_HANDOFF_SECONDS);
                masterEnvelope = Mth.clamp(
                        (startupElapsedSeconds - handoffStart)
                                / START_LOOP_HANDOFF_SECONDS,
                        0.0F, 1.0F);
                if (startupElapsedSeconds >= handoffEnd) {
                    startupHandoff = false;
                    masterEnvelope = 1.0F;
                }
                return;
            }
            float envelopeStep = 0.05F / (running
                    ? MASTER_ATTACK_SECONDS : MASTER_RELEASE_SECONDS);
            masterEnvelope = Mth.clamp(masterEnvelope
                    + (running ? envelopeStep : -envelopeStep), 0.0F, 1.0F);
        }

        private void updateMix(GasTurbineBlockEntity turbine, boolean running) {
            networkLoad = turbine.calculateEngineLoad();
            float load = DEBUG_LOAD >= 0.0F ? DEBUG_LOAD : networkLoad;
            if (FMOD_REFERENCE) load = AbramsFmodReference.unit(load);
            updateMixParameters(turbine.getSoundRpmMagnitude(),
                    Math.max(0.0001F, turbine.getSoundMaxRpm()),
                    running ? load : 0.0F, Math.max(0.0001F, turbine.getSoundRpmAcceleration()), running,
                    turbine.getControlSettings().soundRpmEnabled());
        }

        private void updateMixParameters(float currentRpm, float maxRpm,
                                         float targetLoad, float configuredRisePerSecond, boolean running,
                                         boolean independentRpm) {
            // The independent RPM already implements the requested acceleration.
            // A second climb envelope adds up to +3 semitones/+3 dB, then falls
            // away at constant RPM. That sounds like a speed drop while a key is
            // still held. Clear it on entry too, including a live mode switch.
            if (independentRpm) smoothedClimb = 0;
            updateMixParameters(currentRpm, maxRpm, targetLoad, configuredRisePerSecond,
                    running, independentRpm ? 0 : -1);
        }

        // The live controller and world-free checks share the complete parameter
        // update, including seek, bus selection and compensation selection.
        private void updateMixParameters(float currentRpm, float maxRpm,
                                         float targetLoad, float configuredRisePerSecond, boolean running) {
            updateMixParameters(currentRpm, maxRpm, targetLoad, configuredRisePerSecond, running, -1);
        }

        private void updateMixParameters(float currentRpm, float maxRpm,
                                         float targetLoad, float configuredRisePerSecond, boolean running, float requestedClimb) {
            if (FMOD_REFERENCE) {
                if (!Float.isFinite(currentRpm)) currentRpm = 0.0F;
                if (!Float.isFinite(maxRpm)) maxRpm = 0.0001F;
            }
            float rpmRatio = Mth.clamp(currentRpm / maxRpm, 0.0F, 1.0F);
            if (DEBUG_RPM >= 0.0F) rpmRatio = DEBUG_RPM / 100.0F;
            float rpmResult = rpmRatio * RPM_MAX;
            fillRpmWeights(rpmResult, rpmRawWeights);
            System.arraycopy(rpmRawWeights, 0, rpmWeights, 0, LAYER_COUNT);

            targetSoundLoad = targetLoad;
            smoothedLoad = approach(smoothedLoad, targetLoad, LOAD_SEEK_SPEED * 0.05F);
            fillLoadWeights(smoothedLoad, loadRawWeights);
            System.arraycopy(loadRawWeights, 0, loadWeights, 0, LAYER_COUNT);

            targetClimb = 0.0F;
            if (running && DEBUG_RPM < 0.0F && requestedClimb >= 0) {
                targetClimb = Mth.clamp(requestedClimb, 0, 1);
            } else if (hasPreviousRpm && running && DEBUG_RPM < 0.0F) {
                float risePerSecond = Math.max(0.0F,
                        (currentRpm - previousRpm) / 0.05F);
                targetClimb = Mth.clamp(
                        risePerSecond / configuredRisePerSecond, 0.0F, 1.0F);
                if (FMOD_REFERENCE) targetClimb = AbramsFmodReference.unit(targetClimb);
            }
            smoothedClimb = approach(
                    smoothedClimb, targetClimb, CLIMB_SEEK_SPEED * 0.05F);
            previousRpm = currentRpm;
            hasPreviousRpm = true;
            lfoTimeSeconds += 0.05F;

            float climbPitch = semitoneRatio(smoothedClimb * CLIMB_MAX_PITCH_ST);
            float lfoPitch = semitoneRatio((float) Math.sin(
                    lfoTimeSeconds * Math.PI * 2.0F * LOAD_LFO_RATE_HZ)
                    * LOAD_LFO_DEPTH_TOTAL_ST * 0.5F);
            rpmCompensation = FMOD_REFERENCE ? 1.0F : crossfadeCompensation(
                    rpmResult, rpmRawWeights, true);
            loadCompensation = FMOD_REFERENCE ? 1.0F : crossfadeCompensation(
                    smoothedLoad, loadRawWeights, false);
            loadBusGain = FMOD_REFERENCE
                    ? AbramsFmodReference.loadBusGain(smoothedLoad) : LOAD_BUS_GAIN;
            climbGain = FMOD_REFERENCE ? AbramsFmodReference.climbGain(smoothedClimb) : 1.0F;
            calculateLayerMix(rpmResult, smoothedLoad, masterEnvelope, climbPitch, lfoPitch,
                    rpmCompensation, loadCompensation, loadBusGain, climbGain,
                    rpmWeights, loadWeights, volumes, pitches);
        }

        private void debugMix(GasTurbineBlockEntity turbine) {
            if (!DEBUG_RPM_WEIGHTS || ++debugTicks < DEBUG_INTERVAL_TICKS) return;
            debugTicks = 0;
            float maxRpm = Math.max(0.0001F, turbine.getSoundMaxRpm());
            float currentRpm = turbine.getSoundRpmMagnitude();
            float ratio = Mth.clamp(currentRpm / maxRpm, 0.0F, 1.0F);
            float[] audibleGains = new float[LOOP_COUNT];
            float[] backendPitches = new float[LOOP_COUNT];
            float[] gainDb = new float[LOOP_COUNT];
            float[] assignedVolumes = new float[LOOP_COUNT];
            float[] submittedVolumes = new float[LOOP_COUNT];
            String[] events = new String[LOOP_COUNT];
            String[] resources = new String[LOOP_COUNT];
            for (int index = 0; index < LOOP_COUNT; index++) {
                TurbineLoopSound sound = loops[index];
                audibleGains[index] = sound == null ? 0.0F : sound.debugAudibleGain(turbine);
                backendPitches[index] = Mth.clamp(pitches[index], 0.5F, 2.0F);
                gainDb[index] = volumes[index] > 0.0F ? linearToDb(volumes[index]) : Float.NEGATIVE_INFINITY;
                assignedVolumes[index] = sound == null ? 0.0F : sound.assignedVolume();
                submittedVolumes[index] = sound == null ? 0.0F : sound.lastSubmittedVolume;
                events[index] = sound == null ? "absent" : sound.getLocation().toString();
                resources[index] = sound == null ? "absent" : sound.debugResource();
            }
            StabilizedTurret.LOGGER.info(
                    "Abrams mix={} pos={} network={} networkLoadPct={} soundTargetLoad={} smoothedLoad={} "
                            + "mixRpmPct={} loadEffectDb={} loadBusDb={} climbGainDb={} "
                            + "layerGainDb={} assignedVolume={} submittedVolume={} "
                            + "audibleGainEstimate={} rmsEstimateDbfs={} backendPitch={} "
                            + "loopInstances={} loopsPlaying={}",
                    FMOD_REFERENCE ? "fmod_reference" : "legacy",
                    turbine.getBlockPos(), turbine.network, fmt(networkLoad * 100.0F),
                    fmt(targetSoundLoad), fmt(smoothedLoad), fmt(DEBUG_RPM >= 0 ? DEBUG_RPM : ratio * 100.0F),
                    fmt(FMOD_REFERENCE ? AbramsFmodReference.loadEffectDb(smoothedLoad) : 2.0F),
                    fmt(linearToDb(loadBusGain)), fmt(linearToDb(climbGain)),
                    Arrays.toString(gainDb), Arrays.toString(assignedVolumes), Arrays.toString(submittedVolumes),
                    Arrays.toString(audibleGains),
                    fmt(AbramsFmodReference.rmsEstimateDbfs(audibleGains)), Arrays.toString(backendPitches),
                    Arrays.stream(loops).filter(sound -> sound != null).count(),
                    Arrays.stream(loops).filter(sound -> sound != null
                            && Minecraft.getInstance().getSoundManager().isActive(sound)).count());
            StabilizedTurret.LOGGER.info("Abrams resources pos={} events={} assets={}",
                    turbine.getBlockPos(), Arrays.toString(events), Arrays.toString(resources));
            StabilizedTurret.LOGGER.info(
                    "Abrams sound rpm={} max={} rpmRatio={} rpmResult={} "
                            + "targetLoad={} smoothedLoad={} targetClimb={} smoothedClimb={} "
                            + "rpmOverlap={} loadOverlap={} "
                            + "rpmRaw=[{},{},{},{}] loadRaw=[{},{},{},{}] "
                            + "rpmVol=[{},{},{},{}] loadVol=[{},{},{},{}] "
                            + "rpmCompDb={} loadCompDb={} calibrationDb=[0,0,0,0|0,0,0,0] "
                            + "rpmPitch=[{},{},{},{}] loadPitch=[{},{},{},{}] envelope={}",
                    fmt(currentRpm), fmt(maxRpm), fmt(ratio), fmt(ratio * 100.0F),
                    fmt(targetSoundLoad), fmt(smoothedLoad), fmt(targetClimb),
                    fmt(smoothedClimb), overlapName(ratio * 100.0F, true),
                    overlapName(smoothedLoad, false), fmt(rpmRawWeights[0]), fmt(rpmRawWeights[1]),
                    fmt(rpmRawWeights[2]), fmt(rpmRawWeights[3]), fmt(loadRawWeights[0]),
                    fmt(loadRawWeights[1]), fmt(loadRawWeights[2]), fmt(loadRawWeights[3]),
                    fmt(volumes[0]), fmt(volumes[1]), fmt(volumes[2]), fmt(volumes[3]),
                    fmt(volumes[4]), fmt(volumes[5]), fmt(volumes[6]), fmt(volumes[7]),
                    fmt(linearToDb(rpmCompensation)), fmt(linearToDb(loadCompensation)),
                    fmt(pitches[0]), fmt(pitches[1]), fmt(pitches[2]),
                    fmt(pitches[3]), fmt(pitches[4]), fmt(pitches[5]), fmt(pitches[6]),
                    fmt(pitches[7]), fmt(masterEnvelope));
        }

        private void ensureLoops(GasTurbineBlockEntity turbine) {
            if (FMOD_REFERENCE) {
                Minecraft minecraft = Minecraft.getInstance();
                // SoundEngine skips globally muted sources, or stops them when
                // a category is muted. Recreate this fixed bank on unmute only.
                if (minecraft.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
                        || minecraft.options.getSoundSourceVolume(SoundSource.BLOCKS) <= 0.0F) {
                    stopLoops();
                    return;
                }
            }
            if (loops[0] != null) return;
            for (int layer = 0; layer < LAYER_COUNT; layer++) {
                loops[layer] = playLoop(turbine, rpmEvent(layer), this, layer);
                loops[LAYER_COUNT + layer] = playLoop(
                        turbine, loadEvent(layer), this, LAYER_COUNT + layer);
            }
        }

        private boolean hasLoops() {
            return loops[0] != null;
        }

        private void stopLoops() {
            for (int index = 0; index < LOOP_COUNT; index++) {
                if (loops[index] != null) loops[index].halt();
                loops[index] = null;
                volumes[index] = 0.0F;
            }
        }

        private void invalidate() {
            if (!active) return;
            active = false;
            stopLoops();
            if (FMOD_REFERENCE) {
                if (transitionSound != null) transitionSound.halt();
                if (fadingTransitionSound != null) fadingTransitionSound.halt();
            }
        }

        private float volumeFor(int index) {
            return active ? volumes[index] : 0.0F;
        }

        private float pitchFor(int index) {
            return active ? pitches[index] : 1.0F;
        }
    }

    private static float soundLoad(GasTurbineBlockEntity turbine) {
        // Network utilization remains the default proxy. This optional A/B input
        // exists only in the client sound layer and never changes SU or output.
        float load = DEBUG_LOAD >= 0.0F ? DEBUG_LOAD : turbine.calculateEngineLoad();
        return FMOD_REFERENCE ? AbramsFmodReference.unit(load) : load;
    }

    private static float loopInstanceVolume(float effectiveVolume, float layerVolume) {
        return effectiveVolume * (FMOD_REFERENCE ? Math.max(layerVolume, 0.0F)
                : Math.max(layerVolume, 0.0001F));
    }

    private static void calculateLayerMix(
            float rpm, float load, float envelope, float climbPitch, float lfoPitch,
            float rpmCompensation, float loadCompensation, float loadBusGain, float climbGain,
            float[] rpmWeights, float[] loadWeights, float[] volumes, float[] pitches
    ) {
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            float rpmPitch = calculateAutoPitch(rpm, rpmRoot(layer), rpmMinPitch(layer));
            float loadPitch = calculateAutoPitch(load, loadRoot(layer), loadMinPitch(layer));
            volumes[layer] = rpmWeights[layer] * NORMAL_BUS_GAIN
                    * rpmInstrumentGain(layer) * rpmCalibrationGain(layer) * rpmCompensation
                    * envelope * MASTER_GAIN;
            volumes[LAYER_COUNT + layer] = loadWeights[layer] * loadBusGain
                    * loadCalibrationGain(layer) * loadCompensation * envelope * MASTER_GAIN;
            if (FMOD_REFERENCE) {
                volumes[layer] *= climbGain;
                volumes[LAYER_COUNT + layer] *= climbGain;
            }
            pitches[layer] = clampPitch(rpmPitch * climbPitch);
            pitches[LAYER_COUNT + layer] = clampPitch(loadPitch * climbPitch * lfoPitch);
        }
    }

    private static void fillRpmWeights(float value, float[] weights) {
        clear(weights);
        if (value < 1.0F) {
            weights[0] = 1.0F;
        } else if (value < 10.0F) {
            float t = (value - 1.0F) / 9.0F;
            weights[0] = curvedFade(1.0F - t, 0.25471893F);
            weights[1] = curvedFade(t, -0.25471890F);
        } else if (value >= 10.0F && value < 30.0F) {
            weights[1] = 1.0F;
        } else if (value >= 30.0F && value < 45.0F) {
            float t = (value - 30.0F) / 15.0F;
            weights[1] = curvedFade(1.0F - t, 0.30388346F);
            weights[2] = curvedFade(t, -0.28862119F);
        } else if (value >= 45.0F && value < 65.0F) {
            weights[2] = 1.0F;
        } else if (value >= 65.0F && value < 80.0F) {
            float t = (value - 65.0F) / 15.0F;
            weights[2] = curvedFade(1.0F - t, 0.20882385F);
            weights[3] = curvedFade(t, -0.35749397F);
        } else if (value >= 80.0F) {
            weights[3] = 1.0F;
        }
    }

    private static void fillLoadWeights(float value, float[] weights) {
        clear(weights);
        if (value <= 0.01F) {
            weights[0] = 1.0F;
        } else if (value < 0.10F) {
            float t = (value - 0.01F) / 0.09F;
            weights[0] = curvedFade(1.0F - t, 0.25471893F);
            weights[1] = curvedFade(t, -0.25471890F);
        } else if (value < 0.30F) {
            weights[1] = 1.0F;
        } else if (value < 0.45F) {
            float t = (value - 0.30F) / 0.15F;
            weights[1] = curvedFade(1.0F - t, 0.25471893F);
            weights[2] = curvedFade(t, -0.25471890F);
        } else if (value < 0.65F) {
            weights[2] = 1.0F;
        } else if (value < 0.80F) {
            float t = (value - 0.65F) / 0.15F;
            weights[2] = curvedFade(1.0F - t, 0.25471893F);
            weights[3] = curvedFade(t, -0.25471890F);
        } else {
            weights[3] = 1.0F;
        }
    }

    private static float curvedFade(float t, float shape) {
        float s = Mth.clamp(t, 0.0F, 1.0F);
        s = s * s * (3.0F - 2.0F * s);
        return Mth.clamp(s + shape * s * (1.0F - s), 0.0F, 1.0F);
    }

    /**
     * Compensates only an active adjacent-layer overlap. The target power is
     * interpolated using each instrument's calibration/instrument gain, then
     * the correction is capped at +3 dB so a bad boundary cannot explode in
     * volume. Outside an overlap the result is exactly unity.
     */
    private static float crossfadeCompensation(
            float parameter, float[] rawWeights, boolean rpmLayer
    ) {
        int first;
        int second;
        float t;
        if (rpmLayer) {
            if (parameter >= 1.0F && parameter < 10.0F) {
                first = 0; second = 1; t = (parameter - 1.0F) / 9.0F;
            } else if (parameter >= 30.0F && parameter < 45.0F) {
                first = 1; second = 2; t = (parameter - 30.0F) / 15.0F;
            } else if (parameter >= 65.0F && parameter < 80.0F) {
                first = 2; second = 3; t = (parameter - 65.0F) / 15.0F;
            } else {
                return 1.0F;
            }
        } else if (parameter >= 0.01F && parameter < 0.10F) {
            first = 0; second = 1; t = (parameter - 0.01F) / 0.09F;
        } else if (parameter >= 0.30F && parameter < 0.45F) {
            first = 1; second = 2; t = (parameter - 0.30F) / 0.15F;
        } else if (parameter >= 0.65F && parameter < 0.80F) {
            first = 2; second = 3; t = (parameter - 0.65F) / 0.15F;
        } else {
            return 1.0F;
        }

        float gainA = rpmLayer
                ? rpmInstrumentGain(first) * rpmCalibrationGain(first)
                : loadCalibrationGain(first);
        float gainB = rpmLayer
                ? rpmInstrumentGain(second) * rpmCalibrationGain(second)
                : loadCalibrationGain(second);
        float currentPower = (float) Math.sqrt(
                Math.pow(rawWeights[first] * gainA, 2.0)
                        + Math.pow(rawWeights[second] * gainB, 2.0));
        if (currentPower <= 0.000001F) return 1.0F;
        float targetPower = (float) Math.sqrt(
                (1.0F - t) * gainA * gainA + t * gainB * gainB);
        return Mth.clamp(targetPower / currentPower,
                1.0F, MAX_CROSSFADE_COMPENSATION);
    }

    private static float calculateAutoPitch(float parameter, float root, float minPitch) {
        float minSemitone = 12.0F * (float) (Math.log(minPitch) / Math.log(2.0));
        float semitone = minSemitone + parameter * (-minSemitone / root);
        return semitoneRatio(semitone);
    }

    private static float semitoneRatio(float semitone) {
        return (float) Math.pow(2.0, semitone / 12.0);
    }

    private static float rpmRoot(int layer) {
        return new float[]{1.0F, 33.0F, 55.0F, 100.0F}[layer];
    }

    private static float rpmMinPitch(int layer) {
        return new float[]{0.98999995F, 0.70499998F, 0.70499998F, 0.42999998F}[layer];
    }

    private static float loadRoot(int layer) {
        return new float[]{0.01F, 0.275F, 0.55F, 1.0F}[layer];
    }

    private static float loadMinPitch(int layer) {
        return new float[]{0.98999995F, 0.675F, 0.705F, 0.77F}[layer];
    }

    private static float rpmInstrumentGain(int layer) {
        return dbToLinear(layer == 0 ? 4.0F : 2.0F);
    }

    private static float rpmCalibrationGain(int layer) {
        return dbToLinear(switch (layer) {
            case 0 -> RPM_00_CALIBRATION_DB;
            case 1 -> RPM_33_CALIBRATION_DB;
            case 2 -> RPM_66_CALIBRATION_DB;
            case 3 -> RPM_99_CALIBRATION_DB;
            default -> 0.0F;
        });
    }

    private static float loadCalibrationGain(int layer) {
        return dbToLinear(switch (layer) {
            case 0 -> LOAD_00_CALIBRATION_DB;
            case 1 -> LOAD_33_CALIBRATION_DB;
            case 2 -> LOAD_66_CALIBRATION_DB;
            case 3 -> LOAD_99_CALIBRATION_DB;
            default -> 0.0F;
        });
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static float linearToDb(float linear) {
        return 20.0F * (float) (Math.log10(Math.max(0.000001F, linear)));
    }

    private static float approach(float current, float target, float maxStep) {
        if (current < target) return Math.min(current + maxStep, target);
        return Math.max(current - maxStep, target);
    }

    private static float clampPitch(float pitch) {
        return Mth.clamp(pitch, 0.35F, 2.0F);
    }

    private static void clear(float[] values) {
        for (int index = 0; index < values.length; index++) values[index] = 0.0F;
    }

    private static SoundEvent rpmEvent(int layer) {
        return switch (layer) {
            case 0 -> ModSounds.ABRAMS_ENGINE_00.get();
            case 1 -> ModSounds.ABRAMS_ENGINE_33.get();
            case 2 -> ModSounds.ABRAMS_ENGINE_66.get();
            case 3 -> ModSounds.ABRAMS_ENGINE_99.get();
            default -> throw new IllegalArgumentException("Invalid RPM layer");
        };
    }

    private static SoundEvent loadEvent(int layer) {
        return switch (layer) {
            case 0 -> ModSounds.ABRAMS_ENGINE_00_LOAD.get();
            case 1 -> ModSounds.ABRAMS_ENGINE_33_LOAD.get();
            case 2 -> ModSounds.ABRAMS_ENGINE_66_LOAD.get();
            case 3 -> ModSounds.ABRAMS_ENGINE_99_LOAD.get();
            default -> throw new IllegalArgumentException("Invalid Load layer");
        };
    }

    private static TurbineLoopSound playLoop(
            GasTurbineBlockEntity turbine, SoundEvent event,
            EngineSoundController controller, int index
    ) {
        TurbineLoopSound sound = new TurbineLoopSound(turbine, event, controller, index);
        Minecraft.getInstance().getSoundManager().play(sound);
        return sound;
    }

    private static void playOneShot(
            GasTurbineBlockEntity turbine, SoundEvent event, float trimDb
    ) {
        TurbineOneShotSound sound = new TurbineOneShotSound(turbine, event, dbToLinear(trimDb));
        Minecraft.getInstance().getSoundManager().play(sound);
        sound.debugOneShot(trimDb);
    }

    private static void playOneShot(
            GasTurbineBlockEntity turbine, SoundEvent event
    ) {
        Minecraft.getInstance().getSoundManager().play(
                new TurbineOneShotSound(turbine, event, 1.0F));
    }

    private static boolean sourceIsValid(GasTurbineBlockEntity turbine) {
        return !turbine.isRemoved()
                && turbine.getLevel() != null
                && Minecraft.getInstance().level != null;
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String overlapName(float parameter, boolean rpmLayer) {
        if (rpmLayer) {
            if (parameter >= 1.0F && parameter < 10.0F) return "RPM_00_33";
            if (parameter >= 30.0F && parameter < 45.0F) return "RPM_33_66";
            if (parameter >= 65.0F && parameter < 80.0F) return "RPM_66_99";
        } else {
            if (parameter >= 0.01F && parameter < 0.10F) return "LOAD_00_33";
            if (parameter >= 0.30F && parameter < 0.45F) return "LOAD_33_66";
            if (parameter >= 0.65F && parameter < 0.80F) return "LOAD_66_99";
        }
        return "none";
    }

    private abstract static class TrackedTurbineSound
            extends AbstractTickableSoundInstance {
        private final WeakReference<GasTurbineBlockEntity> turbineReference;
        private final WeakReference<ClientLevel> levelReference;
        protected float lastSubmittedVolume = Float.NaN;

        @Override
        public float getVolume() {
            // Observe the value SoundEngine actually requests, including the
            // resolved JSON volume. Do not sample random resource gains again.
            float result = super.getVolume();
            if (DEBUG_RPM_WEIGHTS) lastSubmittedVolume = result;
            return result;
        }

        protected String debugResource() {
            if (sound == null) return "unresolved";
            String pack = Minecraft.getInstance().getResourceManager().getResource(sound.getPath())
                    .map(resource -> resource.sourcePackId()).orElse("missing");
            return sound.getLocation() + "@pack=" + pack;
        }

        protected float assignedVolume() {
            return volume;
        }

        protected TrackedTurbineSound(GasTurbineBlockEntity turbine, SoundEvent event) {
            super(event, SoundSource.BLOCKS, RandomSource.create());
            turbineReference = new WeakReference<>(turbine);
            levelReference = new WeakReference<>(Minecraft.getInstance().level);
            attenuation = SoundInstance.Attenuation.NONE;
            updateLocation(turbine);
        }

        protected GasTurbineBlockEntity source() {
            GasTurbineBlockEntity turbine = turbineReference.get();
            ClientLevel level = levelReference.get();
            if (turbine == null || level == null || Minecraft.getInstance().level != level
                    || !sourceIsValid(turbine)) {
                stop();
                return null;
            }
            updateLocation(turbine);
            return turbine;
        }

        private void updateLocation(GasTurbineBlockEntity turbine) {
            if (turbine.getLevel() == null) return;
            Vec3 position = Sable.HELPER.projectOutOfSubLevel(
                    turbine.getLevel(), Vec3.atCenterOf(turbine.getBlockPos()));
            x = position.x;
            y = position.y;
            z = position.z;
        }

        protected float effectiveVolume(GasTurbineBlockEntity turbine) {
            float volumeMultiplier = configuredVolumeMultiplier(turbine);
            if (volumeMultiplier <= 0.0F) return 0.0F;
            float silentDistance = Math.max(FULL_VOLUME_DISTANCE + 0.001F,
                    SILENT_DISTANCE * volumeMultiplier);
            Vec3 listener = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double distance = listener.distanceTo(new Vec3(x, y, z));
            float distanceGain = Mth.clamp((float) ((silentDistance - distance)
                    / (silentDistance - FULL_VOLUME_DISTANCE)), 0.0F, 1.0F);
            if (distance <= FULL_VOLUME_DISTANCE) distanceGain = 1.0F;
            return MASTER_ENGINE_VOLUME * volumeMultiplier
                    * distanceGain * ONE_SHOT_BALANCE_GAIN;
        }

        protected float effectiveLoopVolume(GasTurbineBlockEntity turbine) {
            float volumeMultiplier = configuredVolumeMultiplier(turbine);
            if (volumeMultiplier <= 0.0F) return 0.0F;
            float silentDistance = Math.max(LOOP_FULL_VOLUME_DISTANCE + 0.001F,
                    LOOP_MAX_SILENT_DISTANCE * volumeMultiplier);
            Vec3 listener = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double distance = listener.distanceTo(new Vec3(x, y, z));
            float distanceGain = Mth.clamp((float) ((silentDistance - distance)
                    / (silentDistance - LOOP_FULL_VOLUME_DISTANCE)), 0.0F, 1.0F);
            if (distance <= LOOP_FULL_VOLUME_DISTANCE) distanceGain = 1.0F;
            return MASTER_ENGINE_VOLUME * volumeMultiplier
                    * distanceGain * LOOP_VOLUME_GAIN;
        }

        private static float configuredVolumeMultiplier(GasTurbineBlockEntity turbine) {
            float value = turbine.getSoundVolume() / 100.0F;
            if (FMOD_REFERENCE && !Float.isFinite(value)) return 0.0F;
            return Mth.clamp(value, 0.0F, 3.0F);
        }

        protected void halt() {
            stop();
        }
    }

    private static final class TurbineLoopSound extends TrackedTurbineSound {
        private final EngineSoundController controller;
        private final int index;
        private float nativeRadius;

        private TurbineLoopSound(GasTurbineBlockEntity turbine, SoundEvent event,
                                 EngineSoundController controller, int index) {
            super(turbine, event);
            this.controller = controller;
            this.index = index;
            attenuation = FMOD_REFERENCE ? SoundInstance.Attenuation.NONE
                    : SoundInstance.Attenuation.LINEAR;
            relative = false;
            looping = true;
            delay = 0;
            volume = 0.0001F;
            pitch = 1.0F;
            tick();
            nativeRadius = Math.max(volume, 1.0F) * 96.0F;
        }

        @Override
        public boolean canStartSilent() {
            return FMOD_REFERENCE;
        }

        private float debugAudibleGain(GasTurbineBlockEntity turbine) {
            Minecraft minecraft = Minecraft.getInstance();
            float raw = Float.isNaN(lastSubmittedVolume) ? volume : lastSubmittedVolume;
            float gain = Mth.clamp(raw * minecraft.options.getSoundSourceVolume(SoundSource.BLOCKS), 0, 1)
                    * minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
            if (!FMOD_REFERENCE) {
                double distance = minecraft.gameRenderer.getMainCamera().getPosition()
                        .distanceTo(new Vec3(x, y, z));
                gain *= Mth.clamp((float) (1.0 - distance / nativeRadius), 0, 1);
            }
            return gain;
        }

        @Override
        public void tick() {
            GasTurbineBlockEntity turbine = source();
            if (turbine == null || !controller.active) {
                stop();
                return;
            }
            volume = loopInstanceVolume(effectiveLoopVolume(turbine), controller.volumeFor(index));
            pitch = controller.pitchFor(index);
        }
    }

    private static final class TurbineOneShotSound extends TrackedTurbineSound {
        private final float trimGain;
        private boolean fading;
        private float fadeEnvelope = 1.0F;

        private TurbineOneShotSound(
                GasTurbineBlockEntity turbine, SoundEvent event, float trimGain
        ) {
            super(turbine, event);
            this.trimGain = trimGain;
            attenuation = FMOD_REFERENCE ? SoundInstance.Attenuation.NONE
                    : SoundInstance.Attenuation.LINEAR;
            relative = false;
            volume = effectiveVolume(turbine) * trimGain;
            pitch = 1.0F;
            looping = false;
            tick();
        }

        private void fadeOut() {
            fading = true;
        }

        private void debugOneShot(float trimDb) {
            if (DEBUG_RPM_WEIGHTS) StabilizedTurret.LOGGER.info(
                    "Abrams oneShot mode={} event={} asset={} trimDb={} assignedVolume={} submittedVolume={}",
                    FMOD_REFERENCE ? "fmod_reference" : "legacy", getLocation(), debugResource(),
                    trimDb, volume, lastSubmittedVolume);
        }

        @Override
        public void tick() {
            GasTurbineBlockEntity turbine = source();
            if (fading) {
                fadeEnvelope = Math.max(0.0F, fadeEnvelope - 0.5F);
                if (fadeEnvelope == 0.0F) stop();
            }
            if (turbine != null) volume = effectiveVolume(turbine) * trimGain * fadeEnvelope;
        }
    }

    private GasTurbineSoundManager() {
    }
}
