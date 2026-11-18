package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.*;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.mixin.SwivelBearingBlockEntityAccessor;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.*;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Isolated real server, shared physical hull, three independent turrets, three loaded CBC guns each. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID)
public final class DisplayTurretsIntegration {
    private static ServerLevel level;
    private static ServerSubLevel hull;
    private static PhysicsConstraintHandle hullSupport;
    private static Vector3d hullPivot;
    private static ConnectedDisplayBlockEntity display;
    private static com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity radar;
    private static final List<SwivelBearingBlockEntity> yaws=new ArrayList<>(), pitches=new ArrayList<>();
    private static final List<BindingRef> guns=new ArrayList<>();
    private static final List<AbstractAutocannonBreechBlockEntity> breeches=new ArrayList<>();
    private static final List<Entity> projectiles=new ArrayList<>();
    private static final double[] idle=new double[6];
    private static int age;
    private static boolean done;
    private static final StringBuilder trace=new StringBuilder("tick,gun,error,heading,rounds,projectiles\n");

    @SubscribeEvent public static void shot(EntityJoinLevelEvent event) {
        if(event.getEntity() instanceof AbstractAutocannonProjectile)projectiles.add(event.getEntity());
    }
    @SubscribeEvent public static void start(ServerStartedEvent event) {
        try {
            level=event.getServer().overworld();BlockPos base=new BlockPos(256,180,0);
            List<BlockPos> blocks=new ArrayList<>();
            for(int x=0;x<=180;x++)for(int z=0;z<3;z++){
                BlockPos pos=base.offset(x,0,z);level.setBlock(pos,Blocks.STONE.defaultBlockState(),2);blocks.add(pos);
            }
            hull=SubLevelAssemblyHelper.assembleBlocks(level,base,blocks,BoundingBox3i.from(blocks));
            require(hull!=null,"shared hull assembled");force(hull);
            BlockPos local=hull.getPlot().getCenterBlock();
            Vector3d pivot=new Vector3d(local.getX(),local.getY(),local.getZ());
            hullPivot=hull.logicalPose().transformPosition(new Vector3d(pivot));
            hullSupport=SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null,hull,
                    new FixedConstraintConfiguration(hullPivot,pivot,new Quaterniond(hull.logicalPose().orientation())));
            level.setBlockAndUpdate(local.above(),ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
            display=(ConnectedDisplayBlockEntity)level.getBlockEntity(local.above());
            require(display!=null,"real display block entity placed on hull");
            display.setSettings(false,2000,120,120,80,80,false,false,2000,1,512);
            for(int i=0;i<3;i++) {
                var yaw=assemble(local.offset(40+i*60,1,1),Direction.UP);yaws.add(yaw);
                BlockPos pitchPos=yaw.getPlatePos().above(3);level.setBlockAndUpdate(pitchPos.below(),Blocks.STONE.defaultBlockState());
                pitches.add(assemble(pitchPos,i==1?Direction.WEST:Direction.EAST));
            }
        } catch(Throwable failure){finish(failure);}
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) {
        if(done||level==null)return;
        try {
            force(hull);for(var b:yaws)force(child(b));for(var b:pitches)force(child(b));age++;
            if(age==40) {
                for(int i=0;i<3;i++)for(int barrel=0;barrel<3;barrel++) {
                    // Guns face north as well as south, exposing artificial +Z calibration.
                    var pitch=pitches.get(i);Direction direction=i==1?Direction.SOUTH:Direction.NORTH;
                    guns.add(attachCannon(pitch.getPlatePos().offset(2+barrel*2,0,0),direction));
                }
                for(int i=0;i<3;i++)nativeAngle(yaws.get(i),new double[]{25,-35,55}[i]);
                for(var b:pitches)nativeAngle(b,8);
            }
            if(age==140) {
                BlockPos radarPos=new BlockPos(240,180,-12);level.setChunkForced(radarPos.getX()>>4,radarPos.getZ()>>4,true);
                level.setBlockAndUpdate(radarPos.below(),com.simibubi.create.AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(BlockStateProperties.FACING,Direction.UP));
                level.setBlockAndUpdate(radarPos,com.simibubi.create.AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING,Direction.UP));
                level.setBlockAndUpdate(radarPos.above(),Blocks.STONE.defaultBlockState());
                radar=(com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)level.getBlockEntity(radarPos);
                radar.setSpeed(16);radar.assemble();radar.setSpeed(16);
                require(radar.isRunning()&&radar.getMovedContraption()!=null,"normal rotating radar assembled");
                display.bind(BindingSlot.RADAR_BEARING,BindingRef.capture(level,radarPos));
                for(int i=0;i<3;i++) {
                    idle[i]=joint(yaws.get(i));idle[i+3]=joint(pitches.get(i));
                    require(Math.abs(idle[i])>10,"pre-binding nonzero native yaw: "+idle[i]);
                    display.bind(BindingSlot.YAW,BindingRef.capture(level,yaws.get(i).getBlockPos()));
                    display.bind(BindingSlot.PITCH,BindingRef.capture(level,pitches.get(i).getBlockPos()));
                }
                for(var gun:guns)display.bind(BindingSlot.CANNON_MOUNT,gun);
                for(var breech:breeches)require(breech.getInputBuffer().size()==24,"no rounds consumed before trigger");
            }
            if(age>=140)radar.setSpeed(16); // The real display block ticker owns control/fire ordering.
            if(age==220) {
                System.out.println("Display idle bound yaw="+Arrays.toString(idle)+" actual="+Arrays.toString(new double[]{joint(yaws.get(0)),joint(yaws.get(1)),joint(yaws.get(2)),joint(pitches.get(0)),joint(pitches.get(1)),joint(pitches.get(2))})+" native targets="+yaws.stream().map(SwivelBearingBlockEntity::getTargetAngleDegrees).toList());
                for(int i=0;i<3;i++) {
                    near(joint(yaws.get(i)),idle[i],.5,"binding preserves individual yaw");
                    near(joint(pitches.get(i)),idle[i+3],.5,"binding preserves individual pitch");
                }
                checkGroups();clickMap(new Vec3(450,0,300));
            }
            if(age==370) {
                require(maxError()<1,"all nine barrels converge before firing: "+maxError());
                require(display.requestContextFire(),"normal display fire button accepts target");
            }
            if(age==410) {
                require(projectiles.size()>=9,"nine real CBC projectiles spawned: "+projectiles.size());
                for(int i=0;i<9;i++)require(breeches.get(i).getInputBuffer().size()<24,"gun "+i+" consumed loaded ammunition");
                // Change compass center independently. A north-facing model must stay north.
                display.clearSelectedTarget();
                for(int i=0;i<3;i++)display.setPreviewBearingSettings(BindingSlot.YAW,BindingRef.capture(level,yaws.get(i).getBlockPos()),
                        new PreviewBearingSettings(80,80,120,false,false,Vec3.ZERO,new YawLimits(20,30,i==1?YawLimits.SOUTH:YawLimits.NORTH)));
                clickMap(new Vec3(450,0,-700));
            }
            if(age==590) {
                for(int i=0;i<3;i++) {
                    Vec3 ray=CannonMountCompat.getAssembledCannonDirection(level,guns.get(i*3));
                    Vector3d parent=hull.logicalPose().orientation().transformInverse(new Vector3d(ray.x,ray.y,ray.z));
                    double heading=Math.toDegrees(Math.atan2(parent.x,parent.z));
                    require(i==1?Math.abs(heading)<31:Math.abs(heading)>149,"compass limits use real barrel heading: turret="+i+" heading="+heading);
                }
                hullSupport.remove();
                BlockPos hp=hull.getPlot().getCenterBlock();
                hullSupport=SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null,hull,
                        new FixedConstraintConfiguration(new Vector3d(hullPivot),new Vector3d(hp.getX(),hp.getY(),hp.getZ()),new Quaterniond().rotationY(Math.toRadians(60))));
            }
            if(age==720) {
                Vector3d hullRay=hull.logicalPose().orientation().transform(new Vector3d(0,0,1));
                require(Math.abs(Math.toDegrees(Math.atan2(hullRay.x,hullRay.z))-60)<1,"parent hull actually rotated 60 degrees");
                for(int i=0;i<3;i++) {
                    Vec3 ray=CannonMountCompat.getAssembledCannonDirection(level,guns.get(i*3));
                    Vector3d localRay=hull.logicalPose().orientation().transformInverse(new Vector3d(ray.x,ray.y,ray.z));
                    var limits=display.getPreviewBearingSettings(BindingSlot.YAW,BindingRef.capture(level,yaws.get(i).getBlockPos())).limits();
                    double a=limits.angleDegrees(localRay,new Quaterniond());
                    require(a>=-30.5&&a<=20.5,"stops follow immediate parent hull: turret="+i+" angle="+a);
                }
                display.clearSelectedTarget();
                for(int i=0;i<3;i++)display.setPreviewBearingSettings(BindingSlot.YAW,BindingRef.capture(level,yaws.get(i).getBlockPos()),
                        new PreviewBearingSettings(80,80,120,false,false,Vec3.ZERO,YawLimits.UNLIMITED));
            }
            if(age==900) {
                for(int i=0;i<3;i++){
                    near(joint(yaws.get(i)),idle[i],.5,"idle returns to bound yaw");
                    near(joint(pitches.get(i)),idle[i+3],.5,"idle returns to bound pitch");
                }
                var tag=display.saveWithoutMetadata(level.registryAccess());
                require(tag.getList("BearingNeutralAngles",10).size()==6,"all six parent-local defaults persisted");
                var copy=new ConnectedDisplayBlockEntity(display.getBlockPos(),display.getBlockState());copy.setLevel(level);copy.loadWithComponents(tag,level.registryAccess());
                require(copy.saveWithoutMetadata(level.registryAccess()).getList("BearingNeutralAngles",10).equals(tag.getList("BearingNeutralAngles",10)),"defaults survive save/load");
                finish(null);
            }
            if(age>=140&&age%10==0)for(int i=0;i<guns.size();i++) {
                Vec3 ray=CannonMountCompat.getAssembledCannonDirection(level,guns.get(i));
                trace.append(age).append(',').append(i).append(',').append(error(guns.get(i))).append(',')
                        .append(Math.toDegrees(Math.atan2(ray.x,ray.z))).append(',').append(breeches.get(i).getInputBuffer().size()).append(',').append(projectiles.size()).append('\n');
            }
        } catch(Throwable failure){finish(failure);}
    }
    private static void checkGroups() throws Exception {
        var m=ConnectedDisplayBlockEntity.class.getDeclaredMethod("turretGroups");m.setAccessible(true);
        List<?> groups=(List<?>)m.invoke(display);require(groups.size()==3,"three independent turret groups: "+groups);
        for(Object g:groups) {
            var getter=g.getClass().getDeclaredMethod("cannonMounts");getter.setAccessible(true);
            require(((List<?>)getter.invoke(g)).size()==3,"each turret owns exactly three guns");
        }
    }
    private static void clickMap(Vec3 target) {
        Vec3 origin=Vec3.atCenterOf(radar.getBlockPos());
        var point=ConnectedDisplayLayout.radarPoint(target,origin,display.getRadarRange(),ConnectedDisplayLayout.radarGeometry(3,2));
        require(display.handleScreenInteraction(new ConnectedDisplayLayout.ScreenHit(display,display.getBlockPos(),3,2,point.x(),point.y())),"normal map click handled");
        require(display.getManualAimPoint()!=null,"manual target selected with connected radar");
    }
    private static double error(BindingRef gun) {
        if(display.getManualAimPoint()==null)return 0;
        var profile=CannonBallisticsCompat.analyze(level,gun);
        Vec3 requested=CannonBallisticsCompat.solveLaunchDirection(profile,display.getManualAimPoint());
        Vec3 ray=CannonMountCompat.getAssembledCannonDirection(level,gun);
        if(requested==null&&age==370)System.out.println("No fixture launch solution for "+gun.pos()+" profile="+profile+" target="+display.getManualAimPoint());
        return requested==null||ray==null?180:Math.toDegrees(Math.acos(Math.clamp(ray.normalize().dot(requested.normalize()),-1,1)));
    }
    private static double maxError(){return guns.stream().mapToDouble(DisplayTurretsIntegration::error).max().orElse(180);}
    private static SwivelBearingBlockEntity assemble(BlockPos pos,Direction facing) {
        level.setBlockAndUpdate(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing")).defaultBlockState().setValue(SwivelBearingBlock.FACING,facing));
        BlockPos head=pos.relative(facing);
        // Physical turret decks/carriages carry the three-gun load. The CBC
        // entities supply ballistics; they do not add stone ballast to Sable's body inertia.
        if(facing==Direction.UP)for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.setBlockAndUpdate(head.offset(x,0,z),Blocks.STONE.defaultBlockState());
        else for(int d=0;d<5;d++)for(int z=-3;z<=3;z++)level.setBlockAndUpdate(head.relative(facing,d).offset(0,0,z),Blocks.STONE.defaultBlockState());
        var b=(SwivelBearingBlockEntity)level.getBlockEntity(pos);b.assemble();require(b.isAssembled(),"axis assembles");return b;
    }
    private static BindingRef attachCannon(BlockPos pos,Direction direction) throws Exception {
        level.setBlockAndUpdate(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount")).defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING,direction));
        require(level.getBlockEntity(pos) instanceof CannonMountBlockEntity,"mount placed at "+pos+" state="+level.getBlockState(pos));
        var mount=(CannonMountBlockEntity)level.getBlockEntity(pos);var c=new MountedAutocannonContraption();c.anchor=pos.above();
        set(AbstractMountedCannonContraption.class,c,"initialOrientation",direction);set(AbstractMountedCannonContraption.class,c,"startPos",BlockPos.ZERO);
        set(MountedAutocannonContraption.class,c,"cannonMaterial",rbasamoyai.createbigcannons.index.CBCAutocannonMaterials.STEEL);
        for(int z=0;z<=3;z++){
            var local=BlockPos.ZERO.relative(direction,z);
            var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:steel_autocannon_"+(z==0?"breech":"barrel")));
            var state=block.defaultBlockState().setValue(BlockStateProperties.FACING,direction);
            var be=((EntityBlock)block).newBlockEntity(local,state);be.setLevel(level);
            c.presentBlockEntities.put(local,be);c.getBlocks().put(local,new StructureBlockInfo(local,state,null));
            if(z==0){var breech=(AbstractAutocannonBreechBlockEntity)be;breech.setFireRate(0);for(int i=0;i<24;i++)breech.getInputBuffer().add(rbasamoyai.createbigcannons.index.CBCItems.AP_AUTOCANNON_ROUND.get().getCreativeTabCartridgeItem());breeches.add(breech);}
        }
        c.bounds=new AABB(-1,-1,-4,2,2,5);
        var entity=PitchOrientedContraptionEntity.create(level,c,direction,mount);
        entity.setPos(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
        entity.xOld=entity.xo=entity.getX();entity.yOld=entity.yo=entity.getY();entity.zOld=entity.zo=entity.getZ();
        mount.attach(entity);mount.setYaw(direction.toYRot());mount.setPitch(0);
        level.addFreshEntity(entity);return BindingRef.capture(level,pos);
    }
    private static void nativeAngle(SwivelBearingBlockEntity b,double degrees){((SwivelBearingBlockEntityAccessor)b).stabilizedTurret$setTargetAngleDegrees(degrees);b.updateServoCoefficients();}
    private static double joint(SwivelBearingBlockEntity b) throws Exception {
        var m=BearingStabilizer.class.getDeclaredMethod("physicalJointAngle",SwivelBearingBlockEntity.class);m.setAccessible(true);return (double)m.invoke(null,b);
    }
    private static ServerSubLevel child(SwivelBearingBlockEntity b){return (ServerSubLevel)SubLevelContainer.getContainer(level).getSubLevel(b.getSubLevelID());}
    private static void force(ServerSubLevel b){if(b!=null)((ServerSubLevelContainer)SubLevelContainer.getContainer(level)).addForceLoadTicket(b,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);}
    private static void set(Class<?> c,Object o,String name,Object value) throws Exception {Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void near(double actual,double expected,double tolerance,String message){require(Math.abs(net.minecraft.util.Mth.wrapDegrees(actual-expected))<tolerance,message+": actual="+actual+" expected="+expected);}
    private static void require(boolean v,String message){if(!v)throw new AssertionError(message);}
    private static void finish(Throwable failure) {
        done=true;if(failure!=null)failure.printStackTrace();
        try {Files.writeString(Path.of("display-turrets-result.txt"),failure==null?"PASS: three physical turrets, nine real loaded CBC cannons; binding preserves nonzero parent-local poses; independent groups; normal rotating radar and map aim; all nine barrels aligned; all nine guns consumed ammunition and spawned "+projectiles.size()+" projectiles; actual north/south limits; rotated parent; idle defaults and save/load\n":"FAIL: age="+age+" "+failure);
            Files.writeString(Path.of("display-turrets-trace.csv"),trace);
        }catch(Exception e){throw new RuntimeException(e);}finally{if(level!=null)level.getServer().halt(false);}
    }
}
