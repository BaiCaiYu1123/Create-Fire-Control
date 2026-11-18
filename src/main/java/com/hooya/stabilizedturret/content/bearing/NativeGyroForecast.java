package com.hooya.stabilizedturret.content.bearing;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Read-only forecast of the gyroscopic velocity update in Sable 2.0.5's Rapier fork. */
final class NativeGyroForecast {
    private NativeGyroForecast() {}

    /**
     * The fork uses body-local angular velocity and its ordered principal inertia directly,
     * without applying the principal inertia frame. Preserve that behavior when forecasting;
     * applying the full inertia tensor here would predict a different solver.
     */
    static Vector3d predict(Matrix3d localInertia, Quaterniond worldOrientation,
                            Vector3d nativeOmega, double dt) {
        if (!Double.isFinite(dt) || dt <= 0.0 || dt > 0.1
                || !worldOrientation.isFinite() || !nativeOmega.isFinite()) {
            throw new IllegalArgumentException("Invalid gyroscopic forecast state");
        }
        double norm = worldOrientation.lengthSquared();
        if (norm < 0.999 || norm > 1.001) {
            throw new IllegalArgumentException("Gyroscopic forecast requires a unit orientation");
        }
        float[] eigenvalues = orderedEigenvalues(localInertia);
        Vector3d inversePrincipal = new Vector3d();
        Vector3d principal = new Vector3d();
        for (int axis = 0; axis < 3; axis++) {
            // MassProperties stores inverse inertia; principal_inertia() takes its inverse again.
            float eigen = Math.max(0.0f, eigenvalues[axis]);
            float inverse = eigen == 0.0f ? 0.0f : 1.0f / eigen;
            if (!Float.isFinite(inverse)) {
                throw new IllegalArgumentException("Unrepresentable principal inertia");
            }
            inversePrincipal.setComponent(axis, inverse);
            principal.setComponent(axis, inverse == 0.0f ? 0.0f : 1.0f / inverse);
        }
        Vector3d localOmega = new Quaterniond(worldOrientation).conjugate()
                .transform(new Vector3d(nativeOmega));
        Vector3d momentum = new Vector3d(localOmega).mul(principal);
        Vector3d nextMomentum = new Vector3d(localOmega).cross(momentum).mul(-dt).add(momentum);
        double nextNorm = nextMomentum.lengthSquared();
        if (nextNorm == 0.0) return new Vector3d(nativeOmega);
        nextMomentum.mul(Math.sqrt(momentum.lengthSquared() / nextNorm));
        Vector3d result = worldOrientation.transform(nextMomentum.mul(inversePrincipal));
        if (!result.isFinite()) throw new IllegalArgumentException("Nonfinite gyroscopic forecast");
        return result;
    }

    /**
     * Mirrors glamx 0.1 SymmetricEigen3::eigenvalues, including f32 intermediate rounding.
     * Eigenvalues are ascending. Eigenvector3 is eigenvector1.cross(eigenvector2), so the
     * generated principal frame is right-handed and Parry's determinant swap is unnecessary.
     */
    private static float[] orderedEigenvalues(Matrix3d inertia) {
        double[] elements = inertia.get(new double[9]);
        double scale = 0.0;
        for (double element : elements) {
            if (!Double.isFinite(element) || Math.abs(element) > Float.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid native inertia");
            }
            scale = Math.max(scale, Math.abs(element));
        }
        double symmetryTolerance = Math.max(1.0, scale) * 1.0e-10;
        if (Math.abs(inertia.m01() - inertia.m10()) > symmetryTolerance
                || Math.abs(inertia.m02() - inertia.m20()) > symmetryTolerance
                || Math.abs(inertia.m12() - inertia.m21()) > symmetryTolerance) {
            throw new IllegalArgumentException("Gyroscopic forecast requires symmetric inertia");
        }
        float xx = (float) inertia.m00(), yy = (float) inertia.m11(), zz = (float) inertia.m22();
        float xy = (float) inertia.m10(), xz = (float) inertia.m20(), yz = (float) inertia.m21();
        float p1 = xy * xy + xz * xz + yz * yz;
        if (p1 == 0.0f) {
            float[] diagonal = {xx, yy, zz};
            java.util.Arrays.sort(diagonal);
            return diagonal;
        }
        float q = (xx + yy + zz) / 3.0f;
        float dx = xx - q, dy = yy - q, dz = zz - q;
        float p = (float) Math.sqrt((dx * dx + dy * dy + dz * dz + 2.0f * p1) / 6.0f);
        float inverseP = 1.0f / p;
        float x = dx * inverseP, y = dy * inverseP, z = dz * inverseP;
        xy *= inverseP;
        xz *= inverseP;
        yz *= inverseP;
        // glam::Mat3 determinant = z_axis.dot(x_axis.cross(y_axis)).
        float crossX = xy * yz - xz * y;
        float crossY = xz * xy - x * yz;
        float crossZ = x * y - xy * xy;
        float r = (xz * crossX + yz * crossY + z * crossZ) / 2.0f;
        float pi = (float) Math.PI;
        float phi = r <= -1.0f ? pi / 3.0f : r >= 1.0f ? 0.0f : (float) Math.acos(r) / 3.0f;
        float high = q + 2.0f * p * (float) Math.cos(phi);
        float low = q + 2.0f * p * (float) Math.cos(phi + 2.0f * pi / 3.0f);
        float middle = 3.0f * q - high - low;
        if (!Float.isFinite(low) || !Float.isFinite(middle) || !Float.isFinite(high)) {
            throw new IllegalArgumentException("Unrepresentable native eigenvalues");
        }
        return new float[]{low, middle, high};
    }
}
