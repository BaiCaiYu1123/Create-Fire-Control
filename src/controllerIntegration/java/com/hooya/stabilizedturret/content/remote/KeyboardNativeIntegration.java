package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.network.WirelessControllerActionPayload;
import com.hooya.stabilizedturret.registry.*;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllItems;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.*;
import java.util.*;

/** Real block lifecycle and server ticks; native input packets follow the ordinary gameplay path. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class KeyboardNativeIntegration {
    private static final String START = "key_87", MOD = "key_340", DRIVE = "key_65", NEXT_DRIVE = "key_68";
    private static final String FIRE = "mouse_3", SECOND_FIRE = "mouse_4";
    private static final BlockPos HUB = new BlockPos(10, 240, 10), SINK = HUB.north(2);
    private static final List<BlockPos> ENGINE_POS = List.of(new BlockPos(5,240,3), new BlockPos(5,240,5), new BlockPos(5,240,7));
    private static final List<BlockPos> FCC_POS = List.of(HUB.east(), HUB.south());
    private static MinecraftServer server;
    private static ServerPlayer player;
    private static ItemStack keyboard;
    private static UUID token;
    private static int age;

    static void start(MinecraftServer value) { server = value; age = 0; }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (server == null || event.getServer() != server) return;
        try {
            run(server.overworld(), ++age);
            if (age == 54) finish(null);
        } catch (Throwable error) { finish(error); }
    }

    private static void run(ServerLevel level, int tick) throws Exception {
        if (tick == 1) {
            level.setChunkForced(0, 0, true);
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "KeyboardNative"));
            player.setPos(10.5, 240, 10.5);
            var tab = ModCreativeTabs.MAIN.get();
            tab.buildContents(new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, true, level.registryAccess()));
            require(tab.getDisplayItems().stream().anyMatch(stack -> stack.is(ModItems.WIRELESS_SIGNAL_PROCESSOR.get())),
                    "processor is in the native creative tab with or without wire mod");
            require(level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,
                    "wireless_signal_processor")).isPresent(), "processor recipe loads without wire mod");
            level.setBlockAndUpdate(HUB, ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
            var states = List.of(ModBlocks.GAS_TURBINE.get().defaultBlockState().setValue(GasTurbineBlock.PART, GasTurbinePart.NEGATIVE),
                    ModBlocks.ZTZ99A_DIESEL_ENGINE.get().defaultBlockState(), ModBlocks.TIGER_I_ENGINE.get().defaultBlockState());
            for (int i = 0; i < ENGINE_POS.size(); i++) {
                level.setBlockAndUpdate(ENGINE_POS.get(i), states.get(i));
                var engine = engine(level, i);
                require(engine.getTerminalType() == RemoteControllerCompat.Type.WIRELESS, "new engines default to native keyboard capture");
                require(engine.applySettings(settings(DRIVE)), "keyboard mappings can be saved before binding a terminal");
            }
            keyboard = ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance(); ControllerSettings.ensureId(keyboard);
            var data = ControllerSettings.data(keyboard);
            ControllerSettings.putBindings(data, List.of(
                    new ControllerSettings.Binding(START, "Ignition", ItemStack.EMPTY, ItemStack.EMPTY, false, "", 0),
                    new ControllerSettings.Binding(DRIVE, "Drive", ItemStack.EMPTY, ItemStack.EMPTY, false, "", 7)), level.registryAccess());
            ControllerSettings.write(keyboard, data);
        }
        if (tick == 4) {
            // The real block entities register through onLoad; the fixture never calls it or ticks consumers manually.
            player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
            for (BlockPos pos : ENGINE_POS) {
                GasTurbineBindingHandler.onRightClickBlock(click(pos));
                GasTurbineBindingHandler.onRightClickBlock(click(HUB));
            }
            require(KeyboardControllerInputs.channels(level, HUB).containsAll(Set.of(START, MOD, DRIVE)),
                    "engine-only terminal discovers every mapping without any FCC");
            player.setItemInHand(InteractionHand.MAIN_HAND, keyboard);
            foreignSignals(level, Set.of(START, MOD, DRIVE, FIRE));
            require(FireControlInputRouter.signals(level, HUB, RemoteControllerCompat.Type.WIRELESS).isEmpty(),
                    "foreign wire signals cannot activate a native terminal without a keyboard session");
            ControllerSessions.use(player, InteractionHand.MAIN_HAND, HUB);
            require(ControllerSettings.data(keyboard).hasUUID("Processor"), "ordinary right click pairs the keyboard");
            activate();
            require(ControllerSessions.apiState(player).orElseThrow().effective().containsAll(Set.of(START, MOD, DRIVE)),
                    "engine mappings are sent to the keyboard without redstone or physical cables");
            input(Set.of(START));
        }
        if (tick == 6) {
            for (int i = 0; i < 3; i++) require(!engine(level,i).isEngineEnabled(), "ignition chord needs both keys");
            input(Set.of(START, MOD, DRIVE)); foreignSignals(level, Set.of());
        }
        if (tick == 8) {
            driving(level, DRIVE);
            require(KeyboardControllerInputs.signals(level, HUB).get(START) == 16,
                    "zero redstone strength cannot disable an ignition mapping");
            input(Set.of()); foreignSignals(level, Set.of(START, MOD, DRIVE));
        }
        if (tick == 10) {
            stopped(level);
            for (BlockPos pos : FCC_POS) level.setBlockAndUpdate(pos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        }
        if (tick == 12) {
            for (int i = 0; i < 2; i++) {
                var fcc = fcc(level,i); fcc.bind(BindingSlot.WIRELESS_CONTROLLER_HUB, BindingRef.capture(level,HUB));
                fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS, 14, i == 0 ? FIRE : SECOND_FIRE);
            }
            require(ControllerSessions.apiState(player).orElseThrow().effective().containsAll(Set.of(FIRE,SECOND_FIRE)),
                    "all mapped FCCs publish their uses immediately");
            input(Set.of(DRIVE,FIRE,SECOND_FIRE)); foreignSignals(level, Set.of());
        }
        if (tick == 14) {
            driving(level, DRIVE); require(held(fcc(level,0)) && held(fcc(level,1)), "both FCCs read the same independent terminal");
            if (ControllerWireBridge.available()) {
                level.setBlockAndUpdate(SINK, Blocks.IRON_BLOCK.defaultBlockState());
                require(ControllerWireBridge.connect(level,HUB,SINK,Direction.SOUTH,DRIVE), "optional wire can be connected");
                ControllerSessions.mappingsChanged(level,HUB);
                require(cableSignal(level,DRIVE) == 7, "adding a cable while a key is held receives configured strength");
            }
            for (int i = 0; i < 3; i++) require(engine(level,i).applySettings(settings(NEXT_DRIVE)), "live engine remap accepted");
            require(ControllerSessions.apiState(player).orElseThrow().effective().contains(NEXT_DRIVE),
                    "saving engine keys refreshes keyboard input ownership immediately");
            input(Set.of(DRIVE));
        }
        if (tick == 16) {
            stopped(level); require(!held(fcc(level,0)) && !held(fcc(level,1)), "released FCC keys clear without wire input");
            input(Set.of(NEXT_DRIVE,FIRE));
        }
        if (tick == 18) {
            driving(level,NEXT_DRIVE); require(held(fcc(level,0)), "remapped engine and FCC receive native input");
            for (int i = 0; i < 3; i++) {
                var engine = engine(level,i); var data = engine.saveWithoutMetadata(level.registryAccess());
                engine.loadWithComponents(data,level.registryAccess());
                require(engine.getTerminalType() == RemoteControllerCompat.Type.WIRELESS
                        && engine.getControlSettings().equals(settings(NEXT_DRIVE)), "engine save/reload keeps native keys and terminal type");
            }
            packet(WirelessControllerActionPayload.STOP,Set.of()); foreignSignals(level,Set.of(NEXT_DRIVE,FIRE));
        }
        if (tick == 20) {
            stopped(level); require(!held(fcc(level,0)), "stop releases FCC despite foreign high signals");
            activate(); input(Set.of(NEXT_DRIVE,FIRE));
        }
        if (tick == 22) { driving(level,NEXT_DRIVE); require(held(fcc(level,0)), "native control restarts normally"); }
        if (tick == 52) {
            stopped(level); require(!held(fcc(level,0)), "missing keyboard heartbeat releases all consumers");
            require(KeyboardControllerInputs.signals(level,HUB).isEmpty(), "expired session has no native signals");
            level.removeBlock(HUB,false);
            level.setBlockAndUpdate(HUB,ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
            activate(); input(Set.of(NEXT_DRIVE,FIRE));
            require(ControllerSessions.apiState(player).orElseThrow().terminal() == null,
                    "replacement processor does not inherit a saved pairing");
        }
        if (tick == 54) {
            stopped(level); require(!held(fcc(level,0)), "terminal replacement cannot reactivate FCC or engines");
            packet(WirelessControllerActionPayload.STOP,Set.of());
        }
    }

    private static EngineControlSettings settings(String key) {
        return new EngineControlSettings(150,START+"+"+MOD,
                List.of(new EngineDriveBehavior(List.of(key),256,128,128,128,128,128)));
    }
    private static void activate() {
        ControllerSessions.use(player,InteractionHand.MAIN_HAND,null);
        token = ControllerSessions.apiState(player).orElseThrow().token();
    }
    private static void input(Set<String> keys) { packet(WirelessControllerActionPayload.INPUT,keys); }
    private static void packet(int action,Set<String> keys) {
        CompoundTag data = new CompoundTag(); data.putUUID("Session",token); data.put("Pressed",ControllerSessions.strings(keys));
        ControllerSessions.handle(player,new WirelessControllerActionPayload(action,data));
    }
    private static PlayerInteractEvent.RightClickBlock click(BlockPos pos) {
        return new PlayerInteractEvent.RightClickBlock(player,InteractionHand.MAIN_HAND,pos,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),Direction.UP,pos,false));
    }
    private static GasTurbineBlockEntity engine(ServerLevel level,int index) { return (GasTurbineBlockEntity)level.getBlockEntity(ENGINE_POS.get(index)); }
    private static StabilizerControllerBlockEntity fcc(ServerLevel level,int index) { return (StabilizerControllerBlockEntity)level.getBlockEntity(FCC_POS.get(index)); }
    private static boolean held(StabilizerControllerBlockEntity fcc) throws Exception {
        var field = StabilizerControllerBlockEntity.class.getDeclaredField("remoteInputPressed"); field.setAccessible(true);
        return ((boolean[])field.get(fcc))[14];
    }
    private static void driving(ServerLevel level,String key) {
        for (int i = 0; i < 3; i++) {
            var engine = engine(level,i);
            require(engine.isEngineEnabled() && engine.getTargetLeftSpeed()==256 && engine.getTargetRightSpeed()==128,
                    "engine " + i + " follows " + key + " through real server ticks");
        }
    }
    private static void stopped(ServerLevel level) {
        for (int i = 0; i < 3; i++) require(engine(level,i).getTargetMagnitude()==0, "engine " + i + " drive released");
    }
    private static void foreignSignals(ServerLevel level,Set<String> values) throws Exception {
        if (!ControllerWireBridge.available()) return;
        Class<?> store = Class.forName("edn.stratodonut.drivebywire.compat.ControllerSignalStore");
        store.getMethod("clear",Level.class,BlockPos.class).invoke(null,level,HUB);
        for (String key : values) store.getMethod("setSignal",Level.class,BlockPos.class,String.class,int.class).invoke(null,level,HUB,key,16);
    }
    private static int cableSignal(ServerLevel level,String key) throws Exception {
        var network = Class.forName("edn.stratodonut.drivebywire.wire.WireNetworkManager").getMethod("get",Level.class).invoke(null,level);
        var signals = (Map<?,?>)network.getClass().getMethod("getSourceSignals",BlockPos.class).invoke(network,HUB);
        return signals.get(key) instanceof Number value ? value.intValue() : 0;
    }
    private static void finish(Throwable error) {
        if (error != null) StabilizedTurret.LOGGER.error("KEYBOARD NATIVE INTEGRATION FAILED at tick " + age,error);
        String result = error == null ? "PASS: native tab/recipe, gameplay pairing, engine-only discovery, real ticks, zero-strength ignition, two FCCs, all engines, live remapping, persistence, stop, timeout and replacement; wire="
                + ControllerWireBridge.available() : "FAIL at tick " + age + ": " + error;
        try { Files.writeString(Path.of("keyboard-native-result.txt"),result); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        System.out.println(result);
        var finished = server; server = null; finished.halt(false);
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
