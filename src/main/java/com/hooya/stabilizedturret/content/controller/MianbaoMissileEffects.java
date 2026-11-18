package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import com.hooya.stabilizedturret.network.MianbaoDetonationVisualPayload;
import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import com.hooya.stabilizedturret.network.NuclearShockwavePayload;
import com.hooya.stabilizedturret.content.missile.MissilePhysicsConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;

/** Long-range visual synchronization for fire-control adopted missiles. */
public final class MianbaoMissileEffects {
    private static final String SENT_TAG = "CreateFireControlExplosionSent";
    private static final double MAX_EFFECT_DISTANCE_SQR = 20000.0 * 20000.0;
    /** Vanilla's forced particle packets reach root-level players within 512 blocks. */
    private static final double NATIVE_FORCED_PARTICLE_DISTANCE_SQR = 512.0 * 512.0;
    /** Matches Mianbao's original flash and nuclear camera-shake radius. */
    private static final double NUCLEAR_CLOSE_EFFECT_DISTANCE_SQR = 400.0 * 400.0;

    public static void broadcastExplosion(
            Entity missile, float strength, boolean nativeParticlesExpected
    ) {
        broadcastExplosion(
                missile,
                missile == null ? null : MianbaoMissileCompat.getWorldPosition(missile),
                strength,
                nativeParticlesExpected
        );
    }

    public static void broadcastExplosion(
            Entity missile, Vec3 position, float strength, boolean nativeParticlesExpected
    ) {
        if (missile == null || missile.level().isClientSide()
                || position == null || missile.getPersistentData().getBoolean(SENT_TAG)) {
            return;
        }
        boolean adopted = missile.getPersistentData().getBoolean("CreateFireControlGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAaGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided")
                || missile.getPersistentData().getBoolean("CreateFireControlCruiseGuided")
                || missile.getPersistentData().getBoolean(MianbaoRocketPodCompat.ADOPTED_TAG);
        if (!adopted || !MianbaoAirDefenseCompat.isAnyFireControlMissile(missile)) {
            return;
        }
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) return;
        missile.getPersistentData().putBoolean(SENT_TAG, true);
        MianbaoCruiseMissileCompat.Type cruiseType =
                MianbaoCruiseMissileCompat.missileType(missile);
        if (cruiseType != null) {
            broadcastDetonationVisual(root, position, visualProfile(cruiseType),
                    root.getRandom().nextLong(), nativeParticlesExpected);
            return;
        }
        MissileExplosionPayload payload = explosionPayload(
                missile, position, strength, nativeParticlesExpected);
        // Players inside Sable sublevels are not necessarily present in
        // ServerLevel#players() for the root level. Iterate every server level
        // and compare projected world coordinates so a distant explosion is
        // delivered to the actual viewers as well.
        Set<ServerPlayer> sent = new HashSet<>();
        for (ServerLevel candidate : root.getServer().getAllLevels()) {
            for (ServerPlayer player : candidate.players()) {
                sendIfInRange(root, player, position, payload, sent);
            }
        }
        // Sable sublevels are attached to the root level rather than being
        // registered as ordinary Minecraft server dimensions.
        for (SubLevel sub : SubLevelContainer.getContainer(root).getAllSubLevels()) {
            if (sub == null || sub.isRemoved() || !(sub.getLevel() instanceof ServerLevel candidate)) {
                continue;
            }
            for (ServerPlayer player : candidate.players()) {
                sendIfInRange(root, player, position, payload, sent);
            }
        }
    }

    /** Shared by contact, proximity and timeout detonations in both flight backends. */
    public static MissileExplosionPayload explosionPayload(
            Entity missile, Vec3 position, float strength, boolean nativeParticlesExpected
    ) {
        boolean nuclear = isNuclear(missile);
        byte profile = MianbaoAirDefenseCompat.isMissile(missile)
                ? MissileExplosionPayload.AIR_DEFENSE
                : MianbaoMissileDetonation.usesTripledStrength(MianbaoAircraftCompat.typeOf(missile))
                ? MissileExplosionPayload.AIRCRAFT : MissileExplosionPayload.GENERIC;
        return new MissileExplosionPayload(position.x, position.y, position.z, strength,
                nativeParticlesExpected && !nuclear, nuclear, profile);
    }

    public static void broadcastVirtualRocketExplosion(ServerLevel root, Vec3 position, float strength) {
        MissileExplosionPayload payload = new MissileExplosionPayload(
                position.x, position.y, position.z, strength, false, false, false);
        for (ServerPlayer player : root.getServer().getPlayerList().getPlayers()) {
            if (MianbaoMissileCompat.getRootServerLevel(player) == root
                    && MianbaoMissileCompat.getWorldPosition(player).distanceToSqr(position) <= MAX_EFFECT_DISTANCE_SQR) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /** Broadcasts one detached Mianbao visual/audio timeline without detonating anything. */
    public static void broadcastDetonationVisual(
            ServerLevel root, Vec3 position, byte profile, long seed
    ) {
        broadcastDetonationVisual(root, position, profile, seed, false);
    }

    public static void broadcastDetonationVisual(
            ServerLevel root, Vec3 position, byte profile, long seed,
            boolean remoteOnly
    ) {
        if (root == null || position == null
                || profile < MianbaoDetonationVisualPayload.BOMB
                || profile > MianbaoDetonationVisualPayload.BALLISTIC_NUCLEAR) {
            return;
        }
        MianbaoDetonationVisualPayload payload = new MianbaoDetonationVisualPayload(
                profile, position.x, position.y, position.z, seed, true, false);
        Set<ServerPlayer> sent = new HashSet<>();
        for (ServerLevel candidate : root.getServer().getAllLevels()) {
            for (ServerPlayer player : candidate.players()) {
                sendDetonationIfInRange(root, player, position, payload, sent,
                        remoteOnly);
            }
        }
        for (SubLevel sub : SubLevelContainer.getContainer(root).getAllSubLevels()) {
            if (sub == null || sub.isRemoved()
                    || !(sub.getLevel() instanceof ServerLevel candidate)) {
                continue;
            }
            for (ServerPlayer player : candidate.players()) {
                sendDetonationIfInRange(root, player, position, payload, sent,
                        remoteOnly);
            }
        }
    }

    /**
     * The Mianbao nuke marker emits its fire ring after the initial terrain
     * work has completed. Those later particle and explosion packets do not
     * reach a distant player, so relay each real marker step explicitly.
     */
    public static void broadcastNuclearShockwave(
            ServerLevel root, Vec3 position, int radius, long seed
    ) {
        if (root == null || position == null || radius < 1 || radius > 30) return;
        NuclearShockwavePayload payload = new NuclearShockwavePayload(
                position.x, position.y, position.z, radius, seed);
        Set<ServerPlayer> sent = new HashSet<>();
        for (ServerLevel candidate : root.getServer().getAllLevels()) {
            for (ServerPlayer player : candidate.players()) {
                sendShockwaveIfInRange(root, player, position, payload, sent);
            }
        }
        for (SubLevel sub : SubLevelContainer.getContainer(root).getAllSubLevels()) {
            if (sub == null || sub.isRemoved() || !(sub.getLevel() instanceof ServerLevel candidate)) {
                continue;
            }
            for (ServerPlayer player : candidate.players()) {
                sendShockwaveIfInRange(root, player, position, payload, sent);
            }
        }
    }

    private static void sendIfInRange(ServerLevel root, ServerPlayer player,
                                      Vec3 position, MissileExplosionPayload payload,
                                      Set<ServerPlayer> sent) {
        if (!sent.add(player) || MianbaoMissileCompat.getRootServerLevel(player) != root) return;
        Vec3 playerPosition = MianbaoMissileCompat.getWorldPosition(player);
        if (playerPosition.distanceToSqr(position) <= effectDistanceSqr()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static void sendShockwaveIfInRange(ServerLevel root, ServerPlayer player,
                                                Vec3 position, NuclearShockwavePayload payload,
                                                Set<ServerPlayer> sent) {
        if (!sent.add(player) || MianbaoMissileCompat.getRootServerLevel(player) != root) return;
        Vec3 playerPosition = MianbaoMissileCompat.getWorldPosition(player);
        if (playerPosition.distanceToSqr(position) <= effectDistanceSqr()) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static void sendDetonationIfInRange(
            ServerLevel root, ServerPlayer player, Vec3 position,
            MianbaoDetonationVisualPayload payload, Set<ServerPlayer> sent
    ) {
        sendDetonationIfInRange(root, player, position, payload, sent, false);
    }

    private static void sendDetonationIfInRange(
            ServerLevel root, ServerPlayer player, Vec3 position,
            MianbaoDetonationVisualPayload payload, Set<ServerPlayer> sent,
            boolean remoteOnly
    ) {
        if (!sent.add(player) || MianbaoMissileCompat.getRootServerLevel(player) != root) return;
        Vec3 playerPosition = MianbaoMissileCompat.getWorldPosition(player);
        double distanceSqr = playerPosition.distanceToSqr(position);
        if (distanceSqr > effectDistanceSqr()) return;
        // Mianbao's native nuclear commands use the `force` particle mode.
        // Chunk tracking is therefore the wrong duplicate-suppression test:
        // a root-level player can receive those particles up to 512 blocks
        // away even when the impact chunk is outside the ordinary view.
        boolean receivesNativeForcedParticles = player.serverLevel() == root
                && distanceSqr <= NATIVE_FORCED_PARTICLE_DISTANCE_SQR;
        if (!remoteOnly || !receivesNativeForcedParticles) {
            boolean closePlayerEffects = !isNuclearProfile(payload.profile())
                    || distanceSqr <= NUCLEAR_CLOSE_EFFECT_DISTANCE_SQR;
            PacketDistributor.sendToPlayer(player,
                    new MianbaoDetonationVisualPayload(
                            payload.profile(), payload.x(), payload.y(), payload.z(),
                            payload.seed(), payload.initialEffects(), closePlayerEffects));
        }
    }

    public static float visualStrength(Entity missile) {
        if (MianbaoRocketPodCompat.isRocket(missile)) {
            return MianbaoRocketPodCompat.nativeExplosionStrength(missile);
        }
        if (MianbaoCruiseMissileCompat.isMissile(missile)) {
            return MianbaoCruiseMissileCompat.visualStrength(missile);
        }
        if (MianbaoAirDefenseCompat.isMissile(missile)) {
            return MianbaoMissileDetonation.explosionStrength(missile);
        }
        MianbaoAircraftCompat.WeaponType aircraft = MianbaoAircraftCompat.typeOf(missile);
        if (MianbaoAircraftCompat.isAntiRadiation(aircraft)) return 6.0F;
        if (MianbaoMissileDetonation.usesTripledStrength(aircraft)) {
            return MianbaoMissileDetonation.TRIPLED_STRENGTH;
        }
        return aircraft == MianbaoAircraftCompat.WeaponType.LASER_GUIDED ? 3.0F : 2.0F;
    }

    private static boolean isNuclear(Entity missile) {
        MianbaoCruiseMissileCompat.Type type =
                MianbaoCruiseMissileCompat.missileType(missile);
        return type != null && type.nuclear();
    }

    private static byte visualProfile(MianbaoCruiseMissileCompat.Type type) {
        if (type.nuclear()) return MianbaoDetonationVisualPayload.NUCLEAR;
        return type.usesConventionalCruiseWarhead()
                ? MianbaoDetonationVisualPayload.BOMB
                : MianbaoDetonationVisualPayload.GROUND;
    }

    private static boolean isNuclearProfile(byte profile) {
        return profile == MianbaoDetonationVisualPayload.NUCLEAR
                || profile == MianbaoDetonationVisualPayload.BALLISTIC_NUCLEAR;
    }

    private static double effectDistanceSqr() {
        double configured = MissilePhysicsConfig.MAX_RENDER_DISTANCE.get();
        return Math.max(MAX_EFFECT_DISTANCE_SQR, configured * configured);
    }

    private MianbaoMissileEffects() {
    }
}
