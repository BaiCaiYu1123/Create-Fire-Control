package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.CannonLockLeadPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import java.util.UUID;

/** Interpolate the lead offset independently of the existing lock frame's target interpolation. */
public final class ClientCannonLockLead {
    private static CannonLockLeadPayload current = CannonLockLeadPayload.INACTIVE;
    private static CannonLockLeadPayload previous = CannonLockLeadPayload.INACTIVE;
    private static Level level;
    private static long receivedTick;
    private static final LockTargetSmoother OPTICAL_MOTION = new LockTargetSmoother();
    private static final LockTargetSmoother RADAR_MOTION = new LockTargetSmoother();
    private static Vec3 opticalFrameTarget, radarFrameTarget;
    private static long lastMotionNanos;
    private static double motionSeconds;
    private static boolean motionWasPaused;

    private ClientCannonLockLead() {}

    public static void clear() {
        current = previous = CannonLockLeadPayload.INACTIVE;
        level = null;
        receivedTick = 0;
        OPTICAL_MOTION.clear();
        RADAR_MOTION.clear();
        opticalFrameTarget = radarFrameTarget = null;
        lastMotionNanos = 0;
        motionSeconds = 0;
        motionWasPaused = false;
    }

    public static void accept(CannonLockLeadPayload payload) {
        var world = Minecraft.getInstance().level;
        if (world != level) clear();
        advanceMotionClock();
        previous = world == level ? current : CannonLockLeadPayload.INACTIVE;
        current = payload;
        level = world;
        receivedTick = world == null ? 0 : world.getGameTime();
        acceptMotion(OPTICAL_MOTION, payload.optical());
        acceptMotion(RADAR_MOTION, payload.radar());
        if (payload.optical() == null || previous.optical() == null
                || !Objects.equals(payload.optical().acquisitionId(), previous.optical().acquisitionId())) opticalFrameTarget = null;
        if (payload.radar() == null || previous.radar() == null
                || !Objects.equals(payload.radar().acquisitionId(), previous.radar().acquisitionId())) radarFrameTarget = null;
    }

    private static void acceptMotion(LockTargetSmoother smoother, @Nullable CannonLockLeadPayload.Track track) {
        if (track == null) smoother.clear();
        else smoother.accept(track.acquisitionId(), track.target(), track.velocity(), motionSeconds);
    }

    private static void advanceMotionClock() {
        long now = System.nanoTime();
        boolean paused = Minecraft.getInstance().isPaused();
        if (lastMotionNanos != 0 && !paused && !motionWasPaused)
            motionSeconds += Math.clamp((now - lastMotionNanos) / 1_000_000_000.0, 0, .1);
        motionWasPaused = paused;
        lastMotionNanos = now;
    }

    /** Evaluate once per frame; camera, lock frame and input reference consume the same target. */
    public static void beginFrame() {
        advanceMotionClock();
        opticalFrameTarget = OPTICAL_MOTION.sample(motionSeconds);
        radarFrameTarget = RADAR_MOTION.sample(motionSeconds);
    }

    public static @Nullable Vec3 opticalTarget() {
        return fresh() && current.optical() != null
                ? opticalFrameTarget != null ? opticalFrameTarget : OPTICAL_MOTION.sample(motionSeconds) : null;
    }

    public static @Nullable Vec3 radarTarget() {
        return radar() != null ? radarFrameTarget != null ? radarFrameTarget : RADAR_MOTION.sample(motionSeconds) : null;
    }

    private static boolean fresh() {
        var world = Minecraft.getInstance().level;
        return world != null && world == level && world.getGameTime() >= receivedTick
                && world.getGameTime() - receivedTick <= 5;
    }

    public static @Nullable CannonLockLeadPayload.Track radar() {
        var track = fresh() ? current.radar() : null;
        return track != null && track.id() != null && ClientRadarState.isActive() && !ClientRadarState.isAircraftMode()
                && Objects.equals(track.id(), ClientRadarState.getLockedTarget()) ? track : null;
    }

    public static @Nullable CannonLockLeadPayload.Track radarCameraTrack() {
        var track = radar();
        return track != null && track.cameraTracking() ? track : null;
    }

    public static CannonLockLeadPayload.LeadStatus opticalStatus() {
        return fresh() && ClientAimState.isOpticalLockActive() && current.optical() != null
                ? current.optical().leadStatus() : CannonLockLeadPayload.LeadStatus.NONE;
    }

    public static CannonLockLeadPayload.LeadStatus radarStatus() {
        var track = radar();
        return track == null ? CannonLockLeadPayload.LeadStatus.NONE : track.leadStatus();
    }

    public static @Nullable UUID opticalAcquisitionId() {
        return fresh() && current.optical() != null ? current.optical().acquisitionId() : null;
    }

    public static @Nullable Vec3 opticalAimPoint(Vec3 displayedTarget, float partialTick) {
        return !fresh() || !ClientAimState.isOpticalLockActive() ? null
                : aimPoint(current.optical(), previous.optical(), displayedTarget, partialTick);
    }

    public static @Nullable Vec3 radarAimPoint(Vec3 displayedTarget, float partialTick) {
        return aimPoint(radar(), previous.radar(), displayedTarget, partialTick);
    }

    private static @Nullable Vec3 aimPoint(@Nullable CannonLockLeadPayload.Track now,
            @Nullable CannonLockLeadPayload.Track before, Vec3 displayedTarget, float partialTick) {
        if (now == null || now.aimPoint() == null) return null;
        Vec3 offset = now.aimPoint().subtract(now.target());
        if (before != null && before.aimPoint() != null && Objects.equals(now.id(), before.id())
                && now.target().distanceToSqr(before.target()) < 256 * 256)
            offset = before.aimPoint().subtract(before.target()).lerp(offset, Math.clamp(partialTick, 0, 1));
        return displayedTarget.add(offset);
    }
}
