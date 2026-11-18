package com.hooya.stabilizedturret.content.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Executable regressions for remote-weapon-station angular rate limiting. */
public final class RemoteWeaponStationRateChecks {
    public static void main(String[] args) {
        checkClose(3.0F, MianbaoRemoteWeaponStationCompat.stepWrappedDegrees(
                0.0F, 90.0F, 3.0F), "yaw positive step");
        checkClose(-3.0F, MianbaoRemoteWeaponStationCompat.stepWrappedDegrees(
                0.0F, -90.0F, 3.0F), "yaw negative step");
        checkClose(-179.0F, MianbaoRemoteWeaponStationCompat.stepWrappedDegrees(
                179.0F, -170.0F, 2.0F), "yaw shortest wrap path");
        checkClose(12.25F, MianbaoRemoteWeaponStationCompat.stepDegrees(
                10.0F, 40.0F, 2.25F), "pitch positive step");
        checkClose(-12.25F, MianbaoRemoteWeaponStationCompat.stepDegrees(
                -10.0F, -40.0F, 2.25F), "pitch negative step");
        checkClose(11.0F, MianbaoRemoteWeaponStationCompat.stepDegrees(
                10.0F, 11.0F, 2.25F), "pitch settles without overshoot");
        checkClose(-20.0F, MianbaoRemoteWeaponStationCompat.clampPitch(-90.0F),
                "global depression limit");
        checkClose(65.0F, MianbaoRemoteWeaponStationCompat.clampPitch(90.0F),
                "global elevation limit");
        checkClose(-35.0F, MianbaoRemoteWeaponStationCompat.clampPitch(
                -90.0F, 35.0, 48.0), "fire-control depression limit");
        checkClose(48.0F, MianbaoRemoteWeaponStationCompat.clampPitch(
                90.0F, 35.0, 48.0), "fire-control elevation limit");
        checkOverheatCycle();
        checkReleasedTriggerResetsBurst();
        checkManualControlIdentity();
        checkUploadedDirectAim();
    }

    private static void checkOverheatCycle() {
        MianbaoRemoteWeaponStationCompat.ThermalState state =
                new MianbaoRemoteWeaponStationCompat.ThermalState();
        for (long tick = 0; tick < 300; tick++) {
            check(MianbaoRemoteWeaponStationCompat.updateThermalState(
                    state, tick, true), "15-second threshold burst tick " + tick);
            check(MianbaoRemoteWeaponStationCompat.updateThermalState(
                    state, tick, true), "same-tick duplicate must not add heat");
        }
        for (long tick = 300; tick < 400; tick++) {
            check(!MianbaoRemoteWeaponStationCompat.updateThermalState(
                    state, tick, true), "five-second cooldown tick " + tick);
        }
        check(MianbaoRemoteWeaponStationCompat.updateThermalState(
                state, 400, true), "automatic recovery after five seconds");
    }

    private static void checkReleasedTriggerResetsBurst() {
        MianbaoRemoteWeaponStationCompat.ThermalState state =
                new MianbaoRemoteWeaponStationCompat.ThermalState();
        for (long tick = 0; tick < 200; tick++) {
            check(MianbaoRemoteWeaponStationCompat.updateThermalState(
                    state, tick, true), "partial burst");
        }
        check(!MianbaoRemoteWeaponStationCompat.updateThermalState(
                state, 200, false), "released trigger");
        for (long tick = 201; tick < 500; tick++) {
            check(MianbaoRemoteWeaponStationCompat.updateThermalState(
                    state, tick, true), "new burst after release");
        }
        check(MianbaoRemoteWeaponStationCompat.updateThermalState(
                state, 500, true), "new burst threshold");
        check(!MianbaoRemoteWeaponStationCompat.updateThermalState(
                state, 501, true), "new burst overheats independently");
    }

    private static void checkManualControlIdentity() {
        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        BlockPos pos = new BlockPos(12, 34, 56);
        UUID plot = UUID.fromString("7c470dfa-9c54-4c32-87a4-87fcfd8ecb11");
        check(MianbaoRemoteWeaponStationCompat.sameManualBinding(
                        dimension, pos, plot, dimension, pos, plot),
                "manual control yields for the exact Sable station");
        check(!MianbaoRemoteWeaponStationCompat.sameManualBinding(
                        dimension, pos, plot, dimension, pos, UUID.randomUUID()),
                "manual control does not leak to the same plot coordinate on another craft");
        check(MianbaoRemoteWeaponStationCompat.sameManualBinding(
                        dimension, pos, null, dimension, pos, null),
                "manual control yields for a root-level station");
    }

    private static void checkUploadedDirectAim() {
        Vec3 forward = MianbaoRemoteWeaponStationDirectAimCompat
                .directionFromUploadedAngles(-180.0F, 0.0F, Direction.NORTH);
        checkClose(0.0F, (float) forward.x, "uploaded view forward x");
        checkClose(0.0F, (float) forward.y, "uploaded view forward y");
        checkClose(1.0F, (float) forward.z, "uploaded view forward z");

        Vec3 sameWorldViewFromEastBase = MianbaoRemoteWeaponStationDirectAimCompat
                .directionFromUploadedAngles(90.0F, 0.0F, Direction.EAST);
        checkClose(0.0F, (float) sameWorldViewFromEastBase.x,
                "uploaded east-base view x");
        checkClose(0.0F, (float) sameWorldViewFromEastBase.y,
                "uploaded east-base view y");
        checkClose(1.0F, (float) sameWorldViewFromEastBase.z,
                "uploaded east-base view z");

        Vec3 elevated = MianbaoRemoteWeaponStationDirectAimCompat
                .directionFromUploadedAngles(0.0F, 30.0F, Direction.NORTH);
        checkClose(0.5F, (float) elevated.y, "uploaded view elevation");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void checkClose(float expected, float actual, String label) {
        if (Math.abs(expected - actual) > 1.0e-5F) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private RemoteWeaponStationRateChecks() {
    }
}
