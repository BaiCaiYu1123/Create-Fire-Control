package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.cannon.munition.AnnihilationErasure;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Calls the world's removal callback without entering overridable death/removal methods. */
@Mixin(Entity.class)
public abstract class AnnihilationEntityMixin implements AnnihilationErasure {
    @Shadow private Entity.RemovalReason removalReason;
    @Shadow private EntityInLevelCallback levelCallback;

    @Override
    @Unique
    public void stabilizedTurret$erase() {
        Entity entity = (Entity) (Object) this;
        removalReason = Entity.RemovalReason.DISCARDED;
        entity.stopRiding();
        entity.getPassengers().forEach(Entity::stopRiding);
        levelCallback.onRemove(Entity.RemovalReason.DISCARDED);
    }
}
