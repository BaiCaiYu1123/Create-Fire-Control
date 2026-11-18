package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;

/** Isolated server fixture: actual binding, welded load, block edits and backend/role transitions. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class PitchCollisionIntegration {
    private static ServerLevel level;
    private static SwivelBearingBlockEntity pitch, yaw;
    private static ServerSubLevel barrel;
    private static int age;
    private static boolean done;
    private static double originalMass;

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        try {
            level = event.getServer().overworld();
            level.setChunkForced(0, 0, true);
            level.getChunk(new BlockPos(0, 160, 0));
            pitch = assemble(new BlockPos(0, 160, 0), Direction.EAST);
            yaw = assemble(new BlockPos(8, 160, 0), Direction.UP);
            BlockPos barrelPos = new BlockPos(3, 160, 0);
            level.setBlockAndUpdate(barrelPos, Blocks.STONE.defaultBlockState());
            var assembled = SimAssemblyHelper.assembleFromSingleBlock(level, barrelPos.below(), barrelPos, false, false);
            require(assembled != null, "welded barrel assembly exists");
            barrel = (ServerSubLevel) assembled.subLevel();
        } catch (Throwable failure) { finish(failure); }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) {
        if (done || level == null) return;
        try {
            age++;
            if (age == 5) {
                ServerSubLevel child = child(pitch);
                originalMass = child.getMassTracker().getMass();
                Vector3d b = new Vector3d(barrel.getMassTracker().getCenterOfMass());
                Vector3d a = child.logicalPose().transformPositionInverse(barrel.logicalPose().transformPosition(new Vector3d(b)));
                Quaterniond relative = new Quaterniond(child.logicalPose().orientation()).conjugate()
                        .mul(barrel.logicalPose().orientation());
                SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(child, barrel,
                        new FixedConstraintConfiguration(a, b, relative));
            }
            if (age >= 8) bind(yaw, true);
            if (age >= 8 && age < 41 || age >= 46 && age < 56) bind(pitch, age >= 51);
            if (age == 20) {
                suppressed(true);
                require(!PitchStructureCollision.isDisabled(level, child(yaw)), "yaw structure retains collision");
                require(Math.abs(originalMass - child(pitch).getMassTracker().getMass()) < 1e-9,
                        "pitch mass survives collider removal");
                // Exercise the block-edit hook while collision remains disabled.
                level.setBlockAndUpdate(pitch.getPlatePos().above(), Blocks.STONE.defaultBlockState());
            }
            if (age == 25) suppressed(true);
            if (age == 30) BearingStabilizer.setImplementation(pitch, false);
            if (age == 35) suppressed(false);
            if (age == 36) BearingStabilizer.setImplementation(pitch, true);
            if (age == 40) suppressed(true);
            if (age == 41) BearingStabilizer.disable(pitch);
            if (age == 45) suppressed(false);
            if (age == 50) suppressed(true);
            if (age == 55) suppressed(false);
            if (age == 56) {
                // Attaching/removing the CBC fixture rebuilds mass/geometry;
                // finish joint-lifecycle assertions before editing the test body.
                com.hooya.stabilizedturret.content.controller.NearTerrainAimIntegration.check(level, pitch, yaw);
                BearingStabilizer.disable(pitch);
                BearingStabilizer.disable(yaw);
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private static void suppressed(boolean expected) {
        require(PitchStructureCollision.isDisabled(level, child(pitch)) == expected,
                "pitch collision state at tick " + age);
        require(PitchStructureCollision.isDisabled(level, barrel) == expected,
                "fixed-weld barrel collision state at tick " + age);
    }

    private static void bind(SwivelBearingBlockEntity bearing, boolean yawAxis) {
        BearingStabilizer.maintainBinding(bearing, yawAxis, true, true, false, 30, 20, 45, Vec3.ZERO);
    }

    private static ServerSubLevel child(SwivelBearingBlockEntity bearing) {
        return (ServerSubLevel) SubLevelContainer.getContainer(level).getSubLevel(bearing.getSubLevelID());
    }

    private static SwivelBearingBlockEntity assemble(BlockPos pos, Direction facing) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"));
        level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(SwivelBearingBlock.FACING, facing));
        level.setBlockAndUpdate(pos.relative(facing), Blocks.STONE.defaultBlockState());
        SwivelBearingBlockEntity bearing = (SwivelBearingBlockEntity) level.getBlockEntity(pos);
        require(bearing != null, "bearing placement");
        bearing.assemble();
        require(bearing.isAssembled(), "bearing assembly");
        return bearing;
    }

    private static void finish(Throwable failure) {
        done = true;
        try {
            if (failure != null) failure.printStackTrace();
            Files.writeString(Path.of("pitch-collision-integration-result.txt"), failure == null
                    ? "PASS: pitch and welded barrel collision disabled; yaw and mass retained; block edits, legacy switch, unbind, rebind and role changes checked; production CBC near-terrain aim excludes finite targets and reacquires far targets on both backends\n"
                    : "FAIL: " + failure + "\n");
        } catch (Exception error) { throw new RuntimeException(error); }
        if (level != null) level.getServer().halt(false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
