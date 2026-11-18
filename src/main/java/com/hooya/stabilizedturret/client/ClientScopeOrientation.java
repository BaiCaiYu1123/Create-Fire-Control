package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.ScopeOrientationMath;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload;
import com.hooya.stabilizedturret.network.ScopeOrientationPayload.Heading;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Reads the same interpolated Sable poses as the visible vehicle, once per HUD draw. */
final class ClientScopeOrientation {
    private static ScopeOrientationPayload current = ScopeOrientationPayload.inactive();
    private static ScopeOrientationPayload previous = current;

    static void accept(ScopeOrientationPayload payload) {
        if (!ClientAimState.isScoped() || ClientAimState.isPodView()) {
            clear();
            return;
        }
        previous = current;
        current = payload;
    }

    static void clear() {
        current = ScopeOrientationPayload.inactive();
        previous = current;
    }

    static Angles angles(float partialTick) {
        if (!ClientAimState.isScoped() || ClientAimState.isPodView()) return null;
        Double hull = yaw(current.hull(), previous.hull(), partialTick);
        Double turret = yaw(current.turret(), previous.turret(), partialTick);
        return hull == null || turret == null ? null : new Angles(hull, turret);
    }

    private static Double yaw(Heading heading, Heading old, float partialTick) {
        if (heading == null) return null;
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        Vec3 direction = heading.worldForward();
        if (heading.bodyId() != null) {
            var body = SubLevelContainer.getContainer(level).getSubLevel(heading.bodyId());
            if (body != null && body.isRemoved()) return null;
            if (body instanceof ClientSubLevel client) {
                var local = heading.localForward();
                var rendered = client.renderPose(partialTick).transformNormal(
                        new org.joml.Vector3d(local.x, local.y, local.z));
                direction = new Vec3(rendered.x, rendered.y, rendered.z);
            } else if (old != null && heading.bodyId().equals(old.bodyId())) {
                return interpolate(old.worldForward(), direction, partialTick);
            }
        } else if (old != null && old.bodyId() == null) {
            return interpolate(old.worldForward(), direction, partialTick);
        }
        return direction.horizontalDistanceSqr() > 1.0e-8 ? ScopeOrientationMath.yaw(direction) : null;
    }

    private static Double interpolate(Vec3 before, Vec3 after, float partialTick) {
        if (before.horizontalDistanceSqr() < 1.0e-8 || after.horizontalDistanceSqr() < 1.0e-8) return null;
        double start = ScopeOrientationMath.yaw(before);
        return start + ScopeOrientationMath.wrap(ScopeOrientationMath.yaw(after) - start)
                * Math.clamp(partialTick, 0, 1);
    }

    record Angles(double hull, double turret) {
        double relativeTurret() { return ScopeOrientationMath.wrap(turret - hull); }
    }

    private ClientScopeOrientation() {}
}
