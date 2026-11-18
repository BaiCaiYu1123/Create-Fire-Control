package dev.ryanhcode.sable.physics.impl.rapier;

import net.jpountz.lz4.LZ4FrameInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipInputStream;

/**
 * Test-only JNI bindings with an isolated DLL loader. The solver is the unchanged
 * native library from Sable 2.0.5. No Minecraft loader/world is required by box scenes.
 * This class and the checks are excluded from both shipped mod JARs.
 */
public final class Rapier3D {
    static {
        String arch = System.getProperty("os.arch").startsWith("aarch64") ? "aarch64" : "x86_64";
        String os = System.getProperty("os.name").toLowerCase();
        String name = "sable_rapier_" + arch + (os.contains("win") ? "_windows.dll"
                : os.contains("mac") ? "_macos.dylib" : "_linux.so");
        try (var input = Rapier3D.class.getResourceAsStream("/natives/sable_rapier/sable_rapier_binaries.zip.l4z");
             var zip = new ZipInputStream(new LZ4FrameInputStream(input))) {
            Path dir = Path.of("natives").toAbsolutePath();
            Files.createDirectories(dir);
            boolean found = false;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals(name)) continue;
                Path library = dir.resolve(name);
                Files.copy(zip, library, StandardCopyOption.REPLACE_EXISTING);
                System.load(library.toString());
                found = true;
                break;
            }
            if (!found) throw new IllegalStateException("Native library missing: " + name);
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    public static native long initialize(double gx, double gy, double gz, double scale);
    public static native void configFrequencyAndDamping(double frequency, double damping);
    public static native void configSolverIterations(int solver, int pgs, int stabilization);
    public static native void configMinIslandSize(int size);
    public static native void step(long scene, double dt);
    public static native void tick(long scene, double dt);
    public static native void dispose(long scene);
    public static native void createSubLevel(long scene, int id, double[] pose);
    public static native void setCenterOfMass(long scene, int id, double x, double y, double z);
    public static native void setLocalBounds(long scene, int id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    public static native void setMassProperties(long scene, int id, double mass, double[] center, double[] inertia);
    public static native void applyForceAndTorque(long scene, int id, double fx, double fy, double fz,
                                               double tx, double ty, double tz, boolean wakeUp);
    public static native void createBox(long scene, int id, double mass, double hx, double hy, double hz, double[] pose);
    public static native int newVoxelCollider(double friction, double volume, double restitution, boolean sensor,
            dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback callback);
    public static native void addVoxelColliderBox(int collider, double[] bounds);
    public static native void addChunk(long scene, int x, int y, int z, int[] blocks, boolean world, int body);
    public static native void removeChunk(long scene, int x, int y, int z, boolean world);
    public static native void teleportObject(long scene, int id, double x, double y, double z,
                                             double qx, double qy, double qz, double qw);
    public static native void getPose(long scene, int id, double[] pose);
    public static native void wakeUpObject(long scene, int id);
    public static native void getLinearVelocity(long scene, int id, double[] velocity);
    public static native void getAngularVelocity(long scene, int id, double[] velocity);
    public static native void addLinearAngularVelocities(long scene, int id, double x, double y, double z,
                                                       double ax, double ay, double az, boolean local);
    public static native long addGenericConstraint(long scene, int a, int b,
            double x1, double y1, double z1, double qx1, double qy1, double qz1, double qw1,
            double x2, double y2, double z2, double qx2, double qy2, double qz2, double qw2, int mask);
    public static native long addFreeConstraint(long scene, int a, int b,
            double x1, double y1, double z1, double x2, double y2, double z2,
            double qx, double qy, double qz, double qw);
    public static native long addRotaryConstraint(long scene, int a, int b,
            double x1, double y1, double z1, double x2, double y2, double z2,
            double ax, double ay, double az, double bx, double by, double bz);
    public static native long addFixedConstraint(long scene, int a, int b,
            double x1, double y1, double z1, double x2, double y2, double z2,
            double qx, double qy, double qz, double qw);
    public static native void setConstraintFrame(long scene, long joint, int side,
            double x, double y, double z, double qx, double qy, double qz, double qw);
    public static native void setConstraintContactsEnabled(long scene, long joint, boolean enabled);
    public static native void getConstraintImpulses(long scene, long joint, double[] impulses);
    public static native boolean isConstraintValid(long scene, long joint);
    public static native void removeConstraint(long scene, long joint);
    public static native void setConstraintMotor(long scene, long joint, int axis,
            double goal, double stiffness, double damping, boolean limited, double maxForce);
    public static native void setConstraintLimit(long scene, long joint, int axis, double min, double max);
    public static native void lockConstraintAxes(long scene, long joint, byte mask);
}
