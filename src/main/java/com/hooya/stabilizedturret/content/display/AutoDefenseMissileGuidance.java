package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileEffects;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/** Homing ownership for air-defense missiles launched by a display computer. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AutoDefenseMissileGuidance {
    private static final int SAFETY_TICKS = 10;
    private static final int MAX_LIFETIME_TICKS = 20 * 20;
    private static final int ACCELERATION_TICKS = 10 * 20;
    private static final double MAX_SPEED = 34.03;
    private static final double PROXIMITY_RANGE = 5.0;
    private static final double NAVIGATION_CONSTANT = 5.0;
    private static final double MAX_STEER_RADIANS = Math.toRadians(13.0);
    private static Pending pending;
    private static final Map<Entity, Guided> guided = new IdentityHashMap<>();

    public static void armNext(ConnectedDisplayBlockEntity display, BindingRef launcher,
                               ConnectedDisplayBlockEntity.Contact contact) {
        pending = new Pending(display, launcher, contact,
                display.getLevel() == null ? Long.MIN_VALUE : display.getLevel().getGameTime());
    }

    public static void cancelNext(ConnectedDisplayBlockEntity display) {
        if (pending != null && pending.display == display) pending = null;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !MianbaoAirDefenseCompat.isMissile(event.getEntity())
                || pending == null || pending.display.isRemoved()) return;
        long now = pending.display.getLevel() == null ? Long.MIN_VALUE
                : pending.display.getLevel().getGameTime();
        if (now == Long.MIN_VALUE || now - pending.armedAt > 2L
                || MianbaoAirDefenseCompat.getLauncherSpawnDistanceSqr(
                pending.display.getLevel(), pending.launcher, event.getEntity()) > 144.0) return;
        guided.put(event.getEntity(), new Guided(pending.display, pending.contact,
                MianbaoMissileCompat.getWorldVelocity(event.getEntity())));
        event.getEntity().getPersistentData().putBoolean("CreateFireControlAaGuided", true);
        pending = null;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<Entity, Guided>> iterator = guided.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Entity, Guided> entry = iterator.next();
            Entity missile = entry.getKey();
            Guided state = entry.getValue();
            if (missile.isRemoved() || !missile.isAlive()) {
                iterator.remove();
                continue;
            }
            MianbaoMissileCompat.advanceIfNativeTickMissed(missile);
            if (++state.age >= MAX_LIFETIME_TICKS) {
                selfDestruct(missile);
                iterator.remove();
                continue;
            }
            ConnectedDisplayBlockEntity.Contact liveContact = state.display.getContacts().stream()
                    .filter(contact -> contact.key().equals(state.contact.key())).findFirst().orElse(null);
            if (liveContact != null) state.contact = liveContact;
            Vec3 target = resolveTarget(missile, state.contact);
            if (target == null) {
                selfDestruct(missile);
                iterator.remove();
                continue;
            }
            Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
            Vec3 velocity = MianbaoMissileCompat.getWorldVelocity(missile);
            if (state.age >= SAFETY_TICKS && distanceToTargetSqr(position, state.contact) <= PROXIMITY_RANGE * PROXIMITY_RANGE) {
                selfDestruct(missile);
                iterator.remove();
                continue;
            }
            double speed = state.speedAtAge();
            missile.setNoGravity(true);
            if (state.age <= SAFETY_TICKS) {
                MianbaoMissileCompat.setWorldVelocity(missile, state.initialDirection.scale(speed));
                continue;
            }
            Vec3 current = velocity.lengthSqr() < 1.0e-8
                    ? state.initialDirection : velocity.normalize();
            Vec3 relativePosition = target.subtract(position);
            double distanceSqr = relativePosition.lengthSqr();
            if (distanceSqr < 1.0e-8) continue;
            Vec3 relativeVelocity = state.contact.velocity().subtract(velocity);
            Vec3 losRate = relativePosition.cross(relativeVelocity).scale(1.0 / distanceSqr);
            double closingSpeed = Math.max(0.0,
                    -relativeVelocity.dot(relativePosition.normalize()));
            Vec3 normalAcceleration = losRate.cross(current)
                    .scale(NAVIGATION_CONSTANT * Math.max(1.0, closingSpeed));
            Vec3 desiredVelocity = current.scale(speed).add(normalAcceleration);
            Vec3 desired = desiredVelocity.lengthSqr() < 1.0e-8
                    ? relativePosition.normalize() : desiredVelocity.normalize();
            MianbaoMissileCompat.setWorldVelocity(missile,
                    rotateTowards(current, desired, MAX_STEER_RADIANS).scale(speed));
        }
    }

    private static Vec3 resolveTarget(Entity missile, ConnectedDisplayBlockEntity.Contact contact) {
        if (contact.key().living()) {
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
            Entity entity = root == null ? null : root.getEntity(contact.key().id());
            if (entity == null && root != null) {
                for (SubLevel sub : SubLevelContainer.getContainer(root).getAllSubLevels()) {
                    if (sub != null && !sub.isRemoved() && sub.getLevel() instanceof ServerLevel server) {
                        entity = server.getEntity(contact.key().id());
                        if (entity != null) break;
                    }
                }
            }
            return entity instanceof LivingEntity living && living.isAlive()
                    ? MianbaoMissileCompat.getWorldPosition(living) : null;
        }
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        if (root == null) return null;
        AABB bounds = null;
        for (java.util.UUID id : contact.members()) {
            SubLevel sub = SubLevelContainer.getContainer(root).getSubLevel(id);
            if (sub == null || sub.isRemoved()) continue;
            AABB part = com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity
                    .subLevelWorldBounds(sub);
            bounds = bounds == null ? part : new AABB(
                    Math.min(bounds.minX, part.minX), Math.min(bounds.minY, part.minY), Math.min(bounds.minZ, part.minZ),
                    Math.max(bounds.maxX, part.maxX), Math.max(bounds.maxY, part.maxY), Math.max(bounds.maxZ, part.maxZ));
        }
        if (bounds != null) return bounds.getCenter();
        SubLevel sub = SubLevelContainer.getContainer(root).getSubLevel(contact.key().id());
        return sub == null || sub.isRemoved() ? null : com.hooya.stabilizedturret.content.controller
                .StabilizerControllerBlockEntity.subLevelWorldBounds(sub).getCenter();
    }

    private static double distanceToTargetSqr(Vec3 position, ConnectedDisplayBlockEntity.Contact contact) {
        AABB bounds = contact.bounds();
        double x = Math.max(bounds.minX, Math.min(bounds.maxX, position.x));
        double y = Math.max(bounds.minY, Math.min(bounds.maxY, position.y));
        double z = Math.max(bounds.minZ, Math.min(bounds.maxZ, position.z));
        return position.distanceToSqr(x, y, z);
    }

    private static Vec3 rotateTowards(Vec3 current, Vec3 desired, double maxRadians) {
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, current.dot(desired))));
        if (angle <= maxRadians || angle < 1.0e-6) return desired;
        double blend = maxRadians / angle;
        Vec3 result = current.scale(1.0 - blend).add(desired.scale(blend));
        return result.lengthSqr() < 1.0e-8 ? current : result.normalize();
    }

    private static void selfDestruct(Entity missile) {
        MianbaoMissileEffects.broadcastExplosion(missile, 3.0F, false);
        ServerLevel root = MianbaoMissileCompat.getRootServerLevel(missile);
        Vec3 position = MianbaoMissileCompat.getWorldPosition(missile);
        if (root != null && MianbaoMissileCompat.areRootChunksLoaded(missile, position, 3.0)) {
            root.explode(null, position.x, position.y, position.z, 3.0F,
                    Level.ExplosionInteraction.BLOCK);
        }
        missile.discard();
    }

    private record Pending(ConnectedDisplayBlockEntity display, BindingRef launcher,
                           ConnectedDisplayBlockEntity.Contact contact, long armedAt) {}
    private static final class Guided {
        final ConnectedDisplayBlockEntity display;
        ConnectedDisplayBlockEntity.Contact contact;
        final Vec3 initialDirection;
        final double initialSpeed;
        final double acceleration;
        int age;
        Guided(ConnectedDisplayBlockEntity display, ConnectedDisplayBlockEntity.Contact contact,
               Vec3 initialVelocity) {
            this.display=display; this.contact=contact;
            this.initialSpeed=Math.min(MAX_SPEED,Math.max(0.1,initialVelocity.length()));
            this.initialDirection=initialVelocity.lengthSqr()<1.0e-8
                    ? new Vec3(0.0,0.0,1.0):initialVelocity.normalize();
            this.acceleration=Math.max(0.0,(MAX_SPEED-initialSpeed)/ACCELERATION_TICKS);
        }
        double speedAtAge(){return Math.min(MAX_SPEED,initialSpeed+acceleration*age);}
    }
    @SubscribeEvent public static void onStop(ServerStoppingEvent event){guided.clear();pending=null;}
    private AutoDefenseMissileGuidance() {}
}
