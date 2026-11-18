package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.MathematicalMissileManager;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.network.MathematicalMissilePayload;
import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Genuine display launches and real server phase delivery; never calls the intercept callback. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MovingInterceptorIntegration {
    private record Fixture(String name, Entity interceptor, Entity threat, Vec3 center,
                           String control, boolean late, boolean interceptorMath, boolean threatMath) {}
    private static final List<Fixture> fixtures = new ArrayList<>();
    private static final List<String> report = new ArrayList<>(List.of("scenario,intercepted,interceptor_removed,threat_removed"));
    private static ServerLevel level;
    private static Capture capture;
    private static List<ServerPlayer> global, local;
    private static Entity launched;
    private static boolean capturing, done, initialized;
    private static int ticks, warmup;

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void spawned(EntityJoinLevelEvent event) {
        if (capturing && MianbaoAirDefenseCompat.isMissile(event.getEntity())) launched = event.getEntity();
    }

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        try {
            var globalField = PlayerList.class.getDeclaredField("players"); globalField.setAccessible(true);
            global = (List<ServerPlayer>) globalField.get(event.getServer().getPlayerList());
            var localField = ServerLevel.class.getDeclaredField("players"); localField.setAccessible(true);
            local = (List<ServerPlayer>) localField.get(level);
            var profile = new GameProfile(UUID.randomUUID(), "FuzeCheck");
            var player = new ServerPlayer(event.getServer(), level, profile, ClientInformation.createDefault());
            player.setPos(512, 220, 512); player.setInvulnerable(true);
            capture = new Capture(level, player, profile); global.add(player); local.add(player);
            // Entity targets must be in visible sections before addFreshEntity;
            // otherwise ServerLevel.getEntity intentionally cannot find them yet.
            for (int index = -2; index < 34; index++) {
                level.setChunkForced(512 >> 4, (512 + index * 16) >> 4, true);
                level.setChunkForced(576 >> 4, (512 + index * 16) >> 4, true);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private static void launchFixtures() throws Exception {
            for (String target : List.of("aim_120_tanshe", "cruisemissiletanshe")) {
                for (boolean interceptorMath : new boolean[]{false, true}) for (boolean threatMath : new boolean[]{false, true}) {
                    for (boolean late : new boolean[]{false, true}) launch(target, "hit", late, interceptorMath, threatMath);
                }
            }
            for (String control : List.of("near-miss", "different-times", "minimum-range", "safety")) {
                for (boolean interceptorMath : new boolean[]{false, true}) for (boolean threatMath : new boolean[]{false, true}) {
                    launch("aim_120_tanshe", control, true, interceptorMath, threatMath);
                }
            }
            command("math");
    }

    private static void launch(String targetType, String control, boolean late, boolean interceptorMath, boolean threatMath) throws Exception {
        int index = fixtures.size();
        BlockPos launcherPos = new BlockPos(512, 220, 512 + index * 16);
        Vec3 center = Vec3.atCenterOf(launcherPos).add(64, 0, 0);
        command(threatMath ? "math" : "entity");
        Entity threat = BuiltInRegistries.ENTITY_TYPE.get(id(targetType)).create(level);
        require(threat != null, "Missing installed target type");
        threat.setPos(center.add(0, 0, -17.015)); protect(threat);
        threat.getPersistentData().putBoolean("CreateFireControlAircraftGuided", targetType.equals("aim_120_tanshe"));
        threat.getPersistentData().putBoolean("CreateFireControlCruiseGuided", targetType.contains("cruise"));
        // Test-owned movement below exercises early and late mathematical update order.
        require(level.addFreshEntity(threat) != threatMath, "Wrong threat backend");
        require(MathematicalMissileManager.contains(threat) == threatMath, "Threat not in expected registry");
        require(threatMath || level.getEntity(threat.getUUID()) == threat, "Entity threat chunk not visible after warmup");
        command(interceptorMath ? "math" : "entity");
        var block = BuiltInRegistries.BLOCK.get(id("antiairmissilelauncherblock")).defaultBlockState();
        block = block.hasProperty(BlockStateProperties.FACING) ? block.setValue(BlockStateProperties.FACING, Direction.EAST)
                : block.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.setBlock(launcherPos, block, 3);
        var inventory = (Container) level.getBlockEntity(launcherPos);
        inventory.setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(id("portableantiairmissile_2")), 2));
        BlockPos displayPos = launcherPos.west(4);
        level.setBlock(displayPos, ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState(), 3);
        var display = (ConnectedDisplayBlockEntity) level.getBlockEntity(displayPos);
        display.setSettings(false, 12000, 30, 30, 90, 90, false, false, 20, 1, 128);
        display.setMissileProximityRange(control.equals("minimum-range") ? 1 : 5);
        display.setAutomaticMissileDefense(true);
        var contact = new ConnectedDisplayBlockEntity.Contact(
                new ConnectedDisplayBlockEntity.TargetKey(threat.getUUID(), true), Set.of(),
                threat.position(), new Vec3(0, 0, 34.03), new AABB(threat.position(), threat.position()).inflate(1),
                64, 0, true);
        require(display.bind(BindingSlot.AA_MISSILE_LAUNCHER, BindingRef.capture(level, launcherPos)), "Cannot bind launcher");
        require(AutoDefenseMissileGuidance.canLaunch(display, contact), "Threat unavailable for automatic launch");
        require(MianbaoAirDefenseCompat.getRadarAmmoCount(level, BindingRef.capture(level, launcherPos)) == 2,
                "Automatic launcher does not see two radar rounds");
        // The same production engage entry used by the automatic threat selector.
        var engage = ConnectedDisplayBlockEntity.class.getDeclaredMethod("engage",
                ConnectedDisplayBlockEntity.Contact.class, long.class, boolean.class, boolean.class);
        engage.setAccessible(true);
        launched = null; capturing = true;
        try { engage.invoke(display, contact, level.getGameTime(), false, true); }
        finally { capturing = false; }
        require(launched != null && inventory.getItem(0).getCount() == 1,
                "Automatic display engage did not launch/consume one missile: launched=" + launched
                        + ", remaining=" + inventory.getItem(0).getCount());
        require(launched.getPersistentData().getBoolean("CreateFireControlDisplayGuided"), "Missile did not acquire display guidance");
        require(MathematicalMissileManager.contains(launched) == interceptorMath, "Wrong interceptor backend");
        protect(launched);
        fixtures.add(new Fixture(targetType + "/" + (interceptorMath ? "M" : "E") + (threatMath ? "M" : "E")
                + "/" + control + "/" + (late ? "late" : "early"), launched, threat, center,
                control, late, interceptorMath, threatMath));
    }

    private static void protect(Entity missile) {
        missile.noPhysics = true; missile.setNoGravity(true); missile.setInvulnerable(true);
        if (missile instanceof AbstractArrow arrow) arrow.setNoPhysics(true);
        missile.getPersistentData().putBoolean("jamed", true);
        missile.getPersistentData().putDouble("fadongji1", 100);
        missile.getPersistentData().putDouble("fadongji", 100);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void prepare(ServerTickEvent.Pre event) {
        if (level == null || done) return;
        if (!initialized) {
            if (++warmup < 60) return;
            try { launchFixtures(); initialized = true; }
            catch (Throwable failure) { finish(failure); }
            return;
        }
        ticks++;
        for (Fixture f : fixtures) {
            if (f.interceptor.isRemoved() || f.threat.isRemoved()) continue;
            boolean crossing = ticks == (f.control.equals("safety") ? 5 : 12);
            double offset = f.control.equals("near-miss") ? 5.01 : f.control.equals("minimum-range") ? 1.01 : 0;
            double speed = f.name.startsWith("cruise") ? 17.015 : 34.03;
            double half = speed / 2;
            Vec3 interceptorStart = f.center.add(-half, 0, 0);
            Vec3 threatStart = f.center.add(0, offset, f.control.equals("different-times") ? 0 : -half);
            f.interceptor.setPos(interceptorStart); f.interceptor.setOldPosAndRot();
            f.threat.setPos(threatStart); f.threat.setOldPosAndRot();
            f.interceptor.setDeltaMovement(crossing ? new Vec3(speed, 0, 0) : Vec3.ZERO);
            f.threat.setDeltaMovement(crossing ? new Vec3(0, 0, speed) : Vec3.ZERO);
            f.threat.getPersistentData().putDouble("fadongji1", 100);
            f.threat.getPersistentData().putDouble("fadongji", 100);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void earlyThreats(ServerTickEvent.Post event) { moveThreats(false); }
    @SubscribeEvent
    public static void lateThreats(ServerTickEvent.Post event) { moveThreats(true); }
    private static void moveThreats(boolean late) {
        if (level == null || done) return;
        for (Fixture fixture : fixtures) if (fixture.late == late && !fixture.threat.isRemoved()) {
            MianbaoMissileCompat.advanceIfNativeTickMissed(fixture.threat);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void verify(ServerTickEvent.Post event) {
        if (level == null || done || ticks < 14) return;
        try {
            int hits = 0, controls = 0;
            for (Fixture f : fixtures) {
                boolean hit = f.threat.getPersistentData().getBoolean("CreateFireControlIntercepted");
                report.add(f.name + "," + hit + "," + f.interceptor.isRemoved() + "," + f.threat.isRemoved());
                if (f.control.equals("hit")) {
                    require(hit && f.threat.isRemoved() && f.interceptor.isRemoved(), "Fuze missed simultaneous crossing: " + f.name);
                    require(capture.effects.stream().filter(p -> new Vec3(p.x(), p.y(), p.z()).distanceToSqr(f.center) < 10 * 10
                                    && p.airDefenseExplosion()).count() == 1, "Interception did not emit exactly one enlarged AA explosion: " + f.name);
                    if (f.interceptorMath) require(capture.removed.contains(f.interceptor.getUUID()), "Missing mathematical interceptor removal");
                    if (f.threatMath) require(capture.removed.contains(f.threat.getUUID()), "Missing mathematical threat removal");
                    hits++;
                } else {
                    require(!hit && !f.threat.isRemoved() && !f.interceptor.isRemoved(), "False proximity detonation: " + f.name);
                    controls++;
                }
            }
            require(capture.effects.size() == hits, "Control scenarios produced unexpected explosions");
            StabilizedTurret.LOGGER.info("MOVING INTERCEPTOR PASS: {} genuine automatic display launches; {} moving crossings; {} controls; entity/math/mixed; early/late threat updates; effects and authoritative removals", fixtures.size(), hits, controls);
            finish(null);
        } catch (Throwable failure) { finish(failure); }
    }

    private static void finish(Throwable failure) {
        done = true;
        if (failure != null) StabilizedTurret.LOGGER.error("MOVING INTERCEPTOR FAILED", failure);
        for (Fixture f : fixtures) { f.interceptor.discard(); f.threat.discard(); }
        if (capture != null) { if (global != null) global.remove(capture.player); if (local != null) local.remove(capture.player); }
        try {
            Files.write(Path.of("moving-interceptor-scenarios.csv"), report);
            Files.writeString(Path.of("moving-interceptor-result.txt"), failure == null ? "PASS: 32 real launches, 16 crossings, 16 controls, effects and removal packets" : "FAIL: " + failure);
        } catch (Exception error) { StabilizedTurret.LOGGER.error("Cannot write moving interceptor result", error); }
        level.getServer().halt(false);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.parse("mianbaos_modernwarfare:" + path); }
    private static void command(String mode) { level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(), "cfc missile mode " + mode); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Capture extends ServerGamePacketListenerImpl {
        final List<MissileExplosionPayload> effects = new ArrayList<>();
        final Set<UUID> removed = new HashSet<>();
        Capture(ServerLevel level, ServerPlayer player, GameProfile profile) {
            super(level.getServer(), connected(), player, CommonListenerCookie.createInitial(profile, false));
        }
        private static Connection connected() {
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            return connection;
        }
        @Override public boolean hasChannel(ResourceLocation channel) { return false; }
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                if (custom.payload() instanceof MissileExplosionPayload explosion) effects.add(explosion);
                if (custom.payload() instanceof MathematicalMissilePayload flight) removed.addAll(flight.removed());
            }
        }
    }
}
