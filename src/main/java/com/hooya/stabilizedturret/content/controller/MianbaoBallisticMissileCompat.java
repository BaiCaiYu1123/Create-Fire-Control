package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.missile.Df26BallisticMissileBlock;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Linkage-free bridge to Mianbao's public ballistic missile API. */
public final class MianbaoBallisticMissileCompat {
    public static final String MOD_ID = "mianbaos_modernwarfare";
    private static final ResourceLocation LAUNCHER_ID = id("ballistic_missile_launcher");
    private static final ResourceLocation MISSILE_ID = id("ballistic_missile_entity");
    private static final String API = "net.mcreator.myfirstmod.api.BallisticMissileApi";
    private static final String CONTROLLER =
            "net.mcreator.myfirstmod.api.BallisticMissileController";
    private static volatile @Nullable Method fireMethod;
    private static volatile boolean fireResolved;
    private static volatile @Nullable Method findControllerMethod;
    private static volatile @Nullable Method setFlightControlMethod;
    private static volatile @Nullable Method unrestrictedDirectionMethod;
    private static volatile @Nullable Method unrestrictedSpeedMethod;
    private static volatile boolean unrestrictedResolved;
    private static volatile @Nullable Method getTelemetryMethod;
    private static volatile @Nullable Method telemetryPositionMethod;
    private static volatile @Nullable Method telemetryDirectionMethod;
    private static volatile @Nullable Method telemetryVelocityMethod;
    private static volatile @Nullable Method telemetryIgnitedMethod;
    private static volatile @Nullable Method telemetryDockedMethod;
    private static volatile boolean apiResolved;
    private static volatile @Nullable Method launchAgeMethod;
    private static volatile boolean launchAgeResolved;
    private static volatile @Nullable Method renderRotationMethod;
    private static volatile boolean renderRotationResolved;
    private static volatile @Nullable Method mountMethod;
    private static volatile @Nullable Method igniteMethod;
    private static volatile @Nullable Method mountedWorldPositionMethod;
    private static volatile @Nullable Method detachMethod;
    private static volatile @Nullable Method setFlightRotationMethod;
    private static volatile boolean lifecycleResolved;
    private static final Map<BindingRef, Entity> RECENTLY_FIRED = new HashMap<>();
    public static final String DF26_TAG = "CreateFireControlDf26";
    public static final double DF26_NOSE_OFFSET = 42.1875;
    private static final String DF26_DOCKED_TAG = "CreateFireControlDf26Docked";
    private static final String DF26_BASE_ROTATION_X_TAG = "CreateFireControlDf26BaseRotationX";
    private static final String DF26_BASE_ROTATION_Y_TAG = "CreateFireControlDf26BaseRotationY";
    private static final String DF26_BASE_ROTATION_Z_TAG = "CreateFireControlDf26BaseRotationZ";
    private static final String DF26_BASE_ROTATION_W_TAG = "CreateFireControlDf26BaseRotationW";
    private static final String REMOTE_ROTATION_X_TAG = "CreateFireControlRemoteRotationX";
    private static final String REMOTE_ROTATION_Y_TAG = "CreateFireControlRemoteRotationY";
    private static final String REMOTE_ROTATION_Z_TAG = "CreateFireControlRemoteRotationZ";
    private static final String REMOTE_ROTATION_W_TAG = "CreateFireControlRemoteRotationW";
    private static volatile @Nullable Method detonateMethod;
    private static volatile boolean detonateResolved;

    public static boolean isBindingTarget(BlockState state) {
        return state != null && (LAUNCHER_ID.equals(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                || state.getBlock() instanceof Df26BallisticMissileBlock);
    }

    public static boolean isMissile(Entity entity) {
        return entity != null && MISSILE_ID.equals(
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    public static boolean isMissileType(net.minecraft.world.entity.EntityType<?> type) {
        return type != null && MISSILE_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    /** Returns true only for a complete, loaded five-block launcher. */
    public static boolean isLoaded(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        if (context == null) return false;
        if (context.type() == Type.DF_26) return true;
        net.minecraft.world.level.block.state.properties.Property<?> property =
                context.state().getBlock().getStateDefinition().getProperty("loaded");
        return property instanceof net.minecraft.world.level.block.state.properties.BooleanProperty loaded
                && context.state().getValue(loaded);
    }

    /** Fires the native launcher, which preserves Mianbao's rail animation and effects. */
    public static boolean fire(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        if (context == null) return false;
        if (context.type() == Type.DF_26) return fireDf26(context, ref);
        Method method = resolveFireMethod();
        if (method == null) return false;
        try {
            Object result = method.invoke(null, context.level(), context.pos(), context.state());
            return result instanceof InteractionResult interaction
                    ? interaction != InteractionResult.PASS && interaction != InteractionResult.FAIL
                    : true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to fire bound Mianbao ballistic missile launcher at {}",
                    context.pos(), exception);
            return false;
        }
    }

    /** Finds the native missile entity belonging to a launcher, including its docked phase. */
    public static @Nullable Entity findMissile(Level level, BindingRef ref) {
        Entity fired = RECENTLY_FIRED.remove(ref);
        if (fired != null && !fired.isRemoved()) return fired;
        LauncherContext context = resolve(level, ref);
        if (context == null) return null;
        BlockPos root = context.root();
        // Binding positions on a Sable structure are plot coordinates, while
        // Mianbao mounts its missile entity at the projected world position.
        // Searching around the plot origin misses the entity after ignition.
        Vec3 center = ref.worldCenter(context.level());
        if (center == null) center = Vec3.atCenterOf(root);
        AABB search = new AABB(center, center).inflate(16.0);
        Entity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Entity entity : context.level().getEntitiesOfClass(Entity.class, search,
                MianbaoBallisticMissileCompat::isMissile)) {
            double distance = entity.position().distanceToSqr(center);
            if (distance < bestDistance) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static @Nullable Type availableType(Level level, BindingRef ref) {
        LauncherContext context = resolve(level, ref);
        return context != null && isLoaded(level, ref) ? context.type() : null;
    }

    public static boolean hasType(Level level, BindingRef ref, Type type) {
        return type != null && availableType(level, ref) == type;
    }

    public static boolean isDf26(Entity missile) {
        return missile != null && missile.getPersistentData().getBoolean(DF26_TAG);
    }

    public static void setDf26(Entity missile, boolean value) {
        if (missile == null) return;
        missile.getPersistentData().putBoolean(DF26_TAG, value);
    }

    /** Calls BallisticMissileApi.findController(entity).setFlightControl(...). */
    public static boolean setFlightControl(Entity missile, Vec3 direction, double speed) {
        if (missile == null || direction == null || !isMissile(missile)
                || !finite(direction) || direction.lengthSqr() < 1.0e-8
                || !Double.isFinite(speed) || speed < 0.0) return false;
        resolveApiMethods();
        Method find = findControllerMethod;
        Method set = setFlightControlMethod;
        if (find == null || set == null) return false;
        try {
            Object optional = find.invoke(null, missile);
            if (!(optional instanceof Optional<?> found) || found.isEmpty()) return false;
            set.invoke(found.get(), direction.normalize(), speed);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to control Mianbao ballistic missile {}", missile.getUUID(), exception);
            return false;
        }
    }

    /**
     * Drives the native entity above its public integration safety cap while
     * retaining Mianbao's rotation, swept collision and renderer.
     */
    public static boolean setUnrestrictedFlightControl(
            Entity missile,
            Vec3 direction,
            double speed
    ) {
        if (missile == null || direction == null || !isMissile(missile)
                || !finite(direction) || direction.lengthSqr() < 1.0e-8
                || !Double.isFinite(speed) || speed < 0.0) return false;
        resolveUnrestrictedMethods();
        Method rotate = unrestrictedDirectionMethod;
        Method accelerate = unrestrictedSpeedMethod;
        if (rotate == null || accelerate == null) {
            return setFlightControl(missile, direction, speed);
        }
        try {
            rotate.invoke(missile, direction.normalize());
            accelerate.invoke(missile, speed);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to apply unrestricted ballistic flight control {}",
                    missile.getUUID(), exception);
            return false;
        }
    }

    /** Reads the public immutable telemetry without linking against the optional mod. */
    public static @Nullable Telemetry telemetry(Entity missile) {
        if (missile == null || !isMissile(missile)) return null;
        resolveApiMethods();
        Method get = getTelemetryMethod;
        if (get == null || telemetryPositionMethod == null || telemetryDirectionMethod == null
                || telemetryVelocityMethod == null || telemetryIgnitedMethod == null
                || telemetryDockedMethod == null) return null;
        try {
            Object optional = get.invoke(null, missile);
            if (!(optional instanceof Optional<?> found) || found.isEmpty()) return null;
            Object value = found.get();
            Object position = telemetryPositionMethod.invoke(value);
            Object direction = telemetryDirectionMethod.invoke(value);
            Object velocity = telemetryVelocityMethod.invoke(value);
            Object ignited = telemetryIgnitedMethod.invoke(value);
            Object docked = telemetryDockedMethod.invoke(value);
            if (position instanceof Vec3 p && direction instanceof Vec3 d
                    && velocity instanceof Vec3 v && ignited instanceof Boolean i
                    && docked instanceof Boolean k) {
                return new Telemetry(p, d, v, i, k);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to read Mianbao ballistic telemetry {}", missile.getUUID(), exception);
        }
        return null;
    }

    /** Returns the native launch clock, or -1 while mounted before ignition/unavailable. */
    public static int launchAge(Entity missile) {
        if (missile == null || !isMissile(missile)) return -1;
        if (!launchAgeResolved) {
            synchronized (MianbaoBallisticMissileCompat.class) {
                if (!launchAgeResolved) {
                    launchAgeMethod = findInstanceMethod(
                            "net.mcreator.myfirstmod.entity.BallisticMissileEntity",
                            "launchAge");
                    launchAgeResolved = true;
                }
            }
        }
        Method method = launchAgeMethod;
        if (method == null) return -1;
        try {
            Object value = method.invoke(missile);
            return value instanceof Integer age ? age : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.debug(
                    "Unable to read Mianbao ballistic launch age {}", missile.getUUID(), exception);
            return -1;
        }
    }

    /** Reads the complete native model orientation, including launcher roll. */
    public static @Nullable Quaternionf renderRotation(Entity missile) {
        if (missile == null || !isMissile(missile)) return null;
        if (isDf26(missile)
                && missile.getPersistentData().getBoolean(DF26_DOCKED_TAG)) {
            Quaternionf mounted = df26BaseRotation(missile);
            if (mounted != null) return mounted;
        }
        if (!renderRotationResolved) {
            synchronized (MianbaoBallisticMissileCompat.class) {
                if (!renderRotationResolved) {
                    renderRotationMethod = findInstanceMethod(
                            "net.mcreator.myfirstmod.entity.BallisticMissileEntity",
                            "renderRotation");
                    renderRotationResolved = true;
                }
            }
        }
        Method method = renderRotationMethod;
        if (method == null) return null;
        try {
            Object value = method.invoke(missile);
            return value instanceof Quaternionf rotation
                    ? normalizedRotation(rotation) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.debug(
                    "Unable to read Mianbao ballistic render rotation {}",
                    missile.getUUID(), exception);
            return null;
        }
    }

    /** Stores authoritative orientation on a chunk-independent client proxy. */
    public static void setRemoteRenderRotation(Entity missile, Quaternionf rotation) {
        Quaternionf normalized = normalizedRotation(rotation);
        if (missile == null || normalized == null) return;
        var data = missile.getPersistentData();
        data.putFloat(REMOTE_ROTATION_X_TAG, normalized.x);
        data.putFloat(REMOTE_ROTATION_Y_TAG, normalized.y);
        data.putFloat(REMOTE_ROTATION_Z_TAG, normalized.z);
        data.putFloat(REMOTE_ROTATION_W_TAG, normalized.w);
    }

    /** Reads the orientation carried by a chunk-independent client proxy. */
    public static @Nullable Quaternionf remoteRenderRotation(Entity missile) {
        if (missile == null) return null;
        var data = missile.getPersistentData();
        if (!data.contains(REMOTE_ROTATION_X_TAG)
                || !data.contains(REMOTE_ROTATION_Y_TAG)
                || !data.contains(REMOTE_ROTATION_Z_TAG)
                || !data.contains(REMOTE_ROTATION_W_TAG)) return null;
        return normalizedRotation(new Quaternionf(
                data.getFloat(REMOTE_ROTATION_X_TAG),
                data.getFloat(REMOTE_ROTATION_Y_TAG),
                data.getFloat(REMOTE_ROTATION_Z_TAG),
                data.getFloat(REMOTE_ROTATION_W_TAG)));
    }

    private static @Nullable Quaternionf normalizedRotation(Quaternionf rotation) {
        if (rotation == null
                || !Float.isFinite(rotation.x) || !Float.isFinite(rotation.y)
                || !Float.isFinite(rotation.z) || !Float.isFinite(rotation.w)) return null;
        float lengthSquared = rotation.lengthSquared();
        return lengthSquared < 1.0e-8F ? null : new Quaternionf(rotation).normalize();
    }

    /** Uses the native nuclear impact routine when a guided point is crossed. */
    public static void detonateAt(Entity missile, Vec3 position) {
        if (missile == null || position == null || missile.isRemoved()
                || !finite(position)) return;
        if (!detonateResolved) {
            synchronized (MianbaoBallisticMissileCompat.class) {
                if (!detonateResolved) {
                    detonateMethod = findInstanceMethod(
                            "net.mcreator.myfirstmod.entity.BallisticMissileEntity",
                            "detonateAt", Vec3.class);
                    detonateResolved = true;
                }
            }
        }
        Method method = detonateMethod;
        if (method == null) {
            missile.discard();
            return;
        }
        try {
            method.invoke(missile, position);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to detonate Mianbao ballistic missile {}", missile.getUUID(), exception);
            missile.discard();
        }
    }

    /** Position of a mounted native entity, including a moving Sable structure pose. */
    public static @Nullable Vec3 mountedWorldPosition(Entity missile) {
        if (missile == null || !isMissile(missile)) return null;
        resolveLifecycleMethods();
        Method method = mountedWorldPositionMethod;
        if (method == null) return null;
        try {
            Object value = method.invoke(missile);
            return value instanceof Vec3 position && finite(position) ? position : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.debug(
                    "Unable to read mounted ballistic position {}", missile.getUUID(), exception);
            return null;
        }
    }

    /** Completes the native rail phase after a DF-26 has cleared its former block structure. */
    public static boolean detachDf26(Entity missile) {
        if (!isDf26(missile) || missile.level().isClientSide()) return false;
        resolveLifecycleMethods();
        Method method = detachMethod;
        if (method == null) return false;
        try {
            method.invoke(missile);
            Quaternionf baseRotation = df26BaseRotation(missile);
            if (baseRotation != null && setFlightRotationMethod != null) {
                setFlightRotationMethod.invoke(missile, baseRotation);
            }
            missile.getPersistentData().putBoolean(DF26_DOCKED_TAG, false);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to detach DF-26 ballistic missile {}", missile.getUUID(), exception);
            return false;
        }
    }

    private static boolean fireDf26(LauncherContext context, BindingRef ref) {
        resolveLifecycleMethods();
        Method mount = mountMethod;
        Method ignite = igniteMethod;
        if (mount == null || ignite == null) return false;
        Entity missile;
        try {
            net.minecraft.world.entity.EntityType<?> entityType =
                    BuiltInRegistries.ENTITY_TYPE.get(MISSILE_ID);
            missile = entityType.create(context.level());
            if (missile == null) return false;
            mount.invoke(missile, context.root(), context.facing(), context.orientation());
            setDf26(missile, true);
            setDf26BaseRotation(
                    missile, context.level(), context.root(), context.facing());
            missile.getPersistentData().putBoolean(
                    "CreateFireControlBallisticGuided", true);
            if (!context.level().addFreshEntity(missile)) return false;
            Df26BallisticMissileBlock.removeStructure(
                    context.level(), context.pos(), context.state());
            ignite.invoke(missile);
            MianbaoMissileRemoteSync.broadcast(missile);
            RECENTLY_FIRED.put(ref, missile);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.error(
                    "Unable to fire bound DF-26 ballistic missile at {}",
                    context.root(), exception);
            return false;
        }
    }

    private static void resolveLifecycleMethods() {
        if (lifecycleResolved) return;
        synchronized (MianbaoBallisticMissileCompat.class) {
            if (lifecycleResolved) return;
            try {
                Class<?> type = Class.forName(
                        "net.mcreator.myfirstmod.entity.BallisticMissileEntity",
                        false, MianbaoBallisticMissileCompat.class.getClassLoader());
                mountMethod = type.getMethod(
                        "mount", BlockPos.class, Direction.class, int.class);
                igniteMethod = type.getMethod("ignite");
                mountedWorldPositionMethod = type.getMethod("mountedWorldPosition");
                detachMethod = type.getDeclaredMethod("detach");
                setFlightRotationMethod = type.getDeclaredMethod(
                        "setFlightRotation", Quaternionf.class);
                detachMethod.setAccessible(true);
                setFlightRotationMethod.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                StabilizedTurret.LOGGER.debug(
                        "Mianbao ballistic lifecycle API is unavailable", exception);
            }
            lifecycleResolved = true;
        }
    }

    private static @Nullable Method resolveFireMethod() {
        if (!fireResolved) {
            synchronized (MianbaoBallisticMissileCompat.class) {
                if (!fireResolved) {
                    fireMethod = findMethod(
                            "net.mcreator.myfirstmod.block.BallisticMissileLauncherBlock",
                            "fire", Level.class, BlockPos.class, BlockState.class);
                    fireResolved = true;
                }
            }
        }
        return fireMethod;
    }

    private static void resolveApiMethods() {
        if (apiResolved) return;
        synchronized (MianbaoBallisticMissileCompat.class) {
            if (apiResolved) return;
            try {
                Class<?> apiClass = Class.forName(API, false,
                        MianbaoBallisticMissileCompat.class.getClassLoader());
                Class<?> controllerClass = Class.forName(CONTROLLER, false,
                        MianbaoBallisticMissileCompat.class.getClassLoader());
                Class<?> telemetryClass = Class.forName(
                        "net.mcreator.myfirstmod.api.BallisticMissileTelemetry", false,
                        MianbaoBallisticMissileCompat.class.getClassLoader());
                findControllerMethod = apiClass.getMethod("findController", Entity.class);
                getTelemetryMethod = apiClass.getMethod("getTelemetry", Entity.class);
                setFlightControlMethod = controllerClass.getMethod(
                        "setFlightControl", Vec3.class, double.class);
                telemetryPositionMethod = telemetryClass.getMethod("position");
                telemetryDirectionMethod = telemetryClass.getMethod("warheadDirection");
                telemetryVelocityMethod = telemetryClass.getMethod("velocity");
                telemetryIgnitedMethod = telemetryClass.getMethod("ignited");
                telemetryDockedMethod = telemetryClass.getMethod("docked");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                StabilizedTurret.LOGGER.debug(
                        "Mianbao ballistic missile API is unavailable", exception);
            }
            apiResolved = true;
        }
    }

    private static void resolveUnrestrictedMethods() {
        if (unrestrictedResolved) return;
        synchronized (MianbaoBallisticMissileCompat.class) {
            if (unrestrictedResolved) return;
            try {
                Class<?> type = Class.forName(
                        "net.mcreator.myfirstmod.entity.BallisticMissileEntity",
                        false, MianbaoBallisticMissileCompat.class.getClassLoader());
                unrestrictedDirectionMethod = type.getDeclaredMethod(
                        "applyFlightDirection", Vec3.class);
                unrestrictedSpeedMethod = type.getDeclaredMethod(
                        "applyControlledSpeed", double.class);
                unrestrictedDirectionMethod.setAccessible(true);
                unrestrictedSpeedMethod.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                StabilizedTurret.LOGGER.debug(
                        "Mianbao unrestricted ballistic control is unavailable", exception);
            }
            unrestrictedResolved = true;
        }
    }

    private static @Nullable Method findMethod(String className, String name,
                                                Class<?>... parameterTypes) {
        try {
            Class<?> type = Class.forName(className, false,
                    MianbaoBallisticMissileCompat.class.getClassLoader());
            Method method = type.getDeclaredMethod(name, parameterTypes);
            if (!Modifier.isStatic(method.getModifiers())) return null;
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.debug("Mianbao ballistic launcher API is unavailable", exception);
            return null;
        }
    }

    private static @Nullable Method findInstanceMethod(String className, String name,
                                                        Class<?>... parameterTypes) {
        try {
            Class<?> type = Class.forName(className, false,
                    MianbaoBallisticMissileCompat.class.getClassLoader());
            Method method = type.getDeclaredMethod(name, parameterTypes);
            if (Modifier.isStatic(method.getModifiers())) return null;
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            StabilizedTurret.LOGGER.debug("Mianbao ballistic missile API is unavailable", exception);
            return null;
        }
    }

    private static @Nullable LauncherContext resolve(Level level, BindingRef ref) {
        if (level == null || ref == null || !ref.isSameTarget(level)) return null;
        BlockState state = level.getBlockState(ref.pos());
        if (!isBindingTarget(state)) return null;
        if (state.getBlock() instanceof Df26BallisticMissileBlock) {
            BlockPos root = Df26BallisticMissileBlock.structureRoot(ref.pos(), state);
            BlockState rootState = level.getBlockState(root);
            if (!(rootState.getBlock() instanceof Df26BallisticMissileBlock)
                    || rootState.getValue(Df26BallisticMissileBlock.FACING)
                    != state.getValue(Df26BallisticMissileBlock.FACING)
                    || rootState.getValue(Df26BallisticMissileBlock.PART) != 0
                    || rootState.getValue(Df26BallisticMissileBlock.CROSS_U) != 1
                    || rootState.getValue(Df26BallisticMissileBlock.CROSS_V) != 1) {
                return null;
            }
            return new LauncherContext(
                    level, ref.pos(), state, root, level.getBlockEntity(root),
                    Type.DF_26, state.getValue(Df26BallisticMissileBlock.FACING), 0);
        }
        net.minecraft.world.level.block.state.properties.Property<?> facingProperty =
                state.getBlock().getStateDefinition().getProperty("facing");
        net.minecraft.world.level.block.state.properties.Property<?> partProperty =
                state.getBlock().getStateDefinition().getProperty("part");
        if (!(facingProperty instanceof net.minecraft.world.level.block.state.properties.DirectionProperty facing)
                || !(partProperty instanceof net.minecraft.world.level.block.state.properties.IntegerProperty part)) {
            return null;
        }
        Direction direction = state.getValue(facing);
        int partIndex = state.getValue(part);
        BlockPos root = ref.pos().relative(direction.getOpposite(), partIndex);
        for (int index = 0; index < 5; index++) {
            BlockState found = level.getBlockState(root.relative(direction, index));
            if (!isBindingTarget(found)
                    || found.getValue(facing) != direction
                    || found.getValue(part) != index) return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(root);
        net.minecraft.world.level.block.state.properties.Property<?> orientationProperty =
                state.getBlock().getStateDefinition().getProperty("orientation");
        int orientation = orientationProperty
                instanceof net.minecraft.world.level.block.state.properties.IntegerProperty property
                ? state.getValue(property) : 0;
        return new LauncherContext(level, ref.pos(), state, root, blockEntity,
                Type.NATIVE, direction, orientation);
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static void setDf26BaseRotation(
            Entity missile, Level level, BlockPos root, Direction facing
    ) {
        Quaternionf localRotation = switch (facing) {
            case DOWN -> new Quaternionf().rotationX((float) Math.PI);
            case NORTH -> new Quaternionf().rotationX((float) (-Math.PI * 0.5));
            case SOUTH -> new Quaternionf().rotationX((float) (Math.PI * 0.5));
            case WEST -> new Quaternionf().rotationZ((float) (Math.PI * 0.5));
            case EAST -> new Quaternionf().rotationZ((float) (-Math.PI * 0.5));
            case UP -> new Quaternionf();
        };
        Vec3 localOrigin = Vec3.atCenterOf(root);
        Vec3 worldOrigin = Sable.HELPER.projectOutOfSubLevel(level, localOrigin);
        Vec3 axisX = projectedAxis(level, localOrigin, worldOrigin,
                localRotation.transform(new Vector3f(1.0F, 0.0F, 0.0F)));
        Vec3 axisY = projectedAxis(level, localOrigin, worldOrigin,
                localRotation.transform(new Vector3f(0.0F, 1.0F, 0.0F)));
        Vec3 axisZ = projectedAxis(level, localOrigin, worldOrigin,
                localRotation.transform(new Vector3f(0.0F, 0.0F, 1.0F)));
        Matrix3f matrix = new Matrix3f();
        matrix.setColumn(0, new Vector3f(
                (float) axisX.x, (float) axisX.y, (float) axisX.z));
        matrix.setColumn(1, new Vector3f(
                (float) axisY.x, (float) axisY.y, (float) axisY.z));
        matrix.setColumn(2, new Vector3f(
                (float) axisZ.x, (float) axisZ.y, (float) axisZ.z));
        Quaternionf rotation = new Quaternionf().setFromNormalized(matrix).normalize();
        var data = missile.getPersistentData();
        data.putBoolean(DF26_DOCKED_TAG, true);
        data.putFloat(DF26_BASE_ROTATION_X_TAG, rotation.x);
        data.putFloat(DF26_BASE_ROTATION_Y_TAG, rotation.y);
        data.putFloat(DF26_BASE_ROTATION_Z_TAG, rotation.z);
        data.putFloat(DF26_BASE_ROTATION_W_TAG, rotation.w);
    }

    private static Vec3 projectedAxis(
            Level level, Vec3 localOrigin, Vec3 worldOrigin, Vector3f axis
    ) {
        Vec3 endpoint = Sable.HELPER.projectOutOfSubLevel(level,
                localOrigin.add(axis.x, axis.y, axis.z));
        Vec3 projected = endpoint.subtract(worldOrigin);
        return projected.lengthSqr() < 1.0e-8
                ? new Vec3(axis.x, axis.y, axis.z) : projected.normalize();
    }

    private static @Nullable Quaternionf df26BaseRotation(Entity missile) {
        if (missile == null || !isDf26(missile)) return null;
        var data = missile.getPersistentData();
        if (!data.contains(DF26_BASE_ROTATION_W_TAG)) return null;
        return normalizedRotation(new Quaternionf(
                data.getFloat(DF26_BASE_ROTATION_X_TAG),
                data.getFloat(DF26_BASE_ROTATION_Y_TAG),
                data.getFloat(DF26_BASE_ROTATION_Z_TAG),
                data.getFloat(DF26_BASE_ROTATION_W_TAG)));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private record LauncherContext(Level level, BlockPos pos, BlockState state,
                                   BlockPos root, @Nullable BlockEntity blockEntity,
                                   Type type, Direction facing, int orientation) {}

    public record Telemetry(Vec3 position, Vec3 warheadDirection, Vec3 velocity,
                            boolean ignited, boolean docked) {}

    public enum Type {
        NATIVE("native", "原版弹道导弹"),
        DF_26("df_26", "DF-26洲际弹道导弹");

        private final String key;
        private final String displayName;

        Type(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public String key() {
            return key;
        }

        public String displayName() {
            return displayName;
        }

        public static @Nullable Type byKey(String key) {
            for (Type type : values()) {
                if (type.key.equals(key)) return type;
            }
            return null;
        }
    }

    private MianbaoBallisticMissileCompat() {}
}
