package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native four-slot inventories and the controller's real firing path. Test-only radar/IR observations. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MixedAirRackIntegration {
    private record Round(String item, WeaponType type, String entity) { }
    private static final List<Round> ROUNDS = List.of(
            new Round("far_missile_1", WeaponType.FAR, "farmissile_1tanshe"),
            new Round("far_missile_2", WeaponType.FAR, "farmissile_2tanshe"),
            new Round("medium_missile", WeaponType.MEDIUM, "missile_2tanshe"),
            new Round("medium_ai_mmissile_2", WeaponType.MEDIUM, "medium_aim_missile_2tanshe"),
            new Round("closemissile_1", WeaponType.CLOSE, "closemissile_1tanshe"),
            new Round("closemissile_2", WeaponType.CLOSE, "closemissile_2tanshe"),
            new Round("mica_em", WeaponType.MICA_EM, "mica_em_tanshe"),
            new Round("aim_120", WeaponType.AIM_120D, "aim_120_tanshe"));
    private record Fixture(StabilizerControllerBlockEntity computer, StabilizerControllerBlockEntity competitor,
                           BlockEntity rack, BindingRef ref, UUID radarTarget) { }
    private static ServerLevel level;
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel target;
    private static final List<String> REPORT = new ArrayList<>();
    private static final List<Entity> CAPTURED = new ArrayList<>();
    private static boolean capturing;
    private static boolean rejectLaunch;
    private static int nextFixture, launches;

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void join(EntityJoinLevelEvent event) {
        if (capturing && MianbaoAircraftCompat.isAirToAir(MianbaoAircraftCompat.typeOf(event.getEntity())))
            CAPTURED.add(event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void reject(EntityJoinLevelEvent event) {
        if (rejectLaunch && MianbaoAircraftCompat.isAirToAir(MianbaoAircraftCompat.typeOf(event.getEntity()))) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        long originalTime = level.getGameTime();
        Throwable failure = null;
        try {
            createTarget();
            for (boolean math : new boolean[]{false, true}) {
                level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(),
                        "cfc missile mode " + (math ? "math" : "entity"));
                selectionRegression(math);
                for (Round round : ROUNDS) run(List.of(round, round, round, round), math, false);
                run(List.of(ROUNDS.get(0), ROUNDS.get(4), ROUNDS.get(6), ROUNDS.get(7)), math, true);
                run(List.of(ROUNDS.get(2), ROUNDS.get(1), ROUNDS.get(5), ROUNDS.get(3)), math, true);
                run(List.of(ROUNDS.get(0), ROUNDS.get(2), ROUNDS.get(1), ROUNDS.get(7)), math, true);
                run(List.of(ROUNDS.get(7)), math, false);
                run(List.of(ROUNDS.get(6), ROUNDS.get(7)), math, false);
                run(List.of(ROUNDS.get(0), ROUNDS.get(1), ROUNDS.get(7)), math, false);
            }
            gapsAndInvalidItems();
            legacyRack();
            REPORT.add("PASS " + launches + " genuine quad-rack releases; all eight ammo items; both routes; actual next/previous/direct selection and fire inputs; non-next selected slots; complete mixed-family counts; input UI actions; partial loads; selection and lock gates; exact computer ownership; unchanged other slots/components; failed-launch rollback; empty/reload/stale bindings; far-drop heading and ignition safety; legacy single rack");
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("MIXED AIR RACK CHECK FAILED", error);
        } finally {
            for (Entity missile : CAPTURED) missile.discard();
            level.getServer().getWorldData().overworldData().setGameTime(originalTime);
            try {
                Files.write(Path.of("mixed-air-rack-details.txt"), REPORT);
                Files.writeString(Path.of("mixed-air-rack-result.txt"), failure == null ? "PASS" : "FAIL: " + failure);
            } catch (Exception error) { throw new RuntimeException(error); }
            level.getServer().halt(false);
        }
    }

    private static void createTarget() throws Exception {
        BlockPos anchor = new BlockPos(1600, 200, 32);
        level.getChunkAt(anchor);
        level.setBlockAndUpdate(anchor, Blocks.IRON_BLOCK.defaultBlockState());
        var assembly = dev.simulated_team.simulated.util.SimAssemblyHelper.assembleFromSingleBlock(level, anchor.below(), anchor, false, false);
        require(assembly != null, "physical IR target assembly");
        target = (dev.ryanhcode.sable.sublevel.ServerSubLevel) assembly.subLevel();
        ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)).addForceLoadTicket(target,
                dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED, net.minecraft.util.Unit.INSTANCE);
        target.forceUpdateGlobalBounds();
    }

    private static Fixture fixture() throws Exception {
        BlockPos pos = new BlockPos(32 + nextFixture++ * 40, 200, 32);
        level.getChunkAt(pos); level.getChunkAt(pos.west(8));
        var state = BuiltInRegistries.BLOCK.get(id("quad_air_to_air_rack")).defaultBlockState();
        require(!state.isAir(), "new rack installed");
        state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.removeBlock(pos, false);
        level.setBlockAndUpdate(pos, state);
        var rack = level.getBlockEntity(pos);
        require(rack != null && MianbaoAircraftCompat.isRack(state), "quad recognized for binding");
        BindingRef ref = BindingRef.capture(level, pos);
        var computer = computer(pos.west(4), ref);
        var competitor = computer(pos.west(6), ref); // Same physical rack; nearest-association cannot identify the caller.
        UUID targetId = UUID.randomUUID();
        feed(computer, targetId); feed(competitor, UUID.randomUUID());
        return new Fixture(computer, competitor, rack, ref, targetId);
    }

    private static StabilizerControllerBlockEntity computer(BlockPos pos, BindingRef ref) {
        level.removeBlock(pos, false);
        level.setBlockAndUpdate(pos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        level.setBlockAndUpdate(pos.north(), BuiltInRegistries.BLOCK.get(id("jetradar")).defaultBlockState());
        var computer = (StabilizerControllerBlockEntity) level.getBlockEntity(pos);
        computer.bind(BindingSlot.AIRCRAFT_MISSILE_RACK, ref);
        require(computer.getAircraftMissileRacks().contains(ref), "actual quad rack binding");
        computer.bind(BindingSlot.AIRCRAFT_RADAR, BindingRef.capture(level, pos.north()));
        computer.setFrequency(6, true, new ItemStack(Items.REDSTONE));
        ControllerTracker.add(computer);
        return computer;
    }

    private static void feed(StabilizerControllerBlockEntity computer, UUID id) throws Exception {
        Vec3 center = Vec3.atCenterOf(computer.getBlockPos()).add(3000, 30, 300);
        set(computer, "radarContacts", List.of(new StabilizerControllerBlockEntity.RadarContact(id, center, Vec3.ZERO,
                new AABB(center, center).inflate(2), Set.of(id))));
        set(computer, "aircraftSelectedTargetId", id);
        set(computer, "lastRadarScanTick", level.getGameTime());
        set(computer, "aircraftProximityFuseRange", 0.0);
        set(computer, "aircraftFuseSafetySeconds", .5);
    }

    private static void selectionRegression(boolean math) throws Exception {
        Fixture f = fixture();
        List<Round> mixed = List.of(ROUNDS.get(0), ROUNDS.get(2), ROUNDS.get(4), ROUNDS.get(7));
        List<ItemStack> originals = new ArrayList<>();
        f.rack().getPersistentData().putString("MixedSelectionRegression", "channel and metadata survive selected-slot consumption");
        for (Round round : mixed) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id(round.item())));
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("retain-" + round.item()));
            originals.add(stack.copy());
            require((boolean) f.rack().getClass().getMethod("loadMissile", ItemStack.class, Player.class).invoke(f.rack(), stack, null), "regression mixed load");
        }
        f.computer().setFrequency(7, true, new ItemStack(Items.REDSTONE));
        f.computer().setFrequency(StabilizerControllerBlockEntity.INPUT_ACTION_AIRCRAFT_WEAPON_UP, true, new ItemStack(Items.REDSTONE));
        require(((Set<?>) invoke(f.computer(), "boundAircraftWeapons")).equals(Set.of(WeaponType.FAR, WeaponType.MEDIUM, WeaponType.CLOSE, WeaponType.AIM_120D)),
                "mixed selection must recognize all loaded types, including non-next slots");
        Set<Integer> uiActions = new java.util.HashSet<>();
        for (int action : f.computer().inputUiState().getIntArray("AircraftWeaponActions")) uiActions.add(action);
        require(uiActions.equals(Set.of(FireControlWeaponInputs.action(WeaponType.FAR), FireControlWeaponInputs.action(WeaponType.MEDIUM),
                FireControlWeaponInputs.action(WeaponType.CLOSE), FireControlWeaponInputs.action(WeaponType.AIM_120D))), "input configuration advertises all mixed weapon actions");
        pulse(f.computer(), 7);
        require(f.computer().getSelectedAircraftWeapon() == WeaponType.MEDIUM, "real next-weapon input selects loaded medium missile");
        pulse(f.computer(), 7);
        require(f.computer().getSelectedAircraftWeapon() == WeaponType.CLOSE, "real next-weapon input selects loaded infrared missile");
        pulse(f.computer(), StabilizerControllerBlockEntity.INPUT_ACTION_AIRCRAFT_WEAPON_UP);
        require(f.computer().getSelectedAircraftWeapon() == WeaponType.MEDIUM, "real previous-weapon input selects medium");
        for (Round round : mixed) {
            int action = FireControlWeaponInputs.action(round.type());
            f.computer().setFrequency(action, true, new ItemStack(Items.REDSTONE));
            pulse(f.computer(), action);
            require(f.computer().getSelectedAircraftWeapon() == round.type(), "direct weapon input recognizes " + round.type());
            require(f.computer().getAircraftAmmoCount(round.type()) == 1, "HUD reports loaded mixed ammo " + round.type());
        }
        // Medium first reproduces firing a selected family below a different top
        // round. No test writes the caller's selectedAircraftWeapon field.
        boolean[] remaining = {true, true, true, true};
        for (int slot : new int[]{1, 2, 0, 3}) {
            Round round = mixed.get(slot);
            pulse(f.computer(), FireControlWeaponInputs.action(round.type()));
            require(f.computer().getSelectedAircraftWeapon() == round.type(), "selected family before firing " + round.type());
            set(f.competitor(), "selectedAircraftWeapon", round.type());
            feed(f.computer(), f.radarTarget());
            if (round.type() == WeaponType.CLOSE) seedInfrared(f.computer());
            if (slot == 1) {
                rejectLaunch = true;
                try { require(!MianbaoAircraftRackCompat.fireOne(f.computer(), f.ref(), round.type()), "cancelled native launch reports failure"); }
                finally { rejectLaunch = false; }
                for (int i = 0; i < 4; i++) require(ItemStack.matches(item(f.rack(), i), originals.get(i)), "failed launch preserves all slots");
            }
            CAPTURED.clear(); capturing = true;
            try { pulse(f.computer(), 6); } finally { capturing = false; }
            require(CAPTURED.size() == 1, "one actual fire input releases selected non-next slot " + slot);
            Entity missile = CAPTURED.getFirst(); launches++;
            require(BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()).equals(id(round.entity()))
                    && missile.getPersistentData().getInt("MianbaoQuadRackSlot") == slot, "selected family releases its actual mounting slot");
            require(missile.getPersistentData().getLong("CreateFireControlAircraftController") == f.computer().getBlockPos().asLong(), "non-next slot exact source ownership");
            require(MianbaoAircraftRackCompat.currentLaunch(missile) == null, "selected-slot context cleared after fire");
            remaining[slot] = false;
            for (int i = 0; i < 4; i++) require(ItemStack.matches(item(f.rack(), i), remaining[i] ? originals.get(i) : ItemStack.EMPTY), "only selected slot consumed; components retained " + i);
            require(f.rack().getPersistentData().getString("MixedSelectionRegression").equals("channel and metadata survive selected-slot consumption"), "rack metadata retained");
            BlockEntity packetCopy = f.rack().getType().create(f.rack().getBlockPos(), f.rack().getBlockState());
            packetCopy.loadWithComponents(f.rack().getUpdateTag(level.registryAccess()), level.registryAccess());
            for (int i = 0; i < 4; i++) require(ItemStack.matches(item(packetCopy, i), item(f.rack(), i)), "native client update reflects selected-slot removal");
            if (round.type() == WeaponType.FAR) checkFar(missile, math);
            else require(MathematicalMissileManager.contains(missile) == math, "selected-slot math/entity route");
            cleanupMissile(missile);
            REPORT.add("PASS " + (math ? "math" : "entity") + " actual selection/fire input slot=" + slot + " type=" + round.type());
        }
        require(((Set<?>) invoke(f.computer(), "boundAircraftWeapons")).isEmpty(), "all mixed types removed after depletion");
        ControllerTracker.remove(f.computer()); ControllerTracker.remove(f.competitor());
        REPORT.add("PASS " + (math ? "math" : "entity") + " actual next/previous/direct selection inputs with all mixed slots");
    }

    private static void pulse(StabilizerControllerBlockEntity computer, int action) throws Exception {
        var method = StabilizerControllerBlockEntity.class.getDeclaredMethod("receiverForChannel", int.class);
        method.setAccessible(true);
        WirelessPulseReceiver receiver = (WirelessPulseReceiver) method.invoke(computer, action);
        receiver.setReceivedStrength(0); receiver.setReceivedStrength(15); receiver.setReceivedStrength(0);
    }

    private static void run(List<Round> rounds, boolean math, boolean rejectWrong) throws Exception {
        Fixture f = fixture();
        List<ItemStack> originals = new ArrayList<>();
        for (int i = 0; i < rounds.size(); i++) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id(rounds.get(i).item())));
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("slot-" + i));
            originals.add(stack.copy());
            require((boolean) f.rack().getClass().getMethod("loadMissile", ItemStack.class, Player.class).invoke(f.rack(), stack, null), "native loading");
            require(stack.isEmpty(), "one loaded item consumed");
        }
        var saved = f.rack().saveWithoutMetadata(level.registryAccess());
        f.rack().loadWithComponents(saved, level.registryAccess());
        require(f.ref().isSameTarget(level), "save/load preserves binding");
        if (rounds.size() == 4) {
            ItemStack extra = new ItemStack(Items.DIRT);
            require(!(boolean) f.rack().getClass().getMethod("loadMissile", ItemStack.class, Player.class).invoke(f.rack(), extra, null)
                    && extra.getCount() == 1, "invalid/full load does not consume items");
        }
        for (int slot = rounds.size() - 1; slot >= 0; slot--) {
            Round expected = rounds.get(slot);
            f.computer().setFrequency(FireControlWeaponInputs.action(expected.type()), true, new ItemStack(Items.REDSTONE));
            pulse(f.computer(), FireControlWeaponInputs.action(expected.type()));
            require(f.computer().getSelectedAircraftWeapon() == expected.type(), "actual direct selection for native-order shot");
            set(f.competitor(), "selectedAircraftWeapon", expected.type());
            feed(f.computer(), f.radarTarget());
            int count = 0;
            for (int i = slot; i >= 0; i--) if (rounds.get(i).type() == expected.type()) count++;
            require(MianbaoAircraftRackCompat.weaponAt(level, f.ref()) == expected.type(), "next slot type");
            require(f.computer().getAircraftAmmoCount(expected.type()) == count, "all matching slots counted even across another family");
            var available = (Set<?>) invoke(f.computer(), "boundAircraftWeapons");
            EnumSet<WeaponType> allLoaded = EnumSet.noneOf(WeaponType.class);
            for (int i = slot; i >= 0; i--) allLoaded.add(rounds.get(i).type());
            require(available.equals(allLoaded), "every remaining mixed family is selectable");
            if (rejectWrong) {
                WeaponType wrong = expected.type() == WeaponType.FAR ? WeaponType.AIM_120D : WeaponType.FAR;
                require(!MianbaoAircraftRackCompat.fireOne(f.computer(), f.ref(), wrong), "selected type mismatch must not fire");
                require(ItemStack.matches(item(f.rack(), slot), originals.get(slot)), "wrong selection leaves next item intact");
            }
            if (expected.type() == WeaponType.CLOSE) {
                InfraredTargeting.clear(f.computer());
                CAPTURED.clear(); capturing = true;
                try { pulse(f.computer(), 6); } finally { capturing = false; }
                require(CAPTURED.isEmpty() && !item(f.rack(), slot).isEmpty(), "IR lock required, no radar fallback");
                seedInfrared(f.computer());
            }
            CAPTURED.clear(); capturing = true;
            try { pulse(f.computer(), 6); } finally { capturing = false; }
            require(CAPTURED.size() == 1, "one controller input releases exactly one missile: " + expected.item());
            Entity missile = CAPTURED.getFirst();
            launches++;
            require(BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()).equals(id(expected.entity())), "actual entity matches selected slot");
            require(missile.getPersistentData().getInt("MianbaoQuadRackSlot") == slot, "native 4-to-1 slot order");
            require(missile.getPersistentData().getBoolean("CreateFireControlAircraftGuided"), "missile adopted");
            require(missile.getPersistentData().getLong("CreateFireControlAircraftController") == f.computer().getBlockPos().asLong(), "exact source controller owns missile");
            require(MianbaoAircraftRackCompat.currentLaunch(missile) == null, "scoped launch context cleaned");
            if (expected.type() == WeaponType.CLOSE) {
                require(missile.getPersistentData().contains(InfraredSeeker.TAG) && !missile.getPersistentData().contains(ActiveRadarSeeker.TAG), "IR uses infrared seeker");
            } else {
                require(missile.getPersistentData().getUUID("CreateFireControlAircraftRadarTarget").equals(f.radarTarget()), "source radar target copied");
            }
            require(item(f.rack(), slot).isEmpty(), "only fired slot consumed");
            for (int i = 0; i < slot; i++) require(ItemStack.matches(item(f.rack(), i), originals.get(i)), "other mixed items/components unchanged");
            require(f.ref().isSameTarget(level), "partial and empty rack binding retained");
            if (expected.type() != WeaponType.FAR) require(MathematicalMissileManager.contains(missile) == math, "instant-ignition math/entity route");
            else if (slot == 0) checkFar(missile, math);
            REPORT.add("PASS " + (math ? "math" : "entity") + " " + expected.item() + " slot=" + slot + " ready=" + count);
            cleanupMissile(missile);
        }
        require(MianbaoAircraftRackCompat.weaponAt(level, f.ref()) == null, "empty rack has no next weapon");
        require(f.computer().getAircraftAmmoCount(f.computer().getSelectedAircraftWeapon()) == 0, "empty HUD ammo count");
        require(!MianbaoAircraftRackCompat.fireOne(f.computer(), f.ref(), WeaponType.FAR), "empty cannot fire");
        var state = f.rack().getBlockState();
        level.removeBlock(f.rack().getBlockPos(), false); level.setBlockAndUpdate(f.rack().getBlockPos(), state);
        require(!f.ref().isSameTarget(level), "replacement quad invalidates stale binding");
        require(!MianbaoAircraftRackCompat.fireOne(f.computer(), f.ref(), WeaponType.AIM_120D), "stale binding cannot fire replacement");
        ControllerTracker.remove(f.computer()); ControllerTracker.remove(f.competitor());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void seedInfrared(StabilizerControllerBlockEntity computer) throws Exception {
        require(InfraredTargeting.selected(computer), "IR selected and ammo availability refreshed before observation");
        var track = InfraredSeeker.observe(level, target.getUniqueId(), Set.of(target.getUniqueId()), level.getGameTime());
        require(track != null, "real physical IR observation");
        Vec3 origin = computer.getInfraredAircraftOrigin();
        var frame = new InfraredTargeting.Frame(origin, new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 1, 0), 1, 1);
        var constructor = Class.forName(InfraredTargeting.class.getName() + "$State").getDeclaredConstructor();
        constructor.setAccessible(true); Object state = constructor.newInstance();
        set(state, "frame", frame); set(state, "receivedAt", level.getGameTime()); set(state, "evaluatedAt", level.getGameTime());
        set(state, "track", track); set(state, "lastPosition", track.center());
        ((Map) get(InfraredTargeting.class, "STATES")).put(computer, state);
        require(computer.isAircraftWeaponGuidanceReady(WeaponType.CLOSE), "IR lock seeded and ready");
    }

    private static void checkFar(Entity missile, boolean math) {
        var release = missile.getPersistentData().getCompound(FarMissileIgnitionHeading.RELEASE_TAG);
        int steps = 0;
        require(!release.isEmpty() && !MathematicalMissileManager.contains(missile), "far release begins native/unpowered");
        while (!release.getBoolean("SoundPlayed")) {
            require(steps++ < 15 && !MissileFuseSafety.armed(missile, 10), "far drop retains insurance");
            clock(); MianbaoAircraftGuidanceHandler.tick(post());
        }
        require(steps == 10, "new quad far missile preserves 0.5 second release");
        MianbaoAircraftGuidanceHandler.beforeTick(new ServerTickEvent.Pre(() -> true, level.getServer()));
        require(Math.abs(missile.getDeltaMovement().normalize().y) < 1e-8, "quad far ignition heading remains level");
        require(!MissileFuseSafety.armed(missile, 10), "far insurance starts at ignition");
        for (int powered = 1; powered <= 10; powered++) {
            clock(); MianbaoAircraftGuidanceHandler.beforeTick(new ServerTickEvent.Pre(() -> true, level.getServer()));
            if (!MathematicalMissileManager.contains(missile)) { missile.tickCount++; missile.tick(); }
            MianbaoAircraftGuidanceHandler.tick(post());
            require(!missile.isRemoved(), "quad far survives powered transition");
            require(MissileFuseSafety.armed(missile, 10) == (powered == 10), "quad far ignition safety boundary");
        }
        require(MathematicalMissileManager.contains(missile) == math, "quad far returns to requested math/entity route");
    }

    @SuppressWarnings("unchecked") private static void gapsAndInvalidItems() throws Exception {
        Fixture f = fixture();
        var field = f.rack().getClass().getDeclaredField("missiles"); field.setAccessible(true);
        List<ItemStack> slots = (List<ItemStack>) field.get(f.rack());
        slots.set(0, new ItemStack(BuiltInRegistries.ITEM.get(id("aim_120"))));
        slots.set(3, new ItemStack(BuiltInRegistries.ITEM.get(id("aim_120"))));
        require(MianbaoAircraftRackCompat.ammoCount(level, f.ref(), WeaponType.AIM_120D) == 2, "empty holes skipped in native firing order");
        slots.set(3, new ItemStack(Items.DIRT));
        require(MianbaoAircraftRackCompat.weaponAt(level, f.ref()) == null && MianbaoAircraftRackCompat.ammoCount(level, f.ref(), WeaponType.AIM_120D) == 1,
                "unknown top item ignored without hiding valid installed ammo");
        require(!MianbaoAircraftRackCompat.fireOne(f.computer(), f.ref(), WeaponType.MICA_EM), "missing ammo rejected");
        ControllerTracker.remove(f.computer()); ControllerTracker.remove(f.competitor());
    }

    private static void legacyRack() throws Exception {
        Fixture f = fixture(); BlockPos pos = f.rack().getBlockPos();
        level.removeBlock(pos, false);
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(id("aim_120_rack")).defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        BindingRef legacy = BindingRef.capture(level, pos);
        require(MianbaoAircraftRackCompat.weaponAt(level, legacy) == WeaponType.AIM_120D
                && MianbaoAircraftRackCompat.ammoCount(level, legacy, WeaponType.AIM_120D) == 1, "legacy rack reader preserved");
        ControllerTracker.remove(f.computer()); ControllerTracker.remove(f.competitor());
    }

    @SuppressWarnings("unchecked") private static void cleanupMissile(Entity missile) throws Exception {
        Object state = ((Map<Entity, Object>) get(MianbaoAircraftGuidanceHandler.class, "GUIDED")).remove(missile);
        if (state != null) invoke(state, "releaseTickets");
        missile.discard(); MathematicalMissileManager.sync(post());
    }
    private static ItemStack item(BlockEntity rack, int slot) throws Exception { return (ItemStack) rack.getClass().getMethod("missile", int.class).invoke(rack, slot); }
    private static void clock() { level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1); }
    private static ServerTickEvent.Post post() { return new ServerTickEvent.Post(() -> true, level.getServer()); }
    private static Object invoke(Object receiver, String name) throws Exception { var method = receiver.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(receiver); }
    private static Object get(Object receiver, String name) throws Exception { Class<?> type = receiver instanceof Class<?> c ? c : receiver.getClass(); var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(receiver instanceof Class<?> ? null : receiver); }
    private static void set(Object receiver, String name, Object value) throws Exception { var field = receiver.getClass().getDeclaredField(name); field.setAccessible(true); field.set(receiver, value); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
