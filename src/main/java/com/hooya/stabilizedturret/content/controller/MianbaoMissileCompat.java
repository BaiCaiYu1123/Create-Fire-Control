package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional, linkage-free integration with Mianbao's Modern Warfare.
 *
 * <p>The project deliberately does not link against MCreator classes. Exact
 * registry ids and vanilla entity/block APIs keep Create: Fire Control usable
 * when Mianbao is absent and across source-compatible Mianbao builds.
 */
public final class MianbaoMissileCompat {
    public static final String MOD_ID = "mianbaos_modernwarfare";

    private static final Set<ResourceLocation> LAUNCHERS = Set.of(
            id("antitankemissilelauncher"),
            id("antitankemissilelauncherh_2"),
            id("antitankmissilelaunchererxing_right"),
            id("antitankmissilelaunchererxing_left"),
            id("antitankmissilelaunchersixing"),
            id("antitankmissilelaunchersanxing")
    );
    private static final Set<ResourceLocation> DESIGNATORS = Set.of(
            id("laserpointerblock"),
            id("laserpointerblockshase")
    );
    private static final Set<ResourceLocation> GUIDED_MISSILES = Set.of(
            id("antitankmissile_1tanshe"),
            id("antitankmissile_2tanshe"),
            id("antitankmissile_5tanshe"),
            id("antitankmissile_6tanshe"),
            id("antitankmissile_7tanshe")
    );
    private static final Map<ResourceLocation, Optional<Method>> FIRE_METHODS = new ConcurrentHashMap<>();

    public static boolean isLauncher(BlockState state) {
        return LAUNCHERS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isDesignator(BlockState state) {
        return DESIGNATORS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isSameAdjustableLauncherVariant(
            ResourceLocation first,
            ResourceLocation second
    ) {
        if (first == null || second == null || first.equals(second)) {
            return false;
        }
        return (first.equals(id("antitankemissilelauncher"))
                && second.equals(id("antitankemissilelauncherh_2")))
                || (first.equals(id("antitankemissilelauncherh_2"))
                && second.equals(id("antitankemissilelauncher")));
    }

    public static boolean isGuidedMissile(Entity entity) {
        return GUIDED_MISSILES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }
    public static boolean isGuidedMissileType(EntityType<?> type) {
        return GUIDED_MISSILES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }


    /** Returns the number of missiles currently recorded by the native launcher block entity. */
    public static int getAmmoCount(Level level, BindingRef ref) {
        LauncherContext context = resolveLauncher(level, ref);
        if (context == null) {
            return 0;
        }
        int first = Math.max(0, (int) Math.floor(context.blockEntity().getPersistentData().getDouble("missile1ammo")));
        int second = Math.max(0, (int) Math.floor(context.blockEntity().getPersistentData().getDouble("missile2ammo")));
        return first + second;
    }

    /**
     * Invokes Mianbao's own one-shot procedure. The integration remains
     * linkage-free: procedure classes are resolved only when Mianbao is
     * installed and a configured wireless firing pulse arrives.
     */
    public static boolean fireOne(Level level, BindingRef ref) {
        LauncherContext context = resolveLauncher(level, ref);
        if (context == null) {
            return false;
        }
        int ammoBefore = getAmmoCount(level, ref);
        if (ammoBefore <= 0) {
            return false;
        }
        Optional<Method> fireMethod = FIRE_METHODS.computeIfAbsent(
                context.blockId(),
                MianbaoMissileCompat::findFireMethod
        );
        if (fireMethod.isEmpty()) {
            return false;
        }
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            fireMethod.get().invoke(
                    null,
                    context.level(),
                    (double) context.pos().getX(),
                    (double) context.pos().getY(),
                    (double) context.pos().getZ()
            );
            return getAmmoCount(level, ref) < ammoBefore;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to fire bound Mianbao launcher {} at {}",
                    context.blockId(),
                    context.pos(),
                    exception
            );
            return false;
        }
    }

    public static @Nullable Vec3 getDesignatorDirection(Level level, BindingRef ref) {
        if (!ref.isSameTarget(level)) {
            return null;
        }
        BlockState state = level.getBlockState(ref.pos());
        if (!isDesignator(state)) {
            return null;
        }
        Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;
        Vector3d direction = new Vector3d(
                facing.getStepX(),
                facing.getStepY(),
                facing.getStepZ()
        );
        SubLevel subLevel = Sable.HELPER.getContaining(level, ref.pos());
        if (subLevel != null && !subLevel.isRemoved()) {
            subLevel.logicalPose().transformNormal(direction);
        }
        if (direction.lengthSquared() < 1.0e-8) {
            return null;
        }
        direction.normalize();
        return new Vec3(direction.x, direction.y, direction.z);
    }

    public static @Nullable Vec3 getDesignatorOrigin(Level level, BindingRef ref) {
        Vec3 direction = getDesignatorDirection(level, ref);
        if (direction == null) {
            return null;
        }
        Direction facing = level.getBlockState(ref.pos()).getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 localOrigin = Vec3.atCenterOf(ref.pos()).add(0.0, 0.5, 0.0).add(
                facing.getStepX() * 1.6,
                facing.getStepY() * 1.6,
                facing.getStepZ() * 1.6
        );
        return Sable.HELPER.projectOutOfSubLevel(level, localOrigin);
    }

    /**
     * The four launchers create their missile 2.6 blocks in front of the base.
     * Returning that exact muzzle point makes spawn association unambiguous
     * even when several launchers are mounted close together.
     */
    public static @Nullable Vec3 getLauncherMuzzlePosition(Level level, BindingRef ref) {
        if (!ref.isSameTarget(level)) {
            return null;
        }
        BlockState state = level.getBlockState(ref.pos());
        if (!isLauncher(state)) {
            return null;
        }
        Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;
        double muzzleOffset = getMuzzleOffset(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        Vec3 localMuzzle = Vec3.atCenterOf(ref.pos()).add(
                facing.getStepX() * muzzleOffset,
                facing.getStepY() * muzzleOffset,
                facing.getStepZ() * muzzleOffset
        );
        return Sable.HELPER.projectOutOfSubLevel(level, localMuzzle);
    }

    /**
     * Sable versions differ in whether an EntityJoin event exposes plot-space
     * or already projected coordinates. Compare all safe coordinate pairs and
     * keep the shortest distance so both supported Sable branches associate
     * the same physical launch.
     */
    public static double getLauncherSpawnDistanceSqr(Level level, BindingRef ref, Entity missile) {
        Vec3 worldMuzzle = getLauncherMuzzlePosition(level, ref);
        if (worldMuzzle == null) {
            return Double.POSITIVE_INFINITY;
        }
        BlockState state = level.getBlockState(ref.pos());
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        double muzzleOffset = getMuzzleOffset(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        Vec3 localMuzzle = Vec3.atCenterOf(ref.pos()).add(
                facing.getStepX() * muzzleOffset,
                facing.getStepY() * muzzleOffset,
                facing.getStepZ() * muzzleOffset
        );
        Vec3 rawMissile = missile.position();
        Vec3 projectedMissile = getWorldPosition(missile);
        return Math.min(
                worldMuzzle.distanceToSqr(projectedMissile),
                Math.min(worldMuzzle.distanceToSqr(rawMissile), localMuzzle.distanceToSqr(rawMissile))
        );
    }
    public static Vec3 getWorldPosition(Entity entity) {
        return Sable.HELPER.projectOutOfSubLevel(entity.level(), entity.position());
    }

    public static Vec3 getWorldVelocity(Entity entity) {
        Vec3 velocity = entity.getDeltaMovement();
        SubLevel subLevel = Sable.HELPER.getContaining(entity);
        return subLevel == null
                ? velocity
                : subLevel.logicalPose().transformNormal(velocity);
    }

    public static void setWorldVelocity(Entity entity, Vec3 worldVelocity) {
        SubLevel subLevel = Sable.HELPER.getContaining(entity);
        entity.setDeltaMovement(subLevel == null
                ? worldVelocity
                : subLevel.logicalPose().transformNormalInverse(worldVelocity));
    }

    public static @Nullable ServerLevel getRootServerLevel(Entity entity) {
        SubLevel subLevel = Sable.HELPER.getContaining(entity);
        if (subLevel != null && subLevel.getLevel() instanceof ServerLevel root) {
            return root;
        }
        return entity.level() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    private static final String SERVER_TICK_MARKER = "CreateFireControlNativeTick";

    /** Records that vanilla actually ticked this projectile during this server tick. */
    public static void noteNativeMissileTick(Entity entity) {
        ServerLevel root = getRootServerLevel(entity);
        if (root != null) {
            entity.getPersistentData().putLong(SERVER_TICK_MARKER, root.getGameTime());
        }
    }

    /**
     * Advances a guided missile without collision when its root-world terrain
     * is unavailable. Coordinates stay local to the entity's own level, so
     * this also works for missiles spawned inside a Sable sublevel.
     */
    public static void advanceWithoutRootChunkCollision(Entity entity) {
        Vec3 localMovement = entity.getDeltaMovement();
        entity.setPos(
                entity.getX() + localMovement.x,
                entity.getY() + localMovement.y,
                entity.getZ() + localMovement.z
        );
        entity.hasImpulse = true;
        noteNativeMissileTick(entity);
    }

    /** Advances missiles whose entity chunk did not receive a native tick. */
    public static void advanceIfNativeTickMissed(Entity entity) {
        ServerLevel root = getRootServerLevel(entity);
        if (root == null) return;
        if (entity.getPersistentData().getLong(SERVER_TICK_MARKER) != root.getGameTime()) {
            advanceWithoutRootChunkCollision(entity);
        }
        MianbaoMissileRemoteSync.broadcast(entity);
    }

    /**
     * Non-loading test used before an off-screen explosion. Every chunk touched
     * by the blast must already exist; checking only the missile's own chunk
     * still allows an explosion near a border to synchronously load a neighbor.
     */
    public static boolean areRootChunksLoaded(
            Entity entity, Vec3 worldPosition, double radius
    ) {
        ServerLevel root = getRootServerLevel(entity);
        if (root == null) return false;
        int minChunkX = net.minecraft.util.Mth.floor(worldPosition.x - radius - 1.0) >> 4;
        int maxChunkX = net.minecraft.util.Mth.floor(worldPosition.x + radius + 1.0) >> 4;
        int minChunkZ = net.minecraft.util.Mth.floor(worldPosition.z - radius - 1.0) >> 4;
        int maxChunkZ = net.minecraft.util.Mth.floor(worldPosition.z + radius + 1.0) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (root.getChunkSource().getChunkNow(x, z) == null) return false;
            }
        }
        return true;
    }
    private static @Nullable LauncherContext resolveLauncher(Level level, BindingRef ref) {
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null || blockEntity.getLevel() == null) {
            return null;
        }
        Level targetLevel = blockEntity.getLevel();
        BlockState state = targetLevel.getBlockState(blockEntity.getBlockPos());
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return LAUNCHERS.contains(blockId)
                ? new LauncherContext(targetLevel, blockEntity.getBlockPos(), blockEntity, blockId)
                : null;
    }

    private static Optional<Method> findFireMethod(ResourceLocation launcherId) {
        String procedure = switch (launcherId.getPath()) {
            case "antitankemissilelauncher", "antitankemissilelauncherh_2" ->
                    "net.mcreator.myfirstmod.procedures.AntitankMissilelaunchergoProcedure";
            case "antitankmissilelaunchererxing_right", "antitankmissilelaunchererxing_left" ->
                    "net.mcreator.myfirstmod.procedures.AntitankmissilelaunchererxingWORKProcedure";
            case "antitankmissilelaunchersanxing" ->
                    "net.mcreator.myfirstmod.procedures.AntitankmissilelaunchersanxingworkProcedure";
            case "antitankmissilelaunchersixing" ->
                    "net.mcreator.myfirstmod.procedures.Antitankmissilelauncher_sixing_fireProcedure";
            default -> null;
        };
        if (procedure == null) {
            return Optional.empty();
        }
        try {
            Class<?> procedureClass = Class.forName(procedure, false, MianbaoMissileCompat.class.getClassLoader());
            for (Method method : procedureClass.getMethods()) {
                if (method.getName().equals("execute")
                        && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 4) {
                    return Optional.of(method);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to resolve Mianbao firing procedure for {}",
                    launcherId,
                    exception
            );
        }
        return Optional.empty();
    }
    private static double getMuzzleOffset(ResourceLocation launcherId) {
        // The adjustable-height single launcher spawns its projectile one
        // block farther forward than the four fixed rack variants.
        return launcherId.getPath().equals("antitankemissilelauncher")
                || launcherId.getPath().equals("antitankemissilelauncherh_2") ? 3.6 : 2.6;
    }


    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private record LauncherContext(
            Level level,
            net.minecraft.core.BlockPos pos,
            BlockEntity blockEntity,
            ResourceLocation blockId
    ) {
    }

    private MianbaoMissileCompat() {
    }
}
