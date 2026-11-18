package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.TargetLockTelemetry;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.compat.VoxyLodRaycaster;
import com.hooya.stabilizedturret.client.compat.VoxyLodView;
import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import com.hooya.stabilizedturret.content.controller.ElectroOpticalPodCompat;
import com.hooya.stabilizedturret.content.controller.CannonSightDirection;
import com.hooya.stabilizedturret.content.controller.OpticalLockAimGeometry;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketPodCompat;
import com.hooya.stabilizedturret.content.controller.ScopePreset;
import com.hooya.stabilizedturret.mixin.client.GameRendererAccessor;
import com.hooya.stabilizedturret.mixin.client.RenderChunkRegionAccessor;
import com.hooya.stabilizedturret.network.AimDirectionPayload;
import com.hooya.stabilizedturret.network.AimGroupSessionPayload;
import com.hooya.stabilizedturret.network.AimSessionPayload;
import com.hooya.stabilizedturret.network.RemoteInputStatePayload.RangeSample;
import org.jetbrains.annotations.Nullable;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ClientAimState {
    private static final double AIM_RAY_DISTANCE = AimRayLimits.LOADED_DISTANCE;
    /**
     * A missed cannon ray represents a direction, not a finite impact point.
     * Projecting its 4096-block endpoint leaves visible camera/muzzle
     * parallax, so place only the display point effectively at infinity.
     */
    private static final double CANNON_DIRECTION_MARKER_DISTANCE = 1_000_000.0;
    private static final PodAimController POD_AIM = new PodAimController();
    private static final PodRenderVisibility POD_RENDER_VISIBILITY = new PodRenderVisibility();
    private static boolean active;
    private static boolean remoteControl;
    private static boolean seatedAtBoundController;
    private static boolean headAim;
    private static boolean freeLookHeld;
    private static boolean aircraftEquipment;
    private static boolean scoped;
    private static ScopePreset scopePreset = ScopePreset.DEFAULT;
    private static boolean podView;
    private static boolean thermal;
    private static final RadarLockCameraTracker RADAR_CAMERA_TRACKER = new RadarLockCameraTracker();
    private static final LockCameraAcquisitionMemory OPTICAL_CAMERA_ACQUISITIONS = new LockCameraAcquisitionMemory();
    private static int opticalTrackingView = Integer.MIN_VALUE;
    private static long lastLockTrackingFrameNanos;
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
    private static TargetLockTelemetry opticalLockTelemetry = TargetLockTelemetry.UNAVAILABLE;
    private static TargetLockTelemetry airToGroundLockTelemetry = TargetLockTelemetry.UNAVAILABLE;
    private static boolean opticalLockTargetKnown;
    private static boolean airToGroundLockSearching;
    private static boolean airToGroundLockActive;
    private static AABB airToGroundLockBounds;
    private static AABB previousAirToGroundLockBounds;
    private static boolean rocketImpactAvailable;
    private static Vec3 rocketImpactPoint = Vec3.ZERO;
    private static Vec3 previousRocketImpactPoint = Vec3.ZERO;
    private static boolean wasLockingFirstPerson;
    private static boolean scopeCameraCaptured;
    private static CameraType cameraBeforeScope = CameraType.FIRST_PERSON;
    private static boolean hideGuiBeforeScope;
    private static double thirdPersonHeight;
    private static double viewAimOffsetX;
    private static double viewAimOffsetY;
    private static boolean cannonAimAvailable;
    private static boolean groupAimOwnsMarker;
    private static boolean directionalGroupMarkers;
    private static Vec3 cannonAimDirection = Vec3.ZERO;
    private static Vec3 groupPrimaryAimPoint;
    private static List<Integer> cannonAimGroupIndices = List.of();
    private static List<Boolean> cannonAimGroupBallistics = List.of();
    private static Vec3 cannonAimPoint = Vec3.ZERO;
    private static Vec3 previousCannonAimPoint = Vec3.ZERO;
    private static List<Vec3> cannonAimGroupPoints = List.of();
    private static List<Vec3> previousCannonAimGroupPoints = List.of();
    private static List<AimGroupSessionPayload.GroupAim> renderAimGroups = List.of();
    private static long lastFiniteMarkerDiagnosticTick = Long.MIN_VALUE;
    private record ThirdPersonCameraFrame(Level level, UUID playerId, CameraType cameraType,
                                          UUID bodyId, Vec3 origin, Vec3 localOrigin,
                                          Vec3 worldOffset, float partialTick, long tick) {}
    private static ThirdPersonCameraFrame thirdPersonAimCamera;
    private static final Map<UUID,List<AimGroupSessionPayload.BodyMotion>> renderMotionHistory=new java.util.LinkedHashMap<>();
    private record MarkerProfile(double speed,boolean ballistic,double gravity,double drag,boolean quadratic,int ticks) {}
    private record MarkerFace(CannonMarkerSurface surface, UUID bodyId) {
        CannonMarkerSurface rendered(Level level, float partialTick) {
            if (bodyId == null) return surface;
            SubLevel body = SubLevelContainer.getContainer(level).getSubLevel(bodyId);
            if (!(body instanceof ClientSubLevel client) || body.isRemoved()) return null;
            var pose = client.renderPose(partialTick);
            Vector3d normal = pose.transformNormal(new Vector3d(surface.normal().x, surface.normal().y, surface.normal().z));
            return new CannonMarkerSurface(pose.transformPosition(surface.point()),
                    new Vec3(normal.x, normal.y, normal.z).normalize());
        }
    }
    private record MarkerContact(long tick,MarkerProfile profile,Vec3 point,MarkerFace face,
                                 VoxyLodRaycaster.RayHit lodHit) {}
    private static final Map<SightSource,MarkerContact> renderMarkerContacts=new java.util.LinkedHashMap<>();
    // Local geometry at plot coordinates can jitter by a few floating-point bits.
    // Keep display history per physical cannon rather than per exact vector sample.
    private record SightSource(UUID bodyId,long x,long y,long z) {
        static SightSource of(AimGroupSessionPayload.MuzzleFrame frame) {
            Vec3 origin=frame.localOrigin();
            return new SightSource(frame.bodyId(),Math.round(origin.x*16),
                    Math.round(origin.y*16),Math.round(origin.z*16));
        }
    }
    private static final Map<SightSource,CannonSightDirection.History> renderSightTracking=new java.util.LinkedHashMap<>();
    private static long markerSweepCount,markerSweepNanos,markerSweepMaxNanos;
    private static net.minecraft.world.level.Level ignoredStructureLevel;
    private static long ignoredStructureTick=Long.MIN_VALUE;
    private static UUID ignoredStructurePlayer;
    private static Set<UUID> ignoredStructureIds=Set.of();
    private static Set<SubLevel> ignoredStructureCache=Set.of();
    private static BlockPos cannonMountPos;
    private static UUID cannonMountSubLevelId;
    private static Set<UUID> cameraIgnoredSubLevelIds = Set.of();
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
    /** Matches the proven mouse-flight camera response from thread 019feae4. */
    private static final double THIRD_PERSON_CAMERA_FOLLOW_RESPONSE_PER_SECOND = 8.0;
    private static float thirdPersonCameraYaw;
    private static float thirdPersonCameraPitch;
    private static long lastThirdPersonCameraFollowNanos;
    private static boolean thirdPersonCameraFollowActive;
    private static boolean thirdPersonCameraFollowInitialized;
    private static boolean restoringThirdPersonScopeExit;
    private static final Quaternionf freeLookCurrentRotation = new Quaternionf();
    private static final Quaternionf freeLookTargetRotation = new Quaternionf();
    private static final Quaternionf lastBaseCameraRotation = new Quaternionf();
    private static boolean freeLookCameraActive;
    private static boolean freeLookStartPending;
    private static boolean lastBaseCameraRotationValid;
    private static long lastFreeLookCameraNanos;
    private static float freeLookCameraYaw;
    private static float freeLookCameraPitch;
    private static float freeLookCameraRoll;
    private static Vec3 freeLookFrozenAimDirection;
    private static double lastAimDistance;
    private static double displayedScopeZoom = Double.NaN;
    private static double displayedPodZoom = Double.NaN;
    private static long lastZoomUpdateNanos;

    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        resetConnectionView();
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetConnectionView();
    }

    private static void resetConnectionView() {
        resetConnectionView(true);
    }

    private static void resetConnectionView(boolean resetAcquisitions) {
        ClientScopeOrientation.clear();
        ScopeAmmoHud.clear();
        VirtualScopeVisibility.clear();
        ClientBombSight.clear();
        ClientCannonLockLead.clear();
        if (resetAcquisitions) {
            RADAR_CAMERA_TRACKER.reset();
            OPTICAL_CAMERA_ACQUISITIONS.clear();
        } else RADAR_CAMERA_TRACKER.suspend();
        opticalTrackingView = Integer.MIN_VALUE;
        lastLockTrackingFrameNanos = 0;
        Minecraft minecraft = Minecraft.getInstance();
        podView = false;
        updatePodRenderVisibility(minecraft);
        restoreCameraAfterPod(minecraft);
        // Clear scope FOV before restoring an offset third-person camera.
        scoped = false;
        scopePreset = ScopePreset.DEFAULT;
        restoreCameraAfterScope(minecraft);
        active = false;
        remoteControl = false;
        seatedAtBoundController = false;
        headAim = false;
        freeLookHeld = false;
        resetFreeLookCamera();
        lastBaseCameraRotationValid = false;
        aircraftEquipment = false;
        cannonMountPos = null;
        cannonMountSubLevelId = null;
        cameraIgnoredSubLevelIds = Set.of();
        cannonAimAvailable = false;
        groupAimOwnsMarker = false;
        directionalGroupMarkers = false;
        cannonAimDirection = Vec3.ZERO;
        groupPrimaryAimPoint = null;
        cannonAimGroupIndices = List.of();
        cannonAimGroupBallistics = List.of();
        cannonAimGroupPoints = List.of();
        previousCannonAimGroupPoints = List.of();
        renderAimGroups = List.of();
        lastFiniteMarkerDiagnosticTick = Long.MIN_VALUE;
        thirdPersonAimCamera=null;
        renderMotionHistory.clear();
        renderMarkerContacts.clear();
        renderSightTracking.clear();
        markerSweepCount=markerSweepNanos=markerSweepMaxNanos=0;
        ignoredStructureLevel=null;
        ignoredStructureTick=Long.MIN_VALUE;
        ignoredStructurePlayer=null;
        ignoredStructureIds=Set.of();
        ignoredStructureCache=Set.of();
        ClientSableMotionHistory.clear();
        scopePoseRelative = false;
        pendingThirdPersonScopeExitTarget = null;
        opticalLockActive = false;
        opticalLockTelemetry = TargetLockTelemetry.UNAVAILABLE;
        airToGroundLockTelemetry = TargetLockTelemetry.UNAVAILABLE;
        opticalLockSearching = false;
        opticalLockObscured = false;
        opticalLockObscuredSinceNanos = Long.MIN_VALUE;
        opticalLockBounds = null;
        airToGroundLockSearching = false;
        airToGroundLockActive = false;
        airToGroundLockBounds = null;
        previousAirToGroundLockBounds = null;
        rocketImpactAvailable = false;
        rocketImpactPoint = Vec3.ZERO;
        previousRocketImpactPoint = Vec3.ZERO;
        opticalLockTargetKnown = false;
        wasLockingFirstPerson = false;
        resetThirdPersonCameraFollow();
        POD_AIM.end();
        setThermal(false);
    }

    public static void accept(AimSessionPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (!payload.active()) {
            resetConnectionView(false);
            return;
        }

        if ((!active || (!headAim && payload.headAim())) && player != null) {
            // Before first-person suppression becomes active, Sable returns
            // the fully transformed world look vector here.
            captureStableWorldLook(player.getLookAngle());
        }
        active = true;
        remoteControl = payload.remoteControl();
        seatedAtBoundController = payload.seatedAtBoundController();
        headAim = payload.headAim();
        boolean nextFreeLookHeld = payload.freeLookHeld();
        if (!freeLookHeld && nextFreeLookHeld) {
            freeLookStartPending = true;
            captureFreeLookAimDirection(minecraft, player);
        } else if (freeLookHeld && !nextFreeLookHeld) {
            freeLookFrozenAimDirection = null;
        }
        freeLookHeld = nextFreeLookHeld;
        aircraftEquipment = payload.aircraftEquipment();
        scopePreset = ScopePreset.fromNetworkId(payload.scopePreset());
        if (!payload.scoped() || payload.podView()) {
            ClientScopeOrientation.clear();
            ScopeAmmoHud.clear();
        }
        previousScopePosition = scopePosition;
        scopePosition = new Vec3(payload.scopeX(), payload.scopeY(), payload.scopeZ());
        scopePoseRelative = payload.scopePoseRelative();
        cannonMountPos = payload.cannonMountPos();
        cannonMountSubLevelId = payload.cannonMountSubLevelId();
        cameraIgnoredSubLevelIds = Set.copyOf(payload.cameraIgnoredSubLevelIds());
        VirtualScopeVisibility.update(payload.scoped() && !payload.podView(),
                payload.virtualScopeHiddenSubLevels(), payload.virtualScopeHiddenEntities());
        boolean virtualScope = payload.scoped() && !payload.podView()
                && (!payload.virtualScopeHiddenSubLevels().isEmpty() || !payload.virtualScopeHiddenEntities().isEmpty());
        if (!groupAimOwnsMarker) {
            boolean hadCannonAimPoint = cannonAimAvailable;
            cannonAimAvailable = payload.cannonAimAvailable();
            Vec3 rawCannonAimPoint = new Vec3(payload.cannonAimX(), payload.cannonAimY(), payload.cannonAimZ());
            Vec3 cannonOrigin = new Vec3(payload.cannonOriginX(), payload.cannonOriginY(), payload.cannonOriginZ());
            cannonAimDirection = rawCannonAimPoint.subtract(cannonOrigin).normalize();
            Vec3 nextCannonAimPoint = rawCannonAimPoint;
            if (virtualScope && cannonAimAvailable) {
                nextCannonAimPoint = directionalScopeMarker(cannonAimDirection, 1.0F);
            } else if (cannonAimAvailable && player != null) {
                nextCannonAimPoint = clipCannonRay(
                        minecraft, player, cannonOrigin, rawCannonAimPoint);
            }
            previousCannonAimPoint = hadCannonAimPoint && cannonAimAvailable
                    ? cannonAimPoint : nextCannonAimPoint;
            cannonAimPoint = nextCannonAimPoint;
        }
        boolean wasOpticalLockActive = opticalLockActive;
        previousOpticalLockDisplayTarget = opticalLockTarget;
        previousOpticalLockBounds = opticalLockBounds;
        boolean wasOpticalLockObscured = opticalLockObscured;
        opticalLockActive = payload.opticalLockActive();
        opticalLockTelemetry = payload.opticalLockTelemetry();
        airToGroundLockTelemetry = payload.airToGroundLockTelemetry();
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
        Vec3 nextRocketImpact = new Vec3(
                payload.rocketImpactX(), payload.rocketImpactY(), payload.rocketImpactZ());
        boolean nextRocketImpactAvailable = payload.rocketImpactAvailable();
        if (!nextRocketImpactAvailable && payload.rocketTrajectoryAvailable()
                && minecraft.level != null) {
            Vec3 lodImpact = VoxyLodRaycaster.clipBallistic(
                    minecraft.level,
                    new Vec3(payload.rocketOriginX(), payload.rocketOriginY(),
                            payload.rocketOriginZ()),
                    new Vec3(payload.rocketVelocityX(), payload.rocketVelocityY(),
                            payload.rocketVelocityZ()),
                    MianbaoRocketPodCompat.LIFETIME_TICKS,
                    0,
                    payload.rocketFirstDrag(),
                    payload.rocketFlightDrop(),
                    payload.rocketFirstGravity()
            );
            if (lodImpact != null) {
                nextRocketImpact = lodImpact;
                nextRocketImpactAvailable = true;
            }
        }
        previousRocketImpactPoint = rocketImpactAvailable
                ? rocketImpactPoint : nextRocketImpact;
        rocketImpactAvailable = nextRocketImpactAvailable;
        rocketImpactPoint = rocketImpactAvailable ? nextRocketImpact : Vec3.ZERO;
        if (!rocketImpactAvailable) previousRocketImpactPoint = Vec3.ZERO;
        if (opticalLockActive && !wasOpticalLockActive && player != null) {
            previousOpticalLockTarget = opticalLockTarget;
            previousOpticalLockDisplayTarget = opticalLockTarget;
            previousOpticalLockBounds = opticalLockBounds;
            previousOpticalCameraOrigin = opticalTrackingOrigin(minecraft);
            opticalLockTargetKnown = false;
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
        updatePodRenderVisibility(minecraft);
        setThermal(payload.thermal());
    }

    private static void updatePodRenderVisibility(Minecraft minecraft) {
        POD_RENDER_VISIBILITY.update(isPodView() ? cannonMountPos : null, cannonMountSubLevelId,
                pod -> refreshPodRenderData(minecraft, pod.pos(), pod.subLevelId()));
    }

    private static void refreshPodRenderData(Minecraft minecraft, BlockPos podPos, UUID subLevelId) {
        if (minecraft.level == null || podPos == null) return;
        if (subLevelId == null) {
            // World-mounted pods use vanilla/Sodium sections rather than
            // Sable render data. Include edge neighbors just like a block update.
            minecraft.levelRenderer.setBlocksDirty(
                    podPos.getX() - 1, podPos.getY() - 1, podPos.getZ() - 1,
                    podPos.getX() + 1, podPos.getY() + 1, podPos.getZ() + 1);
            return;
        }
        SubLevel subLevel = SubLevelContainer.getContainer(minecraft.level).getSubLevel(subLevelId);
        if (!(subLevel instanceof ClientSubLevel clientSubLevel) || subLevel.isRemoved()) return;
        SectionPos section = SectionPos.of(podPos);
        clientSubLevel.getRenderData().setDirty(section.x(), section.y(), section.z(), true);
        clientSubLevel.getRenderData().compileSections(
                PrioritizeChunkUpdates.NEARBY,
                new RenderRegionCache(),
                minecraft.gameRenderer.getMainCamera()
        );
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ClientWeaponViewState.isActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (!active || player == null || minecraft.level == null) {
            RADAR_CAMERA_TRACKER.suspend();
            return;
        }

        trackedRenderInterpolation = false;
        if (scoped || podView) {
            // Individual HUD layers are filtered by ScopeHudRenderer. Keep
            // vanilla GUI rendering enabled so the requested hotbar can pass.
            minecraft.options.hideGui = false;
        }

        boolean lockFirstPerson = shouldLockFirstPerson();
        boolean followThirdPerson = shouldUseHeadAimThirdPersonFollow();
        if (followThirdPerson && !thirdPersonCameraFollowActive) {
            initializeThirdPersonCameraFollow(
                    minecraft,
                    player.getYRot(),
                    player.getXRot()
            );
        } else if (!followThirdPerson && thirdPersonCameraFollowActive) {
            resetThirdPersonCameraFollow();
        }
        thirdPersonCameraFollowActive = followThirdPerson;
        if (lockFirstPerson && !wasLockingFirstPerson) {
            captureStableWorldLook(!headAim && isGroundRadarCameraTracking()
                    ? new Vec3(minecraft.gameRenderer.getMainCamera().getLookVector()) : lastObservedWorldLook);
        }
        if (lockFirstPerson) {
            applyStableWorldLook(player);
            lastObservedWorldLook = directionFromStableAngles();
        } else if (followThirdPerson && thirdPersonCameraFollowInitialized) {
            lastObservedWorldLook = directionFromAngles(
                    thirdPersonCameraYaw, thirdPersonCameraPitch);
        } else {
            // Sable's unlocked physical-structure view already exposes a
            // world-space vector and remains the sole owner of that mode.
            lastObservedWorldLook = player.getLookAngle().normalize();
        }
        wasLockingFirstPerson = lockFirstPerson;

        if (podView) {
            // The pod controller is the sole writer of attitude and target.
            // No session packet, model angle, or asynchronous depth result is
            // allowed to feed back into this state.
            POD_AIM.stabilize(getPodInputOrigin(minecraft));
        }

        if (!ClientTelevisionGuidanceState.isActive()
                && (headAim || podView || opticalLockActive || opticalLockSearching)) {
            AimRay ray = freeLookHeld && freeLookFrozenAimDirection != null
                    && !podView && !opticalLockActive && !opticalLockSearching
                    ? freeLookDirectionRay(minecraft, player)
                    : getScreenCenterRay(minecraft, player);
            SubLevelAimHit subLevelHit = resolveSubLevelAimHit(minecraft, player, ray);
            OpticalFrameAngles opticalFrame = opticalFrameAngles(minecraft);
            Vec3 transmittedTarget = podView && POD_AIM.hasTarget()
                    ? POD_AIM.target()
                    : ray.target;
            Vec3 lockReference = opticalLockActive ? getOpticalLockDisplayPoint(1.0F) : Vec3.ZERO;
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
                    lockReference.x,
                    lockReference.y,
                    lockReference.z,
                    movingCameraFrame(minecraft,player,ray)
            ));
        }
    }

    public static @Nullable RangeSample captureLaserRangeSample() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active || !headAim || podView || minecraft.player == null || minecraft.level == null
                || minecraft.screen != null || ClientTelevisionGuidanceState.isActive()) return null;
        AimRay ray = VoxyLodRaycaster.withDiagnostics("rangefinder", () ->
                getScreenCenterRay(minecraft, minecraft.player));
        StabilizedTurret.LOGGER.info("Laser rangefinder input snapshot: scoped={} source={} hit={} range={} origin={} direction={}",
                scoped, !ray.hit ? "miss" : ray.rawResult == null ? "lod" : "loaded",
                ray.hit, ray.origin.distanceTo(ray.target), ray.origin, ray.direction);
        return RangeSample.tryCreate(
                ray.origin(), ray.direction(), ray.target(), ray.hit());
    }

    /**
     * Last-line camera-type guard for mods that mutate Options directly
     * instead of using Minecraft's normal perspective-cycle call site.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void enforceBoundSeatCameraType(ClientTickEvent.Post event) {
        if (!isSeatedAtBoundController()) return;
        Minecraft minecraft = Minecraft.getInstance();
        CameraType current = minecraft.options.getCameraType();
        if (isOpticalView()) {
            if (current != CameraType.FIRST_PERSON) {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            }
            return;
        }
        if (isAllowedCameraType(current)) return;
        minecraft.options.setCameraType(
                current == CameraType.THIRD_PERSON_BACK
                        || current == SableCameraTypes.SUB_LEVEL_VIEW
                        ? SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED
                        : CameraType.FIRST_PERSON
        );
    }

    /**
     * Advances the independent third-person camera exactly once per rendered
     * frame. Camera.setup is not a frame callback: the main renderer and
     * compatibility renderers may invoke it more than once before presenting
     * one frame. Advancing the follower from there made those callers apply
     * several tiny horizontal corrections to the same visible frame.
     */
    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        if (ClientWeaponViewState.isActive()) return;
        advanceFreeLookCameraFrame();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        long now = System.nanoTime();
        double elapsed = lastLockTrackingFrameNanos == 0 ? 0
                : Math.clamp((now - lastLockTrackingFrameNanos) / 1_000_000_000.0, 0, .1);
        lastLockTrackingFrameNanos = now;
        ClientCannonLockLead.beginFrame();
        if (!active || player == null || minecraft.level == null || minecraft.isPaused() || minecraft.screen != null) {
            opticalLockTargetKnown = false;
            RADAR_CAMERA_TRACKER.suspend();
            return;
        }
        if (shouldUseHeadAimThirdPersonFollow() && !thirdPersonCameraFollowInitialized) {
            initializeThirdPersonCameraFollow(minecraft, player.getYRot(), player.getXRot());
        }
        if (!headAim && !scoped && isGroundRadarCameraTracking()
                && minecraft.options.getCameraType() == CameraType.FIRST_PERSON && !wasLockingFirstPerson) {
            captureStableWorldLook(new Vec3(minecraft.gameRenderer.getMainCamera().getLookVector()));
            applyStableWorldLook(player);
            wasLockingFirstPerson = true;
        }
        followOpticalLockMotion(minecraft, player);
        followGroundRadarLockMotion(minecraft, player, elapsed);
        if (!shouldUseHeadAimThirdPersonFollow()) {
            return;
        }
        updateHeadAimThirdPersonCamera(
                player,
                player.getYRot(),
                player.getXRot()
        );
    }

    /** Captures the final shared camera base, then composes our active layer. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (isFreeLookCameraOverriding()) {
            recordFreeLookBaseCameraAngles(
                    event.getYaw(), event.getPitch(), event.getRoll());
            // Only win camera ownership while free look is visibly active.
            // The base orientation was already composed from other camera
            // controllers, and control is released after the smooth return.
            event.setYaw(freeLookCameraYaw);
            event.setPitch(freeLookCameraPitch);
            event.setRoll(freeLookCameraRoll);
            return;
        }
        if (!shouldUseHeadAimThirdPersonFollow()
                || !thirdPersonCameraFollowInitialized) {
            return;
        }
        event.setYaw(thirdPersonCameraYaw);
        event.setPitch(thirdPersonCameraPitch);
        event.setRoll(0.0F);

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            alignPlayerView(player, thirdPersonCameraYaw, thirdPersonCameraPitch);
        }
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
        AimRay ray = getUnclippedAimRay(minecraft, player);
        return clipAimRay(minecraft, player, ray.origin(), ray.direction(), headAim && !podView);
    }

    /** Camera tracking needs only the sight ray; avoid a second full terrain/LOD raycast each tick. */
    private static AimRay getUnclippedAimRay(Minecraft minecraft, LocalPlayer player) {
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        boolean firstPerson = minecraft.options.getCameraType() == CameraType.FIRST_PERSON;
        ThirdPersonCameraFrame thirdPersonFrame = thirdPersonCameraFrame(minecraft,player);
        Vec3 start = firstPerson && !scoped && !podView
                ? Sable.HELPER.getEyePositionInterpolated(player, 1.0F)
                : thirdPersonFrame == null ? camera.getPosition() : thirdPersonFrame.origin();
        Vec3 direction;
        if (podView) {
            direction = POD_AIM.stabilize(start);
        } else if (firstPerson && shouldLockFirstPerson()) {
            direction = directionFromStableAngles();
        } else if (shouldUseHeadAimThirdPersonFollow()) {
            // In independent third-person head aim the stable angles belong to
            // the mouse target, not the camera that is still catching up.
            direction = directionFromStableAngles();
        } else {
            Vector3f cameraForward = camera.getLookVector();
            direction = new Vec3(cameraForward.x, cameraForward.y, cameraForward.z).normalize();
        }
        if (!firstPerson && !scoped && !shouldUseHeadAimThirdPersonFollow()) {
            direction = applyViewAimOffset(minecraft, camera, direction);
        }
        return new AimRay(start, direction, start.add(direction.scale(AIM_RAY_DISTANCE)), false, null, null);
    }

    /** Freeze the final camera and its physical anchor together, before another client tick moves the body. */
    public static void captureThirdPersonAimCamera(net.minecraft.client.Camera camera, Entity cameraEntity,
                                                   float partialTick) {
        Minecraft minecraft=Minecraft.getInstance();
        if (camera!=minecraft.gameRenderer.getMainCamera() || cameraEntity!=minecraft.player) return;
        thirdPersonAimCamera=null;
        if (!active || isOpticalView() || minecraft.level==null
                || minecraft.options.getCameraType()==CameraType.FIRST_PERSON) return;
        SubLevel body=Sable.HELPER.getTrackingOrVehicleSubLevel(cameraEntity);
        if (!(body instanceof ClientSubLevel client) || body.isRemoved()) return;
        // Sable's unlocked orbit starts at the structure COM, which can be hundreds
        // of blocks from the seat. Transport the camera through the actual rider
        // eye instead; the offset includes both COM-to-seat displacement and zoom.
        Vec3 anchor=Sable.HELPER.getEyePositionInterpolated(cameraEntity,partialTick);
        Vec3 origin=camera.getPosition();
        Vector3d local=client.renderPose(partialTick)
                .transformPositionInverse(new Vector3d(anchor.x,anchor.y,anchor.z));
        Vec3 offset=origin.subtract(anchor);
        if (!local.isFinite() || !isFinite(origin) || !isFinite(offset)) return;
        thirdPersonAimCamera=new ThirdPersonCameraFrame(minecraft.level,cameraEntity.getUUID(),
                minecraft.options.getCameraType(),body.getUniqueId(),origin,new Vec3(local.x,local.y,local.z),
                offset,partialTick,minecraft.level.getGameTime());
    }

    private static ThirdPersonCameraFrame thirdPersonCameraFrame(Minecraft minecraft,LocalPlayer player) {
        ThirdPersonCameraFrame frame=thirdPersonAimCamera;
        if (frame==null || player==null || isOpticalView() || minecraft.level!=frame.level()
                || !player.getUUID().equals(frame.playerId()) || minecraft.options.getCameraType()!=frame.cameraType())
            return null;
        long age=minecraft.level.getGameTime()-frame.tick();
        return age>=0 && age<=2 ? frame : null;
    }

    private static AimDirectionPayload.CameraFrame movingCameraFrame(Minecraft minecraft,
                                                                      LocalPlayer player,AimRay ray) {
        if (podView) return null;
        Vec3 surfaceNormal=ray.surfaceNormal==null?new Vec3(0,1,0):ray.surfaceNormal;
        SubLevel body = scoped && scopePoseRelative && cannonMountSubLevelId != null
                ? SubLevelContainer.getContainer(minecraft.level).getSubLevel(cannonMountSubLevelId)
                : Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        if (!(body instanceof ClientSubLevel client) || body.isRemoved()) return null;
        boolean firstPerson=minecraft.options.getCameraType()==CameraType.FIRST_PERSON;
        if (!firstPerson && !scoped) {
            ThirdPersonCameraFrame frame=thirdPersonCameraFrame(minecraft,player);
            if (frame!=null && body.getUniqueId().equals(frame.bodyId())
                    && frame.origin().distanceToSqr(ray.origin)<=1e-12
                    && com.hooya.stabilizedturret.network.AimCameraGeometry.validOffset(frame.worldOffset()))
                return new AimDirectionPayload.CameraFrame(frame.bodyId(),frame.localOrigin(),
                        surfaceNormal,frame.worldOffset());
        }
        Vec3 worldOffset=Vec3.ZERO;
        Vector3d local;
        if (scoped && scopePoseRelative) local=new Vector3d(scopePosition.x,scopePosition.y,scopePosition.z);
        else {
            // Client ticks can run without a newly rendered orbit frame, and free
            // look deliberately uses the rider eye. Both still need a valid frame.
            Vec3 eye=Sable.HELPER.getEyePositionInterpolated(player,1.0F);
            local=client.renderPose(1.0F).transformPositionInverse(
                    new Vector3d(eye.x,eye.y,eye.z));
            worldOffset=ray.origin.subtract(eye);
        }
        if(!local.isFinite()||!com.hooya.stabilizedturret.network.AimCameraGeometry.validOffset(worldOffset))return null;
        return new AimDirectionPayload.CameraFrame(body.getUniqueId(),new Vec3(local.x,local.y,local.z),
                surfaceNormal,worldOffset);
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
        SightRaycastResult clipped = clipWorldRayIgnoringStructures(
                minecraft, player, start, end, direction, ignored);
        HitResult result = clipped.rawResult();
        boolean hit = clipped.hit();
        Vec3 target = clipped.target();
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
        if (allowVoxyLod) {
            clipped = withVoxyLod(minecraft, start, direction,
                    new SightRaycastResult(target, hit, result));
            target = clipped.target();
            hit = clipped.hit();
            result = clipped.rawResult();
        }
        lastAimDistance = start.distanceTo(target);
        return new AimRay(start, direction, target, hit, result,
                hit ? clipped.surfaceNormal(direction) : null);
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
        Vec3 target = getOpticalLockDisplayPoint(1.0F);
        int view = lockTrackingViewMode(minecraft);
        boolean acquire = OPTICAL_CAMERA_ACQUISITIONS.begin(ClientCannonLockLead.opticalAcquisitionId());
        if (acquire) {
            aimReticleAt(minecraft, player, target);
            currentCameraOrigin = opticalTrackingOrigin(minecraft);
        }
        if (acquire || !opticalLockTargetKnown || opticalTrackingView != view) {
            opticalLockTargetKnown = true;
            opticalTrackingView = view;
            previousOpticalLockTarget = target;
            previousOpticalCameraOrigin = currentCameraOrigin;
            return;
        }
        Vec3 previousBearing = previousOpticalLockTarget.subtract(previousOpticalCameraOrigin);
        Vec3 currentBearing = target.subtract(currentCameraOrigin);
        previousOpticalLockTarget = target;
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
            stableWorldYaw += yawDelta;
            stableWorldPitch = Mth.clamp(stableWorldPitch + pitchDelta, -90.0F, 90.0F);
            lastObservedWorldLook = directionFromStableAngles();
            applyStableWorldLook(player);
        } else if (shouldUseHeadAimThirdPersonFollow()) {
            // The target direction is independent from the following camera.
            // Preserve the player's angular lead by moving that target by the
            // exact locked-target bearing delta.
            stableWorldYaw += yawDelta;
            stableWorldPitch = Mth.clamp(
                    stableWorldPitch + pitchDelta, -90.0F, 90.0F);
        } else {
            // Preserve the player's manually chosen angular lead while moving
            // the view by exactly the target/vehicle bearing delta.
            Vec3 rawLook = lastObservedWorldLook.lengthSqr() > 1.0e-8
                    ? lastObservedWorldLook
                    : player.getLookAngle();
            Vec3 reticleLook = applyViewAimOffsetToAngles(
                    minecraft,
                    yawOf(rawLook),
                    pitchOf(rawLook)
            );
            float desiredReticleYaw = yawOf(reticleLook) + yawDelta;
            stableWorldYaw += Mth.wrapDegrees(desiredReticleYaw - stableWorldYaw);
            stableWorldPitch = Mth.clamp(pitchOf(reticleLook) + pitchDelta, -90.0F, 90.0F);
            restoreOffsetThirdPersonLook(minecraft, player);
        }
    }

    private static void followGroundRadarLockMotion(Minecraft minecraft, LocalPlayer player, double frameSeconds) {
        var track = ClientCannonLockLead.radarCameraTrack();
        if (track == null || opticalLockActive || podView || ClientTelevisionGuidanceState.isActive()
                || minecraft.screen != null || player.getVehicle() == null) {
            RADAR_CAMERA_TRACKER.suspend();
            return;
        }
        // This ray already includes the configured third-person reticle offset.
        // A scope always supplies its centre ray and its own moving camera origin.
        AimRay ray = getUnclippedAimRay(minecraft, player);
        UUID acquisition = track.acquisitionId();
        if (acquisition == null) return;
        Vec3 target = ClientCannonLockLead.radarTarget();
        if (target == null) return;
        var correction = RADAR_CAMERA_TRACKER.update(acquisition, lockTrackingViewMode(minecraft),
                target, minecraft.gameRenderer.getMainCamera().getPosition(), ray.direction(), frameSeconds);
        if (isScoped() || minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            stableWorldYaw += correction.yaw();
            stableWorldPitch = Mth.clamp(stableWorldPitch + correction.pitch(), -90, 90);
            lastObservedWorldLook = directionFromStableAngles();
            applyStableWorldLook(player);
        } else if (shouldUseHeadAimThirdPersonFollow()) {
            stableWorldYaw += correction.yaw();
            stableWorldPitch = Mth.clamp(stableWorldPitch + correction.pitch(), -90, 90);
        } else {
            Vec3 reticle = ray.direction();
            stableWorldYaw = yawOf(reticle) + correction.yaw();
            stableWorldPitch = Mth.clamp(pitchOf(reticle) + correction.pitch(), -90, 90);
            restoreOffsetThirdPersonLook(minecraft, player);
        }
    }

    private static int lockTrackingViewMode(Minecraft minecraft) {
        int mode = 31 * minecraft.options.getCameraType().ordinal() + (isScoped() ? 1 : 0);
        mode = 31 * mode + Float.floatToIntBits((float) viewAimOffsetX);
        return 31 * mode + Float.floatToIntBits((float) viewAimOffsetY);
    }

    private static Vec3 opticalTrackingOrigin(Minecraft minecraft) {
        // Follow the same continuously rendered camera, rather than the tick-end eye pose.
        return minecraft.gameRenderer.getMainCamera().getPosition();
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
        } else if (shouldUseHeadAimThirdPersonFollow()) {
            // Camera follow will converge on the new target without snapping.
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
        if (VirtualScopeVisibility.isActive()) {
            return directionalScopeMarker(end.subtract(origin), 1.0F);
        }
        if (minecraft.level == null || origin.distanceToSqr(end) < 1.0e-8) {
            return end;
        }
        Vec3 direction = end.subtract(origin).normalize();
        Vec3 lockPoint = opticalLockMarker(origin, direction, 1.0F, null);
        if (lockPoint != null) end = lockPoint;
        Set<SubLevel> ignored = ownStructureClosure(minecraft, player);
        SightRaycastResult clipped = clipSightRayIgnoringStructures(
                minecraft, player, origin, end, direction, ignored);
        if (lockPoint != null) return opticalLockMarker(origin, direction, 1.0F,
                clipped.hit() ? clipped.target() : null);
        return clipped.hit()
                ? clipped.target()
                : origin.add(direction.scale(CANNON_DIRECTION_MARKER_DISTANCE));
    }

    private static @Nullable Vec3 opticalLockMarker(Vec3 origin, Vec3 direction, float partialTick,
                                                    @Nullable Vec3 worldHit) {
        if (!isOpticalLockActive() || isPodView()) return null;
        return OpticalLockAimGeometry.marker(origin, direction,
                Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(),
                getOpticalLockDisplayPoint(partialTick), worldHit);
    }

    /**
     * Clips with the rendered physical poses and excludes the authoritative
     * carrier closure before traversing any blocks in those structures.
     */
    private static SightRaycastResult clipWorldRayIgnoringStructures(
            Minecraft minecraft,
            LocalPlayer player,
            Vec3 origin,
            Vec3 end,
            Vec3 direction,
            Set<SubLevel> ignored
    ) {
        if (minecraft.level == null) {
            return new SightRaycastResult(end, false, null);
        }
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        var result = ClientAimRaycaster.clip(minecraft.level, player, origin, end, ignored, partialTick);
        return new SightRaycastResult(result.point(), result.hit(), result.rawHit());
    }

    /** Full sight rays may fall back to LOD; short ballistic sweeps must stay bounded. */
    private static SightRaycastResult clipSightRayIgnoringStructures(
            Minecraft minecraft, LocalPlayer player, Vec3 origin, Vec3 end,
            Vec3 direction, Set<SubLevel> ignored
    ) {
        return withVoxyLod(minecraft, origin, direction, clipWorldRayIgnoringStructures(
                minecraft, player, origin, end, direction, ignored));
    }

    private static SightRaycastResult withVoxyLod(
            Minecraft minecraft, Vec3 origin, Vec3 direction, SightRaycastResult clipped
    ) {
        return clipped.withLodFallback(origin, direction, () -> VoxyLodRaycaster.clipHit(
                minecraft.level, origin, direction, AimRayLimits.LOD_DISTANCE, lodView(minecraft)));
    }

    static @Nullable VoxyLodView lodView(Minecraft minecraft) {
        double subdivision = VoxyLodRaycaster.subdivisionSize();
        if (!Double.isFinite(subdivision)) return null;
        var camera = minecraft.gameRenderer.getMainCamera();
        double fov = ((GameRendererAccessor) minecraft.gameRenderer)
                .stabilizedTurret$getFov(camera, 1.0F, true);
        double halfTanY = Math.tan(Math.toRadians(Math.clamp(fov, .01, 179)) * .5);
        var target = minecraft.getMainRenderTarget();
        int width = Math.max(1, target.viewWidth), height = Math.max(1, target.viewHeight);
        return new VoxyLodView(camera.getPosition(), new Vec3(camera.getLookVector()),
                new Vec3(camera.getLeftVector()).scale(-1), new Vec3(camera.getUpVector()),
                halfTanY * width / height, halfTanY, subdivision * subdivision / ((double) width * height),
                (minecraft.options.renderDistance().get() + 2) * 16.0);
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

    private static Set<SubLevel> ownStructureClosure(Minecraft minecraft, LocalPlayer player) {
        if (minecraft.level==null) return Set.of();
        long tick=minecraft.level.getGameTime();
        if (ignoredStructureLevel!=minecraft.level || ignoredStructureTick!=tick
                || !Objects.equals(ignoredStructurePlayer,player.getUUID())
                || !ignoredStructureIds.equals(cameraIgnoredSubLevelIds)) {
            ignoredStructureLevel=minecraft.level;
            ignoredStructureTick=tick;
            ignoredStructurePlayer=player.getUUID();
            ignoredStructureIds=cameraIgnoredSubLevelIds;
            ignoredStructureCache=Set.copyOf(computeOwnStructureClosure(minecraft,player));
        }
        return ignoredStructureCache;
    }

    private static Set<SubLevel> computeOwnStructureClosure(Minecraft minecraft, LocalPlayer player) {
        Set<SubLevel> ignored = new HashSet<>();
        Set<SubLevel> seeds = new HashSet<>();
        if (minecraft.level == null) {
            return ignored;
        }
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
        // The server expands the player's physical carrier through bearing
        // dependencies and Synaxis/Photomancy blueprint weld constraints.
        // Those relationships are not all present in the client actor graph,
        // so explicitly seed every authoritative member received in the aim
        // session before applying the local dependency closure.
        SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        for (UUID id : cameraIgnoredSubLevelIds) {
            SubLevel serverMember = container.getSubLevel(id);
            if (serverMember != null && !serverMember.isRemoved()) {
                seeds.add(serverMember);
            }
        }
        return connectedStructureClosure(minecraft.level, seeds);
    }

    /**
     * Expands both outgoing and incoming actor dependencies. This also covers
     * the short synchronization window where only one side of a nested swivel
     * bearing has received its parent/child connection data.
     */
    private static Set<SubLevel> connectedStructureClosure(net.minecraft.world.level.Level level, SubLevel root) {
        return connectedStructureClosure(level, Set.of(root));
    }

    private static Set<SubLevel> connectedStructureClosure(
            net.minecraft.world.level.Level level,
            Set<SubLevel> roots
    ) {
        Set<SubLevel> ignored = new HashSet<>();
        for (SubLevel root : roots) {
            if (root != null && !root.isRemoved()) {
                ignored.addAll(SubLevelHelper.getConnectedChain(root));
            }
        }
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

    private record AimRay(Vec3 origin, Vec3 direction, Vec3 target, boolean hit,
                          HitResult rawResult, @Nullable Vec3 surfaceNormal) {
    }

    private record SubLevelAimHit(UUID id, Vec3 localAnchor) {
    }

    private record OpticalFrameAngles(double halfTanX, double halfTanY) {
    }

    private record CameraAngles(float yaw, float pitch) {
    }

    /** Called by the client-only Entity.turn mixin; returns true when consumed. */
    public static boolean turnStableFirstPerson(LocalPlayer player, double yawInput, double pitchInput) {
        if (shouldCaptureFreeLookMouse()) {
            turnFreeLook(player, yawInput, pitchInput);
            return true;
        }
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
        if (shouldUseHeadAimThirdPersonFollow()) {
            // Mouse input owns the target circle. The player/camera angles are
            // deliberately left untouched here and converge independently in
            // Camera.setup, matching the proven mouse-flight implementation.
            stableWorldPitch = Mth.clamp(
                    stableWorldPitch + (float) pitchInput * 0.15F,
                    -90.0F,
                    90.0F
            );
            stableWorldYaw += (float) yawInput * 0.15F;
            if (player.getVehicle() != null) {
                player.getVehicle().onPassengerTurned(player);
            }
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

    private static boolean shouldCaptureFreeLookMouse() {
        Minecraft minecraft = Minecraft.getInstance();
        return active && freeLookHeld && !scoped
                && minecraft.player != null;
    }

    private static void turnFreeLook(
            LocalPlayer player,
            double yawInput,
            double pitchInput
    ) {
        beginFreeLookCamera(player);
        if (Math.abs(yawInput) < 1.0e-7 && Math.abs(pitchInput) < 1.0e-7) {
            return;
        }
        float yawRadians = (float) Math.toRadians(yawInput * 0.15);
        float pitchRadians = (float) Math.toRadians(pitchInput * 0.15);
        new Quaternionf().rotationY(-yawRadians)
                .mul(freeLookTargetRotation, freeLookTargetRotation);
        freeLookTargetRotation.rotateX(-pitchRadians).normalize();
    }

    private static void beginFreeLookCamera(LocalPlayer player) {
        if (freeLookCameraActive && !freeLookStartPending) {
            return;
        }
        Quaternionf initial;
        if (freeLookCameraActive) {
            initial = new Quaternionf(freeLookCurrentRotation);
        } else if (lastBaseCameraRotationValid) {
            initial = new Quaternionf(lastBaseCameraRotation);
        } else {
            initial = rotationFromAngles(player.getYRot(), player.getXRot());
        }
        freeLookCurrentRotation.set(initial);
        freeLookTargetRotation.set(initial);
        freeLookCameraActive = true;
        freeLookStartPending = false;
        lastFreeLookCameraNanos = System.nanoTime();
        captureFreeLookAimDirection(Minecraft.getInstance(), player);
        updateFreeLookCameraAngles();
    }

    /**
     * Composes free look over the final camera produced by vanilla/Sable and
     * any flight-camera controller. Returning always targets that live base,
     * so releasing the key does not fight a vehicle that is still moving.
     */
    public static boolean prepareFreeLookCamera(
            Quaternionf baseRotation,
            boolean fireControlRebuiltBase
    ) {
        // During active first-person free look the setup-start camera event
        // has already supplied the real shared base. Do not feed our own
        // override back as next frame's return target. Optical and head-aim
        // third-person paths rebuild an authoritative base later at TAIL.
        if (!freeLookCameraActive || fireControlRebuiltBase
                || !lastBaseCameraRotationValid) {
            lastBaseCameraRotation.set(baseRotation).normalize();
            lastBaseCameraRotationValid = true;
        }
        if (!active || scoped) {
            resetFreeLookCamera();
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (freeLookHeld && (!freeLookCameraActive || freeLookStartPending)) {
            if (player == null) {
                return false;
            }
            beginFreeLookCamera(player);
        }
        if (!freeLookCameraActive) {
            return false;
        }

        return true;
    }

    private static void recordFreeLookBaseCameraAngles(
            float yaw,
            float pitch,
            float roll
    ) {
        lastBaseCameraRotation.rotationYXZ(
                (float) Math.PI - yaw * Mth.DEG_TO_RAD,
                -pitch * Mth.DEG_TO_RAD,
                -roll * Mth.DEG_TO_RAD
        ).normalize();
        lastBaseCameraRotationValid = true;
    }

    private static void advanceFreeLookCameraFrame() {
        if (!active || scoped) {
            resetFreeLookCamera();
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (freeLookHeld && (!freeLookCameraActive || freeLookStartPending)) {
            if (player == null) {
                return;
            }
            beginFreeLookCamera(player);
        }
        if (!freeLookCameraActive) {
            return;
        }

        Quaternionf desired = freeLookHeld
                ? freeLookTargetRotation
                : lastBaseCameraRotation;
        long now = System.nanoTime();
        double elapsedSeconds = lastFreeLookCameraNanos == 0L
                ? 0.0
                : Math.min(0.1, Math.max(0.0,
                (now - lastFreeLookCameraNanos) / 1_000_000_000.0));
        lastFreeLookCameraNanos = now;
        float alpha = (float) (1.0 - Math.exp(
                -THIRD_PERSON_CAMERA_FOLLOW_RESPONSE_PER_SECOND * elapsedSeconds));
        freeLookCurrentRotation.slerp(desired, alpha).normalize();

        if (!freeLookHeld
                && Math.abs(freeLookCurrentRotation.dot(lastBaseCameraRotation)) > 0.99999F) {
            freeLookCurrentRotation.set(lastBaseCameraRotation);
            resetFreeLookCamera();
            return;
        }
        updateFreeLookCameraAngles();
    }

    public static Quaternionf getFreeLookCameraRotation() {
        return new Quaternionf(freeLookCurrentRotation);
    }

    public static boolean isFreeLookCameraOverriding() {
        return freeLookCameraActive && active && !scoped;
    }

    private static void resetFreeLookCamera() {
        freeLookCameraActive = false;
        freeLookStartPending = false;
        lastFreeLookCameraNanos = 0L;
        freeLookFrozenAimDirection = null;
    }

    private static void captureFreeLookAimDirection(
            Minecraft minecraft,
            LocalPlayer player
    ) {
        if (freeLookFrozenAimDirection != null || player == null || scoped || podView) {
            return;
        }
        AimRay current = getScreenCenterRay(minecraft, player);
        freeLookFrozenAimDirection = current.direction.lengthSqr() > 1.0e-8
                ? current.direction.normalize()
                : directionFromStableAngles();
    }

    private static AimRay freeLookDirectionRay(
            Minecraft minecraft,
            LocalPlayer player
    ) {
        Vec3 origin = Sable.HELPER.getEyePositionInterpolated(player, 1.0F);
        Vec3 direction = freeLookFrozenAimDirection == null
                ? directionFromStableAngles()
                : freeLookFrozenAimDirection;
        direction = direction.lengthSqr() > 1.0e-8
                ? direction.normalize()
                : new Vec3(0.0, 0.0, -1.0);
        // Free look is an inertial direction, never a persistent block hit.
        // The moving eye origin keeps packet validation local to the vehicle;
        // the server consumes only the normalized direction while held.
        Vec3 target = origin.add(direction.scale(AIM_RAY_DISTANCE));
        return new AimRay(origin, direction, target, false, null, null);
    }

    private static Quaternionf rotationFromAngles(float yaw, float pitch) {
        return new Quaternionf().rotationYXZ(
                (float) Math.PI - yaw * Mth.DEG_TO_RAD,
                -pitch * Mth.DEG_TO_RAD,
                0.0F
        );
    }

    private static void updateFreeLookCameraAngles() {
        Vector3f forward = new Vector3f(0.0F, 0.0F, -1.0F)
                .rotate(freeLookCurrentRotation);
        Vector3f actualUp = new Vector3f(0.0F, 1.0F, 0.0F)
                .rotate(freeLookCurrentRotation);
        double horizontal = Math.sqrt(forward.x * forward.x + forward.z * forward.z);
        freeLookCameraYaw = Mth.wrapDegrees((float) Math.toDegrees(
                Math.atan2(-forward.x, forward.z)));
        freeLookCameraPitch = Mth.clamp((float) Math.toDegrees(
                Math.atan2(-forward.y, horizontal)), -90.0F, 90.0F);
        Vector3f referenceUp = new Vector3f(0.0F, 1.0F, 0.0F)
                .rotate(rotationFromAngles(freeLookCameraYaw, freeLookCameraPitch));
        float cosine = Mth.clamp(referenceUp.dot(actualUp), -1.0F, 1.0F);
        // Camera.setRotation applies the event roll with a negative Z angle.
        float sine = forward.dot(new Vector3f(referenceUp).cross(actualUp));
        freeLookCameraRoll = (float) Math.toDegrees(Math.atan2(sine, cosine));
    }

    /**
     * True only for the unscoped third-person head-aim view. First person and
     * both optical views retain their existing direct, world-stable control.
     */
    public static boolean shouldUseHeadAimThirdPersonFollow() {
        Minecraft minecraft = Minecraft.getInstance();
        return !restoringThirdPersonScopeExit
                && isSeatedAtBoundController() && (headAim || isGroundRadarCameraTracking()) && !scoped && !podView
                && minecraft.player != null
                && minecraft.options.getCameraType() != CameraType.FIRST_PERSON;
    }

    /**
     * Advances the independent third-person camera from the once-per-frame
     * render callback. The exponential response is frame-rate independent and
     * intentionally matches the referenced mouse-flight view.
     */
    public static void updateHeadAimThirdPersonCamera(
            LocalPlayer player,
            float currentCameraYaw,
            float currentCameraPitch
    ) {
        if (!shouldUseHeadAimThirdPersonFollow() || player == null) {
            resetThirdPersonCameraFollow();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!thirdPersonCameraFollowInitialized) {
            initializeThirdPersonCameraFollow(
                    minecraft, currentCameraYaw, currentCameraPitch);
        }

        CameraAngles desired = solveCameraAnglesForReticle(
                minecraft, stableWorldYaw, stableWorldPitch);
        long now = System.nanoTime();
        double elapsedSeconds = lastThirdPersonCameraFollowNanos == 0L
                ? 0.0
                : Math.min(0.1, Math.max(0.0,
                (now - lastThirdPersonCameraFollowNanos) / 1_000_000_000.0));
        lastThirdPersonCameraFollowNanos = now;
        double alpha = ClientVisualConfig.cameraFollowAlpha(elapsedSeconds);
        thirdPersonCameraYaw += Mth.wrapDegrees(
                desired.yaw - thirdPersonCameraYaw) * (float) alpha;
        thirdPersonCameraPitch = Mth.clamp(
                (float) (thirdPersonCameraPitch
                        + (desired.pitch - thirdPersonCameraPitch) * alpha),
                -90.0F,
                90.0F
        );

        alignPlayerView(player, thirdPersonCameraYaw, thirdPersonCameraPitch);
        lastObservedWorldLook = directionFromAngles(
                thirdPersonCameraYaw, thirdPersonCameraPitch);
        thirdPersonCameraFollowActive = true;
    }

    /**
     * Camera.setup may also be called outside the normal render-frame path.
     * Such a caller may initialize the stored view, but must never advance the
     * follower or feed a transformed camera result back into it.
     */
    public static void ensureHeadAimThirdPersonCamera(
            float currentCameraYaw,
            float currentCameraPitch
    ) {
        if (!shouldUseHeadAimThirdPersonFollow()
                || thirdPersonCameraFollowInitialized) {
            return;
        }
        initializeThirdPersonCameraFollow(
                Minecraft.getInstance(), currentCameraYaw, currentCameraPitch);
    }

    public static float getHeadAimThirdPersonCameraYaw() {
        return thirdPersonCameraYaw;
    }

    public static float getHeadAimThirdPersonCameraPitch() {
        return thirdPersonCameraPitch;
    }

    public static Vec3 getHeadAimTargetDirection() {
        return directionFromStableAngles();
    }

    private static void initializeThirdPersonCameraFollow(
            Minecraft minecraft,
            float cameraYaw,
            float cameraPitch
    ) {
        if (!headAim && isGroundRadarCameraTracking()) {
            // An AA-only vehicle has no cannon head-aim state to seed from.
            // Enter from the visible world camera, never stale/local player angles.
            Vec3 visibleLook = new Vec3(minecraft.gameRenderer.getMainCamera().getLookVector());
            cameraYaw = yawOf(visibleLook);
            cameraPitch = pitchOf(visibleLook);
        }
        thirdPersonCameraYaw = cameraYaw;
        thirdPersonCameraPitch = Mth.clamp(cameraPitch, -90.0F, 90.0F);
        // Preserve the configured reticle baseline on entry. The camera stays
        // where it is while the mouse target begins at the old fixed marker.
        Vec3 initialTarget = applyViewAimOffsetToAngles(
                minecraft, thirdPersonCameraYaw, thirdPersonCameraPitch);
        captureStableWorldLook(initialTarget);
        lastObservedWorldLook = directionFromAngles(
                thirdPersonCameraYaw, thirdPersonCameraPitch);
        lastThirdPersonCameraFollowNanos = System.nanoTime();
        thirdPersonCameraFollowInitialized = true;
        thirdPersonCameraFollowActive = true;
    }

    private static void resetThirdPersonCameraFollow() {
        thirdPersonCameraFollowActive = false;
        thirdPersonCameraFollowInitialized = false;
        lastThirdPersonCameraFollowNanos = 0L;
    }

    private static void alignPlayerView(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.yBodyRot = yaw;
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.yHeadRotO = yaw;
        player.yBodyRotO = yaw;
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

    public static boolean isSeatedAtBoundController() {
        return active && seatedAtBoundController;
    }

    public static Set<UUID> getCameraIgnoredSubLevelIds() {
        return cameraIgnoredSubLevelIds;
    }

    public static boolean isHeadAimEnabled() {
        return active && headAim;
    }

    public static boolean hasAircraftEquipment() {
        return active && aircraftEquipment;
    }

    public static boolean hasRocketImpactPoint() {
        return active && seatedAtBoundController && rocketImpactAvailable;
    }

    public static Vec3 getRocketImpactPoint(float partialTick) {
        if (!hasRocketImpactPoint()) return Vec3.ZERO;
        return previousRocketImpactPoint.lerp(
                rocketImpactPoint, Mth.clamp(partialTick, 0.0F, 1.0F));
    }

    private static boolean isGroundRadarCameraTracking() {
        return !podView && !opticalLockActive && !ClientTelevisionGuidanceState.isActive()
                && ClientCannonLockLead.radarCameraTrack() != null;
    }

    public static boolean shouldLockFirstPerson() {
        Minecraft minecraft = Minecraft.getInstance();
        return active && (headAim || scoped || isGroundRadarCameraTracking())
                && minecraft.options.getCameraType() == CameraType.FIRST_PERSON;
    }

    public static boolean shouldSuppressSableCameraRotation() {
        return shouldLockFirstPerson()
                || MianbaoRemoteWeaponStationClientCompat.isControlActive();
    }

    public static boolean isAllowedCameraType(CameraType type) {
        return type == CameraType.FIRST_PERSON || type == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED;
    }

    public static boolean isScoped() {
        return active && scoped;
    }

    public static ScopePreset getScopePreset() {
        return scopePreset;
    }

    public static boolean isPodView() {
        return active && podView;
    }

    public static boolean shouldHidePodHead(BlockEntity pod) {
        return pod != null
                && pod.getLevel() != null
                && ElectroOpticalPodCompat.isPod(pod.getBlockState())
                && isActivePodBlock(pod.getLevel(), pod.getBlockPos());
    }

    /** Hides the complete pod renderer while viewing through that same pod. */
    public static boolean shouldHidePod(BlockEntity pod) {
        return shouldHidePodHead(pod);
    }

    /** Exact section hook for fixed pod blocks without a block entity renderer. */
    public static boolean shouldHidePod(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        if (POD_RENDER_VISIBILITY.hiddenPod() == null || !ElectroOpticalPodCompat.isPod(state)) return false;
        Level world = level instanceof Level direct
                ? direct
                : level instanceof RenderChunkRegionAccessor region
                ? region.stabilizedTurret$getLevel() : null;
        return world != null && isActivePodBlock(world, pos);
    }

    /** Matches the exact bound camera block, including identical local positions in other plots. */
    private static boolean isActivePodBlock(Level level, BlockPos pos) {
        PodRenderVisibility.Pod hidden = POD_RENDER_VISIBILITY.hiddenPod();
        if (level == null || level != Minecraft.getInstance().level || pos == null
                || hidden == null || !hidden.pos().equals(pos)) return false;
        SubLevel containing = Sable.HELPER.getContaining(level, pos);
        UUID containingId = containing == null || containing.isRemoved()
                ? null : containing.getUniqueId();
        return hidden.matches(pos, containingId);
    }

    public static boolean isOpticalView() {
        return !ClientWeaponViewState.isActive() && active && (scoped || podView);
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
        // A live config edit can narrow the range while the view is already zoomed in.
        // Keep the rendered FOV and aiming sensitivity within the new limits immediately.
        displayedScopeZoom = Mth.clamp(displayedScopeZoom, ClientScopeConfig.getMinZoom(), ClientScopeConfig.getMaxZoom());
        displayedPodZoom = Mth.clamp(displayedPodZoom, ClientScopeConfig.getPodMinZoom(), ClientScopeConfig.getPodMaxZoom());
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
        boolean initializeFollowingCamera = shouldUseHeadAimThirdPersonFollow();
        // Camera.setup reaches CameraMixin at TAIL. Suppress the following
        // writer during this synchronous inverse solve, otherwise it treats
        // an intermediate raw camera angle as a fresh mouse-target angle and
        // applies the vertical reticle offset a second time after scope exit.
        restoringThirdPersonScopeExit = initializeFollowingCamera;
        try {
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
            // Keep HUD projection and the first post-scope aim packet on the
            // final solved pose instead of waiting for GameRenderer's next
            // setup call.
            camera.setup(
                    minecraft.level,
                    cameraEntity,
                    !cameraType.isFirstPerson(),
                    cameraType.isMirrored(),
                    1.0F
            );
        } finally {
            restoringThirdPersonScopeExit = false;
        }

        if (initializeFollowingCamera) {
            // Legacy third-person control stores the solved raw camera pose in
            // stableWorldYaw/Pitch. Following mode instead needs that pose and
            // the white reticle's world direction as two separate states.
            Vec3 targetDirection = target.subtract(camera.getPosition());
            if (targetDirection.lengthSqr() > 1.0e-8) {
                captureStableWorldLook(targetDirection);
            }
            CameraAngles solvedCamera = solveCameraAnglesForReticle(
                    minecraft, stableWorldYaw, stableWorldPitch);
            thirdPersonCameraYaw = solvedCamera.yaw;
            thirdPersonCameraPitch = solvedCamera.pitch;
            alignPlayerView(player, thirdPersonCameraYaw, thirdPersonCameraPitch);
            lastObservedWorldLook = directionFromAngles(
                    thirdPersonCameraYaw, thirdPersonCameraPitch);
            lastThirdPersonCameraFollowNanos = System.nanoTime();
            thirdPersonCameraFollowInitialized = true;
            thirdPersonCameraFollowActive = true;
        }
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

    /** Inverse of applyViewAimOffsetToAngles for the following camera. */
    private static CameraAngles solveCameraAnglesForReticle(
            Minecraft minecraft,
            float targetYaw,
            float targetPitch
    ) {
        float cameraYaw = targetYaw;
        float cameraPitch = targetPitch;
        for (int i = 0; i < 6; i++) {
            Vec3 produced = applyViewAimOffsetToAngles(
                    minecraft, cameraYaw, cameraPitch);
            cameraYaw += Mth.wrapDegrees(targetYaw - yawOf(produced));
            cameraPitch = Mth.clamp(
                    cameraPitch + targetPitch - pitchOf(produced),
                    -90.0F,
                    90.0F
            );
        }
        return new CameraAngles(cameraYaw, cameraPitch);
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

    public static boolean hasAimGroups() { return !renderAimGroups.isEmpty(); }

    public static Vec3 getCannonAimPoint(float partialTick) {
        if (!renderAimGroups.isEmpty()) {
            Vec3 live=renderCannonPoint(renderAimGroups.getFirst(),partialTick);
            if (live!=null) return live;
        }
        if (VirtualScopeVisibility.isActive()) return directionalScopeMarker(cannonAimDirection, partialTick);
        // Network samples still arrive once per game tick, but the displayed
        // impact point is evaluated on every render frame. Do not classify a
        // large world-space delta as a teleport: at long range even a tiny
        // barrel rotation moves the endpoint many blocks and would otherwise
        // make the marker fall back to visibly stepping at the packet rate.
        return previousCannonAimPoint.lerp(
                cannonAimPoint,
                Mth.clamp(partialTick, 0.0F, 1.0F)
        );
    }

    public static List<Vec3> getCannonAimGroupPoints(float partialTick) {
        if (!renderAimGroups.isEmpty()) {
            List<Vec3> points=new ArrayList<>();
            for (int index=0;index<renderAimGroups.size();index++) {
                var group=renderAimGroups.get(index);
                Vec3 point=renderCannonPoint(group,partialTick);
                if (point==null && !hasRenderMuzzle(group)) {
                    int stored=cannonAimGroupIndices.indexOf(index);
                    if (stored>=0) point=previousCannonAimGroupPoints.get(stored)
                            .lerp(cannonAimGroupPoints.get(stored),Mth.clamp(partialTick,0,1));
                }
                if (point!=null) points.add(point);
            }
            return List.copyOf(points);
        }
        if (previousCannonAimGroupPoints.size() != cannonAimGroupPoints.size()) {
            return cannonAimGroupPoints;
        }
        float alpha = Mth.clamp(partialTick, 0.0F, 1.0F);
        List<Vec3> interpolated = new ArrayList<>(cannonAimGroupPoints.size());
        for (int index = 0; index < cannonAimGroupPoints.size(); index++) {
            Vec3 previous = previousCannonAimGroupPoints.get(index);
            Vec3 current = cannonAimGroupPoints.get(index);
            interpolated.add(previous.lerp(current, alpha));
        }
        return List.copyOf(interpolated);
    }

    /** A camera-relative far point projects as a direction, without muzzle parallax or terrain queries. */
    private static Vec3 directionalScopeMarker(Vec3 direction, float partialTick) {
        Vec3 ray = direction.lengthSqr() > 1.0e-12 ? direction.normalize() : directionFromStableAngles();
        return getScopePosition(partialTick).add(ray.scale(CANNON_DIRECTION_MARKER_DISTANCE));
    }

    public static void acceptAimGroups(AimGroupSessionPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        renderAimGroups=payload.groups();
        Set<SightSource> sightSources=new HashSet<>();
        for (var group : payload.groups()) {
            if (group.frame()==null || group.sightAim()==null) continue;
            SightSource source=SightSource.of(group.frame());
            sightSources.add(source);
            var sight=group.sightAim();
            var history=renderSightTracking.computeIfAbsent(source,key->new CannonSightDirection.History());
            history.add(sight.interpolationTick(),sight.launchGoal(),sight.geometricGoal());
        }
        renderSightTracking.keySet().retainAll(sightSources);
        for (var group : payload.groups()) {
            var frame=group.frame();
            if (frame==null || frame.motion()==null) continue;
            List<AimGroupSessionPayload.BodyMotion> history=new ArrayList<>(renderMotionHistory.getOrDefault(frame.bodyId(),List.of()));
            if (history.isEmpty() || !history.getLast().equals(frame.motion())) history.add(frame.motion());
            if (history.size()>24) history.removeFirst();
            renderMotionHistory.put(frame.bodyId(),List.copyOf(history));
        }
        while (renderMotionHistory.size()>128) renderMotionHistory.remove(renderMotionHistory.keySet().iterator().next());
        if (payload.groups().isEmpty()) {
            groupAimOwnsMarker = false;
            directionalGroupMarkers = false;
            renderMotionHistory.clear();
            renderMarkerContacts.clear();
            renderSightTracking.clear();
            cannonAimAvailable = false;
            cannonAimGroupPoints = List.of();
            previousCannonAimGroupPoints = List.of();
            cannonAimGroupIndices = List.of();
            cannonAimGroupBallistics = List.of();
            groupPrimaryAimPoint = null;
            return;
        }
        // Session and group packets describe the same tick; only one may own interpolation.
        groupAimOwnsMarker = true;
        boolean virtualScope = VirtualScopeVisibility.isActive();
        List<Vec3> points = new ArrayList<>(payload.groups().size());
        List<Integer> indices = new ArrayList<>(payload.groups().size());
        List<Boolean> ballistics = new ArrayList<>(payload.groups().size());
        Vec3 primary = null;
        Set<SubLevel> ignored = player == null || virtualScope ? Set.of() : ownStructureClosure(minecraft, player);
        for (int index = 0; index < payload.groups().size(); index++) {
            AimGroupSessionPayload.GroupAim group = payload.groups().get(index);
            Vec3 origin = new Vec3(group.originX(), group.originY(), group.originZ());
            Vec3 direction = new Vec3(group.directionX(), group.directionY(), group.directionZ());
            if (direction.lengthSqr() < 1.0e-8) {
                continue;
            }
            direction = direction.normalize();
            Vec3 point;
            // Achieved correction is sampled separately on the physical pose clock.
            // Pod trajectories keep their existing impact policy.
            AimGroupSessionPayload.BallisticAim ballistic = group.ballistic();
            if (virtualScope) {
                point = directionalScopeMarker(direction, 1.0F);
            } else if (hasRenderMuzzle(group)) {
                // The render path owns this muzzle. Do not repeat the full
                // collision sweep when its network sample arrives as well.
                point=ballistic==null ? origin.add(direction.scale(CANNON_DIRECTION_MARKER_DISTANCE))
                        : ballistic.impactAvailable() ? new Vec3(ballistic.impactX(),ballistic.impactY(),ballistic.impactZ()) : null;
            } else if (ballistic == null) {
                Vec3 end = origin.add(direction.scale(AIM_RAY_DISTANCE));
                point = player == null ? end : clipCannonRay(minecraft, player, origin, end);
            } else if (ballistic.impactAvailable()) {
                // An authoritative trajectory hit must never be clipped along a straight barrel ray.
                point = new Vec3(ballistic.impactX(), ballistic.impactY(), ballistic.impactZ());
            } else {
                point = VoxyLodRaycaster.clipCannonBallistic(minecraft.level, origin,
                        new Vec3(ballistic.velocityX(), ballistic.velocityY(), ballistic.velocityZ()),
                        ballistic.maxTicks(), ballistic.gravity(), ballistic.drag(), ballistic.quadraticDrag(),
                        (from, to) -> {
                            Vec3 segment = to.subtract(from);
                            if (segment.lengthSqr() < 1e-12) return null;
                            SightRaycastResult clipped = clipWorldRayIgnoringStructures(
                                    minecraft, player, from, to, segment.normalize(), ignored);
                            return clipped.hit() ? clipped.target() : null;
                        });
            }
            // A ballistic miss has no finite impact marker; retain the aim input crosshair.
            if (index == 0) primary = point;
            if (point != null) {
                indices.add(index);
                ballistics.add(ballistic != null);
                points.add(point);
            }
        }
        List<Vec3> nextPoints = List.copyOf(points);
        boolean sameMarkerSpace = cannonAimGroupIndices.equals(indices)
                && cannonAimGroupBallistics.equals(ballistics)
                && directionalGroupMarkers == virtualScope;
        directionalGroupMarkers = virtualScope;
        previousCannonAimGroupPoints = sameMarkerSpace
                ? cannonAimGroupPoints
                : nextPoints;
        cannonAimGroupPoints = nextPoints;
        cannonAimGroupIndices = List.copyOf(indices);
        cannonAimGroupBallistics = List.copyOf(ballistics);
        cannonAimAvailable = primary != null;
        if (primary != null) {
            previousCannonAimPoint = sameMarkerSpace && groupPrimaryAimPoint != null ? groupPrimaryAimPoint : primary;
            cannonAimPoint = primary;
        }
        groupPrimaryAimPoint = primary;
    }

    private static Vec3 renderCannonPoint(AimGroupSessionPayload.GroupAim group,float partialTick) {
        Minecraft minecraft=Minecraft.getInstance();
        boolean virtualScope = VirtualScopeVisibility.isActive();
        var frame=group.frame();
        if (frame==null || minecraft.level==null || minecraft.player==null) return virtualScope
                ? directionalScopeMarker(new Vec3(group.directionX(), group.directionY(), group.directionZ()), partialTick) : null;
        SubLevel body=SubLevelContainer.getContainer(minecraft.level).getSubLevel(frame.bodyId());
        if (!(body instanceof ClientSubLevel client) || body.isRemoved()) return virtualScope
                ? directionalScopeMarker(new Vec3(group.directionX(), group.directionY(), group.directionZ()), partialTick) : null;
        var pose=client.renderPose(partialTick);
        var ballistic=group.ballistic();
        Vec3 carrier=ballistic==null || virtualScope ? Vec3.ZERO : ClientSableMotionHistory.velocity(frame,client,partialTick);
        if (!virtualScope && ballistic!=null && carrier==null) carrier=frame.motion()==null
                ? com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat.sampleBodyVelocity(
                minecraft.level,body,body.logicalPose().transformPosition(frame.localOrigin()))
                : CannonRenderFrame.carrierVelocity(frame,pose,renderMotionHistory.getOrDefault(frame.bodyId(),List.of(frame.motion())));
        CannonRenderFrame launch=CannonRenderFrame.from(frame,pose,carrier);
        Vec3 direction=launch.direction();
        if (ballistic==null || virtualScope) {
            SightSource sightSource=SightSource.of(frame);
            if (group.sightAim()==null) renderSightTracking.remove(sightSource);
            else {
                var history=renderSightTracking.get(sightSource);
                var container=dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer.getContainer(minecraft.level);
                double sightTick=group.sightAim().interpolationTick();
                if (container!=null && !container.getInterpolation().isStopped()) {
                    var clock=container.getInterpolation();
                    sightTick=clock.lastInterpolationTick+(clock.mostRecentInterpolationTick-clock.lastInterpolationTick)
                            * Math.clamp(partialTick,0,1);
                }
                direction=history==null ? CannonSightDirection.direction(direction,
                        group.sightAim().launchGoal(),group.sightAim().geometricGoal())
                        : CannonRenderFrame.sightDirection(group,pose,history,sightTick);
                while (renderSightTracking.size()>128) renderSightTracking.remove(renderSightTracking.keySet().iterator().next());
            }
        }
        Vec3 origin=launch.origin();
        if (virtualScope) return directionalScopeMarker(direction, partialTick);
        Set<SubLevel> ignored=ownStructureClosure(minecraft,minecraft.player);
        SightSource source=SightSource.of(frame);
        MarkerProfile profile=ballistic==null ? new MarkerProfile(frame.muzzleSpeed(),false,0,0,false,0)
                : new MarkerProfile(frame.muzzleSpeed(),true,ballistic.gravity(),ballistic.drag(),
                ballistic.quadraticDrag(),ballistic.maxTicks());
        long renderTick=minecraft.level.getGameTime();
        MarkerContact contact=renderMarkerContacts.get(source);
        // The point itself must follow the rendered bore at FPS, not the 20 Hz query clock.
        // Leaving the finite cached voxel requires an immediate new terrain query.
        Vec3 lodPoint=ballistic==null && contact!=null && contact.lodHit()!=null
                ? contact.lodHit().ray(origin,direction,AimRayLimits.LOD_DISTANCE) : null;
        if (contact==null || contact.tick()!=renderTick || !contact.profile().equals(profile)
                || (ballistic==null && contact.lodHit()!=null && lodPoint==null)) {
            long sweepStarted=System.nanoTime();
            MarkerFace[] surface={null};
            VoxyLodRaycaster.RayHit lodHit=null;
            Vec3 sampled;
            if (ballistic==null) {
                SightRaycastResult hit=clipSightRayIgnoringStructures(minecraft,minecraft.player,origin,
                        origin.add(direction.scale(AIM_RAY_DISTANCE)),direction,ignored);
                sampled=hit.hit()?hit.target():null;
                surface[0]=markerSurface(minecraft,hit);
                lodHit=hit.lodHit();
            } else sampled=VoxyLodRaycaster.clipCannonBallistic(minecraft.level,origin,
                    launch.velocity(),ballistic.maxTicks(),ballistic.gravity(),ballistic.drag(),ballistic.quadraticDrag(),
                    (from,to)->{
                        Vec3 segment=to.subtract(from);
                        if (segment.lengthSqr()<1e-12) return null;
                        SightRaycastResult hit=clipWorldRayIgnoringStructures(minecraft,minecraft.player,
                                from,to,segment.normalize(),ignored);
                        if (hit.hit()) surface[0]=markerSurface(minecraft,hit);
                        return hit.hit()?hit.target():null;
                    });
            CannonMarkerSurface queriedSurface = surface[0] == null ? null : surface[0].rendered(minecraft.level, partialTick);
            if (queriedSurface!=null && (sampled==null || queriedSurface.point().distanceToSqr(sampled)>1e-8)) surface[0]=null;
            contact=new MarkerContact(renderTick,profile,sampled,surface[0],lodHit);
            lodPoint=lodHit==null ? null : lodHit.ray(origin,direction,AimRayLimits.LOD_DISTANCE);
            long elapsed=System.nanoTime()-sweepStarted;
            markerSweepCount++;
            markerSweepNanos+=elapsed;
            markerSweepMaxNanos=Math.max(markerSweepMaxNanos,elapsed);
            renderMarkerContacts.put(source,contact);
            while (renderMarkerContacts.size()>128) renderMarkerContacts.remove(renderMarkerContacts.keySet().iterator().next());
        }
        Vec3 impact=ballistic==null && contact.lodHit()!=null ? lodPoint : contact.point();
        CannonMarkerSurface currentSurface = contact.face() == null ? null : contact.face().rendered(minecraft.level, partialTick);
        if (currentSurface!=null) {
            Vec3 current=ballistic==null ? currentSurface.ray(origin,direction,AIM_RAY_DISTANCE)
                    : currentSurface.trajectory(launch,ballistic.gravity(),ballistic.drag(),ballistic.quadraticDrag(),
                    ballistic.maxTicks(),minecraft.level.getMinBuildHeight()-64.0);
            // Continue the exact rendered trajectory across a flat face only
            // while the current contact is still a real loaded collision face.
            // Periodic scene queries refresh cached data; leaving a LOD voxel also re-queries immediately.
            Vec3 collisionPoint = current;
            if (current != null && contact.face().bodyId() != null) {
                SubLevel surfaceBody = SubLevelContainer.getContainer(minecraft.level).getSubLevel(contact.face().bodyId());
                collisionPoint = surfaceBody instanceof ClientSubLevel surfaceClient && !surfaceBody.isRemoved()
                        ? surfaceClient.renderPose(partialTick).transformPositionInverse(current) : null;
            }
            boolean valid=collisionPoint!=null && validMarkerSurface(minecraft,collisionPoint,contact.face().surface().normal());
            if (ballistic==null) impact=valid ? current : null;
            else if (valid) impact=current;
        }
        if (ballistic==null) {
            Vec3 lockPoint = opticalLockMarker(origin, direction, partialTick, impact);
            if (lockPoint != null) return lockPoint;
        }
        if (ballistic==null && impact==null) return origin.add(direction.scale(CANNON_DIRECTION_MARKER_DISTANCE));
        if (impact!=null && headAim && !podView && !opticalLockActive
                && !renderAimGroups.isEmpty() && group==renderAimGroups.getFirst()) {
            long now=minecraft.level.getGameTime();
            if (lastFiniteMarkerDiagnosticTick==Long.MIN_VALUE || now<lastFiniteMarkerDiagnosticTick
                    || now-lastFiniteMarkerDiagnosticTick>=200) {
                var camera=minecraft.gameRenderer.getMainCamera();
                Vec3 sightOrigin=camera.getPosition();
                Vec3 sightDirection=scoped || shouldLockFirstPerson() || shouldUseHeadAimThirdPersonFollow()
                        ? directionFromStableAngles() : new Vec3(camera.getLookVector()).normalize();
                if (!scoped && minecraft.options.getCameraType()!=CameraType.FIRST_PERSON
                        && !shouldUseHeadAimThirdPersonFollow())
                    sightDirection=applyViewAimOffset(minecraft,camera,sightDirection);
                Vec3 measured=impact.subtract(sightOrigin).normalize();
                double error=Math.toDegrees(Math.acos(Math.clamp(measured.dot(sightDirection),-1,1)));
                StabilizedTurret.LOGGER.info("Finite marker diagnostics: markerMode={} errorDeg={} scoped={} sightBody={} muzzleBody={} sight={} marker={} inheritedVelocity={}",
                        ballistic==null ? "sight-direction" : "ballistic-impact",error,scoped,cannonMountSubLevelId,frame.bodyId(),sightOrigin,impact,carrier);
                if (ballistic==null && group.sightAim()!=null) {
                    var clock=dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer.getContainer(minecraft.level).getInterpolation();
                    double displayedTick=clock.lastInterpolationTick
                            +(clock.mostRecentInterpolationTick-clock.lastInterpolationTick)*Math.clamp(partialTick,0,1);
                    StabilizedTurret.LOGGER.info("Cannon sight correction: mode=applied-angle sampleTick={} renderTick={} muzzleSpeed={} localMuzzle={} bore={} sightDirection={}",
                            group.sightAim().interpolationTick(),displayedTick,frame.muzzleSpeed(),frame.localOrigin(),
                            launch.direction(),direction);
                }
                ThirdPersonCameraFrame cameraFrame=thirdPersonCameraFrame(minecraft,minecraft.player);
                if (cameraFrame!=null) {
                    SubLevel cameraBody=SubLevelContainer.getContainer(minecraft.level).getSubLevel(cameraFrame.bodyId());
                    Vector3d projected=cameraBody instanceof ClientSubLevel cameraClient
                            ? cameraClient.renderPose(partialTick).transformPosition(new Vector3d(cameraFrame.localOrigin().x,
                            cameraFrame.localOrigin().y,cameraFrame.localOrigin().z)).add(new Vector3d(
                            cameraFrame.worldOffset().x,cameraFrame.worldOffset().y,cameraFrame.worldOffset().z)) : null;
                    StabilizedTurret.LOGGER.info("Third person aim frame: body={} capturedTick={} partial={} age={} origin={} worldOffset={} renderOriginError={}",
                            cameraFrame.bodyId(),cameraFrame.tick(),cameraFrame.partialTick(),now-cameraFrame.tick(),
                            cameraFrame.origin(),cameraFrame.worldOffset(),projected==null ? Double.NaN
                                    : Math.sqrt(sightOrigin.distanceToSqr(projected.x,projected.y,projected.z)));
                }
                StabilizedTurret.LOGGER.info("Aim HUD performance: fps={} collisionSweeps={} averageSweepMs={} maximumSweepMs={}",
                        minecraft.getFps(),markerSweepCount,markerSweepCount==0?0:markerSweepNanos/1e6/markerSweepCount,
                        markerSweepMaxNanos/1e6);
                markerSweepCount=markerSweepNanos=markerSweepMaxNanos=0;
                lastFiniteMarkerDiagnosticTick=now;
            }
        }
        return impact;
    }

    private static MarkerFace markerSurface(Minecraft minecraft,SightRaycastResult clipped) {
        if (!clipped.hit() || !(clipped.rawResult() instanceof net.minecraft.world.phys.BlockHitResult hit)) return null;
        SubLevel body = Sable.HELPER.getContaining(minecraft.level, hit.getLocation());
        var state=minecraft.level.getBlockState(hit.getBlockPos());
        if (state.getCollisionShape(minecraft.level,hit.getBlockPos()).isEmpty()) return null;
        var face=hit.getDirection();
        return new MarkerFace(new CannonMarkerSurface(body == null ? clipped.target() : hit.getLocation(),
                new Vec3(face.getStepX(),face.getStepY(),face.getStepZ())), body == null ? null : body.getUniqueId());
    }

    private static boolean validMarkerSurface(Minecraft minecraft,Vec3 point,Vec3 normal) {
        BlockPos block=BlockPos.containing(point.subtract(normal.scale(1e-4)));
        if (!minecraft.level.hasChunkAt(block)) return false;
        var state=minecraft.level.getBlockState(block);
        Vec3 local=point.subtract(block.getX(),block.getY(),block.getZ());
        if (state.isCollisionShapeFullBlock(minecraft.level,block))
            return Math.abs(local.dot(normal)-(normal.x+normal.y+normal.z>0?1:0))<1e-5;
        for (AABB box:state.getCollisionShape(minecraft.level,block).toAabbs()) {
            double face=normal.x>0?box.maxX:normal.x<0?-box.minX:
                    normal.y>0?box.maxY:normal.y<0?-box.minY:normal.z>0?box.maxZ:-box.minZ;
            if (Math.abs(local.dot(normal)-face)<1e-5
                    && local.x>=box.minX-1e-5 && local.x<=box.maxX+1e-5
                    && local.y>=box.minY-1e-5 && local.y<=box.maxY+1e-5
                    && local.z>=box.minZ-1e-5 && local.z<=box.maxZ+1e-5) return true;
        }
        return false;
    }

    private static boolean hasRenderMuzzle(AimGroupSessionPayload.GroupAim group) {
        var level=Minecraft.getInstance().level;
        if (level==null || group.frame()==null) return false;
        SubLevel body=SubLevelContainer.getContainer(level).getSubLevel(group.frame().bodyId());
        return body instanceof ClientSubLevel && !body.isRemoved();
    }

    public static Vec3 getScopePosition(float partialTick) {
        Vec3 anchor = scopePosition;
        // This id is the current sublevel of the scope reference (the
        // cannon/designator block), not necessarily the CBC cannon entity.
        // Blueprint welding can place those in different nested sublevels.
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
        return active && (scoped || podView
                || ClientTelevisionGuidanceState.isActive()) && thermal;
    }

    public static boolean isOpticalLockActive() {
        return active && opticalLockActive;
    }

    public static TargetLockTelemetry getOpticalLockTelemetry() {
        return opticalLockTelemetry;
    }

    public static TargetLockTelemetry getAirToGroundLockTelemetry() {
        return airToGroundLockTelemetry;
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
        Vec3 smooth = ClientCannonLockLead.opticalTarget();
        if (opticalLockActive && smooth != null) return smooth;
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

    private static void setThermal(boolean requested) {
        if (thermal == requested) {
            return;
        }
        thermal = requested;
        if (requested) {
            ThermalPostProcessor.beginSession();
        } else {
            ThermalPostProcessor.shutdown();
        }
    }

    private ClientAimState() {
    }
}
