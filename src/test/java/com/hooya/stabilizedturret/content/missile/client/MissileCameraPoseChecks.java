package com.hooya.stabilizedturret.content.missile.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** A missile directly ahead must stay at screen centre for every camera bearing. */
public final class MissileCameraPoseChecks {
    public static void main(String[] args) {
        for (int yaw = 0; yaw < 360; yaw += 45) {
            for (int pitch : new int[]{-70, 0, 55}) {
                double azimuth = Math.toRadians(yaw);
                double elevation = Math.toRadians(pitch);
                Vector3f forward = new Vector3f(
                        (float) (Math.sin(azimuth) * Math.cos(elevation)),
                        (float) Math.sin(elevation),
                        (float) (-Math.cos(azimuth) * Math.cos(elevation)));
                Matrix4f view = new Matrix4f().lookAt(new Vector3f(), forward,
                        new Vector3f(0, 1, 0));
                Matrix4f original = new Matrix4f(view);
                var pose = ClientMissileRenderer.cameraPose(view);
                for (float distance : new float[]{8, 256, 16000}) {
                    Vector3f viewed = pose.last().pose().transformPosition(
                            new Vector3f(forward).mul(distance));
                    require(Math.abs(viewed.x) < 0.01f && Math.abs(viewed.y) < 0.01f
                                    && Math.abs(viewed.z + distance) < 0.01f,
                            "Missile ahead is off-centre at yaw=" + yaw + ", pitch=" + pitch);
                }
                Vector3f normal = pose.last().normal().transform(new Vector3f(forward));
                require(normal.distance(new Vector3f(0, 0, -1)) < 0.0001f,
                        "Trail normal must rotate with its vertices");
                require(view.equals(original), "Rendering must preserve the event view matrix");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
