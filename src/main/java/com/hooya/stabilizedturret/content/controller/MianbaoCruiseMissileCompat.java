package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.CruiseMissileGuidance;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Linkage-free support for Mianbao's supported land-attack missile families. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MianbaoCruiseMissileCompat {
    private static final ResourceLocation SILO_HEAD = id("groundmissilelauncherhead");
    private static final ResourceLocation SILO_TAIL = id("groundmissilelaunchertail");
    private static final String CUSTOM_SILO_TYPE = "CreateFireControlCruiseMissileType";
    private static final String DETONATED_TAG = "CreateFireControlCruiseDetonated";
    private static final String NATIVE_SLOT_FOUR_TYPE = "MianbaoSlotFourMissileType";
    private static final String IGNITION_SOUND_PLAYED = "CreateFireControlCruiseIgnitionSoundPlayed";
    private static final Map<Type, Optional<Method>> BLOCK_FIRE_METHODS = new ConcurrentHashMap<>();
    private static final Map<Type, Optional<Method>> NATIVE_TRAIL_METHODS = new ConcurrentHashMap<>();
    private static volatile Optional<Method> siloFireMethod;
    private static volatile boolean nativeFlightOriginResolved;
    private static volatile @Nullable NativeFlightOriginFields nativeFlightOriginFields;
    private static volatile boolean nativeFlightOriginFailureLogged;
    private static volatile @Nullable Optional<Method> nativeNuclearEffectsMethod;
    private static volatile boolean nativeNuclearEffectsFailureLogged;
    private static volatile @Nullable Optional<Method> nativeNuclearTerrainMethod;
    private static volatile @Nullable Optional<Method> nativeNuclearTerrainActiveMethod;
    private static volatile boolean nativeNuclearTerrainFailureLogged;

    public enum Type {
        CRUISE("cruise", "\u5de1\u822a\u5bfc\u5f39", "cruisemissileblock",
                "cruisemissileitem", "cruisemissiletanshe", "CruisemissilegoProcedure", false),
        LOW_ALTITUDE_CRUISE("low_altitude_cruise", "\u5de1\u822a\u5bfc\u5f39(\u4f4e\u7a7a\u5f39\u9053)",
                "cruisemissilelowblock", "cruisemissilelowitem",
                "cruisemissilelowtanshe", "CruisemissilelowgoProcedure", false),
        NUCLEAR_CRUISE("nuclear_cruise", "\u5de1\u822a\u6838\u5bfc\u5f39", "nuclearcruisemissile",
                "nuclearcruisemissileitem", "nuclearcruisemissiletanshe", "Nuclear_cruise_missile_goProcedure", true),
        GROUND_NUCLEAR("ground_nuclear", "\u9646\u57fa\u5de1\u822a\u6838\u5bfc\u5f39", "groundnuclearcruisemissile",
                "groundnuclearcruisemissileitem", "groundnuclearcruisemissiletanshe", "Ground_nuclear_cruise_missile_goProcedure", true),
        GROUND_HIGH_EXPLOSIVE("ground_1", "\u9646\u57fa\u9ad8\u7206\u5bfc\u5f39", "groundmissile_1",
                "groundmissile_1item", "groundmissiletanshe", "Groundmissile1goProcedure", false),
        GROUND_CLUSTER("ground_2", "\u9646\u57fa\u96c6\u675f\u5bfc\u5f39", "groundmissile_2",
                "groundmissile_2item", "groundmissile_2tanshe", "Groundmissile2goProcedure", false),
        GROUND_CRUISE("ground_3", "\u9646\u57fa\u5de1\u822a\u5bfc\u5f39", "groundmissile_3",
                "groundmissile_3item", "groundmissile_3tanshe", "Groundmissile3goProcedure", false),
        GROUND_LOW_ALTITUDE("ground_4", "\u9646\u57fa\u4f4e\u7a7a\u5de1\u822a\u5bfc\u5f39", "groundmissile_4",
                "groundmissile_4item", "groundmissile_4tanshe", "Groundmissile4goProcedure", false);

        private final String key;
        private final String chineseName;
        private final ResourceLocation blockId;
        private final ResourceLocation itemId;
        private final ResourceLocation entityId;
        private final String procedureClass;
        private final boolean nuclear;

        Type(String key, String chineseName, String blockPath, String itemPath,
             String entityPath, String procedureClass, boolean nuclear) {
            this.key = key;
            this.chineseName = chineseName;
            this.blockId = id(blockPath);
            this.itemId = id(itemPath);
            this.entityId = id(entityPath);
            this.procedureClass = procedureClass;
            this.nuclear = nuclear;
        }

        public String key() { return key; }
        public String entityPath() { return entityId.getPath(); }
        public String displayName() { return chineseName; }
        public boolean nuclear() { return nuclear; }
        public boolean cluster() { return this == GROUND_CLUSTER; }
        public boolean lowAltitude() {
            return this == LOW_ALTITUDE_CRUISE || this == GROUND_LOW_ALTITUDE;
        }

        /** Uses the target and engine-age fields of Mianbao's original cruise missile. */
        public boolean usesCruiseFlightData() {
            return this == CRUISE || this == LOW_ALTITUDE_CRUISE
                    || this == NUCLEAR_CRUISE;
        }

        /** Uses the conventional BombexplodeProcedure and its matching visuals. */
        public boolean usesConventionalCruiseWarhead() {
            return this == CRUISE || this == LOW_ALTITUDE_CRUISE;
        }

        /**
         * Identifies missiles whose own Mianbao type is a purpose-built
         * ground-launch family. This is intentionally independent of
         * the launcher block: an ordinary cruise missile loaded into a
         * vertical silo remains an ordinary cruise missile.
         */
        public boolean isGroundLaunchType() {
            return switch (this) {
                case GROUND_NUCLEAR, GROUND_HIGH_EXPLOSIVE,
                     GROUND_CLUSTER, GROUND_CRUISE, GROUND_LOW_ALTITUDE -> true;
                case CRUISE, LOW_ALTITUDE_CRUISE, NUCLEAR_CRUISE -> false;
            };
        }

        public static @Nullable Type byKey(String key) {
            for (Type type : values()) if (type.key.equals(key)) return type;
            return null;
        }
    }

    public static boolean isBindingTarget(BlockState state) {
        return blockType(state) != null || isSiloHead(state);
    }

    public static boolean isSiloHead(BlockState state) {
        return SILO_HEAD.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    public static boolean isValidSilo(Level level, BlockPos head) {
        if (level == null) return false;
        BlockState headState = level.getBlockState(head);
        if (!isSiloHead(headState)) return false;
        Direction facing = facing(headState, Direction.UP);
        boolean directional = true;
        for (int distance = 1; distance <= 2; distance++) {
            BlockState tail = level.getBlockState(head.relative(facing.getOpposite(), distance));
            if (!SILO_TAIL.equals(BuiltInRegistries.BLOCK.getKey(tail.getBlock()))
                    || facing(tail, facing) != facing) {
                directional = false;
                break;
            }
        }
        return directional || hasLegacyVerticalSilo(level, head);
    }

    public static @Nullable Type blockType(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        for (Type type : Type.values()) if (type.blockId.equals(blockId)) return type;
        return null;
    }

    public static @Nullable Type missileType(Entity entity) {
        if (entity == null) return null;
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        for (Type type : Type.values()) if (type.entityId.equals(entityId)) return type;
        return null;
    }

    public static boolean isMissile(Entity entity) { return missileType(entity) != null; }

    /** Radar-compatible matcher for newer Mianbao cruise/VLS variants. */
    public static boolean isRadarMissile(Entity entity) {
        if (isMissile(entity)) return true;
        if (entity == null) return false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null || !MianbaoMissileCompat.MOD_ID.equals(id.getNamespace())) return false;
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith("tanshe")
                && (path.contains("cruise") || path.startsWith("groundmissile"));
    }

    public static boolean wasDetonated(Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean(DETONATED_TAG);
    }

    public static boolean isMissileType(EntityType<?> entityType) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        for (Type type : Type.values()) if (type.entityId.equals(id)) return true;
        return false;
    }

    public static @Nullable Type availableType(Level rootLevel, BindingRef ref) {
        LauncherContext context = resolve(rootLevel, ref);
        if (context == null) return null;
        Type block = blockType(context.state());
        return block != null ? block : loadedSiloType(context.blockEntity());
    }

    public static boolean hasType(Level rootLevel, BindingRef ref, Type requested) {
        return requested != null && requested == availableType(rootLevel, ref);
    }

    public static @Nullable Vec3 getLaunchPosition(Level rootLevel, BindingRef ref) {
        LauncherContext context = resolve(rootLevel, ref);
        if (context == null) return null;
        return launchProfile(context).position();
    }

    public static @Nullable Vec3 getLaunchDirection(Level rootLevel, BindingRef ref) {
        LauncherContext context = resolve(rootLevel, ref);
        if (context == null) return null;
        return launchProfile(context).direction();
    }

    public static double spawnDistanceSqr(Level rootLevel, BindingRef ref, Entity missile) {
        Vec3 launch = getLaunchPosition(rootLevel, ref);
        if (launch == null) return Double.POSITIVE_INFINITY;
        Vec3 worldMissile = MianbaoMissileCompat.getWorldPosition(missile);
        return Math.min(launch.distanceToSqr(worldMissile), launch.distanceToSqr(missile.position()));
    }

    public static boolean fire(Level rootLevel, BindingRef ref, Type requested, Vec3 initialTarget) {
        LauncherContext context = resolve(rootLevel, ref);
        if (context == null || requested == null || requested != availableType(rootLevel, ref)) return false;
        return spawnAndConsume(context, requested, initialTarget);
    }

    public static void writeNativeTarget(Entity missile, Type type, Vec3 target) {
        if (missile == null || type == null || target == null) return;
        Vec3 nativeTarget = target;
        if (type.nuclear()) {
            Vec3 current = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 approach = target.subtract(current);
            // The native nuclear trail calls its inseparable detonation
            // procedure at distance <= 1 before our authoritative post-tick
            // crossing check can run. Keep only its flight steering target a
            // little beyond the aim point during terminal flight; fire-control
            // still detonates at the exact original target this tick.
            if (approach.lengthSqr() > 1.0e-8 && approach.length() < 160.0) {
                nativeTarget = target.add(approach.normalize().scale(128.0));
            }
        }
        if (type.usesCruiseFlightData()) {
            missile.getPersistentData().putDouble("target_x", nativeTarget.x);
            missile.getPersistentData().putDouble("target_y", nativeTarget.y);
            missile.getPersistentData().putDouble("target_z", nativeTarget.z);
        } else {
            missile.getPersistentData().putDouble("x_target", nativeTarget.x);
            missile.getPersistentData().putDouble("y_target", nativeTarget.y);
            missile.getPersistentData().putDouble("z_target", nativeTarget.z);
        }
        missile.getPersistentData().putBoolean("\u5750\u6807\u6a21\u5f0f", true);
    }

    /** Executes Mianbao's own trail/guidance procedure for a skipped entity tick. */
    public static void runNativeFlight(Entity missile, Type type) {
        if (MathematicalMissileManager.contains(missile)) return;
        if (missile == null || type == null || missile.level().isClientSide()) return;
        String procedure = switch (type) {
            case CRUISE -> "CruisemissiletrailProcedure";
            case LOW_ALTITUDE_CRUISE -> "CruisemissilelowtrailProcedure";
            case NUCLEAR_CRUISE -> "NuclearcruisemissiletrailProcedure";
            case GROUND_NUCLEAR -> "Ground_nuclear_cruise_missile_tanshe_trailProcedure";
            case GROUND_HIGH_EXPLOSIVE -> "GroundMissile1trailProcedure";
            case GROUND_CLUSTER -> "GroundMissile2trailProcedure";
            case GROUND_CRUISE -> "GroundMissile3trailProcedure";
            case GROUND_LOW_ALTITUDE -> "GroundMissile4trailProcedure";
        };
        Optional<Method> method = NATIVE_TRAIL_METHODS.computeIfAbsent(type,
                ignored -> findExecute("net.mcreator.myfirstmod.procedures." + procedure, 5));
        if (method.isEmpty()) return;
        try {
            method.get().invoke(null, missile.level(), missile.getX(), missile.getY(),
                    missile.getZ(), missile);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error("Unable to run native Mianbao cruise flight {}",
                    type.key, exception);
        }
    }

    /** Keeps the native ignition/trail sequence while preventing its shorter timeout. */
    public static void synchronizeNativeFlightAge(Entity missile, Type type, int ageTicks) {
        if (missile == null || type == null) return;
        String ageTag = type.usesCruiseFlightData()
                ? "fadongji" : "fadongji1";
        missile.getPersistentData().putDouble(ageTag, Math.min(100, Math.max(0, ageTicks)));
    }

    /**
     * Mianbao's shared LegacyProjectileArrow discards every projectile after
     * 4096 blocks from its private flight origin. Fire-control cruise missiles
     * have their own 120-second lifetime and can legitimately exceed that
     * distance at Mach 2, so keep the native safety origin at the current
     * position while this controller owns the missile.
     */
    public static void refreshNativeFlightOrigin(Entity missile) {
        if (missile == null || missile.level().isClientSide() || !isMissile(missile)) return;
        NativeFlightOriginFields fields = nativeFlightOriginFields;
        if (!nativeFlightOriginResolved) {
            synchronized (MianbaoCruiseMissileCompat.class) {
                if (!nativeFlightOriginResolved) {
                    nativeFlightOriginFields = fields = resolveNativeFlightOriginFields(missile);
                    nativeFlightOriginResolved = true;
                } else {
                    fields = nativeFlightOriginFields;
                }
            }
        }
        if (fields == null) return;
        try {
            fields.initialized.setBoolean(missile, true);
            fields.x.setDouble(missile, missile.getX());
            fields.y.setDouble(missile, missile.getY());
            fields.z.setDouble(missile, missile.getZ());
        } catch (IllegalAccessException | RuntimeException exception) {
            if (!nativeFlightOriginFailureLogged) {
                nativeFlightOriginFailureLogged = true;
                StabilizedTurret.LOGGER.error(
                        "Unable to refresh Mianbao cruise missile flight origin", exception);
            }
        }
    }

    private static @Nullable NativeFlightOriginFields resolveNativeFlightOriginFields(
            Entity missile) {
        Class<?> current = missile.getClass();
        while (current != null && !current.getName().equals(
                "net.mcreator.myfirstmod.entity.LegacyProjectileArrow")) {
            current = current.getSuperclass();
        }
        if (current == null) return null;
        try {
            Field initialized = current.getDeclaredField("flightOriginInitialized");
            Field x = current.getDeclaredField("flightOriginX");
            Field y = current.getDeclaredField("flightOriginY");
            Field z = current.getDeclaredField("flightOriginZ");
            initialized.setAccessible(true);
            x.setAccessible(true);
            y.setAccessible(true);
            z.setAccessible(true);
            return new NativeFlightOriginFields(initialized, x, y, z);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to access Mianbao cruise missile flight origin", exception);
            return null;
        }
    }

    public static void detonate(Entity missile) {
        detonate(missile, missile == null ? null : MianbaoMissileCompat.getWorldPosition(missile));
    }

    public static void detonate(Entity missile, @Nullable Vec3 detonationPosition) {
        Type type = missileType(missile);
        if (type == null || missile.level().isClientSide() || detonationPosition == null) {
            if (missile != null) missile.discard();
            return;
        }
        if (missile.getPersistentData().getBoolean(DETONATED_TAG)) return;
        missile.getPersistentData().putBoolean(DETONATED_TAG, true);
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) {
            missile.discard();
            return;
        }
        byte profile = type.nuclear()
                ? MianbaoDeferredDetonation.PROFILE_NUCLEAR
                : type.usesConventionalCruiseWarhead()
                ? MianbaoDeferredDetonation.PROFILE_BOMB
                : MianbaoDeferredDetonation.PROFILE_GROUND;
        if (MianbaoDeferredDetonation.intercept(missile, detonationPosition, profile)) {
            return;
        }
        if (type.nuclear()) {
            // Mianbao's original procedure synchronously iterates and updates
            // roughly one hundred thousand blocks. Keep its radius, damage,
            // fire and shrapnel behavior, but pace the work across ticks so a
            // guided nuclear impact cannot freeze the integrated server.
            StabilizedTurret.LOGGER.info(
                    "Scheduling paced fire-control nuclear detonation type={} entity={} at {}",
                    type.key, missile.getUUID(), detonationPosition);
            MianbaoNuclearDetonation.detonate(root, missile.getUUID(), detonationPosition);
            missile.discard();
            return;
        }
        String className = type.usesConventionalCruiseWarhead()
                ? "net.mcreator.myfirstmod.procedures.BombexplodeProcedure"
                : "net.mcreator.myfirstmod.procedures.GroundMissileexplodeProcedure";
        int parameterCount = 5;
        try {
            Method method = findExecute(className, parameterCount).orElse(null);
            if (method == null) {
                StabilizedTurret.LOGGER.error(
                        "Missing Mianbao cruise explosion procedure {} for type {}",
                        className, type.key);
                missile.discard();
                return;
            }
            StabilizedTurret.LOGGER.info(
                    "Detonating fire-control cruise missile type={} entity={} at {}",
                    type.key, missile.getUUID(), detonationPosition);
            if (parameterCount == 4) {
                method.invoke(null, root, detonationPosition.x, detonationPosition.y, detonationPosition.z);
            } else {
                method.invoke(null, root, detonationPosition.x, detonationPosition.y, detonationPosition.z, missile);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error("Unable to detonate Mianbao cruise missile {}", type.key, exception);
        } finally {
            // Nuclear procedures do not receive the projectile entity and
            // therefore cannot discard it themselves. Always remove the
            // carrier after the one permitted native detonation.
            missile.discard();
        }
    }

    /**
     * Invokes Mianbao's terrain-free nuclear effect entry point. Keeping this
     * reflective preserves the optional-mod boundary while allowing adopted
     * missiles to use the exact effects from the installed Mianbao version.
     */
    static boolean playNativeNuclearEffects(ServerLevel level, Vec3 position) {
        if (level == null || position == null) return false;
        Optional<Method> resolved = nativeNuclearEffectsMethod;
        if (resolved == null) {
            resolved = findMethod(
                    "net.mcreator.myfirstmod.procedures.Nuke_dynamite_explodeProcedure",
                    "executeVisualOnly", 4);
            nativeNuclearEffectsMethod = resolved;
        }
        if (resolved.isEmpty()) {
            if (!nativeNuclearEffectsFailureLogged) {
                nativeNuclearEffectsFailureLogged = true;
                StabilizedTurret.LOGGER.error(
                        "Mianbao nuclear visual-only entry point is unavailable; using fire-control fallback visuals");
            }
            return false;
        }
        try {
            resolved.get().invoke(null, level, position.x, position.y, position.z);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to play native Mianbao nuclear effects at {}",
                    position, exception);
            return false;
        }
    }

    /** Starts Mianbao's original crater and Nuke marker without duplicating its initial effects. */
    static boolean startNativeNuclearTerrain(ServerLevel level, Vec3 position) {
        if (level == null || position == null) return false;
        Optional<Method> resolved = nativeNuclearTerrainMethod;
        if (resolved == null) {
            resolved = findMethod(
                    "net.mcreator.myfirstmod.procedures.Nuke_dynamite_explodeProcedure",
                    "executeTerrainOnly", 4);
            nativeNuclearTerrainMethod = resolved;
        }
        if (resolved.isEmpty()) {
            logNativeNuclearTerrainFailure(
                    "Mianbao nuclear terrain-only entry point is unavailable", null);
            return false;
        }
        try {
            resolved.get().invoke(null, level, position.x, position.y, position.z);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logNativeNuclearTerrainFailure(
                    "Unable to start native Mianbao nuclear terrain at " + position,
                    exception);
            return false;
        }
    }

    /** @return null when the paired Mianbao completion API is unavailable. */
    static @Nullable Boolean isNativeNuclearTerrainActive(
            ServerLevel level, Vec3 position
    ) {
        if (level == null || position == null) return false;
        Optional<Method> resolved = nativeNuclearTerrainActiveMethod;
        if (resolved == null) {
            resolved = findMethod(
                    "net.mcreator.myfirstmod.procedures.NuclearWaveScheduler",
                    "isDetonationActive", 4);
            nativeNuclearTerrainActiveMethod = resolved;
        }
        if (resolved.isEmpty()) {
            logNativeNuclearTerrainFailure(
                    "Mianbao nuclear completion entry point is unavailable", null);
            return null;
        }
        try {
            return (Boolean) resolved.get().invoke(
                    null, level, position.x, position.y, position.z);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logNativeNuclearTerrainFailure(
                    "Unable to query native Mianbao nuclear terrain at " + position,
                    exception);
            return null;
        }
    }

    private static void logNativeNuclearTerrainFailure(
            String message, @Nullable Throwable throwable
    ) {
        if (nativeNuclearTerrainFailureLogged) return;
        nativeNuclearTerrainFailureLogged = true;
        if (throwable == null) StabilizedTurret.LOGGER.error(message);
        else StabilizedTurret.LOGGER.error(message, throwable);
    }

    public static float visualStrength(Entity missile) {
        Type type = missileType(missile);
        if (type == null) return 2.0F;
        if (type.nuclear()) return 40.0F;
        return type.usesConventionalCruiseWarhead() ? 20.0F : 18.0F;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void loadUnsupportedSiloMissile(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Type held = itemType(event.getItemStack().getItem());
        // Mianbao natively loads the dedicated ground warheads. The ordinary,
        // nuclear and low-altitude cruise items share this compatibility slot.
        if (held == null || !held.usesCruiseFlightData()) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (!isValidSilo(level, pos)) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || loadedSiloType(blockEntity) != null || !nativeSlotsEmpty(blockEntity)) return;
        blockEntity.getPersistentData().putString(CUSTOM_SILO_TYPE, held.key);
        blockEntity.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        if (!player.getAbilities().instabuild) event.getItemStack().shrink(1);
        player.displayClientMessage(Component.translatable("message.create_fire_control.cruise_missile.loaded",
                Component.translatable("screen.create_fire_control.connected_display.missile_type." + held.key())), true);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static boolean fireBlock(LauncherContext context, Type requested) {
        Optional<Method> method = BLOCK_FIRE_METHODS.computeIfAbsent(requested,
                type -> findExecute("net.mcreator.myfirstmod.procedures." + type.procedureClass, 4));
        if (method.isEmpty()) return false;
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            method.get().invoke(null, context.level(), (double) context.pos().getX(),
                    (double) context.pos().getY(), (double) context.pos().getZ());
            return blockType(context.level().getBlockState(context.pos())) != requested;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error("Unable to fire bound cruise missile block {} at {}",
                    requested.key, context.pos(), exception);
            return false;
        }
    }

    private static boolean fireSilo(LauncherContext context, Type requested, Vec3 target) {
        if (!isValidSilo(context.level(), context.pos())) return false;
        String custom = context.blockEntity().getPersistentData().getString(CUSTOM_SILO_TYPE);
        if (requested.key.equals(custom)) return spawnCustomSiloMissile(context, requested, target);
        Optional<Method> method = siloFireMethod;
        if (method == null) {
            method = findExecute("net.mcreator.myfirstmod.procedures.GroundmissilelaunchergoProcedure", 4);
            siloFireMethod = method;
        }
        if (method.isEmpty()) return false;
        Type before = loadedSiloType(context.blockEntity());
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            method.get().invoke(null, context.level(), (double) context.pos().getX(),
                    (double) context.pos().getY(), (double) context.pos().getZ());
            return before == requested && loadedSiloType(context.blockEntity()) != before;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error("Unable to fire bound Mianbao vertical launcher at {}",
                    context.pos(), exception);
            return false;
        }
    }

    private static boolean spawnCustomSiloMissile(LauncherContext context, Type requested, Vec3 target) {
        if (!(context.level() instanceof ServerLevel server)) return false;
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(requested.entityId);
        Entity missile = entityType.create(server);
        if (missile == null) return false;
        double launchY = context.pos().getY()
                + (requested.lowAltitude() ? 1.0 : 1.5);
        missile.setPos(context.pos().getX() + 0.5, launchY,
                context.pos().getZ() + 0.5);
        // Mianbao's native low-altitude VLS path uses shoot(0, 2, 0, 2, 0).
        // Preserve that exact initial trajectory for this missile without
        // changing the compatibility launch profile of the older families.
        missile.setDeltaMovement(0.0, requested.lowAltitude() ? 2.0 : 0.2, 0.0);
        if (!requested.lowAltitude()) missile.setNoGravity(true);
        missile.setSilent(true);
        if (missile instanceof AbstractArrow arrow) {
            arrow.setBaseDamage(20.0);
        }
        writeNativeTarget(missile, requested, target);
        boolean added;
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            added = server.addFreshEntity(missile);
        }
        if (!added && !MathematicalMissileManager.contains(missile)) return false;
        CruiseMissileGuidance.adoptSpawnedMissile(missile);
        context.blockEntity().getPersistentData().remove(CUSTOM_SILO_TYPE);
        context.blockEntity().setChanged();
        context.level().sendBlockUpdated(context.pos(), context.state(), context.state(), 3);
        return true;
    }

    private static boolean spawnAndConsume(LauncherContext context, Type requested, Vec3 target) {
        if (context.level().getServer() == null) return false;
        ServerLevel root = context.level().getServer().getLevel(context.level().dimension());
        if (root == null) return false;
        LaunchProfile profile = launchProfile(context);
        Vec3 launch = profile.position();
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(requested.entityId);
        Entity missile = entityType.create(root);
        if (missile == null) return false;
        missile.setPos(launch.x, launch.y, launch.z);
        // Preserve the launcher's six-way facing in world space instead of
        // aiming at the selected target during entity creation.
        missile.setDeltaMovement(profile.direction().scale(2.0));
        if (!requested.lowAltitude()) missile.setNoGravity(true);
        missile.setSilent(true);
        if (missile instanceof AbstractArrow arrow) {
            arrow.setBaseDamage(20.0);
        }
        writeNativeTarget(missile, requested, target);
        boolean added;
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            added = root.addFreshEntity(missile);
        }
        if (!added && !MathematicalMissileManager.contains(missile)) return false;
        CruiseMissileGuidance.adoptSpawnedMissile(missile);
        playLaunchSound(context, root, requested, launch);
        if (blockType(context.state()) == requested) {
            context.level().removeBlock(context.pos(), false);
        } else {
            clearSiloAmmo(context.blockEntity(), requested);
            context.blockEntity().setChanged();
            context.level().sendBlockUpdated(context.pos(), context.state(), context.state(), 3);
        }
        return true;
    }

    /** Match the native launcher rather than giving every cruise family the same sound. */
    private static void playLaunchSound(LauncherContext context, ServerLevel root, Type type, Vec3 launch) {
        boolean silo = isSiloHead(context.state());
        boolean groundBlock = !silo && type.isGroundLaunchType();
        boolean rackRelease = !silo && !groundBlock;
        SoundEvent sound = rackRelease ? net.minecraft.sounds.SoundEvents.METAL_PRESSURE_PLATE_CLICK_OFF
                : BuiltInRegistries.SOUND_EVENT.get(id("missilelaunch"));
        if (sound == null) return;
        Vec3 origin = groundBlock ? Vec3.atCenterOf(BlockPos.containing(launch))
                : Sable.HELPER.projectOutOfSubLevel(context.level(), Vec3.atCenterOf(context.pos()));
        float volume = rackRelease ? 5.0F : silo ? 6.0F : 8.0F;
        float pitch = rackRelease ? 0.6F : silo ? 0.8F
                : (float) net.minecraft.util.Mth.nextDouble(root.getRandom(), 0.8, 1.0);
        root.playSound(null, origin.x, origin.y, origin.z, sound,
                rackRelease ? SoundSource.BLOCKS : SoundSource.NEUTRAL, volume, pitch);
    }

    /** Native trail ticks are skipped in math flight, so retain their one-shot engine ignition. */
    static void playMathematicalIgnition(Entity missile, Type type, double previousAge, double age) {
        int ignitionTick = type.isGroundLaunchType() ? 10 : 15;
        if (previousAge >= ignitionTick || age < ignitionTick
                || missile.getPersistentData().getBoolean(IGNITION_SOUND_PLAYED)) return;
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(id("missile_launcher"));
        if (root == null || sound == null) return;
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        root.playSound(null, BlockPos.containing(position), sound, SoundSource.NEUTRAL,
                type.isGroundLaunchType() ? 8.0F : 2.0F, type.isGroundLaunchType() ? 0.8F : 1.0F);
        // Guidance can reconcile an engine timer backwards; that must never replay ignition.
        missile.getPersistentData().putBoolean(IGNITION_SOUND_PLAYED, true);
    }


    static void clearSiloAmmo(BlockEntity blockEntity, Type type) {
        if (blockEntity instanceof Container container && container.getContainerSize() > 0
                && !container.getItem(0).isEmpty()) {
            container.removeItem(0, 1);
        }
        // The current launcher stores an actual item in slot zero. Remove
        // obsolete ammunition tags too, so they cannot resurrect a fired round.
        blockEntity.getPersistentData().remove("ammo");
        for (String key : new String[]{"loadmissile", "loadmissile2", "loadmissile3", "loadmissile4"}) {
            blockEntity.getPersistentData().remove(key);
        }
        blockEntity.getPersistentData().remove(NATIVE_SLOT_FOUR_TYPE);
        blockEntity.getPersistentData().remove(CUSTOM_SILO_TYPE);
    }

    private static void writeNativeTarget(BlockEntity blockEntity, Vec3 target) {
        blockEntity.getPersistentData().putBoolean("\u5750\u6807\u6a21\u5f0f", true);
        blockEntity.getPersistentData().putDouble("target_x", target.x);
        blockEntity.getPersistentData().putDouble("target_y", target.y);
        blockEntity.getPersistentData().putDouble("target_z", target.z);
        blockEntity.setChanged();
    }

    static @Nullable Type loadedSiloType(BlockEntity blockEntity) {
        if (blockEntity == null) return null;
        if (blockEntity instanceof Container container && container.getContainerSize() > 0
                && !container.getItem(0).isEmpty()) {
            Type stored = itemType(container.getItem(0).getItem());
            // Mianbao's native launcher maps either low-altitude item to the
            // groundmissile_4 entity; standalone cruise blocks keep their type.
            return stored == Type.LOW_ALTITUDE_CRUISE ? Type.GROUND_LOW_ALTITUDE : stored;
        }
        String custom = blockEntity.getPersistentData().getString(CUSTOM_SILO_TYPE);
        Type customType = Type.byKey(custom);
        if (customType != null) return customType;
        Type namedType = switch (blockEntity.getPersistentData().getString("ammo")) {
            case "HE" -> Type.GROUND_HIGH_EXPLOSIVE;
            case "CLUSTER" -> Type.GROUND_CLUSTER;
            case "CRUISE" -> Type.GROUND_CRUISE;
            case "CRUISELOW" -> Type.GROUND_LOW_ALTITUDE;
            case "NUCLEAR" -> Type.GROUND_NUCLEAR;
            default -> null;
        };
        if (namedType != null) return namedType;
        if (blockEntity.getPersistentData().getDouble("loadmissile") >= 1.0) return Type.GROUND_HIGH_EXPLOSIVE;
        if (blockEntity.getPersistentData().getDouble("loadmissile2") >= 1.0) return Type.GROUND_CLUSTER;
        if (blockEntity.getPersistentData().getDouble("loadmissile3") >= 1.0) return Type.GROUND_CRUISE;
        if (blockEntity.getPersistentData().getDouble("loadmissile4") >= 1.0) {
            String slotFour = blockEntity.getPersistentData().getString(NATIVE_SLOT_FOUR_TYPE);
            if ("ground_nuclear_cruise".equals(slotFour)) return Type.GROUND_NUCLEAR;
            if ("ground_low_cruise".equals(slotFour)) return Type.GROUND_LOW_ALTITUDE;
            if ("cruise_missile_low_item".equals(slotFour)) return Type.GROUND_LOW_ALTITUDE;
        }
        return null;
    }

    static boolean nativeSlotsEmpty(BlockEntity blockEntity) {
        if (blockEntity instanceof Container container && container.getContainerSize() > 0
                && !container.getItem(0).isEmpty()) return false;
        return blockEntity.getPersistentData().getString("ammo").isEmpty()
                && blockEntity.getPersistentData().getDouble("loadmissile") == 0.0
                && blockEntity.getPersistentData().getDouble("loadmissile2") == 0.0
                && blockEntity.getPersistentData().getDouble("loadmissile3") == 0.0
                && blockEntity.getPersistentData().getDouble("loadmissile4") == 0.0;
    }

    private static @Nullable Type itemType(Item item) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        for (Type type : Type.values()) if (type.itemId.equals(itemId)) return type;
        return null;
    }

    private static @Nullable LauncherContext resolve(Level rootLevel, BindingRef ref) {
        if (rootLevel == null || ref == null || !ref.isSameTarget(rootLevel)) return null;
        BlockEntity blockEntity = ref.resolve(rootLevel);
        if (blockEntity == null || blockEntity.getLevel() == null) return null;
        BlockState state = blockEntity.getBlockState();
        if (!isBindingTarget(state)) return null;
        return new LauncherContext(blockEntity.getLevel(), blockEntity.getBlockPos(), blockEntity, state);
    }

    private static LaunchProfile launchProfile(LauncherContext context) {
        Type block = blockType(context.state());
        boolean legacyCruiseBlock = block != null && block.usesCruiseFlightData();
        boolean groundMissileBlock = block != null && block.isGroundLaunchType();
        Direction localDirection = isSiloHead(context.state())
                ? siloLaunchDirection(context.level(), context.pos(), context.state())
                : groundMissileBlock
                ? facing(context.state(), Direction.UP)
                : legacyCruiseBlock
                ? facing(context.state(), Direction.NORTH)
                : Direction.UP;
        Vec3 center = Vec3.atCenterOf(context.pos());
        Vec3 localPosition = legacyCruiseBlock
                ? new Vec3(
                context.pos().getX() + 0.5 + localDirection.getStepX() * 0.6,
                context.pos().getY() - 2.0 + localDirection.getStepY() * 0.6,
                context.pos().getZ() + 0.5 + localDirection.getStepZ() * 0.6)
                : new Vec3(
                context.pos().getX() + 0.5 + localDirection.getStepX() * 1.6,
                context.pos().getY() + 0.5 + localDirection.getStepY() * 1.6,
                context.pos().getZ() + 0.5 + localDirection.getStepZ() * 1.6);
        Vec3 worldCenter = Sable.HELPER.projectOutOfSubLevel(context.level(), center);
        Vec3 worldDirectionPoint = Sable.HELPER.projectOutOfSubLevel(context.level(),
                center.add(localDirection.getStepX(), localDirection.getStepY(),
                        localDirection.getStepZ()));
        Vec3 worldDirection = worldDirectionPoint.subtract(worldCenter);
        if (worldDirection.lengthSqr() < 1.0e-8) {
            worldDirection = new Vec3(localDirection.getStepX(), localDirection.getStepY(),
                    localDirection.getStepZ());
        }
        return new LaunchProfile(
                Sable.HELPER.projectOutOfSubLevel(context.level(), localPosition),
                worldDirection.normalize());
    }

    private static Direction siloLaunchDirection(Level level, BlockPos head,
                                                  BlockState headState) {
        Direction facing = facing(headState, Direction.UP);
        if (hasDirectionalSilo(level, head, facing)) return facing;
        return hasLegacyVerticalSilo(level, head) ? Direction.UP : facing;
    }

    private static boolean hasDirectionalSilo(Level level, BlockPos head,
                                              Direction facing) {
        for (int distance = 1; distance <= 2; distance++) {
            BlockState tail = level.getBlockState(head.relative(facing.getOpposite(), distance));
            if (!SILO_TAIL.equals(BuiltInRegistries.BLOCK.getKey(tail.getBlock()))
                    || facing(tail, facing) != facing) return false;
        }
        return true;
    }

    private static boolean hasLegacyVerticalSilo(Level level, BlockPos head) {
        return SILO_TAIL.equals(BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(head.below()).getBlock()))
                && SILO_TAIL.equals(BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(head.below(2)).getBlock()));
    }

    private static Direction facing(BlockState state, Direction fallback) {
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return fallback;
    }

    private static Optional<Method> findExecute(String className, int parameterCount) {
        return findMethod(className, "execute", parameterCount);
    }

    private static Optional<Method> findMethod(
            String className, String methodName, int parameterCount
    ) {
        try {
            Class<?> type = Class.forName(className);
            for (Method method : type.getMethods()) {
                if (method.getName().equals(methodName)
                        && method.getParameterCount() == parameterCount) {
                    return Optional.of(method);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return Optional.empty();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MianbaoMissileCompat.MOD_ID,
                path.toLowerCase(Locale.ROOT));
    }

    private record LauncherContext(Level level, BlockPos pos, BlockEntity blockEntity,
                                   BlockState state) {}

    private record LaunchProfile(Vec3 position, Vec3 direction) {}

    private record NativeFlightOriginFields(Field initialized, Field x, Field y, Field z) {}

    private MianbaoCruiseMissileCompat() {}
}
