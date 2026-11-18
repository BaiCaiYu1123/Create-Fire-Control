package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.*;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.config.CBCCfgMunitions.GriefState;
import rbasamoyai.createbigcannons.munitions.*;
import java.util.*;
import static rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile.ImpactResult.KinematicOutcome.*;

@PrefixGameTestTemplate(false)
public final class ArmorSeamGameTests {
    private static final class Dart extends StabilizedApfsdsProjectile {
        Dart(net.minecraft.world.level.Level level){super(ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get(),level);}
        ImpactResult impact(BlockPos pos,Vec3 point){return calculateBlockPenetration(new ProjectileContext(this,GriefState.ALL_DAMAGE),level().getBlockState(pos),new BlockHitResult(point,Direction.NORTH,pos,false));}
    }
    private static List<BlockPos> panel(GameTestHelper h,BlockPos root){
        var blocks=new ArrayList<BlockPos>();
        for(int x=-1;x<=0;x++)for(int y=-1;y<=0;y++){
            var pos=root.offset(x,y,0);h.getLevel().setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());
            ((PolyBlockEntity)h.getLevel().getBlockEntity(pos)).setMaterial(Blocks.IRON_BLOCK.defaultBlockState());blocks.add(pos);
        }
        return blocks;
    }
    private static Dart dart(GameTestHelper h,Vec3 point,Vec3 direction){
        var shot=new Dart(h.getLevel());shot.setPos(point.subtract(direction.normalize().scale(.5)));shot.setDeltaMovement(direction.normalize().scale(20));shot.setNoGravity(true);shot.setProjectileMass(200);return shot;
    }
    private static double verify(GameTestHelper h,List<BlockPos> blocks,double width){
        double removed=0;
        for(var pos:blocks){
            h.assertTrue(h.getLevel().getBlockEntity(pos) instanceof PolyBlockEntity,"Seam hit removed armor "+pos);
            var be=(PolyBlockEntity)h.getLevel().getBlockEntity(pos);var damage=((PolyArmorAccess)be).fireControl$armorDamage();
            h.assertTrue(damage.holes().size()==1,"Seam portion missing/duplicated at "+pos+": "+damage.holes().size());
            h.assertTrue(Math.abs(damage.holes().getFirst().width()-width)<1e-8,"Caliber changed across the seam");
            h.assertTrue(damage.volume(be.poly())<1,"Neighbor received a mask without a real cut");
            damage.editable(be.poly()).validate();removed+=1-damage.volume(be.poly());
            var saved=new PolyBlockEntity(pos,be.getBlockState());saved.loadWithComponents(be.saveWithoutMetadata(h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(((PolyArmorAccess)saved).fireControl$armorDamage().holes().equals(damage.holes()),"Saved seam part lost its axis/caliber");
        }
        return removed;
    }

    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_seam_corner")
    public static void onePenetrationMakesAFullFourBlockHoleButDoesNotDrillTheRearLayer(GameTestHelper h){
        var root=new BlockPos(824,260,256);var front=panel(h,root);var rear=panel(h,root.south());var point=Vec3.atLowerCornerOf(root);
        var shot=dart(h,point,new Vec3(0,0,1));
        try {
            h.assertTrue(shot.impact(root,point).kinematics()==PENETRATE,"Corner fixture did not penetrate");
            h.assertTrue(Math.abs(verify(h,front,2.0/16)-4.0/256)<1e-8,"Four quarters did not form one 2x2 hole");
            for(var pos:rear)h.assertTrue(!((PolyArmorAccess)h.getLevel().getBlockEntity(pos)).fireControl$armorDamage().damaged(),"Footprint bypassed penetration of a rear layer");
            for(var pos:front)h.assertTrue(ArmorProjectilePassage.ignores(shot,pos),"Adjacent contact was not included in this shot's passage");
            h.assertTrue(!PolyArmorImpacts.active(),"Seam hit leaked its impact scope");h.succeed();
        }finally{shot.discard();}
    }

    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_seam_flight")
    public static void actualFlightAcrossTheIntersectionWorksForTwoAndTenPixelRounds(GameTestHelper h){
        int i=0;
        for(var id:List.of("create_fire_control:apfsds_medium_shell","createbigcannons:ap_shot")){
            var root=new BlockPos(840+i++*8,260,256);var blocks=panel(h,root);var point=Vec3.atLowerCornerOf(root);
            var shot=(AbstractCannonProjectile)BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id)).create(h.getLevel());
            shot.setPos(point.add(0,0,-2));shot.setDeltaMovement(0,0,3);shot.setNoGravity(true);shot.setProjectileMass(200);
            try{double width=ArmorProjectileBores.width(shot);shot.tick();verify(h,blocks,width);}finally{shot.discard();}
        }
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_seam_oblique")
    public static void obliqueCornerUsesOneContinuousAxisAndStoppedHitsLeaveNoNeighborHoles(GameTestHelper h){
        var root=new BlockPos(872,260,256);var blocks=panel(h,root);var point=Vec3.atLowerCornerOf(root);var direction=new Vec3(.35,.2,1).normalize();
        var shot=dart(h,point,direction);
        try{
            h.assertTrue(shot.impact(root,point).kinematics()==PENETRATE,"Oblique corner did not penetrate");
            double removed=verify(h,blocks,2.0/16);var hole=((PolyArmorAccess)h.getLevel().getBlockEntity(root)).fireControl$armorDamage().holes().getFirst();
            h.assertTrue(Math.abs(removed-hole.width()*hole.width()/Math.abs(hole.direction().z))<1e-8,"Oblique parts do not have the full combined cross section");
            var origin=hole.center().add(Vec3.atLowerCornerOf(root));
            for(var pos:blocks){var part=((PolyArmorAccess)h.getLevel().getBlockEntity(pos)).fireControl$armorDamage().holes().getFirst();
                h.assertTrue(part.center().add(Vec3.atLowerCornerOf(pos)).subtract(origin).cross(hole.direction()).length()<1e-7,"Neighbor's oblique hole was recentered sideways");}
        }finally{shot.discard();}
        var stoppedRoot=root.east(8);var untouched=panel(h,stoppedRoot);var stoppedPoint=Vec3.atLowerCornerOf(stoppedRoot);var stopped=dart(h,stoppedPoint,new Vec3(0,0,1));stopped.setProjectileMass(.00001F);stopped.setDeltaMovement(0,0,.01);
        try{
            h.assertTrue(stopped.impact(stoppedRoot,stoppedPoint).kinematics()!=PENETRATE,"Stopped fixture unexpectedly penetrated");
            for(var pos:untouched)h.assertTrue(!((PolyArmorAccess)h.getLevel().getBlockEntity(pos)).fireControl$armorDamage().damaged(),"Stopped shot drilled a neighboring block");
        }finally{stopped.discard();}
        h.succeed();
    }

    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_seam_sable",timeoutTicks=200)
    public static void rotatedSableFourBlockIntersectionKeepsAllBoresInTheSameLocalFrame(GameTestHelper h){
        var root=h.absolutePos(new BlockPos(2,3,2));var blocks=panel(h,root);var level=h.getLevel();
        var body=SubLevelAssemblyHelper.assembleBlocks(level,root,blocks,new BoundingBox3i(root.offset(-1,-1,0),root));
        h.runAfterDelay(3,()->{
            var pipeline=SubLevelPhysicsSystem.require(level).getPipeline();level.getChunkAt(new BlockPos(940,260,256));
            pipeline.teleport(body,new Vector3d(940.5,260.5,256.5),new Quaterniond().rotateY(.7).rotateX(.25));pipeline.resetVelocity(body);pipeline.readPose(body,body.logicalPose());
            var pos=body.getPlot().getCenterBlock();var localPoint=Vec3.atLowerCornerOf(pos);var pose=body.logicalPose();
            var worldPoint=pose.transformPosition(localPoint);var shot=dart(h,worldPoint,pose.transformNormal(new Vec3(0,0,1)));
            try {
                h.assertTrue(shot.impact(pos,worldPoint).kinematics()==PENETRATE,"Rotated seam did not penetrate");
                var localBlocks=List.of(pos,pos.west(),pos.below(),pos.west().below());
                h.assertTrue(Math.abs(verify(h,localBlocks,2.0/16)-4.0/256)<1e-7,"Rotated seam does not form one full local hole");h.succeed();
            }finally{shot.discard();body.markRemoved();SubLevelContainer.getContainer(level).processSubLevelRemovals();}
        });
    }
}
