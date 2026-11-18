package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.MissileSettings;
import com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance;
import com.hooya.stabilizedturret.content.display.BallisticMissileGuidance;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.CruiseMissileGuidance;
import com.hooya.stabilizedturret.network.MissileRemoteStatePayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Installed entity/procedure mixins, real guidance expiry, and packet age boundaries. Never shipped. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MissilePowerIntegration {
    private static ServerLevel level;
    private static ConnectedDisplayBlockEntity display;
    private static final List<Entity> cleanup = new ArrayList<>();
    private static final List<String> report = new ArrayList<>();
    private static int stopTicks;

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        try {
            level.getChunk(0, 0);
            display = new ConnectedDisplayBlockEntity(new BlockPos(2, 240, 2),
                    ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
            display.setLevel(level);
            deviceConfigurationOwnership();
            nativeBoundaries();
            productionLifetimes();
            configurableNativeMissiles();
            configurableRuntimeProfiles();
            packet();
            Files.writeString(Path.of("missile-power-result.txt"), "PASS: native and mathematical land/cruise boundaries; "
                    + "all 8 cruise/land types expire at 2400 ticks; 2 ballistic variants at 2400; "
                    + "AA controller/display at 600; ordinary AA at 400; burnout at 1000 without speed change; packet age roundtrip");
            Files.write(Path.of("missile-power-details.txt"), report);
            StabilizedTurret.LOGGER.info("MISSILE POWER PASS: {}", report);
        } catch (Throwable failure) {
            StabilizedTurret.LOGGER.error("MISSILE POWER FAILED", failure);
            try { Files.writeString(Path.of("missile-power-result.txt"), "FAIL: " + failure); }
            catch (Exception io) { throw new RuntimeException(io); }
        } finally {
            cleanup.forEach(Entity::discard);
        }
    }

    private static void configurableRuntimeProfiles() throws Exception {
        int aircraftChecks = 0;
        for (String model : MissileSettings.defaultsByModel().keySet()) {
            if (model.endsWith("_df26")) continue;
            require(BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse("mianbaos_modernwarfare:" + model)),
                    "Configuration exposed an unregistered missile: " + model);
            Entity missile = create(model);
            var type = MianbaoAircraftCompat.typeOf(missile);
            if (type == null || MianbaoAircraftCompat.isOpticalGuided(type)) continue;
            var reference = MianbaoAircraftCompat.speedProfile(type, 2.0);
            var current = MianbaoAircraftCompat.speedProfile(missile, 2.0);
            for (int age : new int[]{0, 1, 10, 50, 100, 2400})
                require(Math.abs(reference.speedAt(age) - current.speedAt(age)) < 1e-8,
                        model + " default acceleration differs from the original profile");
            var speed = setting(model, "speedBlocksPerSecond");
            var power = setting(model, "poweredSeconds");
            double oldSpeed = speed.get(), oldPower = power.get();
            try {
                speed.set(120.0); power.set(oldPower * 2);
                var changed = MianbaoAircraftCompat.speedProfile(missile, 2.0);
                require(Math.abs(changed.maximumSpeed() - 6) < 1e-8
                                && changed.accelerationTicks() == reference.accelerationTicks() * 2
                                && Math.abs(changed.speedAt(changed.accelerationTicks() / 2) - 4) < 1e-8,
                        model + " speed/burn edit did not reach the production guidance profile");
                aircraftChecks++;
            } finally { speed.set(oldSpeed); power.set(oldPower); missile.discard(); }
        }
        for (String model : new String[]{"antiairmissiletanshe", "portableantiairmissiletanshe", "portableantiairmissiletanshe_2"}) {
            Entity missile = create(model);
            var reference = MianbaoAirDefenseCompat.speedProfile(2.0);
            var current = MianbaoAirDefenseCompat.speedProfile(missile, 2.0);
            require(reference.equals(current), model + " original display/controller AA profile changed at defaults");
            var speed = setting(model, "speedBlocksPerSecond");
            var power = setting(model, "poweredSeconds");
            double oldSpeed = speed.get(), oldPower = power.get();
            try {
                speed.set(120.0); power.set(oldPower * 2);
                var changed = MianbaoAirDefenseCompat.speedProfile(missile, 2.0);
                require(Math.abs(changed.speedAt(200) - 4) < 1e-8,
                        model + " custom AA speed/burn did not affect the actual guidance profile");
            } finally { speed.set(oldSpeed); power.set(oldPower); missile.discard(); }
        }
        configurableOpticalFlight();
        configurableBallisticCommands();
        for (var type : MianbaoCruiseMissileCompat.Type.values()) {
            String model = entityPath(type);
            var speed = setting(model, "speedBlocksPerSecond");
            var power = setting(model, "poweredSeconds");
            double oldSpeed = speed.get(), oldPower = power.get();
            Entity missile = create(model);
            missile.setDeltaMovement(.2, 0, 0);
            try {
                speed.set(120.0); power.set(oldPower * 2);
                var target = missile.position().add(1_000_000, 0, 0);
                CruiseMissileGuidance.armNext(display, BindingRef.capture(level, display.getBlockPos()), type,
                        null, null, target);
                CruiseMissileGuidance.adoptSpawnedMissile(missile);
                var field = CruiseMissileGuidance.class.getDeclaredField("GUIDED"); field.setAccessible(true);
                Object state = ((Map<?, ?>) field.get(null)).get(missile);
                require(state != null, model + " custom cruise shot was not adopted");
                var age = state.getClass().getDeclaredField("ageTicks"); age.setAccessible(true); age.setInt(state, 210);
                var command = state.getClass().getDeclaredMethod("speedAtAge"); command.setAccessible(true);
                require(Math.abs((double) command.invoke(state) - 3.0) < 1e-8,
                        model + " custom speed/burn did not change the production cruise command");
            } finally { speed.set(oldSpeed); power.set(oldPower); missile.discard(); }
        }
        report.add("configuration consumers: 33 registered models; " + aircraftChecks
                + " aircraft default/edited curves, all 3 AA profiles and all 8 cruise commands; optical flight speed/burn/expiry; "
                + "both ballistic models' phase commands and configured expiry");
    }

    @SuppressWarnings("unchecked")
    private static void configurableBallisticCommands() throws Exception {
        var phaseClass = Class.forName(BallisticMissileGuidance.class.getName() + "$Phase");
        var command = BallisticMissileGuidance.class.getDeclaredMethod("speedCommand", Entity.class, double.class, phaseClass);
        command.setAccessible(true);
        var guidedField = BallisticMissileGuidance.class.getDeclaredField("GUIDED"); guidedField.setAccessible(true);
        var guided = (Map<Entity, Object>) guidedField.get(null);
        var stateClass = Class.forName(BallisticMissileGuidance.class.getName() + "$State");
        var constructor = stateClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var age = stateClass.getDeclaredField("ageTicks"); age.setAccessible(true);
        for (boolean df26 : new boolean[]{false, true}) {
            String model = "ballistic_missile_entity" + (df26 ? "_df26" : "");
            Entity missile = create("ballistic_missile_entity");
            MianbaoBallisticMissileCompat.setDf26(missile, df26);
            var speed = setting(model, "speedBlocksPerSecond");
            var power = setting(model, "poweredSeconds");
            var life = setting(model, "lifetimeSeconds");
            double oldSpeed = speed.get(), oldPower = power.get(), oldLife = life.get();
            try {
                for (Object phase : phaseClass.getEnumConstants()) {
                    int index = ((Enum<?>) phase).ordinal();
                    double baseSpeed = new double[]{17.015 * .65, 34.03, 51.045}[index];
                    double baseAcceleration = new double[]{.045, .12, .22}[index];
                    require(Math.abs((double) command.invoke(null, missile, 0.0, phase) - baseAcceleration) < 1e-8,
                            model + " default ballistic acceleration changed");
                    speed.set(oldSpeed * 2);
                    require(Math.abs((double) command.invoke(null, missile, 0.0, phase) - baseAcceleration * 2) < 1e-8,
                            model + " speed edit did not affect the production ballistic command");
                    power.set(oldPower * 2);
                    require(Math.abs((double) command.invoke(null, missile, 0.0, phase) - baseAcceleration) < 1e-8,
                            model + " powered duration did not affect the production ballistic acceleration");
                    double velocity = 0;
                    for (int i = 0; i < 1200; i++) velocity = (double) command.invoke(null, missile, velocity, phase);
                    require(Math.abs(velocity - baseSpeed * 2) < 1e-8, model + " custom phase speed cap ignored");
                    speed.set(oldSpeed); power.set(oldPower);
                }
                life.set(3.0);
                var target = missile.position().add(1_000_000, 0, 0);
                Object state = constructor.newInstance(level, display, null, target, target, missile.position());
                age.setInt(state, 59); guided.put(missile, state);
                BallisticMissileGuidance.onTick(new ServerTickEvent.Post(() -> true, level.getServer()));
                require(missile.isRemoved(), model + " configured lifetime did not expire in the production tick");
            } finally {
                speed.set(oldSpeed); power.set(oldPower); life.set(oldLife);
                missile.discard(); guided.remove(missile);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void configurableOpticalFlight() throws Exception {
        String model = "opticalguidedmissiletanshe";
        var speed = setting(model, "speedBlocksPerSecond");
        var power = setting(model, "poweredSeconds");
        var lifetime = setting(model, "lifetimeSeconds");
        double oldSpeed = speed.get(), oldPower = power.get(), oldLife = lifetime.get();
        var activeField = MianbaoOpticalMissileHandler.class.getDeclaredField("ACTIVE"); activeField.setAccessible(true);
        var active = (Map<Entity, Object>) activeField.get(null);
        var stateClass = Class.forName(MianbaoOpticalMissileHandler.class.getName() + "$State");
        var constructor = stateClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var age = stateClass.getDeclaredField("age"); age.setAccessible(true);
        var computer = new StabilizerControllerBlockEntity(new BlockPos(4, 240, 4),
                ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState()); computer.setLevel(level);
        Entity missile = create(model);
        Object state = constructor.newInstance(computer, new Vec3(1, 0, 0), 2.0);
        try {
            speed.set(120.0); power.set(oldPower * 2); lifetime.set(60.0);
            age.setInt(state, 399); active.put(missile, state);
            var tick = new ServerTickEvent.Post(() -> true, level.getServer());
            MianbaoOpticalMissileHandler.tick(tick);
            require(Math.abs(missile.getDeltaMovement().length() - 4.0) < 1e-8,
                    "Optical speed/burn change did not affect the actual flight command");
            lifetime.set(3.0); age.setInt(state, 59);
            MianbaoOpticalMissileHandler.tick(tick);
            require(missile.isRemoved(), "Optical configured lifetime did not expire in the production tick");
        } finally {
            speed.set(oldSpeed); power.set(oldPower); lifetime.set(oldLife);
            missile.discard(); active.remove(missile);
        }
    }

    private static void deviceConfigurationOwnership() {
        var firstTag = new CompoundTag();
        firstTag.putDouble("AaProximityFuseRange", 1.5);
        firstTag.putDouble("AircraftProximityFuseRange", 3.0);
        firstTag.putDouble("AircraftFuseSafetySeconds", .2);
        firstTag.putDouble("RadarRange", 2500.0);
        var secondTag = new CompoundTag();
        secondTag.putDouble("AaProximityFuseRange", 6.0);
        secondTag.putDouble("AircraftProximityFuseRange", 9.0);
        secondTag.putDouble("AircraftFuseSafetySeconds", 1.5);
        secondTag.putDouble("RadarRange", 18000.0);
        var first = new StabilizerControllerBlockEntity(new BlockPos(4, 240, 4),
                ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var second = new StabilizerControllerBlockEntity(new BlockPos(5, 240, 4),
                ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        first.setLevel(level); second.setLevel(level);
        first.loadWithComponents(firstTag, level.registryAccess());
        second.loadWithComponents(secondTag, level.registryAccess());
        require(first.getAaProximityFuseRange() == 1.5 && second.getAaProximityFuseRange() == 6.0,
                "Ground AA fuze settings must be independent for each computer");
        require(first.getAircraftProximityFuseRange() == 3.0 && second.getAircraftProximityFuseRange() == 9.0,
                "Aircraft fuze settings must be independent for each computer");
        var restored = new StabilizerControllerBlockEntity(new BlockPos(6, 240, 4),
                ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        restored.setLevel(level);
        restored.loadWithComponents(first.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        require(restored.getAaProximityFuseRange() == 1.5 && restored.getAircraftProximityFuseRange() == 3.0
                        && restored.getAircraftFuseSafetySeconds() == .2 && restored.getRadarRange() == 2500.0,
                "Computer fuze, safety and radar settings must roundtrip in device NBT");
        var displayTag = new CompoundTag();
        displayTag.putDouble("MissileProximityRange", 2.0);
        var owner = new ConnectedDisplayBlockEntity(new BlockPos(7, 240, 4),
                ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
        owner.setLevel(level);
        owner.loadWithComponents(displayTag, level.registryAccess());
        var restoredOwner = new ConnectedDisplayBlockEntity(new BlockPos(8, 240, 4),
                ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
        restoredOwner.setLevel(level);
        restoredOwner.loadWithComponents(owner.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        require(restoredOwner.getMissileProximityRange() == 2.0,
                "Display-owned missile fuze setting must roundtrip in device NBT");
        report.add("device ownership: independent AA/aircraft fuzes and safety/radar settings; computer/display NBT roundtrip");
    }

    private static void nativeBoundaries() {
        for (var type : MianbaoCruiseMissileCompat.Type.values()) {
            for (int age : new int[]{619, 999, 1000, 1199, 1200, 2398}) {
                Entity missile = create(entityPath(type));
                missile.setPos(8.5, 1000, 8.5);
                missile.setDeltaMovement(2, 0, 0);
                // ServerLevel increments tickCount before calling Entity.tick; this isolated call does the same.
                missile.tickCount = age + 1;
                missile.getPersistentData().putBoolean("CreateFireControlCruiseGuided", true);
                missile.getPersistentData().putString(GuidedMissileMode.ROUTE, "entity");
                missile.getPersistentData().putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
                missile.getPersistentData().putDouble("fadongji", age);
                missile.getPersistentData().putDouble("time", age);
                missile.getPersistentData().putBoolean("jamed", true);
                MianbaoCruiseMissileCompat.writeNativeTarget(missile, type, new Vec3(1_000_000, 1000, 100_000));
                missile.tick();
                require(!missile.isRemoved(), type.key() + " native premature expiry from " + age);
                require(LandAttackMissileFlight.emitting(missile) == (age + 1 < 1000), type.key() + " native burn boundary " + age);
                missile.discard();
            }
            report.add(type.key() + ": native survives 620, 1000, 1200, 1201, 2399; engine cutoff at 1000");
        }
        Entity control = create("cruisemissiletanshe");
        control.tickCount = 1200;
        control.tick();
        require(control.isRemoved(), "Unadopted native cleanup changed");
    }

    private static void productionLifetimes() throws Exception {
        Map<Entity, Integer> shots = new LinkedHashMap<>();
        var ref = BindingRef.capture(level, display.getBlockPos());
        var targetKey = new ConnectedDisplayBlockEntity.TargetKey(UUID.randomUUID(), false);
        for (var type : MianbaoCruiseMissileCompat.Type.values()) {
            Entity missile = create(entityPath(type));
            Vec3 target = missile.position().add(1_000_000, 0, 0);
            CruiseMissileGuidance.armNext(display, ref, type, targetKey, null, target);
            CruiseMissileGuidance.adoptSpawnedMissile(missile);
            missile.getPersistentData().putString(GuidedMissileMode.ROUTE, "math");
            missile.getPersistentData().putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
            require(!level.addFreshEntity(missile) && MathematicalMissileManager.contains(missile), "Not mathematical " + type.key());
            shots.put(missile, 2400);
        }
        // Exercise the original ballistic cleanup loop for both the native body and DF-26.
        Field guidedField = BallisticMissileGuidance.class.getDeclaredField("GUIDED");
        guidedField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Entity, Object> guided = (Map<Entity, Object>) guidedField.get(null);
        var stateType = Class.forName(BallisticMissileGuidance.class.getName() + "$State");
        var constructor = stateType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        for (boolean df26 : new boolean[]{false, true}) {
            Entity missile = create("ballistic_missile_entity");
            MianbaoBallisticMissileCompat.setDf26(missile, df26);
            missile.getPersistentData().putBoolean(BallisticMissileGuidance.GUIDED_TAG, true);
            Vec3 target = missile.position().add(1_000_000, 0, 0);
            guided.put(missile, constructor.newInstance(level, display, targetKey, null, target, missile.position()));
            shots.put(missile, 2400);
        }
        Entity aa = create("antiairmissiletanshe");
        var aaData = aa.getPersistentData();
        aaData.putBoolean("CreateFireControlAaGuided", true);
        aaData.putLong("CreateFireControlAaController", new BlockPos(0, 200, 0).asLong());
        aaData.putBoolean("CreateFireControlAaControllerSubLevelKnown", true);
        aaData.putUUID("CreateFireControlAaTarget", UUID.randomUUID());
        aaData.putString(GuidedMissileMode.ROUTE, "math");
        aaData.putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
        level.addFreshEntity(aa);
        shots.put(aa, 600);

        Entity displayAa = create("antiairmissiletanshe");
        var data = displayAa.getPersistentData();
        data.putBoolean("CreateFireControlDisplayGuided", true);
        data.put("CreateFireControlDisplayRef", ref.save());
        var contact = new net.minecraft.nbt.CompoundTag();
        contact.putUUID("Id", UUID.randomUUID()); contact.putBoolean("Living", false);
        contact.putDouble("X", 1_000_000); contact.putDouble("Y", 1000); contact.putDouble("Z", 100_000);
        data.put("CreateFireControlDisplayContact", contact);
        data.putString(GuidedMissileMode.ROUTE, "math");
        data.putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
        level.addFreshEntity(displayAa);
        shots.put(displayAa, 600);

        Entity ordinary = create("portableantiairmissiletanshe");
        ordinary.setPos(8.5, 1000, 8.5);
        ordinary.getPersistentData().putBoolean(OrdinaryAirDefenseGuidance.GUIDED_TAG, true);
        ordinary.getPersistentData().putInt(OrdinaryAirDefenseGuidance.AGE_TAG, 398);
        ordinary.getPersistentData().put("CreateFireControlOrdinaryAaOwner", ref.save());
        OrdinaryAirDefenseGuidance.onJoin(new EntityJoinLevelEvent(ordinary, level));
        shots.put(ordinary, 2);

        Map<Entity, Double> poweredSpeed = new LinkedHashMap<>();
        long startTime = level.getGameTime();
        for (int age = 1; age <= 2400; age++) {
            ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(startTime + age);
            var tick = new ServerTickEvent.Post(() -> true, level.getServer());
            CruiseMissileGuidance.onTick(tick);
            BallisticMissileGuidance.onTick(tick);
            MianbaoAirDefenseGuidanceHandler.onServerTickPost(tick);
            AutoDefenseMissileGuidance.onTick(tick);
            OrdinaryAirDefenseGuidance.tick(tick);
            for (var entry : shots.entrySet()) {
                Entity missile = entry.getKey();
                String name = BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()).getPath();
                require(missile.isRemoved() == (age >= entry.getValue()), name + " wrong removal at " + age);
                if (MianbaoCruiseMissileCompat.isMissile(missile) && age == 999)
                    poweredSpeed.put(missile, missile.getDeltaMovement().length());
                if (MianbaoCruiseMissileCompat.isMissile(missile) && age == 1001) {
                    require(!LandAttackMissileFlight.emitting(missile), name + " still powered after 50s");
                    require(Math.abs(missile.getDeltaMovement().length() - poweredSpeed.get(missile)) < 1e-8,
                            name + " speed changed on burnout");
                }
                if (age == entry.getValue()) report.add(name + (missile == aa ? "@computer" : missile == displayAa ? "@display" : "")
                        + ": production cleanup at " + (missile == ordinary ? 400 : age));
            }
        }
    }

    /** Executes installed native procedures, then the actual computer cleanup/velocity loop. */
    private static void configurableNativeMissiles() throws Exception {
        var computer = new StabilizerControllerBlockEntity(new BlockPos(4, 240, 4),
                ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState()) {
            @Override public boolean isMianbaoMissileGuidanceReady() { return true; }
            @Override public Vec3 getMeasuredAimOrigin() { return new Vec3(0, 1000, 0); }
            @Override public Vec3 getMeasuredAimDirection() { return new Vec3(1, 0, 0); }
        };
        computer.setLevel(level);
        String[] models = {"antitankmissile_1tanshe", "antitankmissile_2tanshe", "antitankmissile_5tanshe",
                "antitankmissile_6tanshe", "antitankmissile_7tanshe"};
        int[] oldExpiry = {200, 200, 200, 150, 250};
        for (int index = 0; index < models.length; index++) {
            String model = models[index];
            var lifetime = setting(model, "lifetimeSeconds");
            var powered = setting(model, "poweredSeconds");
            double oldLife = lifetime.get(), oldBurn = powered.get();
            try {
                Entity originalTimeout = create(model);
                originalTimeout.getPersistentData().putBoolean("CreateFireControlGuided", true);
                originalTimeout.tickCount = oldExpiry[index] + 1;
                nativeProcedure(originalTimeout);
                require(originalTimeout.isRemoved(), model + " default native timeout changed from the pre-menu version");
                // A configured long shot must survive the original procedure's direct timeout.
                lifetime.set(25.0);
                Entity missile = create(model);
                missile.getPersistentData().putBoolean("CreateFireControlGuided", true);
                missile.tickCount = oldExpiry[index] + 1;
                nativeProcedure(missile);
                require(!missile.isRemoved(), model + " still capped by original native timeout");
                missile.getPersistentData().putInt("CreateFireControlGuidedAge", 498);
                require(MianbaoMissileGuidanceHandler.adopt(missile, computer), "Cannot adopt " + model);
                computerTick(missile);
                require(!missile.isRemoved(), model + " removed before configured 25 seconds");
                computerTick(missile);
                require(missile.isRemoved(), model + " did not expire at configured 25 seconds");

                lifetime.set(3.0);
                Entity shortShot = create(model);
                shortShot.getPersistentData().putInt("CreateFireControlGuidedAge", 58);
                require(MianbaoMissileGuidanceHandler.adopt(shortShot, computer), "Cannot adopt short " + model);
                computerTick(shortShot);
                require(!shortShot.isRemoved(), model + " removed before configured 3 seconds");
                computerTick(shortShot);
                require(shortShot.isRemoved(), model + " did not expire at configured 3 seconds");

                // At age 15 (or delayed age 25), the default boost is over; a doubled boost still runs.
                int sample = index == 1 || index == 2 ? 25 : 15;
                Entity defaultBurn = create(model);
                defaultBurn.getPersistentData().putBoolean("CreateFireControlGuided", true);
                defaultBurn.tickCount = sample; defaultBurn.setDeltaMovement(1, 0, 0);
                nativeProcedure(defaultBurn);
                double defaultX = defaultBurn.getDeltaMovement().x;
                powered.set(oldBurn * 2);
                Entity longerBurn = create(model);
                longerBurn.getPersistentData().putBoolean("CreateFireControlGuided", true);
                longerBurn.tickCount = sample; longerBurn.setDeltaMovement(1, 0, 0);
                nativeProcedure(longerBurn);
                require(longerBurn.getDeltaMovement().x > defaultX * 1.1,
                        model + " configured powered duration did not extend the native boost");
                defaultBurn.discard(); longerBurn.discard();
                report.add(model + ": native old expiry bypass; configured 3s/25s cleanup; doubled native boost boundary");
            } finally { lifetime.set(oldLife); powered.set(oldBurn); }
        }
        var multiplier = setting(models[0], "speedMultiplier");
        double oldSpeed = multiplier.get();
        try {
            multiplier.set(1.0);
            double normal = launchSpeedAfterClearance(models[0], computer);
            multiplier.set(2.0);
            double doubled = launchSpeedAfterClearance(models[0], computer);
            require(Math.abs(doubled / normal - 2) < 1e-8,
                    "Configured AT speed was consumed during the first two launch-clearance ticks");
            report.add("AT1: launch speed multiplier2 takes effect after the original two-tick clearance");
        } finally { multiplier.set(oldSpeed); }
    }

    private static net.neoforged.neoforge.common.ModConfigSpec.DoubleValue setting(String model, String field) {
        return (net.neoforged.neoforge.common.ModConfigSpec.DoubleValue)
                com.hooya.stabilizedturret.config.FireControlSettings.SERVER_SPEC.getValues()
                        .get("missiles." + model + "." + field);
    }

    private static void computerTick(Entity missile) {
        var data = (net.minecraft.world.level.storage.ServerLevelData) level.getLevelData();
        data.setGameTime(level.getGameTime() + 1);
        MianbaoMissileCompat.noteNativeMissileTick(missile);
        MianbaoMissileGuidanceHandler.onServerTickPost(new ServerTickEvent.Post(() -> true, level.getServer()));
    }

    private static double launchSpeedAfterClearance(String model, StabilizerControllerBlockEntity computer) throws Exception {
        Entity missile = create(model);
        missile.setDeltaMovement(1, 0, 0);
        require(MianbaoMissileGuidanceHandler.adopt(missile, computer), "Cannot adopt speed probe");
        for (int age = 1; age <= 3; age++) {
            missile.tickCount = age;
            nativeProcedure(missile);
            computerTick(missile);
        }
        double speed = missile.getDeltaMovement().length();
        missile.discard();
        return speed;
    }

    private static void nativeProcedure(Entity missile) throws Exception {
        String model = BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()).getPath();
        String procedure = switch (model) {
            case "antitankmissile_1tanshe" -> "Antitankmissile1trailProcedure";
            case "antitankmissile_2tanshe" -> "Antitankemissile2trailProcedure";
            case "antitankmissile_5tanshe" -> "Antitankmissile5trailProcedure";
            case "antitankmissile_6tanshe" -> "Antitankmissile6trailProcedure";
            case "antitankmissile_7tanshe" -> "Antitankmissile_7tanshe_trailProcedure";
            default -> throw new AssertionError(model);
        };
        var type = Class.forName("net.mcreator.myfirstmod.procedures." + procedure);
        if (model.equals("antitankmissile_7tanshe")) {
            var method = type.getMethod("execute", net.minecraft.world.level.LevelAccessor.class,
                    double.class, double.class, double.class, Entity.class, Entity.class);
            method.invoke(null, level, missile.getX(), missile.getY(), missile.getZ(), missile, missile);
        } else {
            var method = type.getMethod("execute", net.minecraft.world.level.LevelAccessor.class,
                    double.class, double.class, double.class, Entity.class);
            method.invoke(null, level, missile.getX(), missile.getY(), missile.getZ(), missile);
        }
    }

    private static void packet() {
        Entity genericAge = create("antitankmissile_1tanshe");
        genericAge.getPersistentData().putBoolean("CreateFireControlGuided", true);
        genericAge.tickCount = 77;
        require(AirToAirMissileFlight.packetAge(genericAge) == 77, "Native configured burn age missing from existing visual channel");
        AirToAirMissileFlight.acceptAge(genericAge, 123);
        require(genericAge.getPersistentData().getInt("CreateFireControlConfiguredFlightAge") == 123,
                "Native proxy burn age was not accepted");
        require(!AirToAirMissileFlight.managed(genericAge), "Generic visual age reclassified an anti-tank missile as AAM");
        genericAge.discard();
        var value = new MissileRemoteStatePayload(UUID.randomUUID(), ResourceLocation.parse("mianbaos_modernwarfare:groundmissiletanshe"),
                1, 1000, 2, 34.03, 0, 0, 0, 0, 0, 1, false, level.getGameTime(), -2, 1000);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            MissileRemoteStatePayload.STREAM_CODEC.encode(buffer, value);
            require(value.equals(MissileRemoteStatePayload.STREAM_CODEC.decode(buffer)), "Remote burnout age packet changed");
        } finally { buffer.release(); }
        report.add("remote packet: burnout age roundtrip; native generic age accepted without AAM reclassification");
    }

    private static Entity create(String path) {
        Entity entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mianbaos_modernwarfare:" + path)).create(level);
        require(entity != null, "Missing type " + path);
        entity.setPos(100_000.5, 1000, 100_000.5 + cleanup.size() * 64);
        entity.setDeltaMovement(17.015, 0, 0); entity.setNoGravity(true); entity.noPhysics = true;
        cleanup.add(entity);
        return entity;
    }

    private static String entityPath(MianbaoCruiseMissileCompat.Type type) {
        try {
            var field = MianbaoCruiseMissileCompat.Type.class.getDeclaredField("entityId");
            field.setAccessible(true);
            return ((ResourceLocation) field.get(type)).getPath();
        } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
    }

    @SubscribeEvent public static void stop(ServerTickEvent.Post event) {
        if (++stopTicks == 5) event.getServer().halt(false);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
