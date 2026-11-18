package com.hooya.stabilizedturret.mixin;

import com.caca.copypoly.*;
import com.caca.copypoly.compat.PolyRapierColliders;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.content.armor.*;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

@Mixin(value=PolyRapierColliders.class,remap=false)
public abstract class PolyArmorRapierMixin {
    @Shadow @Final private Map<BlockPos,?> positions;
    @Unique private final Map<BlockPos,ArmorMesh> fireControl$meshes=new HashMap<>();
    @Inject(method="get",at=@At("HEAD"))
    private void fireControl$invalidateBores(BlockGetter world,BlockPos pos,BlockState state,CallbackInfoReturnable<RapierVoxelColliderData> cir){
        if(world.getBlockEntity(pos) instanceof PolyBlockEntity be&&be instanceof PolyArmorAccess armor){
            var mesh=armor.fireControl$armorDamage().mesh(be.poly());var previous=fireControl$meshes.put(pos.immutable(),mesh);
            if(previous!=mesh&&positions.get(pos) instanceof PolyArmorRapierEntryAccessor entry)entry.fireControl$clearGeometry(null);
        }
    }
    @WrapOperation(method="get",at=@At(value="INVOKE",target="Lcom/caca/copypoly/geometry/Polyhedron;collisionBoxes()Ljava/util/List;"))
    private List<Polyhedron.Box> fireControl$nativeBores(Polyhedron base,Operation<List<Polyhedron.Box>> original,@Local(argsOnly=true) BlockGetter world,@Local(argsOnly=true) BlockPos pos){
        if(world.getBlockEntity(pos) instanceof PolyArmorAccess armor&&armor.fireControl$armorDamage().damaged())
            return armor.fireControl$armorDamage().boxes(base).stream().map(b->new Polyhedron.Box(b.minX*16,b.minY*16,b.minZ*16,b.maxX*16,b.maxY*16,b.maxZ*16)).toList();
        return original.call(base);
    }
    @Inject(method="release",at=@At("HEAD"))
    private void fireControl$release(BlockPos pos,CallbackInfo ci){fireControl$meshes.remove(pos);}
}
