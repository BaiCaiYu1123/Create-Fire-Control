package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.MianbaoBallisticMissileCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Per-thread visual context used while Mianbao creates DF-26 exhaust particles. */
public final class Df26BallisticMissileVisuals {
    public static final float PARTICLE_SCALE = 9.0F;
    public static final float EXHAUST_PARTICLE_SCALE = 4.5F;
    /** Width multiplier for the original Mianbao ballistic flame texture. */
    public static final float FLAME_PARTICLE_SCALE = 2.0F;
    /** Length of the flame samples behind the DF-26 nozzle, in blocks. */
    public static final double FLAME_LENGTH = 8.0D;
    public static final float FLIGHT_SMOKE_PARTICLE_RADIUS = 1.5F;
    private static final double FLIGHT_SMOKE_DISTRIBUTION_RADIUS = 1.5;
    private static final ThreadLocal<Integer> SCALE_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Entity> CURRENT_MISSILE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> TAIL_SAMPLE =
            ThreadLocal.withInitial(() -> 0);

    public static void beginScaledParticles() {
        SCALE_DEPTH.set(SCALE_DEPTH.get() + 1);
    }

    public static void beginScaledParticles(Entity missile) {
        if (SCALE_DEPTH.get() == 0) {
            CURRENT_MISSILE.set(missile);
            TAIL_SAMPLE.set(0);
        }
        beginScaledParticles();
    }

    public static void endScaledParticles() {
        int depth = SCALE_DEPTH.get() - 1;
        if (depth <= 0) {
            SCALE_DEPTH.remove();
            CURRENT_MISSILE.remove();
            TAIL_SAMPLE.remove();
        } else SCALE_DEPTH.set(depth);
    }

    public static boolean scalingParticles() {
        return SCALE_DEPTH.get() > 0;
    }

    /**
     * Keeps the native ground blast visible after DF-26 removes its custom
     * launcher blocks. Native Mianbao smoke only emits when its ray hits the
     * native launcher block, which is not guaranteed for this launcher.
     */
    public static void emitReliableGroundSmoke(Entity missile, ClientLevel level,
                                               Vec3 nozzle, Vec3 exhaustEnd,
                                               Vec3 forward, int launchAge) {
        if (missile == null || level == null || nozzle == null || exhaustEnd == null
                || forward == null || launchAge < 0 || launchAge > 180
                || (launchAge & 1) != 0) return;
        BlockHitResult hit = level.clip(new ClipContext(
                nozzle, exhaustEnd, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, missile));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            var key = BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(hit.getBlockPos()).getBlock());
            if (key != null && "mianbaos_modernwarfare".equals(key.getNamespace())
                    && "ballistic_missile_launcher".equals(key.getPath())) return;
        }
        Object registered = BuiltInRegistries.PARTICLE_TYPE.get(
                ResourceLocation.fromNamespaceAndPath(
                        "mianbaos_modernwarfare", "ballistic_launch_smoke"));
        if (!(registered instanceof ParticleOptions smoke)) return;

        Vec3 center = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? hit.getLocation() : exhaustEnd;
        Vec3 normal = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? new Vec3(hit.getDirection().getStepX(), hit.getDirection().getStepY(),
                hit.getDirection().getStepZ()) : forward.normalize();
        if (normal.lengthSqr() < 1.0e-8) normal = new Vec3(0.0, 1.0, 0.0);
        normal = normal.normalize();
        Vec3 tangent = normal.cross(Math.abs(normal.y) > 0.9
                ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0)).normalize();
        Vec3 bitangent = normal.cross(tangent).normalize();
        double power = Math.min(1.0, (launchAge + 1) / 32.0);
        int count = 14 + (int) (power * 16.0);
        double phase = level.random.nextDouble() * Math.PI * 2.0;
        for (int index = 0; index < count; index++) {
            double angle = phase + Math.PI * 2.0 * index / count;
            Vec3 radial = tangent.scale(Math.cos(angle))
                    .add(bitangent.scale(Math.sin(angle)));
            Vec3 start = center.add(normal.scale(0.75 + level.random.nextDouble() * 0.3))
                    .add(radial.scale(0.2 + level.random.nextDouble() * 0.55));
            Vec3 velocity = radial.scale((0.26 + power * 0.52)
                            * (0.7 + level.random.nextDouble() * 0.5))
                    .add(normal.scale(0.035 + level.random.nextDouble() * 0.045));
            // ClientLevel.addParticle applies the native distance limiter.
            // The launch cloud is part of the DF-26 remote visual and must be
            // submitted through the same always-visible path as its trail.
            level.addAlwaysVisibleParticle(smoke, true,
                    start.x, start.y, start.z,
                    velocity.x, velocity.y, velocity.z);
        }
    }

    /** Distributes the existing tail samples across a circular six-block section. */
    public static Vec3 circularTailPosition(ParticleOptions type, Vec3 position) {
        Entity missile = CURRENT_MISSILE.get();
        if (missile == null || type == null || position == null) return position;
        var key = BuiltInRegistries.PARTICLE_TYPE.getKey(type.getType());
        if (key == null || !"mianbaos_modernwarfare".equals(key.getNamespace())
                || !"ballistic_flight_smoke".equals(key.getPath())) return position;
        Quaternionf rotation = MianbaoBallisticMissileCompat.renderRotation(missile);
        if (rotation == null) return position;
        Vector3f rightVector = rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f sideVector = rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F));
        Vec3 right = new Vec3(rightVector.x, rightVector.y, rightVector.z).normalize();
        Vec3 side = new Vec3(sideVector.x, sideVector.y, sideVector.z).normalize();
        int sample = TAIL_SAMPLE.get();
        TAIL_SAMPLE.set(sample + 1);
        long seed = missile.getUUID().getLeastSignificantBits()
                ^ ((long) missile.tickCount << 32) ^ sample * 0x9E3779B97F4A7C15L;
        double unitA = ((seed >>> 11) & 0x1FFFFFL) / (double) 0x1FFFFF;
        double unitB = ((seed >>> 36) & 0xFFFFFL) / (double) 0xFFFFF;
        double radius = Math.sqrt(unitA) * FLIGHT_SMOKE_DISTRIBUTION_RADIUS;
        double angle = unitB * Math.PI * 2.0;
        return position.add(right.scale(Math.cos(angle) * radius))
                .add(side.scale(Math.sin(angle) * radius));
    }

    public static void withScaledParticles(Runnable action) {
        beginScaledParticles();
        try {
            action.run();
        } finally {
            endScaledParticles();
        }
    }

    private Df26BallisticMissileVisuals() {
    }
}
