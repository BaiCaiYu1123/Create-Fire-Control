package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
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

/** Native mod + actual Sable physics; excluded from the distributed mod. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class BombRackIntegration {
    private static ServerLevel root;
    private static ServerSubLevel carrier;
    private static BlockPos carrierBlock;
    private static int age;
    private static boolean recording, finished;
    private static final List<Entity> launches = new ArrayList<>();

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void join(EntityJoinLevelEvent event) {
        if (recording && !event.getLevel().isClientSide() && event.getEntity().getType().is(
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, id("ballistic_bombs")))) {
            if (!launches.contains(event.getEntity())) launches.add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (Boolean.getBoolean("create_fire_control.bombClientChecks") || Boolean.getBoolean("create_fire_control.virtualBombChecks")) return;
        root = event.getServer().overworld();
        try {
            for (int x = -1; x <= 2; x++) for (int z = -1; z <= 1; z++) root.getChunk(x, z);
            root.setChunkForced(11, 6, true);
            root.getChunk(11, 6);
            BlockPos original = new BlockPos(32, 240, 0);
            root.setBlockAndUpdate(original, Blocks.STONE.defaultBlockState());
            var assembled = SimAssemblyHelper.assembleFromSingleBlock(root, original.below(), original, false, false);
            require(assembled != null, "Sable carrier assembly failed");
            carrier = (ServerSubLevel) assembled.subLevel();
            carrierBlock = original.offset(assembled.offset());
            ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) SubLevelContainer.getContainer(root)).addForceLoadTicket(
                    carrier, dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED, net.minecraft.util.Unit.INSTANCE);
        } catch (Throwable error) { finish(error); }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (root == null || finished) return;
        try {
            root.resetEmptyTime();
            if (++age == 5) {
                verifyCarrierCollisionExclusion();
                verifyAllRacks(root, new BlockPos(4, 250, 4), new BlockPos(8, 250, 4));
                verifySwitching();
                verifyNativeFlights();
            }
            if (age == 8) {
                SubLevelPhysicsSystem.require(root).getPipeline().addLinearAndAngularVelocity(carrier,
                        new Vector3d(70, 10, -25), new Vector3d(.2, .5, -.1));
            }
            if (age == 10) {
                verifyAllRacks(carrier.getLevel(), carrierBlock.above(5), carrierBlock.above(5).east(4));
                finish(null);
            }
            require(age < 100, "Bomb integration timed out");
        } catch (Throwable error) { finish(error); }
    }

    private static void verifyCarrierCollisionExclusion() {
        Vec3 body = carrier.logicalPose().transformPosition(Vec3.atCenterOf(carrierBlock));
        BlockPos ground = BlockPos.containing(body).below(12);
        root.setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
        var flight = new MianbaoBombBallistics.Flight(WeaponType.BOMB, body.add(0, 5, 0), new Vec3(0, -1, 0), false, Vec3.ZERO, List.of());
        Vec3 structure = MianbaoBombBallistics.predict(root, flight, java.util.Set.of());
        require(structure != null && structure.distanceTo(body) < 1.0,
                "Non-ignored physical body was not hit in world coordinates: " + structure);
        Vec3 terrain = MianbaoBombBallistics.predict(root, flight, java.util.Set.of(carrier.getUniqueId()));
        near(terrain, new Vec3(body.x, ground.getY() + 1, body.z), 1.0e-6,
                "Prediction clipped releasing carrier or returned plot-local coordinates");
        root.removeBlock(ground, false);
        StabilizedTurret.LOGGER.info("BOMB CHECK PASS: ignored carrier cannot enter terrain ray; other structure hits remain world-space");
    }

    private static void verifyAllRacks(Level local, BlockPos controllerPos, BlockPos rackPos) throws Exception {
        local.setBlock(controllerPos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState(), 3);
        var controller = (StabilizerControllerBlockEntity) local.getBlockEntity(controllerPos);
        controller.setFrequency(6, true, new ItemStack(Items.DIAMOND));
        Field receiverField = StabilizerControllerBlockEntity.class.getDeclaredField("aircraftMissileFireReceiver");
        receiverField.setAccessible(true);
        var receiver = (WirelessPulseReceiver) receiverField.get(controller);
        Field selected = StabilizerControllerBlockEntity.class.getDeclaredField("selectedAircraftWeapon");
        selected.setAccessible(true);
        int checked = 0;
        for (ResourceLocation rackId : MianbaoBombCompat.rackIds()) {
            var spec = MianbaoBombCompat.rack(rackId);
            if (spec == null) continue;
            var state = BuiltInRegistries.BLOCK.get(rackId).defaultBlockState();
            require(!state.isAir(), "Missing native rack " + rackId);
            if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, Direction.EAST);
            else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            local.setBlock(rackPos, state, 3);
            BindingRef binding = BindingRef.capture(local, rackPos);
            controller.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, binding);
            selected.set(controller, spec.type());
            require(controller.getAircraftAmmoCount(spec.type()) == spec.ammo(), "Controller misread " + rackId);
            for (int remaining = spec.ammo(); remaining > 0; remaining--) {
                require(binding.isSameTarget(local), "Binding lost before release " + rackId);
                var settings = local.getBlockEntity(rackPos);
                if (settings != null && MianbaoBombBallistics.guided(spec.type()) && spec.type() != WeaponType.LASER_BOMB) {
                    settings.getPersistentData().putBoolean("坐标模式", true);
                    settings.getPersistentData().putDouble("target_x", 600);
                    settings.getPersistentData().putDouble("target_y", 64);
                    settings.getPersistentData().putDouble("target_z", 400);
                }
                Vec3 origin = MianbaoBombCompat.releasePoint(local, binding), velocity = MianbaoBombCompat.releaseVelocity(local, binding);
                if (binding.subLevelId() != null) require(velocity.length() > .5, "Moving carrier velocity missing");
                var sight = controller.getBombSightPayload();
                require(sight.flight() != null && sight.flight().type() == spec.type(), "Selected bomb trajectory missing " + rackId);
                near(sight.flight().origin(), origin, 1.0e-8, "Sight used a different release point");
                near(sight.flight().velocity(), velocity, 1.0e-8, "Sight used a different inherited speed");
                launches.clear();
                receiver.setReceivedStrength(0);
                recording = true;
                receiver.setReceivedStrength(15);
                recording = false;
                require(launches.size() == 1, "Input did not release exactly one native bomb " + rackId + ": " + launches.size());
                Entity bomb = launches.getFirst();
                require(root.getEntity(bomb.getUUID()) == null && MianbaoBombProjectileHandler.snapshot(bomb.getUUID()) != null,
                        "Bound bomb entered native entity tracking instead of mathematical flight " + rackId);
                if (MianbaoBombBallistics.guided(spec.type()) && spec.type() != WeaponType.LASER_BOMB) {
                    require(bomb.getPersistentData().getBoolean("坐标模式") && bomb.getPersistentData().getDouble("target_x") == 600,
                            "Native guided bomb lost source coordinate settings " + rackId);
                }
                near(bomb.position(), origin, 1.0e-5, "Native spawn point disagrees for " + rackId);
                // Small bombs retain native random dispersion; the sight uses its mean release vector.
                near(bomb.getDeltaMovement(), velocity, spec.type() == WeaponType.SMALL_BOMB ? .03 : 1.0e-5,
                        "Native inertia disagrees for " + rackId);
                require(controller.getAircraftAmmoCount(spec.type()) == remaining - 1, "Single input consumed wrong ammo " + rackId);
                receiver.setReceivedStrength(15);
                receiver.setReceivedStrength(15);
                require(launches.size() == 1, "Held input released extra bombs " + rackId);
                bomb.discard();
                require(binding.isSameTarget(local), "Native loaded/empty transition lost binding " + rackId);
            }
            require(controller.getBombSightPayload().flight() == null, "Depleted bomb kept trajectory " + rackId);
            controller.unbindDevice(BindingSlot.AIRCRAFT_MISSILE_RACK, 0, rackPos);
            checked++;
        }
        require(checked == 22, "Rack coverage mismatch " + checked);
        StabilizedTurret.LOGGER.info("BOMB CHECK PASS: {} native rack variants, single rising-edge release, retained bindings, sight/launch point and velocity ({})",
                checked, Sable.HELPER.getContaining(local, rackPos) == null ? "stationary" : "moving and rotating Sable carrier");
    }

    private static void verifySwitching() throws Exception {
        BlockPos pos = new BlockPos(2, 250, 8);
        root.setBlock(pos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState(), 3);
        var controller = (StabilizerControllerBlockEntity) root.getBlockEntity(pos);
        controller.setFrequency(7, true, new ItemStack(Items.EMERALD));
        controller.setFrequency(StabilizerControllerBlockEntity.INPUT_ACTION_AIRCRAFT_WEAPON_UP, true, new ItemStack(Items.GOLD_INGOT));
        int index = 0;
        for (WeaponType type : WeaponType.values()) if (MianbaoBombCompat.isBomb(type)) {
            for (ResourceLocation rack : MianbaoBombCompat.rackIds()) {
                var spec = MianbaoBombCompat.rack(rack);
                if (spec != null && spec.type() == type && spec.ammo() == 1) {
                    BlockPos target = new BlockPos(2 + index++, 250, 12);
                    root.setBlock(target, BuiltInRegistries.BLOCK.get(rack).defaultBlockState(), 3);
                    controller.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, BindingRef.capture(root, target));
                    break;
                }
            }
        }
        var cycle = StabilizerControllerBlockEntity.class.getDeclaredMethod("cycleAircraftWeapon", int.class);
        cycle.setAccessible(true);
        java.util.Set<WeaponType> seen = new java.util.HashSet<>();
        for (int i = 0; i < 13; i++) { cycle.invoke(controller, 1); seen.add(controller.getSelectedAircraftWeapon()); }
        require(seen.size() == 13 && seen.stream().allMatch(MianbaoBombCompat::isBomb), "Weapon switch omitted bomb families");
        WeaponType current = controller.getSelectedAircraftWeapon();
        cycle.invoke(controller, -1); cycle.invoke(controller, 1);
        require(controller.getSelectedAircraftWeapon() == current, "Reverse weapon switch broken");
        StabilizedTurret.LOGGER.info("BOMB CHECK PASS: all 13 bomb types cycle forward and backward without radar");
    }

    private static void verifyNativeFlights() throws Exception {
        String[] entities = {"bombtanshe", "medium_bombtanshe", "clusterbombtanshe", "earthpenetratorbombtanshe",
                "fire_bombtanshe", "highresistancebombtanshe", "jdam_1tanshe", "jdam_2_tanshe", "nuclearbombtanshe",
                "white_phosphorusbombtanshe", "gasbombtanshe", "glidebombtanshe", "littlebomb"};
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 4; z++) root.getChunk(x, z);
        Entity laser = BuiltInRegistries.ENTITY_TYPE.get(id("laserpoint_4")).create(root);
        laser.setPos(180, 220, 100);
        root.getChunk(laser.blockPosition());
        require(root.addFreshEntity(laser), "Laser target did not enter world");
        require(root.getEntitiesOfClass(laser.getClass(), new net.minecraft.world.phys.AABB(-300, 0, -300, 300, 600, 300)).contains(laser),
                "Native laser search cannot see test target");
        for (int index = 0; index < entities.length; index++) {
            WeaponType type = WeaponType.values()[WeaponType.BOMB.ordinal() + index];
            Entity bomb = BuiltInRegistries.ENTITY_TYPE.get(id(entities[index])).create(root);
            Vec3 position = new Vec3(0, 310, 0), velocity = new Vec3(3, -.1, 1);
            bomb.setPos(position);
            bomb.setDeltaMovement(velocity);
            bomb.getPersistentData().putBoolean("坐标模式", true);
            bomb.getPersistentData().putDouble("target_x", 180);
            bomb.getPersistentData().putDouble("target_y", 220);
            bomb.getPersistentData().putDouble("target_z", 100);
            var flight = new MianbaoBombBallistics.Flight(type, position, velocity, true, laser.position(), List.of(laser.position()));
            var mathematical = new VirtualBombFlight(type, position, velocity, 0);
            var pre = bomb.getClass().getMethod("beginBombFlightTick");
            var post = bomb.getClass().getMethod("finishBombFlightTick");
            int steps = type == WeaponType.CLUSTER_BOMB || type == WeaponType.WHITE_PHOSPHORUS_BOMB ? 29 : 45;
            for (int age = 1; age <= steps; age++) {
                position = position.add(velocity);
                velocity = MianbaoBombBallistics.step(velocity, MianbaoBombBallistics.requested(flight, position, velocity, age),
                        MianbaoBombBallistics.guided(type), type == WeaponType.GLIDE_BOMB, MianbaoBombBallistics.drag(type), false);
                mathematical.advance(true, laser.position(), List.of(laser.position()), false);
                near(mathematical.position(), position, 1.0e-8, "Mathematical flight disagrees with bomb sight");
                near(mathematical.velocity(), velocity, 1.0e-8, "Mathematical velocity disagrees with bomb sight");
                bomb.tickCount++;
                pre.invoke(bomb);
                try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) { bomb.tick(); }
                post.invoke(bomb);
                require(!bomb.isRemoved(), "Native flight unexpectedly expired " + type);
                near(bomb.position(), position, 1.0e-6, "Native trajectory mismatch " + type + " tick " + age);
                near(bomb.getDeltaMovement(), velocity, 1.0e-6, "Native post-trail velocity mismatch " + type + " tick " + age);
            }
            bomb.discard();
        }
        laser.discard();
        StabilizedTurret.LOGGER.info("BOMB CHECK PASS: all 13 native entity trajectories match prediction, including actual delayed laser/coordinate guidance");
    }

    private static void near(Vec3 actual, Vec3 expected, double tolerance, String label) {
        require(actual != null && expected != null && actual.distanceTo(expected) <= tolerance, label + ": " + actual + " / " + expected);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path); }
    private static void require(boolean value, String text) { if (!value) throw new AssertionError(text); }
    private static void finish(Throwable error) {
        finished = true;
        recording = false;
        if (error != null) StabilizedTurret.LOGGER.error("BOMB CHECK FAILED", error);
        try { Files.writeString(Path.of("bomb-integration-result.txt"), error == null ? "PASS" : "FAIL: " + error); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        finally { if (root != null) root.getServer().halt(false); }
    }
}
