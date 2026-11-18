package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.network.WirelessControllerActionPayload;
import com.hooya.stabilizedturret.registry.*;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercises live Sable plots after normal block-entity onLoad callbacks have run. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class SableRemoteMappingIntegration {
    private static MinecraftServer server;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static BlockPos hub, computer, enginePos;
    private static int age;
    private static CompoundTag originalBinding;
    private static BlockPos originalHub;
    private static final String KEY = "key_87";

    static void start(MinecraftServer value) {
        server = value;
        level = server.overworld();
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (server == null) return;
        try {
            age++;
            if (age == 1) {
                var body = SubLevelContainer.getContainer(level).allocateNewSubLevel(new Pose3d());
                hub = body.getPlot().getCenterBlock().atY(240);
                body.logicalPose().rotationPoint().set(hub.getX(), hub.getY(), hub.getZ());
                body.logicalPose().position().set(4, 240, 4);
                computer = hub.east(2);
                enginePos = hub.west(2);
                for (var chunk : new HashSet<>(List.of(new net.minecraft.world.level.ChunkPos(hub),
                        new net.minecraft.world.level.ChunkPos(computer), new net.minecraft.world.level.ChunkPos(enginePos))))
                    body.getPlot().newEmptyChunk(chunk);
                SubLevelContainer.getContainer(level).addForceLoadTicket(
                        (dev.ryanhcode.sable.sublevel.ServerSubLevel)body,
                        dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                        net.minecraft.util.Unit.INSTANCE);
                for (int x=-2;x<=2;x++) level.setBlockAndUpdate(hub.offset(x,-1,0),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                require(level.setBlockAndUpdate(hub, ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState()), "plot terminal placed");
                level.setBlockAndUpdate(computer, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
                level.setBlockAndUpdate(enginePos, ModBlocks.GAS_TURBINE.get().defaultBlockState()
                        .setValue(GasTurbineBlock.PART, GasTurbinePart.NEGATIVE));
                body.forceUpdateGlobalBounds();
                player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "SableMappingCheck"));
                player.setPos(4, 240, 4);
                var stack = ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();
                ControllerSettings.ensureId(stack);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            }
            if (age == 6) {
                var fcc = (StabilizerControllerBlockEntity) level.getBlockEntity(computer);
                var engine = (GasTurbineBlockEntity) level.getBlockEntity(enginePos);
                require(fcc != null && engine != null, "live plot block entities exist");
                require(ControllerTracker.loadedControllers().contains(fcc), "normal Sable onLoad registers FCC");
                fcc.bind(BindingSlot.WIRELESS_CONTROLLER_HUB, BindingRef.capture(level, hub));
                fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS, 14, KEY);
                require(engine.bindAdvancedHub(BindingRef.capture(level, hub)), "engine binds plot terminal");
                require(engine.applySettings(new EngineControlSettings(150, KEY,
                        List.of(new EngineDriveBehavior(List.of("key_65"),256,128,128,128,128,128)))), "engine mapping saves");
                ControllerSessions.use(player, InteractionHand.MAIN_HAND, hub);
                ControllerSessions.use(player, InteractionHand.MAIN_HAND, null);
                var state = ControllerSessions.apiState(player).orElseThrow();
                System.out.println("MAPPING PROBE hub=" + state.terminal() + " actions="
                        + ControllerActionRegistry.channels(level, hub) + " effective=" + state.effective());
                require(hub.equals(state.terminal()), "session resolves plot terminal");
                require(state.effective().containsAll(Set.of(KEY,"key_65")), "FCC and engine inputs are discovered");
                CompoundTag input = new CompoundTag();
                input.putUUID("Session", state.token());
                input.put("Pressed", ControllerSessions.strings(Set.of(KEY)));
                ControllerSessions.handle(player, new WirelessControllerActionPayload(WirelessControllerActionPayload.INPUT, input));
                require(ControllerInput.signal(KeyboardControllerInputs.signals(level,hub),KEY)==16, "plot terminal delivers native input");
            }
            if (age == 7) {
                var fcc = (StabilizerControllerBlockEntity) level.getBlockEntity(computer);
                var engine = (GasTurbineBlockEntity) level.getBlockEntity(enginePos);
                Method remote = StabilizerControllerBlockEntity.class.getDeclaredMethod("tickRemoteInputs");
                remote.setAccessible(true);remote.invoke(fcc);
                Field pressed = StabilizerControllerBlockEntity.class.getDeclaredField("remoteInputPressed");
                pressed.setAccessible(true);
                require(((boolean[])pressed.get(fcc))[14], "FCC receives held input after a real server tick");
                require(engine.isEngineEnabled(), "engine receives ignition after a real server tick");
                System.out.println("PASS: direct Sable plot mapping works for FCC and engine");
                stop();
                hub = new BlockPos(4,240,4);computer=hub.east(2);enginePos=hub.west(2);
                level.setChunkForced(0,0,true);
                for(int x=-2;x<=2;x++)level.setBlockAndUpdate(hub.offset(x,-1,0),
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
                level.setBlockAndUpdate(computer,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
                level.setBlockAndUpdate(enginePos,ModBlocks.GAS_TURBINE.get().defaultBlockState()
                        .setValue(GasTurbineBlock.PART,GasTurbinePart.NEGATIVE));
            }
            if(age==12) {
                var fcc=(StabilizerControllerBlockEntity)level.getBlockEntity(computer);
                var engine=(GasTurbineBlockEntity)level.getBlockEntity(enginePos);
                CompoundTag legacy=BindingRef.capture(level,hub).save();
                legacy.remove(BindingRef.WIRELESS_PROCESSOR_ID_TAG);
                fcc.bind(BindingSlot.WIRELESS_CONTROLLER_HUB,BindingRef.loadTag(legacy));
                fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS,14,KEY);
                require(engine.bindAdvancedHub(BindingRef.loadTag(legacy)),"legacy ground engine binds");
                engine.applySettings(new EngineControlSettings(150,KEY,
                        List.of(new EngineDriveBehavior(List.of("key_65"),256,128,128,128,128,128))));
                ControllerSessions.use(player,InteractionHand.MAIN_HAND,hub);
                ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
                require(ControllerSessions.apiState(player).orElseThrow().effective().contains(KEY),"ground mapping is effective before assembly");
                originalBinding=fcc.getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB).save();originalHub=hub;
                require(originalBinding.hasUUID(BindingRef.WIRELESS_PROCESSOR_ID_TAG)
                        &&engine.getAdvancedHub().save().hasUUID(BindingRef.WIRELESS_PROCESSOR_ID_TAG),"healthy legacy connections acquire persistent identities");
                input(); // Keep the key held across assembly, with no second input edge.
                assemble();
            }
            if(age==18) {
                var fcc=(StabilizerControllerBlockEntity)level.getBlockEntity(computer);
                var engine=(GasTurbineBlockEntity)level.getBlockEntity(enginePos);
                require(fcc!=null&&engine!=null,"assembled FCC and engine remain loaded");
                require(KEY.equals(fcc.getRemoteKeys(RemoteControllerCompat.Type.WIRELESS).get(14)),"FCC key survives assembly");
                require(KEY.equals(engine.getControlSettings().engineToggleKey()),"engine key survives assembly");
                var state=ControllerSessions.apiState(player).orElseThrow();
                var fccBinding=fcc.getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB);
                boolean fccValid=RemoteTerminalDetector.hasTerminal(fcc,level,RemoteControllerCompat.Type.WIRELESS);
                boolean engineValid=engine.hasValidAdvancedHub();
                System.out.println("MAPPING AFTER ASSEMBLY hub="+state.terminal()+" FCC binding="
                        +(fccBinding==null?"null":fccBinding.save())+" engine binding="
                        +(engine.getAdvancedHub()==null?"null":engine.getAdvancedHub().save())+" effective="+state.effective()
                        +" FCC valid="+fccValid+" engine valid="+engineValid);
                require(hub.equals(state.terminal()),"paired handheld follows assembled terminal identity");
                require(fccValid&&engineValid,"FCC and engine terminal connections survive assembly without rebinding");
                require(state.effective().containsAll(Set.of(KEY,"key_65")),"both saved mappings remain effective after assembly");
                require(ControllerInput.signal(KeyboardControllerInputs.signals(level,hub),KEY)==16,"held input follows the relocated terminal through the native path");
                require(KeyboardControllerInputs.signals(level,originalHub).isEmpty(),"old terminal coordinates release signals");
            }
            if(age==19) {
                var fcc=(StabilizerControllerBlockEntity)level.getBlockEntity(computer);
                var engine=(GasTurbineBlockEntity)level.getBlockEntity(enginePos);
                Field pressed=StabilizerControllerBlockEntity.class.getDeclaredField("remoteInputPressed");pressed.setAccessible(true);
                require(((boolean[])pressed.get(fcc))[14],"FCC held input survives assembly after server tick");
                require(engine.isEngineEnabled(),"engine ignition survives assembly after server tick");
                stop();
                System.out.println("PASS: legacy identity upgrade, real Sable assembly and held input relocation without rebinding");
            }
            if(age==20) {
                rebuildTerminal();
                ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
                input();
            }
            if(age==21) {
                assertFireControlPressed();
                require(!((GasTurbineBlockEntity)level.getBlockEntity(enginePos)).isEngineEnabled(),"rebuilt terminal transmits the next ignition edge");
                stop();
            }
            if(age==22) assemble();
            if(age==28) {
                ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
                require(ControllerSessions.apiState(player).orElseThrow().effective().containsAll(Set.of(KEY,"key_65")),"mapping survives a second move from an existing Sable plot");
                input();
            }
            if(age==29) {
                assertFireControlPressed();
                require(((GasTurbineBlockEntity)level.getBlockEntity(enginePos)).isEngineEnabled(),"engine input works after reassembly");
                stop();
                checkIdentityIsolation();
                finish(null);
            }
        } catch (Throwable error) { finish(error); }
    }
    private static void assemble() {
        var blocks=new ArrayList<BlockPos>();
        for(int x=-2;x<=2;x++)blocks.add(hub.offset(x,-1,0));
        blocks.addAll(List.of(hub,computer,enginePos));
        var body=dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level,hub,blocks,
                new dev.ryanhcode.sable.companion.math.BoundingBox3i(hub.getX()-2,hub.getY()-1,hub.getZ(),hub.getX()+2,hub.getY(),hub.getZ()));
        SubLevelContainer.getContainer(level).addForceLoadTicket(body,
                dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,net.minecraft.util.Unit.INSTANCE);
        hub=body.getPlot().getCenterBlock();computer=hub.east(2);enginePos=hub.west(2);
    }
    private static void input() {
        var state=ControllerSessions.apiState(player).orElseThrow();
        CompoundTag request=new CompoundTag();request.putUUID("Session",state.token());
        request.put("Pressed",ControllerSessions.strings(Set.of(KEY)));
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.INPUT,request));
    }
    private static void assertFireControlPressed() throws Exception {
        Field pressed=StabilizerControllerBlockEntity.class.getDeclaredField("remoteInputPressed");pressed.setAccessible(true);
        require(((boolean[])pressed.get(level.getBlockEntity(computer)))[14],"FCC receives input after terminal reconstruction or reassembly");
    }
    private static void rebuildTerminal() throws Exception {
        var fcc=(StabilizerControllerBlockEntity)level.getBlockEntity(computer);
        var engine=(GasTurbineBlockEntity)level.getBlockEntity(enginePos);
        fcc.loadWithComponents(fcc.saveWithoutMetadata(level.registryAccess()),level.registryAccess());
        engine.loadWithComponents(engine.saveWithoutMetadata(level.registryAccess()),level.registryAccess());
        var processor=(WirelessSignalProcessorBlockEntity)level.getBlockEntity(hub);
        CompoundTag saved=processor.saveWithoutMetadata(level.registryAccess());
        UUID identity=processor.identity();
        level.removeBlock(hub,false);
        Method prune=StabilizerControllerBlockEntity.class.getDeclaredMethod("removeInvalidBindings");prune.setAccessible(true);prune.invoke(fcc);
        require(fcc.getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB)!=null,"temporary absence does not discard the terminal identity");
        require(!RemoteTerminalDetector.hasTerminal(fcc,level,RemoteControllerCompat.Type.WIRELESS)
                &&!engine.hasValidAdvancedHub(),"missing terminal cannot activate inputs");
        level.setBlockAndUpdate(hub,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        var replacement=(WirelessSignalProcessorBlockEntity)level.getBlockEntity(hub);
        UUID replacementId=replacement.identity();
        require(!RemoteTerminalDetector.hasTerminal(fcc,level,RemoteControllerCompat.Type.WIRELESS)
                &&!engine.hasValidAdvancedHub(),"a new terminal at the same coordinates cannot inherit the connection");
        replacement.loadWithComponents(saved,level.registryAccess());
        require(RemoteTerminalDetector.hasTerminal(fcc,level,RemoteControllerCompat.Type.WIRELESS)
                &&engine.hasValidAdvancedHub(),"reconstruction with the same identity restores saved connections");
        require(WirelessSignalProcessorBlockEntity.findLoaded(level,identity)==replacement
                &&WirelessSignalProcessorBlockEntity.findLoaded(level,replacementId)==null,"loadWithComponents reindexes only the restored identity");
        System.out.println("PASS: consumer save/load, temporary terminal absence, same-position replacement rejection and identity-preserving reconstruction");
    }
    private static void checkIdentityIsolation() {
        var processor=(WirelessSignalProcessorBlockEntity)level.getBlockEntity(hub);
        var saved=processor.saveWithoutMetadata(level.registryAccess());
        BlockPos copyPos=new BlockPos(12,240,4);
        level.setBlockAndUpdate(copyPos,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        var copy=(WirelessSignalProcessorBlockEntity)level.getBlockEntity(copyPos);
        copy.loadWithComponents(saved,level.registryAccess());
        BindingRef old=BindingRef.loadTag(originalBinding);
        require(old.refreshWirelessTerminal(level)==old&&!old.isSameTarget(level),"ambiguous duplicated terminal identities cannot steal a relocated connection");
        level.removeBlock(copyPos,false);
        require(old.refreshWirelessTerminal(level).pos().equals(hub),"a unique terminal resolves after duplicate removal");
        var nether=level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        require(old.refreshWirelessTerminal(nether)==old&&!old.isSameTarget(nether),"terminal references cannot follow identities across dimensions");
        System.out.println("PASS: duplicate-identity rejection, unique relocation and dimension isolation");
    }
    private static void stop() {
        var state=ControllerSessions.apiState(player).orElseThrow();
        CompoundTag request=new CompoundTag();request.putUUID("Session",state.token());
        ControllerSessions.handle(player,new WirelessControllerActionPayload(WirelessControllerActionPayload.STOP,request));
    }

    private static void finish(Throwable error) {
        var active = server;server = null;
        if (error != null) StabilizedTurret.LOGGER.error("SABLE REMOTE MAPPING PROBE FAILED", error);
        try { Files.writeString(Path.of("controller-mapping-result.txt"), error == null
                ? "PASS: assembly, reassembly, persistence, reconstruction and identity isolation" : "FAIL: " + error); }
        catch (Exception writeError) { throw new RuntimeException(writeError); }
        if (error == null) System.out.println("PASS: Sable remote mapping regression checks");
        active.halt(false);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
