package com.hooya.stabilizedturret.content.missile.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.VirtualMissileStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client prediction and bounded visual history for virtual missiles and flares. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ClientMissileManager {
    private static final Map<UUID, Missile> MISSILES = new HashMap<>();
    private static final Map<UUID, Flare> FLARES = new HashMap<>();
    private static ResourceLocation dimension;
    private static ClientLevel level;

    public static void accept(VirtualMissileStatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || !minecraft.level.dimension().location().equals(payload.dimension())) return;
        if (!payload.dimension().equals(dimension)) clear();
        level = minecraft.level;
        dimension = payload.dimension();
        long now = level.getGameTime();
        CompoundTag root = payload.data();

        ListTag removed = root.getList("Removed", Tag.TAG_COMPOUND);
        for (Tag value : removed) MISSILES.remove(((CompoundTag) value).getUUID("Id"));

        ListTag missiles = root.getList("Missiles", Tag.TAG_COMPOUND);
        for (Tag value : missiles) {
            CompoundTag tag = (CompoundTag) value;
            UUID id = tag.getUUID("Id");
            Missile missile = MISSILES.get(id);
            if (missile == null) {
                missile = Missile.read(tag, now);
                MISSILES.put(id, missile);
                StabilizedTurret.LOGGER.info(
                        "Received virtual ballistic missile {} at {}", id, missile.position());
                minecraft.getSoundManager().play(new MissileSoundInstance(id, missile.position()));
            } else {
                missile.accept(tag, now);
            }
        }

        ListTag flares = root.getList("Flares", Tag.TAG_COMPOUND);
        for (Tag value : flares) {
            CompoundTag tag = (CompoundTag) value;
            FLARES.putIfAbsent(tag.getUUID("Id"), Flare.read(tag));
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            clear();
            return;
        }
        if (level != minecraft.level) {
            clear();
            level = minecraft.level;
            dimension = level.dimension().location();
        }
        long now = level.getGameTime();
        MISSILES.values().forEach(missile -> missile.tick(now));
        FLARES.values().removeIf(flare -> now - flare.birth() > flare.life());
    }

    public static Collection<Missile> missiles() { return List.copyOf(MISSILES.values()); }
    public static Collection<Flare> flares() { return List.copyOf(FLARES.values()); }
    public static Missile get(UUID id) { return MISSILES.get(id); }

    public static void clear() {
        MISSILES.clear();
        FLARES.clear();
    }

    private static double number(CompoundTag tag, String key) { return tag.getDouble(key); }
    private static Vec3 vector(CompoundTag tag, String prefix) {
        return new Vec3(number(tag, prefix + "X"), number(tag, prefix + "Y"),
                number(tag, prefix + "Z"));
    }

    public static final class Missile {
        private static final int CORRECTION_TICKS = 5;
        private final UUID id;
        private Vec3 authoritativePosition;
        private Vec3 renderedPosition;
        private Vec3 velocity;
        private Vec3 direction;
        private String phase;
        private long serverTime;
        private long lastClientTime;
        private int age;
        private int correctionTicks;
        private final ArrayDeque<Vec3> trail = new ArrayDeque<>();

        private Missile(UUID id, Vec3 position, Vec3 velocity, Vec3 direction,
                        String phase, long serverTime, int age, long now) {
            this.id = id;
            this.authoritativePosition = position;
            this.velocity = velocity;
            this.direction = direction;
            this.phase = phase;
            this.serverTime = serverTime;
            this.age = age;
            this.lastClientTime = now;
            this.renderedPosition = predict(now);
            this.trail.addLast(renderedPosition);
        }

        private static Missile read(CompoundTag tag, long now) {
            return new Missile(tag.getUUID("Id"), vector(tag, "P"), vector(tag, "V"),
                    vector(tag, "D"), tag.getString("Phase"), tag.getLong("Time"),
                    tag.getInt("Age"), now);
        }

        private void accept(CompoundTag tag, long now) {
            authoritativePosition = vector(tag, "P");
            velocity = vector(tag, "V");
            direction = vector(tag, "D");
            phase = tag.getString("Phase");
            serverTime = tag.getLong("Time");
            age = tag.getInt("Age");
            correctionTicks = CORRECTION_TICKS;
            if (lastClientTime == Long.MIN_VALUE) lastClientTime = now;
        }

        private void tick(long now) {
            Vec3 predicted = predict(now);
            long elapsed = Math.max(0L, now - lastClientTime);
            renderedPosition = renderedPosition.add(velocity.scale(elapsed));
            if (correctionTicks > 0) {
                renderedPosition = renderedPosition.add(
                        predicted.subtract(renderedPosition).scale(1.0 / correctionTicks--));
            } else {
                renderedPosition = predicted;
            }
            lastClientTime = now;
            if (trail.isEmpty() || trail.peekLast().distanceToSqr(renderedPosition) >= 0.04) {
                trail.addLast(renderedPosition);
                int limit = ClientMissileConfig.TRAIL_SAMPLES.get();
                while (trail.size() > limit) trail.removeFirst();
            }
        }

        private Vec3 predict(long now) {
            // Velocity is already blocks/tick; serverTime is measured in ticks.
            return authoritativePosition.add(velocity.scale(Math.max(0L, now - serverTime)));
        }

        public UUID id() { return id; }
        public Vec3 position() { return renderedPosition; }
        public Vec3 predicted(long now) { return renderedPosition; }
        public Vec3 v() { return velocity; }
        public Vec3 direction() { return direction; }
        public String phase() { return phase; }
        public int age() { return age; }
        public List<Vec3> trail() { return new ArrayList<>(trail); }
    }

    public record Flare(UUID id, Vec3 p, Vec3 v, long birth, int life, float heat,
                        long seed, double drag, double gravity) {
        private static Flare read(CompoundTag tag) {
            return new Flare(tag.getUUID("Id"), vector(tag, "P"), vector(tag, "V"),
                    tag.getLong("Time"), tag.getInt("Lifetime"), tag.getFloat("Heat"),
                    tag.getLong("Seed"), tag.contains("Drag") ? number(tag, "Drag") : 0.98,
                    tag.contains("Gravity") ? number(tag, "Gravity") : 0.04);
        }

        public Vec3 position(long now) {
            double ticks = Math.max(0L, now - birth);
            if (ticks == 0.0) return p;
            if (Math.abs(1.0 - drag) < 1.0e-8) {
                return p.add(v.scale(ticks)).add(0.0,
                        -gravity * ticks * (ticks + 1.0) * 0.5, 0.0);
            }
            double dragPower = Math.pow(drag, ticks);
            double velocitySum = drag * (1.0 - dragPower) / (1.0 - drag);
            double gravitySum = (ticks - velocitySum) / (1.0 - drag);
            return p.add(v.scale(velocitySum)).add(0.0, -gravity * gravitySum, 0.0);
        }
    }

    private ClientMissileManager() {}
}
