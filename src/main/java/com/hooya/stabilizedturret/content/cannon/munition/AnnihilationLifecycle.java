package com.hooya.stabilizedturret.content.cannon.munition;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;

/** Ages shots on the server clock even when their chunk stops ticking. */
public final class AnnihilationLifecycle {
    private static final Map<AnnihilationProjectile, Long> ACTIVE = new HashMap<>();

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, AnnihilationLifecycle::onJoin);
        NeoForge.EVENT_BUS.addListener(AnnihilationLifecycle::onTick);
        NeoForge.EVENT_BUS.addListener(AnnihilationLifecycle::onStop);
    }

    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (AnnihilationEntityRemoval.get(level).contains(event.getEntity().getUUID())) {
            event.setCanceled(true);
            ((AnnihilationErasure) event.getEntity()).stabilizedTurret$erase();
            return;
        }
        if (event.getEntity() instanceof AnnihilationProjectile shot) {
            if (level.getGameTime() >= shot.expiryTick()) {
                event.setCanceled(true);
                shot.discard();
            } else {
                ACTIVE.put(shot, shot.expiryTick());
            }
        }
    }

    public static void onTick(ServerTickEvent.Post event) {
        var iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var shot = entry.getKey();
            if (shot.isRemoved()) {
                iterator.remove();
            } else if (shot.level().getGameTime() >= entry.getValue()) {
                iterator.remove();
                shot.discard();
            }
        }
    }

    public static void onStop(ServerStoppingEvent event) {
        ACTIVE.clear();
    }

    private AnnihilationLifecycle() {}
}
