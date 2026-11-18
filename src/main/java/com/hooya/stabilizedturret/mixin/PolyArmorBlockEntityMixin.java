package com.hooya.stabilizedturret.mixin;

import com.caca.copypoly.PolyBlockEntity;
import com.caca.copypoly.compat.PhysicsCompat;
import com.hooya.stabilizedturret.content.armor.*;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(PolyBlockEntity.class)
public abstract class PolyArmorBlockEntityMixin implements PolyArmorAccess {
    @Unique private final ArmorDamage fireControl$damage=new ArmorDamage();
    @Unique private int fireControl$level = ArmorUpgrade.DEFAULT_LEVEL;
    @Shadow private int revision;
    @Override public ArmorDamage fireControl$armorDamage(){return fireControl$damage;}
    @Override public int fireControl$armorLevel() { return fireControl$level; }
    @Override public void fireControl$setArmorLevel(int level) {
        fireControl$level = ArmorUpgrade.clamp(level);
        ((PolyBlockEntity) (Object) this).notifyUpdate();
    }
    @Override public void fireControl$armorChanged(){
        var be=(PolyBlockEntity)(Object)this;revision++;
        if(be.getLevel()!=null){be.notifyUpdate();be.getLevel().updateNeighborsAt(be.getBlockPos(),be.getBlockState().getBlock());PhysicsCompat.shapeChanged(be.getLevel(),be.getBlockPos());}
    }
    @Inject(method="write",at=@At("RETURN"))
    private void fireControl$save(CompoundTag tag,HolderLookup.Provider registries,boolean packet,CallbackInfo ci){fireControl$saveArmor(tag);}
    @Inject(method="writeSafe",at=@At("RETURN"))
    private void fireControl$saveSafe(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci){fireControl$saveArmor(tag);}
    @Unique private void fireControl$saveArmor(CompoundTag tag) {
        fireControl$damage.write(tag);
        if (fireControl$level > ArmorUpgrade.DEFAULT_LEVEL) tag.putInt(ArmorUpgrade.LEVEL_TAG, fireControl$level);
    }
    @Inject(method="read",at=@At("RETURN"))
    private void fireControl$load(CompoundTag tag,HolderLookup.Provider registries,boolean packet,CallbackInfo ci){
        fireControl$level = ArmorUpgrade.clamp(tag.getInt(ArmorUpgrade.LEVEL_TAG));
        var old=fireControl$damage.stamp();fireControl$damage.read(tag);var be=(PolyBlockEntity)(Object)this;
        if(old!=fireControl$damage.stamp()&&be.getLevel()!=null){
            PhysicsCompat.shapeChanged(be.getLevel(),be.getBlockPos());
            if(packet){be.requestModelDataUpdate();be.getLevel().sendBlockUpdated(be.getBlockPos(),be.getBlockState(),be.getBlockState(),3);}
        }
    }
    @Inject(method={"shape","outline"},at=@At("HEAD"),cancellable=true)
    private void fireControl$cutCollision(CallbackInfoReturnable<VoxelShape> cir){if(fireControl$damage.damaged())cir.setReturnValue(fireControl$damage.shape(((PolyBlockEntity)(Object)this).poly()));}
    @Inject(method="getModelData",at=@At("RETURN"),cancellable=true)
    private void fireControl$cutModel(CallbackInfoReturnable<ModelData> cir){if(fireControl$damage.damaged()){
        var data=cir.getReturnValue();var base=data.get(PolyBlockEntity.GEOMETRY);
        cir.setReturnValue(data.derive().with(PolyArmorAccess.MESH,fireControl$damage.renderMesh(base)).build());}}
    @Inject(method="transform",at=@At("HEAD"))
    private void fireControl$rotateBores(BlockEntity be,StructureTransform transform,CallbackInfo ci){fireControl$damage.transform(transform);}
}
