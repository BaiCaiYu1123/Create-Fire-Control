package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.MissileSettings;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MianbaoAirDefenseCompat {
    private static final Set<ResourceLocation> LAUNCHERS = Set.of(
            id("antiairmissilelauncherblock"),
            id("antiairmissilelauncherleft"),
            id("antiairmissilelauncherright"),
            id("antiairmissilelauncherthird_h_1"),
            id("antiairmissilelauncherthird_h_2"),
            id("antiairmissilelauncherthird_h_1sand"),
            id("antiairmissilelauncherthird_h_2sand"),
            id("antiair_missile_launcher_type_1"),
            id("antiair_missile_launcher_type_2_left"),
            id("antiair_missile_launcher_type_2_right"),
            id("antiair_missile_launcher_type_3_green"),
            id("antiair_missile_launcher_type_3_sand")
    );
    private static final Set<ResourceLocation> MISSILES = Set.of(
            id("antiairmissiletanshe"),
            id("portableantiairmissiletanshe"),
            id("portableantiairmissiletanshe_2")
    );
    private static final Map<String, Optional<Method>> FIRE_METHODS = new ConcurrentHashMap<>();
    private static final Map<net.minecraft.world.entity.EntityType<?>, Boolean> MISSILE_TYPES = new ConcurrentHashMap<>();

    public static boolean isLauncher(BlockState state) {
        return isLauncherId(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isLauncherId(@Nullable ResourceLocation blockId) {
        return blockId != null && LAUNCHERS.contains(blockId);
    }

    /** Keeps a welded launcher binding alive only across an in-place BE rebuild. */
    public static boolean isBoundLauncherPresent(Level level, BindingRef ref) {
        if (!ref.isValidIn(level)) return false;
        if (!level.hasChunkAt(ref.pos())) return true;
        return resolve(level, ref) != null;
    }

    public static boolean isMissile(Entity entity) {
        return MISSILES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    /** Vehicle-launched high-speed AA missile; portable variants are excluded. */
    public static boolean isHighSpeedMissile(Entity entity) {
        return entity != null && id("antiairmissiletanshe").equals(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    public static boolean isMissileType(net.minecraft.world.entity.EntityType<?> type) {
        return MISSILES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    /** Native Mianbao blast strength, before the fire-control multiplier. */
    public static float nativeExplosionStrength(Entity missile) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType());
        if (id("portableantiairmissiletanshe").equals(id)) return 5.0F;
        if (id("portableantiairmissiletanshe_2").equals(id)) return 4.0F;
        return id("antiairmissiletanshe").equals(id) ? 6.0F : 0.0F;
    }

    public static int getLifetimeTicks(Entity missile) {
        return MissileSettings.lifetimeTicks(missile);
    }

    /** Missile performance is shared by controller and display launch bindings. */
    public static AcceleratingMissileIntercept.SpeedProfile speedProfile(double launchSpeed) {
        double initialSpeed = Double.isFinite(launchSpeed)
                ? Math.min(34.03, Math.max(0.1, launchSpeed)) : 0.1;
        return new AcceleratingMissileIntercept.SpeedProfile(initialSpeed, 34.03, 10 * 20);
    }

    public static AcceleratingMissileIntercept.SpeedProfile speedProfile(Entity missile, double launchSpeed) {
        // Display-fired ordinary missiles originally use this shared Mach-2 profile,
        // while the computer's beam-riding path has its own Mach-1 profile.
        double maximum = MissileSettings.speedCustomized(missile)
                ? MissileSettings.speedBlocksPerTick(missile) : speedProfile(launchSpeed).maximumSpeed();
        double initial = Double.isFinite(launchSpeed) ? Math.min(maximum, Math.max(0.1, launchSpeed)) : 0.1;
        return new AcceleratingMissileIntercept.SpeedProfile(initial, maximum,
                MissileSettings.accelerationTicks(missile, 10 * 20));
    }

    public static int guidanceDelayTicks() {
        return 10;
    }

    public static double maxTurnRadians() {
        return Math.toRadians(13.0);
    }

    public static double maxTurnRadians(double speed) {
        // AA already has its own ten-tick launch clearance. Keep it and its
        // 13-degree ceiling; bound the low-speed turn radius in both devices.
        return MissileSteeringLimits.maxTurn(speed, 34.03, maxTurnRadians(), Double.POSITIVE_INFINITY);
    }

    public static boolean isAnyFireControlMissile(Entity entity) {
        return entity != null && MISSILE_TYPES.computeIfAbsent(entity.getType(), ignored -> classifyMissile(entity));
    }

    private static boolean classifyMissile(Entity entity) {
        return MianbaoMissileCompat.isGuidedMissile(entity) || isMissile(entity)
                || MianbaoAircraftCompat.isMissile(entity)
                || MianbaoCruiseMissileCompat.isMissile(entity)
                || MianbaoRocketPodCompat.isRocket(entity)
                || MianbaoBallisticMissileCompat.isMissile(entity);
    }

    public static int getAmmoCount(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        if (context == null) return 0;
        return MianbaoLauncherAmmo.airDefense(context.blockEntity());
    }

    public static boolean hasOrdinaryRound(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        return context != null && OrdinaryAirDefenseLauncher.hasRound(context.blockEntity());
    }

    /** Radar displays retain their existing high-speed/intercept ammunition. */
    public static int getRadarAmmoCount(Level level, BindingRef ref) {
        return hasOrdinaryRound(level, ref) ? 0 : getAmmoCount(level, ref);
    }

    public static boolean fireOne(Level level, BindingRef ref) {
        return fireOne(level, ref, null);
    }

    public static boolean fireOne(Level level, BindingRef ref, StabilizerControllerBlockEntity computer) {
        LauncherContext context = resolve(level, ref);
        if (context == null || getAmmoCount(level, ref) <= 0) return false;
        if (OrdinaryAirDefenseLauncher.hasRound(context.blockEntity())) {
            return OrdinaryAirDefenseLauncher.fire(level, ref, context.blockEntity(), computer);
        }
        String procedure = fireProcedureClass(context.blockEntity(), context.blockId().getPath());
        Optional<Method> method = FIRE_METHODS.computeIfAbsent(procedure,
                MianbaoAirDefenseCompat::findFireMethod);
        if (method.isEmpty()) return false;
        int before = getAmmoCount(level, ref);
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            method.get().invoke(null, context.level(),
                    (double) context.pos().getX(),
                    (double) context.pos().getY(),
                    (double) context.pos().getZ());
            return getAmmoCount(level, ref) < before;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error("Unable to fire bound air-defense launcher {} at {}",
                    context.blockId(), context.pos(), exception);
            return false;
        }
    }

    public static @Nullable Vec3 getLauncherMuzzlePosition(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        if (context == null) return null;
        Direction facing = facing(context.level().getBlockState(context.pos()));
        Vec3 local = Vec3.atCenterOf(context.pos()).add(
                facing.getStepX() * 2.6,
                facing.getStepY() * 2.6,
                facing.getStepZ() * 2.6);
        return Sable.HELPER.projectOutOfSubLevel(context.level(), local);
    }

    public static @Nullable Vec3 getLauncherDirection(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        if (context == null) return null;
        Direction facing = facing(context.level().getBlockState(context.pos()));
        Vec3 localBase = Vec3.atCenterOf(context.pos());
        Vec3 localFront = localBase.add(
                facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vec3 worldBase = Sable.HELPER.projectOutOfSubLevel(context.level(), localBase);
        Vec3 worldFront = Sable.HELPER.projectOutOfSubLevel(context.level(), localFront);
        Vec3 direction = worldFront.subtract(worldBase);
        return direction.lengthSqr() < 1.0e-8 ? null : direction.normalize();
    }

    public static double getLauncherSpawnDistanceSqr(Level level, BindingRef ref, Entity missile) {
        Vec3 muzzle = getLauncherMuzzlePosition(level, ref);
        if (muzzle == null) return Double.POSITIVE_INFINITY;
        Vec3 worldMissile = MianbaoMissileCompat.getWorldPosition(missile);
        return Math.min(muzzle.distanceToSqr(worldMissile), muzzle.distanceToSqr(missile.position()));
    }

    public static Vec3 getTargetVelocity(SubLevel subLevel, Vec3 currentCenter) {
        return Vec3.ZERO;
    }

    static String fireProcedureClass(BlockEntity launcher, String blockPath) {
        // Current launchers, including AA II left/right, fire directly from slot 0.
        // Only a backend without an inventory can use the old AA II NBT procedure.
        if (MianbaoLauncherAmmo.hasInventorySlot(launcher)) {
            return "net.mcreator.myfirstmod.procedures.AntiairmissilelaunchergoProcedure";
        }
        return switch (blockPath) {
            case "antiairmissilelauncherleft", "antiairmissilelauncherright" ->
                    "net.mcreator.myfirstmod.procedures.Anti_air_missile_launcher_second_fireProcedure";
            default -> "net.mcreator.myfirstmod.procedures.AntiairmissilelaunchergoProcedure";
        };
    }

    private static Optional<Method> findFireMethod(String className) {
        try {
            Class<?> type = Class.forName(className);
            for (Method method : type.getMethods()) {
                if (method.getName().equals("execute") && method.getParameterCount() == 4) {
                    return Optional.of(method);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return Optional.empty();
    }

    private static @Nullable LauncherContext resolve(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null) {
            blockEntity = ref.resolveRebuiltAirDefenseLauncher(level);
        }
        if (blockEntity == null || blockEntity.getLevel() == null
                || !isLauncher(blockEntity.getBlockState())) return null;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        return new LauncherContext(blockEntity.getLevel(), blockEntity.getBlockPos(), blockEntity, id);
    }

    private static Direction facing(BlockState state) {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;
    }

    static Vec3 localDirection(BlockState state) {
        Direction direction = facing(state);
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MianbaoMissileCompat.MOD_ID, path);
    }

    private record LauncherContext(Level level, net.minecraft.core.BlockPos pos,
                                   BlockEntity blockEntity, ResourceLocation blockId) {
    }

    private MianbaoAirDefenseCompat() {
    }
}
