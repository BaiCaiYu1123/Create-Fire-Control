package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/** Suppresses the native per-pod launch sounds while a grouped salvo is fired. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoRocketPodSoundGuard {
    static final ResourceLocation LAUNCH_SOUND = ResourceLocation.fromNamespaceAndPath(
            MianbaoAircraftCompat.MOD_ID, "rocket_launcher");

    private static final ThreadLocal<Integer> SUPPRESSION_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    static Scope suppressNativeLaunchSounds() {
        SUPPRESSION_DEPTH.set(SUPPRESSION_DEPTH.get() + 1);
        return new Scope();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlaySound(PlayLevelSoundEvent.AtPosition event) {
        if (SUPPRESSION_DEPTH.get() > 0
                && event.getSound().value().getLocation().equals(LAUNCH_SOUND)) {
            event.setCanceled(true);
        }
    }

    static final class Scope implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            int depth = SUPPRESSION_DEPTH.get() - 1;
            if (depth <= 0) {
                SUPPRESSION_DEPTH.remove();
            } else {
                SUPPRESSION_DEPTH.set(depth);
            }
        }
    }

    private MianbaoRocketPodSoundGuard() {
    }
}
