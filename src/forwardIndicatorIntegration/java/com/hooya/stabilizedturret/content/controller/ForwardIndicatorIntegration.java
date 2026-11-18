package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ForwardIndicatorIntegration {
    private static ServerLevel level;
    private static ServerPlayer player;
    private static Capture capture;
    private static boolean flightControlLoaded;

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        Throwable failure = null;
        try {
            level = event.getServer().overworld();
            var id = ResourceLocation.parse("create_flight_control:forward_indicator");
            flightControlLoaded = BuiltInRegistries.BLOCK.containsKey(id);
            if (!flightControlLoaded) {
                BlockPos pos = new BlockPos(128, 128, 0);
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                var ref = BindingRef.capture(level, pos);
                var display = new ConnectedDisplayBlockEntity(pos.east(), ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
                display.setLevel(level);
                require(!ForwardIndicatorCompat.isPresent(level, ref)
                        && ForwardIndicatorCompat.worldDirection(level, ref) == null
                        && !display.bind(BindingSlot.FORWARD_INDICATOR, ref),
                        "Flight Control remains optional and ordinary blocks are rejected");
            } else {
            require(true, "actual Flight Control indicator is loaded");
            var indicator = BuiltInRegistries.BLOCK.get(id);
            var profile = new GameProfile(UUID.randomUUID(), "IndicatorCheck");
            player = new ServerPlayer(event.getServer(), level, profile, ClientInformation.createDefault());
            capture = new Capture(player, profile);
            player.connection = capture;
            player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.asStack());
            for (boolean displayHost : new boolean[]{false, true}) {
            for (boolean physical : new boolean[]{false, true}) {
                String scenario = "display=" + displayHost + ", physical=" + physical;
                BlockPos source = new BlockPos(displayHost ? 160 : 128, 128, physical ? 32 : 0);
                List<BlockPos> blocks = new ArrayList<>();
                for (int x = 0; x < 4; x++) {
                    blocks.add(source.offset(x, -1, 0));
                    level.setBlockAndUpdate(blocks.getLast(), Blocks.STONE.defaultBlockState());
                }
                level.setBlockAndUpdate(source, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(source, displayHost ? ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState()
                        : ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
                level.setBlockAndUpdate(source.east(2), indicator.defaultBlockState());
                blocks.add(source);
                blocks.add(source.east(2));
                UUID bodyId = null;
                if (physical) {
                    var body = SubLevelAssemblyHelper.assembleBlocks(level, source, blocks, BoundingBox3i.from(blocks));
                    require(body != null, "indicator carrier assembles");
                    ((ServerSubLevelContainer)SubLevelContainer.getContainer(level)).addForceLoadTicket(
                            body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                    body.logicalPose().orientation().rotationY(.65).rotateZ(.12);
                    bodyId = body.getUniqueId();
                    source = body.getPlot().getCenterBlock();
                }
                BlockPos target = source.east(2);
                var host = level.getBlockEntity(source);
                require(host != null, "binding source exists; " + scenario);
                require(StabilizerBindingHandler.detectBindingSlot(level, target) == BindingSlot.FORWARD_INDICATOR,
                        "indicator classifier; " + scenario);
                player.setPos(Sable.HELPER.projectOutOfSubLevel(level, source.getCenter()));
                click(source);
                require(capture.candidates().contains(new BindingSessionPayload.Candidate(target, bodyId, false)),
                        "unbound indicator has candidate highlight; " + scenario);
                click(target);
                require(displayHost ? ((ConnectedDisplayBlockEntity)host).isBound(BindingRef.capture(level, target))
                        : ((StabilizerControllerBlockEntity)host).getBinding(BindingSlot.FORWARD_INDICATOR) != null,
                        "real wrench event binds indicator; " + scenario);
                require(capture.candidates().contains(new BindingSessionPayload.Candidate(target, bodyId, true)),
                        "bound indicator has candidate highlight; " + scenario);
                if (!displayHost) {
                    Vec3 direction = ((StabilizerControllerBlockEntity)host).getMeasuredAimDirection();
                    require(direction != null && direction.lengthSqr() > .99,
                            "indicator supplies valid head aim direction; " + scenario);
                } else {
                    checkDisplay((ConnectedDisplayBlockEntity)host, target, scenario);
                }
                click(source);
            }
            }
            }
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("FORWARD INDICATOR INTEGRATION FAILED", error);
        }
        try {
            Files.writeString(Path.of("forward-indicator-result.txt"), failure == null
                    ? (flightControlLoaded
                        ? "PASS: actual Flight Control indicator candidate packets, wrench binding, native facing, display aim requests, weapon priority, persistence and broken-target cleanup on world and physical carrier; existing fire-control head aim retained"
                        : "PASS: dedicated server starts without Flight Control; optional direction bridge rejects ordinary blocks")
                    : "FAIL: " + failure);
        } catch (Exception error) { throw new RuntimeException(error); }
        finally { event.getServer().halt(false); }
    }

    private static void checkDisplay(ConnectedDisplayBlockEntity display, BlockPos target, String scenario) throws Exception {
        var ref = BindingRef.capture(level, target);
        var nativeFacing = Class.forName("ace.flight.block.ForwardIndicatorBlock")
                .getMethod("getModelForwardFacing", BlockState.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState state = level.getBlockState(target).setValue(HorizontalDirectionalBlock.FACING, facing);
            level.setBlockAndUpdate(target, state);
            Object nativeForward = nativeFacing.invoke(null, state);
            Direction nativeDirection = (Direction)nativeForward.getClass().getMethod("direction").invoke(nativeForward);
            Vec3 expected = Sable.HELPER.projectOutOfSubLevel(level, target.getCenter().add(
                    nativeDirection.getStepX(), nativeDirection.getStepY(), nativeDirection.getStepZ()))
                    .subtract(Sable.HELPER.projectOutOfSubLevel(level, target.getCenter())).normalize();
            Vec3 measured = (Vec3)invoke(display, "measuredWeaponDirection");
            require(measured != null && measured.dot(expected) > .999999,
                    "display direction matches native model face " + facing + "; " + scenario);
        }
        Vec3 origin = (Vec3)invoke(display, "measuredWeaponOrigin");
        require(origin != null && origin.distanceTo(ref.worldCenter(level)) < 1e-6,
                "display uses indicator world origin; " + scenario);
        List<?> groups = (List<?>)invoke(display, "turretGroups");
        require(groups.size() == 1, "indicator forms one fallback aiming group; " + scenario);
        Object group = groups.getFirst();
        var requestMethod = ConnectedDisplayBlockEntity.class.getDeclaredMethod("groupAimRequest", group.getClass(),
                ConnectedDisplayBlockEntity.Contact.class, Vec3.class, boolean.class);
        requestMethod.setAccessible(true);
        require(requestMethod.invoke(display, group, null, origin.add(100, 40, 200), true) != null,
                "display generates an aim request without a cannon mount; " + scenario);
        var copy = new ConnectedDisplayBlockEntity(display.getBlockPos(), display.getBlockState());
        copy.setLevel(level);
        copy.loadWithComponents(display.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        require(copy.isBound(ref) && ((Vec3)invoke(copy, "measuredWeaponDirection")).lengthSqr() > .99,
                "display binding and direction survive NBT reload; " + scenario);
        var launcherBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(
                "mianbaos_modernwarfare:antiair_missile_launcher_type_1"));
        require(launcherBlock != Blocks.AIR, "actual air-defense launcher is loaded");
        BlockPos launcherPos = target.east(3);
        level.setBlockAndUpdate(launcherPos, launcherBlock.defaultBlockState());
        var launcher = BindingRef.capture(level, launcherPos);
        require(display.bind(BindingSlot.AA_MISSILE_LAUNCHER, launcher), "bind primary weapon reference");
        var groupConstructor = group.getClass().getDeclaredConstructors()[0];
        groupConstructor.setAccessible(true);
        Object primaryGroup = groupConstructor.newInstance(null, null, List.of(), List.of(launcher), ref);
        var references = ConnectedDisplayBlockEntity.class.getDeclaredMethod("aimReferences", group.getClass());
        references.setAccessible(true);
        require(references.invoke(display, primaryGroup).equals(List.of(launcher)),
                "valid weapon reference takes priority over indicator; " + scenario);
        level.setBlockAndUpdate(launcherPos, Blocks.AIR.defaultBlockState());
        invoke(display, "removeInvalidBindings");
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
        invoke(display, "removeInvalidBindings");
        require(!display.isBound(ref) && invoke(display, "measuredWeaponDirection") == null,
                "replacing indicator removes binding and direction; " + scenario);
        require(!display.bind(BindingSlot.FORWARD_INDICATOR, BindingRef.capture(level, target)),
                "ordinary blocks cannot become direction indicators; " + scenario);
    }

    private static Object invoke(ConnectedDisplayBlockEntity display, String name) throws Exception {
        var method = ConnectedDisplayBlockEntity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(display);
    }

    private static void click(BlockPos pos) {
        var interaction = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos,
                new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
        NeoForge.EVENT_BUS.post(interaction);
        require(interaction.isCanceled(), "binding event is consumed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }

    private static final class Capture extends ServerGamePacketListenerImpl {
        private final List<BindingSessionPayload> pages = new ArrayList<>();
        Capture(ServerPlayer player, GameProfile profile) {
            super(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(profile, false));
        }
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof BindingSessionPayload page)
                pages.add(page);
        }
        List<BindingSessionPayload.Candidate> candidates() {
            var result = new ArrayList<BindingSessionPayload.Candidate>();
            for (var page : pages) {
                if (!page.append()) result.clear();
                result.addAll(page.candidates());
            }
            return result;
        }
    }
}
