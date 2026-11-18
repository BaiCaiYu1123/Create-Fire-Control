package com.hooya.stabilizedturret.mixin;

import com.caca.copypoly.PolyBlock;
import com.hooya.stabilizedturret.content.armor.PolyArmorProperties;
import com.hooya.stabilizedturret.content.armor.EquivalentArmor;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.block_armor_properties.*;

@Mixin(value=BlockArmorPropertiesHandler.class,remap=false)
public abstract class PolyArmorPropertiesMixin {
    @Inject(method="getProperties(Lnet/minecraft/world/level/block/Block;)Lrbasamoyai/createbigcannons/block_armor_properties/BlockArmorPropertiesProvider;",at=@At("HEAD"),cancellable=true)
    private static void fireControl$armor(Block block,CallbackInfoReturnable<BlockArmorPropertiesProvider> cir){if(block instanceof PolyBlock)cir.setReturnValue(EquivalentArmor.wrap(PolyArmorProperties.INSTANCE));}
    @Inject(method="getProperties(Lnet/minecraft/world/level/block/Block;)Lrbasamoyai/createbigcannons/block_armor_properties/BlockArmorPropertiesProvider;",at=@At("RETURN"),cancellable=true)
    private static void fireControl$allBlockThickness(Block block,CallbackInfoReturnable<BlockArmorPropertiesProvider> cir){cir.setReturnValue(EquivalentArmor.wrap(cir.getReturnValue()));}
}
