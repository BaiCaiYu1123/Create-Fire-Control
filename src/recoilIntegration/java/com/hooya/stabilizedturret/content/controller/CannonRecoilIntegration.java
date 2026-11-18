package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
import rbasamoyai.createbigcannons.compat.sable.SableCompat;
import rbasamoyai.createbigcannons.compat.sable.SableForceGroupsCompat;
import rbasamoyai.createbigcannons.config.CBCConfigs;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in server test of the transformed CBC method and real Sable plots. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class CannonRecoilIntegration {
    private static final Vec3 SHOT = new Vec3(1, -2, -3);

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        Throwable failure = null;
        try {
            check(event.getServer().overworld());
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("CANNON RECOIL CHECK FAILED", error);
        }
        try {
            Files.writeString(Path.of("recoil-integration-result.txt"),
                    failure == null ? "PASS" : "FAIL: " + failure);
        } catch (Exception error) {
            throw new RuntimeException(error);
        } finally {
            event.getServer().halt(false);
        }
    }

    private static void check(ServerLevel level) throws Exception {
        // Both mounts share one physical object. Only the exact bound cannon
        // may inherit a computer's setting.
        BlockPos anchor = new BlockPos(0, 180, 0);
        level.setBlockAndUpdate(anchor, Blocks.IRON_BLOCK.defaultBlockState());
        var assembly = SimAssemblyHelper.assembleFromSingleBlock(
                level, anchor.below(), anchor, false, false);
        require(assembly != null, "real Sable body assembled");
        ServerSubLevel body = (ServerSubLevel) assembly.subLevel();
        BlockPos mountPos = anchor.offset(assembly.offset());
        Fixture first = cannon(level, body, mountPos);
        Fixture second = cannon(level, body, mountPos.east(3));
        var firstController = controller(level, new BlockPos(12, 180, 0));
        var secondController = controller(level, new BlockPos(14, 180, 0));
        require(firstController.getCannonRecoilMagnitude() == 40, "new controller defaults to 40");
        firstController.bind(BindingSlot.CANNON_MOUNT, first.binding());
        secondController.bind(BindingSlot.CANNON_MOUNT, second.binding());
        double globalMagnitude = CBCConfigs.server().compat.sableRecoilMultiplier.get();
        try {
            checkForce(level, body, first, 40);
            setMagnitude(secondController, 100);
            checkForce(level, body, second, 100);
            checkForce(level, body, first, 40);
            setMagnitude(firstController, 0);
            checkForce(level, body, first, 0);
            checkForce(level, body, second, 100);
            setMagnitude(firstController, 20.5);
            checkForce(level, body, first, 20.5);
            CBCConfigs.server().compat.sableRecoilMultiplier.set(0.0);
            checkForce(level, body, first, 20.5);
            checkForce(level, body, second, 100);
            CBCConfigs.server().compat.sableRecoilMultiplier.set(7.0);
            checkForce(level, body, first, 20.5);

            CompoundTag saved = firstController.saveWithFullMetadata(level.registryAccess());
            var restored = new StabilizerControllerBlockEntity(firstController.getBlockPos(),
                    firstController.getBlockState());
            restored.setLevel(level);
            restored.loadWithComponents(saved, level.registryAccess());
            require(restored.getCannonRecoilMagnitude() == 20.5, "fractional setting survives NBT round trip");
            saved.remove(CannonRecoilSettings.TAG);
            restored.loadWithComponents(saved, level.registryAccess());
            require(restored.getCannonRecoilMagnitude() == 40, "old saves migrate to 40");
            for (double invalid : new double[]{-1, 101, Double.NaN, Double.POSITIVE_INFINITY}) {
                setMagnitude(firstController, invalid);
                double expected = invalid == -1 ? 0 : invalid == 101 ? 100 : 40;
                require(firstController.getCannonRecoilMagnitude() == expected, "loaded recoil value is bounded");
            }

            // Hiding the control also relinquishes its independent override.
            saved = firstController.saveWithFullMetadata(level.registryAccess());
            saved.putBoolean("StabilizerReactionEnabled", false);
            firstController.loadWithComponents(saved, level.registryAccess());
            checkForce(level, body, first, CBCConfigs.server().compat.sableRecoilMultiplier.getF());
            require(firstController.unbindDevice(BindingSlot.CANNON_MOUNT, 0, first.binding().pos()),
                    "unbind exact cannon");
            saved = firstController.saveWithFullMetadata(level.registryAccess());
            saved.putBoolean("StabilizerReactionEnabled", true);
            firstController.loadWithComponents(saved, level.registryAccess());
            checkForce(level, body, first, CBCConfigs.server().compat.sableRecoilMultiplier.getF());
            checkForce(level, body, second, 100);
            // Replacing a cannon on a bound mount must immediately follow the
            // new assembled entity, without leaking settings to the old one.
            PitchOrientedContraptionEntity replacement = entity(level, second.mount());
            second.mount().attach(replacement);
            require(ControllerTracker.findForCannonRecoil(second.entity()) == null,
                    "old assembled entity loses recoil ownership");
            require(ControllerTracker.findForCannonRecoil(replacement) == secondController,
                    "new assembled entity inherits the bound mount setting");
            StabilizedTurret.LOGGER.info("CANNON RECOIL CHECK: PASS real mixin, shared body isolation, 0/40/100/fractional forces, global multiplier independence including zero, NBT migration, bounds, mode switch, unbinding and reassembly");
        } finally {
            CBCConfigs.server().compat.sableRecoilMultiplier.set(globalMagnitude);
            ControllerTracker.remove(firstController);
            ControllerTracker.remove(secondController);
            SableCompat.drain(level.dimension());
        }
    }

    private static StabilizerControllerBlockEntity controller(ServerLevel level, BlockPos pos) {
        var controller = new StabilizerControllerBlockEntity(pos,
                ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        controller.setLevel(level);
        ControllerTracker.add(controller);
        return controller;
    }

    private static void setMagnitude(StabilizerControllerBlockEntity controller, double value) {
        CompoundTag tag = controller.saveWithFullMetadata(controller.getLevel().registryAccess());
        tag.putDouble(CannonRecoilSettings.TAG, value);
        controller.loadWithComponents(tag, controller.getLevel().registryAccess());
    }

    private static Fixture cannon(ServerLevel level, ServerSubLevel body, BlockPos pos) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(pos);
        require(mount != null && Sable.HELPER.getContaining(mount) == body, "mount resolves to shared Sable body");
        var entity = entity(level, mount);
        mount.attach(entity);
        return new Fixture(mount, entity, BindingRef.capture(level, pos));
    }

    private static PitchOrientedContraptionEntity entity(ServerLevel level, CannonMountBlockEntity mount) {
        var contraption = new MountedAutocannonContraption();
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 4);
        var entity = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, mount);
        entity.setPos(Vec3.atCenterOf(mount.getBlockPos()).add(0, 1, 0));
        return entity;
    }

    private static void checkForce(ServerLevel level, ServerSubLevel body, Fixture fixture, double expected) {
        SableCompat.drain(level.dimension());
        Vec3 point = Vec3.atCenterOf(fixture.mount().getBlockPos());
        fixture.mount().onRecoil(SHOT, point, fixture.entity());
        var forces = SableCompat.drain(level.dimension());
        if (expected == 0) {
            require(forces.isEmpty(), "zero recoil must suppress the original CBC force");
            return;
        }
        require(forces.size() == 1, "shot queues exactly one recoil, not both original and override");
        var force = forces.getFirst();
        require(force.sublevelId().equals(body.getUniqueId()), "recoil targets the actual physical object");
        require(force.pos().equals(point) && force.force().distanceToSqr(SHOT.scale(expected)) < 1e-16,
                "absolute recoil multiplier and application point preserved: expected=" + expected + " actual=" + force);
        require(force.remainSteps() == 1 && force.forceGroup() == SableForceGroupsCompat.RECOIL.get(),
                "original Sable force group and queue timing preserved");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(CannonMountBlockEntity mount, PitchOrientedContraptionEntity entity, BindingRef binding) {}
}
