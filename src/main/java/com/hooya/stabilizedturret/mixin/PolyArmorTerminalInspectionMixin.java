package com.hooya.stabilizedturret.mixin;

import com.cbc_terminal_ballistics.data.MaterialManager;
import com.cbc_terminal_ballistics.data.MaterialStats;
import com.hooya.stabilizedturret.content.armor.PolyArmorProperties;
import com.hooya.stabilizedturret.content.armor.EquivalentArmor;
import com.hooya.stabilizedturret.content.armor.ArmorUpgrade;
import com.hooya.stabilizedturret.content.armor.CompositeArmorTerminalTraits;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Optional CTB inspector reads the same unscaled material traits as its impact solver. */
@Mixin(targets="com.cbc_terminal_ballistics.debug.InspectionSnapshot",remap=false)
public abstract class PolyArmorTerminalInspectionMixin {
    @WrapOperation(method="build",at=@At(value="INVOKE",target="Lcom/cbc_terminal_ballistics/data/MaterialManager;get(Lnet/minecraft/world/level/block/state/BlockState;D)Lcom/cbc_terminal_ballistics/data/MaterialStats;"))
    private static MaterialStats fireControl$fullMaterialTraits(MaterialManager manager,BlockState material,double toughness,Operation<MaterialStats> original,
            ServerLevel level,BlockPos pos,BlockHitResult hit){
        var stats = original.call(manager,material,EquivalentArmor.enabled()?EquivalentArmor.materialToughness(level,material,pos):PolyArmorProperties.unscaledMaterialToughness(level,pos,toughness));
        return CompositeArmorTerminalTraits.scale(stats, ArmorUpgrade.level(level, pos));
    }
}
