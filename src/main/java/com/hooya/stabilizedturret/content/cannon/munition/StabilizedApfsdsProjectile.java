package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.EntityDamagePropertiesComponent;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonProjectile;

/**
 * Keeps CBC-MW's APFSDS impact implementation while applying the requested
 * two-times muzzle velocity to every medium-cannon material.
 */
public final class StabilizedApfsdsProjectile extends APFSDSMediumcannonProjectile {
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
    public EntityDamagePropertiesComponent getDamageProperties() {
        return DAMAGE;
    }

    @Override
    protected BallisticPropertiesComponent getBallisticProperties() {
        return BALLISTICS;
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
