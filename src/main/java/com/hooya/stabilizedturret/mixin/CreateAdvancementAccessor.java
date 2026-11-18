package com.hooya.stabilizedturret.mixin;

import com.simibubi.create.foundation.advancement.CreateAdvancement;
import net.minecraft.advancements.Advancement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses Create's vanilla advancement builder for the lazy-icon compatibility patch. */
@Mixin(value = CreateAdvancement.class, remap = false)
public interface CreateAdvancementAccessor {
    @Accessor("mcBuilder")
    Advancement.Builder stabilizedTurret$getMinecraftBuilder();
}
