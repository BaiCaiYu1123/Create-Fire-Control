package com.hooya.stabilizedturret.mixin;

import com.caca.copypoly.PolyBlock;
import com.hooya.stabilizedturret.content.armor.*;
import com.hooya.stabilizedturret.registry.ModArmorUpgrades;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

@Mixin(PolyBlock.class)
public abstract class PolyArmorBlockMixin {
    @Inject(method="openEditor",at=@At("HEAD"),cancellable=true)
    private void fireControl$editablePerforation(Level level,BlockPos pos,Player player,CallbackInfo ci){
        if(level.getBlockEntity(pos) instanceof com.caca.copypoly.PolyBlockEntity be&&be instanceof PolyArmorAccess armor&&armor.fireControl$armorDamage().damaged()){
            if(player instanceof net.minecraft.server.level.ServerPlayer server&&player.mayBuild()&&level.mayInteract(player,pos))
                com.hooya.stabilizedturret.registry.PolyArmorMenus.open(server,pos,be);
            ci.cancel();
        }
    }
    @Inject(method="getDrops",at=@At("RETURN"))
    private void fireControl$dropDamage(BlockState state,LootParams.Builder params,CallbackInfoReturnable<List<ItemStack>> cir){
        if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof PolyArmorAccess armor) {
            for (var stack : cir.getReturnValue()) {
                if (stack.is(state.getBlock().asItem())) fireControl$storeArmor(stack, armor);
            }
        }
    }
    @Inject(method="getCloneItemStack",at=@At("RETURN"))
    private void fireControl$cloneDamage(BlockState state,HitResult hit,LevelReader level,BlockPos pos,Player player,CallbackInfoReturnable<ItemStack> cir){
        if (level.getBlockEntity(pos) instanceof PolyArmorAccess armor && cir.getReturnValue().is(state.getBlock().asItem())) {
            fireControl$storeArmor(cir.getReturnValue(), armor);
        }
    }
    @org.spongepowered.asm.mixin.Unique
    private static void fireControl$storeArmor(ItemStack stack, PolyArmorAccess armor) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, armor.fireControl$armorDamage()::write);
        ArmorUpgrade.writeLevel(stack, armor.fireControl$armorLevel());
    }
    @Inject(method="setPlacedBy",at=@At("TAIL"))
    private void fireControl$placeDamage(Level level,BlockPos pos,BlockState state,LivingEntity placer,ItemStack stack,CallbackInfo ci){
        if(!level.isClientSide&&level.getBlockEntity(pos) instanceof PolyArmorAccess armor){
            if (stack.has(ModArmorUpgrades.ARMOR_LEVEL.get())) armor.fireControl$setArmorLevel(ArmorUpgrade.level(stack));
            var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            // Preserve a schematic's existing block-entity data when its item has no damage override.
            if(tag.contains(ArmorDamage.TAG)||tag.contains(ArmorDamage.EDITED_TAG)){armor.fireControl$armorDamage().read(tag);armor.fireControl$armorChanged();}
        }
    }
    @Inject(method="getCollisionShape",at=@At("HEAD"),cancellable=true)
    private void fireControl$projectilePassage(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context,CallbackInfoReturnable<VoxelShape> cir){
        if(context instanceof EntityCollisionContext entity&&ArmorProjectilePassage.ignores(entity.getEntity(),pos))cir.setReturnValue(Shapes.empty());
    }
}
