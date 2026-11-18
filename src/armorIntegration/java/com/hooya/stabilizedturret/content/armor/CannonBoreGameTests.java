package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

@PrefixGameTestTemplate(false)
public final class CannonBoreGameTests {
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_all_calibers",timeoutTicks=200)
    public static void realCbcRoundsUseFlyingBodyCalibersAndPreserveTheArmor(GameTestHelper h) {
        var cases=new LinkedHashMap<String,Double>();
        cases.put("createbigcannons:ap_shot",10.0);
        cases.put("createbigcannons:he_shell",10.0);
        cases.put("cbcmodernwarfare:ap_mediumshell",6.0);
        cases.put("cbcmodernwarfare:apds_mediumshell",4.0);
        cases.put("cbcmodernwarfare:apfsds_mediumshell",2.0);
        cases.put("cbcmodernwarfare:apds_shot",6.0);
        cases.put("create_fire_control:apfsds_medium_shell",2.0);
        cases.put("createbigcannons:ap_autocannon",2.0);
        cases.put("createbigcannons:machine_gun_bullet",1.0);
        for(var entry:Map.of("cbcmoreshells:apfsds_shot",2.0,"cbcmoreshells:normal_ap_shot",8.0,"cbcaddon:apfsds_autocannon",.75,
                "cbc_at:ha_ap_projectile",4.0,"cbc_at:ha_apds_projectile",2.0,"cbc_at:ha_apdsfs_projectile",1.5).entrySet())
            if(BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(entry.getKey())))cases.put(entry.getKey(),entry.getValue());
        int index=0;
        for(var entry:cases.entrySet()) {
            var id=ResourceLocation.parse(entry.getKey());
            h.assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(id),"Missing caliber fixture "+id);
            var shot=(AbstractCannonProjectile)BuiltInRegistries.ENTITY_TYPE.get(id).create(h.getLevel());
            var pos=new BlockPos(720+index++*3,260,256);
            for(int z=-3;z<=24;z++)h.getLevel().setBlockAndUpdate(pos.offset(0,0,z),Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());
            var be=(PolyBlockEntity)h.getLevel().getBlockEntity(pos);be.setMaterial(Blocks.SANDSTONE.defaultBlockState());
            shot.setPos(Vec3.atLowerCornerOf(pos).add(.5,.5,-2));shot.setDeltaMovement(0,0,12);shot.setNoGravity(true);shot.setProjectileMass(200);
            if(shot instanceof AbstractAutocannonProjectile auto)auto.setLifetime(100);
            try {
                double expected=entry.getValue()/16;
                h.assertTrue(Math.abs(expected-ArmorProjectileBores.width(shot))<1e-7,"Wrong flying body caliber "+id+": "+ArmorProjectileBores.width(shot)*16);
                shot.tick();
                h.assertTrue(h.getLevel().getBlockEntity(pos)==be,"Caliber "+id+" removed the whole armor block");
                var damage=((PolyArmorAccess)be).fireControl$armorDamage();
                h.assertTrue(damage.holes().size()==1,"Caliber "+id+" did not make exactly one hole; pos="+shot.position());
                var hole=damage.holes().getFirst();
                h.assertTrue(Math.abs(hole.width()-expected)<1e-7,"Impact lost the caliber "+id);
                h.assertTrue(damage.mesh(be.poly()).clip(hole.center().subtract(hole.direction().scale(2)),hole.center().add(hole.direction().scale(2)))==null,"Caliber "+id+" left no through hole");
                damage.editable(be.poly()).validate();
                var restored=new PolyBlockEntity(pos,be.getBlockState());restored.loadWithComponents(be.saveWithoutMetadata(h.getLevel().registryAccess()),h.getLevel().registryAccess());
                h.assertTrue(((PolyArmorAccess)restored).fireControl$armorDamage().holes().equals(damage.holes()),"Caliber was lost on save "+id);
            } finally {shot.discard();}
        }
        h.succeed();
    }
}
