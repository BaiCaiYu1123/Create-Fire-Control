package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One moving native engine sound per adopted AAM, stopped at the authoritative burn boundary. */
public final class AirToAirMissileSounds {
    private static final Map<UUID, EngineSound> ACTIVE = new HashMap<>();

    public static void update(UUID id, Entity missile) {
        var mc = Minecraft.getInstance();
        if (!AirToAirMissileFlight.managed(missile)) return;
        if (!AirToAirMissileFlight.emitting(missile)) {
            EngineSound old = ACTIVE.remove(id);
            if (old != null) mc.getSoundManager().stop(old);
            return;
        }
        EngineSound sound = ACTIVE.get(id);
        if (sound == null || sound.isStopped()) {
            var event = BuiltInRegistries.SOUND_EVENT.getOptional(ResourceLocation.fromNamespaceAndPath(
                    "mianbaos_modernwarfare", "missile_engine_far")).orElse(null);
            if (event == null) return;
            sound = new EngineSound(id, missile, event, mc.level);
            ACTIVE.put(id, sound);
            mc.getSoundManager().play(sound);
        }
        sound.missile = missile;
        sound.receivedAt = missile.level().getGameTime();
    }

    private static final class EngineSound extends AbstractTickableSoundInstance {
        private final UUID id;
        private final ClientLevel level;
        private Entity missile;
        private long receivedAt;

        private EngineSound(UUID id, Entity missile, SoundEvent event, ClientLevel level) {
            super(event, SoundSource.NEUTRAL, RandomSource.create());
            this.id = id; this.missile = missile; this.level = level;
            this.looping = true; this.delay = 0; this.volume = 1; this.pitch = 1;
            receivedAt = missile.level().getGameTime();
            position();
        }

        private void position() {
            var point = MianbaoMissileCompat.getWorldPosition(missile);
            x = point.x; y = point.y; z = point.z;
        }

        @Override public void tick() {
            if (Minecraft.getInstance().level != level || missile.isRemoved()
                    || missile.level().getGameTime() - receivedAt > 20
                    || !AirToAirMissileFlight.emitting(missile)) {
                stop(); ACTIVE.remove(id, this); return;
            }
            position();
        }
    }

    private AirToAirMissileSounds() { }
}
