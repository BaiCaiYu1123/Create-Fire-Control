package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.MathematicalMissilePayload;
import com.hooya.stabilizedturret.network.WeaponViewPayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Real two-player server packet routing, without sockets, client chunks, or an observation broadcast. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class WeaponViewServerChecks {
    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public static void start(ServerStartedEvent event) {
        if (!Boolean.getBoolean("create_fire_control.weaponViewServerChecks")) return;
        Throwable failure = null;
        List<ServerPlayer> players = null;
        Map<UUID, Object> shots = null;
        var added = new ArrayList<ServerPlayer>();
        var controllers = new ArrayList<Controller>();
        UUID missileId = UUID.randomUUID();
        ServerLevel level = event.getServer().overworld();
        try {
            var playersField = PlayerList.class.getDeclaredField("players"); playersField.setAccessible(true);
            players = (List<ServerPlayer>) playersField.get(event.getServer().getPlayerList());
            var captures = new ArrayList<Capture>();
            for (String name : List.of("ViewOwner", "OtherViewer")) {
                var profile = new GameProfile(UUID.randomUUID(), name);
                var player = new ServerPlayer(event.getServer(), level, profile, ClientInformation.createDefault());
                player.setPos(0, 230, 0);
                var capture = new Capture(level, player, profile);
                player.connection = capture;
                players.add(player); added.add(player); captures.add(capture);
                var controller = new Controller(level, new BlockPos(0, 230, controllers.size()));
                controllers.add(controller);
            }
            Entity missile = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(
                    "mianbaos_modernwarfare:farmissile_1tanshe")).orElseThrow().create(level);
            require(missile != null, "Missing installed native missile type");
            missile.setUUID(missileId); missile.setPos(10000, 230, 10000); missile.setDeltaMovement(new Vec3(34.03, 0, 0));
            var activeField = MathematicalMissileManager.class.getDeclaredField("ACTIVE"); activeField.setAccessible(true);
            shots = (Map<UUID, Object>) activeField.get(null);
            Class<?> shotClass = Class.forName(MathematicalMissileManager.class.getName() + "$Shot");
            var ctor = shotClass.getDeclaredConstructor(Entity.class, ServerLevel.class, Vec3.class); ctor.setAccessible(true);
            shots.put(missileId, ctor.newInstance(missile, level, missile.position()));
            var forced = new HashSet<Long>(level.getForcedChunks());
            var tick = new ServerTickEvent.Post(() -> true, event.getServer());
            for (Controller controller : controllers) AirborneWeaponViewHandler.launched(controller, missile);

            // Both clients receive the same public flight, before and during private observation.
            MathematicalMissileManager.sync(tick);
            for (int i = 0; i < 2; i++) require(captures.get(i).hasFlight(missileId), "Public flight missing for player " + i);
            controllers.get(0).viewer = added.get(0);
            captures.forEach(Capture::clear);
            AirborneWeaponViewHandler.tick(tick);
            require(captures.get(0).views.size() == 1 && missileId.equals(captures.get(0).views.getFirst().id()),
                    "Owner did not receive its private camera pose");
            require(captures.get(1).views.isEmpty(), "One player's camera state leaked to another player");
            MathematicalMissileManager.sync(tick);
            require(captures.get(0).hasFlight(missileId) && captures.get(1).hasFlight(missileId),
                    "Observation suppressed public flight updates for another client");

            // A second player can observe the same missile independently.
            controllers.get(1).viewer = added.get(1);
            AirborneWeaponViewHandler.tick(tick);
            controllers.get(0).viewer = null;
            captures.forEach(Capture::clear);
            AirborneWeaponViewHandler.tick(tick);
            MathematicalMissileManager.sync(tick);
            require(captures.get(0).views.size() == 1 && captures.get(0).views.getFirst().id() == null,
                    "Leaving did not stop the first player's camera");
            require(!captures.get(1).views.isEmpty() && captures.get(1).views.stream().allMatch(p -> missileId.equals(p.id())),
                    "The first player leaving interrupted the second player's camera");
            require(captures.stream().allMatch(c -> c.hasFlight(missileId)), "Leaving removed a shared flight/particle source");
            require(added.stream().allMatch(p -> p.getCamera() == p), "Observation changed server player cameras");
            require(level.getForcedChunks().equals(forced) && level.getChunkSource().getChunkNow(625, 625) == null,
                    "Observation loaded the missile's chunk");
        } catch (Throwable error) {
            failure = error; StabilizedTurret.LOGGER.error("WEAPON VIEW TWO-PLAYER CHECK FAILED", error);
        } finally {
            if (players != null) players.removeAll(added);
            if (shots != null) shots.remove(missileId);
            for (Controller controller : controllers) controller.setRemoved();
            AirborneWeaponViewHandler.tick(new ServerTickEvent.Post(() -> true, event.getServer()));
            try { Files.writeString(Path.of("weapon-view-server-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
            catch (Exception error) { throw new RuntimeException(error); }
            event.getServer().halt(false);
        }
    }

    private static final class Controller extends StabilizerControllerBlockEntity {
        ServerPlayer viewer;
        Controller(ServerLevel level, BlockPos pos) { super(pos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState()); setLevel(level); }
        @Override public ServerPlayer getWeaponViewOperator() { return viewer; }
    }
    private static final class Capture extends ServerGamePacketListenerImpl {
        final List<WeaponViewPayload> views = new ArrayList<>();
        final List<MathematicalMissilePayload> flights = new ArrayList<>();
        Capture(ServerLevel level, ServerPlayer player, GameProfile profile) {
            super(level.getServer(), new Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(profile, false));
        }
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                if (custom.payload() instanceof WeaponViewPayload view) views.add(view);
                if (custom.payload() instanceof MathematicalMissilePayload flight) flights.add(flight);
            }
        }
        void clear() { views.clear(); flights.clear(); }
        boolean hasFlight(UUID id) { return flights.stream().flatMap(p -> p.states().stream()).anyMatch(s -> id.equals(s.id())); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
