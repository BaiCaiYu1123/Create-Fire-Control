package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.registry.*;
import com.mojang.authlib.GameProfile;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprint;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprintExporter;
import dev.rew1nd.sableschematicapi.blueprint.SableBlueprintPlacer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.*;
import java.util.*;

/** Actual Photomancy export/placement, separate source/target worlds, and real server ticks. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ControllerTransferIntegration {
    private static final String IGNITION = "key_87+key_340", DRIVE = "key_65", FIRE = "mouse_3";
    private static MinecraftServer server;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static int age;
    private static final List<Vehicle> vehicles = new ArrayList<>();
    private static Vehicle source;
    private static Vehicle activeVehicle;
    private static ServerSubLevel sourceBody;
    private static UUID session;
    private static String mode;
    private static Path fixture;
    private static final Map<BlockPos, net.minecraft.world.level.block.state.BlockState> delayedSinks = new HashMap<>();
    private record Vehicle(BlockPos hub, BlockPos computer, List<BlockPos> engines, BlockPos lectern, BlockPos sink) {}

    static void start(MinecraftServer value) {
        server = value; level = server.overworld(); age = 0; vehicles.clear(); delayedSinks.clear(); activeVehicle = null;
        mode = System.getProperty("create_fire_control.controllerTransferMode", "export");
        fixture = Path.of(System.getProperty("create_fire_control.controllerTransferFixture"));
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (server == null || event.getServer() != server) return;
        try {
            run(++age);
        } catch (Throwable error) { finish(error); }
    }

    @SubscribeEvent public static void followVehicle(ServerTickEvent.Pre event) {
        if (server == null || event.getServer() != server || activeVehicle == null) return;
        // The fake player has no seat/client movement to follow a falling physics plot.
        var center = BindingRef.capture(level, activeVehicle.lectern()).worldCenter(level);
        player.setPos(center.x, center.y, center.z);
    }

    private static void run(int tick) throws Exception {
        if (tick == 1) {
            player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "TransferCheck"));
            player.setGameMode(GameType.CREATIVE);
            level.setChunkForced(0, 0, true);
            if (mode.equals("export")) {
                ServerSubLevel body = (ServerSubLevel) SubLevelContainer.getContainer(level).allocateNewSubLevel(new Pose3d());
                sourceBody = body;
                BlockPos hub = body.getPlot().getCenterBlock().atY(240);
                body.logicalPose().rotationPoint().set(hub.getX(), hub.getY(), hub.getZ());
                body.logicalPose().position().set(5, 240, 5);
                source = layout(hub);
                Set<net.minecraft.world.level.ChunkPos> chunks = new HashSet<>();
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
                    chunks.add(new net.minecraft.world.level.ChunkPos(hub.offset(x, -1, z)));
                for (var chunk : chunks) body.getPlot().newEmptyChunk(chunk);
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    BlockPos pos = hub.offset(x, -1, z);
                    level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                }
                force(body); place(source); body.forceUpdateGlobalBounds();
            } else if (mode.equals("import") || mode.equals("recover")) {
                importVehicle("vehicle.nbt", new BlockPos(30, 240, 30));
                importVehicle("vehicle.nbt", new BlockPos(60, 240, 60));
            } else {
                CompoundTag manifest = NbtIo.readCompressed(Path.of("controller-transfer-placed.nbt"), NbtAccounter.unlimitedHeap());
                ListTag list = manifest.getList("Vehicles", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) vehicles.add(readVehicle(list.getCompound(i)));
                for (var body : SubLevelContainer.getContainer(level).getAllSubLevels()) force((ServerSubLevel) body);
            }
        }
        if (mode.equals("export")) {
            if (tick == 6) {
                configure(source);
                Files.createDirectories(fixture);
                SableBlueprint blueprint = (SableBlueprint) SableBlueprintExporter.class.getMethod("exportSelected",
                        ServerLevel.class, Vec3.class, Collection.class).invoke(null, level,
                        new Vec3(5, 240, 5), List.of(sourceBody.getUniqueId()));
                require(!blueprint.isEmpty() && blueprint.blockEntityCount() == 6,
                        "actual Photomancy export captures terminal, FCC, three engines and mounted keyboard: " + blueprint.blockEntityCount());
                require(blueprint.save().getCompound("global_extra_data").getCompound("create_fire_control:keyboard_connections")
                        .getList("Connections", Tag.TAG_COMPOUND).size() == 5, "independent native binding manifest captures every consumer");
                NbtIo.writeCompressed(blueprint.save(), fixture.resolve("vehicle.nbt"));
                Files.writeString(fixture.resolve("vehicle.snbt"), blueprint.save().toString());
                CompoundTag manifest = new CompoundTag();
                manifest.putUUID("SourceProcessor", processor(source).identity());
                manifest.putUUID("SourceController", ControllerSettings.id(mounted(source).controller()));
                NbtIo.writeCompressed(manifest, fixture.resolve("source.nbt"));
                finish(null);
            }
            return;
        }
        if (tick == 5 && (mode.equals("import") || mode.equals("recover"))) discoverVehicles();
        if (tick == 6 && mode.equals("recover")) {
            delayedSinks.forEach((pos, state) -> level.setBlockAndUpdate(pos, state));
            System.out.println("PASS: deferred cable endpoints become available after placement");
        }
        int check = tick - (mode.equals("recover") ? 24 : 0);
        if (check == 8) {
            require(vehicles.size() == 2, "two independent Photomancy vehicle copies exist");
            Set<UUID> processorIds = new HashSet<>(), itemIds = new HashSet<>();
            CompoundTag original = NbtIo.readCompressed(fixture.resolve("source.nbt"), NbtAccounter.unlimitedHeap());
            for (Vehicle vehicle : vehicles) {
                verifyBindings(vehicle);
                require(processorIds.add(processor(vehicle).identity()), "each placement gets a distinct terminal identity");
                require(itemIds.add(ControllerSettings.id(mounted(vehicle).controller())), "each mounted keyboard has a distinct identity");
                require(!processor(vehicle).identity().equals(original.getUUID("SourceProcessor")), "source terminal identity is not reused");
                require(!ControllerSettings.id(mounted(vehicle).controller()).equals(original.getUUID("SourceController")), "source controller identity is not reused");
            }
            activate(vehicles.getFirst());
        }
        if (check == 10) {
            verifyDriving(vehicles.getFirst());
            for (int i = 1; i < vehicles.size(); i++) for (BlockPos pos : vehicles.get(i).engines())
                require(!engine(pos).isEngineEnabled(), "input cannot control another imported copy");
            ControllerSessions.apiInput(player, session, Set.of());
        }
        if (check == 12) {
            for (BlockPos pos : vehicles.getFirst().engines()) require(engine(pos).getTargetMagnitude() == 0, "key release stops imported engine");
            ControllerSessions.apiStop(player, session); activate(vehicles.get(1));
        }
        if (check == 14) {
            verifyDriving(vehicles.get(1)); ControllerSessions.apiStop(player, session);
            for (Vehicle vehicle : vehicles) {
                // Leave a stopped, disabled machine for the next real process restart.
                for (BlockPos pos : vehicle.engines()) {
                    CompoundTag tag = engine(pos).saveWithoutMetadata(level.registryAccess());
                    tag.putBoolean("EngineEnabled", false); engine(pos).loadWithComponents(tag, level.registryAccess()); engine(pos).setChanged();
                }
            }
            CompoundTag manifest = new CompoundTag(); ListTag list = new ListTag();
            for (Vehicle vehicle : vehicles) list.add(writeVehicle(vehicle));
            manifest.put("Vehicles", list); NbtIo.writeCompressed(manifest, Path.of("controller-transfer-placed.nbt"));
            finish(null);
        }
    }

    private static Vehicle layout(BlockPos hub) {
        return new Vehicle(hub, hub.east(2), List.of(hub.west(2), hub.north(2), hub.south(2)), hub.east(), hub.south(3));
    }
    private static void place(Vehicle v) {
        level.setBlockAndUpdate(v.hub(), ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get().defaultBlockState());
        level.setBlockAndUpdate(v.computer(), ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        level.setBlockAndUpdate(v.lectern(), ModBlocks.WIRELESS_LECTERN_CONTROLLER.get().defaultBlockState());
        level.setBlockAndUpdate(v.sink(), Blocks.IRON_BLOCK.defaultBlockState());
        var states = List.of(ModBlocks.GAS_TURBINE.get().defaultBlockState().setValue(GasTurbineBlock.PART, GasTurbinePart.NEGATIVE),
                ModBlocks.ZTZ99A_DIESEL_ENGINE.get().defaultBlockState(), ModBlocks.TIGER_I_ENGINE.get().defaultBlockState());
        for (int i = 0; i < 3; i++) level.setBlockAndUpdate(v.engines().get(i), states.get(i));
    }
    private static void configure(Vehicle v) {
        var fcc = (StabilizerControllerBlockEntity) level.getBlockEntity(v.computer());
        fcc.bind(BindingSlot.WIRELESS_CONTROLLER_HUB, BindingRef.capture(level, v.hub()));
        fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS, 14, FIRE);
        var settings = new EngineControlSettings(150, IGNITION, List.of(new EngineDriveBehavior(List.of(DRIVE), 256, 128, 128, 128, 128, 128)));
        for (BlockPos pos : v.engines()) { require(engine(pos).bindAdvancedHub(BindingRef.capture(level, v.hub())), "source engine binds"); engine(pos).applySettings(settings); }
        ItemStack keyboard = ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance(); ControllerSettings.ensureId(keyboard);
        CompoundTag data = ControllerSettings.data(keyboard);
        ControllerSettings.putBindings(data, List.of(new ControllerSettings.Binding(DRIVE, "Transfer drive", ItemStack.EMPTY, ItemStack.EMPTY, false, "", 7)), level.registryAccess());
        ControllerSettings.write(keyboard, data); player.setItemInHand(InteractionHand.MAIN_HAND, keyboard);
        ControllerSessions.use(player, InteractionHand.MAIN_HAND, v.hub()); mounted(v).setController(keyboard);
        if (ControllerWireBridge.available()) require(ControllerWireBridge.connect(level, v.hub(), v.sink(), Direction.NORTH, DRIVE), "source physical cable connects");
    }
    private static void importVehicle(String file, BlockPos anchor) throws Exception {
        level.setChunkForced(anchor.getX() >> 4, anchor.getZ() >> 4, true);
        CompoundTag serialized = NbtIo.readCompressed(fixture.resolve(file), NbtAccounter.unlimitedHeap());
        if (mode.equals("recover")) serialized.getCompound("global_extra_data").remove("sable_schematic_api:drivebywire/wires");
        SableBlueprint blueprint = SableBlueprint.load(serialized);
        if (mode.equals("recover")) {
            // Simulate missing per-device mapper data and an unavailable global cable sidecar.
            // The independent native connection manifest and terminal-local cable backup must recover them.
            for (var body : blueprint.subLevels()) for (CompoundTag tag : body.blockEntities()) {
                tag.remove("stabilized_turret:portable_binding_WIRELESS_CONTROLLER_HUB");
                tag.remove("create_fire_control:portable_gas_turbine_advanced_hub");
                tag.remove("create_fire_control:portable_lectern_hub");
            }
        }
        var result = SableBlueprintPlacer.place(level, blueprint, Vec3.atLowerCornerOf(anchor));
        require(result.placedSubLevels() == 1, "actual Photomancy placer creates a vehicle: " + result);
        if (mode.equals("recover")) for (UUID id : result.subLevelUuidMap().values()) {
            var body = SubLevelContainer.getContainer(level).getSubLevel(id);
            var b = body.getPlot().getBoundingBox();
            for (BlockPos pos : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()))
                if (level.getBlockState(pos).is(Blocks.IRON_BLOCK)) {
                    delayedSinks.put(pos.immutable(), level.getBlockState(pos)); level.removeBlock(pos, false);
                }
        }
        for (var body : SubLevelContainer.getContainer(level).getAllSubLevels()) force((ServerSubLevel) body);
    }
    private static void discoverVehicles() {
        List<BlockEntity> entities = new ArrayList<>();
        for (var body : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            var b = body.getPlot().getBoundingBox();
            for (BlockPos pos : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
                BlockEntity entity = level.getBlockEntity(pos); if (entity != null) entities.add(entity);
            }
        }
        for (BlockEntity entity : entities) if (entity instanceof WirelessSignalProcessorBlockEntity terminal) {
            BlockPos hub = terminal.getBlockPos();
            var nearby = entities.stream().filter(other -> other.getBlockPos().distSqr(hub) <= 16).toList();
            BlockPos computer = nearby.stream().filter(other -> other instanceof StabilizerControllerBlockEntity).findFirst().orElseThrow().getBlockPos();
            BlockPos lectern = nearby.stream().filter(other -> other instanceof WirelessLecternControllerBlockEntity).findFirst().orElseThrow().getBlockPos();
            List<BlockPos> engines = nearby.stream().filter(other -> other instanceof GasTurbineBlockEntity).map(BlockEntity::getBlockPos).toList();
            BlockPos sink = BlockPos.betweenClosedStream(hub.offset(-3,-1,-3), hub.offset(3,1,3))
                    .filter(pos -> level.getBlockState(pos).is(Blocks.IRON_BLOCK)).findFirst()
                    .orElseGet(() -> delayedSinks.keySet().stream().filter(pos -> pos.distSqr(hub) <= 9).findFirst().orElseThrow()).immutable();
            vehicles.add(new Vehicle(hub, computer, engines, lectern, sink));
        }
        require(vehicles.size() == 2, "actual Photomancy placement imports two complete vehicles: " + vehicles);
    }
    private static void verifyBindings(Vehicle v) {
        var fcc = (StabilizerControllerBlockEntity) level.getBlockEntity(v.computer());
        require(fcc != null && RemoteTerminalDetector.hasTerminal(fcc, level, RemoteControllerCompat.Type.WIRELESS), "imported FCC resolves its terminal: " + v);
        require(fcc.getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB).pos().equals(v.hub()), "FCC points at the imported terminal");
        require(fcc.getRemoteKeys(RemoteControllerCompat.Type.WIRELESS).contains(FIRE), "FCC key mapping survives transfer");
        require(v.engines().size() == 3, "all three engine types survive transfer");
        for (BlockPos pos : v.engines()) {
            require(engine(pos).hasValidAdvancedHub() && engine(pos).getAdvancedHub().pos().equals(v.hub()), "engine binding survives transfer: " + pos);
            require(engine(pos).getControlSettings().engineToggleKey().equals(IGNITION), "ignition chord survives transfer");
        }
        CompoundTag data = ControllerSettings.data(mounted(v).controller());
        require(ControllerSessions.apiResolveTerminal(level, data).orElseThrow().equals(v.hub()), "mounted keyboard resolves imported terminal");
        require(ControllerSettings.bindings(data, level.registryAccess()).getFirst().signalStrength() == 7, "configured cable strength survives transfer");
        ListTag pending = processor(v).saveWithoutMetadata(level.registryAccess())
                .getList(WirelessSignalProcessorBlockEntity.PORTABLE_WIRES, Tag.TAG_COMPOUND);
        if (ControllerWireBridge.available()) {
            require(ControllerWireBridge.connected(level, v.hub()).contains(DRIVE), "physical cable restores automatically: " + v.hub());
            require(pending.isEmpty(), "completed cable restoration clears the retry queue");
        } else require(!pending.isEmpty(), "cable metadata survives without the optional wire mod");
        System.out.println("PASS: imported terminal/FCC/three engines/mounted keyboard" + (ControllerWireBridge.available() ? "/cable" : " without wire mod") + " at " + v.hub());
    }
    private static void activate(Vehicle v) {
        activeVehicle = v;
        var center = BindingRef.capture(level, v.lectern()).worldCenter(level);
        player.setPos(center.x, center.y, center.z);
        session = ControllerSessions.apiStart(player, InteractionHand.MAIN_HAND, v.lectern()).orElseThrow();
        require(ControllerSessions.apiState(player).orElseThrow().effective().containsAll(Set.of("key_87", "key_340", DRIVE, FIRE)), "imported mappings publish all native inputs");
        require(ControllerSessions.apiInput(player, session, Set.of("key_87", "key_340", DRIVE, FIRE)), "native keyboard input accepted");
    }
    private static void verifyDriving(Vehicle v) throws Exception {
        for (BlockPos pos : v.engines()) require(engine(pos).isEngineEnabled() && engine(pos).getTargetLeftSpeed() == 256
                && engine(pos).getTargetRightSpeed() == 128, "imported engine follows actual server ticks: " + pos
                + " enabled=" + engine(pos).isEngineEnabled() + " speeds=" + engine(pos).getTargetLeftSpeed()
                + "/" + engine(pos).getTargetRightSpeed() + " session=" + ControllerSessions.apiState(player));
        var field = StabilizerControllerBlockEntity.class.getDeclaredField("remoteInputPressed"); field.setAccessible(true);
        require(((boolean[]) field.get(level.getBlockEntity(v.computer())))[14], "imported FCC receives held fire input");
        if (ControllerWireBridge.available()) {
            var manager = Class.forName("edn.stratodonut.drivebywire.wire.WireNetworkManager").getMethod("get", net.minecraft.world.level.Level.class).invoke(null, level);
            Direction side = Direction.getNearest(v.hub().getX() - v.sink().getX(), 0, v.hub().getZ() - v.sink().getZ());
            int signal = (int) manager.getClass().getMethod("getSignalAt", BlockPos.class, Direction.class).invoke(manager, v.sink(), side);
            var signals = (Map<?, ?>) manager.getClass().getMethod("getSourceSignals", BlockPos.class).invoke(manager, v.hub());
            require(Objects.equals(signals.get(DRIVE), 7) && signal == 7, "imported cable retains its face and signal strength (sink=" + signal + ")");
        }
    }
    private static void force(ServerSubLevel body) {
        SubLevelContainer.getContainer(level).addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
    }
    private static WirelessSignalProcessorBlockEntity processor(Vehicle v) { return (WirelessSignalProcessorBlockEntity) level.getBlockEntity(v.hub()); }
    private static WirelessLecternControllerBlockEntity mounted(Vehicle v) { return (WirelessLecternControllerBlockEntity) level.getBlockEntity(v.lectern()); }
    private static GasTurbineBlockEntity engine(BlockPos pos) { return (GasTurbineBlockEntity) level.getBlockEntity(pos); }
    private static CompoundTag writeVehicle(Vehicle v) {
        CompoundTag tag = new CompoundTag(); tag.putLong("Hub", v.hub().asLong()); tag.putLong("Computer", v.computer().asLong());
        tag.putLong("Lectern", v.lectern().asLong()); tag.putLong("Sink", v.sink().asLong()); tag.putLongArray("Engines", v.engines().stream().mapToLong(BlockPos::asLong).toArray()); return tag;
    }
    private static Vehicle readVehicle(CompoundTag tag) {
        return new Vehicle(BlockPos.of(tag.getLong("Hub")), BlockPos.of(tag.getLong("Computer")),
                Arrays.stream(tag.getLongArray("Engines")).mapToObj(BlockPos::of).toList(), BlockPos.of(tag.getLong("Lectern")), BlockPos.of(tag.getLong("Sink")));
    }
    private static void finish(Throwable error) {
        if (error != null) StabilizedTurret.LOGGER.error("CONTROLLER TRANSFER FAILED at tick " + age, error);
        String result = error == null ? "PASS: " + mode + (mode.equals("export")
                ? "; actual Photomancy export, six block entities, independent binding manifest"
                : "; cross-world blueprint, independent copies, native mappings, three engines, FCC, mounted keyboard") + ", wire=" + ControllerWireBridge.available()
                : "FAIL: " + mode + " at tick " + age + ": " + error;
        try { Files.writeString(Path.of("controller-transfer-result.txt"), result); } catch (Exception failure) { throw new RuntimeException(failure); }
        System.out.println(result); var done = server; server = null; done.halt(false);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
