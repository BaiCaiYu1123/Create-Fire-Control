package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Linkage-free support for Mianbao's manual and self-loading flare launchers. */
public final class MianbaoCountermeasureCompat {
    public static final String TRACKED_TAG = "CreateFireControlCountermeasureTracked";
    public static final int LIFETIME_TICKS = 200;

    private static final Set<ResourceLocation> LAUNCHER_IDS = Set.of(
            id("countermeasurelauncher"),
            id("countermeasurelauncher_2")
    );
    private static final ResourceLocation COUNTERMEASURE_ID = id("countermeasure");
    private static final Map<EntityType<?>, Boolean> COUNTERMEASURE_TYPES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Optional<Method>> FIRE_METHODS =
            new ConcurrentHashMap<>();

    public static boolean isLauncher(BlockState state) {
        return state != null
                && LAUNCHER_IDS.contains(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isCountermeasure(Entity entity) {
        return entity != null
                && COUNTERMEASURE_TYPES.computeIfAbsent(entity.getType(), type ->
                COUNTERMEASURE_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type)));
    }

    public static int getAmmoCount(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        return context == null ? 0 : Math.max(0, (int) Math.floor(
                context.blockEntity().getPersistentData().getDouble("ammo")));
    }

    /** The native flare procedure's motion/lifetime, without visual allocations or legacy scans. */
    public static void runManagedTrail(Entity entity) {
        runManagedTrail(entity, true);
    }

    static void runManagedTrail(Entity entity, boolean discardAtEnd) {
        if (entity == null) return;
        var data = entity.getPersistentData();
        long tick = entity.level().getGameTime();
        String marker = "CreateFireControlFlareTrailTick";
        if (data.contains(marker) && data.getLong(marker) == tick) return;
        data.putLong(marker, tick);
        entity.setNoGravity(true);
        entity.setDeltaMovement(entity.getDeltaMovement().add(0, -0.02, 0));
        double age = data.getDouble("time") + 1;
        data.putDouble("time", age);
        if (discardAtEnd && age >= LIFETIME_TICKS && !entity.level().isClientSide()) entity.discard();
    }

    /** Air physics for a step whose terrain chunk has no native arrow tick. */
    public static void applySkippedArrowPhysics(Entity entity) {
        entity.setDeltaMovement(entity.getDeltaMovement().scale((double) 0.99F)
                .add(0, entity.isNoGravity() ? 0 : -0.05, 0));
    }

    /** The self-loading launcher retains partial ammo while its reload lock is active. */
    public static boolean canFire(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        return context != null
                && ammoCount(context) > 0
                && !isReloading(context.state());
    }

    /** Invokes the native procedure once and returns the flare it spawned. */
    public static @Nullable Entity fireOne(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        if (context == null) return null;
        int ammoBefore = ammoCount(context);
        if (ammoBefore <= 0 || isReloading(context.state())) return null;

        Optional<Method> method = FIRE_METHODS.computeIfAbsent(
                context.blockId(), MianbaoCountermeasureCompat::findFireMethod);
        if (method.isEmpty()) return null;
        Set<UUID> carrierIds = connectedCarrierIds(level, ref);
        Entity launched;
        boolean consumed;
        try (MianbaoCountermeasureManager.LaunchScope ignored =
                     MianbaoCountermeasureManager.expectLaunch(carrierIds)) {
            method.get().invoke(
                    null,
                    context.level(),
                    (double) context.pos().getX(),
                    (double) context.pos().getY(),
                    (double) context.pos().getZ(),
                    context.state()
            );
            launched = MianbaoCountermeasureManager.capturedLaunch();
            consumed = getAmmoCount(level, ref) == ammoBefore - 1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to fire bound Mianbao countermeasure launcher at {}",
                    context.pos(), exception);
            return null;
        }
        if (!consumed) {
            if (launched != null) launched.getPersistentData().remove(TRACKED_TAG);
            return null;
        }
        if (launched == null) {
            // Self-loading launchers may enqueue the entity for the following
            // tick. Keep a short, muzzle-local association so that launch is
            // still registered without restoring passive global discovery.
            MianbaoCountermeasureManager.deferLaunch(
                    context.level(), Vec3.atCenterOf(context.pos()), carrierIds);
            return null;
        }
        MianbaoCountermeasureManager.registerLaunched(launched, carrierIds);
        return launched;
    }

    /** Spawns native gameplay flares with the bounded fire-control visual path. */
    public static int spawnBurst(ServerLevel level, Vec3 position, int count) {
        if (level == null || position == null || count <= 0
                || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)) return 0;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(COUNTERMEASURE_ID);
        if (type == null) return 0;
        var sound = BuiltInRegistries.SOUND_EVENT.get(
                id("countermeasure_launcher"));
        if (sound != null) {
            level.playSound(null, position.x, position.y, position.z,
                    sound, SoundSource.NEUTRAL, 4.0F, 0.8F);
        }
        int spawned = 0;
        for (int index = 0; index < count; index++) {
            Entity entity = type.create(level);
            if (!(entity instanceof Projectile projectile)) continue;
            projectile.setPos(position.x, position.y, position.z);
            projectile.setSilent(true);
            projectile.getPersistentData().putBoolean(TRACKED_TAG, true);
            if (projectile instanceof AbstractArrow arrow) {
                arrow.setBaseDamage(0.0);
            }
            projectile.shoot(
                    level.random.nextDouble() * 2.0 - 1.0,
                    -1.0,
                    level.random.nextDouble() * 2.0 - 1.0,
                    1.0F, 5.0F);
            if (level.addFreshEntity(projectile)) {
                MianbaoCountermeasureManager.registerLaunched(projectile, Set.of());
                spawned++;
            }
        }
        return spawned;
    }

    private static Optional<Method> findFireMethod(ResourceLocation launcherId) {
        String procedure = switch (launcherId.getPath()) {
            case "countermeasurelauncher" ->
                    "net.mcreator.myfirstmod.procedures.Countermeasure_launcher_goProcedure";
            case "countermeasurelauncher_2" ->
                    "net.mcreator.myfirstmod.procedures.Countermeasurelauncher2goProcedure";
            default -> null;
        };
        if (procedure == null) return Optional.empty();
        try {
            Class<?> owner = Class.forName(procedure,
                    false,
                    MianbaoCountermeasureCompat.class.getClassLoader());
            for (Method method : owner.getMethods()) {
                if (method.getName().equals("execute")
                        && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 5) {
                    return Optional.of(method);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to resolve Mianbao countermeasure firing procedure for {}",
                    launcherId, exception);
        }
        return Optional.empty();
    }

    private static int ammoCount(LauncherContext context) {
        return Math.max(0, (int) Math.floor(
                context.blockEntity().getPersistentData().getDouble("ammo")));
    }

    private static boolean isReloading(BlockState state) {
        var property = state.getBlock().getStateDefinition().getProperty("reload");
        return property instanceof BooleanProperty reload
                && state.getValue(reload);
    }

    private static Set<UUID> connectedCarrierIds(Level level, BindingRef ref) {
        if (level == null || ref == null || ref.subLevelId() == null) return Set.of();
        SubLevel carrier = SubLevelContainer.getContainer(level).getSubLevel(ref.subLevelId());
        if (carrier == null || carrier.isRemoved()) return Set.of(ref.subLevelId());
        Set<UUID> result = new HashSet<>();
        result.add(carrier.getUniqueId());
        for (SubLevel connected : SubLevelHelper.getConnectedChain(carrier)) {
            if (connected != null && !connected.isRemoved()) {
                result.add(connected.getUniqueId());
            }
        }
        return Set.copyOf(result);
    }

    private static @Nullable LauncherContext resolve(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        BlockEntity blockEntity = ref.resolve(level);
        if (blockEntity == null || blockEntity.getLevel() == null) return null;
        BlockState state = blockEntity.getBlockState();
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return isLauncher(state)
                ? new LauncherContext(
                blockEntity.getLevel(), blockEntity.getBlockPos(), blockEntity,
                state, blockId)
                : null;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MianbaoMissileCompat.MOD_ID, path);
    }

    private record LauncherContext(
            Level level,
            net.minecraft.core.BlockPos pos,
            BlockEntity blockEntity,
            BlockState state,
            ResourceLocation blockId
    ) {
    }

    private MianbaoCountermeasureCompat() {
    }
}
