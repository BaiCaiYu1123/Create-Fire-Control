package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real bearing assembly, computer/display bindings, native racks and both flight backends. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AntiRadiationIntegration {
    private static ServerLevel level;
    private static StabilizerControllerBlockEntity receiver;
    private static ServerPlayer player;
    private static MechanicalBearingBlockEntity flightRadar;
    private static BindingRef flightTarget;
    private static final List<Entity> missiles = new ArrayList<>();
    private static Entity captured;
    private static boolean capturing, finished;
    private static int ticks;
    private static boolean realtimeDelayCheck, delayRestarted;
    private static long delayStartedAt;
    private static BindingRef delayTarget;

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void capture(EntityJoinLevelEvent event) {
        if (capturing && MianbaoAircraftCompat.isAntiRadiation(MianbaoAircraftCompat.typeOf(event.getEntity())))
            captured = event.getEntity();
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (Boolean.getBoolean("create_fire_control.antiRadiationClientChecks")
                || Boolean.getBoolean("create_fire_control.antiRadiationExhaustChecks")) return;
        level = event.getServer().overworld();
        try {
            BlockPos launch = new BlockPos(512, 220, 512);
            receiver = computer(launch);
            BlockPos seatPos = launch.east(3);
            level.setBlockAndUpdate(seatPos, AllBlocks.SEATS.get(net.minecraft.world.item.DyeColor.RED).getDefaultState());
            var seat = new SeatEntity(level);
            seat.setPos(Vec3.atCenterOf(seatPos));
            level.addFreshEntity(seat);
            player = FakePlayerFactory.getMinecraft(level);
            player.setPos(Vec3.atCenterOf(seatPos));
            // NeoForge FakePlayer refuses riding; establish only the seat association used by authority checks.
            var vehicle = Entity.class.getDeclaredField("vehicle");
            vehicle.setAccessible(true);
            vehicle.set(player, seat);
            playerList().add(player);
            receiver.bind(BindingSlot.SEAT, BindingRef.capture(level, seatPos));
            require(ControllerTracker.findForVehicle(level, seat) == receiver, "Seated authority missing");

            var computerRadar = bearing(new BlockPos(512, 220, 128));
            var owner = computer(computerRadar.getBlockPos().east(3));
            var rootRef = BindingRef.capture(level, computerRadar.getBlockPos());
            owner.bind(BindingSlot.RADAR_BEARING, rootRef);

            var displayRadar = bearing(new BlockPos(544, 220, 128));
            var displayPos = displayRadar.getBlockPos().east(3);
            level.setBlockAndUpdate(displayPos, ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
            var display = (ConnectedDisplayBlockEntity) level.getBlockEntity(displayPos);
            display.onLoad();
            var displayRef = BindingRef.capture(level, displayRadar.getBlockPos());
            require(display.bind(BindingSlot.RADAR_BEARING, displayRef), "Display radar binding failed");

            BlockPos anchor = new BlockPos(576, 220, 128);
            level.setBlockAndUpdate(anchor, Blocks.IRON_BLOCK.defaultBlockState());
            var assembly = dev.simulated_team.simulated.util.SimAssemblyHelper.assembleFromSingleBlock(
                    level, anchor.below(), anchor, false, false);
            require(assembly != null, "Sable body assembly failed");
            var body = assembly.subLevel();
            ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)).addForceLoadTicket(
                    (dev.ryanhcode.sable.sublevel.ServerSubLevel) body,
                    dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                    net.minecraft.util.Unit.INSTANCE);
            var plot = anchor.offset(assembly.offset()).above(2);
            var physicalRadar = bearing(plot);
            var physicalOwner = computer(plot.east(3));
            flightTarget = BindingRef.capture(level, plot);
            require(flightTarget.subLevelId() != null, "Radar not inside real Sable structure");
            physicalOwner.bind(BindingSlot.RADAR_BEARING, flightTarget);
            body.forceUpdateGlobalBounds();
            flightRadar = physicalRadar;

            // Verify a real aircraft-side Sable rack, including yaw, pitch, roll and an empty rack.
            var physicalRackPos = plot.east(6);
            var physicalRackState = BuiltInRegistries.BLOCK.get(id("antiradiationmissilerack")).defaultBlockState();
            var physicalFacing = physicalRackState.hasProperty(BlockStateProperties.FACING)
                    ? BlockStateProperties.FACING : BlockStateProperties.HORIZONTAL_FACING;
            level.setBlockAndUpdate(physicalRackPos, physicalRackState.setValue(physicalFacing, Direction.NORTH));
            var aircraft = computer(plot.east(9));
            var physicalRack = BindingRef.capture(level, physicalRackPos);
            aircraft.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, physicalRack);
            var selection = StabilizerControllerBlockEntity.class.getDeclaredField("selectedAircraftWeapon");
            selection.setAccessible(true); selection.set(aircraft, MianbaoAircraftCompat.WeaponType.ANTI_RADIATION);
            var oldOrientation = new org.joml.Quaterniond(body.logicalPose().orientation());
            body.logicalPose().orientation().rotationYXZ(.6, -.2, .4);
            var physicalFrame = AntiRadiationSearchFrame.from(aircraft);
            require(physicalFrame != null && body.getUniqueId().equals(physicalFrame.bodyId())
                    && physicalFrame.forward().distanceToSqr(MianbaoAircraftCompat.launchDirection(level, physicalRack)) < 1e-10,
                    "Rotated Sable search axes differ from actual rack launch direction");
            require(physicalFrame.forward().distanceToSqr(new Vec3(0, 0, -1)) > .1,
                    "Sable aircraft orientation was ignored");
            var emptyRackState = BuiltInRegistries.BLOCK.get(id("rack")).defaultBlockState();
            level.setBlockAndUpdate(physicalRackPos, emptyRackState.setValue(physicalFacing, Direction.NORTH));
            require(AntiRadiationSearchFrame.from(aircraft) != null,
                    "Empty Sable rack lost its interpolated search axes");
            body.logicalPose().orientation().set(oldOrientation);
            body.forceUpdateGlobalBounds();

            BlockPos aircraftRadarPos = new BlockPos(896, 220, 128);
            level.setBlockAndUpdate(aircraftRadarPos, BuiltInRegistries.BLOCK.get(id("jetradar")).defaultBlockState());
            computer(aircraftRadarPos.east(3)).bind(BindingSlot.AIRCRAFT_RADAR, BindingRef.capture(level, aircraftRadarPos));

            BlockPos rackPos = launch.north(3);
            loadRack(rackPos);
            int action = FireControlWeaponInputs.action(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION);
            receiver.setFrequency(action, true, new ItemStack(Items.DIRT));
            pulse(action);
            receiver.setFrequency(6, true, new ItemStack(Items.STONE));
            require(receiver.getSelectedAircraftWeapon() == MianbaoAircraftCompat.WeaponType.ANTI_RADIATION,
                    "Bound weapon selection did not select anti-radiation missile");
            require(!receiver.isRadarActive() && !receiver.isPodViewEnabled(), "Passive seeker fixture depends on radar/pod");
            require(!receiver.isAircraftWeaponGuidanceReady(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION), "Unlocked missile marked ready");
            pulse(6);
            require(receiver.getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION) == 1,
                    "Missile fired without an emitter lock");

            receiver.setFrequency(10, true, new ItemStack(Items.COBBLESTONE));
            receiver.setFrequency(5, true, new ItemStack(Items.GRANITE));
            var rackState = level.getBlockState(rackPos);
            var facingProperty = rackState.hasProperty(BlockStateProperties.FACING)
                    ? BlockStateProperties.FACING : BlockStateProperties.HORIZONTAL_FACING;
            level.setBlockAndUpdate(rackPos, rackState.setValue(facingProperty, Direction.EAST));
            aim(Vec3.atCenterOf(computerRadar.getBlockPos()));
            pulse(10);
            require(AntiRadiationTargeting.target(receiver) == null && AntiRadiationTargeting.searching(receiver),
                    "Camera acquired a radar outside the missile's physical forward cone");
            pulse(10);
            level.setBlockAndUpdate(rackPos, rackState);
            AntiRadiationTargeting.clear(receiver);
            require(AntiRadiationSearchFrame.from(receiver).forward().distanceToSqr(
                    MianbaoAircraftCompat.launchDirection(level, BindingRef.capture(level, rackPos))) < 1e-12,
                    "HUD search direction differs from actual missile rack direction");
            aim(Vec3.atCenterOf(computerRadar.getBlockPos()));
            for (int sample = 0; sample < 3; sample++) {
                require(AntiRadiationTargeting.target(receiver) == null && !AntiRadiationTargeting.searching(receiver),
                        "Selecting the missile or receiving a camera frame started searching");
                aim(Vec3.atCenterOf(computerRadar.getBlockPos()));
            }
            pulse(5);
            require(AntiRadiationTargeting.target(receiver) == null && !AntiRadiationTargeting.searching(receiver),
                    "Radar lock input started an anti-radiation search");
            require(!com.hooya.stabilizedturret.network.AntiRadiationStatePayload.from(receiver).enabled(),
                    "Selecting a weapon displayed an unarmed search box");

            pulse(10);
            require(rootRef.equals(AntiRadiationTargeting.target(receiver)), "Air-to-ground lock input did not acquire radar");
            require(com.hooya.stabilizedturret.network.AntiRadiationStatePayload.from(receiver).enabled()
                    && !AntiRadiationTargeting.searching(receiver), "Locking hid the outer search frame");
            pulse(10);
            aim(Vec3.atCenterOf(computerRadar.getBlockPos()));
            require(AntiRadiationTargeting.target(receiver) == null && !AntiRadiationTargeting.searching(receiver),
                    "Unlocking immediately restarted the search");

            AntiRadiationTargeting.clear(receiver);
            pulse(10);
            require(AntiRadiationTargeting.searching(receiver), "Lock press before the first camera frame was lost");
            aim(Vec3.atCenterOf(computerRadar.getBlockPos()));
            require(rootRef.equals(AntiRadiationTargeting.target(receiver)), "Armed search ignored the next camera frame");

            AntiRadiationTargeting.clear(receiver);
            aim(Vec3.atCenterOf(aircraftRadarPos));
            pulse(10);
            require(AntiRadiationTargeting.target(receiver) == null && AntiRadiationTargeting.searching(receiver),
                    "An empty-frame search did not remain armed after the lock press");
            pulse(10);
            aim(Vec3.atCenterOf(computerRadar.getBlockPos()));
            require(AntiRadiationTargeting.target(receiver) == null && !AntiRadiationTargeting.searching(receiver),
                    "Cancelling search allowed later camera frames to acquire a radar");

            // Two real, operating radar bindings in the same acquisition box.
            AntiRadiationTargeting.clear(receiver);
            Vec3 rootPoint = GroundRadarEmitters.boundPosition(level, rootRef);
            Vec3 displayPoint = GroundRadarEmitters.boundPosition(level, displayRef);
            Vec3 forward = rootPoint.subtract(player.position()).normalize();
            Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize(), up = right.cross(forward).normalize();
            var broad = new AntiRadiationTargeting.Frame(player.position(), forward, right, up, 2, 2560.0 / 1440);
            Vec3 second = broad.project(displayPoint);
            require(second != null && AntiRadiationGeometry.contains(second.x, second.y, broad.aspect()),
                    "Second emitter is outside the multi-target test frame");
            require(AntiRadiationTargeting.updateFrame(receiver, player, broad), "Multi-target camera frame rejected");
            pulse(10);
            require(rootRef.equals(AntiRadiationTargeting.target(receiver)), "Did not choose radar nearest player reticle");
            pulse(10);
            var flightCircle = broad.withAim(AntiRadiationGeometry.referenceX(second.x, broad.aspect()), second.y * 1440);
            AntiRadiationTargeting.updateFrame(receiver, player, flightCircle);
            pulse(10);
            require(displayRef.equals(AntiRadiationTargeting.target(receiver)), "Did not choose radar nearest off-centre flight circle");
            AntiRadiationTargeting.updateFrame(receiver, player, broad);
            require(displayRef.equals(AntiRadiationTargeting.target(receiver)), "Moving reticle replaced a valid existing lock");
            pulse(10);

            AntiRadiationTargeting.clear(receiver);
            aim(Vec3.atCenterOf(aircraftRadarPos));
            pulse(10);
            require(AntiRadiationTargeting.searching(receiver), "Search mode was not armed");
            aim(rootPoint);
            require(rootRef.equals(AntiRadiationTargeting.target(receiver)),
                    "Visible search box required a second shortcut press to acquire radar");

            lock(rootRef); lock(displayRef); lock(flightTarget);
            AntiRadiationTargeting.clear(receiver);
            aim(Vec3.atCenterOf(aircraftRadarPos));
            pulse(10);
            require(AntiRadiationTargeting.target(receiver) == null, "Aircraft radar acquired as ground emitter");

            lock(rootRef);
            computerRadar.setSpeed(0);
            require(AntiRadiationTargeting.target(receiver) == null, "Stopped computer radar retained lock");
            aim(Vec3.atCenterOf(computerRadar.getBlockPos()));
            pulse(10);
            require(AntiRadiationTargeting.target(receiver) == null, "Stopped radar acquired");
            computerRadar.setSpeed(16);

            lock(displayRef);
            displayRadar.setSpeed(0);
            require(AntiRadiationTargeting.target(receiver) == null, "Stopped display radar retained lock");
            displayRadar.setSpeed(16);

            realtimeDelayCheck = true;
            delayTarget = rootRef;
            AntiRadiationTargeting.clear(receiver);
            aim(rootPoint);
            pulse(10);
            delayStartedAt = level.getGameTime();
            require(AntiRadiationTargeting.target(receiver) == null && AntiRadiationTargeting.searching(receiver),
                    "Search locked immediately instead of waiting 1.5 seconds");
            pulse(6);
            require(receiver.getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION) == 1,
                    "Missile fired during acquisition delay");
            playerList().remove(player);
        } catch (Throwable failure) { finish(failure); }
    }

    private static void launchMathChecks() throws Exception {
            for (boolean math : new boolean[]{false, true}) {
                BlockPos rackPos = receiver.getBlockPos().north(3);
                loadRack(rackPos);
                lock(flightTarget);
                com.hooya.stabilizedturret.config.FireControlSettings.saveServerChanges(java.util.Map.of(
                        "flight.trajectoryMode", math ? "MATHEMATICAL" : "ENTITY"));
                captured = null;
                capturing = true;
                try { pulse(6); } finally { capturing = false; }
                require(captured != null && captured.getPersistentData().getBoolean("CreateFireControlAircraftGuided"),
                        "Native missile not launched/adopted in " + (math ? "math" : "entity") + " mode");
                require(MathematicalMissileManager.contains(captured), "Anti-radiation must force mathematical flight even in entity mode");
                require(level.getEntity(captured.getUUID()) == null, "Mathematical missile was registered as a world entity");
                captured.getPersistentData().putString(GuidedMissileMode.ROUTE, "entity");
                require(GuidedMissileMode.mathematical(captured), "Saved entity route bypassed forced mathematical flight");
                require(BindingRef.load(captured.getPersistentData(), "CreateFireControlAntiRadiationTarget").equals(flightTarget),
                        "Emitter reference missing from saved missile");
                require(receiver.getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION) == 0,
                        "Native rack did not consume its missile");
                require(com.hooya.stabilizedturret.network.AntiRadiationStatePayload.from(receiver).searchFrame() != null,
                        "Last missile release removed the empty rack's HUD direction");
                captured.noPhysics = true;
                missiles.add(captured);
            }
            playerList().remove(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (level == null || finished) return;
        try {
            if (realtimeDelayCheck) {
                // FakePlayer has no network channel: expose it only during input authority checks.
                playerList().add(player);
                aim(GroundRadarEmitters.boundPosition(level, delayTarget));
                long elapsed = level.getGameTime() - delayStartedAt;
                if (elapsed < 30) {
                    require(AntiRadiationTargeting.target(receiver) == null && AntiRadiationTargeting.searching(receiver),
                            "Acquired before 30 real server ticks: " + elapsed);
                    require(com.hooya.stabilizedturret.network.AntiRadiationStatePayload.from(receiver).enabled(),
                            "Search frame disappeared during acquisition delay");
                    if (!delayRestarted && elapsed == 10) {
                        pulse(10);
                        require(!AntiRadiationTargeting.enabled(receiver), "Search did not cancel during warmup");
                        pulse(10);
                        delayStartedAt = level.getGameTime();
                        delayRestarted = true;
                    }
                    return;
                }
                require(elapsed == 30 && delayTarget.equals(AntiRadiationTargeting.target(receiver)),
                        "Did not automatically lock at 1.5 seconds with a continuously visible working radar");
                realtimeDelayCheck = false;
                launchMathChecks();
                return;
            }
            ticks++;
            if (ticks == 12) {
                level.removeBlock(flightRadar.getBlockPos().below(), false);
                flightRadar.setSpeed(0);
            }
            for (Entity missile : missiles) {
                if (ticks == 400) {
                    require(missile.isRemoved() && missile.getRemovalReason() == Entity.RemovalReason.DISCARDED,
                            "Anti-radiation missile did not discard after 20 seconds");
                    require(!missile.getPersistentData().getBoolean("CreateFireControlDetonated"), "Timeout caused an explosion");
                    continue;
                }
                require(!missile.isRemoved(), "Missile disappeared before 20 seconds at tick " + ticks
                        + " position=" + missile.position() + " removal=" + missile.getRemovalReason()
                        + " detonated=" + missile.getPersistentData().getBoolean("CreateFireControlDetonated"));
                require(missile.getPersistentData().getInt("CreateFireControlAircraftAge") == ticks, "Flight lifecycle skipped ticks");
                require(MianbaoMissileCompat.getWorldVelocity(missile).length() > 0, "Missile stopped moving");
                if (ticks == 100 || ticks == 399)
                    require(Math.abs(MianbaoMissileCompat.getWorldVelocity(missile).length() - 51.045) < 1e-6,
                            "Missile did not reach/hold Mach 3 at tick " + ticks);
                if (ticks == 5 || ticks == 14) {
                    var states = MianbaoAircraftGuidanceHandler.class.getDeclaredField("GUIDED");
                    states.setAccessible(true);
                    var state = ((java.util.Map<?, ?>) states.get(null)).get(missile);
                    var target = state.getClass().getDeclaredField("targetCenter");
                    target.setAccessible(true);
                    if (ticks == 5) {
                        Vec3 current = GroundRadarEmitters.boundPosition(level, flightTarget);
                        require(current != null && target.get(state) instanceof Vec3 cue && cue.distanceToSqr(current) < 1e-8,
                                "Missile did not follow the operating Sable radar's world position");
                    } else require(target.get(state) == null, "Missile continued receiving a powered-off radar cue");
                }
            }
            if (ticks == 400) {
                require(AntiRadiationTargeting.target(receiver) == null, "HUD lock survived Sable radar shutdown");
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
        finally {
            try { if (player != null) playerList().remove(player); }
            catch (Exception failure) { finish(failure); }
        }
    }

    private static StabilizerControllerBlockEntity computer(BlockPos pos) {
        keepLoaded(pos);
        level.setBlockAndUpdate(pos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var computer = (StabilizerControllerBlockEntity) level.getBlockEntity(pos);
        ControllerTracker.add(computer);
        return computer;
    }
    private static MechanicalBearingBlockEntity bearing(BlockPos pos) {
        keepLoaded(pos);
        level.setBlockAndUpdate(pos.below(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.UP));
        level.setBlockAndUpdate(pos, AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
        level.setBlockAndUpdate(pos.above(), Blocks.STONE.defaultBlockState());
        var bearing = (MechanicalBearingBlockEntity) level.getBlockEntity(pos);
        bearing.setSpeed(16);
        bearing.assemble();
        bearing.setSpeed(16);
        require(bearing.isRunning() && bearing.getMovedContraption() != null, "Real mechanical radar bearing assembly failed");
        require(Math.abs(bearing.getAngularSpeed()) > 1e-3,
                "Fixture radar did not spin: speed=" + bearing.getSpeed() + " angle=" + bearing.getAngularSpeed());
        return bearing;
    }
    private static void loadRack(BlockPos pos) {
        var rack = BuiltInRegistries.BLOCK.get(id("antiradiationmissilerack")).defaultBlockState();
        rack = rack.hasProperty(BlockStateProperties.FACING) ? rack.setValue(BlockStateProperties.FACING, Direction.NORTH)
                : rack.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        level.setBlockAndUpdate(pos, rack);
        receiver.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, BindingRef.capture(level, pos));
        require(receiver.getAircraftAmmoCount(MianbaoAircraftCompat.WeaponType.ANTI_RADIATION) == 1,
                "Anti-radiation rack not bound/countable");
    }
    private static void keepLoaded(BlockPos pos) {
        level.getChunk(pos);
        if (Sable.HELPER.getContaining(level, pos) == null)
            level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
    }
    private static void lock(BindingRef radar) throws Exception {
        AntiRadiationTargeting.clear(receiver);
        Vec3 point = GroundRadarEmitters.boundPosition(level, radar);
        require(point != null, "Bound ground emitter not discovered: " + radar.pos()
                + " loaded=" + level.hasChunkAt(radar.pos()) + " resolve=" + radar.resolve(level)
                + " emitting=" + GroundRadarEmitters.isEmitting(level, radar)
                + " active=" + GroundRadarEmitters.active(level));
        aim(point);
        require(AntiRadiationTargeting.target(receiver) == null && !AntiRadiationTargeting.searching(receiver),
                "A visible radar was acquired without pressing air-to-ground lock");
        pulse(10);
        require(radar.equals(AntiRadiationTargeting.target(receiver)), "Visible ground emitter not acquired");
    }
    private static void aim(Vec3 point) {
        Vec3 origin = player.position(), forward = point.subtract(origin).normalize();
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize(), up = right.cross(forward).normalize();
        require(AntiRadiationTargeting.updateFrame(receiver, player,
                new AntiRadiationTargeting.Frame(origin, forward, right, up, 0.03, 2560.0 / 1440)), "Authorised camera frame rejected");
    }
    private static void pulse(int action) throws Exception {
        var method = StabilizerControllerBlockEntity.class.getDeclaredMethod("receiverForChannel", int.class);
        method.setAccessible(true);
        var receiverInput = (WirelessPulseReceiver) method.invoke(receiver, action);
        receiverInput.setReceivedStrength(15); receiverInput.setReceivedStrength(0);
        if (action == 10 && !realtimeDelayCheck && AntiRadiationTargeting.enabled(receiver)) {
            // The startup geometry/backend scenarios run synchronously. Timing is checked separately
            // over real server ticks above; only those setup scenarios skip the waiting period.
            var states = AntiRadiationTargeting.class.getDeclaredField("STATES");
            states.setAccessible(true);
            var state = ((java.util.Map<?, ?>) states.get(null)).get(receiver);
            var started = state.getClass().getDeclaredField("searchStartedAt");
            started.setAccessible(true);
            started.setLong(state, level.getGameTime() - AntiRadiationTargeting.SEARCH_DELAY_TICKS);
        }
    }
    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> playerList() throws Exception {
        var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
        field.setAccessible(true);
        return (List<ServerPlayer>) field.get(level.getServer().getPlayerList());
    }
    private static void finish(Throwable failure) {
        if (finished) return;
        finished = true;
        try { if (player != null) playerList().remove(player); } catch (Exception ignored) { }
        String result = failure == null ? "PASS: real 30-tick/1.5s acquisition delay; cancellation/re-enable restarts delay; camera updates do not reset timer; no early lock/fire; search frame stays enabled; automatic lock at tick 30; aircraft-axis envelope; nearest reticle/flight-circle real radar selection; stable lock; computer/display/Sable emitters; forced math; Mach 3 at tick 100 and 399; discard at tick 400; shutdown removes cues"
                : "FAIL: " + failure;
        if (failure != null) StabilizedTurret.LOGGER.error(result, failure);
        else StabilizedTurret.LOGGER.info(result);
        try { Files.writeString(Path.of("anti-radiation-result.txt"), result); } catch (Exception ex) { throw new RuntimeException(ex); }
        level.getServer().halt(false);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MianbaoAircraftCompat.MOD_ID, path); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
