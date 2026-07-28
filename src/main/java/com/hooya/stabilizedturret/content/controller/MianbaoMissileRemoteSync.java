package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.network.MissileRemoteStatePayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Visual-only synchronization which never loads terrain or adds chunk tickets. */
public final class MianbaoMissileRemoteSync {
    private static final double MAX_SYNC_DISTANCE_SQR = 20000.0 * 20000.0;

    public static void broadcast(Entity missile) {
        if (missile == null || missile.level().isClientSide()
                || !MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)) return;
        boolean adopted = missile.getPersistentData().getBoolean("CreateFireControlGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided");
        if (!adopted) return;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) return;
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
        MissileRemoteStatePayload payload = new MissileRemoteStatePayload(
                missile.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()),
                position.x, position.y, position.z, velocity.x, velocity.y, velocity.z);
        for (ServerPlayer player : root.players()) {
            if (player.position().distanceToSqr(position) <= MAX_SYNC_DISTANCE_SQR) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private MianbaoMissileRemoteSync() {
    }
}