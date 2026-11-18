package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class VirtualScopeIntegration {
    @SubscribeEvent public static void start(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        Throwable failure = null;
        try {
            check(event.getServer().overworld());
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("VIRTUAL SCOPE INTEGRATION FAILED", error);
        } finally {
            try { Files.writeString(Path.of("virtual-scope-integration-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
            catch (Exception error) { throw new RuntimeException(error); }
            event.getServer().halt(false);
        }
    }

    private static void check(ServerLevel level) throws Exception {
        BlockPos origin = new BlockPos(0, 240, 0);
        level.setChunkForced(0, 0, true);
        level.setBlockAndUpdate(origin, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var controller = (StabilizerControllerBlockEntity) level.getBlockEntity(origin);
        require(!controller.setVirtualScopeEnabled(true), "missing high-low bearing must reject virtual scope");
        var yaw = assemble(level, origin.offset(4, 0, 0), Direction.UP);
        var pitch = assemble(level, yaw.getPlatePos().above(2), Direction.EAST);
        controller.bind(BindingSlot.YAW, BindingRef.capture(level, yaw.getBlockPos()));
        controller.bind(BindingSlot.PITCH, BindingRef.capture(level, pitch.getBlockPos()));
        var tag = controller.saveWithoutMetadata(level.registryAccess());
        tag.putDouble("ScopeOffsetY", 3);
        controller.loadWithComponents(tag, level.registryAccess());
        Vec3 firstPivot = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pitch.getBlockPos().east()));
        require(controller.setVirtualScopeEnabled(true), "assembled single pitch bearing accepted: " + controller.virtualScopeUnavailableReason()
                + ", bindings=" + controller.getPitchBearingBindings().size() + ", assembled=" + pitch.isAssembled());
        near(controller.getScopePosition(), firstPivot, "virtual scope ignores manual offset");
        checkParallelAimPolicy(controller, firstPivot);
        require(controller.getVirtualScopeHiddenSubLevels().containsAll(List.of(yaw.getSubLevelID(), pitch.getSubLevelID())),
                "both turret and gun sublevels must be hidden");
        var copy = new StabilizerControllerBlockEntity(origin, controller.getBlockState());
        copy.setLevel(level);
        copy.loadWithComponents(controller.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        require(copy.isVirtualScopeEnabled(), "virtual scope survives NBT round trip");
        require(controller.setVirtualScopeEnabled(false), "disable accepted");
        near(controller.getScopePosition(), firstPivot.add(0, 3, 0), "manual offset restored");

        var second = assemble(level, pitch.getBlockPos().east(6), Direction.WEST);
        controller.bind(BindingSlot.PITCH, BindingRef.capture(level, second.getBlockPos()));
        require(controller.setVirtualScopeEnabled(true), "opposed coaxial pitch bearings accepted");
        Vec3 secondPivot = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(second.getBlockPos().west()));
        near(controller.getScopePosition(), firstPivot.lerp(secondPivot, .5), "coaxial bearings use midpoint");
        controller.setVirtualScopeEnabled(false);
        var offset = assemble(level, pitch.getBlockPos().above(3), Direction.EAST);
        controller.bind(BindingSlot.PITCH, BindingRef.capture(level, offset.getBlockPos()));
        require(!controller.setVirtualScopeEnabled(true), "parallel offset bearings rejected");
        require(controller.virtualScopeUnavailableReason().equals("virtual_scope_not_coaxial"), "reason available to UI");

        require(controller.unbindDevice(BindingSlot.PITCH, 2, offset.getBlockPos()), "remove invalid offset bearing");
        require(controller.unbindDevice(BindingSlot.PITCH, 1, second.getBlockPos()), "isolate one bound physical pitch axis");

        // The CBC pivot is deliberately above and behind the physical pitch
        // axis, as with a breech at the rear of a long assembled gun.
        BlockPos mountPos = pitch.getPlatePos().above(3).north(8);
        level.setBlockAndUpdate(mountPos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount")).defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(mountPos);
        var cannon = new MountedAutocannonContraption();
        cannon.bounds = new AABB(0, 0, 0, 1, 1, 20);
        var entity = PitchOrientedContraptionEntity.create(level, cannon, Direction.SOUTH, mount);
        entity.setPos(Vec3.atCenterOf(mountPos).add(0, 1, 0));
        mount.attach(entity);
        BindingRef ref = BindingRef.capture(level, mountPos);
        var axis = CannonMountCompat.getMountedCannonPitchAxis(level, ref);
        require(axis != null, "assembled CBC entity supplies pitch axis");
        Vec3 expected = Sable.HELPER.projectOutOfSubLevel(level, entity.toGlobalVector(new Vec3(.5, .5, .5), 1));
        near(axis.pivot(), expected, "CBC pivot uses rotation centre");
        controller.bind(BindingSlot.CANNON_MOUNT, ref);
        require(controller.getVirtualScopeHiddenEntities().contains(entity.getUUID()), "assembled entity explicitly hidden");
        require(controller.setVirtualScopeEnabled(true), "CBC's offset native axis cannot invalidate the bound physical axis");
        Vec3 physicalCenter = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(pitch.getBlockPos()));
        Vec3 physicalDirection = firstPivot.subtract(physicalCenter).normalize();
        var physicalAxis = new VirtualScopeGeometry.Axis(firstPivot, physicalDirection);
        Vec3 camera = firstPivot.add(physicalDirection.scale(axis.pivot().subtract(firstPivot).dot(physicalDirection)));
        require(VirtualScopeGeometry.distanceSquared(physicalAxis, axis.pivot()) > 16,
                "fixture must reproduce a CBC breech far from the real bearing axis");
        near(controller.getScopePosition(), camera, "camera lies on the bound physical bearing axis at the gun's lateral position");
        require(VirtualScopeGeometry.distanceSquared(physicalAxis, controller.getScopePosition()) < 1e-10,
                "virtual camera cannot move to the CBC breech");
        System.out.println("PASS: rear-offset CBC breech selects only the lateral camera position on the bound physical pitch bearing axis");
        System.out.println("PASS: virtual scope real bearings, coaxial rejection, midpoint, NBT, offsets and assembled CBC pivot/entity");
    }

    private static SwivelBearingBlockEntity assemble(ServerLevel level, BlockPos pos, Direction direction) {
        level.getChunk(pos);
        level.setBlockAndUpdate(pos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"))
                .defaultBlockState().setValue(SwivelBearingBlock.FACING, direction));
        level.setBlockAndUpdate(pos.relative(direction), Blocks.IRON_BLOCK.defaultBlockState());
        var bearing = (SwivelBearingBlockEntity) level.getBlockEntity(pos);
        bearing.assemble();
        require(bearing.isAssembled(), "bearing assembly at " + pos);
        return bearing;
    }

    private static void checkParallelAimPolicy(StabilizerControllerBlockEntity controller, Vec3 camera) throws Exception {
        Class<?> groupType = Class.forName(StabilizerControllerBlockEntity.class.getName() + "$TurretGroup");
        var groupConstructor = groupType.getDeclaredConstructor(List.class, BindingRef.class, List.class, List.class);
        groupConstructor.setAccessible(true);
        Object group = groupConstructor.newInstance(List.of(), null, List.of(), List.of());
        Class<?> measuredType = Class.forName(StabilizerControllerBlockEntity.class.getName() + "$AimReference");
        var measuredConstructor = measuredType.getDeclaredConstructor(Vec3.class, Vec3.class, java.util.UUID.class);
        measuredConstructor.setAccessible(true);
        Vec3 sight = new Vec3(0, 0, 1), muzzle = camera.add(.8, 1, 6.5);
        Object measured = measuredConstructor.newInstance(muzzle, sight, null);
        var request = StabilizerControllerBlockEntity.class.getDeclaredMethod("requestedGroupAim", groupType, measuredType);
        request.setAccessible(true);
        var scope = StabilizerControllerBlockEntity.class.getDeclaredField("scopeUserId");
        scope.setAccessible(true);
        for (double range : new double[]{4, 24, 50, 490, 1300}) {
            Vec3 target = camera.add(sight.scale(range));
            controller.setDesiredAimRay(camera, sight, target, true, null, Vec3.ZERO, .1, .1, null);
            scope.set(controller, java.util.UUID.randomUUID());
            Object command = request.invoke(controller, group, measured);
            var direction = command.getClass().getDeclaredMethod("direction"); direction.setAccessible(true);
            near((Vec3) direction.invoke(command), sight, "scoped virtual controller ignores terrain depth, including inside the barrel");
            scope.set(controller, null);
            command = request.invoke(controller, group, measured);
            near((Vec3) direction.invoke(command), target.subtract(muzzle).normalize(),
                    "leaving virtual scope restores ordinary finite convergence");
        }
        System.out.println("PASS: actual controller uses parallel virtual aim only while scoped; ordinary convergence restores on exit");
    }

    private static void near(Vec3 actual, Vec3 expected, String message) {
        require(actual != null && actual.distanceToSqr(expected) < 1.0e-8, message + ": " + actual + " vs " + expected);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
