package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import edn.stratodonut.drivebywire.compat.ControllerSignalStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Real Create seats, Simulated bearings and server-validated remote sessions. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ScopeIntegration {
    private static final BlockPos ORIGIN = new BlockPos(0, 240, 0);
    private static final BlockPos YAW = ORIGIN.offset(2, 0, 0);
    private static final BlockPos PITCH = ORIGIN.offset(4, 0, 0);
    private static final BlockPos SEAT = ORIGIN.offset(6, 0, 0);
    private static final BlockPos ANCHOR = ORIGIN.offset(8, 0, 0);
    private static final BlockPos HUB = ORIGIN.offset(10, 0, 0);
    private static ServerLevel level;
    private static StabilizerControllerBlockEntity controller;
    private static FakePlayer player;
    private static SeatEntity seat;

    @EventBusSubscriber(modid = StabilizedTurret.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Fixtures {
        @SubscribeEvent public static void register(RegisterEvent event) {
            event.register(Registries.BLOCK, helper -> {
                helper.register(id("drivebywire", "controller_hub"), new Block(BlockBehaviour.Properties.of()));
                helper.register(id("drivebywire", "tweaked_controller_hub"), new Block(BlockBehaviour.Properties.of()));
                helper.register(id("create_flight_control", "forward_indicator"),
                        new Indicator(BlockBehaviour.Properties.of()));
            });
            event.register(Registries.ITEM, helper -> helper.register(
                    id("create_tweaked_controllers", "tweaked_linked_controller"), new Item(new Item.Properties())));
        }
    }

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        Throwable failure = null;
        try {
            setup();
            require(!controller.canUseScope(player), "standing without remote authority is rejected");
            setVehicle(seat);
            require(controller.isSeat(player.getVehicle()), "exact Create seat entity grants seat authority");
            require(controller.canUseScope(player), "seated head aim can open a scope without separate input configuration");
            toggle(player);
            require(controller.isScopeEnabled(player), "seated scope opens");
            toggle(player);
            require(!controller.isScopeEnabled(player), "second activation closes scope");
            checkGeometry();
            checkPrerequisites();
            checkRemote(RemoteControllerCompat.Type.NORMAL);
            checkRemote(RemoteControllerCompat.Type.ADVANCED);
            checkMovingGeometry();
            System.out.println("PASS: scope seated/normal/advanced remote authority, both live axes, real direction anchor, input edges, authority loss and bearing-head camera geometry");
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("SCOPE INTEGRATION FAILED", error);
        } finally {
            try {
                if (player != null) {
                    RemoteControlTracker.clear(player);
                    playerList().remove(player);
                    playerMap().remove(player.getUUID());
                }
                Files.writeString(Path.of("scope-integration-result.txt"),
                        failure == null ? "PASS" : "FAIL: " + failure);
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                level.getServer().halt(false);
            }
        }
    }

    private static void setup() throws Exception {
        level.setChunkForced(0, 0, true);
        level.setBlockAndUpdate(ORIGIN, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        controller = (StabilizerControllerBlockEntity) level.getBlockEntity(ORIGIN);
        // ServerStarted precedes the first world tick that normally runs the load hook.
        ControllerTracker.add(controller);
        Block bearing = BuiltInRegistries.BLOCK.get(id("simulated", "swivel_bearing"));
        level.setBlockAndUpdate(YAW, bearing.defaultBlockState().setValue(SwivelBearingBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(PITCH, bearing.defaultBlockState().setValue(SwivelBearingBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(SEAT, BuiltInRegistries.BLOCK.get(id("create", "white_seat")).defaultBlockState());
        level.setBlockAndUpdate(ANCHOR, BuiltInRegistries.BLOCK.get(
                id("create_flight_control", "forward_indicator")).defaultBlockState());
        for (BindingSlot slot : new BindingSlot[]{BindingSlot.YAW, BindingSlot.PITCH,
                BindingSlot.SEAT, BindingSlot.FORWARD_INDICATOR}) {
            BlockPos pos = switch (slot) {
                case YAW -> YAW;
                case PITCH -> PITCH;
                case SEAT -> SEAT;
                default -> ANCHOR;
            };
            controller.bind(slot, BindingRef.capture(level, pos));
        }
        seat = new SeatEntity(level);
        seat.setPos(SEAT.getX() + .5, SEAT.getY(), SEAT.getZ() + .5);
        player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "ScopeRegression"));
        player.setHealth(20);
        player.setPos(SEAT.getX() + .5, SEAT.getY() + 1, SEAT.getZ() + .5);
        playerList().add(player);
        playerMap().put(player.getUUID(), player);
        require(controller.isHeadAimReady(), "actual forward indicator supplies a measurable head-aim direction");
    }

    private static void checkGeometry() throws Exception {
        for (Direction facing : Direction.values()) {
            level.setBlockAndUpdate(PITCH, level.getBlockState(PITCH).setValue(SwivelBearingBlock.FACING, facing));
            close(controller.getScopePosition(), Vec3.atCenterOf(PITCH.relative(facing)),
                    "default camera is exactly one block toward " + facing + " bearing head");
            require(controller.getScopeReferenceBindings().getFirst().pos().equals(PITCH),
                    "camera pose owner is the pitch bearing, not the distant direction indicator");
        }
        set("scopeOffset", new Vec3(.25, -.5, .75));
        close(controller.getScopePosition(), Vec3.atCenterOf(PITCH.relative(Direction.EAST)).add(.25, -.5, .75),
                "configured offsets remain relative to the bearing frame");
        set("scopeOffset", Vec3.ZERO);
    }

    private static void checkPrerequisites() throws Exception {
        set("headAimEnabled", false);
        require(!controller.canUseScope(player), "disabled head aim cannot open scope");
        set("headAimEnabled", true);
        for (BindingSlot slot : new BindingSlot[]{BindingSlot.YAW, BindingSlot.PITCH, BindingSlot.FORWARD_INDICATOR}) {
            BlockPos pos = controller.getBinding(slot).pos();
            require(controller.unbindDevice(slot, 0, pos), "remove prerequisite " + slot);
            require(!controller.canUseScope(player), "missing " + slot + " rejects scope");
            controller.bind(slot, BindingRef.capture(level, pos));
        }
        set("podViewUserId", UUID.randomUUID());
        toggle(player);
        require(!controller.isScopeEnabled(player), "another player's optical view retains ownership");
        set("podViewUserId", player.getUUID());
        toggle(player);
        require(controller.isScopeEnabled(player) && !controller.isPodViewEnabled(player),
                "own pod view switches to scope");
        set("headAimEnabled", false);
        invoke("validateScopeUser");
        require(field("scopeUserId").get(controller) == null, "loss of head aim frees scope ownership");
        set("headAimEnabled", true);
    }

    private static void checkRemote(RemoteControllerCompat.Type type) throws Exception {
        setVehicle(null);
        String hubId = type == RemoteControllerCompat.Type.NORMAL ? "controller_hub" : "tweaked_controller_hub";
        String itemId = type == RemoteControllerCompat.Type.NORMAL ? "linked_controller" : "tweaked_linked_controller";
        String itemNamespace = type == RemoteControllerCompat.Type.NORMAL ? "create" : "create_tweaked_controllers";
        String key = type == RemoteControllerCompat.Type.NORMAL ? "keyJump" : "buttonA";
        level.setBlockAndUpdate(HUB, BuiltInRegistries.BLOCK.get(id("drivebywire", hubId)).defaultBlockState());
        controller.bind(RemoteTerminalDetector.slotFor(type), BindingRef.capture(level, HUB));
        ItemStack remote = new ItemStack(BuiltInRegistries.ITEM.get(id(itemNamespace, itemId)));
        CustomData.update(DataComponents.CUSTOM_DATA, remote, tag -> tag.putLong("DriveByWireHub", HUB.asLong()));
        player.setItemInHand(InteractionHand.MAIN_HAND, remote);
        RemoteControlTracker.update(player, true, type, HUB);
        set("remoteHeadAimEnabled", false);
        require(!controller.canUseScope(player), "input session without head-aim takeover is rejected");
        set("remoteHeadAimEnabled", true);
        require(RemoteControlTracker.findInputController(player) == controller, "remote session resolves its loaded fire control");
        require(controller.isHeadAimReady(), "head-aim prerequisites survive unbind/rebind checks");
        require(controller.canUseScope(player), "authorized " + type + " remote opens scope while standing");
        controller.setRemoteKey(type, 0, key);
        RemoteInputTracker.update(player, type, HUB, Set.of(key));
        invoke("tickRemoteInputs");
        require(controller.isScopeEnabled(player), "mapped " + type + " key opens scope");
        invoke("tickRemoteInputs");
        require(controller.isScopeEnabled(player), "holding key does not toggle scope repeatedly");
        RemoteInputTracker.update(player, type, HUB, Set.of());
        ControllerSignalStore.signals = Map.of();
        invoke("tickRemoteInputs");
        RemoteInputTracker.update(player, type, HUB, Set.of(key));
        invoke("tickRemoteInputs");
        require(!controller.isScopeEnabled(player), "release and press closes scope");
        toggle(player);
        setVehicle(seat);
        RemoteControlTracker.clear(player);
        require(controller.isScopeEnabled(player), "ending remote input preserves seated scope authority");
        RemoteControlTracker.update(player, true, type, HUB);
        setVehicle(null);
        invoke("validateScopeUser");
        require(controller.isScopeEnabled(player), "leaving seat retains active remote scope authority");
        RemoteControlTracker.clear(player);
        require(field("scopeUserId").get(controller) == null, "losing seat and remote authority releases scope");
        RemoteControlTracker.update(player, true, type, HUB);
        toggle(null);
        require(controller.isScopeEnabled(player), "wireless activation resolves authorized remote gunner");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        invoke("validateScopeUser");
        require(field("scopeUserId").get(controller) == null, "putting remote away releases standing scope");
        controller.setRemoteKey(type, 0, "");
    }

    private static void checkMovingGeometry() throws Exception {
        BlockPos anchor = new BlockPos(32, 240, 32);
        level.setBlockAndUpdate(anchor, Blocks.IRON_BLOCK.defaultBlockState());
        var assembly = SimAssemblyHelper.assembleFromSingleBlock(level, anchor.below(), anchor, false, false);
        require(assembly != null, "real Sable camera-frame fixture assembles");
        var body = assembly.subLevel();
        BlockPos pitch = anchor.offset(assembly.offset()).above();
        level.setBlockAndUpdate(pitch, BuiltInRegistries.BLOCK.get(id("simulated", "swivel_bearing"))
                .defaultBlockState().setValue(SwivelBearingBlock.FACING, Direction.WEST));
        var camera = new StabilizerControllerBlockEntity(ORIGIN, controller.getBlockState());
        camera.setLevel(level);
        camera.bind(BindingSlot.PITCH, BindingRef.capture(level, pitch));
        body.logicalPose().orientation().rotationY(.6).rotateZ(.2);
        body.logicalPose().position().add(12, 3, -7);
        Vec3 expected = body.logicalPose().transformPosition(Vec3.atCenterOf(pitch).add(-1, 0, 0));
        close(camera.getScopePosition(), expected, "moving/rotated turret transforms bearing-head anchor once");
        require(body.getUniqueId().equals(camera.getScopeReferenceBindings().getFirst().subLevelId()),
                "camera network pose uses the live bearing's parent Sable frame");
    }

    @SuppressWarnings("unchecked") private static Map<UUID, ServerPlayer> playerMap() throws Exception {
        Field field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
        field.setAccessible(true);
        return (Map<UUID, ServerPlayer>) field.get(level.getServer().getPlayerList());
    }
    @SuppressWarnings("unchecked") private static List<ServerPlayer> playerList() throws Exception {
        Field field = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
        field.setAccessible(true);
        return (List<ServerPlayer>) field.get(level.getServer().getPlayerList());
    }
    private static ResourceLocation id(String namespace, String path) { return ResourceLocation.fromNamespaceAndPath(namespace, path); }
    private static Field field(String name) throws Exception {
        Field field = StabilizerControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void set(String name, Object value) throws Exception { field(name).set(controller, value); }
    private static void setVehicle(SeatEntity vehicle) throws Exception {
        // Forge FakePlayer deliberately refuses riding; supply only the server seat association.
        Field field = net.minecraft.world.entity.Entity.class.getDeclaredField("vehicle");
        field.setAccessible(true);
        field.set(player, vehicle);
    }
    private static void toggle(ServerPlayer user) throws Exception {
        Method method = StabilizerControllerBlockEntity.class.getDeclaredMethod("toggleScope", ServerPlayer.class);
        method.setAccessible(true);
        method.invoke(controller, user);
    }
    private static void invoke(String name) throws Exception {
        Method method = StabilizerControllerBlockEntity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }
    private static void close(Vec3 actual, Vec3 expected, String message) {
        require(actual != null && actual.distanceTo(expected) < 1e-8, message + ": " + actual + " vs " + expected);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Indicator extends HorizontalDirectionalBlock {
        Indicator(BlockBehaviour.Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return simpleCodec(Indicator::new); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    }
}
