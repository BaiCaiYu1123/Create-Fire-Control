package com.hooya.stabilizedturret.content.display;

import net.minecraft.world.phys.Vec3;

/** Executable checks for the launch-angle quadratic ballistic path. */
public final class BallisticTrajectoryChecks {
    public static void main(String[] args) {
        preservesReleaseTangentAndEndpoint();
        verticalLaunchHasOneApex();
        distanceBasedArcHasOneApex();
        distanceAdvanceNeverRunsBackward();
    }

    private static void preservesReleaseTangentAndEndpoint() {
        Vec3 start = new Vec3(10.0, 120.0, -30.0);
        Vec3 target = new Vec3(18010.0, 90.0, 3970.0);
        Vec3 launch = new Vec3(0.2, 0.95, 0.1).normalize();
        BallisticTrajectory path = BallisticTrajectory.fromLaunch(
                start, launch, target, 1200.0);
        require(path.point(0.0, target).distanceToSqr(start) < 1.0e-10,
                "trajectory must begin at release position");
        require(path.point(1.0, target).distanceToSqr(target) < 1.0e-10,
                "trajectory must end at selected target");
        require(path.tangent(0.0, target).normalize().dot(launch) > 0.999999,
                "initial tangent must match release angle");
    }

    private static void verticalLaunchHasOneApex() {
        Vec3 start = new Vec3(0.0, 100.0, 0.0);
        Vec3 target = new Vec3(20000.0, 80.0, 0.0);
        BallisticTrajectory path = BallisticTrajectory.fromLaunch(
                start, new Vec3(0.0, 1.0, 0.0), target, 1200.0);
        double apex = path.apexParameter(target);
        require(apex > 0.0 && apex < 1.0,
                "upward launch must have an interior apex");
        double previousX = start.x;
        double previousY = start.y;
        for (int index = 1; index <= 200; index++) {
            double t = index / 200.0;
            Vec3 point = path.point(t, target);
            require(point.x >= previousX,
                    "vertical-launch trajectory must keep moving toward target");
            if (t <= apex) {
                require(point.y >= previousY - 1.0e-7,
                        "trajectory must not dive before its apex");
            } else if ((index - 1) / 200.0 >= apex) {
                require(point.y <= previousY + 1.0e-7,
                        "trajectory must not climb again after its apex");
            }
            previousX = point.x;
            previousY = point.y;
        }
    }

    private static void distanceAdvanceNeverRunsBackward() {
        Vec3 start = new Vec3(0.0, 100.0, 0.0);
        Vec3 target = new Vec3(18000.0, 80.0, 0.0);
        BallisticTrajectory path = BallisticTrajectory.fromLaunch(
                start, new Vec3(0.0, 1.0, 0.0), target, 1100.0);
        double progress = 0.0;
        for (int tick = 0; tick < 2000 && progress < 1.0; tick++) {
            double next = path.advance(progress, 34.03, target);
            require(next > progress, "distance advance must increase path parameter");
            progress = next;
        }
        require(progress >= 1.0, "long-range trajectory must reach its endpoint");
    }

    private static void distanceBasedArcHasOneApex() {
        Vec3 start = new Vec3(0.0, 100.0, 0.0);
        Vec3 target = new Vec3(20000.0, 80.0, 5000.0);
        BallisticTrajectory path = BallisticTrajectory.fromArc(start, target, 2600.0);
        require(path.point(0.0, target).distanceToSqr(start) < 1.0e-10,
                "arc must begin at release position");
        require(path.point(1.0, target).distanceToSqr(target) < 1.0e-10,
                "arc must end at selected target");
        double apex = path.apexParameter(target);
        require(apex > 0.0 && apex < 1.0,
                "distance-based arc must have an interior apex");
        double previousY = start.y;
        for (int index = 1; index <= 200; index++) {
            double t = index / 200.0;
            double y = path.point(t, target).y;
            if (t <= apex) {
                require(y >= previousY - 1.0e-7,
                        "arc must climb continuously before its apex");
            } else if ((index - 1) / 200.0 >= apex) {
                require(y <= previousY + 1.0e-7,
                        "arc must descend continuously after its apex");
            }
            previousY = y;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private BallisticTrajectoryChecks() {
    }
}
