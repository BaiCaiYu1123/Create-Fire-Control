package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.compat.VoxyLodRaycaster.RayHit;
import com.hooya.stabilizedturret.content.bearing.MovingAimRay;
import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.UUID;

/** Exact VoxelShape hits and actual DDA traversal counts for long physical-plot rays. */
public final class AimRaycastGeometryChecks {
    public static void main(String[] args) {
        boundariesAndMisses();
        loadedAndLodSightHits();
        rangefinderOriginInsideLodVoxel();
        sideFacingLodSightFollowsMovingCamera();
        lodEntranceFaces();
        int full = 0, cropped = 0;
        for (double direction : new double[]{-1, 1}) {
            Vec3 from = new Vec3(20000000 - direction * 2000, 70.25, 20000000.5);
            Vec3 to = from.add(direction * 4096, 0, 0);
            BlockPos block = new BlockPos(20000000, 70, 20000000);
            var segment = AimRaycastGeometry.crop(from, to,
                    new AABB(block.getX(), 70, block.getZ(), block.getX() + 1, 71, block.getZ() + 1));
            require(segment != null, "long ray intersects the physical plot");
            int[] originalCount = {0}, boundedCount = {0};
            BlockHitResult expected = clip(from, to, block, originalCount);
            BlockHitResult actual = clip(segment.from(), segment.to(), block, boundedCount);
            require(expected != null && actual != null && actual.getLocation().distanceToSqr(expected.getLocation()) < 1e-10
                            && actual.getDirection() == expected.getDirection(),
                    "cropping preserves exact collision position and face in both directions: original="
                            + expected + " cropped=" + actual + " segment=" + segment);
            full += originalCount[0];
            cropped += boundedCount[0];
            // Same render pose must project both the ray and its physical hit,
            // including non-unit scale used by the installed scaling add-on.
            var pose = new Pose3d(new Vector3d(3, 80, 4), new Quaterniond().rotationY(.4),
                    new Vector3d(20000000, 70, 20000000), new Vector3d(1.5));
            Vec3 worldFrom = pose.transformPosition(from), worldTo = pose.transformPosition(to);
            Vec3 worldHit = pose.transformPosition(actual.getLocation());
            require(worldHit.subtract(worldFrom).normalize().distanceTo(worldTo.subtract(worldFrom).normalize()) < 1e-9,
                    "rotated/scaled physical hit remains on its rendered sight ray");
        }
        require(full > 3900 && cropped < 10, "plot clipping removes the thousands of empty DDA block visits");
        System.out.printf("PASS: exact physical faces and pose projection; block visits %d -> %d%n", full, cropped);
    }

    private static BlockHitResult clip(Vec3 from, Vec3 to, BlockPos block, int[] visits) {
        return BlockGetter.traverseBlocks(from, to, block, (target, pos) -> {
            visits[0]++;
            return pos.equals(target) ? Shapes.block().clip(from, to, pos) : null;
        }, target -> null);
    }

    private static void boundariesAndMisses() {
        AABB box = new AABB(0, 0, 0, 1, 1, 1);
        require(AimRaycastGeometry.crop(new Vec3(-2, 2, .5), new Vec3(2, 2, .5), box) == null,
                "parallel ray outside the plot is skipped");
        require(AimRaycastGeometry.crop(new Vec3(-2, .5, .5), new Vec3(-1, .5, .5), box) == null,
                "plot beyond the segment is skipped");
        require(AimRaycastGeometry.crop(new Vec3(.5, .5, .5), new Vec3(2, .5, .5), box).from()
                .equals(new Vec3(.5, .5, .5)), "starting inside a plot keeps the original start");
        require(AimRaycastGeometry.crop(new Vec3(-2, 1, 1), new Vec3(2, 1, 1), box) != null,
                "grazing outer faces remain eligible");
    }

    private static void loadedAndLodSightHits() {
        Vec3 origin = new Vec3(0, 70, 0), direction = new Vec3(0, 0, 1);
        Vec3 near = new Vec3(0, 70, 12), far = new Vec3(0, 70, 24000);
        BlockHitResult face = new BlockHitResult(near, Direction.NORTH, BlockPos.containing(near), false);
        var loaded = new SightRaycastResult(near, true, face);
        require(loaded.withLodFallback(origin, direction, () -> {
            throw new AssertionError("A loaded terrain/body hit must skip the LOD query");
        }) == loaded, "nearby physical blocks retain the exact face and hit point");
        require(loaded.surfaceNormal(new Vec3(0, 0, 1)).equals(new Vec3(0, 0, -1)),
                "loaded faces retain their collision normal for moving-camera aim");
        var miss = new SightRaycastResult(new Vec3(0, 70, 4096), false, null);
        var terrain = new RayHit(far, new AABB(0, 64, 24000, 16, 80, 24016));
        var lod = miss.withLodFallback(origin, direction, () -> terrain);
        require(lod.hit() && lod.target().equals(far) && lod.rawResult() == null && lod.lodHit() == terrain,
                "distant cached terrain is a finite sight hit without a fabricated loaded face");
        require(lod.surfaceNormal(new Vec3(0, 0, 1)).equals(new Vec3(0, 0, -1)),
                "LOD voxel entrance provides a moving-camera plane without a physical body anchor");
        require(lod.withLodFallback(origin, direction, () -> { throw new AssertionError("Resolved hit re-queried"); }) == lod,
                "a resolved hit is never replaced by a fallback");
        require(miss.withLodFallback(origin, direction, () -> null) == miss,
                "no Voxy/no cache/sky retains the miss used to clear laser compensation");
        require(miss.surfaceNormal(new Vec3(0, 0, 1)) == null, "sky has no stale moving sight surface");
    }

    private static void rangefinderOriginInsideLodVoxel() {
        // Exact camera/ray values from the 2026-10-11 crash. Voxy returned
        // range=0 while the camera was inside its cached terrain voxel.
        Vec3 origin = new Vec3(-188.9522747150417, 138.62138574295125, 49.09857847414027);
        Vec3 direction = new Vec3(0.5786269969596592, 0.7884643764395098, 0.20860183478412023);
        var miss = new SightRaycastResult(origin.add(direction.scale(4096)), false, null);
        var containingVoxel = new AABB(-192, 128, 48, -176, 144, 64);
        require(miss.withLodFallback(origin, direction, () -> new RayHit(origin, containingVoxel)) == miss,
                "zero-distance LOD hit must leave the valid forward miss intact");
        require(miss.withLodFallback(origin, direction,
                () -> new RayHit(origin.subtract(direction), containingVoxel)) == miss,
                "a cached point behind the camera cannot become a sight target");
        require(miss.withLodFallback(origin, direction,
                () -> new RayHit(new Vec3(Double.NaN, 0, 0), containingVoxel)) == miss,
                "invalid cached coordinates cannot become a sight target");
        Vec3 nearby = origin.add(direction.scale(0.01));
        require(miss.withLodFallback(origin, direction, () -> new RayHit(nearby, containingVoxel)).hit(),
                "valid centimetre-distance hits are retained without an arbitrary minimum range");
    }

    private static void sideFacingLodSightFollowsMovingCamera() {
        Vec3 com = new Vec3(20000000, 70, -20000000);
        double oldError = 0;
        for (double range : new double[]{600, 20000, AimRayLimits.LOD_DISTANCE - 1}) {
            Vec3 direction = new Vec3(1, -4 / range, 0).normalize();
            Vec3 initialHit = new Vec3(range, 0, 0);
            var terrain = new RayHit(initialHit, new AABB(range - 8, -16, -8, range + 8, 0, 8));
            var acquired = new SightRaycastResult(new Vec3(4096, 0, 0), false, null)
                    .withLodFallback(Vec3.ZERO, direction, () -> terrain);
            Vec3 normal = acquired.surfaceNormal(direction);
            require(normal != null, "LOD hit supplies the same camera transport as a loaded terrain face");
            var ray = new MovingAimRay(new UUID(6, 7), com, direction, acquired.target(), normal);
            for (double speed : new double[]{-.6, .6}) for (int frame = 0; frame <= 24; frame++) {
                double travel = speed * (3 + frame / 24.0);
                Pose3d cameraPose = new Pose3d(new Vector3d(0, 4, travel), new Quaterniond(),
                        new Vector3d(com.x, com.y, com.z), new Vector3d(1));
                Vector3d origin = cameraPose.transformPosition(new Vector3d(com.x, com.y, com.z));
                Vec3 moved = ray.intersect(origin, null);
                Vec3 expected = new Vec3(range, 0, travel);
                require(moved != null && moved.distanceTo(expected) < 1e-7,
                        "side-facing sight follows forward/reverse movement beyond the old 8192-block limit");
                Vec3 muzzle = new Vec3(origin.x + 3, origin.y - .5, origin.z);
                Vec3 bore = moved.subtract(muzzle).normalize();
                Vec3 marker = terrain.ray(muzzle, bore, AimRayLimits.LOD_DISTANCE);
                require(marker != null && marker.distanceTo(expected) < 1e-7,
                        "rendered LOD barrel marker lands at the transported scope aim point");
                oldError = Math.max(oldError, acquired.target().distanceTo(expected));
            }
        }
        require(oldError > 2, "a frozen LOD coordinate reproduces the reported sideways lag");
    }

    private static void lodEntranceFaces() {
        for (double size : new double[]{1, 16}) {
            AABB box = new AABB(-size, -size, -size, size, size, size);
            for (Vec3 outward : new Vec3[]{new Vec3(1,0,0), new Vec3(-1,0,0), new Vec3(0,1,0),
                    new Vec3(0,-1,0), new Vec3(0,0,1), new Vec3(0,0,-1)}) {
                var face = new RayHit(outward.scale(size), box);
                require(face.surfaceNormal(outward.scale(-1)).equals(outward),
                        "all six entrance faces work for fine and coarse terrain");
            }
            require(new RayHit(Vec3.ZERO, box).surfaceNormal(new Vec3(1, 0, 0)) == null,
                    "starting inside a voxel does not fabricate an entrance face");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
