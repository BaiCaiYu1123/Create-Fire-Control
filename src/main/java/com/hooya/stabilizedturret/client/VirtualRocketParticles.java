package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.mixin.client.ParticleEngineSpriteAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Native textures with bounded lifetime and no terrain or Sable collision queries. */
public final class VirtualRocketParticles {
    private static final ResourceLocation FLAME = id("rocketmissiletrail_3");
    private static final ResourceLocation SMOKE = id("newsmoke_2");
    private static final ResourceLocation EXPLOSION = id("explosionlarge");
    private static final ResourceLocation SMALL_EXPLOSION = id("explosionsmall");
    private static final ResourceLocation FLARE_GLOW = id("countermeasure_trail");
    private static final ResourceLocation FLARE_SPARK = id("countermeasuretrail_1");
    private static final ResourceLocation FLARE_SMOKE = id("newsmoke_5");
    private static final RocketParticleBudget BUDGET = new RocketParticleBudget();
    private static final List<VisualParticle> PARTICLES = new ArrayList<>();
    private static ClientLevel trackedLevel;

    public static void clear() {
        PARTICLES.forEach(VisualParticle::remove);
        PARTICLES.clear();
        trackedLevel = null;
        BUDGET.clear();
    }

    public static void tick(ClientLevel level) {
        ensureLevel(level);
        PARTICLES.removeIf(particle -> {
            // ParticleEngine's full queue can evict without calling remove().
            // Keep a bounded owner list so those reservations also expire.
            if (level.getGameTime() - particle.createdAt >= particle.getLifetime() + 2L) {
                particle.remove();
            }
            return !particle.isAlive();
        });
    }

    private static void ensureLevel(ClientLevel level) {
        if (trackedLevel == level) return;
        clear();
        trackedLevel = level;
    }

    /** The batched 64-point trail supplies distant smoke; nearby puffs add volume. */
    public static void trail(ClientLevel level, Vec3 position, Vec3 velocity, Vec3 camera) {
        double distance = position.distanceToSqr(camera);
        if (distance > 4096.0 * 4096.0) return;
        Vec3 exhaust = position.subtract(velocity.scale(0.25));
        spawn(level, RocketParticleBudget.Kind.FLAME, exhaust.x, exhaust.y, exhaust.z, 0, 0, 0);
        if (distance <= 96.0 * 96.0) {
            spawn(level, RocketParticleBudget.Kind.SMOKE, exhaust.x, exhaust.y, exhaust.z,
                    (level.random.nextDouble() - 0.5) * 0.012,
                    (level.random.nextDouble() - 0.5) * 0.012,
                    (level.random.nextDouble() - 0.5) * 0.012);
        }
    }

    public static boolean explosion(ClientLevel level, double x, double y, double z,
                                    double dx, double dy, double dz) {
        return spawn(level, RocketParticleBudget.Kind.EXPLOSION, x, y, z, dx, dy, dz);
    }

    public static void countermeasure(ClientLevel level, Vec3 position, Vec3 camera) {
        double distance = position.distanceToSqr(camera);
        if (distance > 20000.0 * 20000.0) return;
        spawn(level, RocketParticleBudget.Kind.FLARE_GLOW, position.x, position.y, position.z, 0, 0, 0);
        if (distance <= 256.0 * 256.0) {
            spawn(level, RocketParticleBudget.Kind.FLARE_SPARK, position.x, position.y, position.z, 0, 0, 0);
        }
        if (distance <= 96.0 * 96.0) {
            spawn(level, RocketParticleBudget.Kind.FLARE_SMOKE, position.x, position.y, position.z,
                    (level.random.nextDouble() - 0.5) * 0.03,
                    (level.random.nextDouble() - 0.5) * 0.03,
                    (level.random.nextDouble() - 0.5) * 0.03);
        }
    }

    private static boolean spawn(ClientLevel level, RocketParticleBudget.Kind kind,
                                 double x, double y, double z, double dx, double dy, double dz) {
        ensureLevel(level);
        var engine = Minecraft.getInstance().particleEngine;
        var sprites = ((ParticleEngineSpriteAccessor) engine).createFireControl$getSpriteSets();
        ResourceLocation type = switch (kind) {
            case FLAME -> FLAME;
            case SMOKE -> SMOKE;
            case EXPLOSION -> EXPLOSION;
            case FLARE_GLOW -> FLARE_GLOW;
            case FLARE_SPARK -> FLARE_SPARK;
            case FLARE_SMOKE -> FLARE_SMOKE;
        };
        SpriteSet sprite = sprites.get(type);
        if (sprite == null && kind == RocketParticleBudget.Kind.EXPLOSION) {
            sprite = sprites.get(SMALL_EXPLOSION);
        }
        if (sprite == null) return false;
        RocketParticleBudget.Ticket ticket = BUDGET.acquire(kind, level.getGameTime());
        if (ticket == null) return false;
        VisualParticle particle = new VisualParticle(level, x, y, z, dx, dy, dz, kind, sprite, ticket);
        PARTICLES.add(particle);
        engine.add(particle);
        return true;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path);
    }

    private static final class VisualParticle extends TextureSheetParticle {
        private final SpriteSet sprites;
        private final RocketParticleBudget.Ticket ticket;
        private final float acceleration;
        private final long createdAt;
        private final float spin;

        private VisualParticle(ClientLevel level, double x, double y, double z,
                               double dx, double dy, double dz, RocketParticleBudget.Kind kind,
                               SpriteSet sprites, RocketParticleBudget.Ticket ticket) {
            super(level, x, y, z);
            this.sprites = sprites;
            this.ticket = ticket;
            createdAt = level.getGameTime();
            hasPhysics = false;
            lifetime = kind == RocketParticleBudget.Kind.SMOKE
                    ? ClientVisualConfig.smokeLifetime(kind.lifetime) : kind.lifetime;
            quadSize *= switch (kind) {
                case FLAME -> 7.0F;
                case SMOKE -> 13.0F;
                case EXPLOSION -> 22.0F;
                case FLARE_GLOW, FLARE_SPARK -> 12.0F;
                case FLARE_SMOKE -> 10.0F;
            };
            acceleration = switch (kind) {
                case SMOKE, FLARE_SMOKE -> -0.0002F;
                case EXPLOSION -> 0.0004F;
                default -> 0.0F;
            };
            spin = kind == RocketParticleBudget.Kind.FLARE_GLOW ? 0.1F
                    : kind == RocketParticleBudget.Kind.FLARE_SPARK ? 0.5F : 0.0F;
            xd = dx;
            yd = dy;
            zd = dz;
            setSpriteFromAge(sprites);
        }

        @Override
        public void tick() {
            xo = x;
            yo = y;
            zo = z;
            if (++age >= lifetime) {
                remove();
                return;
            }
            yd += acceleration;
            move(xd, yd, zd);
            xd *= 0.96;
            yd *= 0.96;
            zd *= 0.96;
            oRoll = roll;
            roll += spin;
            // Play the entire native animation within the shorter lifetime.
            setSpriteFromAge(sprites);
            alpha = Math.min(1.0F, (lifetime - age) / (lifetime * 0.25F));
        }

        @Override
        public void move(double dx, double dy, double dz) {
            // Do not call Particle.move: Sable wraps it with structure scans
            // even when hasPhysics is false. These are world-space visuals.
            setPos(x + dx, y + dy, z + dz);
        }

        @Override
        public void remove() {
            super.remove();
            ticket.close();
        }

        @Override
        protected int getLightColor(float partialTick) { return LightTexture.FULL_BRIGHT; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }
    }

    private VirtualRocketParticles() {}
}
