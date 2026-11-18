package com.hooya.stabilizedturret.compat.touhoumaid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.CollisionInfo;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Suppresses only Sable structure impact damage while a maid is assigned to loading. */
public final class MaidLoadingSableCollisionProtection {
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)
                || maid.getTask() == null
                || !TaskCannonLoading.UID.equals(maid.getTask().getUid())
                || !event.getSource().is(DamageTypes.FLY_INTO_WALL)
                || !(maid instanceof EntityMovementExtension movement)) {
            return;
        }

        CollisionInfo collision = movement.sable$getCollisionInfo();
        if (collision == null || collision.firstCollisions == null
                || collision.firstCollisions.isEmpty()) {
            return;
        }

        // Sable applies fly-into-wall damage only for a horizontal collision against a physical
        // sub-level other than the entity's pre-existing tracking sub-level. Requiring the same
        // live collision record keeps ordinary damage, including non-Sable wall impacts, intact.
        boolean sableStructureImpact = collision.firstCollisions.entrySet().stream()
                .anyMatch(entry -> entry.getKey() != collision.preTrackingSubLevel
                        && entry.getValue() != null && entry.getValue().horizontal());
        if (sableStructureImpact) event.setCanceled(true);
    }

    private MaidLoadingSableCollisionProtection() {
    }
}
