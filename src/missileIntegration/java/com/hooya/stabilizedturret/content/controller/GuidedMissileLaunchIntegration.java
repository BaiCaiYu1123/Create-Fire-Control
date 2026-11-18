package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Actual rack launch, real computer/radar binding and production guidance in both modes. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class GuidedMissileLaunchIntegration {
    private record Weapon(String rack, MianbaoAircraftCompat.WeaponType type) {}
    private static final List<Weapon> WEAPONS = List.of(
            new Weapon("far_missile_1rack", MianbaoAircraftCompat.WeaponType.FAR),
            new Weapon("far_missile_2rack", MianbaoAircraftCompat.WeaponType.FAR),
            new Weapon("medium_missile_rack", MianbaoAircraftCompat.WeaponType.MEDIUM),
            new Weapon("medium_ai_mmissile_2rack", MianbaoAircraftCompat.WeaponType.MEDIUM),
            new Weapon("close_missile_1rack", MianbaoAircraftCompat.WeaponType.CLOSE),
            new Weapon("close_missile_2rack", MianbaoAircraftCompat.WeaponType.CLOSE),
            new Weapon("mica_em_rack", MianbaoAircraftCompat.WeaponType.MICA_EM),
            new Weapon("aim_120_rack", MianbaoAircraftCompat.WeaponType.AIM_120D),
            new Weapon("agm_missile_1rack", MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT),
            new Weapon("agm_missile_3rack", MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_DIRECT),
            new Weapon("agm_missile_2rack", MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK),
            new Weapon("agm_missile_4rack", MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK),
            new Weapon("laser_guided_missile_rack", MianbaoAircraftCompat.WeaponType.LASER_GUIDED));
    private static final List<String> AA_LAUNCHERS = List.of(
            "antiairmissilelauncherblock", "antiairmissilelauncherleft", "antiairmissilelauncherright",
            "antiairmissilelauncherthird_h_1", "antiairmissilelauncherthird_h_2",
            "antiairmissilelauncherthird_h_1sand", "antiairmissilelauncherthird_h_2sand");
    private static final List<Shot> SHOTS = new ArrayList<>();
    private static final List<String> CSV = new ArrayList<>(List.of("scenario,mode,age,x,y,z,vx,vy,vz,turnDegrees"));
    private static Entity captured;
    private static boolean capturing;
    private static ServerLevel level;
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel carrier;
    private static String captureKey;
    private static boolean mathCapture;
    private static boolean decoyLaunch;
    private static Vec3 nativeVelocity;
    record ActiveCue(UUID id, Vec3 position, Vec3 velocity, Set<UUID> members) {}
    private static ActiveCue activeCue;
    private static final Map<String, Vec3> NATIVE_SAMPLES = new HashMap<>();
    private record Feed(StabilizerControllerBlockEntity computer, UUID id, Vec3 target, Vec3 velocity, long start) {}
    private static final List<Feed> FEEDS = new ArrayList<>();
    private record Shot(String name, Entity entity, boolean math, Vec3 axis, Vec3[] previous,
                        MianbaoAircraftCompat.WeaponType type, String ageTag) {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void sampleNativeLaunch(EntityJoinLevelEvent event) {
        if (!capturing || !MianbaoAirDefenseCompat.isAnyFireControlMissile(event.getEntity())) return;
        // Pair the same genuine native scatter sample across backends; two
        // separately randomised launches are not a trajectory equivalence test.
        nativeVelocity = MianbaoMissileCompat.getWorldVelocity(event.getEntity());
        if (!mathCapture) NATIVE_SAMPLES.put(captureKey, nativeVelocity);
        else {
            nativeVelocity = NATIVE_SAMPLES.get(captureKey);
            MianbaoMissileCompat.setWorldVelocity(event.getEntity(), nativeVelocity);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void capture(EntityJoinLevelEvent event) {
        if (capturing && MianbaoAirDefenseCompat.isAnyFireControlMissile(event.getEntity())) captured = event.getEntity();
    }

    static void start(ServerLevel root) throws Exception {
        level = root;
        int index = 0;
        for (Weapon weapon : WEAPONS) for (Direction facing : List.of(Direction.EAST, Direction.NORTH)) {
            BlockPos pos = new BlockPos(512 + index++ * 32, 100, 4096);
            for (boolean math : new boolean[]{false, true}) launch(pos, facing, math, false, weapon);
        }
        Weapon aim = WEAPONS.get(7);
        for (boolean math : new boolean[]{false, true}) launch(new BlockPos(1408, 100, 4096), Direction.WEST, math, true, aim);
        BlockPos anchor = new BlockPos(1500, 100, 4096);
        level.setBlockAndUpdate(anchor, net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
        var assembly = dev.simulated_team.simulated.util.SimAssemblyHelper.assembleFromSingleBlock(
                level, anchor.below(), anchor, false, false);
        require(assembly != null, "real Sable carrier assembly failed");
        var body = assembly.subLevel();
        carrier = (dev.ryanhcode.sable.sublevel.ServerSubLevel) body;
        ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)).addForceLoadTicket(
                (dev.ryanhcode.sable.sublevel.ServerSubLevel) body,
                dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                net.minecraft.util.Unit.INSTANCE);
        BlockPos plot = anchor.offset(assembly.offset()).above(2);
        for (Weapon weapon : List.of(WEAPONS.get(0), WEAPONS.get(2), WEAPONS.get(4), WEAPONS.get(6), aim)) {
            for (boolean math : new boolean[]{false, true}) launch(plot, Direction.EAST, math, false, weapon);
        }
        body.forceUpdateGlobalBounds();
        for (String launcherPath : AA_LAUNCHERS)
        for (boolean display : new boolean[]{false, true}) for (Direction facing : List.of(Direction.EAST, Direction.NORTH)) {
            BlockPos pos = new BlockPos(512 + index++ * 32, 100, 4608);
            UUID syntheticTarget = UUID.randomUUID(); // No world body exists at this synthetic parity-only cue.
            for (boolean math : new boolean[]{false, true}) launchAa(pos, facing, math, display, syntheticTarget, launcherPath);
        }
        decoyLaunch = true;
        for (Weapon weapon : List.of(WEAPONS.get(0), WEAPONS.get(2), WEAPONS.get(4), WEAPONS.get(6), aim)) {
            BlockPos pos = new BlockPos(512 + index++ * 32, 100, 4864);
            for (boolean math : new boolean[]{false, true}) launch(pos, Direction.EAST, math, false, weapon);
        }
        UUID syntheticDecoyTarget = UUID.randomUUID();
        for (boolean math : new boolean[]{false, true}) launchAa(new BlockPos(1536, 100, 4864), Direction.EAST, math, false,
                syntheticDecoyTarget, AA_LAUNCHERS.getFirst());
        decoyLaunch = false;
        Files.write(Path.of("guided-missile-launch-trajectories.csv"), CSV);
        StabilizedTurret.LOGGER.info("GUIDED LAUNCH CHECK: {} actual non-IR launches, Sable racks, all 7 AA launcher inventories with computer and display guidance; both IR variants verified separately", SHOTS.size());
    }

    private static void launch(BlockPos rackPos, Direction facing, boolean math, boolean moving, Weapon weapon) throws Exception {
        // Infrared variants now require physical-body acquisition rather than
        // the synthetic radar cue used here. Their real launches and parity
        // checks live in the independent InfraredMissileIntegration fixture.
        if (MianbaoAircraftCompat.isInfrared(weapon.type)) return;
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(),
                "cfc missile mode " + (math ? "math" : "entity"));
        BlockPos computerPos = rackPos.west(4), radarPos = rackPos.west(6);
        level.setBlock(computerPos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState(), 3);
        level.setBlock(radarPos, BuiltInRegistries.BLOCK.get(id("jetradar")).defaultBlockState(), 3);
        var computer = (StabilizerControllerBlockEntity) level.getBlockEntity(computerPos);
        if (!MianbaoAircraftCompat.isAirToAir(weapon.type)) {
            level.removeBlockEntity(computerPos);
            computer = new TargetFeedComputer(computerPos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            level.setBlockEntity(computer);
        }
        var rackState = BuiltInRegistries.BLOCK.get(id(weapon.rack)).defaultBlockState();
        rackState = rackState.hasProperty(BlockStateProperties.FACING)
                ? rackState.setValue(BlockStateProperties.FACING, facing)
                : rackState.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        level.setBlock(rackPos, rackState, 3);
        var rack = BindingRef.capture(level, rackPos);
        computer.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, rack);
        computer.bind(BindingSlot.AIRCRAFT_RADAR, BindingRef.capture(level, radarPos));
        set(computer, "selectedAircraftWeapon", weapon.type);
        Vec3 muzzle = MianbaoAircraftCompat.muzzle(level, rack);
        Vec3 axis = MianbaoAircraftCompat.launchDirection(level, rack);
        require(axis != null, weapon.rack + " fixture not resolved at " + rackPos + " state=" + level.getBlockState(rackPos));
        Vec3 carrier = MianbaoAircraftCompat.carrierVelocityAtMuzzle(level, rack);
        Vec3 transverse = axis.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 target = muzzle.add(axis.scale(3000)).add(transverse.scale(5196));
        if (rack.subLevelId() != null) target = new Vec3(target.x, muzzle.y, target.z);
        Vec3 targetVelocity = activeCue != null ? activeCue.velocity : moving ? new Vec3(.2, 0, 2) : Vec3.ZERO;
        UUID targetId = activeCue != null ? activeCue.id : UUID.randomUUID();
        if (activeCue != null) target = activeCue.position;
        Feed feed = new Feed(computer, targetId, target, targetVelocity, level.getGameTime());
        if (activeCue == null) FEEDS.add(feed);
        supply(feed);
        ControllerTracker.add(computer);
        if (activeCue != null) set(computer, "aircraftProximityFuseRange", 0.0);
        require(computer.isAircraftWeaponGuidanceReady(weapon.type), weapon.rack + " target/binding not ready");
        captureKey = weapon.rack + "-" + facing + (rack.subLevelId() != null ? "-sable" : "") + (moving ? "-moving" : "") + (decoyLaunch ? "-decoy" : "");
        captured = null; capturing = true; mathCapture = math;
        try { require(MianbaoAircraftCompat.fireOne(level, rack, weapon.type), weapon.rack + " native rack did not fire/consume round"); }
        finally { capturing = false; }
        Entity missile = captured;
        require(missile != null && missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided"), weapon.rack + " not adopted");
        require(MianbaoAircraftCompat.typeOf(missile) == weapon.type, weapon.rack + " wrong native missile family");
        require(MathematicalMissileManager.contains(missile) == math, "wrong flight route");
        Vec3 initial = MianbaoMissileCompat.getWorldVelocity(missile);
        Vec3 expected = carrier.add(MianbaoAircraftCompat.isAirToAir(weapon.type) ? axis.scale(2.5) : nativeVelocity);
        require(initial.distanceToSqr(expected) < 1e-10, weapon.rack + " launch must inherit carrier once and preserve native motor vector");
        missile.noPhysics = true; // Co-located mode comparisons must not hit one another.
        if (activeCue == null) SHOTS.add(new Shot(captureKey, missile, math, initial.normalize(), new Vec3[]{initial}, weapon.type, "CreateFireControlAircraftAge"));
    }

    static Entity launchActiveAircraft(BlockPos pos, boolean math, MianbaoAircraftCompat.WeaponType type,
                                       ActiveCue cue) throws Exception {
        activeCue = cue;
        try {
            Weapon weapon = WEAPONS.stream().filter(value -> value.type == type).findFirst().orElseThrow();
            launch(pos, Direction.EAST, math, false, weapon);
            return captured;
        } finally { activeCue = null; }
    }

    static Entity launchActiveAa(BlockPos pos, boolean math, boolean display, ActiveCue cue) throws Exception {
        activeCue = cue;
        try { launchAa(pos, Direction.EAST, math, display, cue.id, AA_LAUNCHERS.getFirst()); return captured; }
        finally { activeCue = null; }
    }

    /** Only the radar contact is seeded; native launch, adoption and flight remain production code. */
    private static void supply(Feed feed) throws Exception {
        Vec3 center = feed.target.add(feed.velocity.scale(level.getGameTime() - feed.start));
        if (feed.computer instanceof TargetFeedComputer computer) {
            computer.point = center; computer.targetId = feed.id;
        }
        var contact = new StabilizerControllerBlockEntity.RadarContact(feed.id, center, feed.velocity,
                new AABB(center, center).inflate(2), Set.of(feed.id));
        set(feed.computer, "radarContacts", List.of(contact));
        set(feed.computer, "aircraftSelectedTargetId", feed.id);
        set(feed.computer, "lastRadarScanTick", level.getGameTime());
    }

    @SubscribeEvent public static void before(ServerTickEvent.Pre event) throws Exception {
        if (level != null) for (Feed feed : FEEDS) if (!feed.computer.isRemoved()) supply(feed);
    }

    static void tick(int age) throws Exception {
        try {
            for (Shot shot : SHOTS) {
                Entity missile = shot.entity;
                require(!missile.isRemoved(), shot.name + " removed at " + age);
                Vec3 pos = MianbaoMissileCompat.getWorldPosition(missile), velocity = MianbaoMissileCompat.getWorldVelocity(missile);
                double turn = Math.acos(Math.max(-1, Math.min(1, velocity.normalize().dot(shot.previous[0].normalize()))));
                CSV.add(shot.name + "," + (shot.math ? "math" : "entity") + "," + age + ","
                        + pos.x + "," + pos.y + "," + pos.z + "," + velocity.x + "," + velocity.y + "," + velocity.z + "," + Math.toDegrees(turn));
                require(missile.getPersistentData().getInt(shot.ageTag) == age, shot.name + " real guidance age mismatch");
                int delay = shot.type == null ? MianbaoAirDefenseCompat.guidanceDelayTicks() : MianbaoAircraftCompat.maneuverDelayTicks(shot.type);
                if (age <= delay) require(velocity.normalize().distanceToSqr(shot.axis) < 1e-12,
                        shot.name + " turned before native launch clearance at " + age);
                if (age == delay + 1) require(turn > 0 && turn < Math.toRadians(10), shot.name + " snapped during first acquisition");
                double limit = shot.type == null ? MianbaoAirDefenseCompat.maxTurnRadians(velocity.length())
                        : MianbaoAircraftCompat.interceptMaxTurnRadians(shot.type, velocity.length());
                if (age == delay + 1 && shot.type != MianbaoAircraftCompat.WeaponType.AIR_TO_GROUND_TOP_ATTACK) {
                    require(turn < limit * .95, shot.name + " distant acquisition unnecessarily saturated steering");
                }
                require(turn <= limit + 1e-7, shot.name + " steering limit exceeded");
                require(shot.type == null || velocity.length() * Math.tan(turn) <= MianbaoAircraftCompat.maxLateralAcceleration(shot.type) + 1e-6,
                        shot.name + " original overload limit exceeded");
                if (shot.math) {
                    var reference = SHOTS.stream().filter(other -> !other.math && other.name.equals(shot.name)).findFirst().orElseThrow();
                    require(pos.distanceToSqr(MianbaoMissileCompat.getWorldPosition(reference.entity)) < 1e-10,
                            shot.name + " entity/math trajectory mismatch at " + age);
                    require(velocity.distanceToSqr(MianbaoMissileCompat.getWorldVelocity(reference.entity)) < 1e-10,
                            shot.name + " entity/math guidance mismatch at " + age);
                }
                shot.previous[0] = velocity;
                if (age == 5 && shot.name.endsWith("-decoy")) selectDecoy(shot);
            }
            if (age == 100) {
                for (Shot shot : SHOTS) shot.entity.discard();
                SHOTS.clear(); FEEDS.clear();
                ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)
                        dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)).removeForceLoadTicket(
                        carrier, dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                        net.minecraft.util.Unit.INSTANCE);
                StabilizedTurret.LOGGER.info("GUIDED LAUNCH CHECK PASS: all native variants/devices preserve launch stage, progressive acquisition, original overload/angular ceilings and entity/math parity through tick 100");
            }
        } finally { if (age <= 100) Files.write(Path.of("guided-missile-launch-trajectories.csv"), CSV); }
    }

    private static void launchAa(BlockPos pos, Direction facing, boolean math, boolean displayMode, UUID bodyId,
                                 String launcherPath) throws Exception {
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(), "cfc missile mode " + (math ? "math" : "entity"));
        var block = BuiltInRegistries.BLOCK.get(id(launcherPath)).defaultBlockState();
        block = block.hasProperty(BlockStateProperties.FACING) ? block.setValue(BlockStateProperties.FACING, facing)
                : block.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        level.setBlock(pos, block, 3);
        var launcher = level.getBlockEntity(pos);
        require(launcher instanceof net.minecraft.world.Container, "AA fixture inventory missing");
        ((net.minecraft.world.Container) launcher).setItem(0, new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(id("portableantiairmissile_2")), 2));
        launcher.getPersistentData().putDouble("missileammo", 37); // Must never override the real slot or be consumed.
        BindingRef ref = BindingRef.capture(level, pos);
        require(MianbaoAirDefenseCompat.getAmmoCount(level, ref) == 2, launcherPath + " did not read slot 0");
        Vec3 muzzle = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, ref);
        Vec3 axis = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vec3 target = muzzle.add(axis.scale(3000)).add(axis.cross(new Vec3(0, 1, 0)).scale(5196));
        if (activeCue != null) target = activeCue.position;
        if (displayMode) {
            BlockPos displayPos = pos.west(4);
            level.setBlock(displayPos, ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState(), 3);
            var display = (com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity) level.getBlockEntity(displayPos);
            var contact = new com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity.Contact(
                    new com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity.TargetKey(bodyId, false),
                    activeCue == null ? Set.of(bodyId) : activeCue.members, target,
                    activeCue == null ? Vec3.ZERO : activeCue.velocity,
                    new AABB(target, target).inflate(2), 6000, 0, false);
            if (activeCue != null) display.setMissileProximityRange(0);
            com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance.armNext(display, ref, contact);
        } else {
            BlockPos computerPos = pos.west(4), radarPos = pos.west(6);
            level.setBlock(computerPos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState(), 3);
            level.setBlock(radarPos, BuiltInRegistries.BLOCK.get(id("jetradar")).defaultBlockState(), 3);
            var computer = (StabilizerControllerBlockEntity) level.getBlockEntity(computerPos);
            computer.bind(BindingSlot.AIRCRAFT_RADAR, BindingRef.capture(level, radarPos));
            computer.bind(BindingSlot.AA_MISSILE_LAUNCHER, ref);
            set(computer, "radarLockedSubLevelId", bodyId);
            set(computer, "aaProximityFuseRange", 0.0);
            Feed feed = new Feed(computer, bodyId, target, activeCue == null ? Vec3.ZERO : activeCue.velocity, level.getGameTime());
            if (activeCue == null) FEEDS.add(feed);
            supply(feed); ControllerTracker.add(computer);
        }
        captureKey = "aa-" + launcherPath + "-" + (displayMode ? "display" : "computer") + "-" + facing + (decoyLaunch ? "-decoy" : "");
        captured = null; capturing = true; mathCapture = math;
        try { require(MianbaoAirDefenseCompat.fireOne(level, ref), "AA native launch/consumption failed"); }
        finally { capturing = false; }
        require(MianbaoAirDefenseCompat.getAmmoCount(level, ref) == 1, launcherPath + " did not consume exactly one real round");
        require(launcher.getPersistentData().getDouble("missileammo") == 37, launcherPath + " consumed stale legacy ammunition");
        Entity missile = captured;
        require(missile != null && missile.getPersistentData().getBoolean("CreateFireControlAaGuided"), captureKey + " not adopted");
        require(MathematicalMissileManager.contains(missile) == math, "AA wrong route");
        Vec3 initial = MianbaoMissileCompat.getWorldVelocity(missile);
        require(initial.distanceToSqr(nativeVelocity) < 1e-10, "AA adoption changed native launch vector");
        missile.noPhysics = true;
        if (activeCue == null) SHOTS.add(new Shot(captureKey, missile, math, initial.normalize(), new Vec3[]{initial}, null,
                displayMode ? "CreateFireControlDisplayAge" : "CreateFireControlAaAge"));
        ((net.minecraft.world.Container) launcher).setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
        require(MianbaoAirDefenseCompat.getAmmoCount(level, ref) == 0 && !MianbaoAirDefenseCompat.fireOne(level, ref),
                launcherPath + " fired an empty container using stale NBT");
        ((net.minecraft.world.Container) launcher).setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT));
        require(MianbaoAirDefenseCompat.getAmmoCount(level, ref) == 0 && !MianbaoAirDefenseCompat.fireOne(level, ref),
                launcherPath + " fired an invalid round using stale NBT");
    }

    /** Feed only target snapshots for AGM/laser; use real launch and flight code. */
    private static final class TargetFeedComputer extends StabilizerControllerBlockEntity {
        Vec3 point; UUID targetId;
        TargetFeedComputer(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(pos, state); }
        @Override public GroundTargetSnapshot getAirToGroundTargetSnapshot() {
            return point == null ? null : new GroundTargetSnapshot(targetId, point, new AABB(point, point).inflate(2), Set.of(targetId));
        }
        // This fixture supplies a synthetic cue for native launch/flight comparisons.
        // Real acquisition and fresh obstruction checks run in RadarMotionMatrixChecks.
        @Override public GroundTargetSnapshot getAirToGroundLaunchTargetSnapshot() { return getAirToGroundTargetSnapshot(); }
        @Override public GroundTargetSnapshot resolveGroundTarget(UUID id, Set<UUID> members) { return getAirToGroundTargetSnapshot(); }
        @Override public Vec3 getPodGuidanceTarget() { return point; }
    }

    private static void selectDecoy(Shot shot) throws Exception {
        Class<?> handler = shot.type == null ? MianbaoAirDefenseGuidanceHandler.class : MianbaoAircraftGuidanceHandler.class;
        var field = handler.getDeclaredField("GUIDED"); field.setAccessible(true);
        Object state = ((Map<?, ?>) field.get(null)).get(shot.entity);
        require(state != null, "decoy fixture not owned by production guidance");
        Entity flare = BuiltInRegistries.ENTITY_TYPE.get(id("countermeasure")).create(level);
        Vec3 point = MianbaoMissileCompat.getWorldPosition(shot.entity).add(shot.axis.scale(3000))
                .add(shot.axis.cross(new Vec3(0, 1, 0)).scale(5196));
        flare.setPos(point);
        Vec3 before = MianbaoMissileCompat.getWorldVelocity(shot.entity);
        var apply = state.getClass().getDeclaredMethod("applyCountermeasureDecoy", Entity.class, Entity.class);
        apply.setAccessible(true); apply.invoke(state, shot.entity, flare);
        require(before.distanceToSqr(MianbaoMissileCompat.getWorldVelocity(shot.entity)) < 1e-20,
                shot.name + " decoy event consumed an extra steering budget");
        require(shot.entity.getPersistentData().getBoolean("CreateFireControlCountermeasureDecoyed"), "decoy selection was not exercised");
    }

    private static void set(Object object, String name, Object value) throws Exception {
        var field = StabilizerControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.parse("mianbaos_modernwarfare:" + path); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void abort() {
        for (Shot shot : SHOTS) shot.entity.discard();
        if (captured != null) captured.discard();
        SHOTS.clear(); FEEDS.clear();
        if (carrier != null) ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)).removeForceLoadTicket(
                carrier, dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                net.minecraft.util.Unit.INSTANCE);
    }
}
