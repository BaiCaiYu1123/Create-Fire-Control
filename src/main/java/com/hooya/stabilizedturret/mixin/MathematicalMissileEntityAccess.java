package com.hooya.stabilizedturret.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** Rehomes an unregistered flight adapter after its final Sable launch projection. */
@Mixin(Entity.class)
public interface MathematicalMissileEntityAccess {
    @Accessor("level") void createFireControl$setAdapterLevel(Level level);
}
