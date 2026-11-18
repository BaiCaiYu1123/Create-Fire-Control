package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.content.bearing.PreviewBearingSettings;
import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class LiveHeadAimRig {
    public static StabilizerControllerBlockEntity controller;
    public static ServerSubLevel hull;
    public static BlockPos seatPos;
    public static SeatEntity seat;
    public static final List<SwivelBearingBlockEntity> yaws=new ArrayList<>(),pitches=new ArrayList<>();
    private static final List<BindingRef> muzzles=new ArrayList<>();
    private static ServerLevel level;
    public static void create(ServerLevel world) throws Exception {
        level=world;BlockPos base=new BlockPos(256,180,0);
        List<BlockPos> blocks=new ArrayList<>();
        for(int x=0;x<=240;x++)for(int z=0;z<3;z++){var p=base.offset(x,0,z);level.setBlock(p,Blocks.STONE.defaultBlockState(),2);blocks.add(p);}
        level.setBlockAndUpdate(base.above(),ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());blocks.add(base.above());
        level.setBlockAndUpdate(base.offset(0,1,2),BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:white_seat")).defaultBlockState());blocks.add(base.offset(0,1,2));
        hull=SubLevelAssemblyHelper.assembleBlocks(level,base,blocks,BoundingBox3i.from(blocks));
        require(hull!=null,"long shared hull assembles");force(hull);
        BlockPos local=hull.getPlot().getCenterBlock();
        controller=(StabilizerControllerBlockEntity)level.getBlockEntity(local.above());
        require(controller!=null,"physical fire control survives relocation");
        seatPos=local.offset(0,1,2);controller.bind(BindingSlot.SEAT,BindingRef.capture(level,seatPos));
        Vector3d pivot=new Vector3d(local.getX(),local.getY(),local.getZ());
        Vector3d worldPivot=hull.logicalPose().transformPosition(new Vector3d(pivot));
        SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null,hull,
                new FixedConstraintConfiguration(worldPivot,pivot,new Quaterniond(hull.logicalPose().orientation())));
        for(int i=0;i<3;i++) {
            SwivelBearingBlockEntity yaw=assemble(local.offset(60+i*60,1,1),Direction.UP);yaws.add(yaw);
            BlockPos pitchPos=yaw.getPlatePos().above(2);level.setBlockAndUpdate(pitchPos.below(),Blocks.STONE.defaultBlockState());
            SwivelBearingBlockEntity pitch=assemble(pitchPos,Direction.EAST);pitches.add(pitch);
            controller.bind(BindingSlot.YAW,BindingRef.capture(level,yaw.getBlockPos()));
            controller.bind(BindingSlot.PITCH,BindingRef.capture(level,pitch.getBlockPos()));
        }
        ControllerTracker.add(controller);
        set(controller,"stabilizerEnabled",false);
        set(controller,"mobileShotCompensationEnabled",false);
        set(controller,"thirdPersonHeight",0.0);
    }
    public static void mount(net.minecraft.server.level.ServerPlayer player) throws Exception {
        // Sable relocates a newly assembled child's plot asynchronously. Attach
        // references after those plots have registered, as in a built game rig.
        for(var pitch:pitches)for(int barrel=0;barrel<2;barrel++) {
            BindingRef ref=attachCannon(pitch.getPlatePos().offset(2+barrel*2,0,0));
            muzzles.add(ref);controller.bind(BindingSlot.CANNON_MOUNT,ref);
        }
        require(controller.isHeadAimReady(),"three yaw/pitch pairs and six CBC muzzle references resolve normally");
        SeatBlock.sitDown(level,seatPos,player);
        require(player.getVehicle() instanceof SeatEntity,"normal Create seating attaches player");
        seat=(SeatEntity)player.getVehicle();
    }
    private static SwivelBearingBlockEntity assemble(BlockPos pos,Direction facing) {
        level.setBlockAndUpdate(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing")).defaultBlockState().setValue(SwivelBearingBlock.FACING,facing));
        level.setBlockAndUpdate(pos.relative(facing),Blocks.STONE.defaultBlockState());
        var bearing=(SwivelBearingBlockEntity)level.getBlockEntity(pos);bearing.assemble();require(bearing.isAssembled(),"actual axis assembles: "+pos);return bearing;
    }
    private static BindingRef attachCannon(BlockPos pos) throws Exception {
        level.setBlockAndUpdate(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount")).defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH));
        var mount=(CannonMountBlockEntity)level.getBlockEntity(pos);
        var contraption=new MountedAutocannonContraption();
        contraption.anchor=pos.above();
        setDeclared(AbstractMountedCannonContraption.class,contraption,"initialOrientation",Direction.SOUTH);
        setDeclared(AbstractMountedCannonContraption.class,contraption,"startPos",BlockPos.ZERO);
        setDeclared(MountedAutocannonContraption.class,contraption,"cannonMaterial",rbasamoyai.createbigcannons.index.CBCAutocannonMaterials.STEEL);
        for(int z=0;z<5;z++){var p=new BlockPos(0,0,z);contraption.getBlocks().put(p,new StructureBlockInfo(p,Blocks.STONE.defaultBlockState(),null));}
        contraption.bounds=new AABB(0,0,0,1,1,5);
        var cannon=PitchOrientedContraptionEntity.create(level,contraption,Direction.SOUTH,mount);
        cannon.setPos(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
        cannon.xOld=cannon.xo=cannon.getX();cannon.yOld=cannon.yo=cannon.getY();cannon.zOld=cannon.zo=cannon.getZ();
        // CBC supplies its actual mount geometry; native Sable bodies and the controller tick normally.
        mount.attach(cannon);mount.setYaw(Direction.SOUTH.toYRot());mount.setPitch(0);
        return BindingRef.capture(level,pos);
    }
    public static void prepare(boolean stabilized,double height) throws Exception {
        set(controller,"stabilizerEnabled",stabilized);set(controller,"thirdPersonHeight",height);
        for(var yaw:yaws)controller.setPreviewBearingSettings(BindingSlot.YAW,BindingRef.capture(level,yaw.getBlockPos()),new PreviewBearingSettings(90,80,80,false,false,Vec3.ZERO,YawLimits.UNLIMITED));
        for(var pitch:pitches)controller.setPreviewBearingSettings(BindingSlot.PITCH,BindingRef.capture(level,pitch.getBlockPos()),new PreviewBearingSettings(90,80,80,false,false,Vec3.ZERO,YawLimits.UNLIMITED));
    }
    public static long rayAge() throws Exception {return level.getGameTime()-(long)get(controller,"lastAimUpdateGameTime");}
    public static double maxError(Vec3 requested) {
        double worst=0;
        for(BindingRef ref:muzzles){Vec3 actual=CannonMountCompat.getAssembledCannonDirection(level,ref);require(actual!=null,"CBC muzzle remains available: pos="+ref.pos()+" valid="+ref.isSameTarget(level)+" state="+level.getBlockState(ref.pos())+" entity="+level.getBlockEntity(ref.pos()));worst=Math.max(worst,Math.toDegrees(Math.acos(Math.clamp(actual.normalize().dot(requested.normalize()),-1,1))));}
        return worst;
    }
    public static String axisSummary() {
        return yaws.stream().map(b->b.getBlockPos()+"="+b.getTargetAngleDegrees()).toList().toString();
    }
    public static void forceAll(){force(hull);for(var b:yaws)forceChild(b);for(var b:pitches)forceChild(b);}
    private static void forceChild(SwivelBearingBlockEntity b){if(SubLevelContainer.getContainer(level).getSubLevel(b.getSubLevelID()) instanceof ServerSubLevel body)force(body);}
    private static void force(ServerSubLevel body){((ServerSubLevelContainer)SubLevelContainer.getContainer(level)).addForceLoadTicket(body,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);}
    private static Object get(Object target,String name) throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void set(Object target,String name,Object value) throws Exception {setDeclared(target.getClass(),target,name,value);}
    private static void setDeclared(Class<?> owner,Object target,String name,Object value) throws Exception {Field f=owner.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
