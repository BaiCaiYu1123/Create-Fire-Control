package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Optional, registry-only integration for Mianbao aircraft racks and radar. */
public final class MianbaoAircraftCompat {
    public static final String MOD_ID = "mianbaos_modernwarfare";
    private static final ResourceLocation EMPTY_RACK = id("rack");
    private static final ResourceLocation RADAR = id("radar");
    private static final Map<WeaponType, ResourceLocation> LOADED = loadedBlocks();
    private static final Map<WeaponType, ResourceLocation> MISSILES = missileEntities();
    private static final Set<ResourceLocation> RACKS = rackBlocks();
    private static final Map<WeaponType, Optional<Method>> FIRE_METHODS = new ConcurrentHashMap<>();

    public enum WeaponType {
        FAR,
        MEDIUM,
        CLOSE,
        AIR_TO_GROUND_DIRECT,
        AIR_TO_GROUND_TOP_ATTACK,
        LASER_GUIDED
    }

    private static Map<WeaponType, ResourceLocation> loadedBlocks() {
        Map<WeaponType, ResourceLocation> result = new EnumMap<>(WeaponType.class);
        result.put(WeaponType.FAR, id("far_missile_1rack"));
        result.put(WeaponType.MEDIUM, id("medium_missile_rack"));
        result.put(WeaponType.CLOSE, id("close_missile_1rack"));
        result.put(WeaponType.AIR_TO_GROUND_DIRECT, id("agm_missile_1rack"));
        result.put(WeaponType.AIR_TO_GROUND_TOP_ATTACK, id("agm_missile_2rack"));
        result.put(WeaponType.LASER_GUIDED, id("laser_guided_missile_rack"));
        return Map.copyOf(result);
    }

    private static Map<WeaponType, ResourceLocation> missileEntities() {
        Map<WeaponType, ResourceLocation> result = new EnumMap<>(WeaponType.class);
        result.put(WeaponType.FAR, id("farmissile_1tanshe"));
        result.put(WeaponType.MEDIUM, id("missile_2tanshe"));
        result.put(WeaponType.CLOSE, id("closemissile_1tanshe"));
        result.put(WeaponType.AIR_TO_GROUND_DIRECT, id("agm_1tanshe"));
        result.put(WeaponType.AIR_TO_GROUND_TOP_ATTACK, id("agm_2tanshe"));
        result.put(WeaponType.LASER_GUIDED, id("missiletanshe"));
        return Map.copyOf(result);
    }

    private static Set<ResourceLocation> rackBlocks() {
        Set<ResourceLocation> result = new HashSet<>(LOADED.values());
        result.add(EMPTY_RACK);
        return Set.copyOf(result);
    }

    public static boolean isRack(BlockState state) { return RACKS.contains(blockId(state)); }
    public static boolean isRadar(BlockState state) { return RADAR.equals(blockId(state)); }
    public static boolean isSameRackVariant(ResourceLocation a, ResourceLocation b) {
        return RACKS.contains(a) && RACKS.contains(b);
    }
    public static @Nullable WeaponType weaponAt(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null) return null;
        ResourceLocation current = blockId(blockEntity.getBlockState());
        for (var entry : LOADED.entrySet()) if (entry.getValue().equals(current)) return entry.getKey();
        return null;
    }
    public static int ammoCount(Level level, BindingRef ref, WeaponType type) {
        return weaponAt(level, ref) == type ? 1 : 0;
    }
    public static boolean isMissile(Entity entity) { return typeOf(entity) != null; }
    public static boolean isAirToAir(WeaponType type) {
        return type == WeaponType.FAR || type == WeaponType.MEDIUM || type == WeaponType.CLOSE;
    }
    public static boolean isAirToGround(WeaponType type) {
        return type == WeaponType.AIR_TO_GROUND_DIRECT
                || type == WeaponType.AIR_TO_GROUND_TOP_ATTACK;
    }
    public static boolean isLaserGuided(WeaponType type) {
        return type == WeaponType.LASER_GUIDED;
    }
    public static @Nullable WeaponType typeOf(Entity entity) {
        ResourceLocation current = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        for (var entry : MISSILES.entrySet()) if (entry.getValue().equals(current)) return entry.getKey();
        return null;
    }
    public static int lifetimeTicks(WeaponType type) {
        return switch (type) {
            case FAR -> 400;
            case MEDIUM -> 300;
            case CLOSE -> 200;
            case AIR_TO_GROUND_DIRECT, AIR_TO_GROUND_TOP_ATTACK, LASER_GUIDED -> 300;
        };
    }
    public static int maneuverDelayTicks(WeaponType type) {
        return 0;
    }
    public static double maximumSpeedBlocksPerTick(WeaponType type) {
        // Air-to-air missiles retain their existing Mach-2 envelope. Every
        // air-to-ground family requested by the fire-control integration uses
        // the same Mach-1 maximum speed.
        return isAirToAir(type) ? 680.6 / 20.0 : 340.3 / 20.0;
    }
    public static int accelerationTicks(WeaponType type) {
        return 5 * 20;
    }
    public static double maxTurnRadians(WeaponType type) {
        return Math.toRadians(switch (type) {
            case FAR -> 10.0;
            case MEDIUM -> 14.0;
            case CLOSE -> 22.0;
            case AIR_TO_GROUND_DIRECT -> 8.0;
            case AIR_TO_GROUND_TOP_ATTACK -> 7.0;
            case LASER_GUIDED -> 8.0;
        });
    }
    public static boolean fireOne(Level level, BindingRef ref, WeaponType expected) {
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
        Direction facing = state.hasProperty(BlockStateProperties.FACING)
                ? state.getValue(BlockStateProperties.FACING)
                : state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        Vec3 local = new Vec3(ref.pos().getX() + 0.5 + facing.getStepX() * 2.6,
                ref.pos().getY() - 1.0 + facing.getStepY() * 2.6,
                ref.pos().getZ() + 0.5 + facing.getStepZ() * 2.6);
        return Sable.HELPER.projectOutOfSubLevel(targetLevel, local);
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
    private static Optional<Method> findFireMethod(WeaponType type) {
        String name = switch (type) {
            case FAR -> "FarMissile1rackgoProcedure";
            case MEDIUM -> "Missile2rackgoProcedure";
            case CLOSE -> "CloseMissile1rackgoProcedure";
            case AIR_TO_GROUND_DIRECT, AIR_TO_GROUND_TOP_ATTACK -> "AgmMissilerackgoProcedure";
            case LASER_GUIDED -> "MissilerackgoProcedure";
        };
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
