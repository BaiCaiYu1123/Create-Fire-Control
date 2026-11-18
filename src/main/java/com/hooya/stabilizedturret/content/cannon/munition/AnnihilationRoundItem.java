package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonRoundItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.config.MediumcannonProjectilePropertiesComponent;

public final class AnnihilationRoundItem extends APFSDSMediumcannonRoundItem {
    public AnnihilationRoundItem(Properties properties) { super(properties); }

    @Override
    public AbstractMediumcannonProjectile getMediumcannonProjectile(ItemStack stack, Level level) {
        return ModCbcContent.ANNIHILATION_MEDIUM_CANNON_PROJECTILE.get().create(level);
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return ModCbcContent.ANNIHILATION_MEDIUM_CANNON_PROJECTILE.get();
    }

    @Override
    public MediumcannonProjectilePropertiesComponent getMediumcannonProperties(ItemStack stack) {
        return new MediumcannonProjectilePropertiesComponent(1.0, true);
    }

    @Override
    public ItemStack getCartridgeType() {
        return ModCbcContent.ANNIHILATION_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance();
    }

    @Override
    public boolean isFoil(ItemStack stack) { return true; }
}
