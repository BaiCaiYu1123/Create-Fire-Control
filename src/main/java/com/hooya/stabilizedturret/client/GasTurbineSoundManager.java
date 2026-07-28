package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.hooya.stabilizedturret.registry.ModSounds;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.WeakHashMap;

@OnlyIn(Dist.CLIENT)
public final class GasTurbineSoundManager {
    private static final float MASTER_VOLUME = 0.15F;
    private static final float TRANSITION_LOOP_DUCKING = 0.35F;
    private static final int STARTUP_TICKS = 300;
    private static final int ACCEL_TICKS = 110;
    private static final int STOP_TICKS = 140;
    private static final int TRANSITION_GUARD_TICKS = 10;
    private static final float FULL_VOLUME_DISTANCE = 4.0F;
    private static final float SILENT_DISTANCE = 64.0F;
    private static final Map<GasTurbineBlockEntity, State> STATES = new WeakHashMap<>();

    public static void tick(GasTurbineBlockEntity turbine) {
        State state = STATES.computeIfAbsent(turbine, ignored -> new State());
        float target = turbine.getTargetMagnitude();
        float current = turbine.getCurrentMagnitude();
        boolean engineEnabled = turbine.isEngineEnabled();

        if (!state.initialized) {
            state.initialized = true;
            state.engineEnabled = engineEnabled;
            state.lastTarget = target;
            if (engineEnabled) ensureLoops(turbine, state);
            return;
        }

        if (engineEnabled != state.engineEnabled) {
            state.engineEnabled = engineEnabled;
            state.lastTarget = target;
            if (engineEnabled) startEngine(turbine, state);
            else stopEngine(turbine, state);
            return;
        }
        if (!engineEnabled) {
            state.lastTarget = target;
            return;
        }

        if (state.startupTicks > 0) {
            state.startupTicks--;
            if (state.startupTicks == 0) state.startup = null;
            state.lastTarget = target;
            return;
        }

        ensureLoops(turbine, state);

        if (state.transitionTicks > 0) {
            state.transitionTicks--;
        } else {
            state.transition = null;
            state.transitionType = null;
        }
        if (state.transitionGuardTicks > 0) state.transitionGuardTicks--;

        if (target > state.lastTarget + 1.0F) {
            playTransition(turbine, state, TransitionType.ACCEL);
        } else if (target + 1.0F < state.lastTarget && current > 0.5F) {
            playTransition(turbine, state, TransitionType.STOP);
        }

        state.lastTarget = target;
    }

    private static void startEngine(
            GasTurbineBlockEntity turbine,
            State state
    ) {
        halt(state.shutdown);
        halt(state.startup);
        halt(state.transition);
        halt(state.idle);
        halt(state.run);
        state.shutdown = null;
        state.transition = null;
        state.transitionType = null;
        state.transitionTicks = 0;
        state.running = false;
        state.idle = null;
        state.run = null;
        state.startup = playOneShot(
                turbine, ModSounds.GAS_TURBINE_START.get());
        state.startupTicks = STARTUP_TICKS;
    }

    private static void stopEngine(
            GasTurbineBlockEntity turbine,
            State state
    ) {
        halt(state.startup);
        halt(state.transition);
        halt(state.idle);
        halt(state.run);
        halt(state.shutdown);
        state.startup = null;
        state.transition = null;
        state.transitionType = null;
        state.transitionTicks = 0;
        state.startupTicks = 0;
        state.running = false;
        state.idle = null;
        state.run = null;
        state.shutdown = playOneShot(
                turbine, ModSounds.GAS_TURBINE_STOP.get());
    }

    private static void ensureLoops(
            GasTurbineBlockEntity turbine,
            State state
    ) {
        if (state.running) return;
        state.idle = playLoop(turbine, LoopType.IDLE);
        state.run = playLoop(turbine, LoopType.RUN);
        state.running = true;
    }

    private static void halt(TrackedTurbineSound sound) {
        if (sound != null) sound.halt();
    }

    private static void playTransition(
            GasTurbineBlockEntity turbine,
            State state,
            TransitionType type
    ) {
        if (state.transitionType == type
                && (state.transitionGuardTicks > 0
                || state.transitionTicks > 0)) {
            return;
        }
        if (state.transition != null) state.transition.halt();
        SoundEvent event = type == TransitionType.ACCEL
                ? ModSounds.GAS_TURBINE_ACCEL.get()
                : ModSounds.GAS_TURBINE_STOP.get();
        state.transition = playOneShot(turbine, event);
        state.transitionType = type;
        state.transitionTicks = type == TransitionType.ACCEL
                ? ACCEL_TICKS : STOP_TICKS;
        state.transitionGuardTicks = TRANSITION_GUARD_TICKS;
    }

    private static TurbineLoopSound playLoop(
            GasTurbineBlockEntity turbine,
            LoopType type
    ) {
        SoundEvent event = type == LoopType.IDLE
                ? ModSounds.GAS_TURBINE_IDLE.get()
                : ModSounds.GAS_TURBINE_RUN.get();
        TurbineLoopSound sound = new TurbineLoopSound(turbine, event, type);
        Minecraft.getInstance().getSoundManager().play(sound);
        return sound;
    }

    private static TurbineOneShotSound playOneShot(
            GasTurbineBlockEntity turbine,
            SoundEvent event
    ) {
        TurbineOneShotSound sound = new TurbineOneShotSound(turbine, event);
        Minecraft.getInstance().getSoundManager().play(sound);
        return sound;
    }

    private enum LoopType {
        IDLE,
        RUN
    }

    private enum TransitionType {
        ACCEL,
        STOP
    }

    private static final class State {
        private boolean initialized;
        private boolean engineEnabled;
        private boolean running;
        private int startupTicks;
        private float lastTarget;
        private TurbineOneShotSound startup;
        private TurbineLoopSound idle;
        private TurbineLoopSound run;
        private TurbineOneShotSound transition;
        private TurbineOneShotSound shutdown;
        private TransitionType transitionType;
        private int transitionTicks;
        private int transitionGuardTicks;
    }

    private abstract static class TrackedTurbineSound
            extends AbstractTickableSoundInstance {
        protected final GasTurbineBlockEntity turbine;

        protected TrackedTurbineSound(
                GasTurbineBlockEntity turbine,
                SoundEvent event
        ) {
            super(event, SoundSource.BLOCKS, RandomSource.create());
            this.turbine = turbine;
            // Sable sublevels can bypass vanilla's source-distance
            // calculation, so distance attenuation is applied manually.
            attenuation = SoundInstance.Attenuation.NONE;
            updateLocation();
        }

        protected boolean updateAndCheckRemoved() {
            if (turbine.isRemoved() || turbine.getLevel() == null) {
                stop();
                return true;
            }
            updateLocation();
            return false;
        }

        protected void updateLocation() {
            if (turbine.getLevel() == null) return;
            Vec3 position = Sable.HELPER.projectOutOfSubLevel(
                    turbine.getLevel(), Vec3.atCenterOf(turbine.getBlockPos()));
            x = position.x;
            y = position.y;
            z = position.z;
        }

        protected float effectiveVolume() {
            float configured = Mth.clamp(
                    turbine.getSoundVolume()
                            / GasTurbineBlockEntity.MAX_SOUND_VOLUME,
                    0.0F,
                    1.0F
            );
            Vec3 listener = Minecraft.getInstance()
                    .gameRenderer.getMainCamera().getPosition();
            double distance = listener.distanceTo(new Vec3(x, y, z));
            float distanceGain = Mth.clamp(
                    (float) ((SILENT_DISTANCE - distance)
                            / (SILENT_DISTANCE - FULL_VOLUME_DISTANCE)),
                    0.0F,
                    1.0F
            );
            if (distance <= FULL_VOLUME_DISTANCE) distanceGain = 1.0F;
            return MASTER_VOLUME * configured * distanceGain;
        }

        protected void halt() {
            stop();
        }
    }

    private static final class TurbineLoopSound extends TrackedTurbineSound {
        private final LoopType type;

        private TurbineLoopSound(
                GasTurbineBlockEntity turbine,
                SoundEvent event,
                LoopType type
        ) {
            super(turbine, event);
            this.type = type;
            looping = true;
            delay = 0;
            tick();
        }

        @Override
        public void tick() {
            if (updateAndCheckRemoved()) return;
            float reference = Math.max(1.0F,
                    Math.max(turbine.getTurnSpeed(),
                            Math.max(turbine.getForwardSpeed(),
                                    turbine.getReverseSpeed())));
            float normalized = Mth.clamp(turbine.getCurrentMagnitude() / reference, 0, 1);
            float blend = normalized * normalized * (3.0F - 2.0F * normalized);
            State state = STATES.get(turbine);
            float ducking = state != null && state.transitionTicks > 0
                    ? TRANSITION_LOOP_DUCKING : 1.0F;
            float baseVolume = effectiveVolume() * ducking;
            if (type == LoopType.IDLE) {
                volume = baseVolume * Mth.sqrt(1.0F - blend);
                pitch = 0.92F + normalized * 0.08F;
            } else {
                volume = baseVolume * Mth.sqrt(blend);
                pitch = 0.88F + normalized * 0.12F;
            }
        }
    }

    private static final class TurbineOneShotSound extends TrackedTurbineSound {
        private TurbineOneShotSound(
                GasTurbineBlockEntity turbine,
                SoundEvent event
        ) {
            super(turbine, event);
            volume = effectiveVolume();
            pitch = 1.0F;
            looping = false;
        }

        @Override
        public void tick() {
            if (updateAndCheckRemoved()) return;
            volume = effectiveVolume();
        }
    }

    private GasTurbineSoundManager() {
    }
}
