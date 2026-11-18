package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import java.lang.reflect.Method;
import java.nio.file.*;

/** Real Rapier joints, commanded through the display's production default/override/neutral paths. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID)
public final class DisplayBearingLimitsIntegration {
    private static ServerLevel level;
    private static ConnectedDisplayBlockEntity display;
    private static final SwivelBearingBlockEntity[] axes=new SwivelBearingBlockEntity[5];
    private static final BindingRef[] refs=new BindingRef[5];
    private static Method command;
    private static int age,waits;
    private static boolean done;
    private static final StringBuilder samples=new StringBuilder("tick,yaw_a,yaw_b,pitch_east,pitch_west,default_pitch\n");

    @SubscribeEvent public static void start(ServerStartedEvent event){
        try{
            level=event.getServer().overworld();
            // The fixture owns the ticking order; this is the real block entity, without a second world ticker.
            display=new ConnectedDisplayBlockEntity(new BlockPos(-8,160,0),ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
            display.setLevel(level);
            display.setSettings(false,2000,180,180,20,45,false,false,600,30,256);
            Direction[] facing={Direction.UP,Direction.UP,Direction.EAST,Direction.WEST,Direction.EAST};
            for(int i=0;i<axes.length;i++){
                BlockPos pos=new BlockPos(i*16,160,0);level.setChunkForced(pos.getX()>>4,0,true);
                var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"));
                level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pos,block.defaultBlockState().setValue(SwivelBearingBlock.FACING,facing[i]));
                level.setBlockAndUpdate(pos.relative(facing[i]),Blocks.STONE.defaultBlockState());
                axes[i]=(SwivelBearingBlockEntity)level.getBlockEntity(pos);axes[i].assemble();
                require(axes[i].isAssembled(),"axis "+i+" assembled");
                refs[i]=BindingRef.capture(level,pos);display.bind(i<2?BindingSlot.YAW:BindingSlot.PITCH,refs[i]);
            }
            display.setPreviewBearingSettings(BindingSlot.YAW,refs[0],settings(20,45,new YawLimits(25,40,YawLimits.SOUTH)));
            display.setPreviewBearingSettings(BindingSlot.YAW,refs[1],settings(20,45,new YawLimits(15,20,YawLimits.WEST)));
            display.setPreviewBearingSettings(BindingSlot.PITCH,refs[2],settings(12,28,YawLimits.UNLIMITED));
            display.setPreviewBearingSettings(BindingSlot.PITCH,refs[3],settings(9,34,YawLimits.UNLIMITED));
            command=ConnectedDisplayBlockEntity.class.getDeclaredMethod("commandBearing",BindingRef.class,boolean.class,Vec3.class,Vec3.class);
            command.setAccessible(true);
        }catch(Throwable failure){finish(failure);}
    }
    private static PreviewBearingSettings settings(double depression,double elevation,YawLimits limits){
        return new PreviewBearingSettings(depression,elevation,180,false,false,Vec3.ZERO,limits);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Pre event){
        if(done||level==null)return;
        try{
            var container=(ServerSubLevelContainer)SubLevelContainer.getContainer(level);
            for(var axis:axes){var child=child(axis);if(child==null){require(++waits<100,"child plots register");return;}
                container.addForceLoadTicket(child,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);}
            age++;
            if(age==161){
                display.setPreviewBearingSettings(BindingSlot.YAW,refs[0],settings(20,45,new YawLimits(8,12,YawLimits.NORTH)));
                display.setPreviewBearingSettings(BindingSlot.PITCH,refs[2],settings(5,10,YawLimits.UNLIMITED));
            }
            ConnectedDisplayBlockEntity.tick(level,display.getBlockPos(),display.getBlockState(),display);
            if(age<=240){
                double[] target=age<=80?new double[]{100,0,70,70,70}:age<=160?new double[]{-100,-180,-70,-70,-70}:new double[]{90,-180,70,-70,-70};
                for(int i=0;i<axes.length;i++){
                    Vector3d actual=child(axes[i]).logicalPose().orientation().transform(new Vector3d(0,0,1));
                    double radians=Math.toRadians(target[i]);
                    Vec3 desired=i<2?new Vec3(Math.sin(radians),0,Math.cos(radians)):new Vec3(0,Math.sin(radians),Math.cos(radians));
                    command.invoke(display,refs[i],i<2,new Vec3(actual.x,actual.y,actual.z),desired);
                }
            }
            if(age%10==0){samples.append(age);for(int i=0;i<axes.length;i++)samples.append(',').append(angle(i));samples.append('\n');}
            if(age==80)near(new double[]{25,-75,28,34,45},1.0,"independent positive stops / default elevation");
            if(age==160)near(new double[]{-40,-110,-12,-9,-20},1.0,"independent negative stops / default depression");
            if(age==240)near(new double[]{168,-110,10,-9,-20},1.0,"live center/envelope edits stay local to selected axes");
            if(age==285){
                near(new double[]{168,-75,0,0,0},1.0,"neutral return obeys yaw stops and pitch envelope");
                for(var axis:axes)require(BearingStabilizer.ownsJoint(axis),"all display axes, including untouched default pitch, use physical stop joints");
                var tag=display.saveWithoutMetadata(level.registryAccess());var copy=new ConnectedDisplayBlockEntity(display.getBlockPos(),display.getBlockState());copy.setLevel(level);copy.loadWithComponents(tag,level.registryAccess());
                require(copy.getPreviewBearingSettings(BindingSlot.YAW,refs[0]).limits().equals(new YawLimits(8,12,YawLimits.NORTH)),"per-device center/stops persist");
                require(copy.getPreviewBearingSettings(BindingSlot.PITCH,refs[2]).elevation()==10&&copy.getPreviewBearingSettings(BindingSlot.PITCH,refs[4]).elevation()==45,"custom/default elevation remain isolated after save/load");
                finish(null);
            }
        }catch(Throwable failure){finish(failure);}
    }
    private static ServerSubLevel child(SwivelBearingBlockEntity axis){return axis==null?null:(ServerSubLevel)SubLevelContainer.getContainer(level).getSubLevel(axis.getSubLevelID());}
    private static double angle(int index){Vector3d d=child(axes[index]).logicalPose().orientation().transform(new Vector3d(0,0,1));return Math.toDegrees(index<2?Math.atan2(d.x,d.z):Math.atan2(d.y,Math.hypot(d.x,d.z)));}
    private static void near(double[] expected,double tolerance,String label){for(int i=0;i<axes.length;i++)require(Math.abs(net.minecraft.util.Mth.wrapDegrees(angle(i)-expected[i]))<tolerance,label+" axis="+i+" actual="+angle(i)+" expected="+expected[i]);}
    private static void require(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    private static void finish(Throwable failure){
        done=true;if(failure!=null)failure.printStackTrace();
        try{Files.writeString(Path.of("display-limits-result.txt"),failure==null?"PASS: real display yaw left/right stops and centers; east/west pitch depression/elevation; untouched default pitch physical limits; isolated edits; neutral return; save/load\n":"FAIL: "+failure);Files.writeString(Path.of("display-limits.csv"),samples);}
        catch(Exception error){throw new RuntimeException(error);}finally{if(level!=null)level.getServer().halt(false);}
    }
}
