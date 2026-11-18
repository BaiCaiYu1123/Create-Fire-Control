package com.hooya.stabilizedturret.mixin.client;

import com.caca.copypoly.client.PolyModel;
import com.hooya.stabilizedturret.client.armor.PolyArmorModel;
import com.hooya.stabilizedturret.content.armor.*;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** Applied after the upstream model is baked, independent of mod event-bus ordering. */
@Mixin(value=PolyModel.class,remap=false)
public abstract class PolyArmorModelMixin {
    @Unique private PolyArmorModel fireControl$damageModel;
    @Inject(method="getQuads(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)Ljava/util/List;",at=@At("HEAD"),cancellable=true)
    private void fireControl$cutSurface(BlockState state,Direction side,RandomSource random,ModelData data,RenderType type,CallbackInfoReturnable<List<BakedQuad>> cir){
        if(data.has(PolyArmorAccess.MESH)){
            if(fireControl$damageModel==null)fireControl$damageModel=new PolyArmorModel((PolyModel)(Object)this);
            cir.setReturnValue(fireControl$damageModel.getQuads(state,side,random,data,type));
        }
    }
    @Inject(method="getOverrides",at=@At("RETURN"),cancellable=true)
    private void fireControl$damagedItem(CallbackInfoReturnable<ItemOverrides> cir){
        var original=cir.getReturnValue();var model=(PolyModel)(Object)this;
        cir.setReturnValue(new ItemOverrides(){
            @Override public net.minecraft.client.resources.model.BakedModel resolve(net.minecraft.client.resources.model.BakedModel base,net.minecraft.world.item.ItemStack stack,net.minecraft.client.multiplayer.ClientLevel level,net.minecraft.world.entity.LivingEntity entity,int seed){
                var damaged=PolyArmorModel.damagedItem(model,stack);return damaged!=null?damaged:original.resolve(base,stack,level,entity,seed);
            }});
    }
}
