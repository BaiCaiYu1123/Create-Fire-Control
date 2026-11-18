package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Real inventories, wireless rising edges, native entities, unloaded terrain and timeout. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class OrdinaryAaIntegration {
    private static ServerLevel level;
    private static TestComputer fuzeComputer;
    private static Entity captured;
    private static boolean capturing, done;
    private static int ticks;
    private static final List<Flight> FLIGHTS = new ArrayList<>();
    private static final List<String> CSV = new ArrayList<>(List.of("launcher,age,x,y,z,speed,turnDegrees"));
    private record Flight(String launcher, Entity missile, TestComputer computer, Vec3 start, Vec3[] previous) {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void capture(EntityJoinLevelEvent event) {
        if (capturing && OrdinaryAirDefenseGuidance.isManaged(event.getEntity())) captured = event.getEntity();
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (Boolean.getBoolean("create_fire_control.ordinaryAaClientChecks")) return;
        level = event.getServer().overworld();
        try {
            int index = 0;
            for (String path : List.of("antiairmissilelauncherblock", "antiairmissilelauncherleft", "antiairmissilelauncherright",
                    "antiairmissilelauncherthird_h_1", "antiairmissilelauncherthird_h_2",
                    "antiairmissilelauncherthird_h_1sand", "antiairmissilelauncherthird_h_2sand")) {
                BlockPos pos = new BlockPos(512, 180, 512 + index++ * 2);
                BlockState state = BuiltInRegistries.BLOCK.get(id(path)).defaultBlockState();
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
                level.setBlock(pos, state, 3);
                var launcher = level.getBlockEntity(pos);
                require(launcher instanceof WorldlyContainer, path + " inventory missing");
                var inventory = (WorldlyContainer) launcher;
                inventory.clearContent(); // Repeated verification runs reuse this isolated save.
                ItemStack ordinary = new ItemStack(BuiltInRegistries.ITEM.get(id("portableantiairmissile")), 3);
                require(inventory.canPlaceItemThroughFace(0, ordinary, Direction.DOWN), path + " hopper rejects ordinary round");
                require(!inventory.canPlaceItemThroughFace(1, ordinary, Direction.DOWN), path + " hopper accepts a non-firing slot");
                var player = FakePlayerFactory.getMinecraft(level);
                player.getAbilities().instabuild = false;
                require(OrdinaryAirDefenseLauncher.load(level, pos, player, ordinary), path + " first right-click load failed");
                require(OrdinaryAirDefenseLauncher.load(level, pos, player, ordinary), path + " second right-click load failed");
                require(ordinary.getCount() == 1 && inventory.getItem(0).getCount() == 2, path + " load did not consume player inventory exactly once");
                launcher.getPersistentData().putDouble("missileammo", 37);
                BindingRef ref = BindingRef.capture(level, pos);
                require(MianbaoAirDefenseCompat.getAmmoCount(level, ref) == 2, path + " ordinary ammo not recognised");
                require(MianbaoAirDefenseCompat.getRadarAmmoCount(level, ref) == 0, "Radar display adopted beam ammunition");
                BlockPos computerPos = pos.west(4);
                BlockState computerState = ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState();
                level.setBlock(computerPos, computerState, 3);
                level.removeBlockEntity(computerPos);
                TestComputer computer = new TestComputer(computerPos, computerState);
                level.setBlockEntity(computer);
                computer.origin = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, ref);
                computer.bind(BindingSlot.AA_MISSILE_LAUNCHER, ref);
                computer.setFrequency(4, true, new ItemStack(Items.STONE));
                ControllerTracker.add(computer);
                require(!computer.isRadarActive(), "Fixture unexpectedly has a radar");
                var receiverMethod = StabilizerControllerBlockEntity.class.getDeclaredMethod("receiverForChannel", int.class);
                receiverMethod.setAccessible(true);
                var receiver = (WirelessPulseReceiver) receiverMethod.invoke(computer, 4);
                inventory.setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(id("portableantiairmissile_2")), 2));
                receiver.setReceivedStrength(15); receiver.setReceivedStrength(0);
                require(inventory.getItem(0).getCount() == 2, "High-speed round bypassed its radar requirement");
                inventory.setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(id("portableantiairmissile")), 2));
                computer.beam = null;
                receiver.setReceivedStrength(15); receiver.setReceivedStrength(0);
                require(inventory.getItem(0).getCount() == 2, "Beam round fired without an aiming direction");
                int ordinarySelection = FireControlWeaponInputs.action(FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE);
                computer.setFrequency(ordinarySelection, true, new ItemStack(Items.DIRT));
                var selector = (WirelessPulseReceiver) receiverMethod.invoke(computer, ordinarySelection);
                selector.setReceivedStrength(15); selector.setReceivedStrength(0);
                receiver.setReceivedStrength(15); receiver.setReceivedStrength(0);
                require(inventory.getItem(0).getCount() == 2, "Selected ordinary round fired without an aiming direction");
                computer.beam = new Vec3(1, 0, 0);
                if (index == 1) {
                    // A display command issued just before the computer must not steal its beam round.
                    BlockPos displayPos = pos.west(8);
                    level.setBlock(displayPos, ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState(), 3);
                    var display = (com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity) level.getBlockEntity(displayPos);
                    Vec3 target = computer.origin.add(1000, 0, 0);
                    java.util.UUID targetId = java.util.UUID.randomUUID();
                    var contact = new com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity.Contact(
                            new com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity.TargetKey(targetId, false),
                            Set.of(targetId), target, Vec3.ZERO, new net.minecraft.world.phys.AABB(target, target).inflate(2),
                            1000, 0, false);
                    com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance.armNext(display, ref, contact);
                }
                captured = null; capturing = true;
                try { receiver.setReceivedStrength(15); }
                finally { capturing = false; receiver.setReceivedStrength(0); }
                require(captured != null, path + " real wireless input did not launch a beam round without radar");
                require(inventory.getItem(0).getCount() == 1 && launcher.getPersistentData().getDouble("missileammo") == 37,
                        path + " incorrect ammunition consumption");
                require(!captured.getPersistentData().getBoolean("CreateFireControlAaGuided"), "Ordinary round acquired radar guidance");
                require(!captured.getPersistentData().getBoolean("CreateFireControlDisplayGuided"), "Pending display launch stole ordinary beam guidance");
                require(!MathematicalMissileManager.contains(captured), "Ordinary beam round lost entity self-loading");
                captured.noPhysics = true; // Co-located fixture missiles must not intercept one another.
                FLIGHTS.add(new Flight(path, captured, computer, captured.position(), new Vec3[]{captured.getDeltaMovement()}));
                inventory.setItem(0, ItemStack.EMPTY);
                require(!MianbaoAirDefenseCompat.fireOne(level, ref, computer), "Empty slot revived stale NBT");
                inventory.setItem(0, new ItemStack(Items.DIRT));
                require(!MianbaoAirDefenseCompat.fireOne(level, ref, computer), "Invalid slot revived stale NBT");
            }
            require(level.players().isEmpty(), "Off-screen flight requires an empty test server");
        } catch (Throwable failure) { finish(failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (level == null || done) return;
        try {
            ticks++;
            for (Flight flight : FLIGHTS) {
                Entity missile = flight.missile;
                int age = missile.getPersistentData().getInt(OrdinaryAirDefenseGuidance.AGE_TAG);
                require(age == ticks, flight.launcher + " age is not exactly one per server tick");
                if (ticks >= 400) {
                    require(missile.isRemoved(), "Twenty-second timeout did not remove the round");
                    require(!missile.getPersistentData().getBoolean("CreateFireControlExplosionSent")
                            && !missile.getPersistentData().getBoolean("CreateFireControlDetonated"), "Timeout exploded");
                    continue;
                }
                require(!missile.isRemoved(), "Native timeout prematurely removed ordinary round at " + ticks);
                Vec3 velocity = missile.getDeltaMovement();
                double expected = OrdinaryAirDefenseGuidance.speedProfile(2).speedAt(ticks);
                require(Math.abs(velocity.length() - expected) < 1e-9, "Wrong actual speed at tick " + ticks);
                double turn = Math.acos(Math.max(-1, Math.min(1, velocity.normalize().dot(flight.previous[0].normalize()))));
                require(turn <= OrdinaryAirDefenseGuidance.MAX_TURN_RADIANS + 1e-7, "Ordinary AA exceeded its enhanced turn limit");
                if (ticks == 71) require(Math.abs(turn - Math.toRadians(16)) < 1e-7,
                        "Real ordinary AA flight did not use fourfold steering authority");
                require(missile.getPersistentData().getDouble("fadongji1") == 0, "Native motor/targeting competed with beam guidance");
                if (ticks == 70) flight.computer.beam = new Vec3(1, 0, .25).normalize();
                if (ticks == 120) {
                    Entity restored = missile.getType().create(level);
                    restored.load(missile.saveWithoutId(new CompoundTag()));
                    require(restored.getPersistentData().getInt(OrdinaryAirDefenseGuidance.AGE_TAG) == 120,
                            "Save/load lost flight age");
                    require(restored.getPersistentData().getBoolean(OrdinaryAirDefenseGuidance.GUIDED_TAG), "Save/load lost adoption");
                }
                if (ticks == 200) require(Math.abs(velocity.length() * 20 - 340.3) < 1e-8, "Ten-second Mach-1 limit failed");
                if (ticks == 300 && flight == FLIGHTS.getFirst()) {
                    level.removeBlock(flight.computer.getBlockPos(), false);
                }
                if (ticks == 399) require(missile.position().distanceTo(flight.start) > 4000,
                        "Missile froze beyond view distance instead of self-loading");
                CSV.add(flight.launcher + "," + ticks + "," + missile.getX() + "," + missile.getY() + "," + missile.getZ()
                        + "," + velocity.length() + "," + Math.toDegrees(turn));
                flight.previous[0] = velocity;
            }
            if (ticks == 120) {
                // Simulate service reattachment using the same serialized ownership/age path.
                OrdinaryAirDefenseGuidance.stop(new ServerStoppingEvent(level.getServer()));
                for (Flight flight : FLIGHTS) OrdinaryAirDefenseGuidance.onJoin(new EntityJoinLevelEvent(flight.missile, level));
            }
            if (ticks == 400) {
                var active = OrdinaryAirDefenseGuidance.class.getDeclaredField("ACTIVE"); active.setAccessible(true);
                require(((java.util.Map<?, ?>) active.get(null)).isEmpty(), "Expired rounds retain self-loading state");
                require(level.getForcedChunks().isEmpty(), "Native permanent forceload commands leaked");
                verifyProximityFuzes();
                require(((java.util.Map<?, ?>) active.get(null)).isEmpty(), "Fuzed rounds retain self-loading state");
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private static void verifyProximityFuzes() throws Exception {
        BlockPos launcherPos = new BlockPos(1000, 280, 1024), computerPos = launcherPos.west(4);
        level.setBlockAndUpdate(launcherPos, BuiltInRegistries.BLOCK.get(id("antiairmissilelauncherblock"))
                .defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        BlockState computerState = ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState();
        level.setBlockAndUpdate(computerPos, computerState);
        level.removeBlockEntity(computerPos);
        fuzeComputer = new TestComputer(computerPos, computerState);
        level.setBlockEntity(fuzeComputer);
        var launcherRef = BindingRef.capture(level, launcherPos);
        fuzeComputer.origin = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, launcherRef);
        fuzeComputer.bind(BindingSlot.AA_MISSILE_LAUNCHER, launcherRef);
        ControllerTracker.add(fuzeComputer);
        verifyFuze(0, 3, 9, false, false, false);
        verifyFuze(2, 3, 9, false, false, false);
        verifyFuze(5, 3, 9, true, true, false);
        verifyFuze(10, 8, 9, true, true, false);
        verifyFuze(5, 5.25, 9, false, false, false);
        verifyFuze(5, 5, 9, true, true, false);
        verifyFuze(5, 3, 8, false, false, false); // Ten-tick launch clearance.
        verifyFuze(0, 0, 9, true, true, false); // Contact remains armed at zero.
        verifyFuze(5, 3, 399, true, false, false); // Timeout remains silent.
        verifyFuze(5, 3, 9, true, true, true); // Saved fuze survives loss of its computer.
        StabilizedTurret.LOGGER.info("ORDINARY AA FUZE PASS: 0/2/5/10 ranges, boundary, swept crossing, contact, clearance, persistence, owner loss, silent timeout");
    }

    private static void verifyFuze(double range, double offset, int initialAge,
                                   boolean removed, boolean exploded, boolean removeComputer) throws Exception {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        BlockPos original = new BlockPos(1024, 300, 1024);
        level.setBlockAndUpdate(original, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        var assembly = dev.simulated_team.simulated.util.SimAssemblyHelper.assembleFromSingleBlock(
                level, original.below(), original, false, false);
        require(assembly != null, "Fuze target assembly failed");
        var body = (dev.ryanhcode.sable.sublevel.ServerSubLevel) assembly.subLevel();
        Entity missile = null;
        TestComputer computer = fuzeComputer;
        try {
            body.getPlot().updateBoundingBox();
            body.updateBoundingBox();
            body.forceUpdateGlobalBounds();
            body.buildMassTracker();
            body.updateMergedMassData(1);
            Vec3 center = SableMissileFuze.physicalCenterOfMass(body);
            require(center != null && center.distanceTo(new Vec3(1024.5, 300.5, 1024.5)) < .01,
                    "Fuze target has no real physical centre: " + center);
            for (int x = 62; x <= 66; x++) for (int z = 62; z <= 66; z++) level.getChunk(x, z);
            computer.setFuseRange(range);
            BindingRef ref = computer.getAaMissileLaunchers().getFirst();
            var inventory = (net.minecraft.world.Container) ref.resolve(level);
            inventory.setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(id("portableantiairmissile")), 1));
            captured = null; capturing = true;
            try { require(MianbaoAirDefenseCompat.fireOne(level, ref, computer), "Fuze fixture failed to launch"); }
            finally { capturing = false; }
            missile = captured;
            require(missile != null && missile.getPersistentData().getDouble(OrdinaryAirDefenseGuidance.FUSE_TAG) == range,
                    "Ordinary missile ignored computer fuze " + range);
            Vec3 from = center.add(-8, offset, 0), to = center.add(8, offset, 0);
            missile.noPhysics = true;
            missile.setPos(from.x, from.y, from.z);
            missile.getPersistentData().putInt(OrdinaryAirDefenseGuidance.AGE_TAG, initialAge);
            Entity saved = missile.getType().create(level);
            saved.load(missile.saveWithoutId(new CompoundTag()));
            require(saved.getPersistentData().getDouble(OrdinaryAirDefenseGuidance.FUSE_TAG) == range, "Save lost fuze setting");
            computer.setFuseRange(range == 0 ? 10 : 0); // A fired round keeps its own setting.
            OrdinaryAirDefenseGuidance.stop(new ServerStoppingEvent(level.getServer()));
            OrdinaryAirDefenseGuidance.onJoin(new EntityJoinLevelEvent(missile, level));
            if (removeComputer) computer.setRemoved();
            missile.setPos(to.x, to.y, to.z);
            MianbaoMissileCompat.noteNativeMissileTick(missile);
            OrdinaryAirDefenseGuidance.tick(new ServerTickEvent.Post(() -> true, level.getServer()));
            require(missile.isRemoved() == removed, "Wrong fuze outcome: range=" + range + " offset=" + offset + " age=" + initialAge);
            require(missile.getPersistentData().getBoolean("CreateFireControlDetonated") == exploded,
                    "Wrong detonation outcome: range=" + range + " offset=" + offset + " age=" + initialAge);
            require(missile.getPersistentData().getBoolean("CreateFireControlExplosionSent") == exploded,
                    "Fuze did not use long-range explosion effects");
        } finally {
            if (missile != null) OrdinaryAirDefenseGuidance.cancelLaunch(missile);
            if (removeComputer) computer.clearRemoved();
            container.removeSubLevel(body, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
        }
    }

    private static void finish(Throwable failure) {
        if (done) return;
        done = true;
        try {
            Files.write(Path.of("ordinary-aa-trajectories.csv"), CSV);
            Files.writeString(Path.of("ordinary-aa-result.txt"), failure == null ? "PASS: seven launchers, 400 ticks, native entities, no players; ordinary AA proximity fuze settings and recovery" : "FAIL: " + failure);
            if (failure == null) StabilizedTurret.LOGGER.info("ORDINARY AA CHECK PASS: seven inventories, real wireless launch without radar, beam/motor/recovery/self-loading, silent tick-400 removal");
            else StabilizedTurret.LOGGER.error("ORDINARY AA CHECK FAILED", failure);
        } catch (Exception error) { throw new RuntimeException(error); }
        finally { level.getServer().halt(false); }
    }

    private static final class TestComputer extends StabilizerControllerBlockEntity {
        Vec3 origin, beam = new Vec3(1, 0, 0);
        TestComputer(BlockPos pos, BlockState state) { super(pos, state); }
        @Override public Vec3 getMeasuredAimOrigin() { return origin; }
        @Override public Vec3 getMeasuredAimDirection() { return beam; }
        void setFuseRange(double range) {
            var tag = saveWithoutMetadata(level.registryAccess());
            tag.putDouble("AaProximityFuseRange", range);
            loadAdditional(tag, level.registryAccess());
            require(getAaProximityFuseRange() == range, "Controller did not load real AA fuze setting");
        }
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
