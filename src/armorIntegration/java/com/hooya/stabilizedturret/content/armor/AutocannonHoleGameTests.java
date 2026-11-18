package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.cannon_control.contraption.*;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.munitions.autocannon.*;
import java.lang.reflect.Field;
import java.util.*;

@PrefixGameTestTemplate(false)
public final class AutocannonHoleGameTests {
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_real_autocannon",timeoutTicks=200)
    public static void realCbcBreechAndCartridgeFireUnmodifiedMassRoundsThatMakeHoles(GameTestHelper h) throws Exception {
        boolean old=FireControlSettings.equivalentArmorEnabled();var level=h.getLevel();int index=0;
        var rounds=new ArrayList<>(List.of("createbigcannons:ap_autocannon_round","createbigcannons:flak_autocannon_round","createbigcannons:machine_gun_round"));
        for(var id:List.of("cbcmodernwarfare:apds_autocannon_round","cbcaddon:apfsds_autocannon_round"))if(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id)))rounds.add(id);
        try{
            for(boolean enabled:new boolean[]{false,true})for(var id:rounds){
                FireControlSettings.EQUIVALENT_ARMOR.set(enabled);var root=new BlockPos(1200+index++*8,260,256);level.getChunkAt(root);
                for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++)for(int z=-2;z<=32;z++)level.setBlockAndUpdate(root.offset(x,y,z),Blocks.AIR.defaultBlockState());
                var cannon=new MountedAutocannonContraption();set(cannon,"startPos",BlockPos.ZERO);set(cannon,"initialOrientation",Direction.SOUTH);
                AbstractAutocannonBreechBlockEntity breech=null;
                for(int z=0;z<3;z++){
                    var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cast_iron_autocannon_"+(z==0?"breech":"barrel")));
                    var state=block.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.SOUTH);var local=new BlockPos(0,0,z);
                    BlockEntity be=((EntityBlock)block).newBlockEntity(local,state);be.setLevel(level);cannon.presentBlockEntities.put(local,be);cannon.getBlocks().put(local,new StructureBlockInfo(local,state,null));
                    if(z==0){breech=(AbstractAutocannonBreechBlockEntity)be;set(cannon,"cannonMaterial",block.getClass().getMethod("getAutocannonMaterial").invoke(block));}
                }
                var round=BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));var cartridge=round instanceof AutocannonRoundItem item?item.getCreativeTabCartridgeItem():round.getDefaultInstance();breech.getInputBuffer().add(cartridge);breech.setFireRate(15);
                cannon.bounds=new AABB(0,0,0,1,1,3);var gun=PitchOrientedContraptionEntity.create(level,cannon,Direction.SOUTH,false);gun.setPos(Vec3.atLowerCornerOf(root).add(.5,0,0));level.addFreshEntity(gun);
                try{
                    h.assertTrue(breech.canFire(),"CBC test breech is not ready to fire");
                    h.assertTrue(cannon.presentBlockEntities.get(BlockPos.ZERO)==breech,"CBC replaced the test breech");
                    h.assertTrue(!breech.getInputBuffer().isEmpty()&&breech.getInputBuffer().peek().getItem() instanceof AutocannonAmmoItem,"CBC test cartridge is missing or invalid");
                    var bullets=new ArrayList<AbstractAutocannonProjectile>();
                    java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> launch=e->{if(e.getEntity() instanceof AbstractAutocannonProjectile bullet)bullets.add(bullet);};
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(launch);
                    try{cannon.fireShot(level,gun);}finally{net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(launch);}
                    h.assertTrue(bullets.size()==1,"Actual CBC breech did not launch exactly one projectile for "+id+": "+bullets.size());var bullet=bullets.getFirst();
                    var target=BlockPos.containing(bullet.position().add(bullet.getDeltaMovement().normalize().scale(2)));
                    level.setBlockAndUpdate(target,Copypoly.POLY.get().defaultBlockState());var armor=(PolyBlockEntity)level.getBlockEntity(target);armor.setMaterial(Blocks.GLASS.defaultBlockState());
                    armor.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/16)).toList()));
                    var first=level.clip(new net.minecraft.world.level.ClipContext(bullet.position(),bullet.position().add(bullet.getDeltaMovement()),net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,bullet));
                    h.assertTrue(first.getBlockPos().equals(target),"Real cannon's shot path is obstructed before the test armor: "+first.getBlockPos());
                    try{
                        for(int tick=0;tick<4&&!((PolyArmorAccess)armor).fireControl$armorDamage().damaged()&&!bullet.isRemoved();tick++)bullet.tick();
                        h.assertTrue(((PolyArmorAccess)armor).fireControl$armorDamage().holes().size()==1,"Real gun did not carve a hole for "+id+", equivalent="+enabled+", mass="+bullet.getProjectileMass()+", pos="+bullet.position());
                    }finally{bullet.discard();}
                }finally{gun.discard();}
            }
            h.succeed();
        }finally{FireControlSettings.EQUIVALENT_ARMOR.set(old);}
    }
    private static void set(Object target,String name,Object value)throws Exception{
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(target,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
}
