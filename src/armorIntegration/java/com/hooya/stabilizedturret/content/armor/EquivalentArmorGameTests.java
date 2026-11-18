package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.config.FireControlSettings;
import com.hooya.stabilizedturret.mixin.PolyArmorProjectileAccessor;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.List;

@PrefixGameTestTemplate(false)
public final class EquivalentArmorGameTests {
    private static void close(GameTestHelper h,double expected,double actual,String message){h.assertTrue(Math.abs(expected-actual)<1e-6,message+": "+expected+" != "+actual);}

    @GameTest(template = "empty", templateNamespace = "copypoly", batch = "equivalent_inspection_layers")
    public static void inspectionAddsTouchingLayersAndStopsAtAirOrTwentyMeters(GameTestHelper h) {
        var level = h.getLevel();
        var front = new BlockPos(1240, 260, 256);
        var air = Blocks.AIR.defaultBlockState();
        var iron = Blocks.IRON_BLOCK.defaultBlockState();
        var stone = Blocks.STONE.defaultBlockState();
        boolean previous = FireControlSettings.equivalentArmorEnabled();
        try {
            FireControlSettings.EQUIVALENT_ARMOR.set(true);
            for (int z = 0; z <= 21; z++) level.setBlockAndUpdate(front.south(z), air);
            level.setBlockAndUpdate(front, iron);
            var panel = front.south();
            level.setBlockAndUpdate(panel, Copypoly.POLY.get().defaultBlockState());
            var armor = (PolyBlockEntity) level.getBlockEntity(panel);
            armor.setMaterial(stone);
            armor.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream()
                    .map(p -> new Polyhedron.Point(p.x(), p.y(), p.z() / 2)).toList()));
            level.setBlockAndUpdate(front.south(2), stone);
            Vec3 entry = Vec3.atLowerCornerOf(front).add(.5, .5, 0);
            var hit = new BlockHitResult(entry, Direction.NORTH, front, false);
            Vec3 eye = entry.add(0, 0, -3);
            var totals = ArmorInspectionRay.scan(level, hit, eye, SubLevel::logicalPose);
            var ironProperties = BlockArmorPropertiesHandler.getProperties(iron);
            var stoneProperties = BlockArmorPropertiesHandler.getProperties(stone);
            h.assertTrue(totals.blocks() == 2 && totals.millimeters() == 1500, "Expected only the two touching layers and 1500 mm");
            close(h, ironProperties.hardness(level, iron, front, true)
                    + .5 * stoneProperties.hardness(level, stone, panel, true), totals.hardness(), "Mixed layer hardness");
            close(h, ironProperties.toughness(level, iron, front, true)
                    + .5 * stoneProperties.toughness(level, stone, panel, true), totals.toughness(), "Mixed layer toughness");

            var tunnel = ArmorHole.along(new Vec3(.5, .5, 0), new Vec3(0, 0, 1), .25);
            ((PolyArmorAccess) armor).fireControl$armorDamage().perforate(armor.poly(), tunnel);
            totals = ArmorInspectionRay.scan(level, hit, eye, SubLevel::logicalPose);
            h.assertTrue(totals.blocks() == 1 && totals.millimeters() == 1000, "A bore did not separate the front and rear layers");

            level.setBlockAndUpdate(panel, air);
            totals = ArmorInspectionRay.scan(level, hit, eye, SubLevel::logicalPose);
            h.assertTrue(totals.blocks() == 1 && totals.millimeters() == 1000, "An isolated rear block was included across an air block");

            for (int z = 0; z < 20; z++) level.setBlockAndUpdate(front.south(z), iron);
            level.setBlockAndUpdate(front.south(20), Blocks.OBSIDIAN.defaultBlockState());
            totals = ArmorInspectionRay.scan(level, hit, eye, SubLevel::logicalPose);
            h.assertTrue(totals.blocks() == 20 && totals.millimeters() == 20000, "Scan exceeded the 20 m depth");
            close(h, 20 * ironProperties.toughness(level, iron, front, true), totals.toughness(),
                    "A block beyond the range contributed toughness");
            h.succeed();
        } finally {
            FireControlSettings.EQUIVALENT_ARMOR.set(previous);
        }
    }

    @GameTest(template="empty",templateNamespace="copypoly",batch="equivalent_sable",timeoutTicks=200)
    public static void rotatedStructureUsesLocalThicknessAndKeepsTheStopAtItsEntrance(GameTestHelper h){
        var level=h.getLevel();var original=h.absolutePos(new BlockPos(2,3,2));
        level.setBlockAndUpdate(original,Copypoly.POLY.get().defaultBlockState());
        var be=(PolyBlockEntity)level.getBlockEntity(original);be.setMaterial(Blocks.IRON_BLOCK.defaultBlockState());
        be.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/4)).toList()));
        level.setBlockAndUpdate(original.below(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(original.east(),Blocks.IRON_BLOCK.defaultBlockState());
        var body=SubLevelAssemblyHelper.assembleBlocks(level,original,List.of(original,original.below(),original.east()),new BoundingBox3i(original.below(),original.east()));
        h.runAfterDelay(3,()->{
            boolean old=FireControlSettings.equivalentArmorEnabled();var shot=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);
            try{
                FireControlSettings.EQUIVALENT_ARMOR.set(true);level.getChunkAt(new BlockPos(1180,260,256));
                var pipeline=SubLevelPhysicsSystem.require(level).getPipeline();pipeline.teleport(body,new Vector3d(1180.5,260.5,256.5),new Quaterniond().rotateY(.7).rotateX(.25));pipeline.resetVelocity(body);pipeline.readPose(body,body.logicalPose());
                var pos=body.getPlot().getCenterBlock();var entry=Vec3.atLowerCornerOf(pos).add(.25,.5,0);var direction=new Vec3(1,0,1).normalize();var pose=body.logicalPose();
                var worldEntry=pose.transformPosition(entry);shot.setNoGravity(true);shot.setPos(worldEntry.subtract(pose.transformNormal(direction).scale(.5)));shot.setDeltaMovement(pose.transformNormal(direction).scale(8));
                var hit=new ArmorHitResult(worldEntry,Direction.NORTH,pos,false,new Vec3(0,0,-1));
                try(var scope=EquivalentArmor.impact(shot,hit,shot.getDeltaMovement())){close(h,Math.sqrt(2)/4,EquivalentArmor.length(level,pos),"Rotated structure impact thickness");}
                try(var scope=EquivalentArmor.inspect(level,pos,shot.position(),worldEntry)){close(h,Math.sqrt(2)/4,EquivalentArmor.length(level,pos),"Rotated structure inspection thickness");}
                var localHit = new BlockHitResult(entry, Direction.NORTH, pos, false);
                var layers = ArmorInspectionRay.scan(level, localHit, shot.position(), SubLevel::logicalPose);
                h.assertTrue(layers.blocks() == 1 && layers.millimeters() == 354,
                        "The gap on a rotated Sable structure should separate its layers: " + layers);
                ((EquivalentArmorStopAccess)shot).fireControl$pinStop(hit);shot.setInGround(true);shot.setDeltaMovement(Vec3.ZERO);shot.setPos(worldEntry.add(.01,0,.01));shot.tick();
                h.assertTrue(shot.position().distanceTo(worldEntry)<1e-7,"World-space stopped shot moved away from the actual entrance");
                shot.setPos(entry.add(.01,0,.01));shot.tick();h.assertTrue(shot.position().distanceTo(entry)<1e-7,"Grounded structure-local shot moved away from the actual entrance");
                ((PolyBlockEntity) level.getBlockEntity(pos)).setPoly(Polyhedron.CUBE);
                var worldEye = worldEntry.subtract(pose.transformNormal(direction).scale(.5));
                layers = ArmorInspectionRay.scan(level, localHit, worldEye, SubLevel::logicalPose);
                h.assertTrue(layers.blocks() == 2 && layers.millimeters() == 1414,
                        "Touching shapes on rotated Sable should add to 1414 mm: " + layers);
                h.succeed();
            }finally{shot.discard();FireControlSettings.EQUIVALENT_ARMOR.set(old);body.markRemoved();SubLevelContainer.getContainer(level).processSubLevelRemovals();}
        });
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="equivalent_properties")
    public static void defaultOffAndAllBlockInspectorValuesUseTheOneMeterReference(GameTestHelper h){
        h.assertTrue(!FireControlSettings.EQUIVALENT_ARMOR.getDefault(),"Global armor feature must default off");
        boolean old=FireControlSettings.equivalentArmorEnabled();var level=h.getLevel();var pos=new BlockPos(1104,260,256);
        try{
            FireControlSettings.EQUIVALENT_ARMOR.set(false);level.setBlockAndUpdate(pos,Blocks.IRON_BLOCK.defaultBlockState());
            var state=level.getBlockState(pos);var provider=BlockArmorPropertiesHandler.getProperties(state);
            double hardness=provider.hardness(level,state,pos,true),toughness=provider.toughness(level,state,pos,true);
            var point=Vec3.atLowerCornerOf(pos);
            try(var scope=EquivalentArmor.inspect(level,pos,point.add(-1,-1,-1),point)){close(h,toughness,provider.toughness(level,state,pos,true),"Disabled mode changed ordinary armor");}
            FireControlSettings.EQUIVALENT_ARMOR.set(true);provider=BlockArmorPropertiesHandler.getProperties(state);
            try(var scope=EquivalentArmor.inspect(level,pos,point.add(-1,-1,-1),point)){
                close(h,Math.sqrt(3),EquivalentArmor.length(level,pos),"Cube diagonal thickness");
                close(h,hardness*Math.sqrt(3),provider.hardness(level,state,pos,true),"Ordinary cube equivalent hardness");
                close(h,toughness*Math.sqrt(3),provider.toughness(level,state,pos,true),"Ordinary cube equivalent toughness");
            }
            close(h,toughness,provider.toughness(level,state,pos,true),"Ray context leaked outside inspector");
            level.setBlockAndUpdate(pos,Blocks.STONE_SLAB.defaultBlockState());state=level.getBlockState(pos);provider=BlockArmorPropertiesHandler.getProperties(state);
            double base=provider.toughness(level,state,pos,true);
            try(var scope=EquivalentArmor.inspect(level,pos,point.add(.5,2,.5),point.add(.5,.5,.5))){close(h,.5,EquivalentArmor.length(level,pos),"Vanilla slab thickness");close(h,.5*base,provider.toughness(level,state,pos,true),"Vanilla slab equivalent resistance");}
            h.succeed();
        }finally{FireControlSettings.EQUIVALENT_ARMOR.set(old);}
    }

    @GameTest(template="empty",templateNamespace="copypoly",batch="equivalent_angles")
    public static void shotResistanceUsesGeometricLengthWithoutDuplicatingCbcAngleCorrection(GameTestHelper h){
        boolean old=FireControlSettings.equivalentArmorEnabled();var level=h.getLevel();var pos=new BlockPos(1120,260,256);
        var shot=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);
        try{
            FireControlSettings.EQUIVALENT_ARMOR.set(true);level.setBlockAndUpdate(pos,Blocks.IRON_BLOCK.defaultBlockState());var state=level.getBlockState(pos);
            var provider=BlockArmorPropertiesHandler.getProperties(state);double base=provider.toughness(level,state,pos,true);
            Vec3 direction=new Vec3(1,1,1).normalize(),point=Vec3.atLowerCornerOf(pos);shot.setPos(point.subtract(direction));shot.setNoGravity(true);shot.setDeltaMovement(direction.scale(20));
            var actual=shot.getDeltaMovement().add(((PolyArmorProjectileAccessor)shot).fireControl$forces(shot.position(),shot.getDeltaMovement())).normalize();
            try(var scope=PolyArmorImpacts.begin(shot,state,new BlockHitResult(point,Direction.NORTH,pos,false))){
                double expected=base*EquivalentArmor.length(level,pos)*Math.abs(actual.z);
                close(h,expected,provider.toughness(level,state,pos,true),"Angle was counted more than once");
            }
            close(h,base,provider.toughness(level,state,pos,true),"Shot context leaked to another query");h.succeed();
        }finally{shot.discard();FireControlSettings.EQUIVALENT_ARMOR.set(old);}
    }

    @GameTest(template="empty",templateNamespace="copypoly",batch="equivalent_stop")
    public static void stoppedShotStaysAtTheThinArmorEntranceAcrossFollowingTicks(GameTestHelper h){
        boolean old=FireControlSettings.equivalentArmorEnabled();var level=h.getLevel();
        try{
            FireControlSettings.EQUIVALENT_ARMOR.set(true);
            for(int part=0;part<2;part++){
                var pos=new BlockPos(1152+part*4,260,256);var point=Vec3.atLowerCornerOf(pos).add(.5,.5,0);
                if(part==0){
                    level.setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());var be=(PolyBlockEntity)level.getBlockEntity(pos);be.setMaterial(Blocks.SANDSTONE.defaultBlockState());
                    be.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/4)).toList()));
                }else level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
                var shot=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);shot.setPos(point.add(0,0,-2));shot.setDeltaMovement(0,0,8);shot.setNoGravity(true);shot.setProjectileMass(.00001F);
                try{
                    for(int tick=0;tick<3;tick++){shot.tick();h.assertTrue(!shot.isRemoved(),"Stopped fixture shattered");h.assertTrue(shot.position().distanceTo(point)<.002,"Stopped projectile moved to the exit or inside the armor: "+shot.position());}
                    if(part==0){
                        h.assertTrue(!((PolyArmorAccess)level.getBlockEntity(pos)).fireControl$armorDamage().damaged(),"Stopped dart drilled armor");
                        ((PolyBlockEntity)level.getBlockEntity(pos)).setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y()/4,p.z()/4)).toList()));
                        shot.tick();h.assertTrue(!shot.isInGround(),"Reshaping away the contact left the projectile pinned in midair");
                    }
                }finally{shot.discard();}
            }
            h.succeed();
        }finally{FireControlSettings.EQUIVALENT_ARMOR.set(old);}
    }

    @GameTest(template="empty",templateNamespace="copypoly",batch="equivalent_terminal_inspect")
    public static void advancedInspectorUsesViewThicknessButKeepsFullMaterialTraits(GameTestHelper h){
        if(!ModList.get().isLoaded("cbc_terminal_ballistics")){h.succeed();return;}
        boolean old=FireControlSettings.equivalentArmorEnabled();var level=h.getLevel();var pos=new BlockPos(1136,260,256);
        try{
            FireControlSettings.EQUIVALENT_ARMOR.set(true);level.setBlockAndUpdate(pos,Blocks.IRON_BLOCK.defaultBlockState());
            var straight=com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level,pos,null);var point=Vec3.atLowerCornerOf(pos);
            try(var scope=EquivalentArmor.inspect(level,pos,point.add(-1,-1,-1),point)){
                var diagonal=com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level,pos,new BlockHitResult(point,Direction.NORTH,pos,false));
                close(h,straight.armorToughness()*Math.sqrt(3),diagonal.armorToughness(),"Advanced inspector thickness");
                close(h,straight.armorHardness()*Math.sqrt(3),diagonal.armorHardness(),"Advanced inspector hardness");
                close(h,straight.ductility(),diagonal.ductility(),"Ductility changed with viewing angle");
                close(h,straight.brittleness(),diagonal.brittleness(),"Brittleness changed with viewing angle");
                close(h,straight.spallMultiplier(),diagonal.spallMultiplier(),"Material spall multiplier changed");
            }
            h.succeed();
        }finally{FireControlSettings.EQUIVALENT_ARMOR.set(old);}
    }
}
