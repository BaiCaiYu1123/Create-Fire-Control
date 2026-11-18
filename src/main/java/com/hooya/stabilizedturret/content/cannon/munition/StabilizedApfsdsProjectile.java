package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.EntityDamagePropertiesComponent;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonProjectile;

/**
 * Keeps CBC-MW's APFSDS impact implementation while applying the requested
 * two-times muzzle velocity to every medium-cannon material.
 */
public class StabilizedApfsdsProjectile extends APFSDSMediumcannonProjectile {
    public static final float VELOCITY_MULTIPLIER = 2.0F;
    private static final EntityDamagePropertiesComponent DAMAGE =
            new EntityDamagePropertiesComponent(50.0F, false, true, true, 2.0F);
    private static final BallisticPropertiesComponent BALLISTICS =
            new BallisticPropertiesComponent(
                    -0.03,
                    0.01,
                    false,
                    10.0F,
                    4.0F,
                    1.0F,
                    0.17364818F
            );

    public StabilizedApfsdsProjectile(
            EntityType<? extends APFSDSMediumcannonProjectile> type,
            Level level
    ) {
        super(type, level);
    }

    @Override
    public ItemStack getItem() {
        return ModCbcContent.APFSDS_MEDIUM_CANNON_ROUND.get().getDefaultInstance();
    }

    @Override
    protected rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile.ImpactResult calculateBlockPenetration(
            rbasamoyai.createbigcannons.munitions.ProjectileContext context,
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (!(state.getBlock() instanceof com.caca.copypoly.PolyBlock)
                && !com.hooya.stabilizedturret.content.armor.PolyArmorImpacts.active())
            return super.calculateBlockPenetration(context, state, hit);
        try (var scope = com.hooya.stabilizedturret.content.armor.PolyArmorImpacts.begin(this, state, hit)) {
            // The original CBC/CBC-MW method, including CTB's optional override,
            // still owns penetration, mass loss, ricochet and projectile removal.
            var result = super.calculateBlockPenetration(context, state, hit);
            scope.complete(result, hit);
            return result;
        }
    }

    @Override
    public EntityDamagePropertiesComponent getDamageProperties() {
        return DAMAGE;
    }

    @Override
    protected BallisticPropertiesComponent getBallisticProperties() {
        return BALLISTICS;
    }

    @Override
    public Vec3 getOrientation() {
        // Sable rotates deltaMovement when it kicks the shot out of its plot,
        // but CBC's separate synchronized orientation is still unset/stale.
        // Flight rendering and spawn data must use that final world velocity.
        Vec3 motion = getDeltaMovement();
        if (!isInGround() && motion.lengthSqr() > 1.0e-8
                && Double.isFinite(motion.x) && Double.isFinite(motion.y) && Double.isFinite(motion.z)) {
            return motion;
        }
        return super.getOrientation();
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        // Vanilla pairing/impulse packets cap each axis at 3.9 blocks/tick.
        // CBC's complex spawn data and our precise motion packets already
        // apply the full vector through setDeltaMovement, including ricochets.
        if (isInGround()) {
            super.lerpMotion(x, y, z);
        }
    }

    @Override
    public void shoot(
            double x,
            double y,
            double z,
            float velocity,
            float inaccuracy
    ) {
        super.shoot(x, y, z, velocity * VELOCITY_MULTIPLIER, inaccuracy);
    }

    /** Read reflectively by the optional fire-control compatibility layer. */
    public double stabilizedTurret$getVelocityMultiplier() {
        return VELOCITY_MULTIPLIER;
    }

}
