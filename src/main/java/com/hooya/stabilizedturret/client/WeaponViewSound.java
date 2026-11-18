package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight;
import com.hooya.stabilizedturret.content.controller.LandAttackMissileFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileFlightProfile;
import dev.ryanhcode.sable.sound.MovingSoundInstanceDelegate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;

import java.util.ArrayList;
import java.util.UUID;

/** Observer-only native engine samples. The player and server sound recipients stay at the aircraft. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class WeaponViewSound {
    private static final ResourceLocation ENGINE = ResourceLocation.fromNamespaceAndPath(
            "mianbaos_modernwarfare", "missile_engine_far");
    private static final ArrayList<FlightSound> ACTIVE = new ArrayList<>();
    private static long emissionTick = Long.MIN_VALUE;

    static void tick(Entity model) {
        Minecraft mc = Minecraft.getInstance();
        if (model == null || !MianbaoAirDefenseCompat.isAnyFireControlMissile(model)
                || Boolean.FALSE.equals(MathematicalMissileVisuals.emitting(model.getUUID()))
                || !AirToAirMissileFlight.emitting(model) || !LandAttackMissileFlight.emitting(model)) {
            clear();
            return;
        }
        if (mc.isPaused()) return;
        long now = ClientWeaponViewState.animationTick();
        var manager = mc.getSoundManager();
        ACTIVE.removeIf(sound -> {
            if (sound.isStopped() || now - sound.bornAt > 2 && !manager.isActive(sound.playback)) {
                sound.halt();
                return true;
            }
            return false;
        });
        if (emissionTick == now) return;
        emissionTick = now;
        var event = BuiltInRegistries.SOUND_EVENT.getOptional(ENGINE).orElse(null);
        if (event == null) return;
        boolean large = MianbaoMissileFlightProfile.of(BuiltInRegistries.ENTITY_TYPE
                .getKey(model.getType()).getPath()).large();
        // Native smoke procedures emit one engine sample per flight tick, with this pitch range.
        FlightSound sound = new FlightSound(event, model.getUUID(), large ? 10 : 1,
                1 + mc.level.random.nextFloat() * .5F, now);
        ACTIVE.add(sound);
        manager.play(sound);
    }

    static void clear() {
        ACTIVE.forEach(FlightSound::halt);
        ACTIVE.clear();
        emissionTick = Long.MIN_VALUE;
    }

    @SubscribeEvent public static void sourceStarted(PlaySoundSourceEvent event) {
        SoundInstance instance = event.getSound();
        while (instance instanceof MovingSoundInstanceDelegate delegate) instance = delegate.instance;
        if (instance instanceof FlightSound sound) sound.playback = event.getSound();
    }

    private static final class FlightSound extends AbstractTickableSoundInstance {
        final UUID missileId;
        final ClientLevel level = Minecraft.getInstance().level;
        final long bornAt;
        volatile SoundInstance playback = this;

        FlightSound(SoundEvent event, UUID missileId, float volume, float pitch, long bornAt) {
            super(event, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.missileId = missileId;
            this.bornAt = bornAt;
            // Relative playback caps native gain at 1; halve that audible level for every missile size.
            this.volume = Math.min(volume, 1.0F) * 0.5F;
            this.pitch = pitch;
            // A fixed world-space sample is immediately left behind by a supersonic camera.
            // Listener-relative playback also avoids depending on loaded chunks or player position.
            relative = true;
            attenuation = Attenuation.NONE;
        }

        @Override public void tick() {
            if (Minecraft.getInstance().level != level || !ClientWeaponViewState.observes(missileId)) halt();
        }

        void halt() {
            stop();
            Minecraft.getInstance().getSoundManager().stop(playback);
        }
    }

    private WeaponViewSound() {}
}
