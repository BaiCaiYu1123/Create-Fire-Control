package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.lang.reflect.Field;
import java.util.Map;

/** Real production servo: break the input stream midway through a large traverse, then reacquire. */
public final class AimCoastFixture {
    private static SwivelBearingBlockEntity bearing;
    private static ServerLevel level;
    private static Vector3d stopped;
    public static void setup(ServerLevel server) {
        level=server;
        BlockPos pos=new BlockPos(4096,180,0);
        level.setChunkForced(pos.getX()>>4,0,true);
        level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"))
                .defaultBlockState().setValue(SwivelBearingBlock.FACING,Direction.UP));
        level.setBlockAndUpdate(pos.above(),Blocks.STONE.defaultBlockState());
        bearing=(SwivelBearingBlockEntity)level.getBlockEntity(pos);
        bearing.assemble();
        require(bearing.isAssembled(),"coast regression turret assembles");
    }
    public static void tick(int age) throws Exception {
        if(age<12)return;
        var container=(ServerSubLevelContainer)SubLevelContainer.getContainer(level);
        if(!(container.getSubLevel(bearing.getSubLevelID()) instanceof ServerSubLevel body))throw new AssertionError("missing coast turret body");
        container.addForceLoadTicket(body,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);
        BearingStabilizer.maintainBinding(bearing,true,true,true,false,15,20,45,Vec3.ZERO);
        Vector3d actual=body.logicalPose().orientation().transform(new Vector3d(0,0,1));
        if(age<=35||age>=100&&age<=125) {
            double angle=Math.toRadians(age<100?150:-90);
            BearingStabilizer.commandAim(bearing,new Vec3(actual.x,actual.y,actual.z),new Vec3(Math.sin(angle),0,Math.cos(angle)),true,15,20,45,Vec3.ZERO);
        }
        if(age==42) {
            Object lock=locks().get(bearing);
            Vector3d held=(Vector3d)field(lock,"heldAimDirection"),requested=(Vector3d)field(lock,"requestedAimDirection");
            require(held.distance(requested)<1e-8,"lost input discards unfinished traverse instead of retaining the old 150-degree request");
        }
        if(age==55)stopped=new Vector3d(actual);
        if(age==95)require(actual.distance(stopped)<.01,"turret holds position for two seconds after losing input");
        if(age==122) {
            require((long)field(locks().get(bearing),"lastAimCommandTick")==level.getGameTime(),"fresh input reacquires the stopped turret");
            require(actual.distance(stopped)>.1,"reacquired turret moves toward the new command");
        }
    }
    private static Map<?,?> locks() throws Exception {Field f=BearingStabilizer.class.getDeclaredField("ACTIVE");f.setAccessible(true);return (Map<?,?>)f.get(null);}
    private static Object field(Object value,String name) throws Exception {Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS: "+message);}
}
