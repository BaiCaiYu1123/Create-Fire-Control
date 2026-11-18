package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.config.CBCCfgMunitions.GriefState;
import rbasamoyai.createbigcannons.munitions.*;
import rbasamoyai.createbigcannons.utils.CBCUtils;
import java.util.*;
import static rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile.ImpactResult.KinematicOutcome.*;

@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
public final class PolyArmorRicochetGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event){event.register(PolyArmorRicochetGameTests.class);}
    private static final Polyhedron RAMP=Polyhedron.of(Polyhedron.parse("0,0,0;16,0,0;0,0,16;16,0,16;0,16,16;16,16,16"));
    private static final Vec3 NORMAL=new Vec3(0,1,-1).normalize(),GRAZE=new Vec3(0,1,1.1).normalize();
    private static final class Dart extends StabilizedApfsdsProjectile {
        private ImpactResult.KinematicOutcome outcome;
        private int contacts;
        Dart(net.minecraft.world.level.Level level){super(ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get(),level);setNoGravity(true);setProjectileMass(900);}
        @Override protected boolean onImpact(HitResult hit,ImpactResult result,ProjectileContext context){contacts++;outcome=result.kinematics();return super.onImpact(hit,result,context);}
        Vec3 pending(){return nextVelocity;}
        Vec3 incoming(){return getDeltaMovement().add(getForces(position(),getDeltaMovement()));}
        ImpactResult impact(BlockHitResult hit){return calculateBlockPenetration(new ProjectileContext(this,GriefState.ALL_DAMAGE),level().getBlockState(hit.getBlockPos()),hit);}
    }
    private static PolyBlockEntity ramp(GameTestHelper h,BlockPos pos,int yaw,boolean edited){
        var level=h.getLevel();
        for(int x=-12;x<=12;x++)for(int y=-12;y<=12;y++)for(int z=-12;z<=12;z++)level.setBlockAndUpdate(pos.offset(x,y,z),Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());var be=(PolyBlockEntity)level.getBlockEntity(pos);be.setMaterial(Blocks.ANCIENT_DEBRIS.defaultBlockState());
        be.setPoly(Polyhedron.of(RAMP.points().stream().map(p->{var v=rotate(new Vec3(p.x()/16-.5,p.y()/16-.5,p.z()/16-.5),yaw).add(.5,.5,.5);return new Polyhedron.Point(v.x*16,v.y*16,v.z*16);}).toList()));
        if(edited){var damage=((PolyArmorAccess)be).fireControl$armorDamage();damage.add(ArmorHole.along(new Vec3(.15,0,.85),new Vec3(0,1,0)));damage.install(damage.editable(be.poly()));((PolyArmorAccess)be).fireControl$armorChanged();}
        return be;
    }
    private static Vec3 rotate(Vec3 v,int turns){for(int i=0;i<turns;i++)v=new Vec3(v.z,v.y,-v.x);return v;}
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_ricochet")
    public static void slopeNormalsRemainExactOutsideThePenetrationCallback(GameTestHelper h){
        var pos=new BlockPos(896,260,256);var be=ramp(h,pos,0,false);var point=Vec3.atLowerCornerOf(pos).add(.5,.5,.5);
        var hit=h.getLevel().clip(new ClipContext(point.subtract(GRAZE.scale(.3)),point.add(GRAZE.scale(.3)),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,new Dart(h.getLevel())));
        h.assertTrue(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(pos),"Ray missed the true ramp surface");
        h.assertTrue(hit.getLocation().distanceTo(point)<1e-7,"Undamaged ramp ray hit a staircase contact box: "+hit.getLocation());
        var normal=CBCUtils.getSurfaceNormalVector(h.getLevel(),hit);
        h.assertTrue(normal.distanceTo(NORMAL)<1e-7,"True slope normal was lost outside the impact scope: "+normal);
        h.assertTrue(CBCUtils.getSurfaceNormalVector(h.getLevel(),hit.withPosition(pos).withDirection(Direction.NORTH)).distanceTo(NORMAL)<1e-7,"Hit copies lost the true normal");h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_ricochet")
    public static void realTicksRicochetFromOriginalAndEditedSlopesInFourDirections(GameTestHelper h){
        var config=CBCConfigs.server().munitions;var chance=config.baseProjectileBounceChance.get();boolean enabled=config.projectilesCanBounce.get();config.baseProjectileBounceChance.set(1.0);config.projectilesCanBounce.set(true);
        try{
            for(boolean edited:new boolean[]{false,true})for(int yaw=0;yaw<4;yaw++){
                var pos=new BlockPos(928+yaw*32,260,edited?320:256);var be=ramp(h,pos,yaw,edited);var normal=rotate(NORMAL,yaw);var direction=rotate(GRAZE,yaw);var point=Vec3.atLowerCornerOf(pos).add(.5,.5,.5);
                var shot=new Dart(h.getLevel());shot.setPos(point.subtract(direction.scale(.3)));shot.setDeltaMovement(direction.scale(8));
                var damage=((PolyArmorAccess)be).fireControl$armorDamage();double volume=damage.volume(be.poly());
                var incoming=shot.incoming();
                var expected=incoming.subtract(normal.scale(normal.dot(incoming)*1.7F));
                try{
                    shot.tick();h.assertTrue(shot.outcome==BOUNCE,"Grazing shot did not ricochet; edited="+edited+" yaw="+yaw+" outcome="+shot.outcome+" position="+shot.position());
                    h.assertTrue(shot.pending()!=null&&shot.pending().distanceTo(expected)<1e-6,"Reflection used the block face instead of the true slope; actual="+shot.pending()+" expected="+expected);
                    h.assertTrue(shot.position().subtract(point).dot(normal)>1e-5,
                            "The ricochet flight packet starts on or inside the struck mesh");
                    h.assertTrue(shot.getDeltaMovement().distanceTo(expected)<1e-6,
                            "The impact tick would synchronize incoming movement after a ricochet");
                    h.assertTrue(Math.abs(damage.volume(be.poly())-volume)<1e-8,"Ricochet cut a penetration hole");
                    shot.tick();h.assertTrue(!shot.isInGround()&&shot.getDeltaMovement().dot(normal)>0&&shot.contacts==1,
                            "Ricochet hit the same sloped surface twice instead of flying away");
                    h.assertTrue(h.getLevel().getBlockEntity(pos)==be,"Ricochet removed the armor");
                }finally{shot.discard();}
            }
        }finally{config.baseProjectileBounceChance.set(chance);config.projectilesCanBounce.set(enabled);}h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_ricochet")
    public static void slopeRicochetStillRespectsTheIncidenceLimitAndDisabledSetting(GameTestHelper h){
        var pos=new BlockPos(1120,260,256);var be=ramp(h,pos,0,false);var point=Vec3.atLowerCornerOf(pos).add(.5,.5,.5);
        var config=CBCConfigs.server().munitions;boolean enabled=config.projectilesCanBounce.get();var chance=config.baseProjectileBounceChance.get();
        try{
            config.baseProjectileBounceChance.set(1.0);config.projectilesCanBounce.set(true);
            var headOn=new Dart(h.getLevel());headOn.setProjectileMass(4);headOn.setPos(point.add(NORMAL.scale(.3)));headOn.setDeltaMovement(NORMAL.scale(-8));
            try{headOn.tick();h.assertTrue(headOn.outcome!=BOUNCE,"Normal incidence was forced to ricochet");}finally{headOn.discard();}
            config.projectilesCanBounce.set(false);var disabled=new Dart(h.getLevel());disabled.setPos(point.subtract(GRAZE.scale(.3)));disabled.setDeltaMovement(GRAZE.scale(8));
            try{disabled.tick();h.assertTrue(disabled.outcome!=BOUNCE,"Disabled ricochets remained enabled for slopes");}finally{disabled.discard();}
            h.assertTrue(!((PolyArmorAccess)be).fireControl$armorDamage().damaged(),"Stopped shots drilled a hole");
        }finally{config.projectilesCanBounce.set(enabled);config.baseProjectileBounceChance.set(chance);}h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_ricochet")
    public static void stockBigMediumAndAutocannonRoundsUseTheSameSlopeReflection(GameTestHelper h){
        var config=CBCConfigs.server().munitions;boolean enabled=config.projectilesCanBounce.get();var chance=config.baseProjectileBounceChance.get();
        try{
            config.projectilesCanBounce.set(true);config.baseProjectileBounceChance.set(1.0);int index=0;
            for(String id:List.of("createbigcannons:ap_shot","cbcmodernwarfare:ap_mediumshell","createbigcannons:ap_autocannon")){
                var pos=new BlockPos(1152+index++*32,260,320);var be=ramp(h,pos,0,false);var point=Vec3.atLowerCornerOf(pos).add(.5,.5,.5);
                var shot=(AbstractCannonProjectile)net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.parse(id)).create(h.getLevel());
                shot.setNoGravity(true);shot.setProjectileMass(900);shot.setPos(point.subtract(GRAZE.scale(.3)));shot.setDeltaMovement(GRAZE.scale(8));
                if(shot instanceof rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile auto)auto.setLifetime(100);
                var incoming=shot.getDeltaMovement().add(((com.hooya.stabilizedturret.mixin.PolyArmorProjectileAccessor)shot).fireControl$forces(shot.position(),shot.getDeltaMovement()));
                var expected=incoming.subtract(NORMAL.scale(NORMAL.dot(incoming)*1.7F));
                try{
                    shot.tick();var saved=new net.minecraft.nbt.CompoundTag();shot.saveWithoutId(saved);var next=saved.getList("NextMotion",net.minecraft.nbt.Tag.TAG_DOUBLE);
                    var reflected=new Vec3(next.getDouble(0),next.getDouble(1),next.getDouble(2));
                    h.assertTrue(next.size()==3&&reflected.distanceTo(expected)<1e-6,"Stock round did not reflect off the real slope: "+id+" actual="+reflected);
                    h.assertTrue(!((PolyArmorAccess)be).fireControl$armorDamage().damaged(),"Ricochet drilled armor: "+id);
                    shot.tick();h.assertTrue(!shot.isInGround()&&!shot.isRemoved()&&shot.getDeltaMovement().dot(NORMAL)>0,"Stock round did not continue after ricochet: "+id);
                }finally{shot.discard();}
            }
        }finally{config.projectilesCanBounce.set(enabled);config.baseProjectileBounceChance.set(chance);}h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_ricochet_sable",timeoutTicks=200)
    public static void rotatedSableSlopeNormalAndBothSolversUseWorldSpace(GameTestHelper h){
        // Assemble in the loaded GameTest chunk; a remote body may unload before the delayed callback.
        var original=h.absolutePos(new BlockPos(2,3,2));var level=h.getLevel();level.setBlockAndUpdate(original,Copypoly.POLY.get().defaultBlockState());
        var source=(PolyBlockEntity)level.getBlockEntity(original);source.setPoly(RAMP);source.setMaterial(Blocks.ANCIENT_DEBRIS.defaultBlockState());level.setBlockAndUpdate(original.below(),Blocks.STONE.defaultBlockState());
        var body=SubLevelAssemblyHelper.assembleBlocks(level,original,List.of(original,original.below()),new BoundingBox3i(original.below(),original));
        h.runAfterDelay(3,()->{
            var config=CBCConfigs.server().munitions;boolean enabled=config.projectilesCanBounce.get();var chance=config.baseProjectileBounceChance.get();
            try{
                config.projectilesCanBounce.set(true);config.baseProjectileBounceChance.set(1.0);
                level.getChunkAt(new BlockPos(1312,260,256));var pipeline=SubLevelPhysicsSystem.require(level).getPipeline();pipeline.teleport(body,new org.joml.Vector3d(1312.5,260.5,256.5),new org.joml.Quaterniond().rotationYXZ(.7,.3,.2));pipeline.resetVelocity(body);pipeline.readPose(body,body.logicalPose());
                var pos=body.getPlot().getCenterBlock();var be=(PolyBlockEntity)level.getBlockEntity(pos);
                // Set the controlled test material on the assembled body; this case tests ricochet frames.
                be.setMaterial(Blocks.ANCIENT_DEBRIS.defaultBlockState());
                var localPoint=Vec3.atLowerCornerOf(pos).add(.5,.5,.5);var pose=body.logicalPose();
                var shot=new Dart(level);shot.setPos(pose.transformPosition(localPoint.subtract(GRAZE.scale(.3))));shot.setDeltaMovement(pose.transformNormal(GRAZE).scale(8));
                var shape=be.getBlockState().getCollisionShape(level,pos,CollisionContext.of(shot));var hit=shape.clip(localPoint.subtract(GRAZE.scale(.3)),localPoint.add(GRAZE.scale(.3)),pos);
                h.assertTrue(hit!=null,"Sable local ray missed the slope");var expected=pose.transformNormal(NORMAL);
                h.assertTrue(CBCUtils.getSurfaceNormalVector(level,hit).distanceTo(expected)<1e-7,"Sable normal was not transformed exactly once");
                h.assertTrue(shot.impact(hit).kinematics()==BOUNCE,"Rotated Sable slope did not ricochet in the actual solver");
                h.assertTrue(CBCUtils.getSurfaceNormalVector(level,hit).distanceTo(expected)<1e-7,"Sable reflection lost its normal after the solver returned");
                var offset=ArmorRicochet.departure(level,hit).subtract(pose.transformPosition(hit.getLocation()));
                h.assertTrue(offset.distanceTo(expected.scale(1e-4))<1e-7,"Ricochet departure used the wrong Sable coordinate frame: "+offset);
                h.assertTrue(!((PolyArmorAccess)be).fireControl$armorDamage().damaged(),"Sable ricochet drilled armor");shot.discard();h.succeed();
            }finally{config.projectilesCanBounce.set(enabled);config.baseProjectileBounceChance.set(chance);body.markRemoved();SubLevelContainer.getContainer(level).processSubLevelRemovals();}
        });
    }
}
