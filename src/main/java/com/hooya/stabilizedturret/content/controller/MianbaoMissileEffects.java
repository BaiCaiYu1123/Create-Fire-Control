package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Long-range visual synchronization for fire-control adopted missiles. */
public final class MianbaoMissileEffects {
    private static final String SENT_TAG = "CreateFireControlExplosionSent";
    private static final double MAX_EFFECT_DISTANCE_SQR = 20000.0 * 20000.0;

    public static void broadcastExplosion(
            Entity missile, float strength, boolean nativeParticlesExpected
    ) {
        if (missile == null || missile.level().isClientSide()
                || missile.getPersistentData().getBoolean(SENT_TAG)) {
            return;
        }
        boolean adopted = missile.getPersistentData().getBoolean("CreateFireControlGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided");
        if (!adopted || !MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)) {
            return;
        }
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) return;
        missile.getPersistentData().putBoolean(SENT_TAG, true);
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        MissileExplosionPayload payload = new MissileExplosionPayload(
                position.x, position.y, position.z, strength, nativeParticlesExpected);
        for (ServerPlayer player : root.players()) {
            if (player.position().distanceToSqr(position) <= MAX_EFFECT_DISTANCE_SQR) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    public static float visualStrength(Entity missile) {
        return MianbaoAirDefenseCompat.isMissile(missile) ? 5.0F : 2.0F;
    }

    private MianbaoMissileEffects() {
    }
}
