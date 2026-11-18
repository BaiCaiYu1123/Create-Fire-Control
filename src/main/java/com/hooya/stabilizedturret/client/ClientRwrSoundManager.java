package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.RadarSessionPayload;
import com.hooya.stabilizedturret.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Separate radar scan clocks and one shared incoming-missile alarm. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ClientRwrSoundManager {
    private static final long BEEP_INTERVAL_TICKS = 20L;
    private static final Map<UUID, Long> LAST_BEEP = new HashMap<>();
    private static long lastClientTick = Long.MIN_VALUE;
    private static final ResourceLocation MISSILE_ALARM =
            ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "aps_off");
    private static long lastMissileBeep = Long.MIN_VALUE;
    private static SimpleSoundInstance missileSound;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.isPaused()
                || !ClientRadarState.isActive() || !ClientRadarState.isAircraftMode()) {
            reset();
            return;
        }
        long now = minecraft.level.getGameTime();
        if (lastClientTick != Long.MIN_VALUE && now < lastClientTick) reset();
        lastClientTick = now;
        boolean incoming = ClientRadarState.getThreats().stream().anyMatch(threat ->
                threat.type() == RadarSessionPayload.ThreatType.MSL && threat.incoming());
        if (!incoming) {
            stopMissileAlarm();
        } else if (lastMissileBeep == Long.MIN_VALUE || now - lastMissileBeep >= BEEP_INTERVAL_TICKS) {
            // Reuse Mianbao's own missile lock warning (LockonwarningProcedure).
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(MISSILE_ALARM).orElse(null);
            if (sound != null) {
                if (missileSound != null) minecraft.getSoundManager().stop(missileSound);
                missileSound = SimpleSoundInstance.forUI(sound, 1.0F);
                minecraft.getSoundManager().play(missileSound);
            }
            lastMissileBeep = now;
        }
        Set<UUID> activeRadars = new HashSet<>();
        for (ClientRadarState.Threat threat : ClientRadarState.getThreats()) {
            if (threat.type() == RadarSessionPayload.ThreatType.MSL) continue;
            activeRadars.add(threat.id());
            Long previous = LAST_BEEP.get(threat.id());
            if (previous != null && now - previous < BEEP_INTERVAL_TICKS) continue;
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(ModSounds.RWR_SCAN.get(), 1.0F));
            LAST_BEEP.put(threat.id(), now);
        }
        LAST_BEEP.keySet().removeIf(id -> !activeRadars.contains(id));
    }

    private static void reset() {
        stopMissileAlarm();
        LAST_BEEP.clear();
        lastClientTick = Long.MIN_VALUE;
    }

    private static void stopMissileAlarm() {
        if (missileSound != null) Minecraft.getInstance().getSoundManager().stop(missileSound);
        missileSound = null;
        lastMissileBeep = Long.MIN_VALUE;
    }

    private ClientRwrSoundManager() {
    }
}
