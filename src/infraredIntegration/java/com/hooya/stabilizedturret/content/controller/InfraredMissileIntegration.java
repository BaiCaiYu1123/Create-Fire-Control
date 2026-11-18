package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.iff.IffIdentification;
import com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native rack launches and production seeker state; never included in the distribution. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class InfraredMissileIntegration {
    private static ServerLevel level;
    private static int ticks, launchAt;
    private static boolean capture, done;
    private static Entity captured;
    private static Throwable failure;
    private static final List<Fixture> fixtures = new ArrayList<>();
    private static final List<String> csv = new ArrayList<>(List.of("variant,age,entityX,entityY,entityZ,mathX,mathY,mathZ,closest"));
    private static final class Fixture {
        int variant;
        ServerSubLevel body;
        Vec3 origin, previousTarget;
        Entity[] missiles = new Entity[2];
        Vec3[] previous = new Vec3[2];
        StabilizerControllerBlockEntity[] controllers = new StabilizerControllerBlockEntity[2];
        double closest = Double.POSITIVE_INFINITY;
        boolean complete;
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (!Boolean.getBoolean("create_fire_control.infraredClientChecks")) level = event.getServer().overworld();
    }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void capture(EntityJoinLevelEvent event) {
        if (capture && MianbaoAircraftCompat.isInfrared(MianbaoAircraftCompat.typeOf(event.getEntity()))) captured = event.getEntity();
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void move(ServerTickEvent.Post event) {
        if (level == null || done || failure != null) return;
        for (Fixture fixture : fixtures) if (!fixture.complete) {
            double phase = Math.max(0, ticks - launchAt - 20) * .04;
            Vec3 position = fixture.origin.add(0, 0, 25 * Math.sin(phase));
            var pose = fixture.body.logicalPose();
            // Each target is assembled from one block, so its bounding centre
            // and pose translation retain the same fixed offset.
            Vec3 delta = position.subtract(StabilizerControllerBlockEntity.subLevelWorldBounds(fixture.body).getCenter());
            Vector3d moved = new Vector3d(pose.position()).add(delta.x, delta.y, delta.z);
            var physics = SubLevelPhysicsSystem.require(level).getPipeline();
            physics.teleport(fixture.body, moved, pose.orientation()); physics.resetVelocity(fixture.body);
            pose.position().set(moved); fixture.body.updateLastPose(); fixture.body.updateBoundingBox(); fixture.body.forceUpdateGlobalBounds();
        }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (level == null || done) return;
        try {
            ticks++;
            if (failure != null) { if (ticks > launchAt + 240) finish(); return; }
            if (ticks == 5) for (int variant = 1; variant <= 2; variant++) create(variant);
            if (ticks == 15) {
                for (Fixture fixture : fixtures) for (int mode = 0; mode < 2; mode++) launch(fixture, mode);
                launchAt = ticks;
            }
            if (ticks <= 15) return;
            int age = ticks - launchAt;
            for (Fixture fixture : fixtures) if (!fixture.complete) {
                Vec3 target = StabilizerControllerBlockEntity.subLevelWorldBounds(fixture.body).getCenter();
                if (age == 5) for (var controller : fixture.controllers) {
                    ControllerTracker.remove(controller); level.removeBlock(controller.getBlockPos(), false);
                }
                if (age == 30) for (Entity missile : fixture.missiles) restore(missile);
                for (int mode = 0; mode < 2; mode++) {
                    Entity missile = fixture.missiles[mode];
                    Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
                    Vec3 relative = fixture.previousTarget.subtract(fixture.previous[mode]);
                    Vec3 step = target.subtract(position).subtract(relative);
                    double f = Math.clamp(-relative.dot(step) / Math.max(1e-12, step.lengthSqr()), 0, 1);
                    fixture.closest = Math.min(fixture.closest, relative.add(step.scale(f)).length());
                    require(!missile.getPersistentData().contains(ActiveRadarSeeker.TAG), "IR shot contains radar seeker state");
                    require(!missile.isRemoved(), "missile ended before interception");
                    fixture.previous[mode] = position;
                }
                require(fixture.previous[0].distanceTo(fixture.previous[1]) < 1e-6, "entity/math trajectory mismatch");
                csv.add(fixture.variant + "," + age + "," + xyz(fixture.previous[0]) + "," + xyz(fixture.previous[1]) + "," + fixture.closest);
                if (fixture.closest < 3) {
                    fixture.complete = true;
                    for (Entity missile : fixture.missiles) missile.discard();
                    release(fixture);
                }
                fixture.previousTarget = target;
            }
            if (fixtures.stream().allMatch(value -> value.complete)) {
                if (age > 130) finish();
            } else require(age < 180, "IR interception timed out");
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("INFRARED CHECK FAILED", error);
            for (Fixture fixture : fixtures) { for (Entity missile : fixture.missiles) if (missile != null) missile.discard(); release(fixture); }
            try { finish(); } catch (Exception reporting) { throw new RuntimeException(reporting); }
        }
    }
    private static void create(int variant) throws Exception {
        Fixture fixture = new Fixture(); fixture.variant = variant;
        BlockPos anchor = new BlockPos(4100, 220, 6000 + variant * 512);
        level.getChunk(anchor);
        level.setBlockAndUpdate(anchor, ModBlocks.IFF_TRANSPONDER.get().defaultBlockState());
        ((IffTransponderBlockEntity) level.getBlockEntity(anchor)).setChannel("IRTEST");
        var assembly = dev.simulated_team.simulated.util.SimAssemblyHelper.assembleFromSingleBlock(level, anchor.below(), anchor, false, false);
        require(assembly != null, "target assembly failed");
        fixture.body = (ServerSubLevel) assembly.subLevel();
        ((ServerSubLevelContainer) SubLevelContainer.getContainer(level)).addForceLoadTicket(fixture.body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        fixture.body.forceUpdateGlobalBounds();
        var transponder = (IffTransponderBlockEntity) fixture.body.getLevel().getBlockEntity(anchor.offset(assembly.offset()));
        require(transponder != null, "transponder not moved into target plot");
        transponder.onLoad(); transponder.setChannel("IRTEST");
        BlockPos localTransponder = anchor.offset(assembly.offset());
        BlockPos localComputer = localTransponder.east();
        fixture.body.getLevel().setBlockAndUpdate(localComputer, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var targetComputer = (StabilizerControllerBlockEntity) fixture.body.getLevel().getBlockEntity(localComputer);
        targetComputer.bind(BindingSlot.IFF_TRANSPONDER, BindingRef.capture(fixture.body.getLevel(), localTransponder));
        ControllerTracker.add(targetComputer);
        fixture.body.forceUpdateGlobalBounds();
        fixture.origin = StabilizerControllerBlockEntity.subLevelWorldBounds(fixture.body).getCenter();
        fixture.previousTarget = fixture.origin;
        fixtures.add(fixture);
    }
    private static void launch(Fixture fixture, int mode) throws Exception {
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(), "cfc missile mode " + (mode == 1 ? "math" : "entity"));
        BlockPos rackPos = new BlockPos(3000, 220, 6000 + fixture.variant * 512);
        level.getChunk(rackPos);
        BlockPos computerPos = rackPos.west(4);
        level.setBlockAndUpdate(computerPos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var computer = (StabilizerControllerBlockEntity) level.getBlockEntity(computerPos);
        var rackState = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mianbaos_modernwarfare:close_missile_" + fixture.variant + "rack")).defaultBlockState();
        rackState = rackState.hasProperty(BlockStateProperties.FACING) ? rackState.setValue(BlockStateProperties.FACING, Direction.EAST)
                : rackState.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.setBlockAndUpdate(rackPos, rackState);
        var rack = BindingRef.capture(level, rackPos); computer.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, rack);
        select(computer, MianbaoAircraftCompat.WeaponType.CLOSE);
        set(computer, "aircraftFuseSafetySeconds", 10.0); set(computer, "aircraftProximityFuseRange", 0.0);
        ControllerTracker.add(computer);
        require(!computer.isAircraftRadarActive(), "fixture unexpectedly has aircraft radar");
        require(IffIdentification.sharesEnabledChannel(level, Set.of("IRTEST"), Set.of(fixture.body.getUniqueId())), "target is not identified friendly by same-channel IFF");
        double tangent = Math.tan(Math.toRadians(35));
        var initial = new InfraredTargeting.Frame(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 1, 0), tangent, 16.0 / 9);
        double markerX = mode == 0 ? 1664 : 900, markerY = mode == 0 ? 420 : 750;
        Vec3 direction = initial.direction(markerX, markerY);
        var camera = new InfraredTargeting.Frame(fixture.origin.subtract(direction.scale(1000)), initial.forward(), initial.right(), initial.up(), tangent, initial.aspect());
        var frame = camera.withSeeker(markerX, markerY);
        var acquire = InfraredTargeting.class.getDeclaredMethod("acquire", StabilizerControllerBlockEntity.class, InfraredTargeting.Frame.class, long.class);
        acquire.setAccessible(true);
        rangeAndHemisphereChecks(computer, fixture, acquire);
        require(acquire.invoke(null, computer, camera, level.getGameTime()) == null, "old fixed acquisition circle unexpectedly sees the off-centre physical target");
        BlockPos corridor = BlockPos.containing(frame.origin().lerp(fixture.origin, .5));
        boolean unloadedCorridor = level.getChunkSource().getChunkNow(corridor.getX() >> 4, corridor.getZ() >> 4) == null;
        var track = (InfraredSeeker.Track) acquire.invoke(null, computer, frame, level.getGameTime());
        require(!unloadedCorridor || level.getChunkSource().getChunkNow(corridor.getX() >> 4, corridor.getZ() >> 4) == null,
                "infrared acquisition loaded a terrain corridor");
        require(track != null && track.members().contains(fixture.body.getUniqueId()), "physical-body acquisition failed with radar absent and friendly IFF");
        seed(computer, frame, track);
        require(computer.isAircraftWeaponGuidanceReady(MianbaoAircraftCompat.WeaponType.CLOSE), "IR launch not ready without radar");
        // Leaving and re-entering the weapon must invalidate both memory and
        // its selection epoch, even when no game tick occurs in between.
        int epoch = computer.getInfraredSelectionEpoch();
        select(computer, MianbaoAircraftCompat.WeaponType.MEDIUM);
        require(!computer.isInfraredWeaponSelected() && InfraredTargeting.target(computer) == null, "switch did not clear/hide IR");
        select(computer, MianbaoAircraftCompat.WeaponType.CLOSE);
        require(InfraredTargeting.target(computer) == null && computer.getInfraredSelectionEpoch() != epoch, "return restored previous lock");
        seed(computer, frame, track);
        var lookingAway = new InfraredTargeting.Frame(frame.origin(), frame.forward().scale(-1), frame.right().scale(-1), frame.up(), tangent, frame.aspect())
                .withAircraft(frame.aircraftOrigin(), frame.aircraftForward());
        seed(computer, lookingAway, track); evaluateAgain(computer);
        require(InfraredTargeting.target(computer) != null, "free-look incorrectly moved the aircraft's acquisition cone");
        var outside = frame.withAircraft(frame.aircraftOrigin(), frame.aircraftForward().scale(-1));
        seed(computer, outside, track); evaluateAgain(computer);
        require(InfraredTargeting.target(computer) == null && InfraredTargeting.lostAt(computer) == level.getGameTime(), "outside-circle lock was not lost");
        BlockPos reservePos = rackPos.north(3);
        level.setBlockAndUpdate(reservePos, rackState);
        computer.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, BindingRef.capture(level, reservePos));
        seed(computer, frame, track);
        int launchEpoch = computer.getInfraredSelectionEpoch();
        capture = true; captured = null;
        try { require(MianbaoAircraftCompat.fireOne(level, rack, MianbaoAircraftCompat.WeaponType.CLOSE), "native rack did not consume and launch"); }
        finally { capture = false; }
        Entity missile = captured;
        require(missile != null && missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided"), "native IR missile was not adopted");
        require(MathematicalMissileManager.contains(missile) == (mode == 1), "wrong math/entity route");
        require(missile.getPersistentData().contains(InfraredSeeker.TAG) && !missile.getPersistentData().contains(ActiveRadarSeeker.TAG), "wrong seeker assigned");
        require(computer.getInfraredSelectionEpoch() != launchEpoch && InfraredTargeting.target(computer) == null,
                "successful launch did not immediately clear the launcher and invalidate old frames");
        var launcherHud = com.hooya.stabilizedturret.network.InfraredStatePayload.from(computer);
        require(launcherHud.selected() && !launcherHud.locked() && launcherHud.target() == null && launcherHud.lostAt() == Long.MIN_VALUE,
                "post-launch HUD must immediately return to green search, without a lost-lock animation");
        require(launcherHud.ammo() == 1, "remaining reserve round was not counted");
        require(launcherHud.aircraftForward().dot(new Vec3(1, 0, 0)) > .999,
                "empty rack lost the aircraft nose direction after firing");
        require(missile.getPersistentData().getCompound(InfraredSeeker.TAG).getUUID("Target").equals(track.id()),
                "resetting the launcher erased the launched missile's independent target");
        level.removeBlock(reservePos, false);
        var empty = com.hooya.stabilizedturret.network.InfraredStatePayload.from(computer);
        require(!empty.selected() && empty.ammo() == 0 && !empty.locked() && empty.target() == null,
                "empty IR weapon still exposed a range/search/lock HUD");
        int emptyEpoch = empty.epoch();
        level.setBlockAndUpdate(reservePos, rackState);
        computer.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, BindingRef.capture(level, reservePos));
        var reloaded = com.hooya.stabilizedturret.network.InfraredStatePayload.from(computer);
        require(reloaded.selected() && reloaded.epoch() != emptyEpoch && !reloaded.locked() && reloaded.target() == null,
                "reloading restored an old lock instead of a fresh search");
        level.removeBlock(reservePos, false);
        com.hooya.stabilizedturret.network.InfraredStatePayload.from(computer);
        missile.noPhysics = true; if (missile instanceof AbstractArrow arrow) arrow.setNoPhysics(true);
        fixture.missiles[mode] = missile; fixture.previous[mode] = MianbaoMissileCompat.getWorldPosition(missile);
        fixture.controllers[mode] = computer;
    }
    private static void seed(StabilizerControllerBlockEntity computer, InfraredTargeting.Frame frame, InfraredSeeker.Track track) throws Exception {
        require(InfraredTargeting.selected(computer), "cannot seed an unarmed IR weapon");
        Class<?> type = Class.forName(InfraredTargeting.class.getName() + "$State");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); Object state = constructor.newInstance();
        set(state, "frame", frame); set(state, "receivedAt", level.getGameTime()); set(state, "evaluatedAt", level.getGameTime());
        set(state, "track", track); set(state, "lastPosition", track.center());
        Field states = InfraredTargeting.class.getDeclaredField("STATES"); states.setAccessible(true);
        ((Map) states.get(null)).put(computer, state);
    }
    private static void rangeAndHemisphereChecks(StabilizerControllerBlockEntity computer, Fixture fixture, java.lang.reflect.Method acquire) throws Exception {
        Vec3 source = computer.getInfraredAircraftOrigin();
        try {
            for (double range : new double[]{1999, 2000, 2000.01, 2001}) {
                moveTarget(fixture, source.add(range, 0, 0));
                for (double fov : new double[]{.5, 1.4}) {
                    var view = new InfraredTargeting.Frame(source.add(400, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1),
                            new Vec3(0, 1, 0), fov, 16.0 / 9, 1280, 720)
                            .withAircraft(source.add(900, 0, 0), new Vec3(1, 0, 0));
                    var acquired = (InfraredSeeker.Track) acquire.invoke(null, computer, view, level.getGameTime());
                    require((acquired != null && acquired.members().contains(fixture.body.getUniqueId())) == (range <= 2000),
                            "physical range gate used camera/client origin: " + range + "/" + fov + " actual=" + acquired);
                    var observed = InfraredSeeker.observe(level, fixture.body.getUniqueId(), Set.of(fixture.body.getUniqueId()), level.getGameTime());
                    seed(computer, view, observed);
                    require((InfraredTargeting.target(computer) != null) == (range <= 2000), "cached tracking escaped 2km: " + range);
                    require((InfraredTargeting.launchTarget(computer) != null) == (range <= 2000), "launch gate escaped 2km: " + range);
                }
            }
            var view = new InfraredTargeting.Frame(source, new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 1, 0), .7, 16.0 / 9, 1280, 720);
            moveTarget(fixture, source.add(1999, 0, 0));
            seed(computer, view, InfraredSeeker.observe(level, fixture.body.getUniqueId(), Set.of(fixture.body.getUniqueId()), level.getGameTime()));
            moveTarget(fixture, source.add(2001, 0, 0));
            require(InfraredTargeting.launchTarget(computer) == null, "same-tick physical movement reused a stale in-range launch sample");
            for (double angle : new double[]{-90, -89.99, -65, 0, 65, 89.99, 90, 90.01, -90.01, 180}) {
                Vec3 direction = new Vec3(Math.cos(Math.toRadians(angle)), 0, Math.sin(Math.toRadians(angle)));
                moveTarget(fixture, source.add(direction.scale(1000)));
                Vec3 camera = source.add(direction.scale(20)).add(10, 3, 0);
                Vec3 look = source.add(direction.scale(1000)).subtract(camera).normalize();
                Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
                var looking = new InfraredTargeting.Frame(camera, look, right,
                        right.cross(look).normalize(), .7, 16.0 / 9, 1280, 720).withAircraft(source, new Vec3(1, 0, 0));
                boolean found = acquire.invoke(null, computer, looking, level.getGameTime()) != null;
                Vec3 actualCenter = StabilizerControllerBlockEntity.subLevelWorldBounds(fixture.body).getCenter();
                require(found == (Math.abs(angle) <= 90), "physical forward hemisphere capture failed: " + angle
                        + " found=" + found + " source=" + source + " actual=" + actualCenter + " delta=" + actualCenter.subtract(source)
                        + " inRange=" + looking.inRange(actualCenter) + " projected=" + looking.project(actualCenter)
                        + " limited=" + looking.limitSeeker(1280, 720) + " clip=" + InfraredRaycast.clip(level, looking.origin(),
                        looking.origin().add(direction.scale(2100)), computer.getOwnControlledSubLevelIds()));
            }
        } finally { moveTarget(fixture, fixture.origin); InfraredTargeting.clear(computer); }
    }
    private static void moveTarget(Fixture fixture, Vec3 center) {
        var pose = fixture.body.logicalPose();
        Vec3 delta = center.subtract(StabilizerControllerBlockEntity.subLevelWorldBounds(fixture.body).getCenter());
        Vector3d moved = new Vector3d(pose.position()).add(delta.x, delta.y, delta.z);
        var physics = SubLevelPhysicsSystem.require(level).getPipeline();
        physics.teleport(fixture.body, moved, pose.orientation()); physics.resetVelocity(fixture.body);
        pose.position().set(moved); fixture.body.updateLastPose(); fixture.body.updateBoundingBox(); fixture.body.forceUpdateGlobalBounds();
    }
    private static void evaluateAgain(StabilizerControllerBlockEntity computer) throws Exception {
        Field states = InfraredTargeting.class.getDeclaredField("STATES"); states.setAccessible(true);
        set(((Map) states.get(null)).get(computer), "evaluatedAt", Long.MIN_VALUE);
    }
    private static void select(StabilizerControllerBlockEntity computer, MianbaoAircraftCompat.WeaponType type) throws Exception {
        var method = StabilizerControllerBlockEntity.class.getDeclaredMethod("selectAircraftWeapon", MianbaoAircraftCompat.WeaponType.class);
        method.setAccessible(true); method.invoke(computer, type);
    }
    private static void set(Object owner, String key, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(key); field.setAccessible(true); field.set(owner, value);
    }
    private static void restore(Entity missile) throws Exception {
        Field field = MianbaoAircraftGuidanceHandler.class.getDeclaredField("GUIDED"); field.setAccessible(true);
        Object state = ((Map) field.get(null)).remove(missile);
        require(state != null, "missing flight guidance before restore");
        Field tickets = state.getClass().getDeclaredField("chunkTickets"); tickets.setAccessible(true); ((MissileChunkTickets) tickets.get(state)).release();
        MianbaoAircraftGuidanceHandler.onJoin(new EntityJoinLevelEvent(missile, missile.level()));
        require(((Map) field.get(null)).containsKey(missile), "passive missile did not restore without its controller");
    }
    private static void release(Fixture fixture) {
        ((ServerSubLevelContainer) SubLevelContainer.getContainer(level)).removeForceLoadTicket(fixture.body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
    }
    private static String xyz(Vec3 point) { return point.x + "," + point.y + "," + point.z; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void finish() throws Exception {
        done = true;
        Files.write(Path.of("infrared-trajectories.csv"), csv);
        String result = failure == null ? "PASS: both native IR variants, physical +/-90 hemisphere capture, authoritative 1999/2000/2000.01/2001m acquisition/tracking/launch, same-tick launch resample, empty HUD hide and fresh reload, four real launches, independent entity/math interception and ownerless restore" : "FAIL: " + failure;
        Files.writeString(Path.of("infrared-integration-result.txt"), result);
        StabilizedTurret.LOGGER.info(result);
        level.getServer().halt(false);
    }
}
