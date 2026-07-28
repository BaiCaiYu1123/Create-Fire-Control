package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.compat.VoxyLodRaycaster;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.hooya.stabilizedturret.network.AimDirectionPayload;
import com.hooya.stabilizedturret.network.AimSessionPayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ClientAimState {
    private static final double AIM_RAY_DISTANCE = 4096.0;
    /** Matches Voxy's 16 * 3000 far projection instead of vanilla chunk range. */
    private static final double VOXY_AIM_RAY_DISTANCE = 48_000.0;
    private static final double SCOPED_CANNON_FALLBACK_DISTANCE_SQR = 64.0 * 64.0;
    private static final String CBC_CANNON_ENTITY_CLASS =
            "rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity";
    private static final String CBC_CANNON_CONTRAPTION_CLASS =
            "rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption";
    private static final PodAimController POD_AIM = new PodAimController();
    private static boolean active;
    private static boolean remoteControl;
    private static boolean headAim;
    private static boolean aircraftEquipment;
    private static boolean scoped;
    private static boolean podView;
    private static boolean thermal;
    private static boolean opticalLockActive;
    private static boolean opticalLockSearching;
    private static boolean opticalLockObscured;
    private static long opticalLockObscuredSinceNanos = Long.MIN_VALUE;
    private static Vec3 opticalLockTarget = Vec3.ZERO;
    private static Vec3 previousOpticalLockTarget = Vec3.ZERO;
    private static Vec3 previousOpticalLockDisplayTarget = Vec3.ZERO;
    private static Vec3 previousOpticalCameraOrigin = Vec3.ZERO;
    private static AABB opticalLockBounds;
    private static AABB previousOpticalLockBounds;
    private static boolean opticalLockTargetKnown;
    private static boolean airToGroundLockSearching;
    private static boolean airToGroundLockActive;
    private static AABB airToGroundLockBounds;
    private static AABB previousAirToGroundLockBounds;
    private static boolean wasLockingFirstPerson;
    private static boolean scopeCameraCaptured;
    private static CameraType cameraBeforeScope = CameraType.FIRST_PERSON;
    private static boolean hideGuiBeforeScope;
    private static double thirdPersonHeight;
    private static double viewAimOffsetX;
    private static double viewAimOffsetY;
    private static boolean cannonAimAvailable;
    private static Vec3 cannonAimPoint = Vec3.ZERO;
    private static Vec3 previousCannonAimPoint = Vec3.ZERO;
    private static int cannonAimInterpolationTicks = 2;
    private static int cannonEntityId = -1;
    private static UUID cannonEntityUuid;
    private static UUID cannonSubLevelId;
    private static UUID pitchSubLevelId;
    private static BlockPos cannonMountPos;
    private static UUID cannonMountSubLevelId;
    private static final Set<UUID> SCOPE_CANNON_DIAGNOSTICS = new HashSet<>();
    private static final Set<UUID> SCOPE_SUBLEVEL_DIAGNOSTICS = new HashSet<>();
    private static final Map<net.minecraft.world.entity.Entity, Boolean> THERMAL_GLOW_STATES =
            new WeakHashMap<>();

    private static Vec3 scopePosition = Vec3.ZERO;
    private static Vec3 previousScopePosition = Vec3.ZERO;
    private static boolean scopePoseRelative;
    private static Vec3 pendingThirdPersonScopeExitTarget;
    private static Vec3 lastObservedWorldLook = new Vec3(0.0, 0.0, 1.0);
    private static float stableWorldYaw;
    private static float stableWorldPitch;
    private static boolean podCameraCaptured;
    private static CameraType cameraBeforePod = CameraType.FIRST_PERSON;
    private static boolean hideGuiBeforePod;
    private static float playerYawBeforePod;
    private static float playerPitchBeforePod;
    private static float playerPreviousYawBeforePod;
    private static float playerPreviousPitchBeforePod;
    private static Vec3 observedWorldLookBeforePod = new Vec3(0.0, 0.0, 1.0);
    private static float trackedRenderFromYaw;
    private static float trackedRenderFromPitch;
    private static boolean trackedRenderInterpolation;
    private static double lastAimDistance;
    private static double displayedScopeZoom = Double.NaN;
    private static double displayedPodZoom = Double.NaN;
    private static long lastZoomUpdateNanos;

    public static void accept(AimSessionPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (!payload.active()) {
            if (podView) {
                podView = false;
                restoreCameraAfterPod(minecraft);
            }
            // Scope FOV and the forced first-person camera must no longer be
            // active while restoring an offset third-person view. Otherwise
            // the inverse offset is solved with the magnified FOV and makes
            // the raw screen centre, rather than the configured reticle, keep
            // the last scoped aim point.
            scoped = false;
            restoreCameraAfterScope(minecraft);
            active = false;
            remoteControl = false;
            headAim = false;
            aircraftEquipment = false;
            cannonEntityId = -1;
            cannonEntityUuid = null;
            cannonSubLevelId = null;
            pitchSubLevelId = null;
            cannonMountPos = null;
            cannonMountSubLevelId = null;
            cannonAimAvailable = false;
            scopePoseRelative = false;
            pendingThirdPersonScopeExitTarget = null;
            opticalLockActive = false;
            opticalLockSearching = false;
            opticalLockObscured = false;
            opticalLockObscuredSinceNanos = Long.MIN_VALUE;
            opticalLockBounds = null;
            airToGroundLockSearching = false;
            airToGroundLockActive = false;
            airToGroundLockBounds = null;
            previousAirToGroundLockBounds = null;
            opticalLockTargetKnown = false;
            SCOPE_CANNON_DIAGNOSTICS.clear();
            SCOPE_SUBLEVEL_DIAGNOSTICS.clear();
            wasLockingFirstPerson = false;
            POD_AIM.end();
            setThermal(false);
            return;
        }

        if ((!active || (!headAim && payload.headAim())) && player != null) {
            // Before first-person suppression becomes active, Sable returns
            // the fully transformed world look vector here.
            captureStableWorldLook(player.getLookAngle());
        }
        active = true;
        remoteControl = payload.remoteControl();
        headAim = payload.headAim();
        aircraftEquipment = payload.aircraftEquipment();
        previousScopePosition = scopePosition;
        scopePosition = new Vec3(payload.scopeX(), payload.scopeY(), payload.scopeZ());
        scopePoseRelative = payload.scopePoseRelative();
        cannonEntityId = payload.cannonEntityId();
        cannonEntityUuid = payload.cannonEntityUuid();
        cannonSubLevelId = payload.cannonSubLevelId();
        pitchSubLevelId = payload.pitchSubLevelId();
        cannonMountPos = payload.cannonMountPos();
        cannonMountSubLevelId = payload.cannonMountSubLevelId();
        cannonAimAvailable = payload.cannonAimAvailable();
        previousCannonAimPoint = cannonAimPoint;
        Vec3 rawCannonAimPoint = new Vec3(payload.cannonAimX(), payload.cannonAimY(), payload.cannonAimZ());
        Vec3 cannonOrigin = new Vec3(payload.cannonOriginX(), payload.cannonOriginY(), payload.cannonOriginZ());
        cannonAimPoint = cannonAimAvailable && player != null
                ? clipCannonRay(minecraft, player, cannonOrigin, rawCannonAimPoint)
                : rawCannonAimPoint;
        cannonAimInterpolationTicks = 0;
        boolean wasOpticalLockActive = opticalLockActive;
        previousOpticalLockDisplayTarget = opticalLockTarget;
        previousOpticalLockTarget = opticalLockTarget;
        previousOpticalLockBounds = opticalLockBounds;
        boolean wasOpticalLockObscured = opticalLockObscured;
        opticalLockActive = payload.opticalLockActive();
        opticalLockSearching = payload.opticalLockSearching();
        opticalLockObscured = payload.opticalLockObscured();
        if (opticalLockActive && opticalLockObscured) {
            if (!wasOpticalLockObscured || opticalLockObscuredSinceNanos == Long.MIN_VALUE) {
                opticalLockObscuredSinceNanos = System.nanoTime();
            }
        } else {
            opticalLockObscuredSinceNanos = Long.MIN_VALUE;
        }
        opticalLockTarget = new Vec3(
                payload.opticalLockTargetX(),
                payload.opticalLockTargetY(),
                payload.opticalLockTargetZ()
        );
        opticalLockBounds = payload.opticalLockBoundsAvailable()
                ? new AABB(
                payload.opticalLockMinX(),
                payload.opticalLockMinY(),
                payload.opticalLockMinZ(),
                payload.opticalLockMaxX(),
                payload.opticalLockMaxY(),
                payload.opticalLockMaxZ()
                )
                : null;
        previousAirToGroundLockBounds = airToGroundLockBounds;
        airToGroundLockSearching = payload.airToGroundLockSearching();
        airToGroundLockActive = payload.airToGroundLockActive();
        airToGroundLockBounds = payload.airToGroundLockBoundsAvailable()
                ? new AABB(
                payload.airToGroundLockMinX(),
                payload.airToGroundLockMinY(),
                payload.airToGroundLockMinZ(),
                payload.airToGroundLockMaxX(),
                payload.airToGroundLockMaxY(),
                payload.airToGroundLockMaxZ()
        )
                : null;
        if (opticalLockActive && !wasOpticalLockActive && player != null) {
            aimReticleAt(minecraft, player, opticalLockTarget);
            previousOpticalLockTarget = opticalLockTarget;
            previousOpticalLockDisplayTarget = opticalLockTarget;
            previousOpticalLockBounds = opticalLockBounds;
            previousOpticalCameraOrigin = opticalTrackingOrigin(minecraft);
            opticalLockTargetKnown = true;
        } else if (!opticalLockActive) {
            opticalLockTargetKnown = false;
        }
        boolean nextScoped = payload.scoped();
        boolean nextPodView = payload.podView();
        thirdPersonHeight = payload.thirdPersonHeight();
        viewAimOffsetX = payload.viewAimOffsetX();
        viewAimOffsetY = payload.viewAimOffsetY();
        if (!scoped && nextScoped) {
            pendingThirdPersonScopeExitTarget = null;
            AimRay entryRay = player == null ? null : getScreenCenterRay(minecraft, player);
            cameraBeforeScope = minecraft.options.getCameraType();
            hideGuiBeforeScope = minecraft.options.hideGui;
            minecraft.options.hideGui = false;
            scopeCameraCaptured = true;
            if (player != null) {
                Vec3 entryTarget = entryRay == null
                        ? player.getEyePosition().add(player.getLookAngle().scale(AIM_RAY_DISTANCE))
                        : entryRay.target();
                Vec3 opticToImpact = entryTarget.subtract(getScopePosition(1.0F));
                captureStableWorldLook(opticToImpact.lengthSqr() > 1.0e-8
                        ? opticToImpact
                        : scopeEntryDirection(minecraft));
            }
            SCOPE_CANNON_DIAGNOSTICS.clear();
            SCOPE_SUBLEVEL_DIAGNOSTICS.clear();
        }
        if (scoped && !nextScoped) {
            if (player != null) {
                AimRay exitRay = getScreenCenterRay(minecraft, player);
                if (exitRay.direction().lengthSqr() > 1.0e-8) {
                    captureStableWorldLook(exitRay.direction());
                }
                if (cameraBeforeScope != CameraType.FIRST_PERSON) {
                    pendingThirdPersonScopeExitTarget = exitRay.target();
                }
            }
            scoped = false;
            restoreCameraAfterScope(minecraft);
        }
        if (!podView && nextPodView) {
            enterPodView(minecraft, player, new Vec3(
                    payload.podAimX(), payload.podAimY(), payload.podAimZ()));
        }
        if (podView && !nextPodView) {
            podView = false;
            restoreCameraAfterPod(minecraft);
            POD_AIM.end();
        }
        scoped = nextScoped;
        podView = nextPodView;
        setThermal(payload.thermal());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!active || player == null || minecraft.level == null) {
            return;
        }

        trackedRenderInterpolation = false;
        cannonAimInterpolationTicks = Math.min(2, cannonAimInterpolationTicks + 1);

        if (scoped || podView) {
            // Individual HUD layers are filtered by ScopeHudRenderer. Keep
            // vanilla GUI rendering enabled so the requested hotbar can pass.
            minecraft.options.hideGui = false;
        }

        boolean lockFirstPerson = shouldLockFirstPerson();
        if (lockFirstPerson && !wasLockingFirstPerson) {
            captureStableWorldLook(lastObservedWorldLook);
        }
        if (lockFirstPerson) {
            applyStableWorldLook(player);
            lastObservedWorldLook = directionFromStableAngles();
        } else {
            // Sable's unlocked physical-structure view already exposes a
            // world-space vector and remains the sole owner of that mode.
            lastObservedWorldLook = player.getLookAngle().normalize();
        }
        wasLockingFirstPerson = lockFirstPerson;
        followOpticalLockMotion(minecraft, player);

        if (podView) {
            // The pod controller is the sole writer of attitude and target.
            // No session packet, model angle, or asynchronous depth result is
            // allowed to feed back into this state.
            POD_AIM.stabilize(getPodInputOrigin(minecraft));
        }

        if (headAim || podView || opticalLockActive || opticalLockSearching) {
            AimRay ray = getScreenCenterRay(minecraft, player);
            SubLevelAimHit subLevelHit = resolveSubLevelAimHit(minecraft, player, ray);
            OpticalFrameAngles opticalFrame = opticalFrameAngles(minecraft);
            Vec3 transmittedTarget = podView && POD_AIM.hasTarget()
                    ? POD_AIM.target()
                    : ray.target;
            PacketDistributor.sendToServer(new AimDirectionPayload(
                    ray.origin.x, ray.origin.y, ray.origin.z,
                    ray.direction.x, ray.direction.y, ray.direction.z,
                    transmittedTarget.x, transmittedTarget.y, transmittedTarget.z,
                    ray.hit,
                    subLevelHit == null ? null : subLevelHit.id(),
                    subLevelHit == null ? 0.0 : subLevelHit.localAnchor().x,
                    subLevelHit == null ? 0.0 : subLevelHit.localAnchor().y,
                    subLevelHit == null ? 0.0 : subLevelHit.localAnchor().z,
                    opticalFrame.halfTanX(),
                    opticalFrame.halfTanY(),
                    opticalLockActive,
                    opticalLockActive ? opticalLockTarget.x : 0.0,
                    opticalLockActive ? opticalLockTarget.y : 0.0,
                    opticalLockActive ? opticalLockTarget.z : 0.0
            ));
        }
        updateThermalHighlights(minecraft, player);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!isOpticalView() || Math.abs(event.getScrollDeltaY()) < 1.0e-6) {
            return;
        }
        double step = Math.signum(event.getScrollDeltaY());
        if (isPodView()) {
            ClientScopeConfig.setPodZoom(ClientScopeConfig.getPodZoom() + step);
        } else {
            ClientScopeConfig.setZoom(ClientScopeConfig.getZoom() + step);
        }
        event.setCanceled(true);
    }

    private static AimRay getScreenCenterRay(Minecraft minecraft, LocalPlayer player) {
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        boolean firstPerson = minecraft.options.getCameraType() == CameraType.FIRST_PERSON;
        Vec3 start = firstPerson && !scoped && !podView
                ? Sable.HELPER.getEyePositionInterpolated(player, 1.0F)
                : camera.getPosition();
        Vec3 direction;
        if (podView) {
            direction = POD_AIM.stabilize(start);
        } else if (firstPerson && shouldLockFirstPerson()) {
            direction = directionFromStableAngles();
        } else {
            Vector3f cameraForward = camera.getLookVector();
            direction = new Vec3(cameraForward.x, cameraForward.y, cameraForward.z).normalize();
        }
        if (!firstPerson && !scoped) {
            direction = applyViewAimOffset(minecraft, camera, direction);
        }
        return clipAimRay(minecraft, player, start, direction, false);
    }

    private static AimRay clipAimRay(
            Minecraft minecraft,
            LocalPlayer player,
            Vec3 start,
            Vec3 direction,
            boolean allowVoxyLod
    ) {
        direction = direction.lengthSqr() > 1.0e-8
                ? direction.normalize()
                : new Vec3(0.0, 0.0, -1.0);
        Vec3 end = start.add(direction.scale(AIM_RAY_DISTANCE));
        ClipContext context = new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        );
        Set<SubLevel> ignored = new HashSet<>(ownStructureClosure(minecraft, player));
        // Pod view may be opened remotely while the player is not riding the
        // aircraft. Ignore the pod's complete connected Sable structure as
        // well, otherwise the initial gyro point can be captured on the
        // aircraft's own skin a few centimetres in front of the camera.
        if ((podView || podCameraCaptured) && cannonMountSubLevelId != null) {
            SubLevel podLevel = SubLevelContainer.getContainer(minecraft.level)
                    .getSubLevel(cannonMountSubLevelId);
            if (podLevel != null && !podLevel.isRemoved()) {
                ignored.addAll(connectedStructureClosure(minecraft.level, podLevel));
            }
        }
        if (!ignored.isEmpty()) {
            ((ClipContextExtension) context).sable$setSubLevelIgnoring(
                    ignored::contains
            );
        }
        HitResult result = minecraft.level.clip(context);
        boolean hit = result.getType() != HitResult.Type.MISS;
        Vec3 target = hit
                ? projectPhysicalHitToWorld(minecraft, start, direction, result.getLocation())
                : end;
        // Some Sable renderer/clip combinations do not honour the ignored
        // sub-level predicate for the first physical hit after a contraption
        // pose update. Never let that transient self-hit become the pod's
        // inertial target: a point attached to the carrier moves with the
        // aircraft and makes manual input appear to be pulled back or locked.
        // Check the complete bearing/welded carrier closure as a second,
        // representation-independent guard.
        if (hit && (podView || podCameraCaptured)
                && isPointOnPodCarrier(minecraft, target)) {
            hit = false;
            target = end;
            result = null;
        }
        if (!hit && allowVoxyLod && (podView || podCameraCaptured)) {
            Vec3 voxyTarget = VoxyLodRaycaster.clip(
                    minecraft.level,
                    start,
                    direction,
                    VOXY_AIM_RAY_DISTANCE
            );
            if (voxyTarget != null) {
                target = voxyTarget;
                hit = true;
                // A Voxy voxel is world terrain, never a Sable sub-level hit.
                result = null;
            }
        }
        lastAimDistance = start.distanceTo(target);
        return new AimRay(start, direction, target, hit, result);
    }

    private static OpticalFrameAngles opticalFrameAngles(Minecraft minecraft) {
        int guiHeight = Math.max(1, minecraft.getWindow().getGuiScaledHeight());
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        double verticalFov = ((GameRendererAccessor) (Object) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, 1.0F, true);
        if (isOpticalView()) {
            verticalFov = Math.toDegrees(2.0 * Math.atan(
                    Math.tan(Math.toRadians(verticalFov) * 0.5) * getScopeZoom()
            ));
        }
        double focal = guiHeight * 0.5 / Math.tan(Mth.DEG_TO_RAD * verticalFov * 0.5);
        double distance = Math.max(1.0, lastAimDistance);
        return new OpticalFrameAngles(
                Mth.clamp(Math.max(9.0 / Math.max(1.0, focal), 1.2 / distance), 1.0e-4, 1.0),
                Mth.clamp(Math.max(9.0 / Math.max(1.0, focal), 1.0 / distance), 1.0e-4, 1.0)
        );
    }

    /**
     * Applies the change in target bearing rather than only its world-space
     * displacement. This includes movement of the tank/camera itself, so a
     * stationary target remains tracked while the hull drives or turns. The
     * player's manually added angular lead is preserved.
     */
    private static void followOpticalLockMotion(Minecraft minecraft, LocalPlayer player) {
        if (!opticalLockActive) {
            return;
        }
        Vec3 currentCameraOrigin = opticalTrackingOrigin(minecraft);
        if (!opticalLockTargetKnown) {
            opticalLockTargetKnown = true;
            previousOpticalLockTarget = opticalLockTarget;
            previousOpticalCameraOrigin = currentCameraOrigin;
            aimReticleAt(minecraft, player, opticalLockTarget);
            return;
        }
        Vec3 previousBearing = previousOpticalLockTarget.subtract(previousOpticalCameraOrigin);
        Vec3 currentBearing = opticalLockTarget.subtract(currentCameraOrigin);
        previousOpticalLockTarget = opticalLockTarget;
        previousOpticalCameraOrigin = currentCameraOrigin;
        if (previousBearing.lengthSqr() < 1.0e-8 || currentBearing.lengthSqr() < 1.0e-8) {
            return;
        }
        float previousYaw = directionYaw(previousBearing);
        float currentYaw = directionYaw(currentBearing);
        float previousPitch = directionPitch(previousBearing);
        float currentPitch = directionPitch(currentBearing);
        float yawDelta = Mth.wrapDegrees(currentYaw - previousYaw);
        float pitchDelta = Mth.wrapDegrees(currentPitch - previousPitch);
        if (Math.abs(yawDelta) < 1.0e-5F && Math.abs(pitchDelta) < 1.0e-5F) {
            return;
        }
        if (isScoped() || minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            float renderFromYaw = stableWorldYaw;
            float renderFromPitch = stableWorldPitch;
            stableWorldYaw += yawDelta;
            stableWorldPitch = Mth.clamp(stableWorldPitch + pitchDelta, -90.0F, 90.0F);
            lastObservedWorldLook = directionFromStableAngles();
            applyStableWorldLook(player);
            beginTrackedRenderInterpolation(player, renderFromYaw, renderFromPitch);
        } else {
            // Preserve the player's manually chosen angular lead while moving
            // the view by exactly the target/vehicle bearing delta.
            Vec3 rawLook = lastObservedWorldLook.lengthSqr() > 1.0e-8
                    ? lastObservedWorldLook
                    : player.getLookAngle();
            float renderFromYaw = stableWorldYaw;
            float renderFromPitch = stableWorldPitch;
            Vec3 reticleLook = applyViewAimOffsetToAngles(
                    minecraft,
                    yawOf(rawLook),
                    pitchOf(rawLook)
            );
            float desiredReticleYaw = yawOf(reticleLook) + yawDelta;
            stableWorldYaw += Mth.wrapDegrees(desiredReticleYaw - stableWorldYaw);
            stableWorldPitch = Mth.clamp(pitchOf(reticleLook) + pitchDelta, -90.0F, 90.0F);
            restoreOffsetThirdPersonLook(minecraft, player);
            beginTrackedRenderInterpolation(player, renderFromYaw, renderFromPitch);
        }
    }

    private static Vec3 opticalTrackingOrigin(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        return player == null
                ? minecraft.gameRenderer.getMainCamera().getPosition()
                : Sable.HELPER.getEyePositionInterpolated(player, 1.0F);
    }

    private static float directionYaw(Vec3 direction) {
        Vec3 normalized = direction.normalize();
        return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-normalized.x, normalized.z)));
    }

    private static float directionPitch(Vec3 direction) {
        Vec3 normalized = direction.normalize();
        double horizontal = Math.sqrt(normalized.x * normalized.x + normalized.z * normalized.z);
        return Mth.clamp(
                Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-normalized.y, horizontal))),
                -90.0F,
                90.0F
        );
    }

    /**
     * Places the displayed reticle on a point once. For third-person this
     * solves the controller's configured reticle offset rather than aiming
     * the invisible raw camera centre.
     */
    private static void aimReticleAt(Minecraft minecraft, LocalPlayer player, Vec3 target) {
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 origin = isScoped() ? getScopePosition(1.0F) : camera.getPosition();
        Vec3 direction = target.subtract(origin);
        if (direction.lengthSqr() < 1.0e-8) {
            return;
        }
        captureStableWorldLook(direction);
        if (isScoped() || minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            applyStableWorldLook(player);
        } else {
            restoreOffsetThirdPersonLook(minecraft, player);
        }
    }

    private static SubLevelAimHit resolveSubLevelAimHit(
            Minecraft minecraft,
            LocalPlayer player,
            AimRay ray
    ) {
        if (!ray.hit || ray.rawResult == null || minecraft.level == null) {
            return null;
        }
        try {
            SubLevel subLevel = Sable.HELPER.getContaining(minecraft.level, ray.rawResult.getLocation());
            if (subLevel == null || subLevel.isRemoved() || ownStructureClosure(minecraft, player).contains(subLevel)) {
                return null;
            }
            // Sable's clip overwrite returns physical hits directly in the
            // sub-level plot coordinate system. Applying inverseLogicalPose
            // here treated that plot point as world space and transformed it
            // a second time, placing both the tracked anchor and its frame far
            // away from the actual structure.
            Vec3 plotAnchor = ray.rawResult.getLocation();
            return new SubLevelAimHit(subLevel.getUniqueId(), plotAnchor);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Vec3 scopeEntryDirection(Minecraft minecraft) {
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        Vector3f cameraForward = camera.getLookVector();
        Vec3 direction = new Vec3(cameraForward.x, cameraForward.y, cameraForward.z).normalize();
        if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            direction = applyViewAimOffset(minecraft, camera, direction);
        }
        return direction;
    }

    private static Vec3 clipCannonRay(
            Minecraft minecraft,
            LocalPlayer player,
            Vec3 origin,
            Vec3 end
    ) {
        if (minecraft.level == null || origin.distanceToSqr(end) < 1.0e-8) {
            return end;
        }
        Vec3 direction = end.subtract(origin).normalize();
        ClipContext context = new ClipContext(
                origin,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        );
        Set<SubLevel> ignored = ownStructureClosure(minecraft, player);
        if (!ignored.isEmpty()) {
            ((ClipContextExtension) context).sable$setSubLevelIgnoring(ignored::contains);
        }
        HitResult result = minecraft.level.clip(context);
        return result.getType() == HitResult.Type.MISS
                ? end
                : projectPhysicalHitToWorld(minecraft, origin, direction, result.getLocation());
    }

    private static Vec3 applyViewAimOffset(
            Minecraft minecraft,
            net.minecraft.client.Camera camera,
            Vec3 forward
    ) {
        if (Math.abs(viewAimOffsetX) < 1.0e-6 && Math.abs(viewAimOffsetY) < 1.0e-6) {
            return forward;
        }
        double currentFov = ((GameRendererAccessor) (Object) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, 1.0F, true);
        double fovRadians = Math.toRadians(currentFov);
        double tangent = Math.tan(fovRadians * 0.5);
        int windowWidth = Math.max(1, minecraft.getWindow().getWidth());
        int windowHeight = Math.max(1, minecraft.getWindow().getHeight());
        double aspect = (double) windowWidth / windowHeight;
        double ndcX = viewAimOffsetX * 0.02;
        double ndcY = viewAimOffsetY * 0.02;
        Vector3f leftVector = camera.getLeftVector();
        Vector3f upVector = camera.getUpVector();
        Vec3 left = new Vec3(leftVector.x, leftVector.y, leftVector.z);
        Vec3 up = new Vec3(upVector.x, upVector.y, upVector.z);
        return forward
                .subtract(left.scale(ndcX * tangent * aspect))
                .subtract(up.scale(ndcY * tangent))
                .normalize();
    }

    /**
     * Sable raycasts physical structures in their plot-space coordinates and
     * returns the selected BlockHitResult without converting its location back
     * to world space. Head aim is a world-space servo, so sending that raw
     * location makes the server reject the ray or aim toward the plot area.
     */
    private static Vec3 projectPhysicalHitToWorld(
            Minecraft minecraft,
            Vec3 worldOrigin,
            Vec3 worldDirection,
            Vec3 hitLocation
    ) {
        if (minecraft.level == null) {
            return hitLocation;
        }
        SubLevel hitSubLevel = Sable.HELPER.getContaining(minecraft.level, hitLocation);
        if (hitSubLevel == null) {
            return hitLocation;
        }

        Vector3d projected = new Vector3d(hitLocation.x, hitLocation.y, hitLocation.z);
        hitSubLevel.logicalPose().transformPosition(projected);
        Vec3 worldHit = new Vec3(projected.x, projected.y, projected.z);

        // Preserve the rigid transform's hit distance but rebuild the final
        // point from the actual screen-centre ray. This removes tiny client
        // render-pose/logical-pose differences and guarantees that first- and
        // third-person simulated rays remain exactly at the crosshair centre.
        double hitDistance = worldOrigin.distanceTo(worldHit);
        return worldOrigin.add(worldDirection.scale(hitDistance));
    }

    private static Set<SubLevel> ownStructureClosure(Minecraft minecraft, LocalPlayer player) {
        Set<SubLevel> ignored = new HashSet<>();
        Set<SubLevel> seeds = new HashSet<>();
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(player);
        SubLevel vehicle = Sable.HELPER.getVehicleSubLevel(player);
        if (tracking != null) {
            seeds.add(tracking);
        }
        if (vehicle != null) {
            seeds.add(vehicle);
        }
        if (player.getVehicle() != null) {
            SubLevel containingVehicle = Sable.HELPER.getContaining(player.getVehicle());
            if (containingVehicle != null) {
                seeds.add(containingVehicle);
            }
        }
        for (SubLevel seed : seeds) {
            ignored.addAll(connectedStructureClosure(minecraft.level, seed));
        }
        return ignored;
    }

    /**
     * Expands both outgoing and incoming actor dependencies. This also covers
     * the short synchronization window where only one side of a nested swivel
     * bearing has received its parent/child connection data.
     */
    private static Set<SubLevel> connectedStructureClosure(net.minecraft.world.level.Level level, SubLevel root) {
        Set<SubLevel> ignored = new HashSet<>(SubLevelHelper.getConnectedChain(root));
        boolean changed;
        do {
            changed = false;
            for (SubLevel candidate : SubLevelContainer.getContainer(level).getAllSubLevels()) {
                if (candidate.isRemoved() || ignored.contains(candidate)) {
                    continue;
                }
                Set<SubLevel> candidateChain = new HashSet<>(SubLevelHelper.getConnectedChain(candidate));
                boolean touchesIgnored = candidateChain.stream().anyMatch(ignored::contains);
                if (touchesIgnored) {
                    changed |= ignored.addAll(candidateChain);
                }
            }
        } while (changed);
        return ignored;
    }

    private record AimRay(Vec3 origin, Vec3 direction, Vec3 target, boolean hit, HitResult rawResult) {
    }

    private record SubLevelAimHit(UUID id, Vec3 localAnchor) {
    }

    private record OpticalFrameAngles(double halfTanX, double halfTanY) {
    }

    /** Called by the client-only Entity.turn mixin; returns true when consumed. */
    public static boolean turnStableFirstPerson(LocalPlayer player, double yawInput, double pitchInput) {
        if (isPodView()) {
            if (Math.abs(yawInput) < 1.0e-7 && Math.abs(pitchInput) < 1.0e-7) {
                return true;
            }
            Minecraft minecraft = Minecraft.getInstance();
            Vec3 origin = getPodInputOrigin(minecraft);
            float opticalSensitivity = (float) (1.0 / getScopeZoom());
            float yawDelta = (float) yawInput * 0.15F * opticalSensitivity;
            float pitchDelta = (float) pitchInput * 0.15F * opticalSensitivity;
            Vec3 direction = POD_AIM.turn(
                    origin, yawDelta, pitchDelta);
            // Resolve synchronously in the same input frame. Level.clip does
            // not intersect Create contraption entities or projectiles, and
            // the Sable ignore set removes the complete carrier chain. Voxy
            // is the final terrain fallback; rendered GPU depth is no longer
            // an authority and therefore cannot steal the sight.
            AimRay resolved = clipAimRay(
                    minecraft, player, origin, direction, true);
            POD_AIM.acceptTarget(origin, resolved.target());
            return true;
        }
        if (!shouldLockFirstPerson()) {
            return false;
        }
        float opticalSensitivity = isScoped() ? (float) (1.0 / getScopeZoom()) : 1.0F;
        stableWorldPitch = Mth.clamp(
                stableWorldPitch + (float) pitchInput * 0.15F * opticalSensitivity,
                -90.0F,
                90.0F
        );
        stableWorldYaw += (float) yawInput * 0.15F * opticalSensitivity;
        applyStableWorldLook(player);
        lastObservedWorldLook = directionFromStableAngles();
        if (player.getVehicle() != null) {
            player.getVehicle().onPassengerTurned(player);
        }
        return true;
    }

    public static void restoreObservedWorldLook(LocalPlayer player) {
        captureStableWorldLook(lastObservedWorldLook);
        applyStableWorldLook(player);
    }

    private static void captureStableWorldLook(Vec3 look) {
        Vec3 normalized = look.lengthSqr() < 1.0e-8 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();
        double horizontal = Math.sqrt(normalized.x * normalized.x + normalized.z * normalized.z);
        float capturedYaw = Mth.wrapDegrees(
                (float) Math.toDegrees(Math.atan2(-normalized.x, normalized.z))
        );
        // Keep the internal angle continuous across the -180/+180 seam. A
        // hard wrap here makes Sable/vanilla observe a one-frame 360-degree
        // discontinuity, visible as a flash in both supported camera modes.
        stableWorldYaw += Mth.wrapDegrees(capturedYaw - stableWorldYaw);
        stableWorldPitch = Mth.clamp(
                Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-normalized.y, horizontal))),
                -90.0F,
                90.0F
        );
        lastObservedWorldLook = normalized;
    }

    private static Vec3 directionFromStableAngles() {
        return directionFromAngles(stableWorldYaw, stableWorldPitch);
    }

    private static Vec3 getPodInputOrigin(Minecraft minecraft) {
        return POD_AIM.inputOrigin(getScopePosition(1.0F));
    }

    private static boolean isPointOnPodCarrier(Minecraft minecraft, Vec3 worldPoint) {
        if (minecraft.level == null || cannonMountSubLevelId == null) return false;
        SubLevel root = SubLevelContainer.getContainer(minecraft.level)
                .getSubLevel(cannonMountSubLevelId);
        if (root == null || root.isRemoved()) return false;
        Vec3 origin = getPodInputOrigin(minecraft);
        Vec3 direction = worldPoint.subtract(origin);
        direction = direction.lengthSqr() > 1.0e-8
                ? direction.normalize()
                : new Vec3(0.0, 0.0, 1.0);
        for (SubLevel subLevel : connectedStructureClosure(minecraft.level, root)) {
            if (subLevel.isRemoved()) continue;
            for (double offset : new double[]{-0.2, 0.0, 0.2}) {
                Vec3 sample = worldPoint.add(direction.scale(offset));
                Vector3d local = new Vector3d(sample.x, sample.y, sample.z);
                subLevel.logicalPose().transformPositionInverse(local);
                BlockPos blockPos = BlockPos.containing(local.x, local.y, local.z);
                if (!subLevel.getLevel().getBlockState(blockPos).isAir()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    private static Vec3 directionFromAngles(float yaw, float pitch) {
        float pitchRadians = -pitch * Mth.DEG_TO_RAD;
        float yawRadians = -yaw * Mth.DEG_TO_RAD;
        float horizontal = Mth.cos(pitchRadians);
        return new Vec3(
                Mth.sin(yawRadians) * horizontal,
                Mth.sin(pitchRadians),
                Mth.cos(yawRadians) * horizontal
        ).normalize();
    }

    private static void applyStableWorldLook(LocalPlayer player) {
        trackedRenderInterpolation = false;
        player.setYRot(stableWorldYaw);
        player.setXRot(stableWorldPitch);
        player.yRotO = stableWorldYaw;
        player.xRotO = stableWorldPitch;
    }

    private static void beginTrackedRenderInterpolation(
            LocalPlayer player,
            float fromYaw,
            float fromPitch
    ) {
        trackedRenderFromYaw = fromYaw;
        trackedRenderFromPitch = fromPitch;
        trackedRenderInterpolation = true;
        // Vanilla/Sable third-person cameras interpolate entity view angles.
        // Keep their old values for this render tick; scoped CameraMixin uses
        // the same explicit from/to pair below.
        player.yRotO = fromYaw;
        player.xRotO = fromPitch;
    }

    public static void applyWorldLook(LocalPlayer player, Vec3 look) {
        captureStableWorldLook(look);
        applyStableWorldLook(player);
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isRemoteControl() {
        return active && remoteControl;
    }

    public static boolean isHeadAimEnabled() {
        return active && headAim;
    }

    public static boolean hasAircraftEquipment() {
        return active && aircraftEquipment;
    }

    public static boolean shouldLockFirstPerson() {
        Minecraft minecraft = Minecraft.getInstance();
        return active && (headAim || scoped)
                && minecraft.options.getCameraType() == CameraType.FIRST_PERSON;
    }

    public static boolean shouldSuppressSableCameraRotation() {
        return shouldLockFirstPerson();
    }

    public static boolean isAllowedCameraType(CameraType type) {
        return type == CameraType.FIRST_PERSON || type == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED;
    }

    public static boolean isScoped() {
        return active && scoped;
    }

    public static boolean isPodView() {
        return active && podView;
    }

    public static boolean shouldHidePodHead(BlockEntity pod) {
        if (!isPodView() || pod == null || pod.getLevel() == null) return false;
        Vec3 worldCenter = Sable.HELPER.projectOutOfSubLevel(
                pod.getLevel(), Vec3.atCenterOf(pod.getBlockPos())
        );
        return worldCenter.distanceToSqr(getScopePosition(1.0F)) <= 1.0;
    }

    public static boolean isOpticalView() {
        return active && (scoped || podView);
    }

    public static double getScopeZoom() {
        updateDisplayedZoom();
        return isPodView() ? displayedPodZoom : displayedScopeZoom;
    }

    private static void updateDisplayedZoom() {
        double scopeTarget = ClientScopeConfig.getZoom();
        double podTarget = ClientScopeConfig.getPodZoom();
        long now = System.nanoTime();
        if (!Double.isFinite(displayedScopeZoom) || !Double.isFinite(displayedPodZoom)
                || lastZoomUpdateNanos == 0L) {
            displayedScopeZoom = scopeTarget;
            displayedPodZoom = podTarget;
            lastZoomUpdateNanos = now;
            return;
        }
        double elapsedSeconds = Math.min(0.1, Math.max(0.0,
                (now - lastZoomUpdateNanos) / 1_000_000_000.0));
        lastZoomUpdateNanos = now;
        // An exponential response stays equally smooth at any frame rate,
        // reaches about 90% of the next magnification in 0.23 seconds, and
        // never jumps on a single mouse-wheel event.
        double alpha = 1.0 - Math.exp(-10.0 * elapsedSeconds);
        displayedScopeZoom += (scopeTarget - displayedScopeZoom) * alpha;
        displayedPodZoom += (podTarget - displayedPodZoom) * alpha;
        if (Math.abs(scopeTarget - displayedScopeZoom) < 1.0e-4) {
            displayedScopeZoom = scopeTarget;
        }
        if (Math.abs(podTarget - displayedPodZoom) < 1.0e-4) {
            displayedPodZoom = podTarget;
        }
    }

    private static void restoreCameraAfterScope(Minecraft minecraft) {
        if (!scopeCameraCaptured) {
            return;
        }
        // Camera type and scope state are part of the projection used by the
        // inverse reticle solver. Select the saved third-person mode first;
        // callers clear `scoped` before entering this method.
        minecraft.options.setCameraType(cameraBeforeScope);
        if (cameraBeforeScope != CameraType.FIRST_PERSON && minecraft.player != null) {
            if (pendingThirdPersonScopeExitTarget != null) {
                restoreThirdPersonAimPoint(
                        minecraft,
                        minecraft.player,
                        pendingThirdPersonScopeExitTarget
                );
                pendingThirdPersonScopeExitTarget = null;
                // Changing between optic and orbit-camera origins is not
                // target movement. Reset the lock tracker after solving the
                // finite-range parallax so it cannot apply that delta again.
                if (opticalLockActive) {
                    previousOpticalLockTarget = opticalLockTarget;
                    previousOpticalCameraOrigin = opticalTrackingOrigin(minecraft);
                    opticalLockTargetKnown = true;
                }
            } else {
                restoreOffsetThirdPersonLook(minecraft, minecraft.player);
            }
        }
        minecraft.options.hideGui = hideGuiBeforeScope;
        scopeCameraCaptured = false;
    }

    private static void enterPodView(Minecraft minecraft, LocalPlayer player, Vec3 savedDirection) {
        if (player != null) {
            playerYawBeforePod = player.getYRot();
            playerPitchBeforePod = player.getXRot();
            playerPreviousYawBeforePod = player.yRotO;
            playerPreviousPitchBeforePod = player.xRotO;
            observedWorldLookBeforePod = lastObservedWorldLook;
        }
        cameraBeforePod = minecraft.options.getCameraType();
        hideGuiBeforePod = minecraft.options.hideGui;
        podCameraCaptured = true;
        Vec3 origin = getScopePosition(1.0F);
        POD_AIM.begin(savedDirection, origin);
        if (player != null && minecraft.level != null) {
            AimRay initial = clipAimRay(
                    minecraft,
                    player,
                    origin,
                    POD_AIM.direction(),
                    true
            );
            POD_AIM.acceptTarget(origin, initial.target());
        } else {
            POD_AIM.acceptTarget(
                    origin,
                    origin.add(POD_AIM.direction().scale(AIM_RAY_DISTANCE))
            );
        }
        SCOPE_CANNON_DIAGNOSTICS.clear();
        SCOPE_SUBLEVEL_DIAGNOSTICS.clear();
    }

    private static void restoreCameraAfterPod(Minecraft minecraft) {
        if (!podCameraCaptured) return;
        minecraft.options.setCameraType(cameraBeforePod);
        LocalPlayer player = minecraft.player;
        if (player != null) {
            captureStableWorldLook(observedWorldLookBeforePod);
            player.setYRot(playerYawBeforePod);
            player.setXRot(playerPitchBeforePod);
            player.yRotO = playerPreviousYawBeforePod;
            player.xRotO = playerPreviousPitchBeforePod;
        }
        minecraft.options.hideGui = hideGuiBeforePod;
        podCameraCaptured = false;
    }

    /**
     * Solves the coupled third-person orbit camera and configured reticle
     * offset against one finite world point. Camera position depends on the
     * player's raw yaw/pitch, so refresh the real Sable camera between a few
     * fixed-point iterations instead of approximating it with the old optic
     * direction. This also makes the result independent of obstacle range.
     */
    private static void restoreThirdPersonAimPoint(
            Minecraft minecraft,
            LocalPlayer player,
            Vec3 target
    ) {
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        net.minecraft.world.entity.Entity cameraEntity = minecraft.getCameraEntity() == null
                ? player
                : minecraft.getCameraEntity();
        CameraType cameraType = minecraft.options.getCameraType();
        for (int i = 0; i < 5; i++) {
            camera.setup(
                    minecraft.level,
                    cameraEntity,
                    !cameraType.isFirstPerson(),
                    cameraType.isMirrored(),
                    1.0F
            );
            Vec3 direction = target.subtract(camera.getPosition());
            if (direction.lengthSqr() < 1.0e-8) {
                break;
            }
            captureStableWorldLook(direction);
            restoreOffsetThirdPersonLook(minecraft, player);
        }
        // Keep HUD projection and the first post-scope aim packet on the final
        // solved pose instead of waiting for GameRenderer's next setup call.
        camera.setup(
                minecraft.level,
                cameraEntity,
                !cameraType.isFirstPerson(),
                cameraType.isMirrored(),
                1.0F
        );
    }

    /**
     * While scoped, stableWorldYaw/Pitch describe the actual centre ray. The
     * unlocked third-person camera applies its configured screen offset on top
     * of the player's raw look. Solve the inverse mapping before returning so
     * that the offset reticle, rather than the raw screen centre, continues to
     * point at the exact world direction seen through the sight.
     */
    private static void restoreOffsetThirdPersonLook(Minecraft minecraft, LocalPlayer player) {
        float desiredYaw = stableWorldYaw;
        float desiredPitch = stableWorldPitch;
        float rawYaw = desiredYaw;
        float rawPitch = desiredPitch;
        for (int i = 0; i < 6; i++) {
            Vec3 produced = applyViewAimOffsetToAngles(minecraft, rawYaw, rawPitch);
            float producedYaw = yawOf(produced);
            float producedPitch = pitchOf(produced);
            rawYaw += Mth.wrapDegrees(desiredYaw - producedYaw);
            rawPitch = Mth.clamp(rawPitch + desiredPitch - producedPitch, -90.0F, 90.0F);
        }
        stableWorldYaw = rawYaw;
        stableWorldPitch = rawPitch;
        lastObservedWorldLook = directionFromAngles(rawYaw, rawPitch);
        applyStableWorldLook(player);
    }

    private static Vec3 applyViewAimOffsetToAngles(
            Minecraft minecraft,
            float yaw,
            float pitch
    ) {
        Vec3 forward = directionFromAngles(yaw, pitch);
        Vec3 left = forward.cross(new Vec3(0.0, 1.0, 0.0));
        if (left.lengthSqr() < 1.0e-8) {
            left = new Vec3(-1.0, 0.0, 0.0);
        } else {
            left = left.normalize();
        }
        Vec3 up = left.cross(forward).normalize();
        double currentFov = ((GameRendererAccessor) (Object) minecraft.gameRenderer)
                .stabilizedTurret$getFov(minecraft.gameRenderer.getMainCamera(), 1.0F, true);
        double tangent = Math.tan(Math.toRadians(currentFov) * 0.5);
        double aspect = (double) Math.max(1, minecraft.getWindow().getWidth())
                / Math.max(1, minecraft.getWindow().getHeight());
        double ndcX = viewAimOffsetX * 0.02;
        double ndcY = viewAimOffsetY * 0.02;
        return forward
                .subtract(left.scale(ndcX * tangent * aspect))
                .subtract(up.scale(ndcY * tangent))
                .normalize();
    }

    private static float yawOf(Vec3 direction) {
        Vec3 normalized = direction.normalize();
        return Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-normalized.x, normalized.z)));
    }

    private static float pitchOf(Vec3 direction) {
        Vec3 normalized = direction.normalize();
        double horizontal = Math.sqrt(normalized.x * normalized.x + normalized.z * normalized.z);
        return Mth.clamp(
                Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-normalized.y, horizontal))),
                -90.0F,
                90.0F
        );
    }

    public static double getThirdPersonHeight() {
        return active ? thirdPersonHeight : 0.0;
    }

    public static double getViewAimOffsetX() {
        return active ? viewAimOffsetX : 0.0;
    }

    public static double getViewAimOffsetY() {
        return active ? viewAimOffsetY : 0.0;
    }

    public static boolean hasCannonAimPoint() {
        return active && cannonAimAvailable;
    }

    public static Vec3 getCannonAimPoint(float partialTick) {
        // The server now reports the measured cannon pose every tick. Extra
        // two-tick interpolation made the blue mark visibly trail the barrel
        // while the target mark had already moved to its new position.
        return cannonAimPoint;
    }

    public static Vec3 getScopePosition(float partialTick) {
        Vec3 anchor = scopePosition;
        UUID poseSubLevelId = cannonMountSubLevelId;
        if (scopePoseRelative && poseSubLevelId != null) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                SubLevel subLevel = SubLevelContainer.getContainer(minecraft.level).getSubLevel(poseSubLevelId);
                if (subLevel instanceof ClientSubLevel clientSubLevel && !subLevel.isRemoved()) {
                    // Keep the sight exactly on the cannon axis. Only the
                    // pose interpolation technique is borrowed from working
                    // Sable optics; no external/side-mounted camera offset.
                    return clientSubLevel.renderPose(partialTick).transformPosition(anchor);
                }
            }
        }

        // A bound device can be transferred into a nested Sable sub-level by
        // a bearing or schematic weld after the session packet was created.
        // Recover the live client sub-level from the bound block position so
        // the camera still follows renderPose instead of falling back to a
        // stepped world-space sample.
        if (scopePoseRelative && poseSubLevelId == null && cannonMountPos != null) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                SubLevel live = Sable.HELPER.getContaining(minecraft.level, cannonMountPos);
                if (live instanceof ClientSubLevel clientSubLevel && !live.isRemoved()) {
                    return clientSubLevel.renderPose(partialTick).transformPosition(anchor);
                }
            }
        }

        // Static/world fallback. Interpolation is retained only when there is
        // no physical render pose available.
        // Payload positions are consecutive server-tick samples. Interpolate
        // across the complete tick; multiplying partialTick by two reaches
        // the new sample halfway through the frame interval and then holds it,
        // which is visible as periodic stepping while the aircraft moves.
        anchor = previousScopePosition.lerp(
                scopePosition,
                Mth.clamp(partialTick, 0.0F, 1.0F)
        );
        return anchor;
    }

    public static boolean isThermal() {
        return active && (scoped || podView) && thermal;
    }

    public static boolean isOpticalLockActive() {
        return active && opticalLockActive;
    }

    public static boolean isOpticalLockSearching() {
        return active && opticalLockSearching;
    }

    public static boolean isAirToGroundLockSearching() {
        return active && podView && airToGroundLockSearching;
    }

    public static boolean isAirToGroundLockActive() {
        return active && podView && airToGroundLockActive && airToGroundLockBounds != null;
    }

    public static AABB getAirToGroundLockBounds(float partialTick) {
        if (!isAirToGroundLockActive()) return null;
        if (previousAirToGroundLockBounds == null) return airToGroundLockBounds;
        double alpha = Mth.clamp(partialTick, 0.0F, 1.0F);
        return new AABB(
                Mth.lerp(alpha, previousAirToGroundLockBounds.minX, airToGroundLockBounds.minX),
                Mth.lerp(alpha, previousAirToGroundLockBounds.minY, airToGroundLockBounds.minY),
                Mth.lerp(alpha, previousAirToGroundLockBounds.minZ, airToGroundLockBounds.minZ),
                Mth.lerp(alpha, previousAirToGroundLockBounds.maxX, airToGroundLockBounds.maxX),
                Mth.lerp(alpha, previousAirToGroundLockBounds.maxY, airToGroundLockBounds.maxY),
                Mth.lerp(alpha, previousAirToGroundLockBounds.maxZ, airToGroundLockBounds.maxZ)
        );
    }

    public static Vec3 getOpticalLockDisplayPoint(float partialTick) {
        // The green marker represents the target itself, never the player's
        // independently adjustable lead point.
        return opticalLockActive
                ? previousOpticalLockDisplayTarget.lerp(
                opticalLockTarget,
                Mth.clamp(partialTick, 0.0F, 1.0F)
        )
                : getCannonAimPoint(partialTick);
    }

    public static AABB getOpticalLockBounds(float partialTick) {
        if (!isOpticalLockActive() || opticalLockBounds == null) {
            return null;
        }
        if (previousOpticalLockBounds == null) {
            return opticalLockBounds;
        }
        double t = Mth.clamp(partialTick, 0.0F, 1.0F);
        return new AABB(
                Mth.lerp(t, previousOpticalLockBounds.minX, opticalLockBounds.minX),
                Mth.lerp(t, previousOpticalLockBounds.minY, opticalLockBounds.minY),
                Mth.lerp(t, previousOpticalLockBounds.minZ, opticalLockBounds.minZ),
                Mth.lerp(t, previousOpticalLockBounds.maxX, opticalLockBounds.maxX),
                Mth.lerp(t, previousOpticalLockBounds.maxY, opticalLockBounds.maxY),
                Mth.lerp(t, previousOpticalLockBounds.maxZ, opticalLockBounds.maxZ)
        );
    }

    /** Five smooth disappear/reappear cycles over the server's two-second grace period. */
    public static float getOpticalLockFrameAlpha() {
        if (!opticalLockActive || !opticalLockObscured
                || opticalLockObscuredSinceNanos == Long.MIN_VALUE) {
            return 1.0F;
        }
        double elapsedSeconds = Math.max(
                0.0,
                (System.nanoTime() - opticalLockObscuredSinceNanos) / 1_000_000_000.0
        );
        double wave = 0.5 + 0.5 * Math.cos(elapsedSeconds * Math.PI * 5.0);
        double eased = wave * wave * (3.0 - 2.0 * wave);
        return (float) Mth.clamp(eased, 0.0, 1.0);
    }

    public static double getLastAimDistance() {
        return lastAimDistance;
    }

    public static float getStableWorldYaw() {
        return isPodView() ? POD_AIM.yaw() : stableWorldYaw;
    }

    public static float getStableWorldYaw(float partialTick) {
        if (isPodView()) {
            POD_AIM.stabilize(getScopePosition(partialTick));
            return POD_AIM.yaw();
        }
        return trackedRenderInterpolation
                ? Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), trackedRenderFromYaw, stableWorldYaw)
                : stableWorldYaw;
    }

    public static float getStableWorldPitch() {
        return isPodView() ? POD_AIM.pitch() : stableWorldPitch;
    }

    public static float getStableWorldPitch(float partialTick) {
        if (isPodView()) {
            POD_AIM.stabilize(getScopePosition(partialTick));
            return POD_AIM.pitch();
        }
        return trackedRenderInterpolation
                ? Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), trackedRenderFromPitch, stableWorldPitch)
                : stableWorldPitch;
    }

    /**
     * Builds a zero-roll pod camera from the continuous, unwrapped gimbal
     * yaw/pitch pair. Pitch is intentionally not clamped to +/-90 degrees:
     * passing the nadir naturally turns the picture upside down. Only lateral
     * cant is removed; no "keep upright" correction is applied.
     */
    public static Quaternionf getPodCameraRotation(float partialTick) {
        return POD_AIM.cameraRotation(getScopePosition(partialTick));
    }

    /** Called from CameraMixin after Sable render-pose interpolation. */
    public static void recordPodRenderedCameraOrigin(Vec3 origin) {
        if (!podView || !isFinite(origin)) return;
        POD_AIM.recordRenderedOrigin(origin);
    }

    public static boolean shouldHideScopedEntity(net.minecraft.world.entity.Entity entity) {
        if (!isScoped()) {
            return false;
        }
        if (cannonEntityId >= 0 && entity.getId() == cannonEntityId) {
            return true;
        }
        if (cannonEntityUuid != null && cannonEntityUuid.equals(entity.getUUID())) {
            return true;
        }

        // Entity ids can differ across Sable's nested client/server levels,
        // and that leaves Flywheel's CBC contraption visual visible even
        // though the server sent the correct mounted cannon. Identify CBC's
        // actual assembled-cannon entity by its class hierarchy as a local
        // fallback, then restrict the match to the cannon beside this sight.
        // All CBC cannon add-ons based on the standard mount inherit this
        // entity, including CBC Neo Warfare's mounted cannon contraptions.
        if (isCbcMountedCannon(entity)) {
            CannonControllerRef controller = getCannonControllerRef(entity);
            boolean controllerMatches = matchesBoundCannonController(controller);
            boolean positionMatches = isNearScopePosition(entity);
            if (SCOPE_CANNON_DIAGNOSTICS.add(entity.getUUID())) {
                StabilizedTurret.LOGGER.info(
                        "Scope cannon candidate: entityClass={}, contraptionClass={}, entityId={}, entityUuid={}, "
                                + "controller={}, boundMount={}@{}, controllerMatch={}, positionMatch={}",
                        entity.getClass().getName(),
                        getContraptionClassName(entity),
                        entity.getId(),
                        entity.getUUID(),
                        controller,
                        cannonMountPos,
                        cannonMountSubLevelId,
                        controllerMatches,
                        positionMatches
                );
            }
            if (controllerMatches || positionMatches) {
                return true;
            }
        }

        // Fallback for Create/Flywheel paths whose client-side contraption id
        // is not yet synchronized when the first scope packet arrives.
        SubLevel containing = Sable.HELPER.getContaining(entity);
        if (containing == null) {
            return false;
        }
        UUID containingId = containing.getUniqueId();
        return (cannonSubLevelId != null && cannonSubLevelId.equals(containingId))
                || (pitchSubLevelId != null && pitchSubLevelId.equals(containingId));
    }

    private static boolean isCbcMountedCannon(net.minecraft.world.entity.Entity entity) {
        if (hasClassInHierarchy(entity.getClass(), CBC_CANNON_ENTITY_CLASS)) {
            return true;
        }
        Object contraption = invokePublicNoArg(entity, "getContraption");
        return contraption != null
                && hasClassInHierarchy(contraption.getClass(), CBC_CANNON_CONTRAPTION_CLASS);
    }

    private static boolean hasClassInHierarchy(Class<?> type, String expectedName) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            if (expectedName.equals(cursor.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String getContraptionClassName(net.minecraft.world.entity.Entity entity) {
        Object contraption = invokePublicNoArg(entity, "getContraption");
        return contraption == null ? "-" : contraption.getClass().getName();
    }

    private static boolean matchesBoundCannonController(CannonControllerRef controller) {
        if (controller == null || cannonMountPos == null || !cannonMountPos.equals(controller.pos())) {
            return false;
        }
        return cannonMountSubLevelId == null
                || controller.subLevelId() == null
                || cannonMountSubLevelId.equals(controller.subLevelId());
    }

    private static CannonControllerRef getCannonControllerRef(net.minecraft.world.entity.Entity entity) {
        Object controller = invokePublicNoArg(entity, "getController");
        if (controller instanceof BlockEntity blockEntity) {
            SubLevel containing = Sable.HELPER.getContaining(blockEntity);
            UUID subLevelId = containing == null ? null : containing.getUniqueId();
            return new CannonControllerRef(blockEntity.getBlockPos(), subLevelId);
        }

        // CBC can receive the contraption entity before its controller block
        // entity is present on the client. The synchronized controllerPos
        // field still identifies the exact mount in that short window.
        Field field = findField(entity.getClass(), "controllerPos");
        if (field != null) {
            try {
                if (field.trySetAccessible() && field.get(entity) instanceof BlockPos pos) {
                    SubLevel containing = Sable.HELPER.getContaining(entity);
                    UUID subLevelId = containing == null ? null : containing.getUniqueId();
                    return new CannonControllerRef(pos, subLevelId);
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
                // The position-based fallback below remains available.
            }
        }
        return null;
    }

    private static Object invokePublicNoArg(Object owner, String methodName) {
        try {
            Method method = owner.getClass().getMethod(methodName);
            return method.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                return cursor.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // Continue through the CBC entity hierarchy.
            }
        }
        return null;
    }

    private static boolean isNearScopePosition(net.minecraft.world.entity.Entity entity) {
        try {
            Vec3 sightPosition = getScopePosition(1.0F);
            Vec3 localCenter = entity.getBoundingBox().getCenter();
            if (localCenter.distanceToSqr(sightPosition) <= SCOPED_CANNON_FALLBACK_DISTANCE_SQR) {
                return true;
            }
            Vec3 worldCenter = Sable.HELPER.projectOutOfSubLevel(
                    entity.level(),
                    localCenter
            );
            if (worldCenter.distanceToSqr(sightPosition) <= SCOPED_CANNON_FALLBACK_DISTANCE_SQR) {
                return true;
            }
            SubLevel subLevel = Sable.HELPER.getContaining(entity);
            if (subLevel != null) {
                Vector3d localScope = new Vector3d(sightPosition.x, sightPosition.y, sightPosition.z);
                subLevel.logicalPose().transformPositionInverse(localScope);
                double dx = localCenter.x - localScope.x;
                double dy = localCenter.y - localScope.y;
                double dz = localCenter.z - localScope.z;
                return dx * dx + dy * dy + dz * dz <= SCOPED_CANNON_FALLBACK_DISTANCE_SQR;
            }
            return false;
        } catch (RuntimeException | LinkageError ignored) {
            // If another compatibility layer is still constructing the
            // entity, retain the exact id/UUID/sub-level checks above.
            return false;
        }
    }

    private record CannonControllerRef(BlockPos pos, UUID subLevelId) {
    }

    public static boolean shouldHideScopedSubLevel(SubLevel subLevel) {
        if (!isScoped() || subLevel == null) {
            return false;
        }
        UUID id = subLevel.getUniqueId();
        boolean idMatch = (cannonSubLevelId != null && cannonSubLevelId.equals(id))
                || (pitchSubLevelId != null && pitchSubLevelId.equals(id));

        // Do not hide the mount/turret parent. CBC Neo Warfare folds the gun
        // into the child assembled by the bound pitch bearing, so that child
        // UUID is the authoritative barrel structure to suppress.
        boolean hide = idMatch;
        if (hide && id != null && SCOPE_SUBLEVEL_DIAGNOSTICS.add(id)) {
            StabilizedTurret.LOGGER.info(
                    "Scope hiding cannon/pitch physical sublevel {} (cannonId={}, pitchId={})",
                    id,
                    cannonSubLevelId,
                    pitchSubLevelId
            );
        }
        return hide;
    }

    private static void updateThermalHighlights(Minecraft minecraft, LocalPlayer player) {
        if (!isThermal() || minecraft.level == null) {
            restoreThermalHighlights();
            return;
        }
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == player || entity.isRemoved() || shouldHideScopedEntity(entity)) {
                continue;
            }
            THERMAL_GLOW_STATES.computeIfAbsent(entity, current -> {
                boolean previous = current.hasGlowingTag();
                current.setGlowingTag(true);
                return previous;
            });
        }
        THERMAL_GLOW_STATES.entrySet().removeIf(entry -> entry.getKey().isRemoved());
    }

    private static void restoreThermalHighlights() {
        THERMAL_GLOW_STATES.forEach((entity, previous) -> {
            if (!entity.isRemoved()) {
                entity.setGlowingTag(previous);
            }
        });
        THERMAL_GLOW_STATES.clear();
    }

    private static void setThermal(boolean requested) {
        if (thermal == requested) {
            return;
        }
        thermal = requested;
        if (requested) {
            ThermalPostProcessor.beginSession();
        } else {
            restoreThermalHighlights();
            ThermalPostProcessor.shutdown();
        }
    }

    private ClientAimState() {
    }
}
