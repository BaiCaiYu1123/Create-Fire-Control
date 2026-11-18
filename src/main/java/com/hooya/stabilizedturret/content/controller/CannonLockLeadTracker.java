package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.CannonLockLeadPayload;
import com.hooya.stabilizedturret.network.CannonLockLeadPayload.LeadStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Live cannon lead and ordinary-AA camera tracks, refreshed every server tick. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class CannonLockLeadTracker {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final Map<StabilizerControllerBlockEntity, Cached> CACHE = new WeakHashMap<>();
    private static final Map<StabilizerControllerBlockEntity, Diagnostic> DIAGNOSTICS = new WeakHashMap<>();

    private CannonLockLeadTracker() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (ACTIVE.isEmpty() && !ControllerTracker.hasLoadedControllers()) return;
        var seated = ControllerTracker.findForVehicle(player.level(), player.getVehicle());
        var input = RemoteControlTracker.findInputController(player);
        var view = ControllerTracker.playerViewController(player, seated, input);
        var payload = snapshotForView(view, seated);
        if (payload.optical() != null || payload.radar() != null) {
            ACTIVE.add(player.getUUID());
            PacketDistributor.sendToPlayer(player, payload);
        } else if (ACTIVE.remove(player.getUUID())) PacketDistributor.sendToPlayer(player, payload);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        ACTIVE.remove(event.getEntity().getUUID());
    }

    static CannonLockLeadPayload snapshotForView(@Nullable StabilizerControllerBlockEntity view,
            @Nullable StabilizerControllerBlockEntity radarController) {
        var visible = snapshot(view);
        if (view == radarController) return visible;
        // RadarSessionPayload still belongs to the seated controller; remote optics
        // must not replace its target with a lock from a different computer.
        return new CannonLockLeadPayload(visible.optical(), snapshot(radarController).radar());
    }

    public static CannonLockLeadPayload snapshot(@Nullable StabilizerControllerBlockEntity controller) {
        if (controller == null || controller.getLevel() == null) return CannonLockLeadPayload.INACTIVE;
        var level = controller.getLevel();
        long tick = level.getGameTime();
        var cached = CACHE.get(controller);
        if (cached != null && cached.tick == tick) return cached.payload;
        if (!controller.hasOpticalLock() && (controller.isAircraftRadarMode()
                || controller.getRadarLockedSubLevelId() == null)) return CannonLockLeadPayload.INACTIVE;
        var selected = controller.getSelectedGroundWeapon();
        var ref = controller.getPrimaryLockLeadCannon();
        if (ref == null && !controller.hasOpticalLock() && selected != FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE)
            return CannonLockLeadPayload.INACTIVE;
        // Every assembled cannon supplies lead, independently of missile selection.
        var shot = ref != null
                ? CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level, ref), level, ref)
                : null;
        CannonLockLeadPayload.Track optical = null, radar = null;
        Vec3 target = controller.getOpticalLockTarget();
        if (controller.hasOpticalLock() && target != null) {
            Vec3 velocity = controller.getOpticalLockTelemetry().velocity();
            optical = (ref == null ? new CannonLockLeadPayload.Track(controller.getOpticalLockTargetId(), target, velocity, null)
                    : track(controller, ref, shot, controller.getOpticalLockTargetId(), target, velocity, true))
                    .withAcquisition(controller.getOpticalCameraAcquisitionId());
        }
        if (controller.isRadarActive() && !controller.isAircraftRadarMode()) {
            UUID id = controller.getRadarLockedSubLevelId();
            var contact = id == null ? null : controller.getRadarContact(id);
            if (contact != null) {
                Vec3 velocity = contact.missile() ? contact.velocity()
                        : controller.getLockTargetVelocity(id, contact.center());
                long age = Math.clamp(tick - controller.getRadarContactSampleTick(), 0, 2);
                Vec3 radarTarget = contact.center().add(velocity.scale(age));
                radar = radarTrack(selected, ref != null, id, radarTarget, velocity,
                        () -> track(controller, ref, shot, id, radarTarget, velocity, false).aimPoint());
                if (radar != null && ref != null && shot == null)
                    radar = radar.withLeadStatus(LeadStatus.NO_AMMUNITION);
                if (radar != null) radar = radar.withAcquisition(controller.getRadarCameraAcquisitionId());
            }
        }
        var payload = new CannonLockLeadPayload(optical, radar);
        diagnose(controller, ref, shot, payload, tick);
        CACHE.put(controller, new Cached(tick, payload));
        return payload;
    }

    static @Nullable CannonLockLeadPayload.Track radarTrack(
            @Nullable FireControlWeaponInputs.GroundWeapon selected, boolean assembledCannon,
            UUID id, Vec3 target, Vec3 velocity, Supplier<@Nullable Vec3> cannonLead
    ) {
        boolean camera = selected == FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE
                || selected == null && assembledCannon;
        if (!assembledCannon && !camera) return null;
        Vec3 point = assembledCannon ? cannonLead.get() : null;
        LeadStatus status = !assembledCannon ? LeadStatus.NONE
                : point == null ? LeadStatus.UNREACHABLE : LeadStatus.READY;
        return new CannonLockLeadPayload.Track(id, target, velocity, point, id, status, camera);
    }

    private static CannonLockLeadPayload.Track track(StabilizerControllerBlockEntity controller, BindingRef ref,
            @Nullable CannonBallisticsCompat.ShotProfile shot, @Nullable UUID id, Vec3 target, Vec3 velocity,
            boolean optical) {
        var solution = CannonLeadSolver.solve(shot, target, velocity);
        Vec3 point = solution == null ? null : controller.getCannonLockLeadSightPoint(
                ref, shot, solution, optical ? target : null);
        LeadStatus status = shot == null ? LeadStatus.NO_AMMUNITION
                : point == null ? LeadStatus.UNREACHABLE : LeadStatus.READY;
        return new CannonLockLeadPayload.Track(id, target, velocity, point, id, status, true);
    }

    private record Cached(long tick, CannonLockLeadPayload payload) {}

    private static void diagnose(StabilizerControllerBlockEntity controller, @Nullable BindingRef ref,
            @Nullable CannonBallisticsCompat.ShotProfile shot, CannonLockLeadPayload payload, long tick) {
        String state = "optical=" + trackStatus(payload.optical()) + ",radar=" + trackStatus(payload.radar())
                + ",cannon=" + (ref != null) + ",profile=" + (shot != null)
                + ",selected=" + controller.getSelectedGroundWeapon();
        var before = DIAGNOSTICS.get(controller);
        if (before != null && tick >= before.tick && tick - before.tick < 200
                && state.equals(before.state)) return;
        // Rate-limit alternating states as well as steady failures.
        if (before != null && tick >= before.tick && tick - before.tick < 20) return;
        DIAGNOSTICS.put(controller, new Diagnostic(tick, state));
        var target = payload.optical() != null ? payload.optical() : payload.radar();
        StabilizedTurret.LOGGER.info("Cannon lock lead: controller={} {} projectile={} speed={} gravity={} drag={} lifetime={} range={} targetVelocity={} ownVelocity={} leadOffset={} cannonDetails={}",
                controller.getBlockPos(), state, shot == null ? "unavailable" : shot.projectile(),
                shot == null ? 0 : shot.muzzleSpeed(), shot == null ? 0 : shot.gravity(),
                shot == null ? 0 : shot.drag(), shot == null ? 0 : shot.maxFlightTicks(),
                shot == null || target == null ? 0 : target.target().distanceTo(shot.muzzle()),
                target == null ? Vec3.ZERO : target.velocity(), shot == null ? Vec3.ZERO : shot.carrierVelocity(),
                target == null || target.aimPoint() == null ? null : target.aimPoint().subtract(target.target()),
                shot != null ? shot.cannonType() : ref == null ? "unbound"
                        : CannonBallisticsCompat.describeLoadedCannon(controller.getLevel(), ref));
    }

    private static String trackStatus(@Nullable CannonLockLeadPayload.Track track) {
        return track == null ? "inactive" : track.leadStatus() + "/camera=" + track.cameraTracking();
    }

    private record Diagnostic(long tick, String state) {}
}
