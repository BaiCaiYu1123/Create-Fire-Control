package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.api.remote.RemoteControllerApi;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.registry.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.lang.reflect.Method;
import java.util.*;

/** Real registry/session/consumer tests, with the actual wire store cleared or stuck high. */
final class NativeInputFallbackIntegration {
    private static final String START = "key_87", MODIFIER = "key_340", DRIVE = "key_65", FIRE = "mouse_3";

    static void run(ServerLevel level) throws Exception {
        long time = level.getGameTime();
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "NativeInputCheck"));
        player.setPos(10, 240, 10);
        BlockPos hub = new BlockPos(10, 240, 10), computer = hub.east();
        level.setBlockAndUpdate(hub, ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        level.setBlockAndUpdate(computer, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var fcc = (StabilizerControllerBlockEntity) level.getBlockEntity(computer);
        ControllerTracker.add(fcc);
        fcc.bind(BindingSlot.WIRELESS_CONTROLLER_HUB, BindingRef.capture(level, hub));
        fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS, 14, FIRE);
        var settings = new EngineControlSettings(150, START + "+" + MODIFIER,
                List.of(new EngineDriveBehavior(List.of(DRIVE), 256, 128, 128, 128, 128, 128)));
        var states = List.of(ModBlocks.GAS_TURBINE.get().defaultBlockState().setValue(GasTurbineBlock.PART, GasTurbinePart.NEGATIVE),
                ModBlocks.ZTZ99A_DIESEL_ENGINE.get().defaultBlockState(), ModBlocks.TIGER_I_ENGINE.get().defaultBlockState());
        List<GasTurbineBlockEntity> engines = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            BlockPos pos = hub.offset(-2, 0, i);
            level.setBlockAndUpdate(pos, states.get(i));
            var engine = (GasTurbineBlockEntity) level.getBlockEntity(pos); engine.onLoad();
            require(engine.bindAdvancedHub(BindingRef.capture(level, hub)) && engine.applySettings(settings), "engine config");
            engines.add(engine);
        }
        Method tick = StabilizerControllerBlockEntity.class.getDeclaredMethod("tickRemoteInputs"); tick.setAccessible(true);
        Method update = GasTurbineBlockEntity.class.getDeclaredMethod("updateTargetsFromRemote"); update.setAccessible(true);
        ItemStack remote = RemoteControllerApi.createController(); player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        // The same right-click pairing gesture must also work when the optional bridge is absent.
        ControllerSessions.use(player, InteractionHand.MAIN_HAND, hub);
        require(RemoteControllerApi.boundTerminal(level, remote).isPresent(), "native pairing survives missing wire mod");
        UUID token = ControllerSessions.apiStart(player, InteractionHand.MAIN_HAND, null).orElseThrow();
        try {
            ControllerSessions.apiInput(player, token, Set.of(START));
            breakWire(level, hub, Set.of());
            for (var engine : engines) { update.invoke(engine); require(!engine.isEngineEnabled(), "incomplete ignition chord"); }
            ControllerSessions.apiInput(player, token, Set.of(START, MODIFIER, DRIVE, FIRE));
            breakWire(level, hub, Set.of());
            tick.invoke(fcc);
            require(held(fcc), "FCC uses native press after wire loss");
            for (var engine : engines) {
                update.invoke(engine);
                require(engine.isEngineEnabled() && engine.getTargetLeftSpeed() == 256 && engine.getTargetRightSpeed() == 128,
                        "every engine uses native ignition and drive after wire loss");
                update.invoke(engine); require(engine.isEngineEnabled(), "holding ignition cannot toggle twice");
            }
            ControllerSessions.apiInput(player, token, Set.of());
            breakWire(level, hub, Set.of(START, MODIFIER, DRIVE, FIRE));
            tick.invoke(fcc); require(!held(fcc), "FCC releases despite stuck-high wire");
            for (var engine : engines) {
                update.invoke(engine); require(engine.isEngineEnabled() && engine.getTargetMagnitude() == 0, "engine releases despite stuck wire");
            }
            ControllerSessions.apiInput(player, token, Set.of(DRIVE, FIRE));
            breakWire(level, hub, Set.of(DRIVE, FIRE)); tick.invoke(fcc);
            require(held(fcc), "native route remains usable");
            level.getServer().getWorldData().overworldData().setGameTime(time + 31);
            tick.invoke(fcc); require(!held(fcc), "wire cannot retain timed-out native fire");
            for (var engine : engines) { update.invoke(engine); require(engine.getTargetMagnitude() == 0, "native timeout clears drive"); }
        } finally {
            ControllerSessions.apiStop(player, token);
            level.getServer().getWorldData().overworldData().setGameTime(time);
        }
        breakWire(level, hub, Set.of(DRIVE, FIRE));
        tick.invoke(fcc); require(!held(fcc), "session stop clears stale output");
        for (var engine : engines) { update.invoke(engine); require(engine.getTargetMagnitude() == 0, "stop clears engine output"); }
        level.removeBlock(hub, false); tick.invoke(fcc); require(!held(fcc), "removed terminal clears output");
        if (DriveByWireCompat.isAvailable()) normalController(level, player, tick, time);
        System.out.println("PASS: native FCC and all three engines, lost/stuck wire, chords, hold, release, timeout, stop, terminal removal; wire="
                + ControllerWireBridge.available());
    }

    private static void normalController(ServerLevel level, ServerPlayer player, Method tick, long time) throws Exception {
        BlockPos hub = new BlockPos(5, 240, 10), computer = hub.east();
        level.setBlockAndUpdate(hub, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("drivebywire:controller_hub")).defaultBlockState());
        level.setBlockAndUpdate(computer, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var fcc = (StabilizerControllerBlockEntity) level.getBlockEntity(computer); ControllerTracker.add(fcc);
        fcc.bind(BindingSlot.REMOTE_CONTROLLER_HUB, BindingRef.capture(level, hub));
        fcc.setRemoteKey(RemoteControllerCompat.Type.NORMAL, 14, "keyUp");
        ItemStack remote = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:linked_controller")).getDefaultInstance();
        CustomData.update(DataComponents.CUSTOM_DATA, remote, tag -> tag.putLong("DriveByWireHub", hub.asLong()));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        RemoteControlTracker.update(player, true, RemoteControllerCompat.Type.NORMAL, hub);
        RemoteInputTracker.update(player, RemoteControllerCompat.Type.NORMAL, hub, Set.of("keyUp"));
        breakWire(level, hub, Set.of()); tick.invoke(fcc); require(held(fcc), "normal controller native route");
        RemoteInputTracker.update(player, RemoteControllerCompat.Type.NORMAL, hub, Set.of());
        breakWire(level, hub, Set.of("keyUp")); tick.invoke(fcc); require(!held(fcc), "normal controller releases stuck wire");
        RemoteInputTracker.update(player, RemoteControllerCompat.Type.NORMAL, hub, Set.of("keyUp"));
        tick.invoke(fcc); require(held(fcc), "normal controller can press again");
        try {
            level.getServer().getWorldData().overworldData().setGameTime(time + 13);
            tick.invoke(fcc); require(!held(fcc), "direct transport timeout releases normal input");
        } finally {
            level.getServer().getWorldData().overworldData().setGameTime(time);
            RemoteControlTracker.clear(player);
        }
    }

    private static boolean held(StabilizerControllerBlockEntity fcc) throws Exception {
        var field = StabilizerControllerBlockEntity.class.getDeclaredField("remoteInputPressed"); field.setAccessible(true);
        return ((boolean[]) field.get(fcc))[14];
    }
    private static void breakWire(ServerLevel level, BlockPos hub, Set<String> stuck) throws Exception {
        if (!DriveByWireCompat.isAvailable()) return;
        var store = Class.forName("edn.stratodonut.drivebywire.compat.ControllerSignalStore");
        store.getMethod("clear", net.minecraft.world.level.Level.class, BlockPos.class).invoke(null, level, hub);
        for (String key : stuck) store.getMethod("setSignal", net.minecraft.world.level.Level.class, BlockPos.class, String.class, int.class)
                .invoke(null, level, hub, key, 16);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
