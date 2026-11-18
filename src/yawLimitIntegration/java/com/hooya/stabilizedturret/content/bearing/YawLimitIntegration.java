package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
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

/** Real production joints: activate at the rear, recenter, disable, then lock at zero. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class YawLimitIntegration {
    private static ServerLevel level;
    private static SwivelBearingBlockEntity[] bearings;
    private static final double[] previous = {Double.NaN, Double.NaN};
    private static int age, startupWaits, samples;
    private static double peakRate;
    private static boolean finished;
    private static final StringBuilder diagnostics = new StringBuilder("age,bearing,dt,angle_deg,rate_deg_s\n");

    @SubscribeEvent public static void start(ServerStartedEvent event) {
        try {
            level = event.getServer().overworld();
            bearings = new SwivelBearingBlockEntity[]{assemble(0), assemble(10)};
            SableEventPlatform.INSTANCE.onPostPhysicsTick(YawLimitIntegration::measure);
        } catch (Throwable failure) { fail(failure); }
    }

    private static SwivelBearingBlockEntity assemble(int x) {
        BlockPos pos = new BlockPos(x, 160, 0);
        level.setChunkForced(x >> 4, 0, true);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"));
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, block.defaultBlockState().setValue(SwivelBearingBlock.FACING, Direction.UP));
        level.setBlockAndUpdate(pos.above(), Blocks.STONE.defaultBlockState());
        var bearing = (SwivelBearingBlockEntity) level.getBlockEntity(pos);
        bearing.assemble();
        require(bearing.isAssembled(), "real bearing assembles");
        return bearing;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) {
        if (finished || level == null) return;
        try {
            var container = (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
            for (var bearing : bearings) {
                ServerSubLevel body = child(bearing);
                if (body == null) {
                    require(++startupWaits < 80, "child plots register");
                    return;
                }
                container.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            }
            age++;
            for (int i = 0; i < bearings.length; i++) {
                var bearing = bearings[i];
                if (age == 80) {
                    require(Math.abs(Math.abs(angle(bearing)) - 180) < .1, "starts at rear seam");
                    BearingStabilizer.setYawLimits(bearing, new YawLimits(45, 45, YawLimits.SOUTH));
                }
                if (age == 200) {
                    require(Math.abs(Math.abs(angle(bearing)) - 45) < .2, "activation returns to stop");
                    BearingStabilizer.setYawLimits(bearing, new YawLimits(45, 45, YawLimits.NORTH));
                }
                if (age == 320) {
                    require(Math.abs(BearingServoMath.wrapRadians(Math.toRadians(angle(bearing) - 180))) < .01,
                            "new center converges");
                    BearingStabilizer.setYawLimits(bearing, YawLimits.UNLIMITED);
                }
                if (age == 400) {
                    require(Math.abs(angle(bearing) + 170) < .2, "disabled limits allow rear seam crossing");
                    BearingStabilizer.setYawLimits(bearing, new YawLimits(0, 0, YawLimits.NORTH));
                }
                double target = age >= 320 && age < 400 ? -170 : (i == 0 ? 179.999 : -179.999);
                Vector3d actual = child(bearing).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
                Vector3d requested = new Quaterniond().rotationY(Math.toRadians(target)).transform(new Vector3d(0, 0, 1));
                BearingStabilizer.commandAim(bearing, new Vec3(actual.x, actual.y, actual.z),
                        new Vec3(requested.x, requested.y, requested.z), true, age < 80 ? 360 : 30, 20, 45, Vec3.ZERO);
            }
            if (age >= 480) {
                for (var bearing : bearings) require(Math.abs(Math.abs(angle(bearing)) - 180) < .2,
                        "zero range holds calibrated north");
                Files.writeString(Path.of("yaw-limit-integration-result.txt"),
                        "PASS: production rear activation, center edit, disable/re-enable, zero range; samples="
                                + samples + " peakReturnDegS=" + Math.toDegrees(peakRate));
                Files.writeString(Path.of("yaw-limit-integration.csv"), diagnostics);
                finished = true;
                event.getServer().halt(false);
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private static void measure(SubLevelPhysicsSystem system, double dt) {
        if (finished || system.getLevel() != level || age == 0) return;
        try {
            for (int i = 0; i < bearings.length; i++) {
                double now = Math.toRadians(angle(bearings[i]));
                if (Double.isFinite(previous[i]) && (age >= 80 && age < 320 || age >= 400)) {
                    double rate = Math.abs(BearingServoMath.wrapRadians(now - previous[i])) / dt;
                    diagnostics.append(age).append(',').append(i).append(',').append(dt).append(',')
                            .append(Math.toDegrees(now)).append(',').append(Math.toDegrees(rate)).append('\n');
                    require(Double.isFinite(rate) && rate < Math.toRadians(90), "production motion stays bounded: " + Math.toDegrees(rate));
                    // The live log uses 30 deg/s head aim; stop recovery uses 60 deg/s.
                    if (age >= 80 && age < 180 || age >= 400) {
                        peakRate = Math.max(peakRate, rate);
                        require(rate < Math.toRadians(90), "outside activation returns smoothly: age=" + age
                                + " bearing=" + i + " angle=" + Math.toDegrees(now) + " rate=" + Math.toDegrees(rate));
                        samples++;
                    }
                }
                previous[i] = now;
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private static ServerSubLevel child(SwivelBearingBlockEntity bearing) {
        return (ServerSubLevel) SubLevelContainer.getContainer(level).getSubLevel(bearing.getSubLevelID());
    }
    private static double angle(SwivelBearingBlockEntity bearing) {
        Vector3d ray = child(bearing).logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        return Math.toDegrees(Math.atan2(ray.x, ray.z));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void fail(Throwable failure) {
        finished = true;
        failure.printStackTrace();
        try { Files.writeString(Path.of("yaw-limit-integration-result.txt"), "FAIL: " + failure); }
        catch (Exception ignored) {}
        try { Files.writeString(Path.of("yaw-limit-integration.csv"), diagnostics); }
        catch (Exception ignored) {}
        if (level != null) level.getServer().halt(false);
    }
}
