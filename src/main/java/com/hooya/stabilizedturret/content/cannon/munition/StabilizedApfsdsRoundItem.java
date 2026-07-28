package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import javax.annotation.Nonnull;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonRoundItem;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.config.MediumcannonProjectilePropertiesComponent;

public final class StabilizedApfsdsRoundItem extends APFSDSMediumcannonRoundItem {
    public StabilizedApfsdsRoundItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public AbstractMediumcannonProjectile getMediumcannonProjectile(
            ItemStack stack,
            Level level
    ) {
        return ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get();
    }

    @Override
    @Nonnull
    public MediumcannonProjectilePropertiesComponent getMediumcannonProperties(
            ItemStack itemStack
    ) {
        return new MediumcannonProjectilePropertiesComponent(1.0, true);
    }

    @Override
    public ItemStack getCartridgeType() {
        return new ItemStack(ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get());
    }
}
