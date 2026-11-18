package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.display.BallisticMissileGuidance;
import com.hooya.stabilizedturret.network.MissileRemoteStatePayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Visual-only synchronization for supported remote projectiles. */
public final class MianbaoMissileRemoteSync {
    private static final double MAX_SYNC_DISTANCE_SQR = 20000.0 * 20000.0;
    private static final int MAX_ROCKET_SYNCS_PER_TICK = 24;

    public static void broadcast(Entity missile) {
        if (MathematicalMissileManager.contains(missile)) return;
        if (missile == null || missile.level().isClientSide()) return;
        boolean countermeasure = MianbaoCountermeasureCompat.isCountermeasure(missile)
                && missile.getPersistentData().getBoolean(
                MianbaoCountermeasureCompat.TRACKED_TAG);
        if (countermeasure) return; // The countermeasure manager owns the batched channel.
        if (!countermeasure && !MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)) return;
        boolean adopted = missile.getPersistentData().getBoolean("CreateFireControlGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlCruiseGuided")
                || missile.getPersistentData().getBoolean(
                BallisticMissileGuidance.GUIDED_TAG)
                || missile.getPersistentData().getBoolean(MianbaoRocketPodCompat.ADOPTED_TAG);
        if (!adopted && !countermeasure) return;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) return;
        if (MianbaoRocketPodCompat.isRocket(missile)) {
            // Spread large salvos over several ticks instead of constructing
            // and handling one packet for every rocket in the same frame. The
            // client extrapolates between authoritative updates.
            int activeRockets = Math.max(1,
                    MianbaoRocketPodProjectileHandler.activeRocketCount());
            int interval = Math.max(2, (activeRockets
                    + MAX_ROCKET_SYNCS_PER_TICK - 1) / MAX_ROCKET_SYNCS_PER_TICK);
            int slot = Math.floorMod(missile.getUUID().hashCode(), interval);
            if (Math.floorMod(root.getGameTime(), (long) interval) != slot) return;
        }
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
        Quaternionf rotation = MianbaoBallisticMissileCompat.renderRotation(missile);
        if (rotation == null) {
            Vec3 direction = velocity.lengthSqr() < 1.0e-8
                    ? new Vec3(0.0, 1.0, 0.0) : velocity.normalize();
            rotation = new Quaternionf().rotationTo(
                    new Vector3f(0.0F, 1.0F, 0.0F),
                    new Vector3f((float) direction.x, (float) direction.y,
                            (float) direction.z));
        }
        MissileRemoteStatePayload payload = new MissileRemoteStatePayload(
                missile.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()),
                position.x, position.y, position.z, velocity.x, velocity.y, velocity.z,
                rotation.x, rotation.y, rotation.z, rotation.w,
                MianbaoBallisticMissileCompat.isDf26(missile),
                root.getGameTime(), AirToAirMissileFlight.packetAge(missile), LandAttackMissileFlight.packetAge(missile));
        // PlayerList also contains viewers inside Sable sublevels. Their
        // plot-space coordinates must be projected before the range test.
        for (ServerPlayer player : root.getServer().getPlayerList().getPlayers()) {
            if (MianbaoMissileCompat.getRootServerLevel(player) == root
                    && MianbaoMissileCompat.getWorldPosition(player).distanceToSqr(position) <= MAX_SYNC_DISTANCE_SQR) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    public static void remove(Entity missile) {
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) return;
        var payload = new com.hooya.stabilizedturret.network.MissileRemoteRemovalPayload(
                root.dimension().location(), missile.getUUID());
        for (ServerPlayer player : root.getServer().getPlayerList().getPlayers()) {
            if (MianbaoMissileCompat.getRootServerLevel(player) == root) PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private MianbaoMissileRemoteSync() {
    }
}
