package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** Real native entity ticks thousands of blocks from spawn, with no connected player. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MissileTicketIntegration {
    private static final TicketType<UUID> TYPE = TicketType.create("missile_ticket_check", UUID::compareTo);
    private static MissileChunkTickets tickets = new MissileChunkTickets(TYPE);
    private static Entity missile;
    private static ServerLevel level;
    private static int age;
    private static int ticksAtUpgrade;
    private static double startX;
    private static final List<Entity> flares = new ArrayList<>();
    private static boolean finished;
    private static int phase;
    private static int steps;

    @SubscribeEvent
    public static void collectFlare(EntityJoinLevelEvent event) {
        if (event.getLevel() == level && phase == 2
                && MianbaoCountermeasureCompat.isCountermeasure(event.getEntity())) {
            flares.add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        try {
            level = event.getServer().overworld();
            require(level.players().isEmpty(), "test requires no nearby players");
            level.getChunk(256, 256); // Initial fixture only; flight loading uses production tickets.
            LowAltitudeCruiseIntegration.check(level);
            missile = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(
                    "mianbaos_modernwarfare:ballistic_missile_entity")).create(level);
            require(missile != null, "Mianbao ballistic entity missing");
            CompoundTag data = missile.saveWithoutId(new CompoundTag());
            data.putBoolean("Docked", false);
            data.putInt("LaunchAge", 160);
            data.putFloat("RotationW", 1.0F);
            data.putDouble("LaunchDistance", 6.0);
            missile.load(data);
            missile.setPos(4096.5, 1000, 4096.5);
            startX = missile.getX();
            require(MianbaoBallisticMissileCompat.setUnrestrictedFlightControl(
                    missile, new Vec3(1, 0, 0), 4), "native control failed");
            tickets.update(missile, missile.getDeltaMovement(), 384);
            require(level.addFreshEntity(missile), "native entity spawn failed");
            StabilizedTurret.LOGGER.info("TICKET CHECK: baseline FULL-only ticket at {}", missile.position());
        } catch (Throwable failure) { finish(failure); }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (missile == null || finished) return;
        try {
            // An empty dedicated level suspends all entities after 300 ticks.
            // Keep the test world awake like a world with a distant player;
            // this does not load chunks or bypass entity-ticking ticket checks.
            level.resetEmptyTime();
            age++;
            require(age < 1800, "integration test timed out");
            if (phase == 0) {
                if (age < 60) return;
                require(level.getChunkSource().getChunkNow(256, 256) != null, "baseline terrain absent");
                require(!level.isPositionEntityTicking(missile.blockPosition()), "baseline unexpectedly ticks");
                require(missile.getX() == startX && missile.tickCount == 0,
                        "baseline must reproduce stationary native entity despite loaded terrain");
                StabilizedTurret.LOGGER.info("TICKET CHECK: reproduced loaded terrain with nativeTickCount=0");
                tickets.release();
                tickets = new MissileChunkTickets(TYPE, true);
                ticksAtUpgrade = age;
                phase = 1;
            }
            if (phase == 1) {
                require(!missile.isRemoved(), "native missile removed during flight");
                double speed = missile.tickCount < 80 ? 4.0 : missile.tickCount < 120 ? 34.03 : 51.045;
                Vec3 command = new Vec3(speed, 0, 0);
                tickets.update(missile, command, 384);
                MianbaoBallisticMissileCompat.setUnrestrictedFlightControl(missile,
                        new Vec3(1, 0, 0), tickets.movementPathLoaded(missile, command) ? speed : 0);
                require(missile.tickCount <= age - ticksAtUpgrade, "entity ticked twice in one server tick");
                if (missile.tickCount < 160) return;
                require(missile.getX() - startX > 512, "missile failed to cross multiple remote chunk borders");
                StabilizedTurret.LOGGER.info("TICKET CHECK: PASS native flight ticks={} distance={} finalSpeed={}",
                        missile.tickCount, missile.getX() - startX, speed);
                tickets.release();
                missile.discard();
                phase = 2;
                steps = 0;
                level.getChunk(256, 256);
                Vec3 flarePosition = new Vec3(4096.5, 300, 4096.5);
                require(MianbaoCountermeasureCompat.spawnBurst(level, flarePosition, 5) == 5,
                        "five native flares must spawn");
                require(flares.size() == 5, "flare fixture count mismatch");
            } else if (phase == 2) {
                steps++;
                if (steps == 60 || steps == 300) {
                    for (Entity flare : flares) {
                        StabilizedTurret.LOGGER.info(
                                "TICKET CHECK: flare step={} ticks={} nativeAge={} removed={} position={} entityTicking={}",
                                steps, flare.tickCount, flare.getPersistentData().getDouble("jilu1"),
                                flare.isRemoved(), flare.position(), level.isPositionEntityTicking(flare.blockPosition()));
                    }
                }
                if (steps == 60) require(flares.stream().allMatch(f -> f.tickCount > 0), "remote flares did not tick");
                if (steps < 300) return;
                require(flares.stream().allMatch(Entity::isRemoved), "flare lifetime did not release entities");
                require(!level.isPositionEntityTicking(missile.blockPosition()), "missile ticket leaked after release");
                require(flares.stream().noneMatch(f -> level.isPositionEntityTicking(f.blockPosition())),
                        "shared flare ticket leaked after removal");
                StabilizedTurret.LOGGER.info("TICKET CHECK: PASS five remote flares expired and tickets released");
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void finish(Throwable failure) {
        finished = true;
        if (failure != null) StabilizedTurret.LOGGER.error("TICKET CHECK FAILED", failure);
        try { Files.writeString(Path.of("ticket-integration-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally {
            tickets.release();
            if (missile != null) missile.discard();
            for (Entity flare : flares) flare.discard();
            if (level != null) level.getServer().halt(false);
        }
    }
}
