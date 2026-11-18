package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.network.AimDirectionPayload;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.content.bearing.AimCoastFixture;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AimBindingIntegration {
    private static final BlockPos BASE=new BlockPos(512,128,0);
    private static final BlockPos ROOT=new BlockPos(2048,160,0);
    private static final BlockPos FAR=new BlockPos(3072,160,0);
    private static ServerLevel level;
    private static ServerSubLevel body;
    private static StabilizerControllerBlockEntity controller,root;
    private static ServerPlayer player;
    private static Capture capture;
    private static BlockPos seatPos,bearingPos,displayPos;
    private static int age;
    private static boolean done;

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        try {
            level=event.getServer().overworld();
            List<BlockPos> blocks=new ArrayList<>();
            for(int x=0;x<=256;x++)blocks.add(BASE.offset(x,-1,0));
            for(int z=1;z<=64;z++)blocks.add(BASE.offset(128,-1,z));
            for(int y=0;y<64;y++)blocks.add(BASE.offset(128,y,64));
            for(var pos:blocks)level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
            blocks.addAll(List.of(BASE,BASE.offset(256,0,0),BASE.offset(128,64,64),BASE.offset(2,0,0)));
            level.setBlockAndUpdate(BASE,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            level.setBlockAndUpdate(BASE.offset(256,0,0),BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:white_seat")).defaultBlockState());
            level.setBlockAndUpdate(BASE.offset(128,64,64),BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"))
                    .defaultBlockState().setValue(SwivelBearingBlock.FACING,Direction.UP));
            level.setBlockAndUpdate(BASE.offset(2,0,0),ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
            body=SubLevelAssemblyHelper.assembleBlocks(level,BASE,blocks,BoundingBox3i.from(blocks));
            require(body!=null,"large sparse carrier assembles");
            container().addForceLoadTicket(body,SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);
            BlockPos local=body.getPlot().getCenterBlock();
            controller=(StabilizerControllerBlockEntity)level.getBlockEntity(local);
            seatPos=local.offset(256,0,0);bearingPos=local.offset(128,64,64);displayPos=local.offset(2,0,0);
            require(controller!=null,"controller survives assembly relocation");
            ControllerTracker.add(controller);
            level.setBlockAndUpdate(ROOT,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            root=(StabilizerControllerBlockEntity)level.getBlockEntity(ROOT);
            level.setBlockAndUpdate(FAR.below(),Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(FAR,BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:white_seat")).defaultBlockState());
            var profile=new GameProfile(UUID.randomUUID(),"AimBindCheck");
            player=new ServerPlayer(event.getServer(),level,profile,ClientInformation.createDefault());
            player.setInvulnerable(true);
            capture=new Capture(player,profile);player.connection=capture;
            player.setItemInHand(InteractionHand.MAIN_HAND,AllItems.WRENCH.asStack());
            player.setPos(Sable.HELPER.projectOutOfSubLevel(level,local.getCenter()));
            AimCoastFixture.setup(level);
        } catch(Throwable error){finish(error);}
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(done||level==null)return;
        try {
            age++;
            AimCoastFixture.tick(age);
            if(age==10) {
                checkLargeBinding();
                checkAim();
                checkDisplaySwitch();
                player.setPos(ROOT.getCenter());
                click(ROOT);
                require(lastActive(),"root source starts binding");
                player.setPos(FAR.getCenter());
            }
            if(age==140) {
                require(level.getChunkSource().getChunkNow(ROOT.getX()>>4,ROOT.getZ()>>4)!=null,"single source chunk stays loaded after moving 1024 blocks away");
                level.getChunk(FAR);
                player.tickCount=40;
                capture.pages.clear();
                StabilizerBindingHandler.onPlayerTick(new PlayerTickEvent.Post(player));
                require(hasCandidate(FAR,null,false),"walking refreshes nearby candidate boxes far from source");
                click(FAR);
                require(root.getBinding(BindingSlot.SEAT)!=null&&root.getBinding(BindingSlot.SEAT).pos().equals(FAR),"real wrench click binds a target 1024 blocks from source");
                player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                StabilizerBindingHandler.onPlayerTick(new PlayerTickEvent.Post(player));
                require(!lastActive(),"putting away wrench ends session");
                int count=capture.pages.size();
                for(int i=0;i<10;i++)StabilizerBindingHandler.onPlayerTick(new PlayerTickEvent.Post(player));
                require(capture.pages.size()==count,"inactive binding sends no refreshes");
            }
            if(age==480) {
                require(level.getChunkSource().getChunkNow(ROOT.getX()>>4,ROOT.getZ()>>4)==null,"source chunk unloads after binding ends");
                finish(null);
            }
        } catch(Throwable error){finish(error);}
    }

    private static void checkLargeBinding() throws Exception {
        // > 1,000,000-block bounds; the old volume cutoff skipped every device.
        body.logicalPose().orientation().rotationY(.65).rotateZ(.12);
        capture.pages.clear();click(controller.getBlockPos());
        require(lastActive(),"physical source starts binding");
        require(hasCandidate(seatPos,body.getUniqueId(),false),"seat 256 blocks away remains a candidate on rotated large carrier");
        require(hasCandidate(bearingPos,body.getUniqueId(),false),"block entity on rotated large carrier receives box");
        click(bearingPos);
        require(controller.getYawBearingBindings().size()==1,"real wrench click binds bearing");
        require(hasCandidate(bearingPos,body.getUniqueId(),true),"bound device receives updated red box");
        require(bindingTickets()>0,"physical source owns a temporary loading ticket");
        click(controller.getBlockPos());
        require(!lastActive()&&bindingTickets()==0,"explicit cancellation releases physical source ticket");
        var interrupted=new BindingSourceLease(level,BindingRef.capture(level,controller.getBlockPos()));
        BindingSourceLease.clearInterruptedSessions(new ServerStartedEvent(level.getServer()));
        require(bindingTickets()==0,"interrupted-session cleanup removes only binding leases");
        interrupted.close();interrupted.close();
    }

    private static void checkAim() throws Exception {
        controller.bind(BindingSlot.SEAT,BindingRef.capture(level,seatPos));
        SeatEntity seat=new SeatEntity(level);seat.setPos(seatPos.getX()+.5,seatPos.getY(),seatPos.getZ()+.5);
        Field vehicle=Entity.class.getDeclaredField("vehicle");vehicle.setAccessible(true);vehicle.set(player,seat);
        player.setPos(Sable.HELPER.projectOutOfSubLevel(level,seatPos.getCenter()));
        require(Sable.HELPER.getTrackingOrVehicleSubLevel(player)==body,"real riding association authorizes physical camera frame");
        Vec3 eye=Sable.HELPER.getEyePositionInterpolated(player,1);
        var local=body.logicalPose().transformPositionInverse(new Vector3d(eye.x,eye.y,eye.z));
        var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(proxy,method,args)->{
            if(method.getName().equals("player"))return player;
            if(method.getName().equals("enqueueWork")){((Runnable)args[0]).run();return CompletableFuture.completedFuture(null);}
            throw new UnsupportedOperationException(method.getName());
        });
        for(double radius:new double[]{265.75,1024,4096})for(boolean hit:new boolean[]{true,false}) {
            Vec3 offset=new Vec3(0,20,-radius),origin=eye.add(offset),direction=new Vec3(.3,.05,1).normalize();
            var frame=new AimDirectionPayload.CameraFrame(body.getUniqueId(),new Vec3(local.x,local.y,local.z),new Vec3(0,1,0),offset);
            setControllerField("lastAimUpdateGameTime",Long.MIN_VALUE);
            AimDirectionPayload.handle(packet(origin,direction,hit,frame),context);
            require(controllerField("lastAimUpdateGameTime").equals(level.getGameTime()),"real handler accepts distant camera radius="+radius+" hit="+hit);
            require(((Vec3)controllerField("lastAimRayOrigin")).distanceTo(origin)<1e-8,"accepted ray reaches live controller");
        }
        Vec3 offset=new Vec3(0,20,-266),origin=eye.add(offset);
        Vector3d originalPosition=new Vector3d(body.logicalPose().position());
        Vec3 surfaceAnchor=controller.getBlockPos().getCenter();
        Vec3 oldSurface=body.logicalPose().transformPosition(surfaceAnchor);
        body.logicalPose().position().add(240,0,0);
        for(boolean hit:new boolean[]{true,false}) {
            setControllerField("lastAimUpdateGameTime",Long.MIN_VALUE);
            var skewed=new AimDirectionPayload.CameraFrame(body.getUniqueId(),new Vec3(local.x,local.y,local.z),new Vec3(0,1,0),offset);
            AimDirectionPayload.handle(packet(origin,new Vec3(0,0,1),hit,skewed),context);
            require(controllerField("lastAimUpdateGameTime").equals(level.getGameTime()),"seat-anchored ray survives 240-block render/server pose skew; hit="+hit);
        }
        setControllerField("lastAimUpdateGameTime",Long.MIN_VALUE);
        Vec3 surfaceDirection=oldSurface.subtract(origin).normalize();
        var surfaceFrame=new AimDirectionPayload.CameraFrame(body.getUniqueId(),new Vec3(local.x,local.y,local.z),new Vec3(0,1,0),offset);
        AimDirectionPayload.handle(new AimDirectionPayload(origin.x,origin.y,origin.z,surfaceDirection.x,surfaceDirection.y,surfaceDirection.z,
                oldSurface.x,oldSurface.y,oldSurface.z,true,body.getUniqueId(),surfaceAnchor.x,surfaceAnchor.y,surfaceAnchor.z,
                .5,.5,false,0,0,0,surfaceFrame),context);
        require(controllerField("lastAimUpdateGameTime").equals(level.getGameTime())&&controllerField("movingAimRay")!=null,
                "moving physical hit anchors also survive 240-block pose skew");
        body.logicalPose().position().set(originalPosition);
        setControllerField("lastAimUpdateGameTime",Long.MIN_VALUE);
        var forged=new AimDirectionPayload.CameraFrame(body.getUniqueId(),new Vec3(local.x+1000,local.y,local.z),new Vec3(0,1,0),offset);
        AimDirectionPayload.handle(packet(origin,new Vec3(0,0,1),true,forged),context);
        require(controllerField("lastAimUpdateGameTime").equals(Long.MIN_VALUE),"forged distant physical anchor is rejected by real handler");
        vehicle.set(player,null);
        player.setPos(Sable.HELPER.projectOutOfSubLevel(level,controller.getBlockPos().getCenter()));
    }

    private static void checkDisplaySwitch() throws Exception {
        click(controller.getBlockPos());
        require(bindingTickets()==1,"one active fire-control lease");
        var display=(ConnectedDisplayBlockEntity)level.getBlockEntity(displayPos);
        AutoDefenseDisplayBindingHandler.rightClick(event(displayPos));
        require(bindingTickets()==1,"switching to display releases previous fire-control lease");
        require(hasCandidate(bearingPos,body.getUniqueId(),false),"display uses same complete large-structure candidates");
        click(controller.getBlockPos());
        require(bindingTickets()==1,"switching back releases display lease");
        click(controller.getBlockPos());
        require(bindingTickets()==0,"switch cleanup leaves no binding tickets");
    }

    private static AimDirectionPayload packet(Vec3 origin,Vec3 direction,boolean hit,AimDirectionPayload.CameraFrame frame) {
        Vec3 target=origin.add(direction.scale(1000));
        return new AimDirectionPayload(origin.x,origin.y,origin.z,direction.x,direction.y,direction.z,target.x,target.y,target.z,
                hit,null,0,0,0,.5,.5,false,0,0,0,frame);
    }
    private static PlayerInteractEvent.RightClickBlock event(BlockPos pos) {
        return new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,pos,
                new BlockHitResult(pos.getCenter(),Direction.UP,pos,false));
    }
    private static void click(BlockPos pos){StabilizerBindingHandler.onRightClickBlock(event(pos));}
    private static boolean hasCandidate(BlockPos pos,UUID bodyId,boolean bound) {
        return capture.candidates().contains(new BindingSessionPayload.Candidate(pos,bodyId,bound));
    }
    private static boolean lastActive(){return !capture.pages.isEmpty()&&capture.pages.getLast().active();}
    private static ServerSubLevelContainer container(){return (ServerSubLevelContainer)SubLevelContainer.getContainer(level);}
    private static long bindingTickets() throws Exception {
        Field f=BindingSourceLease.class.getDeclaredField("BODY");f.setAccessible(true);Object type=f.get(null);
        return container().collectForceLoadTickets().values().stream().flatMap(java.util.Collection::stream).filter(t->t.type().equals(type)).count();
    }
    private static Object controllerField(String name) throws Exception {Field f=StabilizerControllerBlockEntity.class.getDeclaredField(name);f.setAccessible(true);return f.get(controller);}
    private static void setControllerField(String name,Object value) throws Exception {Field f=StabilizerControllerBlockEntity.class.getDeclaredField(name);f.setAccessible(true);f.set(controller,value);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);System.out.println("PASS: "+message);}
    private static void finish(Throwable error) {
        done=true;
        if(error!=null)StabilizedTurret.LOGGER.error("AIM/BINDING INTEGRATION FAILED",error);
        try {Files.writeString(Path.of("aim-binding-integration-result.txt"),error==null?
                "PASS: real distant hit/sky handler; rotated million-volume carrier candidates; 1024-block binding; source loading and release; inactive refresh suppression":
                "FAIL: "+error);}
        catch(Exception failure){throw new RuntimeException(failure);}
        finally{if(level!=null)level.getServer().halt(false);}
    }
    private static final class Capture extends ServerGamePacketListenerImpl {
        private final List<BindingSessionPayload> pages=new ArrayList<>();
        Capture(ServerPlayer player,GameProfile profile){super(level.getServer(),new Connection(PacketFlow.SERVERBOUND),player,CommonListenerCookie.createInitial(profile,false));}
        @Override public void send(Packet<?> packet){if(packet instanceof ClientboundCustomPayloadPacket custom&&custom.payload() instanceof BindingSessionPayload binding)pages.add(binding);}
        List<BindingSessionPayload.Candidate> candidates(){List<BindingSessionPayload.Candidate> all=new ArrayList<>();for(var page:pages){if(!page.append())all.clear();all.addAll(page.candidates());}return all;}
    }
}
