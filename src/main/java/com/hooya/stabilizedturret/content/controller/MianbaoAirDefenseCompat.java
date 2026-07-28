package com.hooya.stabilizedturret.content.controller;

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
            id("antiairmissilelauncherright")
    );
    private static final Set<ResourceLocation> MISSILES = Set.of(
            id("antiairmissiletanshe"),
            id("portableantiairmissiletanshe"),
            id("portableantiairmissiletanshe_2")
    );
    private static final Map<ResourceLocation, Optional<Method>> FIRE_METHODS = new ConcurrentHashMap<>();

    public static boolean isLauncher(BlockState state) {
        return LAUNCHERS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isMissile(Entity entity) {
        return MISSILES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    public static boolean isMissileType(net.minecraft.world.entity.EntityType<?> type) {
        return MISSILES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    public static int getLifetimeTicks(Entity missile) {
        return 20 * 20;
    }

    public static boolean isAnyFireControlMissile(Entity entity) {
        return MianbaoMissileCompat.isGuidedMissile(entity) || isMissile(entity)
                || MianbaoAircraftCompat.isMissile(entity);
    }

    public static int getAmmoCount(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        if (context == null) return 0;
        return Math.max(0, (int) Math.floor(
                context.blockEntity().getPersistentData().getDouble("missileammo")));
    }

    public static boolean fireOne(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        if (context == null || getAmmoCount(level, ref) <= 0) return false;
        Optional<Method> method = FIRE_METHODS.computeIfAbsent(context.blockId(),
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

    public static double getLauncherSpawnDistanceSqr(Level level, BindingRef ref, Entity missile) {
        Vec3 muzzle = getLauncherMuzzlePosition(level, ref);
        if (muzzle == null) return Double.POSITIVE_INFINITY;
        Vec3 worldMissile = MianbaoMissileCompat.getWorldPosition(missile);
        return Math.min(muzzle.distanceToSqr(worldMissile), muzzle.distanceToSqr(missile.position()));
    }

    public static Vec3 getTargetVelocity(SubLevel subLevel, Vec3 currentCenter) {
        return Vec3.ZERO;
    }

    private static Optional<Method> findFireMethod(ResourceLocation blockId) {
        String className = blockId.getPath().endsWith("block")
                ? "net.mcreator.myfirstmod.procedures.AntiairmissilelaunchergoProcedure"
                : "net.mcreator.myfirstmod.procedures.Anti_air_missile_launcher_second_fireProcedure";
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
        if (level == null || ref == null || !ref.isSameTarget(level)) return null;
        BlockEntity blockEntity = ref.resolve(level);
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

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MianbaoMissileCompat.MOD_ID, path);
    }

    private record LauncherContext(Level level, net.minecraft.core.BlockPos pos,
                                   BlockEntity blockEntity, ResourceLocation blockId) {
    }

    private MianbaoAirDefenseCompat() {
    }
}
