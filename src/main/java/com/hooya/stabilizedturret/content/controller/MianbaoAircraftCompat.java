package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Optional, registry-only integration for Mianbao aircraft racks and radar. */
public final class MianbaoAircraftCompat {
    public static final String MOD_ID = "mianbaos_modernwarfare";
    private static final ResourceLocation EMPTY_RACK = id("rack");
    private static final ResourceLocation RADAR = id("jetradar");
    private static final Map<ResourceLocation, WeaponType> LOADED = loadedBlocks();
    private static final Map<ResourceLocation, WeaponType> MISSILES = missileEntities();
    private static final Set<ResourceLocation> RACKS = rackBlocks();
    private static final Map<WeaponType, Optional<Method>> FIRE_METHODS = new ConcurrentHashMap<>();

    public enum WeaponType {
        FAR,
        MEDIUM,
        CLOSE,
        AIR_TO_GROUND_DIRECT,
        AIR_TO_GROUND_TOP_ATTACK,
        LASER_GUIDED,
        ROCKET_POD,
        /** Player-steered television/optical missile. Appended for NBT ordinal compatibility. */
        OPTICAL_GUIDED,
        /** MICA-EM stays distinct in weapon selection. Appended to preserve every existing NBT ordinal. */
        MICA_EM,
        /** AIM-120D shares the MICA-EM profile but remains independently selectable. */
        AIM_120D,
        // Append only: weapon ordinals are persisted in existing worlds.
        BOMB, MEDIUM_BOMB, CLUSTER_BOMB, EARTH_PENETRATOR_BOMB,
        FIRE_BOMB, RETARDED_BOMB, LASER_BOMB, JDAM_BOMB,
        NUCLEAR_BOMB, WHITE_PHOSPHORUS_BOMB, GAS_BOMB, GLIDE_BOMB, SMALL_BOMB,
        ANTI_RADIATION;

        public String translationKey() {
            return "weapon.create_fire_control.aircraft." + name().toLowerCase(Locale.ROOT);
        }

        public Component displayName() {
            return Component.translatable(translationKey());
        }
    }

    private static Map<ResourceLocation, WeaponType> loadedBlocks() {
        Map<ResourceLocation, WeaponType> result = new HashMap<>();
        registerVariants(result, WeaponType.FAR,
                "far_missile_1rack", "far_missile_2rack");
        registerVariants(result, WeaponType.MEDIUM,
                "medium_missile_rack", "medium_ai_mmissile_2rack");
        registerVariants(result, WeaponType.CLOSE,
                "close_missile_1rack", "close_missile_2rack");
        registerVariants(result, WeaponType.AIR_TO_GROUND_DIRECT,
                "agm_missile_1rack", "agm_missile_3rack");
        registerVariants(result, WeaponType.AIR_TO_GROUND_TOP_ATTACK,
                "agm_missile_2rack", "agm_missile_4rack");
        registerVariants(result, WeaponType.LASER_GUIDED,
                "laser_guided_missile_rack");
        registerVariants(result, WeaponType.OPTICAL_GUIDED,
                "opticalguidedmissilerack");
        registerVariants(result, WeaponType.MICA_EM,
                "mica_em_rack");
        registerVariants(result, WeaponType.AIM_120D,
                "aim_120_rack");
        registerVariants(result, WeaponType.ANTI_RADIATION, "antiradiationmissilerack");
        return Map.copyOf(result);
    }

    private static Map<ResourceLocation, WeaponType> missileEntities() {
        Map<ResourceLocation, WeaponType> result = new HashMap<>();
        registerVariants(result, WeaponType.FAR,
                "farmissile_1tanshe", "farmissile_2tanshe");
        registerVariants(result, WeaponType.MEDIUM,
                "missile_2tanshe", "medium_aim_missile_2tanshe");
        registerVariants(result, WeaponType.CLOSE,
                "closemissile_1tanshe", "closemissile_2tanshe");
        registerVariants(result, WeaponType.AIR_TO_GROUND_DIRECT,
                "agm_1tanshe", "agm_3tanshe", "agmmissile_3");
        registerVariants(result, WeaponType.AIR_TO_GROUND_TOP_ATTACK,
                "agm_2tanshe", "agm_4tanshe", "agmmissile_4");
        registerVariants(result, WeaponType.LASER_GUIDED,
                "missiletanshe");
        registerVariants(result, WeaponType.OPTICAL_GUIDED,
                "opticalguidedmissiletanshe");
        registerVariants(result, WeaponType.MICA_EM,
                "mica_em_tanshe");
        registerVariants(result, WeaponType.AIM_120D,
                "aim_120_tanshe");
        registerVariants(result, WeaponType.ANTI_RADIATION, "antiradiationmissiletanshe");
        return Map.copyOf(result);
    }

    private static void registerVariants(
            Map<ResourceLocation, WeaponType> result,
            WeaponType type,
            String... paths
    ) {
        for (String path : paths) result.put(id(path), type);
    }

    private static Set<ResourceLocation> rackBlocks() {
        Set<ResourceLocation> result = new HashSet<>(LOADED.keySet());
        result.addAll(MianbaoBombCompat.rackIds());
        result.add(EMPTY_RACK);
        return Set.copyOf(result);
    }

    public static boolean isRack(BlockState state) {
        return RACKS.contains(blockId(state)) || MianbaoAircraftRackCompat.isMixedRack(state);
    }
    public static boolean isRadar(BlockState state) { return RADAR.equals(blockId(state)); }
    public static boolean isSameRackVariant(ResourceLocation a, ResourceLocation b) {
        // The inventory-backed rack keeps its block entity on height/ammo changes.
        // Breaking it must invalidate the old binding, even at the same position.
        if (MianbaoAircraftRackCompat.isMixedRackId(a) || MianbaoAircraftRackCompat.isMixedRackId(b)) return false;
        return RACKS.contains(a) && RACKS.contains(b);
    }
    public static @Nullable WeaponType weaponAt(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        if (!level.hasChunkAt(ref.pos()) || !ref.isSameTarget(level)) return null;
        ResourceLocation current = blockId(level.getBlockState(ref.pos()));
        WeaponType bomb = MianbaoBombCompat.weaponAt(current);
        return bomb != null ? bomb : LOADED.get(current);
    }
    public static int ammoCount(Level level, BindingRef ref, WeaponType type) {
        if (weaponAt(level, ref) != type) return 0;
        return MianbaoBombCompat.isBomb(type)
                ? MianbaoBombCompat.ammoAt(blockId(level.getBlockState(ref.pos()))) : 1;
    }
    public static boolean isMissile(Entity entity) { return typeOf(entity) != null; }
    /** Returns true for every aircraft-launched air-to-ground missile family. */
    public static boolean isAirToGroundMissile(Entity entity) {
        if (entity == null) return false;
        WeaponType type = typeOf(entity);
        return isAirToGround(type) || isLaserGuided(type) || isOpticalGuided(type) || isAntiRadiation(type);
    }
    public static boolean isAirToAir(WeaponType type) {
        return type == WeaponType.FAR || type == WeaponType.MEDIUM
                || type == WeaponType.CLOSE || type == WeaponType.MICA_EM
                || type == WeaponType.AIM_120D;
    }
    public static boolean isInfrared(WeaponType type) { return type == WeaponType.CLOSE; }
    public static boolean isRadarGuided(WeaponType type) { return isAirToAir(type) && !isInfrared(type); }
    public static boolean isAirToGround(WeaponType type) {
        return type == WeaponType.AIR_TO_GROUND_DIRECT
                || type == WeaponType.AIR_TO_GROUND_TOP_ATTACK;
    }
    public static boolean isLaserGuided(WeaponType type) {
        return type == WeaponType.LASER_GUIDED;
    }
    public static boolean isAntiRadiation(WeaponType type) {
        return type == WeaponType.ANTI_RADIATION;
    }
    public static boolean isOpticalGuided(WeaponType type) {
        return type == WeaponType.OPTICAL_GUIDED;
    }
    public static @Nullable WeaponType typeOf(Entity entity) {
        ResourceLocation current = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return MISSILES.get(current);
    }
    public static int lifetimeTicks(WeaponType type) {
        return switch (type) {
            case FAR -> 120 * 20;
            case MEDIUM, MICA_EM, AIM_120D -> 80 * 20;
            case CLOSE -> 30 * 20;
            case ANTI_RADIATION -> 400;
            case AIR_TO_GROUND_DIRECT, AIR_TO_GROUND_TOP_ATTACK, LASER_GUIDED -> 300;
            case OPTICAL_GUIDED -> 20 * 20;
            case ROCKET_POD -> MianbaoRocketPodCompat.LIFETIME_TICKS;
            default -> MianbaoBombBallistics.MAX_TICKS;
        };
    }
    public static int airToAirBurnTicks(WeaponType type) {
        if (type == WeaponType.CLOSE) return 5 * 20;
        if (type == WeaponType.MEDIUM || type == WeaponType.MICA_EM || type == WeaponType.AIM_120D) return 8 * 20;
        return type == WeaponType.FAR ? 12 * 20 : 0;
    }
    public static int maneuverDelayTicks(WeaponType type) {
        // Native far/medium/close AAMs acquire at time >= 10. AGM1 starts
        // after 10, AGM2 at 10; the laser missile has no native waiting stage.
        if (isAirToAir(type) || type == WeaponType.AIR_TO_GROUND_TOP_ATTACK) return 9;
        return type == WeaponType.AIR_TO_GROUND_DIRECT ? 10 : 0;
    }
    public static double maximumSpeedBlocksPerTick(WeaponType type) {
        // One block is one metre; anti-radiation missiles reach Mach 3 in five seconds.
        if (type == WeaponType.ANTI_RADIATION) return 1020.9 / 20.0;
        if (type == WeaponType.OPTICAL_GUIDED) return 170.15 / 20.0;
        return type == WeaponType.ROCKET_POD
                ? MianbaoRocketPodCompat.MUZZLE_SPEED
                : isAirToAir(type) ? 680.6 / 20.0 : 340.3 / 20.0;
    }
    public static int accelerationTicks(WeaponType type) {
        return 5 * 20;
    }

    public static AcceleratingMissileIntercept.SpeedProfile speedProfile(WeaponType type, double launchSpeed) {
        // Aircraft motion is inherited once at launch. A carrier can exceed
        // the missile's scheduled maximum, so the profile also permits a
        // gradual reduction from that initial world speed.
        double initialSpeed = Double.isFinite(launchSpeed) ? Math.max(0.0, launchSpeed) : 0.0;
        return new AcceleratingMissileIntercept.SpeedProfile(
                initialSpeed, maximumSpeedBlocksPerTick(type), accelerationTicks(type));
    }
    /** Actual model, rather than the rack family, owns its configurable profile. */
    public static AcceleratingMissileIntercept.SpeedProfile speedProfile(Entity missile, double launchSpeed) {
        return new AcceleratingMissileIntercept.SpeedProfile(
                Double.isFinite(launchSpeed) ? Math.max(0.0, launchSpeed) : 0.0,
                MissileSettings.speedBlocksPerTick(missile),
                MissileSettings.accelerationTicks(missile, accelerationTicks(typeOf(missile))));
    }
    public static double maxTurnRadians(WeaponType type) {
        double baseDegrees = switch (type) {
            case FAR -> 10.0;
            case MEDIUM, MICA_EM, AIM_120D -> 14.0;
            case CLOSE -> 22.0;
            case AIR_TO_GROUND_DIRECT -> 8.0;
            case AIR_TO_GROUND_TOP_ATTACK -> 7.0;
            case LASER_GUIDED, ANTI_RADIATION -> 8.0;
            case OPTICAL_GUIDED -> 18.0;
            case ROCKET_POD -> 0.0;
            default -> 0.0;
        };
        return Math.toRadians(baseDegrees / turnRadiusScale(type));
    }
    static double turnRadiusScale(WeaponType type) {
        if (type == WeaponType.MICA_EM || type == WeaponType.AIM_120D) return 0.25;
        return type == WeaponType.MEDIUM || type == WeaponType.CLOSE ? 0.5 : 1.0;
    }

    public static double maxLateralAcceleration(WeaponType type) {
        double maximumG = switch (type) {
            case FAR -> 400.0;
            case MEDIUM, MICA_EM, AIM_120D -> 450.0;
            case CLOSE -> 500.0;
            case AIR_TO_GROUND_DIRECT, LASER_GUIDED, ANTI_RADIATION -> 45.0;
            case AIR_TO_GROUND_TOP_ATTACK -> 40.0;
            case OPTICAL_GUIDED, ROCKET_POD -> 0.0;
            default -> 0.0;
        };
        return maximumG / turnRadiusScale(type) * (9.80665 / 400.0);
    }

    public static double interceptMaxTurnRadians(WeaponType type, double speed) {
        return MissileSteeringLimits.maxTurn(speed, maximumSpeedBlocksPerTick(type),
                maxTurnRadians(type), maxLateralAcceleration(type));
    }
    public static boolean fireOne(Level level, BindingRef ref, WeaponType expected) {
        if (MianbaoBombCompat.isBomb(expected)) return MianbaoBombCompat.fireOne(level, ref, expected);
        if (expected == WeaponType.ROCKET_POD) return false;
        if (weaponAt(level, ref) != expected) return false;
        BlockEntity be = ref.resolve(level);
        if (be == null || be.getLevel() == null) return false;
        Optional<Method> method = FIRE_METHODS.computeIfAbsent(expected, MianbaoAircraftCompat::findFireMethod);
        if (method.isEmpty()) return false;
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            method.get().invoke(null, be.getLevel(), (double) ref.pos().getX(),
                    (double) ref.pos().getY(), (double) ref.pos().getZ());
            return weaponAt(level, ref) == null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            StabilizedTurret.LOGGER.error("Unable to fire aircraft rack {} at {}", expected, ref.pos(), ex);
            return false;
        }
    }
    public static @Nullable Vec3 muzzle(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null || blockEntity.getLevel() == null) return null;
        Level targetLevel = blockEntity.getLevel();
        BlockState state = blockEntity.getBlockState();
        return Sable.HELPER.projectOutOfSubLevel(
                targetLevel, localMuzzle(ref, state));
    }

    /** Complete three-axis Sable carrier velocity at this rack's launch point. */
    public static Vec3 carrierVelocityAtMuzzle(Level level, BindingRef ref) {
        BlockEntity blockEntity = level == null || ref == null ? null : ref.resolve(level);
        if (blockEntity == null) return Vec3.ZERO;
        return carrierVelocityAtPoint(level, ref, localMuzzle(ref, blockEntity.getBlockState()));
    }

    public static Vec3 carrierVelocityAtPoint(Level level, BindingRef ref, Vec3 muzzle) {
        if (level == null || ref == null || ref.subLevelId() == null) return Vec3.ZERO;
        SubLevel carrier = SubLevelContainer.getContainer(level)
                .getSubLevel(ref.subLevelId());
        if (carrier == null || carrier.isRemoved()) return Vec3.ZERO;

        Vector3d localPoint = new Vector3d(muzzle.x, muzzle.y, muzzle.z);
        try {
            Vector3d physicalVelocity = Sable.HELPER.getVelocity(
                    carrier.getLevel(), carrier, localPoint, new Vector3d()
            ).div(20.0);
            if (physicalVelocity.isFinite()) {
                return new Vec3(
                        physicalVelocity.x,
                        physicalVelocity.y,
                        physicalVelocity.z
                );
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Use pose history while a Sable physics handle is unavailable.
        }

        try {
            Vector3d currentWorld = carrier.logicalPose().transformPosition(
                    new Vector3d(localPoint));
            Vector3d previousWorld = carrier.lastPose().transformPosition(
                    new Vector3d(localPoint));
            Vector3d velocity = currentWorld.sub(previousWorld);
            return velocity.isFinite()
                    ? new Vec3(velocity.x, velocity.y, velocity.z)
                    : Vec3.ZERO;
        } catch (RuntimeException | LinkageError ignored) {
            return Vec3.ZERO;
        }
    }
    /** World-space forward direction of the bound rack, independent of carrier velocity. */
    public static @Nullable Vec3 launchDirection(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null || blockEntity.getLevel() == null) return null;
        Level targetLevel = blockEntity.getLevel();
        BlockState state = blockEntity.getBlockState();
        Direction facing = state.hasProperty(BlockStateProperties.FACING)
                ? state.getValue(BlockStateProperties.FACING)
                : state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        Vec3 localOrigin = Vec3.atCenterOf(ref.pos());
        Vec3 localForward = localOrigin.add(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vec3 worldOrigin = Sable.HELPER.projectOutOfSubLevel(targetLevel, localOrigin);
        Vec3 worldForward = Sable.HELPER.projectOutOfSubLevel(targetLevel, localForward);
        Vec3 direction = worldForward.subtract(worldOrigin);
        return direction.lengthSqr() < 1.0e-8 ? null : direction.normalize();
    }
    public static double spawnDistanceSqr(Level level, BindingRef ref, Entity missile) {
        Vec3 muzzle = muzzle(level, ref);
        if (muzzle == null) return Double.POSITIVE_INFINITY;
        return Math.min(muzzle.distanceToSqr(MianbaoMissileCompat.getWorldPosition(missile)),
                muzzle.distanceToSqr(missile.position()));
    }
    private static Vec3 localMuzzle(BindingRef ref, BlockState state) {
        Direction facing = state.hasProperty(BlockStateProperties.FACING)
                ? state.getValue(BlockStateProperties.FACING)
                : state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        return new Vec3(
                ref.pos().getX() + 0.5 + facing.getStepX() * 2.6,
                ref.pos().getY() - 1.0 + facing.getStepY() * 2.6,
                ref.pos().getZ() + 0.5 + facing.getStepZ() * 2.6);
    }
    private static Optional<Method> findFireMethod(WeaponType type) {
        String name = switch (type) {
            case FAR -> "FarMissile1rackgoProcedure";
            case MEDIUM, MICA_EM, AIM_120D -> "Missile2rackgoProcedure";
            case CLOSE -> "CloseMissile1rackgoProcedure";
            case AIR_TO_GROUND_DIRECT, AIR_TO_GROUND_TOP_ATTACK -> "AgmMissilerackgoProcedure";
            case LASER_GUIDED -> "MissilerackgoProcedure";
            case OPTICAL_GUIDED -> "OpticalguidedmissilerackgoProcedure";
            case ANTI_RADIATION -> "AntiradiationmissilerackgoProcedure";
            case ROCKET_POD -> "";
            default -> "";
        };
        if (name.isEmpty()) return Optional.empty();
        try {
            Class<?> owner = Class.forName("net.mcreator.myfirstmod.procedures." + name);
            for (Method method : owner.getMethods())
                if (method.getName().equals("execute") && method.getParameterCount() == 4) return Optional.of(method);
        } catch (ReflectiveOperationException | LinkageError ignored) { }
        return Optional.empty();
    }
    private static ResourceLocation blockId(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MOD_ID, path); }
    private MianbaoAircraftCompat() { }
}
