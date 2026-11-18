package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.AutoDefenseMissileGuidance;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.mixin.MathematicalMissileImpactAccess;
import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import com.mojang.authlib.GameProfile;
import net.mcreator.myfirstmod.procedures.PortableantiairmissileexplodeProcedure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Actual installed native recipe, adopted impact Mixins and near/far packets; never shipped. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AirDefenseExplosionIntegration {
    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public static void start(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();
        Vec3 impact = new Vec3(8.5, 220, 8.5);
        List<Capture> viewers = new ArrayList<>();
        List<ServerPlayer> global = null, local = null;
        Throwable failure = null;
        int impacts = 0;
        try {
            if (Boolean.getBoolean("create_fire_control.modePolicyChecks")) GuidedMissileModeIntegration.verify(level);
            var globalField = PlayerList.class.getDeclaredField("players"); globalField.setAccessible(true);
            global = (List<ServerPlayer>) globalField.get(event.getServer().getPlayerList());
            var localField = ServerLevel.class.getDeclaredField("players"); localField.setAccessible(true);
            local = (List<ServerPlayer>) localField.get(level);
            level.getChunk(0, 0);
            for (double distance : new double[]{0, 1024, 250000}) {
                var profile = new GameProfile(UUID.randomUUID(), "AaEffectCheck");
                var player = new ServerPlayer(event.getServer(), level, profile, ClientInformation.createDefault());
                player.setPos(impact.add(distance, 0, 0)); player.setInvulnerable(true);
                var capture = new Capture(level, player, profile);
                viewers.add(capture); global.add(player); local.add(player);
            }
            for (String type : List.of("portableantiairmissiletanshe", "portableantiairmissiletanshe_2", "antiairmissiletanshe")) {
                viewers.forEach(Capture::clear);
                Entity original = create(level, type, impact);
                PortableantiairmissileexplodeProcedure.execute(level, impact.x, impact.y, impact.z, original);
                require(viewers.get(0).smallParticles == 84, type + " installed native hemisphere is not 84 small particles");
                require(viewers.get(1).smallParticles == 0, "Native forced packet reached 1024 blocks");
                require(viewers.stream().allMatch(viewer -> viewer.visuals.isEmpty()), "Unadopted missile was enlarged");

                for (boolean math : type.equals("antiairmissiletanshe") ? new boolean[]{false, true} : new boolean[]{false}) {
                    for (boolean display : type.equals("antiairmissiletanshe") ? new boolean[]{false, true} : new boolean[]{false}) {
                        viewers.forEach(Capture::clear);
                        Entity missile = create(level, type, impact);
                        missile.getPersistentData().putBoolean("CreateFireControlGuided", true);
                        missile.getPersistentData().putBoolean("CreateFireControlAaGuided", !type.equals("portableantiairmissiletanshe"));
                        missile.getPersistentData().putBoolean("CreateFireControlDisplayGuided", display);
                        missile.getPersistentData().putString(GuidedMissileMode.ROUTE, math ? "math" : "entity");
                        missile.getPersistentData().putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
                        missile.tickCount = 30;
                        boolean added = level.addFreshEntity(missile);
                        require(added != math, "Flight backend was not selected for " + type);
                        if (math) require(MathematicalMissileManager.contains(missile), "Detached mathematical shot missing");
                        float strength = MianbaoMissileDetonation.explosionStrength(missile);
                        ((MathematicalMissileImpactAccess) missile).createFireControl$impact(
                                new BlockHitResult(impact, Direction.WEST, BlockPos.containing(impact), false));
                        require(missile.isRemoved(), "Adopted impact failed to detonate " + type);
                        MianbaoMissileDetonation.detonate(missile);
                        MianbaoMissileEffects.broadcastExplosion(missile, strength, false);
                        require(viewers.get(0).visuals.size() == 1 && viewers.get(1).visuals.size() == 1
                                && viewers.get(2).visuals.isEmpty(), "Impact packet duplicated/missing or exceeded render range");
                        for (Capture viewer : viewers.subList(0, 2)) {
                            var payload = viewer.visuals.getFirst();
                            require(payload.airDefenseExplosion() && !payload.aircraftExplosion()
                                    && !payload.nativeParticlesExpected() && !payload.nuclear(), "Incorrect AA particle profile");
                            require(new Vec3(payload.x(), payload.y(), payload.z()).equals(impact)
                                    && payload.strength() == strength, "Impact position/blast strength changed");
                            require(viewer.smallParticles == 0, "Adopted AA played duplicate native packets");
                        }
                        impacts++;
                    }
                }
            }
            Entity aircraft = create(level, "aim_120_tanshe", impact);
            require(MianbaoMissileEffects.explosionPayload(aircraft, impact, 18, false).aircraftExplosion(),
                    "Existing aircraft visual profile changed");
            aircraft.discard();
            for (boolean math : new boolean[]{false, true}) {
                viewers.forEach(Capture::clear);
                Entity interceptor = create(level, "antiairmissiletanshe", impact);
                interceptor.getPersistentData().putBoolean("CreateFireControlAaGuided", true);
                interceptor.getPersistentData().putBoolean("CreateFireControlDisplayGuided", true);
                interceptor.getPersistentData().putString(GuidedMissileMode.ROUTE, math ? "math" : "entity");
                interceptor.getPersistentData().putInt(GuidedMissileMode.ROUTE_POLICY_TAG, GuidedMissileMode.DEFAULT_POLICY_VERSION);
                require(level.addFreshEntity(interceptor) != math, "Display interception backend mismatch");
                Entity threat = create(level, "aim_120_tanshe", impact.add(1, 0, 0));
                var contact = new ConnectedDisplayBlockEntity.Contact(
                        new ConnectedDisplayBlockEntity.TargetKey(threat.getUUID(), true), Set.of(),
                        threat.position(), Vec3.ZERO, new AABB(threat.position(), threat.position()).inflate(1),
                        1, 0, true);
                Class<?> guidedClass = Class.forName(AutoDefenseMissileGuidance.class.getName() + "$Guided");
                var constructor = guidedClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
                Object state = constructor.newInstance(interceptor, null, contact, interceptor.getDeltaMovement(), null, impact);
                var intercept = AutoDefenseMissileGuidance.class.getDeclaredMethod("intercept",
                        Entity.class, Entity.class, Vec3.class, guidedClass);
                intercept.setAccessible(true); intercept.invoke(null, interceptor, threat, impact, state);
                require(interceptor.isRemoved() && threat.isRemoved(), "Point defense lost its interception behavior");
                require(viewers.get(0).visuals.size() == 1 && viewers.get(1).visuals.size() == 1
                        && viewers.get(0).visuals.getFirst().airDefenseExplosion(),
                        "Successful missile interception still uses the little-bomb fallback");
                impacts++;
            }
            require(level.getChunkSource().getChunkNow(64, 0) == null, "Visual routing loaded the remote viewer's chunk");
            StabilizedTurret.LOGGER.info("AA EXPLOSION PASS: 3 installed native recipes, {} adopted impacts, entity/math, computer/display, one near/far profile and unchanged aircraft", impacts);
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("AA EXPLOSION FAILED", error);
        } finally {
            for (Capture viewer : viewers) {
                if (global != null) global.remove(viewer.player);
                if (local != null) local.remove(viewer.player);
            }
            try { Files.writeString(Path.of("aa-explosion-result.txt"), failure == null
                    ? "PASS: 3 native recipes; " + impacts + " adopted impacts; entity/math; near/far; no duplicate particles" : "FAIL: " + failure); }
            catch (Exception error) { StabilizedTurret.LOGGER.error("Cannot save AA explosion result", error); }
            event.getServer().halt(false);
        }
    }

    private static Entity create(ServerLevel level, String type, Vec3 position) {
        Entity entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mianbaos_modernwarfare:" + type)).create(level);
        require(entity != null, "Missing installed missile " + type);
        entity.setPos(position); entity.setDeltaMovement(1, 0, 0);
        return entity;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Capture extends ServerGamePacketListenerImpl {
        int smallParticles;
        final List<MissileExplosionPayload> visuals = new ArrayList<>();
        Capture(ServerLevel level, ServerPlayer player, GameProfile profile) {
            super(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player,
                    CommonListenerCookie.createInitial(profile, false));
        }
        void clear() { smallParticles = 0; visuals.clear(); }
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundLevelParticlesPacket particles
                    && BuiltInRegistries.PARTICLE_TYPE.getKey(particles.getParticle().getType())
                    .equals(ResourceLocation.parse("mianbaos_modernwarfare:explosionsmall"))) smallParticles++;
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof MissileExplosionPayload payload) visuals.add(payload);
        }
    }
}
