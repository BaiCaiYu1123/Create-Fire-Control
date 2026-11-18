package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.content.bearing.MovingAimRay;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

import java.lang.reflect.Method;
import java.util.List;

/** Exercises production group selection with a real CBC muzzle on an assembled Sable pitch body. */
public final class NearTerrainAimIntegration {
    private NearTerrainAimIntegration() {}

    public static void check(ServerLevel level, SwivelBearingBlockEntity pitch,
                             SwivelBearingBlockEntity yaw) throws Exception {
        BlockPos mountPos = pitch.getPlatePos().relative(pitch.getBlockState().getValue(SwivelBearingBlock.FACING));
        level.getChunk(mountPos);
        level.setBlockAndUpdate(mountPos, BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("createbigcannons:cannon_mount")).defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(mountPos);
        require(mount != null, "CBC mount is present on the pitch structure: pos=" + mountPos
                + " state=" + level.getBlockState(mountPos));
        var contraption = new MountedAutocannonContraption();
        var orientation = AbstractMountedCannonContraption.class.getDeclaredField("initialOrientation");
        orientation.setAccessible(true);
        orientation.set(contraption, Direction.SOUTH);
        var start = AbstractMountedCannonContraption.class.getDeclaredField("startPos");
        start.setAccessible(true);
        start.set(contraption, BlockPos.ZERO);
        for (int z = 0; z <= 8; z++) {
            BlockPos block = new BlockPos(0, 0, z);
            contraption.getBlocks().put(block, new StructureBlockInfo(block, Blocks.STONE.defaultBlockState(), null));
        }
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 9);
        var cannon = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, true);
        cannon.setPos(mountPos.getX() + .5, mountPos.getY() + 1, mountPos.getZ() + .5);
        cannon.xOld = cannon.xo = cannon.getX();
        cannon.yOld = cannon.yo = cannon.getY();
        cannon.zOld = cannon.zo = cannon.getZ();
        var mounted = CannonMountBlockEntity.class.getDeclaredField("mountedContraption");
        mounted.setAccessible(true);
        mount.attach(cannon);
        mount.setYaw(Direction.SOUTH.toYRot());
        mount.setPitch(0);
        try {
            var controller = new StabilizerControllerBlockEntity(new BlockPos(20, 180, 0),
                    ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            controller.setLevel(level);
            BindingRef ref = BindingRef.capture(level, mountPos);
            // Exercise the exact group-command method; fixture topology need
            // not give the independently assembled yaw a parent relationship.
            controller.bind(BindingSlot.CANNON_MOUNT, ref);
            var groupType = Class.forName(StabilizerControllerBlockEntity.class.getName() + "$TurretGroup");
            var ctor = groupType.getDeclaredConstructor(List.class, BindingRef.class, List.class, List.class);
            ctor.setAccessible(true);
            Object group = ctor.newInstance(List.of(BindingRef.capture(level, yaw.getBlockPos())),
                    BindingRef.capture(level, pitch.getBlockPos()), List.of(ref), List.of());
            Method measure = StabilizerControllerBlockEntity.class.getDeclaredMethod("measureAimReference", groupType);
            measure.setAccessible(true);
            Object measured = measure.invoke(controller, group);
            require(measured != null, "production code resolves the CBC muzzle");
            Method request = StabilizerControllerBlockEntity.class.getDeclaredMethod("requestedGroupAim", groupType,
                    measured.getClass());
            request.setAccessible(true);
            Vec3 pivot = Sable.HELPER.projectOutOfSubLevel(level, pitch.getBlockPos().relative(
                    pitch.getBlockState().getValue(SwivelBearingBlock.FACING)).getCenter());
            Vec3 muzzle = CannonBallisticsCompat.getCurrentMuzzlePosition(level, ref);
            require(muzzle != null && muzzle.distanceTo(pivot) > 7, "long physical muzzle offset is retained");
            Vec3 camera = pivot.add(0, .5, 0), sight = new Vec3(0, -.1, 1).normalize();
            var reaction = StabilizerControllerBlockEntity.class.getDeclaredField("stabilizerReactionEnabled");
            reaction.setAccessible(true);
            for (boolean enabled : new boolean[]{true, false}) {
                reaction.setBoolean(controller, enabled);
                controller.setDesiredAimRay(camera, sight, camera.add(sight.scale(4)), true);
                Object near = request.invoke(controller, group, measured);
                require(targets(near).isEmpty(), "near point is excluded from both finite control paths");
                require(direction(near).distanceToSqr(sight) < 1e-10, "near command follows sight, not the rearward muzzle ray");
                var hit = StabilizerControllerBlockEntity.class.getDeclaredField("lastAimRayHit");
                hit.setAccessible(true);
                require(hit.getBoolean(controller), "real terrain hit survives for rangefinding and HUD");
                controller.setDesiredAimRay(camera, sight, camera.add(sight.scale(40)), true);
                Object far = request.invoke(controller, group, measured);
                require(targets(far).size() == 1, "leaving terrain reacquires the actual finite muzzle target");
                var body = Sable.HELPER.getContaining(cannon);
                require(body != null, "CBC camera fixture belongs to the actual pitch body");
                Vec3 localCamera = body.logicalPose().transformPositionInverse(camera);
                controller.setMovingAimRay(new MovingAimRay(body.getUniqueId(), localCamera, sight,
                        camera.add(sight.scale(4)), new Vec3(0, 0, -1)));
                require(targets(request.invoke(controller, group, measured)).isEmpty(),
                        "live camera-plane intersection overrides a stale far client point");
                controller.setMovingAimRay(new MovingAimRay(body.getUniqueId(), localCamera, sight,
                        camera.subtract(sight.scale(4)), new Vec3(0, 0, -1)));
                require(targets(request.invoke(controller, group, measured)).isEmpty(),
                        "camera beyond its face keeps sight direction instead of chasing a stale point");
                controller.setDesiredAimRay(camera, sight, camera.add(sight.scale(4096)), false);
                require(targets(request.invoke(controller, group, measured)).isEmpty(), "sky remains direction only");
            }
        } finally {
            mounted.set(mount, null);
            level.removeBlock(mountPos, false);
            cannon.discard();
        }
    }

    private static List<?> targets(Object command) throws Exception {
        var method = command.getClass().getDeclaredMethod("targets");
        method.setAccessible(true);
        return (List<?>) method.invoke(command);
    }

    private static Vec3 direction(Object command) throws Exception {
        var method = command.getClass().getDeclaredMethod("direction");
        method.setAccessible(true);
        return (Vec3) method.invoke(command);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
