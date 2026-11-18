package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.mixin.SwivelBearingBlockEntityAccessor;
import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import com.hooya.stabilizedturret.content.controller.CannonProjectileFlight;
import com.hooya.stabilizedturret.content.controller.SableMissileFuze;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.compat.simulated.SimulatedPhysicsStaffCompat;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import com.mojang.authlib.GameProfile;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Opt-in dedicated-server fixture: real assembly, mixins, persistence and controller entry points. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class BearingIntegration {
    private static final boolean ISOLATED_CARRIER_STAFF = Boolean.getBoolean("create_fire_control.isolatedCarrierStaff");
    private static final boolean FINITE_AIM_TRANSITIONS = Boolean.getBoolean("create_fire_control.finiteAimTransitions");
    private static final boolean FINITE_LIMIT_TRANSITIONS = Boolean.getBoolean("create_fire_control.finiteLimitTransitions");
    private static final boolean AIM_EDGE_CASES = Boolean.getBoolean("create_fire_control.aimEdgeCases");
    private static final StringBuilder edgeReport = new StringBuilder("age,turret,sky,pitch_deg,relative_yaw_rate,relative_pitch_rate,held_yaw_step,held_pitch_step,carrier_angle,pitch_world_error\n");
    private static final Vector3d[] edgePreviousHeld = new Vector3d[4];
    private static Quaterniond edgeCarrierBaseline;
    private static Vec3 edgePitchBaseline;
    private static double edgeSlewExcess,edgePitchPeak,edgeCarrierPeak,edgePitchHoldPeak,edgeEndpointPeak;
    private static boolean edgeSky;
    private static int edgeSurfacePhase=-1;
    private static Vec3 edgeSurfacePoint,edgeSurfaceNormal;
    private static final boolean FINITE_USER_OFFSETS = Boolean.getBoolean("create_fire_control.finiteUserOffsets");
    private static final boolean FINITE_MEDIUM_CANNON = Boolean.getBoolean("create_fire_control.finiteMediumCannon");
    private static final boolean FINITE_GROUND_TARGET = Boolean.getBoolean("create_fire_control.finiteGroundTarget");
    private static double finiteActualImpactPeak;
    private static java.util.Map<UUID, ArticulatedServoPredictor.BodyState> finitePrediction = java.util.Map.of();
    private static final java.util.Map<UUID, Vector3d> finiteAzimuthFrames = new java.util.HashMap<>();
    private static double finiteAzimuthFrameChange;
    private static final boolean FINITE_CARRIER_AIM = FINITE_LIMIT_TRANSITIONS || FINITE_AIM_TRANSITIONS || Boolean.getBoolean("create_fire_control.finiteCarrierAim");
    private static final boolean FINITE_SUPPORT_BEFORE_HINGES = Boolean.getBoolean("create_fire_control.finiteSupportBeforeHinges");
    private static final boolean FINITE_REBASE_SUPPORT = Boolean.getBoolean("create_fire_control.finiteRebaseSupport");
    private static final boolean FINITE_TRANSLATION = Boolean.getBoolean("create_fire_control.finiteTranslation");
    private static final boolean FINITE_PRODUCTION_INPUT = Boolean.getBoolean("create_fire_control.finiteProductionInput");
    private static final boolean FINITE_WELDED_CARRIER = Boolean.getBoolean("create_fire_control.finiteWeldedCarrier");
    private static final boolean FINITE_WELDED_TURRET = Boolean.getBoolean("create_fire_control.finiteWeldedTurret");
    private static final boolean FINITE_COMBINED_MOTION = Boolean.getBoolean("create_fire_control.finiteCombinedMotion");
    private static final java.util.List<ServerSubLevel> finiteWeldedBodies = new java.util.ArrayList<>();
    private static final java.util.List<PhysicsConstraintHandle> finiteWeldHandles = new java.util.ArrayList<>();
    private static ServerSubLevel finiteCameraBody;
    private static Vector3d finiteCameraLocal;
    private static Vec3 finiteMovingViewDirection;
    private static double finiteRenderedMarkerPeak,finiteStalePacketPeak;
    private static final int FINITE_START = 100, FINITE_PHASE_TICKS = 220;
    private static final String[] FINITE_PHASE_NAMES = FINITE_TRANSLATION ? new String[]{
            "53m sideways sight forward", "53m sideways sight reverse", "53m repeated forward",
            "53m reverse and pitch", "53m forward and pitch", "sky reverse", "sky forward and pitch",
            "53m reacquired reverse and pitch"} : new String[]{
            "near point +120 yaw", "far point -120 yaw", "far point +360 yaw", "near point pitch",
            "far point yaw and pitch", "sky +360 yaw", "sky pitch", "reacquired point yaw and pitch"};
    private static final double[] FINITE_YAW_SPEEDS = {120, -120, 360, 0, 120, 360, 0, -120};
    private static StabilizerControllerBlockEntity finiteController;
    private static final BindingRef[] finiteMounts = new BindingRef[2];
    private static final PitchOrientedContraptionEntity[] finiteCannons = new PitchOrientedContraptionEntity[2];
    private static Vec3 finiteNearTarget, finiteFarTarget, finiteTarget;
    private static Vec3 limitNear, limitFar, limitBelow;
    private static Vec3 limitView;
    private static double limitPeak, limitYawPeak, limitLandingPeak, limitHeldPitchPeak,limitTerrainPeak;
    private static int limitSamples;
    private static final double[] limitPreviousPitch=new double[]{Double.NaN,Double.NaN};
    private static int limitFirstReturnSamples;
    private static final StringBuilder limitDiagnostics = new StringBuilder("age,turret,marker_deg,held_pitch_deg,yaw_error_deg,operator_speed_rad_s\n");
    private static Vec3 finiteCameraOrigin;
    private static final Vec3 FINITE_SKY_DIRECTION = new Vec3(0.5, 0.15, 0.85).normalize();
    // Each phase includes its first moving physics step and the entire stopped recovery.
    private static final double[][][] finiteAimPeaks = new double[8][2][3];
    private static final double[][] finiteAimStopPeaks = new double[8][2];
    private static final double[][] finiteAimSettled = new double[8][2];
    private static final int[][] finiteAimSamples = new int[8][2];
    private static final double[] finiteCarrierTravel = new double[8];
    private static final double[] finiteYawTravel = new double[8];
    private static double finiteMotionTime;
    private static Vector3d finiteSupportAnchor, finiteSupportGoal;
    private static dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle finiteRebasedSupport;
    private static Vec3 transitionSecondTarget, transitionCameraDirection;
    private static Vec3 transitionCamera, transitionInputDirection;
    private static double transitionOperatorSpeed;
    private static boolean transitionSky;
    private static final Vector3d[] transitionHeldBaseline = new Vector3d[4], transitionPreviousHeld = new Vector3d[4];
    private static final Vec3[] transitionBarrelBaseline = new Vec3[2];
    private static double transitionMaxZeroHeldError, transitionMaxZeroBarrelError, transitionMaxCameraMarkerError;
    private static double transitionMaxSlewExcess, transitionMaxSiblingMarkerError;
    private static double transitionMaxExpiredBarrelError;
    private static final boolean[] transitionExpiryObserved = new boolean[4];
    private static int transitionMeasuredSteps;
    private static final StringBuilder transitionDiagnostics = new StringBuilder(
            "age,dt,state,turret,operator_speed_deg_s,sky,marker_error_deg,actual_from_baseline_deg,"
                    + "yaw_held_from_baseline_deg,pitch_held_from_baseline_deg,yaw_step_deg,pitch_step_deg,"
                    + "yaw_finite_active,pitch_finite_active,camera_x,camera_y,camera_z,target_x,target_y,target_z\n");
    private static final StringBuilder finiteAimDiagnostics = new StringBuilder(
            "age,dt,phase,mode,turret,marker_error_deg,horizontal_error_deg,vertical_error_deg,launch_error_deg,"
                    + "muzzle_x,muzzle_y,muzzle_z,muzzle_vx,muzzle_vy,muzzle_vz,carrier_vx,carrier_vy,carrier_vz,"
                    + "carrier_omega_x,carrier_omega_y,carrier_omega_z,target_x,target_y,target_z\n");
    private static final StringBuilder finiteAimLockDiagnostics = new StringBuilder(
            "age,dt,phase,state,axis,requested_x,requested_y,requested_z,held_x,held_y,held_z,"
                    + "operator_rate_rad_s,max_rate_deg_s,landing_remaining,finite_target_count,finite_compensation_active,"
                    + "motor_target_rad,motor_residual_rad,model_ready,eligible,planning_results\n");
    private static ServerLevel level;
    private static SwivelBearingBlockEntity a, b, pitchA, pitchB;
    private static PhysicsConstraintHandle staff;
    private static int age;
    private static boolean finished;
    private static boolean startupTurretsRegistered;
    private static int movingStartupWaits;
    private static String failure;
    private static ServerSubLevel carrier;
    private static SwivelBearingBlockEntity movingYaw, movingPitch;
    private static PhysicsConstraintHandle carrierSteering;
    private static double movingPeakError;
    private static double carrierTime;
    private static double stepDisturbancePeak;
    private static int lastSteeringAge = -1;
    private static FakePlayer staffPlayer;
    private static SwivelBearingBlockEntity staffSiblingYaw, staffSiblingPitch;
    private static Vector3d staffAnchor, staffWorldGoal;
    private static Quaterniond staffOrientation;
    private static double staffCarrierPeakSpeed, staffTurretPeakSpeed;
    private static double arrivalOvershoot;
    private static double carrierRotationPeak;
    private static double carrierStaffPeakError, carrierStaffSiblingPeakError;
    private static final double[] TURN_SPEEDS = {120, -120, 360, 30};
    private static final double[][] turnPeaks = new double[4][2];
    private static double turnTravel;
    private static double mixedSiblingPeak, mixedOperatorTravel;
    private static final StringBuilder turnDiagnostics = new StringBuilder(
            "age,dt,axis,held_error_deg,ray_error_deg,measured_deg,prediction_goal_deg,native_zero_deg,residual_deg,locked_swing_deg,parent_omega_x,parent_omega_y,parent_omega_z\n");
    private static net.minecraft.world.entity.animal.Pig markerOpticalTarget;
    private static final double[][] isolatedStaffPeaks = new double[3][2];
    private static final StringBuilder isolatedStaffReport = new StringBuilder();
    private static double isolatedStaffTravel;

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        try {
            level = event.getServer().overworld();
            if (FINITE_CARRIER_AIM) {
                BearingStabilizer.servoPredictionObserver = states -> finitePrediction = states;
                SableEventPlatform.INSTANCE.onPhysicsTick(BearingIntegration::steerFiniteCarrier);
                SableEventPlatform.INSTANCE.onPostPhysicsTick(BearingIntegration::measureFiniteCarrierAim);
                return;
            }
            if (ISOLATED_CARRIER_STAFF) {
                SableEventPlatform.INSTANCE.onPostPhysicsTick(BearingIntegration::measureIsolatedCarrierStaff);
                return;
            }
            a = assemble(new BlockPos(0, 160, 0));
            b = assemble(new BlockPos(10, 160, 0));
            level.getChunk(new BlockPos(20, 180, 0));
            markerOpticalTarget = net.minecraft.world.entity.EntityType.PIG.create(level);
            require(markerOpticalTarget != null, "optical target fixture exists");
            markerOpticalTarget.setNoGravity(true);
            markerOpticalTarget.setPos(20, 180, 1);
            level.addFreshEntity(markerOpticalTarget);
            SableEventPlatform.INSTANCE.onPhysicsTick(BearingIntegration::steerCarrier);
            SableEventPlatform.INSTANCE.onPostPhysicsTick(BearingIntegration::measureCarrierStaffHold);
        } catch (Throwable error) { fail(error); event.getServer().halt(false); }
    }

    private static void measureCarrierStaffHold(SubLevelPhysicsSystem system, double dt) {
        if (system.getLevel() == level && age >= 1120 && age < 1280 && !finished) {
            mixedSiblingPeak = Math.max(mixedSiblingPeak, worldRayError(staffSiblingPitch, -35, -5));
            mixedOperatorTravel = Math.max(mixedOperatorTravel, worldRayError(movingPitch, 30, 10));
            return;
        }
        if (system.getLevel() == level && age >= 800 && age < 1120 && !finished) {
            int phase = (age - 800) / 80;
            turnTravel += system.getPipeline().getAngularVelocity(carrier, new Vector3d()).length() * dt;
            if ((age - 800) % 80 >= 20) {
                if (phase == 2) {
                    recordTurnState("yaw1", movingYaw, movingPitch, 30, 10, dt);
                    recordTurnState("pitch1", movingPitch, movingPitch, 30, 10, dt);
                    recordTurnState("yaw2", staffSiblingYaw, staffSiblingPitch, -35, -5, dt);
                    recordTurnState("pitch2", staffSiblingPitch, staffSiblingPitch, -35, -5, dt);
                }
                turnPeaks[phase][0] = Math.max(turnPeaks[phase][0], worldRayError(movingPitch, 30, 10));
                turnPeaks[phase][1] = Math.max(turnPeaks[phase][1], worldRayError(staffSiblingPitch, -35, -5));
                if (phase == 2 && age % 20 == 0) {
                    System.out.printf("turn motor state age %d yaw %s / %s pitch %s / %s%n", age,
                            turnMotorState(movingYaw), turnMotorState(staffSiblingYaw),
                            turnMotorState(movingPitch), turnMotorState(staffSiblingPitch));
                }
            }
            return;
        }
        if (system.getLevel() != level || age < 580 || age >= 720 || finished) return;
        for (ServerSubLevel body : new ServerSubLevel[]{carrier, child(movingYaw), child(movingPitch),
                child(staffSiblingYaw), child(staffSiblingPitch)}) {
            double speed = system.getPipeline().getAngularVelocity(body, new Vector3d()).length();
            require(Double.isFinite(speed) && speed < 8, "carrier staff body remains bounded: " + speed);
        }
        double firstError = worldRayError(movingPitch, 30, 10), secondError = worldRayError(staffSiblingPitch, -35, -5);
        if (firstError > carrierStaffPeakError || secondError > carrierStaffSiblingPeakError || age % 20 == 0) {
            System.out.printf("carrier staff age %d rays %.6f/%.6f parent omega %s pitch %.6f/%.6f%n", age,
                    firstError, secondError, system.getPipeline().getAngularVelocity(carrier, new Vector3d()),
                    carrierElevation(30, 10), carrierElevation(-35, -5));
        }
        carrierStaffPeakError = Math.max(carrierStaffPeakError, firstError);
        carrierStaffSiblingPeakError = Math.max(carrierStaffSiblingPeakError, secondError);
    }

    private static void steerCarrier(SubLevelPhysicsSystem system, double dt) {
        if (system.getLevel() == level && age >= 1120 && age < 1280 && !finished) {
            Vector3d omega = system.getPipeline().getAngularVelocity(carrier, new Vector3d());
            system.getPipeline().addLinearAndAngularVelocity(carrier, new Vector3d(),
                    new Vector3d(0, Math.toRadians(120) - omega.y, 0));
            system.getPipeline().wakeUp(carrier);
            return;
        }
        if (system.getLevel() == level && age >= 800 && age < 1120 && !finished) {
            int phase = (age - 800) / 80;
            Vector3d omega = system.getPipeline().getAngularVelocity(carrier, new Vector3d());
            system.getPipeline().addLinearAndAngularVelocity(carrier, new Vector3d(),
                    new Vector3d(0, Math.toRadians(TURN_SPEEDS[phase]) - omega.y, 0));
            system.getPipeline().wakeUp(carrier);
            return;
        }
        if (system.getLevel() != level || age < 110 || age >= 380 || carrierSteering == null) return;
        carrierTime += dt;
        // Continuous motion is sampled on every physics substep. Separately test a tick-stepped
        // stiff external pose constraint, which imposes changes unavailable to the servo beforehand.
        if (age >= 300 && age < 320 && lastSteeringAge == age) return;
        lastSteeringAge = age;
        carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_X, 0.22 * Math.sin(carrierTime * 1.7), 3e7, 3e5, false, 0);
        carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_Y, 0.35 * Math.sin(carrierTime * 2), 3e7, 3e5, false, 0);
        carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_Z, 0.18 * Math.sin(carrierTime * 1.3), 3e7, 3e5, false, 0);
        system.getPipeline().wakeUp(carrier);
    }

    private static String turnMotorState(SwivelBearingBlockEntity bearing) {
        try {
            var activeField = BearingStabilizer.class.getDeclaredField("ACTIVE");
            activeField.setAccessible(true);
            Object lock = ((java.util.Map<?, ?>) activeField.get(null)).get(bearing);
            var residualField = lock.getClass().getDeclaredField("motorResidual");
            residualField.setAccessible(true);
            Object residual = residualField.get(lock);
            var correctionField = residual.getClass().getDeclaredField("correction");
            correctionField.setAccessible(true);
            return String.format(java.util.Locale.ROOT, "target %.9f correction %.9f",
                    bearing.getTargetAngleDegrees(), Math.toDegrees(correctionField.getDouble(residual)));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("read-only motor characterization failed", failure);
        }
    }

    private static void recordTurnState(String label, SwivelBearingBlockEntity bearing,
                                        SwivelBearingBlockEntity barrel, double yaw, double pitch, double dt) {
        try {
            var active = BearingStabilizer.class.getDeclaredField("ACTIVE");
            active.setAccessible(true);
            Object lock = ((java.util.Map<?, ?>) active.get(null)).get(bearing);
            var heldField = lock.getClass().getDeclaredField("heldAimDirection");
            heldField.setAccessible(true);
            Vector3d held = new Vector3d((Vector3d) heldField.get(lock));
            Vector3d expected = new Quaterniond().rotationY(Math.toRadians(yaw))
                    .rotateX(Math.toRadians(-pitch)).transform(new Vector3d(0, 0, 1));
            Vector3d actual = child(barrel).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
            double heldError = Math.toDegrees(Math.acos(Math.clamp(held.dot(expected), -1, 1)));
            double rayError = Math.toDegrees(Math.acos(Math.clamp(held.dot(actual), -1, 1)));
            var angleMethod = lock.getClass().getDeclaredMethod("canonicalJointAngleDegrees",
                    SwivelBearingBlockEntity.class, Quaterniond.class);
            angleMethod.setAccessible(true);
            Quaterniond pose = new Quaterniond(child(bearing).logicalPose().orientation());
            double measured = (double) angleMethod.invoke(lock, bearing, pose);
            var idealMethod = lock.getClass().getDeclaredMethod("orientationForJointAngle",
                    SwivelBearingBlockEntity.class, Direction.class, double.class);
            idealMethod.setAccessible(true);
            Direction plate = bearing.getLevel().getBlockState(bearing.getPlatePos()).getValue(SwivelBearingPlateBlock.FACING);
            Quaterniond ideal = (Quaterniond) idealMethod.invoke(lock, bearing, plate, measured);
            double swing = Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(ideal.dot(pose)), -1, 1)));
            var targetField = lock.getClass().getDeclaredField("motorTarget");
            targetField.setAccessible(true);
            Object target = targetField.get(lock);
            var previousField = target.getClass().getDeclaredField("previous");
            previousField.setAccessible(true);
            var zeroField = lock.getClass().getDeclaredField("nativeZeroDegrees");
            zeroField.setAccessible(true);
            var residualField = lock.getClass().getDeclaredField("motorResidual");
            residualField.setAccessible(true);
            Object residual = residualField.get(lock);
            var correctionField = residual.getClass().getDeclaredField("correction");
            correctionField.setAccessible(true);
            SubLevel containing = Sable.HELPER.getContaining(bearing);
            Vector3d omega = containing instanceof ServerSubLevel parent
                    ? SubLevelPhysicsSystem.require(level).getPipeline().getAngularVelocity(parent, new Vector3d()) : new Vector3d();
            turnDiagnostics.append(String.format(java.util.Locale.ROOT,
                    "%d,%.9f,%s,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                    age, dt, label, heldError, rayError, measured, Math.toDegrees(previousField.getDouble(target)),
                    zeroField.getDouble(lock), Math.toDegrees(correctionField.getDouble(residual)), swing,
                    omega.x, omega.y, omega.z));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("read-only turn state capture failed", failure);
        }
    }

    private static SwivelBearingBlockEntity assemble(BlockPos pos) {
        level.getChunk(pos);
        if (Sable.HELPER.getContaining(level,pos)==null)
            level.setChunkForced(pos.getX()>>4,pos.getZ()>>4,true);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"));
        require(block instanceof SwivelBearingBlock, "native bearing registry missing");
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(SwivelBearingBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(pos.above(), Blocks.STONE.defaultBlockState());
        SwivelBearingBlockEntity bearing = (SwivelBearingBlockEntity) level.getBlockEntity(pos);
        require(bearing != null, "bearing placement failed at " + pos + ", state=" + level.getBlockState(pos));
        bearing.assemble();
        require(bearing.isAssembled() && bearing.getSubLevelID() != null, "assembly failed");
        return bearing;
    }

    private static SwivelBearingBlockEntity assemblePitch(SwivelBearingBlockEntity yaw, Direction facing) {
        BlockPos pos = yaw.getPlatePos().above(2);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"));
        level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(SwivelBearingBlock.FACING, facing));
        level.setBlockAndUpdate(pos.relative(facing), Blocks.STONE.defaultBlockState());
        SwivelBearingBlockEntity bearing = (SwivelBearingBlockEntity) level.getBlockEntity(pos);
        bearing.assemble();
        require(bearing.isAssembled(), "nested pitch assembly failed");
        return bearing;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) {
        if (finished || level == null) return;
        try {
            age++;
            if (FINITE_CARRIER_AIM) {
                tickFiniteCarrierAim(event);
                return;
            }
            if (ISOLATED_CARRIER_STAFF) {
                forceLoadFixtureBodies();
                tickIsolatedCarrierStaff(event);
                return;
            }
            forceLoadFixtureBodies();
            // Dedicated-server startup can finish before asynchronously loaded child plots.
            // Keep the test clock at its initial phase until real registration completes.
            if (!startupTurretsRegistered) {
                if (child(a)==null || child(b)==null) {
                    require(age<40,"startup turret plots register within forty world ticks");
                    return;
                }
                startupTurretsRegistered=true;
                age=2;
                var container=(dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)SubLevelContainer.getContainer(level);
                for(ServerSubLevel body:new ServerSubLevel[]{child(a),child(b)})
                    container.addForceLoadTicket(body,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                            net.minecraft.util.Unit.INSTANCE);
            }
            double requestedYawA = age >= 40 && age < 70 ? 80 : 35;
            if (age <= 260 || age >= 270 && age < 300) aim(a, requestedYawA, 60);
            if (age < 330) aim(b, -55, 90);
            if (age > 2 && age < 90) {
                double overrunA = age >= 70 ? 35 - angle(a) : angle(a) - requestedYawA;
                arrivalOvershoot = Math.max(arrivalOvershoot, Math.max(overrunA, -55 - angle(b)));
                require(arrivalOvershoot < 0.05, "production head-aim arrival overshoot at age " + age
                        + ": " + arrivalOvershoot + ", A=" + angle(a) + ", B=" + angle(b));
            }
            if (age == 20) {
                pitchA = assemblePitch(a, Direction.EAST);
                pitchB = assemblePitch(b, Direction.WEST);
                BlockPos carrierBlock = new BlockPos(40, 170, 0);
                level.getChunk(carrierBlock);
                level.setBlockAndUpdate(carrierBlock, Blocks.STONE.defaultBlockState());
                var assembled = SimAssemblyHelper.assembleFromSingleBlock(level, carrierBlock.below(), carrierBlock, false, false);
                require(assembled != null, "moving carrier assembly");
                carrier = (ServerSubLevel) assembled.subLevel();
                ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) SubLevelContainer.getContainer(level))
                        .addForceLoadTicket(carrier,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                                net.minecraft.util.Unit.INSTANCE);
                movingYaw = assemble(carrierBlock.offset(assembled.offset()).above(2));
            }
            if (age == 21) {
                if (child(movingYaw)==null || child(movingYaw).isRemoved()) {
                    require(++movingStartupWaits<40,"carrier yaw registers before nested pitch assembly");
                    age--;
                    return;
                }
                ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) SubLevelContainer.getContainer(level))
                        .addForceLoadTicket(child(movingYaw),dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                                net.minecraft.util.Unit.INSTANCE);
                // Native parent registration follows the assembly tick.
                movingPitch = assemblePitch(movingYaw, Direction.EAST);
                Vector3d anchor = new Vector3d(carrier.getMassTracker().getCenterOfMass());
                Vector3d world = carrier.logicalPose().transformPosition(new Vector3d(anchor));
                carrierSteering = SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null, carrier,
                        new FreeConstraintConfiguration(new Vector3d(), anchor, new Quaterniond()));
                for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) carrierSteering.setMotor(axis, 0, 3e7, 3e5, false, 0);
                carrierSteering.setMotor(ConstraintJointAxis.LINEAR_X, world.x, 3e7, 3e5, false, 0);
                carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Y, world.y, 3e7, 3e5, false, 0);
                carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Z, world.z, 3e7, 3e5, false, 0);
            }
            if (age > 20 && age < 325) {
                aimPitch(pitchA, requestedYawA, 20);
                aimPitch(pitchB, -55, -15);
            }
            if (age > 21 && age < 380) movingCarrierHold();
            if (age == 90) checkImpactPrediction();
            if (age == 91) checkEmptySkyAim();
            if (age == 100) {
                require(BearingStabilizer.ownsJoint(a) && BearingStabilizer.ownsJoint(b), "independent owned handles");
                require(Math.abs(angle(a) - 35) < 2, "turret A convergence " + angle(a));
                require(Math.abs(angle(b) + 55) < 2, "turret B convergence " + angle(b));
                require(Math.abs(elevation(pitchA) - 20) < 2, "right pitch convergence " + elevation(pitchA));
                require(Math.abs(elevation(pitchB) + 15) < 2, "left pitch convergence " + elevation(pitchB));
                require(child(a).getUserDataTag() == null
                        || !child(a).getUserDataTag().getBoolean("StabilizedTurretOwnsNoGravity"), "manual gravity restored");
                ServerSubLevel child = child(a);
                Vector3d grab = new Vector3d(child.getMassTracker().getCenterOfMass()).add(0.8, 0, 0);
                Vector3d world = child.logicalPose().transformPosition(new Vector3d(grab)).add(0, 0, 2);
                staff = SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null, child,
                        new FreeConstraintConfiguration(new Vector3d(), grab, new Quaterniond()));
                for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) staff.setMotor(axis, 0, 1200, 120, false, 0);
                staff.setMotor(ConstraintJointAxis.LINEAR_X, world.x, 1200, 120, false, 0);
                staff.setMotor(ConstraintJointAxis.LINEAR_Y, world.y, 1200, 120, false, 0);
                staff.setMotor(ConstraintJointAxis.LINEAR_Z, world.z, 1200, 120, false, 0);
            }
            if (age >= 100 && age <= 260) {
                Vector3d velocity = SubLevelPhysicsSystem.require(level).getPipeline().getAngularVelocity(child(a), new Vector3d());
                require(Double.isFinite(velocity.length()) && velocity.length() < 30, "staff bounded " + velocity);
            }
            if (age == 160) { staff.remove(); staff = null; }
            if (age == 260) {
                require(Math.abs(angle(a) - 35) < 2, "staff release recovery " + angle(a));
                require(Math.abs(angle(b) + 55) < 2, "other turret unaffected by staff");
                require(Math.abs(elevation(pitchA) - 20) < 2, "pitch recovers with yaw parent");
                require(Math.abs(elevation(pitchB) + 15) < 2, "other turret pitch unaffected");
            }
            if (age == 266) {
                require(!BearingStabilizer.ownsJoint(a) && handle(a).isValid(), "heartbeat restores original rotary");
                require(BearingStabilizer.ownsJoint(b), "heartbeat isolated from other turret");
            }
            if (age == 295) {
                BearingStabilizer.maintainConstrainedBinding(a, true, 20, 45);
                require(!BearingStabilizer.ownsJoint(a) && handle(a).isValid(), "automatic defense native takeover");
                require(BearingStabilizer.ownsJoint(b), "automatic defense takeover isolated");
            }
            if (age == 300) {
                BearingStabilizer.disable(a);
                require(!BearingStabilizer.ownsJoint(a) && handle(a).isValid(), "explicit unbind restores native");
                b.reattachConstraint(child(b), true);
                require(BearingStabilizer.ownsJoint(b), "redstone/native reattach cannot duplicate custom hinge");
            }
            if (age == 315) require(Math.abs(elevation(pitchA) - 5) < 2,
                    "live limit edit returns outside barrel safely " + elevation(pitchA));
            if (age == 325) {
                pitchB.disassemble();
                b.disassemble();
                require(!b.isAssembled() && !BearingStabilizer.isBound(b) && handle(b) == null,
                        "disassembly cleans joint and binding");
            }
            if (age == 380) {
                require(carrierRotationPeak > Math.toRadians(10), "moving-carrier fixture actually rotates " + Math.toDegrees(carrierRotationPeak));
                require(movingPeakError < 0.4, "production two-axis moving-carrier hold " + movingPeakError);
                System.out.printf("production moving carrier: peak world-ray error %.6f degrees%n", movingPeakError);
                System.out.printf("production stepped external pose constraint: peak transient %.6f degrees%n", stepDisturbancePeak);
                // A second turret shares the same free carrier during a real staff session.
                staffSiblingYaw = assemble(movingYaw.getBlockPos().offset(-4, 0, 0));
                carrierSteering.remove();
                carrierSteering = null;
            }
            if (age==381) staffSiblingPitch=assemblePitch(staffSiblingYaw,Direction.WEST);
            if (age >= 381) {
                if (age >= 1120) commandMixedOperatorRay();
                else commandWorldRay(movingYaw, movingPitch, 30, 10);
                commandWorldRay(staffSiblingYaw, staffSiblingPitch, -35, -5);
            }
            if (age == 800) recordTurnInitialState();
            if (age == 420) startRealStaffDrag();
            if (age >= 420 && age < 500) {
                double time = (age - 420) * 0.05;
                Vector3d goal = new Vector3d(staffWorldGoal).add(1.5 * Math.sin(time), 0.4 * Math.sin(time * 0.7), 0);
                Quaterniond orientation = new Quaterniond().rotationY(0.3 * Math.sin(time))
                        .rotateZ(0.12 * Math.sin(time * 0.8)).mul(staffOrientation);
                Vector3d eye = new Vector3d(staffPlayer.getX(), staffPlayer.getEyeY(), staffPlayer.getZ());
                PhysicsStaffServerHandler.get(level).drag(staffPlayer.getUUID(), movingPitch.getSubLevelID(),
                        goal.sub(eye), staffAnchor, orientation);
                require(SimulatedPhysicsStaffCompat.draggedBodies(level).contains(movingPitch.getSubLevelID()),
                        "real staff session detected");
                if (age > 421) {
                    require(BearingStabilizer.yieldingToStaff(movingYaw) && BearingStabilizer.yieldingToStaff(movingPitch),
                            "both grabbed turret axes yield to staff");
                    require(!BearingStabilizer.yieldingToStaff(staffSiblingYaw)
                            && !BearingStabilizer.yieldingToStaff(staffSiblingPitch), "sibling turret retains stabilization");
                }
                var pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
                double carrierSpeed = pipeline.getAngularVelocity(carrier, new Vector3d()).length();
                double turretSpeed = pipeline.getAngularVelocity(child(movingPitch), new Vector3d()).length();
                staffCarrierPeakSpeed = Math.max(staffCarrierPeakSpeed, carrierSpeed);
                staffTurretPeakSpeed = Math.max(staffTurretPeakSpeed, turretSpeed);
                require(Double.isFinite(carrierSpeed) && carrierSpeed < 8.0, "real staff carrier angular speed at age " + age + ": " + carrierSpeed);
                require(Double.isFinite(turretSpeed) && turretSpeed < 8.0, "real staff turret angular speed at age " + age + ": " + turretSpeed);
            }
            if (age == 500) {
                PhysicsStaffServerHandler.get(level).stopDragging(staffPlayer.getUUID());
                require(SimulatedPhysicsStaffCompat.draggedBodies(level).isEmpty(), "release removes real staff ownership");
            }
            if (age == 502) {
                require(!BearingStabilizer.yieldingToStaff(movingYaw) && !BearingStabilizer.yieldingToStaff(movingPitch),
                        "both axes resume on staff release");
            }
            if (age == 580) {
                double releasedError = worldRayError(movingPitch, 30, 10);
                double siblingError = worldRayError(staffSiblingPitch, -35, -5);
                require(releasedError < 0.5, "grabbed turret recovers world aim on release " + releasedError);
                require(siblingError < 0.5, "same-carrier sibling keeps independent world aim " + siblingError);
                System.out.printf("production finite aim: overshoot %.6f degrees%n", arrivalOvershoot);
                System.out.printf("real staff on free two-turret carrier: peak carrier %.6f rad/s, turret %.6f rad/s%n",
                        staffCarrierPeakSpeed, staffTurretPeakSpeed);
                System.out.printf("real staff release: grabbed ray %.6f degrees, sibling ray %.6f degrees%n",
                        releasedError, siblingError);
                System.out.printf("free-carrier requested relative pitch %.6f/%.6f degrees (fixture limits +/-90)%n",
                        carrierElevation(30, 10), carrierElevation(-35, -5));
                require(BearingStabilizer.ownsJoint(movingYaw) && BearingStabilizer.ownsJoint(movingPitch),
                        "staff never removes the native hinge");
                startRealStaffDrag(carrier);
            }
            if (age >= 580 && age < 720) {
                double time = (age - 580) * 0.05;
                Vector3d goal = new Vector3d(staffWorldGoal).add(1.5 * Math.sin(time), 0.4 * Math.sin(time * 0.7), 0);
                Quaterniond orientation = new Quaterniond().rotationY(0.35 * Math.sin(time * 2.4))
                        .rotateX(0.14 * Math.sin(time * 1.7)).rotateZ(0.11 * Math.sin(time * 1.3)).mul(staffOrientation);
                Vector3d eye = new Vector3d(staffPlayer.getX(), staffPlayer.getEyeY(), staffPlayer.getZ());
                PhysicsStaffServerHandler.get(level).drag(staffPlayer.getUUID(), carrier.getUniqueId(),
                        goal.sub(eye), staffAnchor, orientation);
                if (age > 581) {
                    require(!BearingStabilizer.yieldingToStaff(movingYaw) && !BearingStabilizer.yieldingToStaff(movingPitch)
                            && !BearingStabilizer.yieldingToStaff(staffSiblingYaw) && !BearingStabilizer.yieldingToStaff(staffSiblingPitch),
                            "grabbing the carrier never suspends either turret");
                }
            }
            if (age == 720) {
                PhysicsStaffServerHandler.get(level).stopDragging(staffPlayer.getUUID());
                System.out.printf("real carrier staff with zero head speed: peak rays %.6f / %.6f degrees%n",
                        carrierStaffPeakError, carrierStaffSiblingPeakError);
                boolean accuracyPassed = carrierStaffPeakError < 0.05 && carrierStaffSiblingPeakError < 0.05;
                Files.writeString(Path.of("bearing-hold-accuracy-result.txt"), String.format(java.util.Locale.ROOT,
                        "%s: threshold < 0.05 degrees; measured %.6f / %.6f degrees",
                        accuracyPassed ? "PASS" : "FAIL", carrierStaffPeakError, carrierStaffSiblingPeakError));
                if (!Boolean.getBoolean("create_fire_control.characterizeCarrierHold")) {
                    require(accuracyPassed, "carrier staff holds both world rays without a head-speed budget");
                } else if (!accuracyPassed) {
                    System.out.println("KNOWN FAILURE: strict carrier hold accuracy; characterization mode only accepts functional regressions");
                }
                Vector3d anchor = new Vector3d(carrier.getMassTracker().getCenterOfMass());
                Vector3d world = carrier.logicalPose().transformPosition(new Vector3d(anchor));
                carrierSteering = SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null, carrier,
                        new FreeConstraintConfiguration(world, anchor, new Quaterniond()));
                carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_X, 0, 3e7, 3e5, false, 0);
                carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_Z, 0, 3e7, 3e5, false, 0);
                carrierSteering.setMotor(ConstraintJointAxis.LINEAR_X, world.x, 3e7, 3e5, false, 0);
                carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Y, world.y, 3e7, 3e5, false, 0);
                carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Z, world.z, 3e7, 3e5, false, 0);
            }
            if (age == 1120) {
                boolean turnPassed = true;
                StringBuilder report = new StringBuilder();
                require(turnTravel > Math.PI * 4, "sustained-turn fixture actually rotates with two turrets");
                for (int phase = 0; phase < TURN_SPEEDS.length; phase++) {
                    String line = String.format(java.util.Locale.ROOT,
                            "%.0f deg/s (%s): %.6f / %.6f degrees%n", TURN_SPEEDS[phase],
                            phase % 2 == 0 ? "head aim speed zero" : "stabilizer only", turnPeaks[phase][0], turnPeaks[phase][1]);
                    report.append(line);
                    System.out.print("production sustained turn " + line);
                    turnPassed &= turnPeaks[phase][0] < 0.05 && turnPeaks[phase][1] < 0.05;
                }
                Files.writeString(Path.of("bearing-turn-accuracy-result.txt"), (turnPassed ? "PASS\n" : "FAIL\n") + report);
                Files.writeString(Path.of("bearing-turn-diagnostics.csv"), turnDiagnostics);
                if (!Boolean.getBoolean("create_fire_control.characterizeCarrierHold")) {
                    require(turnPassed, "both turrets hold world rays throughout sustained carrier rotation");
                }
            }
            if (age == 1280) {
                require(mixedOperatorTravel > 10, "mixed turret operator really slews while the carrier rotates");
                require(worldRayError(movingPitch, 70, 10) < 0.05, "mixed turret operator reaches the new world ray");
                require(mixedSiblingPeak < 0.05, "other turret retains strict stabilization during operator slewing");
                Files.writeString(Path.of("bearing-mixed-turret-result.txt"), String.format(java.util.Locale.ROOT,
                        "PASS: carrier 120 deg/s, operator 30 deg/s; sibling peak %.9f degrees; operator travel %.6f degrees%n",
                        mixedSiblingPeak, mixedOperatorTravel));
                Files.writeString(Path.of("bearing-integration-result.txt"), "PASS");
                writeServoPlanningDiagnostics();
                finished = true;
                event.getServer().halt(false);
            }
        } catch (Throwable error) { fail(error); event.getServer().halt(false); }
    }

    /**
     * Actual controller/CBC convergence regression. Unlike the direction-only fixtures,
     * commands pass through setDesiredAimRay, turret grouping, loaded-round analysis,
     * muzzle-frame sampling, finite-range ballistics and the configured operator rate.
     */
    private static void tickFiniteCarrierAim(ServerTickEvent.Pre event) throws Exception {
        if (age == 20) {
            BlockPos carrierBlock = new BlockPos(40, 170, 0);
            level.getChunk(carrierBlock);
            level.setBlockAndUpdate(carrierBlock, Blocks.STONE.defaultBlockState());
            var assembled = SimAssemblyHelper.assembleFromSingleBlock(level, carrierBlock.below(), carrierBlock, false, false);
            require(assembled != null, "finite-point carrier assembly");
            carrier = (ServerSubLevel) assembled.subLevel();
            ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) SubLevelContainer.getContainer(level))
                    .addForceLoadTicket(carrier,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                            net.minecraft.util.Unit.INSTANCE);
            movingYaw = assemble(carrierBlock.offset(assembled.offset()).above(2));
            System.out.println("Finite carrier and primary yaw assembled; pitch waits for native parent registration");
        }
        if (age == 21) {
            movingPitch = assemblePitch(movingYaw, Direction.EAST);
            Vector3d anchor = new Vector3d(carrier.getMassTracker().getCenterOfMass());
            Vector3d world = carrier.logicalPose().transformPosition(new Vector3d(anchor));
            finiteSupportAnchor = new Vector3d(anchor);
            finiteSupportGoal = new Vector3d(world);
            carrierSteering = createFiniteSupportConstraint();
            // Azimuth is deliberately free: native angular velocity drives sustained turns.
            carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_X, 0, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_Z, 0, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_X, world.x, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Y, world.y, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Z, world.z, 3e7, 3e5, false, 0);
        }
        if (age == 60 && !FINITE_WELDED_TURRET) {
            staffSiblingYaw = assemble(movingYaw.getBlockPos().offset(-4, 0, 0));
            staffSiblingPitch = assemblePitch(staffSiblingYaw, Direction.WEST);
        }
        if (age == 65 && FINITE_WELDED_CARRIER) {
            for (int i = 0; i < 5; i++) {
                BlockPos p = new BlockPos(FINITE_WELDED_TURRET && i == 0 ? 36 : 48 + i * 3, 169, 0);
                level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
                level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                var result = SimAssemblyHelper.assembleFromSingleBlock(level, p.below(), p, false, false);
                require(result != null, "fixed-weld carrier member assembly");
                ServerSubLevel member = (ServerSubLevel) result.subLevel();
                require(member != carrier && !finiteWeldedBodies.contains(member), "distinct fixed-weld carrier member");
                ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) SubLevelContainer.getContainer(level))
                        .addForceLoadTicket(member,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                                net.minecraft.util.Unit.INSTANCE);
                finiteWeldedBodies.add(member);
                if (i==0 && FINITE_WELDED_TURRET) {
                    var centre=member.getMassTracker().getCenterOfMass();
                    staffSiblingYaw=assemble(BlockPos.containing(centre.x(),centre.y(),centre.z()).above(3));
                    require(Sable.HELPER.getContaining(staffSiblingYaw)==member,
                            "second turret is installed on a separate fixed-weld member");
                }
            }
        }
        if (age == 66 && FINITE_WELDED_TURRET) staffSiblingPitch=assemblePitch(staffSiblingYaw,Direction.WEST);
        if (age == 69 && FINITE_WELDED_CARRIER) {
            for (ServerSubLevel member : finiteWeldedBodies) {
                require(!member.isRemoved() && SubLevelContainer.getContainer(level).getSubLevel(member.getUniqueId()) == member,
                        "fixed-weld fixture member remains loaded before native joint creation");
                Vector3d worldAnchor = member.logicalPose().position();
                Vector3d a = carrier.logicalPose().transformPositionInverse(new Vector3d(worldAnchor));
                Vector3d b = member.logicalPose().transformPositionInverse(new Vector3d(worldAnchor));
                Quaterniond relative = new Quaterniond(carrier.logicalPose().orientation()).conjugate()
                        .mul(member.logicalPose().orientation());
                var handle = SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(carrier, member,
                        new dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration(a, b, relative));
                require(handle != null && handle.isValid(), "native fixed-weld handle exists after body registration");
                finiteWeldHandles.add(handle);
            }
            System.out.println("Created and verified five native fixed-weld joints after body registration");
        }
        if (age == 70) {
            finiteMounts[0] = attachLoadedFiniteCannon(movingPitch, 0);
            finiteMounts[1] = attachLoadedFiniteCannon(staffSiblingPitch, 1);
            BlockPos controllerPos = FINITE_PRODUCTION_INPUT ? movingYaw.getBlockPos().offset(2, 0, 0)
                    : new BlockPos(20, 180, 0);
            var state = ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState();
            if (FINITE_PRODUCTION_INPUT) {
                level.setBlockAndUpdate(controllerPos, state);
                finiteController = (StabilizerControllerBlockEntity) level.getBlockEntity(controllerPos);
                require(finiteController != null, "real world controller block entity");
            } else {
                finiteController = new StabilizerControllerBlockEntity(controllerPos, state);
                finiteController.setLevel(level);
            }
            finiteController.bind(BindingSlot.YAW, BindingRef.capture(level, movingYaw.getBlockPos()));
            finiteController.bind(BindingSlot.PITCH, BindingRef.capture(level, movingPitch.getBlockPos()));
            finiteController.bind(BindingSlot.CANNON_MOUNT, finiteMounts[0]);
            finiteController.bind(BindingSlot.YAW, BindingRef.capture(level, staffSiblingYaw.getBlockPos()));
            finiteController.bind(BindingSlot.PITCH, BindingRef.capture(level, staffSiblingPitch.getBlockPos()));
            finiteController.bind(BindingSlot.CANNON_MOUNT, finiteMounts[1]);
            if (FINITE_USER_OFFSETS) {
                var scopeField=StabilizerControllerBlockEntity.class.getDeclaredField("scopeOffset");
                scopeField.setAccessible(true);
                scopeField.set(finiteController,new Vec3(1,1,0));
            }
            Vec3 first = CannonBallisticsCompat.getCurrentMuzzlePosition(level, finiteMounts[0]);
            Vec3 second = CannonBallisticsCompat.getCurrentMuzzlePosition(level, finiteMounts[1]);
            require(first != null && second != null, "both finite-point CBC muzzles resolve");
            finiteCameraOrigin = first.add(second).scale(0.5);
            finiteNearTarget = FINITE_GROUND_TARGET
                    ? finiteGroundTarget(finiteCameraOrigin.add(FINITE_SKY_DIRECTION.x*80,-20,FINITE_SKY_DIRECTION.z*80))
                    : finiteBlockTarget(finiteCameraOrigin.add(FINITE_SKY_DIRECTION.scale(80)));
            finiteFarTarget = FINITE_GROUND_TARGET
                    ? finiteGroundTarget(finiteCameraOrigin.add(FINITE_SKY_DIRECTION.x*240,-30,FINITE_SKY_DIRECTION.z*240))
                    : finiteBlockTarget(finiteCameraOrigin.add(FINITE_SKY_DIRECTION.scale(240)));
            if (FINITE_TRANSLATION) {
                finiteCameraBody = FINITE_PRODUCTION_INPUT
                        ? (ServerSubLevel) Sable.HELPER.getContaining(movingPitch) : carrier;
                if (FINITE_PRODUCTION_INPUT) finiteCameraOrigin = finiteController.getScopePosition();
                finiteCameraLocal = finiteCameraBody.logicalPose().transformPositionInverse(
                        new Vector3d(finiteCameraOrigin.x,finiteCameraOrigin.y,finiteCameraOrigin.z));
                finiteNearTarget = finiteGroundTarget(finiteCameraOrigin.add(53, -4, 0));
                finiteFarTarget = finiteNearTarget;
                finiteMovingViewDirection = finiteNearTarget.subtract(finiteCameraOrigin).normalize();
                BlockPos centre = BlockPos.containing(finiteNearTarget).below();
                for (int cx=-4;cx<=4;cx++) for (int cz=-10;cz<=10;cz++)
                    level.setChunkForced((centre.getX()>>4)+cx,(centre.getZ()>>4)+cz,true);
                // Keep target terrain clear of the five welded chassis members at x=48..60.
                // A low sight's broad floor otherwise touches their top faces from above.
                int groundHalfWidth=FINITE_LIMIT_TRANSITIONS ? 25 : 45;
                System.out.println("Finite ground top="+finiteNearTarget.y+" x="+(centre.getX()-groundHalfWidth)+".."+(centre.getX()+groundHalfWidth));
                for (int x=-groundHalfWidth;x<=groundHalfWidth;x++) for (int z=-150;z<=150;z++) {
                    BlockPos block=centre.offset(x,0,z);
                    level.getChunk(block);
                    level.setBlockAndUpdate(block,Blocks.STONE.defaultBlockState());
                }
            }
        }
        if (finiteController == null) return;
        if (FINITE_LIMIT_TRANSITIONS) {
            if (AIM_EDGE_CASES) { tickAimEdgeCases(event); return; }
            tickFiniteLimitTransitions(event);
            return;
        }
        if (FINITE_AIM_TRANSITIONS) {
            tickFiniteAimTransitions(event);
            return;
        }
        int phase = finitePhase();
        if (phase >= FINITE_PHASE_NAMES.length) {
            finishFiniteCarrierAim(event);
            return;
        }
        int elapsed = finitePhaseElapsed();
        if (FINITE_WELDED_CARRIER) require(finiteWeldedBodies.size() == 5
                        && finiteWeldHandles.size() == 5 && finiteWeldHandles.stream().allMatch(PhysicsConstraintHandle::isValid),
                "all five physical fixed-weld joints remain valid throughout motion");
        boolean sky = finiteSkyPhase(phase);
        finiteTarget = phase == 0 || phase == 3 ? finiteNearTarget : finiteFarTarget;
        if (FINITE_TRANSLATION) updateFiniteMovingView();
        // Only the acquisition budget is limited. Once acquired, moving-platform
        // compensation must work even with a zero head-aim speed on both turrets.
        double operatorSpeed = elapsed < 100 ? 180 : FINITE_PRODUCTION_INPUT ? 30 : 0;
        for (SwivelBearingBlockEntity yaw : new SwivelBearingBlockEntity[]{movingYaw, staffSiblingYaw}) {
            finiteController.setBearingSettings(BindingSlot.YAW, yaw.getBlockPos(), 90, 89,
                    operatorSpeed, false, false, Vec3.ZERO);
        }
        for (SwivelBearingBlockEntity pitch : new SwivelBearingBlockEntity[]{movingPitch, staffSiblingPitch}) {
            finiteController.setBearingSettings(BindingSlot.PITCH, pitch.getBlockPos(), 90, 89,
                    operatorSpeed, false, false, Vec3.ZERO);
        }
        Vec3 cameraDirection = sky ? FINITE_SKY_DIRECTION : finiteTarget.subtract(finiteCameraOrigin).normalize();
        finiteController.setDesiredAimRay(finiteCameraOrigin, cameraDirection,
                sky ? finiteCameraOrigin.add(cameraDirection.scale(4096)) : finiteTarget, !sky);
        if (FINITE_TRANSLATION && !sky) finiteController.setMovingAimRay(new MovingAimRay(
                finiteCameraBody.getUniqueId(),new Vec3(finiteCameraLocal.x,finiteCameraLocal.y,finiteCameraLocal.z),
                finiteMovingViewDirection,finiteTarget,new Vec3(0,1,0)));
        if (!FINITE_PRODUCTION_INPUT) StabilizerControllerBlockEntity.tick(level,
                finiteController.getBlockPos(), finiteController.getBlockState(), finiteController);
        appendFiniteSupportAfterHinges();
        var groups = finiteController.getMeasuredAimGroups();
        require(groups.size() == 2, "real controller resolves two independent loaded CBC groups at age " + age);
        for (var group : groups) require(sky == (group.ballistic() == null),
                "finite/sky marker mode switches for each turret in the same tick at age " + age);
        if (FINITE_TRANSLATION) for (var group : groups) {
            require(group.frame()!=null && group.frame().motion()!=null,"each turret publishes real physical motion");
            var frame=group.frame(); var motion=frame.motion();
            var pose=new dev.ryanhcode.sable.companion.math.Pose3d(
                    new Vector3d(motion.position().x,motion.position().y,motion.position().z),motion.orientation(),
                    new Vector3d(motion.localCOM().x,motion.localCOM().y,motion.localCOM().z),new Vector3d(1));
            Vec3 inherited=com.hooya.stabilizedturret.client.CannonRenderFrame.carrierVelocity(frame,pose,java.util.List.of(motion));
            var launch=com.hooya.stabilizedturret.client.CannonRenderFrame.from(frame,pose,inherited);
            require(launch.origin().distanceTo(new Vec3(group.originX(),group.originY(),group.originZ()))<1e-6,
                    "published COM motion and local muzzle reconstruct the actual packet position");
            if (group.ballistic()!=null) {
                var ballistic=group.ballistic();
                require(launch.velocity().distanceTo(new Vec3(ballistic.velocityX(),ballistic.velocityY(),ballistic.velocityZ()))<1e-6,
                        "published native instantaneous point velocity matches the actual CBC ballistic packet");
            }
        }
        if (age >= FINITE_START && elapsed == 0) {
            finiteMotionTime = 0;
            System.out.println("finite-point phase " + phase + ": " + FINITE_PHASE_NAMES[phase]);
        }
    }

    private static void appendFiniteSupportAfterHinges() {
        if (age == 75 && !FINITE_SUPPORT_BEFORE_HINGES) {
            // Match production's solver order without changing a physical pose or
            // velocity. The original order remains available for the known numerical
            // boundary; the same native motor parameters and world goals are retained.
            require(BearingStabilizer.ownsJoint(movingYaw) && BearingStabilizer.ownsJoint(movingPitch)
                            && BearingStabilizer.ownsJoint(staffSiblingYaw) && BearingStabilizer.ownsJoint(staffSiblingPitch),
                    "every finite-point owned hinge exists before support is appended");
            carrierSteering.remove();
            carrierSteering = createFiniteSupportConstraint();
            carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_X, 0, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_Z, 0, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_X, finiteSupportGoal.x, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Y, finiteSupportGoal.y, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Z, finiteSupportGoal.z, 3e7, 3e5, false, 0);
            System.out.println("finite-point support appended after all owned hinges; unchanged pose, velocity and native motor parameters");
        }
    }

    private static PhysicsConstraintHandle createFiniteSupportConstraint() {
        if (!FINITE_REBASE_SUPPORT) {
            finiteRebasedSupport = null;
            return SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null, carrier,
                    new FreeConstraintConfiguration(new Vector3d(), new Vector3d(finiteSupportAnchor), new Quaterniond()));
        }
        finiteRebasedSupport = SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null, carrier,
                new dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration(
                        new Vector3d(), new Vector3d(finiteSupportAnchor), new Quaterniond(), new Quaterniond(), Set.of()));
        return finiteRebasedSupport;
    }

    private static void rebaseFiniteSupport() {
        if (finiteRebasedSupport == null) return;
        Vector3d forward = carrier.logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        double yaw = Math.atan2(forward.x, forward.z);
        Quaterniond frame = new Quaterniond().rotationY(yaw);
        finiteRebasedSupport.setFrame1(new Vector3d(), frame);
        finiteRebasedSupport.setFrame2(new Vector3d(finiteSupportAnchor), new Quaterniond());
        Vector3d localGoal = frame.transformInverse(new Vector3d(finiteSupportGoal));
        finiteRebasedSupport.setMotor(ConstraintJointAxis.LINEAR_X, localGoal.x, 3e7, 3e5, false, 0);
        finiteRebasedSupport.setMotor(ConstraintJointAxis.LINEAR_Y, localGoal.y, 3e7, 3e5, false, 0);
        finiteRebasedSupport.setMotor(ConstraintJointAxis.LINEAR_Z, localGoal.z, 3e7, 3e5, false, 0);
    }

    /** User envelope, independent axes, side-facing travel and near/far acquisition. */
    private static void tickAimEdgeCases(ServerTickEvent.Pre event) throws Exception {
        if (age>=1400) {
            String result=String.format(java.util.Locale.ROOT,
                    "slew excess %.9f deg/step; pitch envelope excess %.9f; pitch endpoint error %.9f; grabbed carrier rotation %.9f; grabbed pitch ray error %.9f degrees\n",
                    edgeSlewExcess,edgePitchPeak,edgeEndpointPeak,edgeCarrierPeak,edgePitchHoldPeak);
            Files.writeString(Path.of("bearing-aim-edge-diagnostics.csv"),edgeReport);
            boolean passed=edgeSlewExcess<.05 && edgePitchPeak<.05 && edgeEndpointPeak<.05 && edgeCarrierPeak<.05 && edgePitchHoldPeak<.05;
            Files.writeString(Path.of("bearing-aim-edge-result.txt"),(passed?"PASS\n":"FAIL\n")+result);
            require(passed,"aim mode/envelope/turret staff regression: "+result);
            Files.writeString(Path.of("bearing-integration-result.txt"),"PASS");
            finished=true; event.getServer().halt(false); return;
        }
        for (var yaw:new SwivelBearingBlockEntity[]{movingYaw,staffSiblingYaw})
            finiteController.setBearingSettings(BindingSlot.YAW,yaw.getBlockPos(),10,20,40,false,false,Vec3.ZERO);
        for (var pitch:new SwivelBearingBlockEntity[]{movingPitch,staffSiblingPitch})
            finiteController.setBearingSettings(BindingSlot.PITCH,pitch.getBlockPos(),10,20,28,false,false,Vec3.ZERO);
        Vector3d camera=finiteCameraBody.logicalPose().transformPosition(new Vector3d(finiteCameraLocal));
        finiteCameraOrigin=new Vec3(camera.x,camera.y,camera.z);
        double elevation=age<220?-5:age<340?-75:age<560?75:age<680?89.99:age<780?-75:-5;
        double heading=age>=680 && age<780 ? 90+30*Math.sin((age-680)*.03) : 90;
        double el=Math.toRadians(elevation), az=Math.toRadians(heading);
        limitView=new Vec3(Math.sin(az)*Math.cos(el),Math.sin(el),Math.cos(az)*Math.cos(el));
        edgeSky=age>=440 && age<500 || age>=560 && age<620 || age>=780 && age<1040 || age>=1320;
        int surfacePhase=age<220?0:age<340?1:age<440?2:age<500?3:age<560?4:age<620?5:age<680?6:age<780?7:age<1040?8:age<1320?9:10;
        if (surfacePhase!=edgeSurfacePhase) {
            edgeSurfacePhase=surfacePhase; edgeSurfacePoint=finiteCameraOrigin.add(limitView.scale(80)); edgeSurfaceNormal=limitView;
        }
        double distance=edgeSurfacePoint.subtract(finiteCameraOrigin).dot(edgeSurfaceNormal)/limitView.dot(edgeSurfaceNormal);
        finiteTarget=finiteCameraOrigin.add(limitView.scale(distance));
        finiteController.setDesiredAimRay(finiteCameraOrigin,limitView,finiteTarget,!edgeSky);
        finiteController.setMovingAimRay(edgeSky?null:new MovingAimRay(finiteCameraBody.getUniqueId(),
                new Vec3(finiteCameraLocal.x,finiteCameraLocal.y,finiteCameraLocal.z),limitView,edgeSurfacePoint,edgeSurfaceNormal));
        if (age==878 || age==1198) { carrierSteering.remove(); carrierSteering=null; }
        if (age==880 || age==1200) {
            edgeCarrierBaseline=new Quaterniond(carrier.logicalPose().orientation());
            edgePitchBaseline=CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level,finiteMounts[0]),level,finiteMounts[0]).currentForward();
            startRealStaffDrag(child(movingYaw));
        }
        if (age>=880 && age<1000 || age>=1200 && age<1320) {
            int start=age>=1200?1200:880;
            Vector3d goal=new Vector3d(staffWorldGoal).add(0,2*Math.sin((age-start)*.07),0);
            Vector3d eye=new Vector3d(staffPlayer.getX(),staffPlayer.getEyeY(),staffPlayer.getZ());
            PhysicsStaffServerHandler.get(level).drag(staffPlayer.getUUID(),child(movingYaw).getUniqueId(),goal.sub(eye),staffAnchor,staffOrientation);
        }
        if (age==1000 || age==1320) {
            PhysicsStaffServerHandler.get(level).stopDragging(staffPlayer.getUUID());
            Vector3d anchor=new Vector3d(carrier.getMassTracker().getCenterOfMass());
            Vector3d world=carrier.logicalPose().transformPosition(new Vector3d(anchor));
            carrierSteering=SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null,carrier,
                    new FreeConstraintConfiguration(new Vector3d(),anchor,new Quaterniond()));
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_X,world.x,3e7,3e5,false,0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Y,world.y,3e7,3e5,false,0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Z,world.z,3e7,3e5,false,0);
        }
        if (age>=1198 && age<=1210) {
            var pipeline=SubLevelPhysicsSystem.require(level).getPipeline();
            var field=pipeline.getClass().getDeclaredField("recentCollisions");field.setAccessible(true);
            var collisions=(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap)field.get(pipeline);
            for(var collision:collisions.long2LongEntrySet()) if(level.getGameTime()-collision.getLongValue()<=3)
                System.out.println("edge-recent-contact age "+age+" block "+BlockPos.of(collision.getLongKey())+" tick "+collision.getLongValue());
        }
    }

    private static void measureAimEdgeCases(SubLevelPhysicsSystem system,double dt) throws Exception {
        if (age<110) return;
        if (age>=880 && age<1000 || age>=1200 && age<1320) {
            require(com.hooya.stabilizedturret.compat.simulated.SimulatedPhysicsStaffCompat.draggedBodies(level)
                    .contains(child(movingYaw).getUniqueId()),"creative staff session remains active on every physics step");
        }
        for (int turret=0;turret<2;turret++) {
            var yaw=turret==0?movingYaw:staffSiblingYaw;
            var pitch=turret==0?movingPitch:staffSiblingPitch;
            Vec3 actual=CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level,finiteMounts[turret]),level,finiteMounts[turret]).currentForward();
            Vector3d up=Sable.HELPER.getContaining(pitch).logicalPose().transformNormal(new Vector3d(0,1,0)).normalize();
            double elevation=Math.toDegrees(Math.asin(Math.clamp(new Vector3d(actual.x,actual.y,actual.z).dot(up),-1,1)));
            edgePitchPeak=Math.max(edgePitchPeak,Math.max(-10-elevation,elevation-20));
            if (age>=280 && age<340 || age>=760 && age<780)
                edgeEndpointPeak=Math.max(edgeEndpointPeak,Math.abs(elevation+10));
            if (age>=400 && age<680)
                edgeEndpointPeak=Math.max(edgeEndpointPeak,Math.abs(elevation-20));
            double[] rates=new double[2],steps=new double[2];
            var axes=new SwivelBearingBlockEntity[]{yaw,pitch};
            for (int j=0;j<2;j++) {
                var bearing=axes[j]; var parent=(ServerSubLevel)Sable.HELPER.getContaining(bearing);
                Direction facing=bearing.getBlockState().getValue(SwivelBearingBlock.FACING);
                Vector3d axis=parent.logicalPose().transformNormal(new Vector3d(facing.getStepX(),facing.getStepY(),facing.getStepZ())).normalize();
                rates[j]=Math.toDegrees(system.getPipeline().getAngularVelocity(child(bearing),new Vector3d())
                        .sub(system.getPipeline().getAngularVelocity(parent,new Vector3d())).dot(axis));
                Vector3d held=new Vector3d((Vector3d)readTurnSnapshotField(transitionLock(bearing),"heldAimDirection"));
                if (edgePreviousHeld[2*turret+j]!=null) steps[j]=Math.abs(Math.toDegrees(BearingServoMath.signedDirectionAngle(edgePreviousHeld[2*turret+j],held,axis)));
                edgePreviousHeld[2*turret+j]=held;
                if (age<878) edgeSlewExcess=Math.max(edgeSlewExcess,steps[j]-(j==0?40:28)*dt);
                require(BearingStabilizer.ownsJoint(bearing),"edge fixture retains every owned hinge");
            }
            double carrierAngle=0,pitchError=0;
            if (age>=880 && age<1000 || age>=1200 && age<1320) {
                Quaterniond delta=new Quaterniond(edgeCarrierBaseline).conjugate().mul(carrier.logicalPose().orientation());
                carrierAngle=Math.toDegrees(2*Math.atan2(Math.sqrt(delta.x*delta.x+delta.y*delta.y+delta.z*delta.z),Math.abs(delta.w)));
                if (turret==0) pitchError=directionError(actual,edgePitchBaseline);
                edgeCarrierPeak=Math.max(edgeCarrierPeak,carrierAngle);
                edgePitchHoldPeak=Math.max(edgePitchHoldPeak,pitchError);
                if(turret==0 && age>=1200 && age<=1210 && pitchError>.05) {
                    Object yawLock=transitionLock(yaw),pitchLock=transitionLock(pitch);
                    Vector3d yawAxis=carrier.logicalPose().transformNormal(new Vector3d(0,1,0)).normalize();
                    Direction face=pitch.getBlockState().getValue(SwivelBearingBlock.FACING);
                    Vector3d pitchAxis=Sable.HELPER.getContaining(pitch).logicalPose().transformNormal(new Vector3d(face.getStepX(),face.getStepY(),face.getStepZ())).normalize();
                    Vector3d ray=new Vector3d(actual.x,actual.y,actual.z),baseline=new Vector3d(edgePitchBaseline.x,edgePitchBaseline.y,edgePitchBaseline.z);
                    System.out.printf(java.util.Locale.ROOT,"edge-grab age %d yaw/pitch %.9f/%.9f held/yaw/pitch %.9f/%.9f root-pos %s yaw-pos %s pitch-pos %s%n",age,
                            Math.toDegrees(BearingServoMath.signedDirectionAngle(ray,baseline,yawAxis)),Math.toDegrees(BearingServoMath.signedDirectionAngle(ray,baseline,pitchAxis)),
                            Math.toDegrees(BearingServoMath.signedDirectionAngle(ray,(Vector3d)readTurnSnapshotField(yawLock,"heldAimDirection"),yawAxis)),
                            Math.toDegrees(BearingServoMath.signedDirectionAngle(ray,(Vector3d)readTurnSnapshotField(pitchLock,"heldAimDirection"),pitchAxis)),
                            carrier.logicalPose().position(),child(yaw).logicalPose().position(),child(pitch).logicalPose().position());
                    for(var bearing:new SwivelBearingBlockEntity[]{yaw,pitch}) {
                        ServerSubLevel body=child(bearing); var forecast=finitePrediction.get(body.getUniqueId());
                        if(forecast!=null) {
                            Quaterniond difference=forecast.orientation().conjugate().mul(body.logicalPose().orientation());
                            double poseError=Math.toDegrees(2*Math.atan2(Math.sqrt(difference.x*difference.x+difference.y*difference.y+difference.z*difference.z),Math.abs(difference.w)));
                            System.out.printf(java.util.Locale.ROOT,"edge-grab forecast %s pose %.9f position %.9f%n",bearing.getBlockPos(),poseError,forecast.position().distance(body.logicalPose().position()));
                        }
                    }
                }
                if (turret==0 && age>=1200 && age<=1210 && pitchError>.05) {
                    var counts=BearingStabilizer.class.getDeclaredField("SERVO_PLANNING_RESULTS"); counts.setAccessible(true);
                    System.out.println("edge-current-plan age "+age+" forecast-present "+finitePrediction.containsKey(child(yaw).getUniqueId())+" "+counts.get(null));
                }
                require(!BearingStabilizer.yieldingToStaff(pitch),"grabbing a turret never yields its downstream elevation stabilizer");
                if (turret==0) require(((java.util.List<?>)readTurnSnapshotField(transitionLock(pitch),"finiteTargets")).isEmpty(),
                        "turret staff holds the barrel ray instead of chasing a finite point while lifted");
            }
            edgeReport.append(String.format(java.util.Locale.ROOT,"%d,%d,%s,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                    age,turret,edgeSky,elevation,rates[0],rates[1],steps[0],steps[1],carrierAngle,pitchError));
        }
    }

    private static void tickFiniteLimitTransitions(ServerTickEvent.Pre event) throws Exception {
        if (age >= 820) {
            require(limitFirstReturnSamples>=2,"both pitch drives respond on the first reachable return step");
            Files.writeString(Path.of("bearing-finite-limit-diagnostics.csv"),limitDiagnostics);
            Files.writeString(Path.of("bearing-finite-limit-result.txt"),String.format(java.util.Locale.ROOT,
                    "PASS: %d physical samples; moving/stopped marker %.9f; landing marker %.9f; yaw while pitch stopped %.9f; held pitch %.9f; real terrain impact %.9f degrees.\n"
                    + "Two native turrets, five fixed welds, real controller ticks, 40/28 deg/s, 10/20 degree envelope.\n",
                    limitSamples,limitPeak,limitLandingPeak,limitYawPeak,limitHeldPitchPeak,limitTerrainPeak));
            Files.writeString(Path.of("bearing-integration-result.txt"),"PASS");
            writeServoPlanningDiagnostics();
            finished=true;
            event.getServer().halt(false);
            return;
        }
        if (limitNear==null) {
            limitNear=finiteNearTarget;
            limitFar=finiteGroundTarget(finiteCameraOrigin.add(200,-6,0));
            limitBelow=finiteGroundTarget(finiteCameraOrigin.add(25,-40,0));
        }
        if (FINITE_WELDED_CARRIER) require(finiteWeldHandles.size()==5
                        && finiteWeldHandles.stream().allMatch(PhysicsConstraintHandle::isValid),
                "five native welds remain loaded in limit/arrival fixture");
        for (var yaw:new SwivelBearingBlockEntity[]{movingYaw,staffSiblingYaw})
            finiteController.setBearingSettings(BindingSlot.YAW,yaw.getBlockPos(),10,20,40,false,false,
                    FINITE_USER_OFFSETS ? new Vec3(0,-.5,0) : Vec3.ZERO);
        for (var pitch:new SwivelBearingBlockEntity[]{movingPitch,staffSiblingPitch})
            finiteController.setBearingSettings(BindingSlot.PITCH,pitch.getBlockPos(),10,20,28,false,false,
                    FINITE_USER_OFFSETS ? new Vec3(0,0,-.5) : Vec3.ZERO);
        Vector3d camera=finiteCameraBody.logicalPose().transformPosition(new Vector3d(finiteCameraLocal));
        finiteCameraOrigin=new Vec3(camera.x,camera.y,camera.z);
        if (age==420) finiteMovingViewDirection=limitNear.subtract(finiteCameraOrigin).normalize();
        finiteTarget=age>=220 && age<340 ? limitBelow : age>=560 && age<680 ? limitFar : limitNear;
        if (age>=420 && age<560) {
            double distance=(limitNear.y-finiteCameraOrigin.y)/finiteMovingViewDirection.y;
            finiteTarget=finiteCameraOrigin.add(finiteMovingViewDirection.scale(distance));
        }
        Vec3 direction=finiteTarget.subtract(finiteCameraOrigin).normalize();
        limitView=direction;
        finiteController.setDesiredAimRay(finiteCameraOrigin,direction,finiteTarget,true);
        finiteController.setMovingAimRay(new MovingAimRay(finiteCameraBody.getUniqueId(),
                new Vec3(finiteCameraLocal.x,finiteCameraLocal.y,finiteCameraLocal.z),direction,finiteTarget,new Vec3(0,1,0)));
    }

    private static void measureFiniteLimitTransitions(double dt) throws Exception {
        if (age<110) return;
        Vector3d camera=finiteCameraBody.logicalPose().transformPosition(new Vector3d(finiteCameraLocal));
        finiteCameraOrigin=new Vec3(camera.x,camera.y,camera.z);
        finiteTarget=finiteCameraOrigin.add(limitView.scale((finiteTarget.y-finiteCameraOrigin.y)/limitView.y));
        recordFiniteAimLocks(dt,-1,"limits-arrival");
        for (int turret=0;turret<2;turret++) {
            var profile=CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level,finiteMounts[turret]),level,finiteMounts[turret]);
            require(profile!=null,"limit test retains loaded actual firing profile");
            Vec3 expected=CannonBallisticsCompat.solveLaunchDirection(profile,finiteTarget);
            require(expected!=null,"limit test ballistic solution exists");
            Vec3 actual=profile.currentForward();
            Vec3 plane=finiteTargetPlaneImpactX(profile,finiteTarget.x);
            double marker=plane==null ? 180 : directionError(plane.subtract(finiteCameraOrigin),finiteTarget.subtract(finiteCameraOrigin));
            if (age>=490 && age<540 && marker>.05) {
                for (var body:isolatedStaffBodies()) {
                    var forecast=finitePrediction.get(body.getUniqueId());
                    if (forecast==null) continue;
                    Quaterniond difference=forecast.orientation().conjugate().mul(body.logicalPose().orientation());
                    System.out.printf(java.util.Locale.ROOT,"limit-forecast age %d turret %d body %s pose %.9f position %.9f velocity %.9f angular %.9f marker %.9f%n",age,turret,body.getUniqueId(),
                            Math.toDegrees(2*Math.atan2(Math.sqrt(difference.x*difference.x+difference.y*difference.y+difference.z*difference.z),Math.abs(difference.w))),
                            forecast.position().distance(body.logicalPose().position()),forecast.linearVelocity().distance(SubLevelPhysicsSystem.require(level).getPipeline().getLinearVelocity(body,new Vector3d())),
                            forecast.angularVelocity().distance(SubLevelPhysicsSystem.require(level).getPipeline().getAngularVelocity(body,new Vector3d())),marker);
                }
            }
            var yaw=turret==0?movingYaw:staffSiblingYaw;
            var pitch=turret==0?movingPitch:staffSiblingPitch;
            Object yawLock=transitionLock(yaw),pitchLock=transitionLock(pitch);
            Vector3d held=(Vector3d)readTurnSnapshotField(pitchLock,"heldAimDirection");
            Vector3d parentUp=Sable.HELPER.getContaining(pitch).logicalPose().transformNormal(new Vector3d(0,1,0)).normalize();
            double heldPitch=Math.toDegrees(Math.asin(Math.clamp(held.dot(parentUp),-1,1)));
            double actualPitch=Math.toDegrees(Math.asin(Math.clamp(new Vector3d(actual.x,actual.y,actual.z).dot(parentUp),-1,1)));
            if (age==340 && limitFirstReturnSamples<2) {
                require(actualPitch>limitPreviousPitch[turret]+.001,"pitch begins returning on the first physics step after leaving depression stop: turret "+turret
                        +" previous "+limitPreviousPitch[turret]+" current "+actualPitch);
                limitFirstReturnSamples++;
            }
            limitPreviousPitch[turret]=actualPitch;
            Vector3d yawAxis=Sable.HELPER.getContaining(yaw).logicalPose().transformNormal(new Vector3d(0,1,0)).normalize();
            // Beyond depression, azimuth follows the nearest sight heading;
            // there is no reachable finite launch direction to track there.
            Vec3 yawExpected=age>=220 && age<340 ? limitView : expected;
            double yawError=Math.abs(Math.toDegrees(BearingServoMath.signedDirectionAngle(
                    new Vector3d(actual.x,actual.y,actual.z),new Vector3d(yawExpected.x,yawExpected.y,yawExpected.z),yawAxis)));
            var motion=(BearingServoMath.OperatorMotion)readTurnSnapshotField(pitchLock,"operatorMotion");
            limitDiagnostics.append(String.format(java.util.Locale.ROOT,"%d,%d,%.9f,%.9f,%.9f,%.9f%n",age,turret,marker,heldPitch,yawError,motion.velocity()));
            limitHeldPitchPeak=Math.max(limitHeldPitchPeak,Math.max(-heldPitch,heldPitch));
            require(heldPitch>=-10.05 && heldPitch<=20.05,"internal pitch cannot accumulate outside user envelope: "+heldPitch);
            if (age>=290 && age<340) {
                limitYawPeak=Math.max(limitYawPeak,yawError);
                require(yawError<0.05,"pitch stop leaves independent yaw stable: "+yawError);
            }
            if (age>=380 && age<560 || age>=650 && age<680 || age>=730) {
                Set<UUID> ignored=java.util.Arrays.stream(isolatedStaffBodies()).map(ServerSubLevel::getUniqueId).collect(Collectors.toSet());
                ignored.addAll(finiteWeldedBodies.stream().map(ServerSubLevel::getUniqueId).toList());
                Vec3 impact=CannonBallisticsCompat.predictImpact(level,profile,ignored);
                require(impact!=null,"limit/arrival physical projectile hits actual terrain");
                double terrainError=directionError(impact.subtract(finiteCameraOrigin),limitView);
                limitTerrainPeak=Math.max(limitTerrainPeak,terrainError);
                require(terrainError<.05,"limit/arrival real terrain marker stays on view: age "+age+" error "+terrainError);
                limitPeak=Math.max(limitPeak,marker);
                require(marker<0.05,"physical marker stable immediately after reacquisition and throughout side travel: age "+age+" turret "+turret+" error "+marker);
            }
            Vector3d requested=(Vector3d)readTurnSnapshotField(pitchLock,"requestedAimDirection");
            Direction facing=pitch.getBlockState().getValue(SwivelBearingBlock.FACING);
            Vector3d axis=Sable.HELPER.getContaining(pitch).logicalPose().transformNormal(new Vector3d(facing.getStepX(),facing.getStepY(),facing.getStepZ()));
            double pending=Math.abs(BearingServoMath.signedDirectionAngle(held,requested,axis));
            Vector3d yawHeld=(Vector3d)readTurnSnapshotField(yawLock,"heldAimDirection");
            Vector3d yawRequested=(Vector3d)readTurnSnapshotField(yawLock,"requestedAimDirection");
            var yawMotion=(BearingServoMath.OperatorMotion)readTurnSnapshotField(yawLock,"operatorMotion");
            double yawPending=Math.abs(BearingServoMath.signedDirectionAngle(yawHeld,yawRequested,yawAxis));
            if (age>=560 && pending<1e-5 && yawPending<1e-5 && Math.abs(motion.velocity())<1e-6 && Math.abs(yawMotion.velocity())<1e-6) {
                limitLandingPeak=Math.max(limitLandingPeak,marker);
                require(marker<0.05,"new depth marker is centered on first completed operator step: age "+age+" error "+marker);
            }
            limitSamples++;
        }
    }

    /** Isolates operator intent/state transitions from carrier dynamics. */
    private static void tickFiniteAimTransitions(ServerTickEvent.Pre event) throws Exception {
        if (age >= 1060) {
            for (boolean observed : transitionExpiryObserved) require(observed,
                    "every finite axis actually crosses the production input-expiry branch");
            Files.writeString(Path.of("bearing-finite-aim-transitions-diagnostics.csv"), transitionDiagnostics);
            Files.writeString(Path.of("bearing-finite-carrier-lock-diagnostics.csv"), finiteAimLockDiagnostics);
            Files.writeString(Path.of("bearing-finite-aim-transitions-result.txt"), String.format(java.util.Locale.ROOT,
                    "PASS: %d physical steps; zero-speed held %.9f, barrel %.9f; same-view moving-camera marker %.9f; "
                            + "slew excess %.9f; sibling marker during unbind %.9f; input-expiry barrel %.9f degrees.\n"
                            + "Real controller/CBC geometry, AP breech input; production may select fallback profile.\n"
                            + "Finite body identity change is not covered: legal mount unbind/rebind retains the same physical bodies.\n",
                    transitionMeasuredSteps, transitionMaxZeroHeldError, transitionMaxZeroBarrelError,
                    transitionMaxCameraMarkerError, transitionMaxSlewExcess, transitionMaxSiblingMarkerError,
                    transitionMaxExpiredBarrelError));
            Files.writeString(Path.of("bearing-integration-result.txt"), "PASS");
            writeServoPlanningDiagnostics();
            finished = true;
            event.getServer().halt(false);
            return;
        }
        if (transitionSecondTarget == null) {
            Vector3d turned = new Quaterniond().rotationY(Math.toRadians(20))
                    .transform(new Vector3d(FINITE_SKY_DIRECTION.x, FINITE_SKY_DIRECTION.y, FINITE_SKY_DIRECTION.z));
            transitionSecondTarget = finiteBlockTarget(finiteCameraOrigin.add(turned.x * 160, turned.y * 160, turned.z * 160));
            transitionCameraDirection = transitionSecondTarget.subtract(finiteCameraOrigin).normalize();
            BlockPos wall = BlockPos.containing(transitionSecondTarget);
            for (int x = -4; x <= 4; x++) {
                BlockPos block = wall.offset(x, 0, 0);
                level.getChunk(block);
                level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState());
            }
        }
        if (age == 220 || age == 480 || age == 600 || age == 800 || age == 980) {
            captureTransitionBaseline();
        }
        if (age == 980) {
            for (SwivelBearingBlockEntity bearing : new SwivelBearingBlockEntity[]{movingYaw, movingPitch, staffSiblingYaw, staffSiblingPitch}) {
                Object lock = transitionLock(bearing);
                require((boolean) readTurnSnapshotField(lock, "finiteCompensationActive")
                                && !((java.util.List<?>) readTurnSnapshotField(lock, "finiteTargets")).isEmpty(),
                        "input-expiry fixture starts from acquired finite tracking on every axis");
            }
        }
        if (age == 220) require(directionError(finiteNearTarget.subtract(finiteCameraOrigin), transitionCameraDirection) > 10,
                "zero-speed finite transition changes view direction by at least ten degrees");
        if (age == 780) {
            require(finiteController.unbindDevice(BindingSlot.CANNON_MOUNT, 0, finiteMounts[0].pos()),
                    "finite source is removed through legitimate device-page API");
        }
        if (age == 800) finiteController.bind(BindingSlot.CANNON_MOUNT, finiteMounts[0]);
        transitionSky = age >= 480 && age < 600;
        transitionOperatorSpeed = age >= 980 ? 0 : age < 220 ? 180
                : age >= 260 && age < 400 || age >= 520 && age < 600
                || age >= 640 && age < 780 || age >= 840 ? 30 : 0;
        transitionCamera = finiteCameraOrigin;
        finiteTarget = age < 220 ? finiteNearTarget : transitionSecondTarget;
        transitionInputDirection = age < 220
                ? finiteNearTarget.subtract(finiteCameraOrigin).normalize() : transitionCameraDirection;
        if (age >= 400 && age < 480) {
            // Same inertial camera direction meets a different point on one fixed wall.
            // This is geometry transport, not a new operator traverse command.
            transitionCamera = finiteCameraOrigin.add(2.0 * Math.sin((age - 400) * 0.05), 0, 0);
            double range = (transitionSecondTarget.z - transitionCamera.z) / transitionCameraDirection.z;
            finiteTarget = transitionCamera.add(transitionCameraDirection.scale(range));
        }
        setFiniteFixtureOperatorSpeed(transitionOperatorSpeed);
        // Stop input through the production timeout path; never mutate lock state or body velocities.
        if (age < 980) finiteController.setDesiredAimRay(transitionCamera, transitionInputDirection,
                transitionSky ? transitionCamera.add(transitionInputDirection.scale(4096)) : finiteTarget, !transitionSky);
        StabilizerControllerBlockEntity.tick(level, finiteController.getBlockPos(), finiteController.getBlockState(), finiteController);
        appendFiniteSupportAfterHinges();
        var groups = finiteController.getMeasuredAimGroups();
        int expectedGroups = age >= 780 && age < 800 ? 1 : 2;
        require(groups.size() == expectedGroups, "transition uses expected live turret groups at age " + age);
        for (var group : groups) if (age < 980) require(transitionSky == (group.ballistic() == null),
                "transition changes each live marker mode in the same game tick");
    }

    private static void setFiniteFixtureOperatorSpeed(double speed) {
        for (SwivelBearingBlockEntity yaw : new SwivelBearingBlockEntity[]{movingYaw, staffSiblingYaw}) {
            finiteController.setBearingSettings(BindingSlot.YAW, yaw.getBlockPos(), 90, 89, speed, false, false, Vec3.ZERO);
        }
        for (SwivelBearingBlockEntity pitch : new SwivelBearingBlockEntity[]{movingPitch, staffSiblingPitch}) {
            finiteController.setBearingSettings(BindingSlot.PITCH, pitch.getBlockPos(), 90, 89, speed, false, false, Vec3.ZERO);
        }
    }

    private static Object transitionLock(SwivelBearingBlockEntity bearing) throws Exception {
        var field = BearingStabilizer.class.getDeclaredField("ACTIVE");
        field.setAccessible(true);
        Object lock = ((java.util.Map<?, ?>) field.get(null)).get(bearing);
        require(lock != null, "transition retains active physical bearing lock at age " + age);
        return lock;
    }

    private static void captureTransitionBaseline() throws Exception {
        SwivelBearingBlockEntity[] axes = {movingYaw, movingPitch, staffSiblingYaw, staffSiblingPitch};
        for (int index = 0; index < axes.length; index++) {
            transitionHeldBaseline[index] = new Vector3d((Vector3d) readTurnSnapshotField(transitionLock(axes[index]), "heldAimDirection"));
            transitionPreviousHeld[index] = new Vector3d(transitionHeldBaseline[index]);
        }
        for (int turret = 0; turret < 2; turret++) {
            transitionBarrelBaseline[turret] = CannonBallisticsCompat.getCurrentForward(level, finiteMounts[turret]);
            require(transitionBarrelBaseline[turret] != null, "transition baseline observes physical CBC barrel");
        }
    }

    private static void measureFiniteAimTransitions(SubLevelPhysicsSystem system, double dt) throws Exception {
        if (age < 200) return;
        String state = age < 220 ? "initial settled" : age < 260 ? "new point at zero speed"
                : age < 400 ? "new point at 30 deg/s" : age < 480 ? "same ray moving camera"
                : age < 520 ? "sky at zero speed" : age < 600 ? "sky at 30 deg/s"
                : age < 640 ? "finite at zero speed" : age < 780 ? "finite at 30 deg/s"
                : age < 800 ? "first cannon unbound" : age < 840 ? "cannon rebound at zero speed"
                : age < 980 ? "cannon rebound at 30 deg/s" : "input expired with stabilizer enabled";
        recordFiniteAimLocks(dt, 0, state);
        SwivelBearingBlockEntity[] axes = {movingYaw, movingPitch, staffSiblingYaw, staffSiblingPitch};
        Vector3d[] held = new Vector3d[4];
        double[] heldChange = new double[4], heldStep = new double[4];
        boolean[] active = new boolean[4];
        for (int index = 0; index < axes.length; index++) {
            Object lock = transitionLock(axes[index]);
            held[index] = new Vector3d((Vector3d) readTurnSnapshotField(lock, "heldAimDirection"));
            active[index] = (boolean) readTurnSnapshotField(lock, "finiteCompensationActive");
            if (age >= 980) {
                require(BearingStabilizer.ownsJoint(axes[index]), "input expiry retains every native physical hinge");
                if (((java.util.List<?>) readTurnSnapshotField(lock, "finiteTargets")).isEmpty()
                        && !transitionExpiryObserved[index]) {
                    require(((Number) readTurnSnapshotField(lock, "finiteModeRecoveryRemaining")).doubleValue() > 0.0,
                            "first expired finite step activates mode-history recovery");
                    transitionExpiryObserved[index] = true;
                }
            }
            if (age >= 990) {
                require(!active[index] && ((java.util.List<?>) readTurnSnapshotField(lock, "finiteTargets")).isEmpty(),
                        "production input expiry clears finite mode on every axis without releasing the hinge");
            }
            Direction facing = axes[index].getBlockState().getValue(SwivelBearingBlock.FACING);
            Vector3d axis = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
            Sable.HELPER.getContaining(axes[index]).logicalPose().transformNormal(axis);
            axis.normalize();
            if (transitionHeldBaseline[index] != null) heldChange[index] = Math.abs(Math.toDegrees(
                    BearingServoMath.signedDirectionAngle(transitionHeldBaseline[index], held[index], axis)));
            if (transitionPreviousHeld[index] != null) {
                heldStep[index] = Math.abs(Math.toDegrees(BearingServoMath.signedDirectionAngle(
                        transitionPreviousHeld[index], held[index], axis)));
            }
        }
        boolean zeroHold = age >= 220 && age < 260 || age >= 480 && age < 520
                || age >= 600 && age < 640 || age >= 800 && age < 840;
        boolean rateLimited = age >= 260 && age < 400 || age >= 520 && age < 600
                || age >= 640 && age < 780 || age >= 840 && age < 980;
        for (int turret = 0; turret < 2; turret++) {
            var profile = CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level, finiteMounts[turret]),
                    level, finiteMounts[turret]);
            require(profile != null, "transition keeps actual CBC shot profile available");
            Vec3 actual = profile.currentForward().normalize();
            Vec3 plane = transitionSky ? null : finiteTargetPlaneImpact(profile, finiteTarget.z);
            double marker = transitionSky ? directionError(actual, transitionInputDirection)
                    : plane == null ? 180 : Math.toDegrees(Math.atan2(plane.subtract(finiteTarget).length(),
                    Math.max(1, profile.muzzle().distanceTo(finiteTarget))));
            double actualBaseline = transitionBarrelBaseline[turret] == null ? 0 : directionError(actual, transitionBarrelBaseline[turret]);
            if (age >= 980) {
                transitionMaxExpiredBarrelError = Math.max(transitionMaxExpiredBarrelError, actualBaseline);
                require(actualBaseline < 0.05,
                        "input expiry holds the physical barrel on first and subsequent physics steps: " + actualBaseline);
            }
            transitionDiagnostics.append(String.format(java.util.Locale.ROOT,
                    "%d,%.9f,%s,%d,%.0f,%s,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%s,%s,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                    age, dt, state, turret, transitionOperatorSpeed, transitionSky, marker, actualBaseline,
                    heldChange[2 * turret], heldChange[2 * turret + 1], heldStep[2 * turret], heldStep[2 * turret + 1],
                    active[2 * turret], active[2 * turret + 1], transitionCamera.x, transitionCamera.y, transitionCamera.z,
                    finiteTarget.x, finiteTarget.y, finiteTarget.z));
            if (zeroHold) {
                transitionMaxZeroHeldError = Math.max(transitionMaxZeroHeldError,
                        Math.max(heldChange[2 * turret], heldChange[2 * turret + 1]));
                transitionMaxZeroBarrelError = Math.max(transitionMaxZeroBarrelError, actualBaseline);
                require(heldChange[2 * turret] < 0.05 && heldChange[2 * turret + 1] < 0.05 && actualBaseline < 0.05,
                        state + " never bypasses operator speed on first or subsequent physics steps: turret " + turret
                                + " held " + heldChange[2 * turret] + "/" + heldChange[2 * turret + 1] + " barrel " + actualBaseline);
                if (age < 800) require(!active[2 * turret] && !active[2 * turret + 1],
                        state + " cannot activate finite tracking before new operator request is acquired");
            }
            if (rateLimited) {
                double excess = Math.max(heldStep[2 * turret], heldStep[2 * turret + 1]) - transitionOperatorSpeed * dt;
                transitionMaxSlewExcess = Math.max(transitionMaxSlewExcess, excess);
                require(excess < 0.05, state + " holds configured axis speed on every physics step: excess " + excess);
            }
            if (age >= 400 && age < 480) {
                transitionMaxCameraMarkerError = Math.max(transitionMaxCameraMarkerError, marker);
                require(active[2 * turret] && active[2 * turret + 1], "unchanged view ray retains finite compensation while camera moves");
                require(marker < 0.05, "same-view camera movement converges on its fixed-wall intersection on every physics step: " + marker);
            }
            if (age < 220 || age == 399 || age == 599 || age == 779 || age >= 960 && age < 980) {
                require(marker < 0.05, state + " reaches actual physical marker within strict threshold: " + marker);
            }
            if (age >= 780 && age < 800 && turret == 1) {
                transitionMaxSiblingMarkerError = Math.max(transitionMaxSiblingMarkerError, marker);
                require(marker < 0.05, "legal first-cannon unbind never changes sibling finite marker: " + marker);
            }
        }
        for (int index = 0; index < axes.length; index++) transitionPreviousHeld[index] = new Vector3d(held[index]);
        transitionMeasuredSteps++;
    }

    /** Native CBC mount attachment with an actual steel barrel, loaded breech and AP projectile. */
    private static BindingRef attachLoadedFiniteCannon(SwivelBearingBlockEntity pitch, int index) throws Exception {
        Direction facing = pitch.getBlockState().getValue(SwivelBearingBlock.FACING);
        BlockPos mountPos = pitch.getPlatePos().relative(facing);
        require(Sable.HELPER.getContaining(level, mountPos) == child(pitch), "CBC mount belongs to actual pitch child plot");
        var mountBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(mountPos, mountBlock.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        require(level.getBlockEntity(mountPos) instanceof CannonMountBlockEntity, "finite-point CBC mount exists");
        var mount = (CannonMountBlockEntity) level.getBlockEntity(mountPos);
        AbstractMountedCannonContraption contraption = FINITE_MEDIUM_CANNON
                ? new riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption()
                : new MountedAutocannonContraption();
        var orientation = AbstractMountedCannonContraption.class.getDeclaredField("initialOrientation");
        orientation.setAccessible(true);
        orientation.set(contraption, Direction.SOUTH);
        var start = AbstractMountedCannonContraption.class.getDeclaredField("startPos");
        start.setAccessible(true);
        start.set(contraption, BlockPos.ZERO);
        var material = contraption.getClass().getDeclaredField("cannonMaterial");
        material.setAccessible(true);
        material.set(contraption, FINITE_MEDIUM_CANNON
                ? Class.forName("riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials").getField("STEEL").get(null)
                : rbasamoyai.createbigcannons.index.CBCAutocannonMaterials.STEEL);
        var breechBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(FINITE_MEDIUM_CANNON
                ? "cbcmodernwarfare:steel_mediumcannon_breech" : "createbigcannons:steel_autocannon_breech"));
        var barrelBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(FINITE_MEDIUM_CANNON
                ? "cbcmodernwarfare:steel_mediumcannon_barrel" : "createbigcannons:steel_autocannon_barrel"));
        require(breechBlock instanceof net.minecraft.world.level.block.EntityBlock, "loaded CBC breech registry exists");
        var breechState = breechBlock.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.SOUTH);
        var breech = ((net.minecraft.world.level.block.EntityBlock) breechBlock).newBlockEntity(BlockPos.ZERO, breechState);
        breech.setLevel(level);
        if (FINITE_MEDIUM_CANNON) {
            require(breech instanceof riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity,
                    "actual medium cannon breech type");
            ((riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity)breech)
                    .setInputBuffer(com.hooya.stabilizedturret.registry.ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance());
        } else {
            require(breech instanceof rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity,
                    "loaded CBC breech type");
            ((rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity) breech)
                    .getInputBuffer().add(rbasamoyai.createbigcannons.index.CBCItems.AP_AUTOCANNON_ROUND.asStack());
        }
        contraption.presentBlockEntities.put(BlockPos.ZERO, breech);
        contraption.getBlocks().put(BlockPos.ZERO, new StructureBlockInfo(BlockPos.ZERO, breechState, null));
        for (int z = 1; z <= 3; z++) {
            BlockPos local = new BlockPos(0, 0, z);
            var barrelState = barrelBlock.defaultBlockState().setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.SOUTH);
            contraption.getBlocks().put(local, new StructureBlockInfo(local, barrelState, null));
        }
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 4);
        var cannon = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, mount);
        cannon.setPos(mountPos.getX() + 0.5, mountPos.getY() + 1, mountPos.getZ() + 0.5);
        // The inert test entity supplies CBC's unchanged geometry and loaded-round
        // analysis. It is never fired or spawned into a user world, and no profile
        // cache is injected. The real mount and physics bodies still tick normally.
        cannon.xOld = cannon.xo = cannon.getX();
        cannon.yOld = cannon.yo = cannon.getY();
        cannon.zOld = cannon.zo = cannon.getZ();
        mount.attach(cannon);
        mount.setYaw(Direction.SOUTH.toYRot());
        mount.setPitch(0);
        finiteCannons[index] = cannon;
        require(Sable.HELPER.getContaining(cannon) == child(pitch), "CBC firing geometry uses pitch child rigid-body velocity");
        BindingRef reference = BindingRef.capture(level, mountPos);
        var profile = CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level, reference), level, reference);
        require(profile != null && profile.muzzleSpeed() > 0 && profile.gravity() < 0
                        && profile.projectile().contains(FINITE_MEDIUM_CANNON ? "apfsds_medium_shell" : "autocannon"),
                "real loaded CBC profile with gravity is available: " + profile);
        System.out.println("finite-point loaded CBC " + index + ": " + profile);
        return reference;
    }

    private static Vec3 finiteBlockTarget(Vec3 approximate) {
        BlockPos block = BlockPos.containing(approximate);
        level.getChunk(block);
        level.setBlockAndUpdate(block, Blocks.STONE.defaultBlockState());
        // The camera request points at the centre of the near face, not inside terrain.
        return new Vec3(block.getX() + 0.5, block.getY() + 0.5, block.getZ());
    }

    private static Vec3 finiteGroundTarget(Vec3 approximate) {
        BlockPos centre = BlockPos.containing(approximate);
        // The empty dedicated fixture has no player keeping distant terrain loaded.
        for (int cx=-1;cx<=1;cx++) for (int cz=-1;cz<=1;cz++) {
            level.setChunkForced((centre.getX()>>4)+cx,(centre.getZ()>>4)+cz,true);
        }
        for (int x=-2;x<=2;x++) for (int z=-2;z<=2;z++) {
            BlockPos block=centre.offset(x,0,z);
            level.getChunk(block);
            level.setBlockAndUpdate(block,Blocks.STONE.defaultBlockState());
        }
        return new Vec3(centre.getX()+.5,centre.getY()+1.0,centre.getZ()+.5);
    }

    private static int finitePhase() {
        return age < FINITE_START ? 0 : (age - FINITE_START) / FINITE_PHASE_TICKS;
    }

    private static int finitePhaseElapsed() {
        return age < FINITE_START ? 0 : (age - FINITE_START) % FINITE_PHASE_TICKS;
    }

    private static boolean finiteSkyPhase(int phase) { return phase == 5 || phase == 6; }

    private static void steerFiniteCarrier(SubLevelPhysicsSystem system, double dt) {
        if (system.getLevel()==level && AIM_EDGE_CASES) finitePrediction=java.util.Map.of();
        if (system.getLevel() != level || carrierSteering == null || finished) return;
        if (FINITE_LIMIT_TRANSITIONS) {
            if (AIM_EDGE_CASES) {
                // Head slew acquisition has a supported carrier. Unlimited world
                // compensation must not be counted as operator traverse. The
                // support is removed for both real creative-staff grab phases.
                for (var axis:ConstraintJointAxis.ANGULAR)
                    carrierSteering.setMotor(axis,0,3e7,3e5,false,0);
                return;
            }
            if (age >= 420 && age < 500) finiteSupportGoal.z += 12 * dt;
            if (age >= 500 && age < 540) finiteSupportGoal.z -= 12 * dt;
            rebaseFiniteSupport();
            carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_X, 0, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_Z, 0, 3e7, 3e5, false, 0);
            Vector3d omega = system.getPipeline().getAngularVelocity(carrier,new Vector3d());
            system.getPipeline().addLinearAndAngularVelocity(carrier,new Vector3d(),new Vector3d(0,-omega.y,0));
            return;
        }
        if (FINITE_AIM_TRANSITIONS) {
            carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_X, 0, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_Z, 0, 3e7, 3e5, false, 0);
            Vector3d omega = system.getPipeline().getAngularVelocity(carrier, new Vector3d());
            system.getPipeline().addLinearAndAngularVelocity(carrier, new Vector3d(), new Vector3d(0, -omega.y, 0));
            return;
        }
        int phase = finitePhase(), elapsed = finitePhaseElapsed();
        if (phase >= FINITE_PHASE_NAMES.length) return;
        boolean moving = age >= FINITE_START && elapsed >= 100 && elapsed < 200;
        if (FINITE_TRANSLATION) finiteSupportGoal.z += (moving ? (phase % 2 == 0 ? 12 : -12) : 0) * dt;
        rebaseFiniteSupport();
        boolean pitching = phase == 3 || phase == 4 || phase == 6 || phase == 7;
        if (moving) finiteMotionTime += dt;
        double pitchTarget = moving && pitching ? 0.14 * Math.sin(finiteMotionTime * 1.7) : 0;
        carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_X, pitchTarget, 3e7, 3e5, false, 0);
        carrierSteering.setMotor(ConstraintJointAxis.ANGULAR_Z, 0, 3e7, 3e5, false, 0);
        Vector3d omega = system.getPipeline().getAngularVelocity(carrier, new Vector3d());
        system.getPipeline().addLinearAndAngularVelocity(carrier, new Vector3d(),
                new Vector3d(0, Math.toRadians(moving && (!FINITE_TRANSLATION || FINITE_COMBINED_MOTION)
                        ? FINITE_YAW_SPEEDS[phase] : 0) - omega.y, 0));
        system.getPipeline().wakeUp(carrier);
    }

    private static void measureFiniteCarrierAim(SubLevelPhysicsSystem system, double dt) {
        if (system.getLevel() != level || finiteController == null || finiteTarget == null || finished) return;
        if (FINITE_LIMIT_TRANSITIONS) {
            try { if (AIM_EDGE_CASES) measureAimEdgeCases(system,dt); else measureFiniteLimitTransitions(dt); }
            catch (Throwable error) {
                try { Files.writeString(Path.of("bearing-finite-limit-diagnostics.csv"),limitDiagnostics); }
                catch (Exception ignored) {}
                fail(error); level.getServer().halt(false);
            }
            return;
        }
        if (FINITE_AIM_TRANSITIONS) {
            try { measureFiniteAimTransitions(system, dt); }
            catch (Throwable error) { fail(error); level.getServer().halt(false); }
            return;
        }
        int phase = finitePhase(), elapsed = finitePhaseElapsed();
        if (phase >= FINITE_PHASE_NAMES.length) return;
        if (FINITE_TRANSLATION) updateFiniteMovingView();
        boolean moving = elapsed >= 100 && elapsed < 200, stopped = elapsed >= 200;
        try {
            boolean acquisitionDiagnostic = !moving && !stopped && age % 20 == 0;
            if (acquisitionDiagnostic || moving || stopped || elapsed == 99) {
                recordFiniteAimLocks(dt, phase, moving ? "moving" : stopped ? "stopped" : "acquiring");
            }
            if (!moving && !stopped && elapsed != 99 && !acquisitionDiagnostic) return;
            Vector3d omega = system.getPipeline().getAngularVelocity(carrier, new Vector3d());
            if (moving) finiteCarrierTravel[phase] += (FINITE_TRANSLATION
                    ? system.getPipeline().getLinearVelocity(carrier,new Vector3d()).length() : omega.length()) * dt;
            if (moving) finiteYawTravel[phase] += Math.abs(omega.y)*dt;
            ServerSubLevel[] bodies = isolatedStaffBodies();
            String[] names = {"carrier", "yaw1", "pitch1", "yaw2", "pitch2"};
            for (int index = 0; index < bodies.length; index++) {
                Vector3d bodyOmega = system.getPipeline().getAngularVelocity(bodies[index], new Vector3d());
                if (FINITE_WELDED_TURRET && (age >= 80 && age <= 85 || age >= 180 && age <= 205)
                        && finitePrediction.containsKey(bodies[index].getUniqueId())) {
                    var predicted = finitePrediction.get(bodies[index].getUniqueId());
                    Quaterniond delta=predicted.orientation().conjugate().mul(bodies[index].logicalPose().orientation());
                    System.out.printf(java.util.Locale.ROOT,"weld-prediction age %d body %s pose %.9f position %.9f angular %.9f%n",
                            age,names[index],Math.toDegrees(2*Math.atan2(Math.sqrt(delta.x*delta.x+delta.y*delta.y+delta.z*delta.z),Math.abs(delta.w))),
                            predicted.position().distance(bodies[index].logicalPose().position()),
                            predicted.angularVelocity().distance(bodyOmega));
                }
                require(Double.isFinite(bodyOmega.length()) && bodyOmega.length() < 12,
                        "finite-point body remains bounded at age " + age + " phase " + phase
                                + " " + names[index] + " (" + bodies[index].getUniqueId() + ") omega " + bodyOmega
                                + " length " + bodyOmega.length());
                if (stopped && elapsed == 200 && finitePrediction.containsKey(bodies[index].getUniqueId())) {
                    var predicted = finitePrediction.get(bodies[index].getUniqueId());
                    Quaterniond delta = predicted.orientation().conjugate().mul(bodies[index].logicalPose().orientation()).normalize();
                    System.out.printf(java.util.Locale.ROOT,
                            "stop-prediction age %d body %s pose %.9f velocity %.9f angular %.9f%n", age, names[index],
                            Math.toDegrees(2*Math.atan2(Math.sqrt(delta.x*delta.x+delta.y*delta.y+delta.z*delta.z),Math.abs(delta.w))),
                            predicted.linearVelocity().distance(system.getPipeline().getLinearVelocity(bodies[index],new Vector3d())),
                            predicted.angularVelocity().distance(bodyOmega));
                }
            }
            for (int turret = 0; turret < finiteMounts.length; turret++) {
                var profile = CannonBallisticsCompat.withCurrentFrame(
                        CannonBallisticsCompat.analyze(level, finiteMounts[turret]), level, finiteMounts[turret]);
                require(profile != null && profile.muzzleSpeed() > 0, "loaded CBC profile retained while moving");
                Vec3 expected = finiteSkyPhase(phase) ? FINITE_SKY_DIRECTION
                        : CannonBallisticsCompat.solveLaunchDirection(profile, finiteTarget);
                require(expected != null, "finite-point actual firing profile has a reachable target");
                Vec3 actual = profile.currentForward().normalize();
                if (FINITE_TRANSLATION && (moving || stopped || elapsed==99)) {
                    UUID muzzleBody=Sable.HELPER.getContaining(finiteCannons[turret]).getUniqueId();
                    var group=finiteController.getMeasuredAimGroups().stream()
                            .filter(value->value.frame()!=null && value.frame().bodyId().equals(muzzleBody))
                            .findFirst().orElseThrow(()->new AssertionError("missing turret muzzle identity"));
                    require(group.frame()!=null,"production packet retains actual per-turret muzzle body");
                    SubLevel body=SubLevelContainer.getContainer(level).getSubLevel(group.frame().bodyId());
                    var launch=com.hooya.stabilizedturret.client.CannonRenderFrame.from(
                            group.frame(),body.logicalPose(),profile.carrierVelocity());
                    require(launch.origin().distanceTo(profile.muzzle())<1e-6
                                    && directionError(launch.direction(),actual)<1e-5,
                            "render metadata reconstructs the current actual muzzle, including sky mode: age "+age
                                    +" phase "+phase+" turret "+turret+" point "+launch.origin().distanceTo(profile.muzzle())
                                    +" ray "+directionError(launch.direction(),actual)+" local "+group.frame()
                                    +" actual "+profile);
                    if (!finiteSkyPhase(phase)) {
                        Set<UUID> ignored=java.util.Arrays.stream(bodies).map(ServerSubLevel::getUniqueId).collect(Collectors.toSet());
                        Vec3 impact=CannonBallisticsCompat.predictImpact(level,
                                CannonBallisticsCompat.withFrame(profile,launch.origin(),launch.direction(),
                                        launch.velocity().subtract(launch.direction().scale(group.frame().muzzleSpeed()))),ignored);
                        require(impact!=null,"rendered production muzzle has a real terrain impact: age " + age
                                + " phase " + phase + " turret " + turret + " target " + finiteTarget
                                + " profile " + profile);
                        double error=directionError(impact.subtract(finiteCameraOrigin),finiteMovingViewDirection);
                        finiteRenderedMarkerPeak=Math.max(finiteRenderedMarkerPeak,error);
                        if (moving && group.ballistic()!=null && group.ballistic().impactAvailable()) {
                            var b=group.ballistic();
                            finiteStalePacketPeak=Math.max(finiteStalePacketPeak,directionError(
                                    new Vec3(b.impactX(),b.impactY(),b.impactZ()).subtract(finiteCameraOrigin),finiteMovingViewDirection));
                        }
                    }
                }
                double launchError = directionError(actual, expected);
                double markerError, horizontalError, verticalError;
                if (finiteSkyPhase(phase)) {
                    markerError = launchError;
                    horizontalError = Math.abs(Math.toDegrees(Math.atan2(
                            actual.x * expected.z - actual.z * expected.x,
                            actual.x * expected.x + actual.z * expected.z)));
                    verticalError = Math.abs(Math.toDegrees(Math.asin(Math.clamp(actual.y, -1, 1))
                            - Math.asin(Math.clamp(expected.y, -1, 1))));
                } else {
                    Vec3 projected = FINITE_TRANSLATION
                            ? finiteTargetPlaneImpactX(profile,finiteTarget.x)
                            : finiteTargetPlaneImpact(profile, finiteTarget.z);
                    require(projected != null, "actual CBC trajectory crosses finite target plane");
                    Vec3 error = projected.subtract(finiteTarget);
                    double range = Math.max(1.0, profile.muzzle().distanceTo(finiteTarget));
                    horizontalError = Math.toDegrees(Math.atan2(Math.abs(FINITE_TRANSLATION ? error.z : error.x), range));
                    verticalError = Math.toDegrees(Math.atan2(Math.abs(error.y), range));
                    double lateral=FINITE_TRANSLATION ? error.z : error.x;
                    markerError = Math.toDegrees(Math.atan2(Math.sqrt(lateral * lateral + error.y * error.y), range));
                    if (FINITE_GROUND_TARGET && (moving || stopped || elapsed == 99)) {
                        Set<UUID> ignored = java.util.Arrays.stream(bodies).map(ServerSubLevel::getUniqueId)
                                .collect(Collectors.toSet());
                        Vec3 impact = CannonBallisticsCompat.predictImpact(level, profile, ignored);
                        require(impact != null, "real terrain collision marker exists at age " + age + " turret " + turret
                                + " target " + finiteTarget + " profile " + profile);
                        double impactError = directionError(impact.subtract(finiteCameraOrigin), finiteTarget.subtract(finiteCameraOrigin));
                        finiteActualImpactPeak = Math.max(finiteActualImpactPeak, impactError);
                        markerError = Math.max(markerError, impactError);
                    }
                }
                if (moving) {
                    finiteAimSamples[phase][turret]++;
                    finiteAimPeaks[phase][turret][0] = Math.max(finiteAimPeaks[phase][turret][0], markerError);
                    finiteAimPeaks[phase][turret][1] = Math.max(finiteAimPeaks[phase][turret][1], horizontalError);
                    finiteAimPeaks[phase][turret][2] = Math.max(finiteAimPeaks[phase][turret][2], verticalError);
                } else if (stopped) {
                    finiteAimStopPeaks[phase][turret] = Math.max(finiteAimStopPeaks[phase][turret], markerError);
                } else if (elapsed == 99) {
                    finiteAimSettled[phase][turret] = Math.max(finiteAimSettled[phase][turret], markerError);
                }
                Vec3 baseVelocity = CannonBallisticsCompat.sampleBodyVelocity(level, carrier, profile.muzzle());
                Vec3 p = profile.muzzle(), v = profile.carrierVelocity();
                finiteAimDiagnostics.append(String.format(java.util.Locale.ROOT,
                        "%d,%.9f,%d,%s,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                        age, dt, phase, finiteSkyPhase(phase) ? "sky" : "finite", turret, markerError,
                        horizontalError, verticalError, launchError, p.x, p.y, p.z, v.x, v.y, v.z,
                        baseVelocity.x, baseVelocity.y, baseVelocity.z, omega.x, omega.y, omega.z,
                        finiteTarget.x, finiteTarget.y, finiteTarget.z));
                if (age % 20 == 0) System.out.printf(java.util.Locale.ROOT,
                        "finite-point age %d phase %d turret %d marker %.9f launch %.9f actual-v %s carrier-v %s%n",
                        age, phase, turret, markerError, launchError, v, baseVelocity);
            }
        } catch (Throwable error) { fail(error); level.getServer().halt(false); }
    }

    private static void updateFiniteMovingView() {
        Vector3d camera = finiteCameraBody.logicalPose().transformPosition(new Vector3d(finiteCameraLocal));
        finiteCameraOrigin = new Vec3(camera.x,camera.y,camera.z);
        double distance = (finiteNearTarget.y-finiteCameraOrigin.y)/finiteMovingViewDirection.y;
        finiteTarget = finiteCameraOrigin.add(finiteMovingViewDirection.scale(distance));
    }

    private static void recordFiniteAimLocks(double dt, int phase, String state) throws Exception {
        var activeField = BearingStabilizer.class.getDeclaredField("ACTIVE");
        activeField.setAccessible(true);
        java.util.Map<?, ?> active = (java.util.Map<?, ?>) activeField.get(null);
        var planningField = BearingStabilizer.class.getDeclaredField("SERVO_PLANNING_RESULTS");
        planningField.setAccessible(true);
        String planning = String.valueOf(planningField.get(null)).replace(',', ';');
        SwivelBearingBlockEntity[] bearings = {movingYaw, movingPitch, staffSiblingYaw, staffSiblingPitch};
        String[] labels = {"yaw1", "pitch1", "yaw2", "pitch2"};
        for (int index = 0; index < bearings.length; index++) {
            Object lock = active.get(bearings[index]);
            require(lock != null, "finite-point active lock retained: " + labels[index] + " age " + age);
            Vector3d requested = new Vector3d((Vector3d) readTurnSnapshotField(lock, "requestedAimDirection"));
            Vector3d held = new Vector3d((Vector3d) readTurnSnapshotField(lock, "heldAimDirection"));
            if (index == 0 || index == 2) {
                Vector3d frame = new Vector3d((Vector3d)readTurnSnapshotField(lock,"aimLocalForward"));
                Vector3d baseline = finiteAzimuthFrames.putIfAbsent(bearings[index].getSubLevelID(),frame);
                if (baseline != null) {
                    finiteAzimuthFrameChange = Math.max(finiteAzimuthFrameChange,baseline.distance(frame));
                    require(baseline.distance(frame) < 1e-12,
                            "elevation motion and head-aim mode changes do not rewrite azimuth's local frame");
                }
            }
            Object motion = readTurnSnapshotField(lock, "operatorMotion");
            double rate = ((Number) readTurnSnapshotField(motion, "velocity")).doubleValue();
            java.util.List<?> targets = (java.util.List<?>) readTurnSnapshotField(lock, "finiteTargets");
            if (finitePhaseElapsed() == 200 && (index == 0 || index == 2)) {
                var yawPrediction = finitePrediction.get(bearings[index].getSubLevelID());
                var barrelBody = child(bearings[index+1]);
                var barrelPrediction = finitePrediction.get(barrelBody.getUniqueId());
                var parentPrediction = finitePrediction.get(carrier.getUniqueId());
                if (yawPrediction != null && barrelPrediction != null && parentPrediction != null) {
                    Vector3d axis = parentPrediction.orientation().transform(new Vector3d(0,1,0));
                    Vector3d ownRay = yawPrediction.orientation().transform(new Vector3d((Vector3d)readTurnSnapshotField(lock,"aimLocalForward")));
                    Object pitchLock = active.get(bearings[index+1]);
                    Vector3d barrelRay = barrelPrediction.orientation().transform(new Vector3d((Vector3d)readTurnSnapshotField(pitchLock,"aimLocalForward")));
                    Vector3d desired = targets.isEmpty() ? held : ((FiniteAimTarget)targets.getFirst())
                            .predictedDirection(barrelPrediction,new Vector3d(),new Vector3d(barrelBody.getMassTracker().getCenterOfMass()));
                    System.out.printf(java.util.Locale.ROOT,"stop-ray age %d axis %s own %.9f barrel %.9f%n",age,labels[index],
                            Math.toDegrees(BearingServoMath.signedDirectionAngle(ownRay,desired,axis)),
                            Math.toDegrees(BearingServoMath.signedDirectionAngle(barrelRay,desired,axis)));
                }
            }
            Object servoStep = readTurnSnapshotField(lock, "servoStep");
            boolean ready = servoStep != null && (boolean) readTurnSnapshotField(servoStep, "modelReady");
            boolean eligible = servoStep != null && (boolean) readTurnSnapshotField(servoStep, "eligible");
            double motorTarget = ((Number) readTurnSnapshotField(readTurnSnapshotField(lock, "motorTarget"), "previous")).doubleValue();
            double motorResidual = ((Number) readTurnSnapshotField(readTurnSnapshotField(lock, "motorResidual"), "correction")).doubleValue();
            finiteAimLockDiagnostics.append(String.format(java.util.Locale.ROOT,
                    "%d,%.9f,%d,%s,%s,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.9f,%.12f,%d,%s,%.12f,%.12f,%s,%s,%s%n",
                    age, dt, phase, state, labels[index], requested.x, requested.y, requested.z,
                    held.x, held.y, held.z, rate,
                    ((Number) readTurnSnapshotField(lock, "maxDegreesPerSecond")).doubleValue(),
                    ((Number) readTurnSnapshotField(lock, "aimLandingRemaining")).doubleValue(), targets.size(),
                    readTurnSnapshotField(lock, "finiteCompensationActive"), motorTarget, motorResidual, ready, eligible, planning));
        }
    }

    private static double directionError(Vec3 actual, Vec3 expected) {
        return Math.toDegrees(Math.acos(Math.clamp(actual.normalize().dot(expected.normalize()), -1, 1)));
    }

    /** Same CBC swept timestep as firing/markers, evaluated at the requested block-face plane. */
    private static Vec3 finiteTargetPlaneImpact(CannonBallisticsCompat.ShotProfile profile, double targetZ) {
        return CannonProjectileFlight.trace(profile.muzzle(),
                profile.currentForward().scale(profile.muzzleSpeed()).add(profile.carrierVelocity()),
                profile.gravity(), profile.drag(), profile.quadraticDrag(), profile.maxFlightTicks(), -64,
                (from, to) -> {
                    double dz = to.z - from.z;
                    if (Math.abs(dz) < 1e-12) return null;
                    double fraction = (targetZ - from.z) / dz;
                    return fraction >= 0 && fraction <= 1 ? from.lerp(to, fraction) : null;
                });
    }

    private static Vec3 finiteTargetPlaneImpactX(CannonBallisticsCompat.ShotProfile profile,double targetX) {
        return CannonProjectileFlight.trace(profile.muzzle(),
                profile.currentForward().scale(profile.muzzleSpeed()).add(profile.carrierVelocity()),
                profile.gravity(),profile.drag(),profile.quadraticDrag(),profile.maxFlightTicks(),-64,
                (from,to)->{
                    double dx=to.x-from.x;
                    if (Math.abs(dx)<1e-12) return null;
                    double f=(targetX-from.x)/dx;
                    return f>=0 && f<=1 ? from.lerp(to,f) : null;
                });
    }

    private static void finishFiniteCarrierAim(ServerTickEvent.Pre event) throws Exception {
        boolean passed = true;
        StringBuilder report = new StringBuilder(
                "Production controller + native two-turret fixture; actual CBC steel geometry and AP breech input.\n"
                        + "Production ammunition analysis may select its fallback profile; exact projectile/profile is logged at assembly.\n"
                        + "Every moving physics step is sampled, including startup; head speed = "
                        + (FINITE_PRODUCTION_INPUT ? "30" : "0") + " after stationary acquisition.\n"
                        + "Marker is actual inherited-velocity CBC swept trajectory at target block-face plane; sky uses actual barrel.\n"
                        + "Strict threshold < 0.05 degrees for settled, moving and stopped phases; no characterization bypass.\n");
        report.append("World support order: ").append(FINITE_SUPPORT_BEFORE_HINGES ? "original before/mixed hinge order" : "after owned hinges")
                .append("; same anchors, motor gains and target poses.\n");
        if (FINITE_PRODUCTION_INPUT) report.append("Actual controller block ticks through Sable actors; head speed remains 30 degrees/second during compensation.\n");
        if (FINITE_PRODUCTION_INPUT && FINITE_TRANSLATION) report.append("Actual configured sight rides primary turret; each physics sample resamples its moving block-face target.\n");
        if (FINITE_WELDED_CARRIER) report.append("Five additional native fixed-weld bodies remain attached to the carrier throughout all phases.\n");
        if (FINITE_WELDED_TURRET) report.append("Second turret sits on a distinct fixed-weld member; both retain independent yaw/pitch actuators.\n");
        if (FINITE_COMBINED_MOTION) report.append("Actual translation plus yaw rotation, with pitch in the designated phases.\n");
        report.append("Physical support frame: ").append(FINITE_REBASE_SUPPORT
                ? "generic free joint; world frame rebased to current yaw each physics step, with unchanged world linear goals and yaw-relative pitch/roll motors"
                : "original identity world frame; angular X/Z motors with free yaw").append(".\n");
        if (FINITE_GROUND_TARGET) report.append(String.format(java.util.Locale.ROOT,
                "Production terrain collision marker maximum %.9f degrees.\n", finiteActualImpactPeak));
        if (FINITE_TRANSLATION) report.append(String.format(java.util.Locale.ROOT,
                "Side-facing 53 m sight; real forward/reverse translation. Render-frame actual collision %.9f; stale packet collision %.9f degrees.\n",
                finiteRenderedMarkerPeak,finiteStalePacketPeak));
        report.append(String.format(java.util.Locale.ROOT,
                "Independent azimuth local frame maximum change %.12f.\n",finiteAzimuthFrameChange));
        for (int phase = 0; phase < FINITE_PHASE_NAMES.length; phase++) {
            passed &= finiteCarrierTravel[phase] > 0.1;
            if (FINITE_COMBINED_MOTION && FINITE_YAW_SPEEDS[phase] != 0) passed &= finiteYawTravel[phase] > 1;
            if (FINITE_COMBINED_MOTION) report.append(String.format(java.util.Locale.ROOT,
                    "Phase %d measured yaw travel %.6f radians.\n",phase,finiteYawTravel[phase]));
            for (int turret = 0; turret < 2; turret++) {
                double[] peak = finiteAimPeaks[phase][turret];
                passed &= finiteAimSamples[phase][turret] > 100 && finiteAimSettled[phase][turret] < 0.05
                        && peak[0] < 0.05 && finiteAimStopPeaks[phase][turret] < 0.05;
                report.append(String.format(java.util.Locale.ROOT,
                        "%d %s turret %d: settled %.9f; moving marker %.9f (horizontal %.9f, vertical %.9f); stopped %.9f degrees; %d physics samples; carrier travel %.6f %s%n",
                        phase, FINITE_PHASE_NAMES[phase], turret, finiteAimSettled[phase][turret],
                        peak[0], peak[1], peak[2], finiteAimStopPeaks[phase][turret],
                        finiteAimSamples[phase][turret], finiteCarrierTravel[phase],FINITE_TRANSLATION ? "blocks" : "radians"));
            }
        }
        Files.writeString(Path.of("bearing-finite-carrier-aim-result.txt"), (passed ? "PASS\n" : "FAIL\n") + report);
        Files.writeString(Path.of("bearing-finite-carrier-aim-diagnostics.csv"), finiteAimDiagnostics);
        Files.writeString(Path.of("bearing-finite-carrier-lock-diagnostics.csv"), finiteAimLockDiagnostics);
        System.out.println(report);
        require(passed, "actual finite-range and sky markers remain stable throughout carrier yaw/pitch motion");
        Files.writeString(Path.of("bearing-integration-result.txt"), "PASS");
        writeServoPlanningDiagnostics();
        finished = true;
        event.getServer().halt(false);
    }

    /** Independent comparison: no preceding turret grab, free tumble or continuous-turn phase. */
    private static void forceLoadFixtureBodies() {
        // This headless world has no real player retaining secondary child plots.
        var fixtureContainer=(dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)SubLevelContainer.getContainer(level);
        for (SubLevel body:fixtureContainer.getAllSubLevels()) if (body instanceof ServerSubLevel serverBody && !body.isRemoved())
            fixtureContainer.addForceLoadTicket(serverBody,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                    net.minecraft.util.Unit.INSTANCE);
    }

    private static void tickIsolatedCarrierStaff(ServerTickEvent.Pre event) throws Exception {
        if (age == 20) {
            BlockPos carrierBlock = new BlockPos(40, 170, 0);
            level.getChunk(carrierBlock);
            level.setBlockAndUpdate(carrierBlock, Blocks.STONE.defaultBlockState());
            var assembled = SimAssemblyHelper.assembleFromSingleBlock(level, carrierBlock.below(), carrierBlock, false, false);
            require(assembled != null, "isolated carrier assembly");
            carrier = (ServerSubLevel) assembled.subLevel();
            ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer) SubLevelContainer.getContainer(level))
                    .addForceLoadTicket(carrier,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                            net.minecraft.util.Unit.INSTANCE);
            movingYaw = assemble(carrierBlock.offset(assembled.offset()).above(2));
        }
        if (age == 21) {
            if (child(movingYaw)==null) {
                require(++movingStartupWaits<40,"isolated parent registers before pitch assembly");
                age--;
                return;
            }
            movingPitch=assemblePitch(movingYaw,Direction.EAST);
            Vector3d anchor = new Vector3d(carrier.getMassTracker().getCenterOfMass());
            Vector3d world = carrier.logicalPose().transformPosition(new Vector3d(anchor));
            carrierSteering = SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null, carrier,
                    new FreeConstraintConfiguration(new Vector3d(), anchor, new Quaterniond()));
            for (ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) carrierSteering.setMotor(axis, 0, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_X, world.x, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Y, world.y, 3e7, 3e5, false, 0);
            carrierSteering.setMotor(ConstraintJointAxis.LINEAR_Z, world.z, 3e7, 3e5, false, 0);
        }
        // Install the sibling after the first assembly has been registered and bound.
        // Assembling every nested structure in one tick can leave a replaced block entity.
        if (age == 60) {
            staffSiblingYaw = assemble(movingYaw.getBlockPos().offset(-4, 0, 0));
        }
        if (age == 61) staffSiblingPitch=assemblePitch(staffSiblingYaw,Direction.WEST);
        if (age >= 21) {
            commandIsolatedWorldRay(movingYaw, movingPitch, 30, 10);
        }
        if (age >= 61) {
            commandIsolatedWorldRay(staffSiblingYaw, staffSiblingPitch, -35, -5);
        }
        if (age == 200) {
            writeServoPlanningDiagnostics();
            recordTurnInitialState();
            require(isolatedStaffPeaks[0][0] < 0.05 && isolatedStaffPeaks[0][1] < 0.05,
                    "isolated fixture starts with both world rays settled");
            recordIsolatedStaffState("before grab");
            for (ServerSubLevel body : isolatedStaffBodies()) {
                var pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
                require(pipeline.getAngularVelocity(body, new Vector3d()).length() < 0.001,
                        "isolated fixture has settled angular momentum before grabbing");
                require(pipeline.getLinearVelocity(body, new Vector3d()).length() < 0.001,
                        "isolated fixture has settled linear momentum before grabbing");
            }
            carrierSteering.remove();
            carrierSteering = null;
            startRealStaffDrag(carrier);
            // A real player retains the session by holding the staff between game ticks.
            staffPlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    dev.simulated_team.simulated.index.SimItems.PHYSICS_STAFF.asStack());
        }
        if (age >= 200 && age < 340) {
            double time = (age - 200) * 0.05;
            Vector3d goal = new Vector3d(staffWorldGoal).add(1.5 * Math.sin(time), 0.4 * Math.sin(time * 0.7), 0);
            Quaterniond orientation = new Quaterniond().rotationY(0.35 * Math.sin(time * 2.4))
                    .rotateX(0.14 * Math.sin(time * 1.7)).rotateZ(0.11 * Math.sin(time * 1.3)).mul(staffOrientation);
            Vector3d eye = new Vector3d(staffPlayer.getX(), staffPlayer.getEyeY(), staffPlayer.getZ());
            PhysicsStaffServerHandler.get(level).drag(staffPlayer.getUUID(), carrier.getUniqueId(),
                    goal.sub(eye), staffAnchor, orientation);
        }
        if (age == 340) {
            PhysicsStaffServerHandler.get(level).stopDragging(staffPlayer.getUUID());
            require(SimulatedPhysicsStaffCompat.draggedBodies(level).isEmpty(), "isolated carrier staff releases ownership");
            recordIsolatedStaffState("release");
        }
        if (age == 420) {
            recordIsolatedStaffState("recovered");
            double firstFinal = worldRayError(movingPitch, 30, 10), secondFinal = worldRayError(staffSiblingPitch, -35, -5);
            require(firstFinal < 0.05 && secondFinal < 0.05, "isolated carrier staff release recovers both world rays");
            require(isolatedStaffTravel > 1, "isolated real carrier staff actually rotates the carrier");
            String[] phases = {"before grab", "during grab", "after release"};
            boolean passed = true;
            for (int phase = 0; phase < phases.length; phase++) {
                String line = String.format(java.util.Locale.ROOT, "%s: %.9f / %.9f degrees%n",
                        phases[phase], isolatedStaffPeaks[phase][0], isolatedStaffPeaks[phase][1]);
                isolatedStaffReport.append(line);
                System.out.print("isolated carrier staff " + line);
                passed &= isolatedStaffPeaks[phase][0] < 0.05 && isolatedStaffPeaks[phase][1] < 0.05;
            }
            Files.writeString(Path.of("bearing-isolated-staff-accuracy-result.txt"),
                    (passed ? "PASS\n" : "FAIL: threshold < 0.05 degrees\n") + isolatedStaffReport);
            if (!Boolean.getBoolean("create_fire_control.characterizeCarrierHold")) {
                require(passed, "isolated carrier staff holds both world rays throughout grab and release");
            }
            Files.writeString(Path.of("bearing-integration-result.txt"), "PASS");
            writeServoPlanningDiagnostics();
            finished = true;
            event.getServer().halt(false);
        }
    }

    private static void writeServoPlanningDiagnostics() throws Exception {
        var counts = BearingStabilizer.class.getDeclaredField("SERVO_PLANNING_RESULTS");
        counts.setAccessible(true);
        var details = SableServoConstraints.class.getDeclaredField("REJECTION_DETAILS");
        details.setAccessible(true);
        Files.writeString(Path.of("bearing-servo-planning-result.txt"), counts.get(null) + "\n" + details.get(null));
    }

    private static void commandIsolatedWorldRay(SwivelBearingBlockEntity yaw, SwivelBearingBlockEntity pitch,
                                                 double yawDegrees, double elevationDegrees) {
        requireLiveIsolatedBearing(yaw);
        requireLiveIsolatedBearing(pitch);
        Vector3d actual = child(pitch).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        Vector3d target = new Quaterniond().rotationY(Math.toRadians(yawDegrees)).rotateX(Math.toRadians(-elevationDegrees))
                .transform(new Vector3d(0, 0, 1));
        Vec3 from = new Vec3(actual.x, actual.y, actual.z), to = new Vec3(target.x, target.y, target.z);
        double speed = age < 160 ? 90 : 0;
        BearingStabilizer.commandAim(yaw, from, to, true, speed, 90, 90, Vec3.ZERO);
        BearingStabilizer.commandAim(pitch, from, to, false, speed, 90, 90, Vec3.ZERO);
        observeFixtureAzimuth(yaw,pitch,from);
    }

    private static void observeFixtureAzimuth(SwivelBearingBlockEntity yaw,SwivelBearingBlockEntity pitch,Vec3 ray) {
        BearingStabilizer.maintainAzimuthObservations(yaw,java.util.List.of(
                new BearingStabilizer.AzimuthObservation(pitch.getSubLevelID(),ray)));
    }

    private static void requireLiveIsolatedBearing(SwivelBearingBlockEntity bearing) {
        var actual = level.getBlockEntity(bearing.getBlockPos());
        require(!bearing.isRemoved() && actual == bearing,
                "isolated aim uses live bearing at age " + age + " " + bearing.getBlockPos()
                        + ", removed=" + bearing.isRemoved() + ", current=" + actual
                        + ", held=" + bearing + ", subLevel=" + bearing.getSubLevelID());
    }

    private static ServerSubLevel[] isolatedStaffBodies() {
        return new ServerSubLevel[]{carrier, child(movingYaw), child(movingPitch), child(staffSiblingYaw), child(staffSiblingPitch)};
    }

    private static void measureIsolatedCarrierStaff(SubLevelPhysicsSystem system, double dt) {
        if (system.getLevel() != level || age < 160 || age >= 420 || finished) return;
        try {
            for (ServerSubLevel body : isolatedStaffBodies()) {
                double speed = system.getPipeline().getAngularVelocity(body, new Vector3d()).length();
                require(Double.isFinite(speed) && speed < 8, "isolated carrier staff body angular speed remains bounded");
            }
            for (SwivelBearingBlockEntity bearing : new SwivelBearingBlockEntity[]{movingYaw, movingPitch, staffSiblingYaw, staffSiblingPitch}) {
                require(BearingStabilizer.ownsJoint(bearing) && handle(bearing).isValid(),
                        "isolated carrier staff retains every native hinge at age " + age + " " + bearing.getBlockPos()
                                + ", assembled=" + bearing.isAssembled() + ", bound=" + BearingStabilizer.isBound(bearing)
                                + ", active=" + BearingStabilizer.isActive(bearing) + ", handle=" + handle(bearing));
                require(!BearingStabilizer.yieldingToStaff(bearing), "isolated carrier grab never suspends turret stabilization");
            }
            int phase = age < 200 ? 0 : age < 340 ? 1 : 2;
            if (phase == 1) {
                require(SimulatedPhysicsStaffCompat.draggedBodies(level).contains(carrier.getUniqueId()),
                        "isolated real carrier staff session exists on every physics substep");
                isolatedStaffTravel += system.getPipeline().getAngularVelocity(carrier, new Vector3d()).length() * dt;
            }
            double first = worldRayError(movingPitch, 30, 10), second = worldRayError(staffSiblingPitch, -35, -5);
            if (first > isolatedStaffPeaks[phase][0] || second > isolatedStaffPeaks[phase][1]) {
                System.out.printf(java.util.Locale.ROOT, "isolated carrier staff age %d phase %d rays %.9f/%.9f pitch %.9f/%.9f%n",
                        age, phase, first, second, carrierElevation(30, 10), carrierElevation(-35, -5));
            }
            isolatedStaffPeaks[phase][0] = Math.max(isolatedStaffPeaks[phase][0], first);
            isolatedStaffPeaks[phase][1] = Math.max(isolatedStaffPeaks[phase][1], second);
        } catch (Throwable error) { fail(error); level.getServer().halt(false); }
    }

    private static void recordIsolatedStaffState(String phase) throws java.io.IOException {
        var bodies = isolatedStaffBodies();
        var pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
        for (int i = 0; i < bodies.length; i++) {
            var body = bodies[i];
            var p = body.logicalPose().position();
            var q = body.logicalPose().orientation();
            var v = pipeline.getLinearVelocity(body, new Vector3d());
            var w = pipeline.getAngularVelocity(body, new Vector3d());
            String line = String.format(java.util.Locale.ROOT,
                    "%s body %d position %.12f %.12f %.12f orientation %.12f %.12f %.12f %.12f velocity %.12f %.12f %.12f omega %.12f %.12f %.12f%n",
                    phase, i, p.x(), p.y(), p.z(), q.x(), q.y(), q.z(), q.w(), v.x, v.y, v.z, w.x, w.y, w.z);
            isolatedStaffReport.append(line);
            System.out.print("isolated carrier staff " + line);
        }
        Files.writeString(Path.of("bearing-isolated-staff-state.txt"), isolatedStaffReport);
    }

    /** Read-only native-reproduction snapshot before the first continuous-turn physics step. */
    private static void recordTurnInitialState() throws java.io.IOException, ReflectiveOperationException {
        StringBuilder snapshot = new StringBuilder();
        snapshot.append("capture=ServerTickEvent.Pre before sustained-turn steering\n")
                .append("age=").append(age).append('\n')
                .append("dimension=").append(level.dimension().location()).append('\n')
                .append("body_order=carrier,yaw1,pitch1,yaw2,pitch2\n")
                .append("inertia_order=m00,m01,m02,m10,m11,m12,m20,m21,m22 (JOML names)\n");
        appendTurnSnapshotNumbers(snapshot, "dimension.universal_drag", DimensionPhysicsData.getUniversalDrag(level));
        Vector3d gravity = DimensionPhysicsData.getGravity(level, carrier.logicalPose().position());
        appendTurnSnapshotNumbers(snapshot, "dimension.gravity", gravity.x, gravity.y, gravity.z);
        String[] bodyNames = {"carrier", "yaw1", "pitch1", "yaw2", "pitch2"};
        ServerSubLevel[] bodies = {carrier, child(movingYaw), child(movingPitch), child(staffSiblingYaw), child(staffSiblingPitch)};
        var pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
        for (int i = 0; i < bodies.length; i++) {
            ServerSubLevel body = bodies[i];
            String prefix = "body." + bodyNames[i];
            var mass = body.getMassTracker();
            var center = mass.getCenterOfMass();
            var inertia = mass.getInertiaTensor();
            var pose = body.logicalPose();
            var position = pose.position();
            var orientation = pose.orientation();
            var rotationPoint = pose.rotationPoint();
            Vector3d worldCenter = pose.transformPosition(new Vector3d(center));
            Vector3d velocity = pipeline.getLinearVelocity(body, new Vector3d());
            Vector3d omega = pipeline.getAngularVelocity(body, new Vector3d());
            snapshot.append(prefix).append(".uuid=").append(body.getUniqueId()).append('\n');
            appendTurnSnapshotNumbers(snapshot, prefix + ".mass", mass.getMass());
            appendTurnSnapshotNumbers(snapshot, prefix + ".com_local", center.x(), center.y(), center.z());
            appendTurnSnapshotNumbers(snapshot, prefix + ".com_world", worldCenter.x, worldCenter.y, worldCenter.z);
            appendTurnSnapshotNumbers(snapshot, prefix + ".inertia_local", inertia.m00(), inertia.m01(), inertia.m02(),
                    inertia.m10(), inertia.m11(), inertia.m12(), inertia.m20(), inertia.m21(), inertia.m22());
            appendTurnSnapshotNumbers(snapshot, prefix + ".position", position.x(), position.y(), position.z());
            appendTurnSnapshotNumbers(snapshot, prefix + ".orientation_xyzw", orientation.x(), orientation.y(), orientation.z(), orientation.w());
            appendTurnSnapshotNumbers(snapshot, prefix + ".rotation_point", rotationPoint.x(), rotationPoint.y(), rotationPoint.z());
            appendTurnSnapshotNumbers(snapshot, prefix + ".velocity", velocity.x, velocity.y, velocity.z);
            appendTurnSnapshotNumbers(snapshot, prefix + ".omega", omega.x, omega.y, omega.z);
        }
        var activeField = BearingStabilizer.class.getDeclaredField("ACTIVE");
        activeField.setAccessible(true);
        java.util.Map<?, ?> active = (java.util.Map<?, ?>) activeField.get(null);
        SwivelBearingBlockEntity[] bearings = {movingYaw, movingPitch, staffSiblingYaw, staffSiblingPitch};
        String[] lockNames = {"yaw1", "pitch1", "yaw2", "pitch2"};
        for (int i = 0; i < bearings.length; i++) {
            SwivelBearingBlockEntity bearing = bearings[i];
            Object lock = active.get(bearing);
            String prefix = "lock." + lockNames[i];
            if (lock == null) {
                snapshot.append(prefix).append(".available=false\n");
                continue;
            }
            Vector3d parentAnchor = new Vector3d((Vector3d) readTurnSnapshotField(lock, "parentLocalAnchor"));
            Vector3d childPivot = new Vector3d((Vector3d) readTurnSnapshotField(lock, "childLocalPivot"));
            ServerSubLevel parent = (ServerSubLevel) readTurnSnapshotField(lock, "jointParent");
            ServerSubLevel jointChild = (ServerSubLevel) readTurnSnapshotField(lock, "jointChild");
            if (jointChild == null) {
                snapshot.append(prefix).append(".available=false\n");
                continue;
            }
            Vector3d parentWorld = parent == null ? new Vector3d(parentAnchor)
                    : parent.logicalPose().transformPosition(new Vector3d(parentAnchor));
            Vector3d childWorld = jointChild.logicalPose().transformPosition(new Vector3d(childPivot));
            snapshot.append(prefix).append(".parent_uuid=").append(parent == null ? "ground" : parent.getUniqueId()).append('\n')
                    .append(prefix).append(".child_uuid=").append(jointChild.getUniqueId()).append('\n')
                    .append(prefix).append(".bearing_facing=").append(readTurnSnapshotField(lock, "jointBearingFacing")).append('\n')
                    .append(prefix).append(".plate_facing=").append(readTurnSnapshotField(lock, "jointPlateFacing")).append('\n');
            appendTurnSnapshotNumbers(snapshot, prefix + ".parent_local_anchor", parentAnchor.x, parentAnchor.y, parentAnchor.z);
            appendTurnSnapshotNumbers(snapshot, prefix + ".child_local_pivot", childPivot.x, childPivot.y, childPivot.z);
            appendTurnSnapshotNumbers(snapshot, prefix + ".parent_world_anchor", parentWorld.x, parentWorld.y, parentWorld.z);
            appendTurnSnapshotNumbers(snapshot, prefix + ".child_world_pivot", childWorld.x, childWorld.y, childWorld.z);
            appendTurnSnapshotNumbers(snapshot, prefix + ".native_zero_degrees", ((Number) readTurnSnapshotField(lock, "nativeZeroDegrees")).doubleValue());
        }
        Files.writeString(Path.of("bearing-turn-initial-state.txt"), snapshot);
    }

    private static Object readTurnSnapshotField(Object instance, String name) throws ReflectiveOperationException {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void appendTurnSnapshotNumbers(StringBuilder snapshot, String name, double... values) {
        snapshot.append(name).append('=');
        for (int i = 0; i < values.length; i++) {
            if (i != 0) snapshot.append(',');
            snapshot.append(String.format(java.util.Locale.ROOT, "%.17g", values[i]));
        }
        snapshot.append('\n');
    }

    private static double worldRayError(SwivelBearingBlockEntity pitch, double yawDegrees, double elevationDegrees) {
        Vector3d actual = child(pitch).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        Vector3d expected = new Quaterniond().rotationY(Math.toRadians(yawDegrees))
                .rotateX(Math.toRadians(-elevationDegrees)).transform(new Vector3d(0, 0, 1));
        return Math.toDegrees(Math.acos(Math.clamp(actual.dot(expected), -1, 1)));
    }

    private static double carrierElevation(double yawDegrees, double elevationDegrees) {
        Vector3d expected = new Quaterniond().rotationY(Math.toRadians(yawDegrees))
                .rotateX(Math.toRadians(-elevationDegrees)).transform(new Vector3d(0, 0, 1));
        Vector3d up = carrier.logicalPose().orientation().transform(new Vector3d(0, 1, 0));
        return Math.toDegrees(Math.asin(Math.clamp(expected.dot(up), -1, 1)));
    }

    private static void checkEmptySkyAim() throws Exception {
        BlockPos pos = new BlockPos(20, 180, 0);
        var state = ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState();
        var controller = new StabilizerControllerBlockEntity(pos, state);
        controller.setLevel(level);
        var finiteTarget = StabilizerControllerBlockEntity.class.getDeclaredField("hasAimTarget");
        finiteTarget.setAccessible(true);
        Vec3 origin = new Vec3(20, 180, 0), direction = new Vec3(0, 0, 1);
        controller.setDesiredAimRay(origin, direction, origin.add(0, 0, 100), true);
        require(finiteTarget.getBoolean(controller), "block hit enables finite-range ballistics");
        controller.setDesiredAimRay(origin, direction, origin.add(0, 0, 4096), false);
        require(!finiteTarget.getBoolean(controller), "empty sky disables finite-range ballistics");
        var tag = controller.saveWithoutMetadata(level.registryAccess());
        require(Math.abs(tag.getDouble("AimX")) < 1e-12 && Math.abs(tag.getDouble("AimY")) < 1e-12
                && Math.abs(tag.getDouble("AimZ") - 1) < 1e-12, "empty sky uses camera direction without parallax");
        controller.setDesiredAimRay(origin, direction, origin.add(0, 0, 100), true);
        require(finiteTarget.getBoolean(controller), "reacquired block restores finite-range ballistics");
        checkAimMarkerModes(controller, origin, direction);
    }

    /** Real CBC geometry with a known cached firing profile; does not fire or change a user world. */
    private static void checkAimMarkerModes(StabilizerControllerBlockEntity controller, Vec3 origin, Vec3 direction) throws Exception {
        BlockPos mountPos = new BlockPos(25, 180, 0);
        var mountBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(mountPos, mountBlock.defaultBlockState());
        require(level.getBlockEntity(mountPos) instanceof CannonMountBlockEntity, "CBC marker fixture mount exists");
        var mount = (CannonMountBlockEntity) level.getBlockEntity(mountPos);
        var contraption = new MountedAutocannonContraption();
        var orientation = AbstractMountedCannonContraption.class.getDeclaredField("initialOrientation");
        orientation.setAccessible(true);
        orientation.set(contraption, Direction.SOUTH);
        var start = AbstractMountedCannonContraption.class.getDeclaredField("startPos");
        start.setAccessible(true);
        start.set(contraption, BlockPos.ZERO);
        contraption.getBlocks().put(BlockPos.ZERO, new StructureBlockInfo(BlockPos.ZERO,
                Blocks.STONE.defaultBlockState(), null));
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 1);
        var cannon = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, true);
        cannon.setPos(25, 180, 0);
        var mounted = CannonMountBlockEntity.class.getDeclaredField("mountedContraption");
        mounted.setAccessible(true);
        mounted.set(mount, cannon);
        BindingRef ref = BindingRef.capture(level, mountPos);
        Vec3 muzzle = CannonBallisticsCompat.getMuzzlePosition(level, ref);
        require(muzzle != null, "CBC marker fixture resolves a physical muzzle");
        var profile = new CannonBallisticsCompat.ShotProfile(muzzle, direction, Vec3.ZERO, 8,
                -0.05, 0.002, false, "marker fixture", "autocannon fixture", 1, 1, 400, 0, 1, 0);
        var cacheField = CannonBallisticsCompat.class.getDeclaredField("PROFILE_CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked") var cache = (java.util.Map<UUID, Object>) cacheField.get(null);
        var cachedType = Class.forName(CannonBallisticsCompat.class.getName() + "$CachedProfile");
        var cachedConstructor = cachedType.getDeclaredConstructors()[0];
        cachedConstructor.setAccessible(true);
        cache.put(cannon.getUUID(), cachedConstructor.newInstance(level.getGameTime(), profile));
        try {
            controller.bind(BindingSlot.CANNON_MOUNT, ref);
            controller.setDesiredAimRay(origin, direction, origin.add(0, 0, 100), true);
            var hitGroups = controller.getMeasuredAimGroups();
            require(hitGroups.size() == 1 && hitGroups.getFirst().ballistic() != null, "finite-range marker retains loaded ballistics");
            controller.setDesiredAimRay(origin, direction, origin.add(0, 0, 4096), false);
            var skyGroups = controller.getMeasuredAimGroups();
            require(skyGroups.size() == 1 && skyGroups.getFirst().ballistic() == null, "sky marker switches from loaded impact to actual barrel ray in the same tick");
            var sky = skyGroups.getFirst();
            Vec3 measured = controller.getMeasuredAimDirection();
            require(new Vec3(sky.directionX(), sky.directionY(), sky.directionZ()).distanceTo(measured.normalize()) < 1e-9,
                    "sky marker follows actual barrel, not camera or old impact");
            controller.setDesiredAimRay(origin, direction, origin.add(0, 0, 100), true);
            require(controller.getMeasuredAimGroups().getFirst().ballistic() != null, "reacquired block restores loaded impact marker in the same tick");
            var target = markerOpticalTarget;
            require(level.getEntity(target.getUUID()) == target && target.isAlive(), "optical fixture target is live and visible to server lookup");
            var locked = StabilizerControllerBlockEntity.class.getDeclaredField("lockedEntityId");
            locked.setAccessible(true);
            locked.set(controller, target.getUUID());
            try {
                controller.setDesiredAimRay(origin, direction, origin.add(0, 0, 4096), false);
                require(controller.getMeasuredAimGroups().getFirst().ballistic() != null,
                        "live optical target retains ballistics when camera ray misses blocks");
            } finally {
                locked.set(controller, null);
                target.discard();
            }
            System.out.println("PASS: production CBC marker switches hit -> sky -> hit with a nonzero firing profile");
        } finally {
            cache.remove(cannon.getUUID());
            mounted.set(mount, null);
            level.removeBlock(mountPos, false);
        }
    }

    private static void commandWorldRay(SwivelBearingBlockEntity yaw, SwivelBearingBlockEntity pitch,
                                        double yawDegrees, double elevationDegrees) {
        Vector3d measured = child(pitch).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        Vector3d requested = new Quaterniond().rotationY(Math.toRadians(yawDegrees))
                .rotateX(Math.toRadians(-elevationDegrees)).transform(new Vector3d(0, 0, 1));
        Vec3 from = new Vec3(measured.x, measured.y, measured.z), to = new Vec3(requested.x, requested.y, requested.z);
        if (age >= 800 && (age - 800) / 80 % 2 == 1) {
            BearingStabilizer.maintainBinding(yaw, true, true, true, false, 0, 90, 90, Vec3.ZERO);
            BearingStabilizer.maintainBinding(pitch, false, true, true, false, 0, 90, 90, Vec3.ZERO);
            BearingStabilizer.maintainDirectionReference(yaw, from);
            BearingStabilizer.maintainDirectionReference(pitch, from);
            observeFixtureAzimuth(yaw,pitch,from);
            return;
        }
        // This free carrier can tumble through a full roll. Recovery accuracy needs
        // reachable rays; the earlier fixtures separately exercise narrow pitch stops.
        BearingStabilizer.commandAim(yaw, from, to, true, age >= 580 ? 0 : 90, 90, 90, Vec3.ZERO);
        BearingStabilizer.commandAim(pitch, from, to, false, age >= 580 ? 0 : 90, 90, 90, Vec3.ZERO);
        observeFixtureAzimuth(yaw,pitch,from);
    }

    private static void commandMixedOperatorRay() {
        Vector3d measured = child(movingPitch).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        Vector3d requested = new Quaterniond().rotationY(Math.toRadians(70)).rotateX(Math.toRadians(-10))
                .transform(new Vector3d(0, 0, 1));
        Vec3 from = new Vec3(measured.x, measured.y, measured.z), to = new Vec3(requested.x, requested.y, requested.z);
        BearingStabilizer.commandAim(movingYaw, from, to, true, 30, 90, 90, Vec3.ZERO);
        BearingStabilizer.commandAim(movingPitch, from, to, false, 30, 90, 90, Vec3.ZERO);
        observeFixtureAzimuth(movingYaw,movingPitch,from);
    }

    private static void startRealStaffDrag() {
        startRealStaffDrag(child(movingPitch));
    }

    private static void startRealStaffDrag(ServerSubLevel grabbed) {
        boolean newPlayer = staffPlayer == null;
        if (newPlayer) staffPlayer = FakePlayerFactory.get(level,
                new GameProfile(UUID.fromString("04529a0a-039d-4f43-94e8-040408aee331"), "StaffRegression"));
        if (AIM_EDGE_CASES) staffPlayer.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        staffPlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                dev.simulated_team.simulated.index.SimItems.PHYSICS_STAFF.asStack());
        staffAnchor = new Vector3d(grabbed.getMassTracker().getCenterOfMass()).add(0.8, 0, 0);
        staffWorldGoal = grabbed.logicalPose().transformPosition(new Vector3d(staffAnchor));
        staffOrientation = new Quaterniond(grabbed.logicalPose().orientation());
        staffPlayer.setPos(staffWorldGoal.x, staffWorldGoal.y - staffPlayer.getEyeHeight(), staffWorldGoal.z - 8);
        // The real staff interpolates player xOld/yOld/zOld on physics substeps.
        // A fake player's default zeros would pull the vehicle toward world origin.
        staffPlayer.xOld = staffPlayer.xo = staffPlayer.getX();
        staffPlayer.yOld = staffPlayer.yo = staffPlayer.getY();
        staffPlayer.zOld = staffPlayer.zo = staffPlayer.getZ();
        if (newPlayer) level.addNewPlayer(staffPlayer);
        Vector3d eye = new Vector3d(staffPlayer.getX(), staffPlayer.getEyeY(), staffPlayer.getZ());
        PhysicsStaffServerHandler.get(level).drag(staffPlayer.getUUID(), grabbed.getUniqueId(),
                new Vector3d(staffWorldGoal).sub(eye), staffAnchor, staffOrientation);
    }

    private static void movingCarrierHold() {
        carrierRotationPeak = Math.max(carrierRotationPeak,
                2 * Math.acos(Math.clamp(Math.abs(carrier.logicalPose().orientation().w()), 0, 1)));
        Vector3d requested = new Quaterniond().rotationY(Math.toRadians(30)).rotateX(Math.toRadians(-10))
                .transform(new Vector3d(0, 0, 1));
        Vector3d actual = child(movingPitch).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        Vec3 measured = new Vec3(actual.x, actual.y, actual.z);
        if (age < 200) {
            Vec3 target = new Vec3(requested.x, requested.y, requested.z);
            BearingStabilizer.commandAim(movingYaw, measured, target, true, age < 100 ? 90 : 0, 45, 60, Vec3.ZERO);
            BearingStabilizer.commandAim(movingPitch, measured, target, false, age < 100 ? 90 : 0, 45, 60, Vec3.ZERO);
        } else {
            // No head input and a zero operator rate must still retain the same world ray.
            BearingStabilizer.maintainBinding(movingYaw, true, true, true, false, 0, 45, 60, Vec3.ZERO);
            BearingStabilizer.maintainBinding(movingPitch, false, true, true, false, 0, 45, 60, Vec3.ZERO);
            BearingStabilizer.maintainDirectionReference(movingYaw, measured);
            BearingStabilizer.maintainDirectionReference(movingPitch, measured);
        }
        if (age >= 130) {
            double error = Math.toDegrees(Math.acos(Math.clamp(actual.dot(requested), -1.0, 1.0)));
            if (age >= 300 && age < 340) {
                stepDisturbancePeak = Math.max(stepDisturbancePeak, error);
                require(error < 3.0, "bounded stepped external disturbance " + error);
            } else {
                movingPeakError = Math.max(movingPeakError, error);
                require(error < 0.4, "live world stabilization with zero operator speed, age " + age + ", error " + error);
            }
        }
    }

    private static void checkImpactPrediction() {
        for (int z = 0; z <= 80; z += 16) level.getChunk(new BlockPos(60, 190, z));
        for (int y = 180; y <= 192; y++) level.setBlockAndUpdate(new BlockPos(60, y, 80), Blocks.STONE.defaultBlockState());
        var shell = new CannonBallisticsCompat.ShotProfile(new Vec3(60.5, 190, 0), new Vec3(0, 0, 1), Vec3.ZERO,
                8, -0.05, 0, false, "test_shell", "autocannon", 5, 1, 60, 1, 1, 1);
        Vec3 hit = CannonBallisticsCompat.predictImpact(level, shell, Set.of());
        require(hit != null && Math.abs(hit.z - 80) < 0.02 && Math.abs(hit.y - 187.5) < 0.01,
                "loaded terrain impact uses real swept gravity " + hit);
        Vec3 center = SableMissileFuze.physicalCenterOfMass(child(a));
        var probe = new CannonBallisticsCompat.ShotProfile(center.add(-10, 0, 0), new Vec3(1, 0, 0), Vec3.ZERO,
                1, 0, 0, false, "test_probe", "autocannon", 5, 1, 40, 1, 1, 1);
        Vec3 physicalHit = CannonBallisticsCompat.predictImpact(level, probe, Set.of());
        require(physicalHit != null && physicalHit.distanceTo(center) < 1.5,
                "physical contact is projected to world coordinates " + physicalHit + " center " + center);
        Set<UUID> own = SubLevelContainer.getContainer(level).getAllSubLevels().stream()
                .map(body -> body.getUniqueId()).collect(Collectors.toSet());
        require(CannonBallisticsCompat.predictImpact(level, probe, own) == null, "own structures are ignored before choosing nearest impact");
        System.out.println("PASS: production terrain/structure ballistic impacts and own-vehicle exclusion");
    }

    private static void aim(SwivelBearingBlockEntity bearing, double degrees, double speed) {
        if (!bearing.isAssembled()) return;
        Vector3d actual = child(bearing).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        Vector3d requested = new Quaterniond().rotationY(Math.toRadians(degrees)).transform(new Vector3d(0, 0, 1));
        BearingStabilizer.commandAim(bearing, new Vec3(actual.x, actual.y, actual.z),
                new Vec3(requested.x, requested.y, requested.z), true, speed, 20, 45, Vec3.ZERO);
    }
    private static void aimPitch(SwivelBearingBlockEntity bearing, double yaw, double pitch) {
        Vector3d actual = child(bearing).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        Vector3d requested = new Quaterniond().rotationY(Math.toRadians(yaw)).rotateX(Math.toRadians(-pitch))
                .transform(new Vector3d(0, 0, 1));
        BearingStabilizer.commandAim(bearing, new Vec3(actual.x, actual.y, actual.z),
                new Vec3(requested.x, requested.y, requested.z), false, 60, 20,
                bearing == pitchA && age >= 280 ? 5 : 45, Vec3.ZERO);
    }
    private static double elevation(SwivelBearingBlockEntity bearing) {
        Vector3d forward = child(bearing).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        return Math.toDegrees(Math.asin(forward.y));
    }
    private static ServerSubLevel child(SwivelBearingBlockEntity bearing) {
        return (ServerSubLevel) SubLevelContainer.getContainer(level).getSubLevel(bearing.getSubLevelID());
    }
    private static dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle handle(SwivelBearingBlockEntity bearing) {
        return ((SwivelBearingBlockEntityAccessor) bearing).stabilizedTurret$getConstraintHandle();
    }
    private static double angle(SwivelBearingBlockEntity bearing) {
        Vector3d forward = child(bearing).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        return Math.toDegrees(Math.atan2(forward.x, forward.z));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void fail(Throwable error) {
        failure = error.toString();
        error.printStackTrace();
        finished = true;
        try {
            Files.writeString(Path.of("bearing-integration-result.txt"), "FAIL: " + failure);
            if (FINITE_CARRIER_AIM) {
                Files.writeString(Path.of("bearing-finite-carrier-aim-diagnostics.csv"), finiteAimDiagnostics);
                Files.writeString(Path.of("bearing-finite-carrier-lock-diagnostics.csv"), finiteAimLockDiagnostics);
                if (!Files.exists(Path.of("bearing-finite-carrier-aim-result.txt"))) {
                    Files.writeString(Path.of("bearing-finite-carrier-aim-result.txt"), "FAIL: " + failure);
                }
                if (FINITE_AIM_TRANSITIONS) {
                    Files.writeString(Path.of("bearing-finite-aim-transitions-diagnostics.csv"), transitionDiagnostics);
                    Files.writeString(Path.of("bearing-finite-aim-transitions-result.txt"), "FAIL: " + failure);
                }
            }
            writeServoPlanningDiagnostics();
        }
        catch (Exception ignored) {}
    }
}
