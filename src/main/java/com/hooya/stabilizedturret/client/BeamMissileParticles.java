package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.OrdinaryAirDefenseGuidance;
import com.hooya.stabilizedturret.mixin.client.NativeMissileParticleFactory;
import com.hooya.stabilizedturret.mixin.client.ParticleAccessor;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** Smaller, translucent native exhaust for computer-controlled beam-riding missiles. */
public final class BeamMissileParticles {
    private static final float SIZE_SCALE = 0.5F;
    private static final float OPACITY = 0.3F;
    private static final String REMOTE_VISUAL_TAG = "CreateFireControlRemoteVisual";

    public static boolean isManaged(Entity missile) {
        return missile != null
                && (OrdinaryAirDefenseGuidance.isOrdinaryType(missile.getType())
                || MianbaoMissileCompat.isGuidedMissile(missile))
                && (missile.getPersistentData().getBoolean(REMOTE_VISUAL_TAG)
                || missile.getPersistentData().getBoolean("CreateFireControlGuided")
                || MissileLongRangeEffects.shouldSuppressNativeMissile(missile));
    }

    public static boolean isExhaust(ParticleOptions options) {
        var id = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        return id != null && id.getNamespace().equals(MianbaoMissileCompat.MOD_ID)
                && switch (id.getPath()) {
                    case "newrocketmissiletrailsmall", "rocketmissiletrail_3", "newsmoke_2" -> true;
                    default -> false;
                };
    }

    public static void spawn(ClientLevel level, ParticleOptions options,
                             double x, double y, double z, double dx, double dy, double dz) {
        var engine = Minecraft.getInstance().particleEngine;
        Particle original = ((NativeMissileParticleFactory) engine).createFireControl$makeParticle(
                options, x, y, z, dx, dy, dz);
        if (original != null) engine.add(new ExhaustParticle(level, x, y, z, original));
    }

    /** Retains the installed provider's textures, motion, lifetime and animation. */
    private static final class ExhaustParticle extends Particle {
        private final Particle original;

        private ExhaustParticle(ClientLevel level, double x, double y, double z, Particle original) {
            super(level, x, y, z);
            this.original = original;
            original.scale(SIZE_SCALE);
        }

        @Override public ParticleRenderType getRenderType() {
            return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
        }

        @Override public AABB getBoundingBox() {
            return original == null ? super.getBoundingBox() : original.getBoundingBox();
        }

        @Override public void tick() {
            original.tick();
            if (!original.isAlive()) remove();
        }

        @Override public void remove() {
            super.remove();
            original.remove();
        }

        @Override public void render(VertexConsumer vertices, Camera camera, float partial) {
            ParticleAccessor state = (ParticleAccessor) original;
            float nativeAlpha = state.createFireControl$getAlpha();
            state.createFireControl$setAlpha(nativeAlpha * OPACITY);
            try {
                original.render(vertices, camera, partial);
            } finally {
                state.createFireControl$setAlpha(nativeAlpha);
            }
        }
    }

    private BeamMissileParticles() {}
}
