package com.hooya.stabilizedturret.content.armor;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** The inspection tool's bounded survey. Actual impacts still resolve each block separately. */
public final class ArmorInspectionRay {
    private static final double RANGE = 20;
    private static final double EPSILON = 1e-7;
    private static final double CONTACT_TOLERANCE = 1e-6;

    public record Totals(double thickness, double toughness, double hardness, int blocks) {
        public long millimeters() {
            return Math.round(thickness * 1000);
        }
    }

    private record Layer(BlockPos pos, BlockState state, double entry, double exit) {}

    public static Totals scan(Level level, BlockHitResult hit, Vec3 eye, Function<SubLevel, Pose3dc> poses) {
        BlockPos target = hit.getBlockPos();
        SubLevel targetBody = Sable.HELPER.getContaining(level, target);
        Pose3dc targetPose = targetBody == null ? null : poses.apply(targetBody);
        Vec3 aim = hit.getLocation();
        // Sable's pick keeps the intersection in plot coordinates.
        if (targetPose != null && new AABB(target).inflate(1).contains(aim)) {
            aim = targetPose.transformPosition(aim);
        }
        Vec3 direction = aim.subtract(eye).normalize();
        if (direction.lengthSqr() == 0) return new Totals(0, 0, 0, 0);

        double start = eye.distanceTo(aim);
        if (level.hasChunkAt(target)) {
            Vec3 localEye = targetPose == null ? eye : targetPose.transformPositionInverse(eye);
            Vec3 localDirection = targetPose == null ? direction : targetPose.transformNormalInverse(direction);
            var contact = EquivalentArmor.envelope(level, target, level.getBlockState(target),
                    localEye.subtract(Vec3.atLowerCornerOf(target)), localDirection);
            if (contact.length() > EPSILON && contact.exit() > EPSILON) start = Math.max(0, contact.entry());
        }

        Vec3 from = eye.add(direction.scale(start));
        Vec3 to = from.add(direction.scale(RANGE));
        var layers = new ArrayList<Layer>();
        collect(level, from, direction, null, layers);
        if (targetBody != null) {
            collect(level, targetPose.transformPositionInverse(from), targetPose.transformNormalInverse(direction),
                    targetBody, layers);
        }
        var container = SubLevelContainer.getContainer(level);
        if (container != null) {
            AABB bounds = new AABB(from, to).inflate(1);
            for (SubLevel body : container.queryIntersecting(new BoundingBox3d(bounds))) {
                if (body == targetBody || body.isRemoved()) continue;
                var pose = poses.apply(body);
                collect(level, pose.transformPositionInverse(from), pose.transformNormalInverse(direction), body, layers);
            }
        }
        return sumConnected(level, layers);
    }

    private static void collect(Level level, Vec3 from, Vec3 direction, SubLevel body, List<Layer> layers) {
        var bounds = body == null ? null : body.getPlot().getBoundingBox();
        BlockGetter.traverseBlocks(from, from.add(direction.scale(RANGE)), level, (view, pos) -> {
            if (bounds != null && !bounds.contains(pos.getX(), pos.getY(), pos.getZ())) return null;
            if (!view.hasChunkAt(pos) || Sable.HELPER.getContaining(level, pos) != body) return null;
            var state = view.getBlockState(pos);
            if (state.isAir()) return null;

            var contact = EquivalentArmor.envelope(level, pos, state,
                    from.subtract(Vec3.atLowerCornerOf(pos)), direction);
            double entry = Math.max(0, contact.entry());
            double exit = Math.min(RANGE, contact.exit());
            if (exit - entry > EPSILON) layers.add(new Layer(pos.immutable(), state, entry, exit));
            return null;
        }, ignored -> null);
    }

    private static Totals sumConnected(Level level, List<Layer> layers) {
        layers.sort(Comparator.comparingDouble(Layer::entry));
        double coveredUntil = 0;
        double thickness = 0, toughness = 0, hardness = 0;
        int count = 0;
        try (var ignored = EquivalentArmor.baseMaterial()) {
            for (Layer layer : layers) {
                // Distances are measured from the aimed surface. Crossing air ends the stack,
                // even when the next shape occupies an adjacent cell or the same Sable body.
                if (layer.entry() > coveredUntil + CONTACT_TOLERANCE) break;
                double length = Math.min(RANGE - thickness, layer.exit() - layer.entry());
                if (length <= EPSILON) break;
                var provider = BlockArmorPropertiesHandler.getProperties(layer.state());
                toughness += length * provider.toughness(level, layer.state(), layer.pos(), true);
                hardness += length * provider.hardness(level, layer.state(), layer.pos(), true);
                thickness += length;
                count++;
                coveredUntil = Math.max(coveredUntil, layer.exit());
            }
        }
        return new Totals(thickness, toughness, hardness, count);
    }

    private ArmorInspectionRay() {}
}
