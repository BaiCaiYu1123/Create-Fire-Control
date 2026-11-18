package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.TelevisionGuidanceInputPayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/** Client-only camera-mode bookkeeping for a server-selected missile camera. */
public final class ClientTelevisionGuidanceState {
    private static boolean active;
    private static CameraType previousCameraType;
    private static UUID missileId;
    private static boolean directionInitialized;
    private static float missileYaw;
    private static float elevation;
    private static boolean directionDirty;
    private static long lastDirectionSendTick = Long.MIN_VALUE;
    private static int remainingTicksAtEntry;
    private static long entryGameTick;

    public static void accept(
            boolean requested, UUID requestedMissileId, int requestedRemainingTicks
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (requested) {
            if (!active) previousCameraType = minecraft.options.getCameraType();
            active = true;
            missileId = requestedMissileId;
            directionInitialized = false;
            directionDirty = false;
            lastDirectionSendTick = Long.MIN_VALUE;
            remainingTicksAtEntry = Math.max(0, requestedRemainingTicks);
            entryGameTick = minecraft.level == null ? 0L : minecraft.level.getGameTime();
            // A detached rear view both matches the UCAV controls and makes the
            // camera entity's own missile model visible through vanilla rendering.
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            return;
        }
        restore();
    }

    public static void tick() {
        if (!active) return;
        if (ClientWeaponViewState.isActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            restore();
            return;
        }
        Entity camera = MissileLongRangeEffects.getRemoteVisual(missileId);
        if (camera == null && missileId != null) {
            for (Entity candidate : minecraft.level.entitiesForRendering()) {
                if (missileId.equals(candidate.getUUID())) {
                    camera = candidate;
                    break;
                }
            }
        }
        if (camera != null && !camera.isRemoved()) {
            minecraft.setCameraEntity(camera);
            if (!directionInitialized) initializeDirection(camera);
        }
        if (directionInitialized) {
            long tick = minecraft.level.getGameTime();
            if (directionDirty || lastDirectionSendTick == Long.MIN_VALUE
                    || tick - lastDirectionSendTick >= 5L) {
                sendDirection(tick);
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** The visual used by both the detached camera and forced television-view effects. */
    public static Entity getMissileVisual() {
        if (!active || missileId == null) return null;
        Entity visual = MissileLongRangeEffects.getRemoteVisual(missileId);
        if (visual != null && !visual.isRemoved()) return visual;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return null;
        for (Entity candidate : minecraft.level.entitiesForRendering()) {
            if (missileId.equals(candidate.getUUID()) && !candidate.isRemoved()) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean hasDirection() {
        return active && directionInitialized;
    }

    /** Camera yaw uses vanilla's sign convention, opposite Mianbao's missile yaw. */
    public static float getCameraYaw() {
        return -missileYaw;
    }

    /** Camera pitch is positive downward, while the missile's elevation is positive upward. */
    public static float getCameraPitch() {
        return -elevation;
    }

    public static double getRemainingSeconds() {
        Minecraft minecraft = Minecraft.getInstance();
        long elapsed = minecraft.level == null
                ? 0L : Math.max(0L, minecraft.level.getGameTime() - entryGameTick);
        int ticks = Math.max(0, remainingTicksAtEntry - (int) Math.min(Integer.MAX_VALUE, elapsed));
        return Math.floor(ticks / 2.0) / 10.0;
    }

    /**
     * Consumes only mouse-look while television guidance is active. Keyboard,
     * remote-controller and vehicle inputs continue through their normal paths.
     */
    public static boolean turn(double yawInput, double pitchInput) {
        if (!active) return false;
        if (!directionInitialized) {
            Entity camera = MissileLongRangeEffects.getRemoteVisual(missileId);
            if (camera != null && !camera.isRemoved()) initializeDirection(camera);
        }
        if (!directionInitialized) return true;
        if (Math.abs(yawInput) < 1.0e-7 && Math.abs(pitchInput) < 1.0e-7) {
            return true;
        }
        // Entity.turn uses positive yaw for a turn toward -X. Mianbao's missile
        // renderer uses positive yaw toward +X, hence both input signs invert.
        missileYaw -= (float) yawInput * 0.15F;
        elevation = Mth.clamp(
                elevation - (float) pitchInput * 0.15F,
                -89.9F,
                89.9F
        );
        directionDirty = true;
        return true;
    }

    private static void initializeDirection(Entity missile) {
        Vec3 velocity = missile.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0e-8) {
            missileYaw = (float) (Mth.atan2(velocity.x, velocity.z) * Mth.RAD_TO_DEG);
            elevation = (float) (Mth.atan2(
                    velocity.y, velocity.horizontalDistance()) * Mth.RAD_TO_DEG);
        } else {
            missileYaw = missile.getYRot();
            elevation = missile.getXRot();
        }
        directionInitialized = true;
        directionDirty = true;
    }

    private static Vec3 direction() {
        double yawRadians = missileYaw * Mth.DEG_TO_RAD;
        double elevationRadians = elevation * Mth.DEG_TO_RAD;
        double horizontal = Math.cos(elevationRadians);
        return new Vec3(
                Math.sin(yawRadians) * horizontal,
                Math.sin(elevationRadians),
                Math.cos(yawRadians) * horizontal
        );
    }

    private static void sendDirection(long tick) {
        Vec3 direction = direction();
        PacketDistributor.sendToServer(new TelevisionGuidanceInputPayload(
                direction.x, direction.y, direction.z));
        directionDirty = false;
        lastDirectionSendTick = tick;
    }

    private static void restore() {
        Minecraft minecraft = Minecraft.getInstance();
        active = false;
        missileId = null;
        directionInitialized = false;
        directionDirty = false;
        lastDirectionSendTick = Long.MIN_VALUE;
        remainingTicksAtEntry = 0;
        entryGameTick = 0L;
        if (minecraft.player != null) minecraft.setCameraEntity(minecraft.player);
        if (previousCameraType != null) {
            minecraft.options.setCameraType(previousCameraType);
            previousCameraType = null;
        }
    }

    private ClientTelevisionGuidanceState() {
    }
}
