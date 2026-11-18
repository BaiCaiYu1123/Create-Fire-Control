package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.PolyBlock;
import com.caca.copypoly.PolyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import rbasamoyai.createbigcannons.block_armor_properties.*;
import java.util.List;

/** CBC values, including datapack overrides; never substitutes vanilla mining hardness. */
public final class PolyArmorProperties implements BlockArmorPropertiesProvider {
    public static final PolyArmorProperties INSTANCE=new PolyArmorProperties();
    /** Material traits use the full copied block, even when this armor is thin or perforated. */
    public static double unscaledMaterialToughness(Level level,BlockPos pos,double fallback){
        try(var ignored=EquivalentArmor.baseMaterial()){
        if(level!=null&&level.getBlockEntity(pos) instanceof PolyBlockEntity be){
            var material=be.getMaterial();
            if(!(material.getBlock() instanceof PolyBlock))
                return BlockArmorPropertiesHandler.getProperties(material).toughness(level,material,pos,true);
        }
        return fallback;
        }
    }
    private BlockState material(Level level,BlockPos pos,BlockState fallback){
        return level!=null&&level.getBlockEntity(pos) instanceof PolyBlockEntity be?be.getMaterial():fallback;
    }
    private double ratio(Level level,BlockPos pos){
        if(EquivalentArmor.enabled())return 1;
        if(level!=null&&level.getBlockEntity(pos) instanceof PolyBlockEntity be)
            return be instanceof PolyArmorAccess armor?armor.fireControl$armorDamage().volume(be.poly()):ArmorMesh.of(be.poly()).volume();
        return 1;
    }
    @Override public double hardness(Level level,BlockState state,BlockPos pos,boolean recurse){
        var material=material(level,pos,state);return ArmorUpgrade.level(level,pos)*ratio(level,pos)*(material.getBlock() instanceof PolyBlock?1:BlockArmorPropertiesHandler.getProperties(material).hardness(level,material,pos,true));
    }
    @Override public double toughness(Level level,BlockState state,BlockPos pos,boolean recurse){
        var material=material(level,pos,state);return ArmorUpgrade.level(level,pos)*ratio(level,pos)*(material.getBlock() instanceof PolyBlock?state.getBlock().getExplosionResistance():BlockArmorPropertiesHandler.getProperties(material).toughness(level,material,pos,true));
    }
    @Override public List<BlockState> containedBlockStates(Level level,BlockState state,BlockPos pos,boolean recurse){return List.of(material(level,pos,state));}
}
