package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Fire-control bridge dedicated to Mianbao's aircraft cannon turret pod. */
public final class MianbaoCannonTurretPodCompat {
    public static final String PROJECTILE_TAG = "CreateFireControlCannonTurretPodShell";
    public static final double MUZZLE_SPEED = CannonTurretPodBallistics.MUZZLE_SPEED;
    /** Native arrows use 0.05; the pod round is deliberately about 30% flatter. */
    public static final double GRAVITY_PER_TICK = CannonTurretPodBallistics.GRAVITY_PER_TICK;
    public static final double DRAG_PER_TICK = CannonTurretPodBallistics.DRAG_PER_TICK;

    private static final String MOD_NAMESPACE = "mianbaos_modernwarfare";
    private static final String API_CLASS = "net.mcreator.myfirstmod.api.RemoteWeaponStationApi";
    private static final ResourceLocation CANNON_TURRET = id("cannonturret");
    private static final ResourceLocation AUTOCANNON_PROJECTILE = id("autocannontanshe");
    private static final ResourceLocation AUTOCANNON_FIRE_SOUND = id("new_autocannon_fire");
    private static final ResourceLocation AUTOCANNON_FAR_SOUND = id("new_cannonfire_far");
    private static final ResourceLocation AUTOCANNON_SPARK = id("spark");
    private static final int FIRE_INTERVAL_TICKS = 2;
    private static final Map<Level, Map<BindingRef, Long>> LAST_FIRE_TICKS =
            new WeakHashMap<>();

    private static volatile boolean apiLookupAttempted;
    private static volatile @Nullable Method findControllerMethod;

    public static boolean isCannonTurret(Level level, BlockPos pos) {
        return level != null && pos != null && CANNON_TURRET.equals(
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
    }

    public static @Nullable Vec3 getMuzzlePosition(Level level, BindingRef ref) {
        Vec3 local = invokeVec3(resolve(level, ref), "getMuzzlePosition");
        return local == null ? null : toWorldPosition(level, ref, local);
    }

    public static @Nullable Vec3 getMuzzleDirection(Level level, BindingRef ref) {
        Vec3 local = invokeVec3(resolve(level, ref), "getMuzzleDirection");
        if (local == null || local.lengthSqr() < 1.0e-10) return null;
        Vec3 world = toWorldDirection(level, ref, local);
        return finite(world) && world.lengthSqr() >= 1.0e-10 ? world.normalize() : null;
    }

    /** Complete Sable rigid-body velocity at the API-reported muzzle point. */
    public static Vec3 carrierVelocityAtMuzzle(Level level, BindingRef ref) {
        if (level == null || ref == null || ref.subLevelId() == null) return Vec3.ZERO;
        BlockEntity blockEntity = ref.resolve(level);
        Vec3 localMuzzle = invokeVec3(resolve(level, ref), "getMuzzlePosition");
        if (blockEntity == null || blockEntity.getLevel() == null || localMuzzle == null) {
            return Vec3.ZERO;
        }
        SubLevel carrier = SubLevelContainer.getContainer(level).getSubLevel(ref.subLevelId());
        if (carrier == null || carrier.isRemoved()) return Vec3.ZERO;
        Vector3d localPoint = new Vector3d(localMuzzle.x, localMuzzle.y, localMuzzle.z);
        try {
            Vector3d velocity = Sable.HELPER.getVelocity(
                    blockEntity.getLevel(), carrier, localPoint, new Vector3d()).div(20.0);
            if (velocity.isFinite() && velocity.lengthSquared() > 1.0e-10) {
                return new Vec3(velocity.x, velocity.y, velocity.z);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fall back to the two most recent rigid-body poses while physics rebuilds.
        }
        try {
            Vector3d current = carrier.logicalPose().transformPosition(new Vector3d(localPoint));
            Vector3d previous = carrier.lastPose().transformPosition(new Vector3d(localPoint));
            Vector3d velocity = current.sub(previous);
            return velocity.isFinite() ? new Vec3(velocity.x, velocity.y, velocity.z) : Vec3.ZERO;
        } catch (RuntimeException | LinkageError ignored) {
            return Vec3.ZERO;
        }
    }

    /** Aims along a gravity- and carrier-velocity-compensated launch direction. */
    public static boolean pointMuzzleAt(Level level, BindingRef ref, Vec3 worldTarget) {
        Vec3 muzzle = getMuzzlePosition(level, ref);
        Vec3 forward = getMuzzleDirection(level, ref);
        if (muzzle == null || forward == null || !finite(worldTarget)) return false;
        Vec3 direction = solveDirection(
                muzzle, forward, carrierVelocityAtMuzzle(level, ref), worldTarget);
        if (direction == null) {
            direction = worldTarget.subtract(muzzle);
            if (direction.lengthSqr() < 1.0e-10) return false;
            direction = direction.normalize();
        }
        return setMuzzleDirection(level, ref, direction);
    }

    static @Nullable Vec3 solveDirection(
            Vec3 muzzle, Vec3 forward, Vec3 carrierVelocity, Vec3 target
    ) {
        return CannonTurretPodBallistics.solveDirection(
                muzzle, forward, carrierVelocity, target);
    }

    /** Redstone-held 600 RPM fire with native damage/explosion behavior and no heat state. */
    public static boolean tickPoweredFire(Level level, BindingRef ref) {
        if (level == null || level.isClientSide || ref == null
                || !level.hasNeighborSignal(ref.pos())) return false;
        Vec3 muzzle = getMuzzlePosition(level, ref);
        Vec3 direction = getMuzzleDirection(level, ref);
        if (muzzle == null || direction == null) return false;
        ServerLevel root = level.getServer() == null
                ? null : level.getServer().getLevel(level.dimension());
        if (root == null || !reserveFireTick(root, ref)) return false;

        EntityType<?> projectileType = BuiltInRegistries.ENTITY_TYPE.get(AUTOCANNON_PROJECTILE);
        Entity entity = projectileType.create(root);
        if (!(entity instanceof Projectile projectile)) return false;
        entity.setPos(muzzle.x, muzzle.y, muzzle.z);
        entity.setSilent(true);
        entity.getPersistentData().putBoolean(PROJECTILE_TAG, true);
        MianbaoAutocannonProjectileLifetimeHandler.arm(entity, root);
        if (entity instanceof AbstractArrow arrow) {
            arrow.setBaseDamage(5.0);
        }
        projectile.shoot(direction.x, direction.y, direction.z,
                (float) MUZZLE_SPEED, 0.0F);
        entity.setDeltaMovement(entity.getDeltaMovement()
                .add(carrierVelocityAtMuzzle(level, ref)));
        entity.hasImpulse = true;
        if (!root.addFreshEntity(entity)) return false;

        root.sendParticles(ParticleTypes.POOF, muzzle.x, muzzle.y, muzzle.z,
                1, 0.25, 0.25, 0.25, 0.1);
        sendMianbaoParticle(root, AUTOCANNON_SPARK, muzzle,
                1, 0.25, 0.25, 0.25, 0.1);
        // Native 4/32 gains are clamped to full gain. 0.5 is an actual 50% volume.
        play(root, AUTOCANNON_FIRE_SOUND, muzzle, 0.5F);
        play(root, AUTOCANNON_FAR_SOUND, muzzle, 0.5F);
        return true;
    }

    private static boolean setMuzzleDirection(Level level, BindingRef ref, Vec3 worldDirection) {
        if (!finite(worldDirection) || worldDirection.lengthSqr() < 1.0e-10) return false;
        Object station = resolve(level, ref);
        if (station == null) return false;
        Vec3 localDirection = toStationDirection(level, ref, worldDirection.normalize());
        try {
            Object result = station.getClass().getMethod("setMuzzleDirection", Vec3.class)
                    .invoke(station, localDirection);
            return !(result instanceof Boolean accepted) || accepted;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static @Nullable Object resolve(Level level, BindingRef ref) {
        if (level == null || level.isClientSide || ref == null
                || !ref.isSameTarget(level) || !isCannonTurret(level, ref.pos())) return null;
        return findController(level, ref.pos());
    }

    private static @Nullable Vec3 invokeVec3(@Nullable Object owner, String methodName) {
        if (owner == null) return null;
        try {
            Object result = owner.getClass().getMethod(methodName).invoke(owner);
            return result instanceof Vec3 vector && finite(vector) ? vector : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean reserveFireTick(Level level, BindingRef ref) {
        synchronized (LAST_FIRE_TICKS) {
            Map<BindingRef, Long> ticks = LAST_FIRE_TICKS.computeIfAbsent(
                    level, ignored -> new HashMap<>());
            long now = level.getGameTime();
            Long previous = ticks.get(ref);
            if (previous != null && now >= previous
                    && now - previous < FIRE_INTERVAL_TICKS) return false;
            ticks.put(ref, now);
            return true;
        }
    }

    private static Vec3 toWorldPosition(Level level, BindingRef ref, Vec3 localPosition) {
        SubLevel containing = containing(level, ref);
        if (containing == null) return localPosition;
        Vector3d world = containing.logicalPose().transformPosition(
                new Vector3d(localPosition.x, localPosition.y, localPosition.z));
        return new Vec3(world.x, world.y, world.z);
    }

    private static Vec3 toWorldDirection(Level level, BindingRef ref, Vec3 localDirection) {
        SubLevel containing = containing(level, ref);
        if (containing == null) return localDirection;
        Vector3d world = containing.logicalPose().transformNormal(
                new Vector3d(localDirection.x, localDirection.y, localDirection.z));
        return new Vec3(world.x, world.y, world.z);
    }

    private static Vec3 toStationDirection(Level level, BindingRef ref, Vec3 worldDirection) {
        SubLevel containing = containing(level, ref);
        if (containing == null) return worldDirection;
        Vector3d local = containing.logicalPose().transformNormalInverse(
                new Vector3d(worldDirection.x, worldDirection.y, worldDirection.z));
        return new Vec3(local.x, local.y, local.z);
    }

    private static @Nullable SubLevel containing(Level level, BindingRef ref) {
        if (level == null || ref == null) return null;
        try {
            SubLevel containing = Sable.HELPER.getContaining(level, ref.pos());
            return containing == null || containing.isRemoved() ? null : containing;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Object findController(BlockGetter level, BlockPos pos) {
        Method find = findControllerMethod(level.getClass());
        if (find == null) return null;
        try {
            Object result = find.invoke(null, level, pos);
            return result instanceof Optional<?> optional ? optional.orElse(null) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static @Nullable Method findControllerMethod(Class<?> levelClass) {
        if (apiLookupAttempted) return findControllerMethod;
        synchronized (MianbaoCannonTurretPodCompat.class) {
            if (apiLookupAttempted) return findControllerMethod;
            try {
                Class<?> api = Class.forName(API_CLASS);
                for (Method method : api.getMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getName().equals("findController")
                            && Modifier.isStatic(method.getModifiers())
                            && parameters.length == 2
                            && parameters[0].isAssignableFrom(levelClass)
                            && parameters[1] == BlockPos.class) {
                        findControllerMethod = method;
                        break;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                findControllerMethod = null;
            }
            apiLookupAttempted = true;
            return findControllerMethod;
        }
    }

    private static void play(
            ServerLevel level, ResourceLocation soundId, Vec3 position, float volume
    ) {
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(soundId);
        if (sound == null) return;
        level.playSound(null, position.x, position.y, position.z, sound,
                SoundSource.NEUTRAL, volume,
                Mth.nextFloat(level.random, 0.7F, 1.0F));
    }

    private static void sendMianbaoParticle(
            ServerLevel level, ResourceLocation particleId, Vec3 position,
            int count, double spreadX, double spreadY, double spreadZ, double speed
    ) {
        if (BuiltInRegistries.PARTICLE_TYPE.get(particleId)
                instanceof SimpleParticleType particle) {
            level.sendParticles(particle, position.x, position.y, position.z,
                    count, spreadX, spreadY, spreadZ, speed);
        }
    }

    private static boolean finite(@Nullable Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_NAMESPACE, path);
    }

    private MianbaoCannonTurretPodCompat() {
    }
}
