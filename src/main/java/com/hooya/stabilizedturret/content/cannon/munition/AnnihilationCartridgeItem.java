package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonCartridgeItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.config.MediumcannonProjectilePropertiesComponent;

public final class AnnihilationCartridgeItem extends MediumcannonCartridgeItem {
    public AnnihilationCartridgeItem(Properties properties) { super(properties); }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack cartridge = new ItemStack(this);
        writeProjectile(ModCbcContent.ANNIHILATION_MEDIUM_CANNON_ROUND.get().getDefaultInstance(), cartridge);
        return cartridge;
    }

    @Override
    public ItemStack getSpentItem(ItemStack stack) {
        return ModCbcContent.APFSDS_MEDIUM_CANNON_CASING.get().getDefaultInstance();
    }

    @Override
    public MediumcannonProjectilePropertiesComponent getMediumcannonProperties(ItemStack stack) {
        return ModCbcContent.ANNIHILATION_MEDIUM_CANNON_ROUND.get().getMediumcannonProperties(stack);
    }

    @Override
    public AbstractMediumcannonProjectile getMediumcannonProjectile(ItemStack stack, Level level) {
        return ModCbcContent.ANNIHILATION_MEDIUM_CANNON_PROJECTILE.get().create(level);
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return ModCbcContent.ANNIHILATION_MEDIUM_CANNON_PROJECTILE.get();
    }

    @Override
    public boolean isFoil(ItemStack stack) { return true; }
}
