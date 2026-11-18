package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Reuses the native animated atlas without constructing native collision particles. */
@Mixin(ParticleEngine.class)
public interface ParticleEngineSpriteAccessor {
    @Accessor("spriteSets")
    Map<ResourceLocation, ? extends SpriteSet> createFireControl$getSpriteSets();
}
