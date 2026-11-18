package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AirToAirBurnIntegration {
    private record Fixture(String entity, String procedure, int burn, int life) { }
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("closemissile_1tanshe", "CloseMissile1trailProcedure", 100, 600),
            new Fixture("closemissile_2tanshe", "CloseMissile1trailProcedure", 100, 600),
            new Fixture("missile_2tanshe", "Missile2trailProcedure", 160, 1600),
            new Fixture("medium_aim_missile_2tanshe", "Missile2trailProcedure", 160, 1600),
            new Fixture("mica_em_tanshe", "Missile2trailProcedure", 160, 1600),
            new Fixture("aim_120_tanshe", "Missile2trailProcedure", 160, 1600),
            new Fixture("farmissile_1tanshe", "FarMissile1trailProcedure", 240, 2400),
            new Fixture("farmissile_2tanshe", "FarMissile1trailProcedure", 240, 2400));
    private static final List<String> REPORT = new ArrayList<>();
    private static ServerLevel level;
    private static FakePlayer listener;
    private static int engineSounds;

    @SubscribeEvent public static void sound(PlayLevelSoundEvent.AtPosition event) {
        if (event.getLevel() == level && event.getSound() != null
                && event.getSound().value().getLocation().equals(id("missile_engine_far"))) engineSounds++;
    }

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        long originalTime = level.getGameTime();
        Throwable failure = null;
        try {
            for (int x = -2; x <= 4; x++) for (int z = -1; z <= 1; z++) level.getChunk(x, z);
            listener = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "AamBurnListener"));
            listener.setPos(8.5, 250, 8.5); level.addNewPlayer(listener);
            for (Fixture fixture : FIXTURES) {
                effects(fixture);
                for (boolean math : new boolean[]{false, true}) flight(fixture, math);
            }
            checkPackets();
            REPORT.add("PASS: eight AAM variants; burn/sound boundaries 100/160/240; exact powered lifetimes 600/1600/2400 in native and math routes; original native timeout and 60s limits bypassed only for adopted AAMs; delayed ignition clock; save restore; late-observer packet age");
        } catch (Throwable error) {
            failure = error; StabilizedTurret.LOGGER.error("AAM BURN CHECK FAILED", error);
        } finally {
            if (listener != null) listener.discard();
            level.getServer().getWorldData().overworldData().setGameTime(originalTime);
            try {
                Files.write(Path.of("aam-burn-details.txt"), REPORT);
                Files.writeString(Path.of("aam-burn-result.txt"), failure == null ? "PASS" : "FAIL: " + failure);
            } catch (Exception error) { throw new RuntimeException(error); }
            level.getServer().halt(false);
        }
    }

    private static Entity create(Fixture fixture, boolean managed) {
        Entity missile = BuiltInRegistries.ENTITY_TYPE.get(id(fixture.entity())).create(level);
        require(missile != null, fixture.entity() + " installed");
        missile.setPos(8.5, 250, 8.5); missile.setDeltaMovement(.5, 0, 0); missile.setNoGravity(true);
        missile.noPhysics = true;
        missile.getPersistentData().putBoolean("CreateFireControlAircraftGuided", managed);
        missile.getPersistentData().putBoolean("jamed", true);
        return missile;
    }

    private static void effects(Fixture fixture) throws Exception {
        var procedure = Class.forName("net.mcreator.myfirstmod.procedures." + fixture.procedure())
                .getMethod("execute", LevelAccessor.class, double.class, double.class, double.class, Entity.class);
        Entity missile = create(fixture, true);
        var type = MianbaoAircraftCompat.typeOf(missile);
        require(MianbaoAircraftCompat.lifetimeTicks(type) == fixture.life(), "requested lifetime " + fixture.entity());
        require(MianbaoAircraftCompat.airToAirBurnTicks(type) == fixture.burn(), "requested burn " + fixture.entity());
        var profile = MianbaoMissileFlightProfile.of(fixture.entity());
        require(profile.smoke(fixture.burn()) && !profile.smoke(fixture.burn() + 1), "math smoke/sound boundary");
        for (int age : new int[]{0, fixture.burn() - 1, fixture.burn(), fixture.burn() + 1, fixture.life() - 1}) {
            missile.getPersistentData().putInt("CreateFireControlAircraftAge", age);
            missile.getPersistentData().putDouble("fadongji1", 9999); // Raw stale timers cannot restart/extend effects.
            missile.tickCount = 1210;
            if (type == MianbaoAircraftCompat.WeaponType.FAR) {
                var release = new net.minecraft.nbt.CompoundTag();
                release.putBoolean("SoundPlayed", true); release.putLong("LastStep", level.getGameTime() - age);
                missile.getPersistentData().put(FarMissileIgnitionHeading.RELEASE_TAG, release);
            }
            engineSounds = 0;
            procedure.invoke(null, level, missile.getX(), missile.getY(), missile.getZ(), missile);
            require(!missile.isRemoved(), fixture.entity() + " original native expiry still active at " + age);
            require(engineSounds == 0, fixture.entity() + " native one-shots must yield to the stoppable client engine loop");
            require(AirToAirMissileFlight.emitting(missile) == (age < fixture.burn()), fixture.entity() + " remote/observer burn gate");
            int[] flames = {0};
            LevelAccessor probe = (LevelAccessor) java.lang.reflect.Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),
                    new Class<?>[]{LevelAccessor.class}, (receiver, method, args) -> {
                        if (method.getName().equals("addParticle")) flames[0]++;
                        if (java.util.List.class.isAssignableFrom(method.getReturnType())) return List.of();
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == long.class) return 0L;
                        if (method.getReturnType() == double.class) return 0D;
                        return null;
                    });
            procedure.invoke(null, probe, missile.getX(), missile.getY(), missile.getZ(), missile);
            require((flames[0] > 0) == (age < fixture.burn()), fixture.entity() + " actual native flame emission at powered age " + age);
        }
        Entity nativeControl = create(fixture, false); nativeControl.tickCount = 1; engineSounds = 0;
        procedure.invoke(null, level, nativeControl.getX(), nativeControl.getY(), nativeControl.getZ(), nativeControl);
        require(engineSounds > 0, "unmanaged engine sound remains native"); nativeControl.discard();
        Entity unadopted = create(fixture, false); unadopted.tickCount = 1201; unadopted.tick();
        require(unadopted.isRemoved(), "unmanaged native cleanup retained");
        if (type == MianbaoAircraftCompat.WeaponType.FAR) {
            var release = missile.getPersistentData().getCompound(FarMissileIgnitionHeading.RELEASE_TAG);
            release.putBoolean("SoundPlayed", false);
            require(AirToAirMissileFlight.poweredAge(missile) == -1 && !AirToAirMissileFlight.emitting(missile), "unpowered release is silent/no exhaust");
        }
        missile.discard();
        REPORT.add("PASS effects " + fixture.entity() + " burn=" + fixture.burn() + " engine stops with smoke/flame");
    }

    private static void flight(Fixture fixture, boolean math) throws Exception {
        Entity missile = create(fixture, true);
        var data = missile.getPersistentData();
        data.putLong("CreateFireControlAircraftController", new BlockPos(0, 240, 0).asLong());
        data.putBoolean("CreateFireControlAircraftControllerSubLevelKnown", true);
        data.putString(GuidedMissileMode.ROUTE, math ? "math" : "entity");
        data.putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
        if (MianbaoAircraftCompat.typeOf(missile) == MianbaoAircraftCompat.WeaponType.FAR) {
            var release = new net.minecraft.nbt.CompoundTag();
            release.putBoolean("SoundPlayed", true); release.putBoolean("GuidanceReady", true);
            release.putBoolean("HandoffChecked", true); release.putLong("LastStep", level.getGameTime());
            release.putInt("Age", 10); release.putInt("IgnitionTick", 10);
            release.putInt("IgnitionDropAge", 10); missile.tickCount = 10;
            data.put(FarMissileIgnitionHeading.RELEASE_TAG, release);
        }
        level.addFreshEntity(missile);
        require(MathematicalMissileManager.contains(missile) == math, "correct route");
        for (int age = 1; age <= fixture.life(); age++) {
            level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1);
            missile.setPos(8.5, 250, 8.5);
            listener.setPos(8.5, 250, 8.5);
            MianbaoAircraftGuidanceHandler.beforeTick(new ServerTickEvent.Pre(() -> true, level.getServer()));
            if (!math) { missile.tickCount++; missile.tick(); }
            MianbaoAircraftGuidanceHandler.tick(post());
            require(missile.isRemoved() == (age == fixture.life()), fixture.entity() + " " + math + " wrong lifetime at powered tick " + age);
            if (age == fixture.burn() + 1 || age == 1210 || age == fixture.life() - 1) {
                require(!AirToAirMissileFlight.emitting(missile), "no new remote exhaust after burnout");
                if (!math) MianbaoAircraftGuidanceHandler.onJoin(new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(missile, level, true));
            }
        }
        MathematicalMissileManager.sync(post());
        REPORT.add("PASS lifetime " + fixture.entity() + " route=" + (math ? "math" : "entity") + " poweredTicks=" + fixture.life());
    }

    private static void checkPackets() {
        for (int age : new int[]{-2, -1, 0, 100, 160, 240, 1600, 2400}) {
            var packet = new com.hooya.stabilizedturret.network.MissileRemoteStatePayload(UUID.randomUUID(), id("farmissile_1tanshe"),
                    0, 1, 2, 3, 4, 5, 0, 0, 0, 1, false, level.getGameTime(), age);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                com.hooya.stabilizedturret.network.MissileRemoteStatePayload.STREAM_CODEC.encode(buffer, packet);
                require(packet.equals(com.hooya.stabilizedturret.network.MissileRemoteStatePayload.STREAM_CODEC.decode(buffer)), "powered age round trip " + age);
            } finally { buffer.release(); }
        }
    }

    private static ServerTickEvent.Post post() { return new ServerTickEvent.Post(() -> true, level.getServer()); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
