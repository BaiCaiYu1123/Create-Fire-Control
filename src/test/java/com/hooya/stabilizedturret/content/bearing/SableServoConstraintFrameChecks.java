package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies mutable native frames, ownership of snapshots and the old free-joint path. */
public final class SableServoConstraintFrameChecks {
    private SableServoConstraintFrameChecks() {}

    public static void main(String[] args) throws Exception {
        copiesBothMutableFrames();
        recordsUnmodelledRows();
        recordsFixedWelds();
        identityChildFramePreservesPrediction();
        childFrameChangesAngularPrediction();
        System.out.println("PASS: mutable generic free-joint frames, safe row rejection and identity parity");
    }

    private static void recordsFixedWelds() throws Exception {
        var handle = new DummyHandle();
        Vector3d a = new Vector3d(1, 2, 3), b = new Vector3d(4, 5, 6);
        Quaterniond orientation = new Quaterniond().rotationX(.4);
        SableServoConstraints.created(null, null, null, new FixedConstraintConfiguration(a, b, orientation), handle);
        Object tracked = tracked(handle);
        a.zero(); b.zero(); orientation.identity();
        require((int) field(tracked, "lockedMask") == 63, "fixed weld includes all six native locked rows");
        require(vector(tracked, "parentAnchor").equals(new Vector3d(1, 2, 3)), "fixed parent anchor is copied");
        require(vector(tracked, "childAnchor").equals(new Vector3d(4, 5, 6)), "fixed child anchor is copied");
        require(rotation(tracked, "frame").equals(new Quaterniond().rotationX(.4), 0), "fixed weld frame matches Sable native parent frame");
        require(rotation(tracked, "childFrame").equals(new Quaterniond(), 0), "fixed weld uses native identity child frame");
        var fixedMethod=tracked.getClass().getDeclaredMethod("fixed");
        fixedMethod.setAccessible(true);
        var rigidMethod=tracked.getClass().getDeclaredMethod("rigid");
        rigidMethod.setAccessible(true);
        require((boolean) fixedMethod.invoke(tracked), "plain fixed weld is eligible for the native fixed-row model");
        SableServoConstraints.motor(handle,ConstraintJointAxis.ANGULAR_X,0,0,0,true,0);
        require(!(boolean) fixedMethod.invoke(tracked), "zero-gain native motor bit prevents incorrectly selecting the SIMD weld model");
        require((boolean) rigidMethod.invoke(tracked), "all-six-row weld remains a kinematic rigid link despite a zero-gain motor bit");
        SableServoConstraints.removed(handle);
        var generic = new DummyHandle();
        SableServoConstraints.created(null, null, null, new GenericConstraintConfiguration(
                a, b, orientation, new Quaterniond(), Set.of(ConstraintJointAxis.values())), generic);
        require((int) field(tracked(generic), "lockedMask") == 63, "generic full weld has the same locked-row mask");
        SableServoConstraints.removed(generic);
        SableServoConstraints.clear(null);
    }

    private static void copiesBothMutableFrames() throws Exception {
        var handle = new DummyHandle();
        Vector3d first = new Vector3d(1, 2, 3), second = new Vector3d(4, 5, 6);
        Quaterniond qa = new Quaterniond().rotationY(.7), qb = new Quaterniond().rotationX(.03);
        SableServoConstraints.created(null, null, null,
                new GenericConstraintConfiguration(first, second, qa, qb, Set.of()), handle);
        Object tracked = tracked(handle);
        first.zero(); second.zero(); qa.identity(); qb.identity();
        require(vector(tracked, "parentAnchor").equals(new Vector3d(1, 2, 3)), "creation copies parent anchor");
        require(vector(tracked, "childAnchor").equals(new Vector3d(4, 5, 6)), "creation copies child anchor");
        require(rotation(tracked, "frame").equals(new Quaterniond().rotationY(.7), 0), "creation copies parent frame");
        require(rotation(tracked, "childFrame").equals(new Quaterniond().rotationX(.03), 0), "creation copies child frame");
        first.set(7, 8, 9); qa.rotationY(2.9);
        SableServoConstraints.frame(handle, true, first, qa);
        second.set(-1, -2, -3); qb.rotationZ(-.04);
        SableServoConstraints.frame(handle, false, second, qb);
        first.zero(); second.zero(); qa.identity(); qb.identity();
        require(vector(tracked, "parentAnchor").equals(new Vector3d(7, 8, 9)), "setFrame1 copies updated anchor");
        require(vector(tracked, "childAnchor").equals(new Vector3d(-1, -2, -3)), "setFrame2 copies updated anchor");
        require(rotation(tracked, "frame").equals(new Quaterniond().rotationY(2.9), 0), "setFrame1 copies updated orientation");
        require(rotation(tracked, "childFrame").equals(new Quaterniond().rotationZ(-.04), 0), "setFrame2 copies updated orientation");
        require(SableServoConstraints.order(handle) == 0, "frame changes preserve dense native row order");
        SableServoConstraints.removed(handle);

        var oldHandle = new DummyHandle();
        SableServoConstraints.created(null, null, null,
                new FreeConstraintConfiguration(new Vector3d(), new Vector3d(), new Quaterniond().rotationY(.4)), oldHandle);
        require(rotation(tracked(oldHandle), "childFrame").equals(new Quaterniond(), 0), "old free configuration uses identity child frame");
        SableServoConstraints.removed(oldHandle);
        SableServoConstraints.clear(null);
    }

    private static void recordsUnmodelledRows() throws Exception {
        var handle = new DummyHandle();
        SableServoConstraints.created(null, null, null,
                new GenericConstraintConfiguration(new Vector3d(), new Vector3d(), new Quaterniond(), new Quaterniond(), Set.of()), handle);
        Object tracked = tracked(handle);
        require((boolean) field(tracked, "freeAxes"), "empty locked axes may be represented as free motors");
        SableServoConstraints.lockedAxes(handle, new ConstraintJointAxis[]{ConstraintJointAxis.ANGULAR_Y});
        require(!(boolean) field(tracked, "freeAxes"), "new locked rows reject the optional free model");
        SableServoConstraints.lockedAxes(handle, new ConstraintJointAxis[0]);
        require(!(boolean) field(tracked, "freeAxes"), "empty lock call does not remove previously enabled native rows");
        SableServoConstraints.limit(handle, ConstraintJointAxis.ANGULAR_X, -.2, .2);
        require((int) field(tracked, "limitMask") != 0, "finite limit rows reject the optional free model");
        SableServoConstraints.limit(handle, ConstraintJointAxis.ANGULAR_X, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        require((int) field(tracked, "limitMask") != 0, "infinite bounds do not disable the native limit axis");
        SableServoConstraints.removed(handle);
        SableServoConstraints.clear(null);
    }

    private static void identityChildFramePreservesPrediction() {
        var old = world(new Quaterniond(), false);
        var explicit = world(new Quaterniond(), true);
        var initial = body(new Quaterniond().rotationY(.06).rotateX(.01));
        var a = predict(initial, old); var b = predict(initial, explicit);
        require(a.orientation().equals(b.orientation(), 0), "explicit identity keeps old pose bitwise");
        require(a.position().equals(b.position()), "explicit identity keeps old position bitwise");
        require(a.angularVelocity().equals(b.angularVelocity()), "explicit identity keeps old angular velocity bitwise");
        require(a.linearVelocity().equals(b.linearVelocity()), "explicit identity keeps old linear velocity bitwise");
    }

    private static void childFrameChangesAngularPrediction() {
        Quaterniond childFrame = new Quaterniond().rotationX(.06).rotateZ(-.02);
        var changed = predict(body(new Quaterniond()), world(childFrame, true));
        var equivalent = predict(body(childFrame), world(new Quaterniond(), false));
        Quaterniond changedJoint = changed.orientation().mul(childFrame);
        require(changedJoint.equals(equivalent.orientation(), 1e-12), "native child frame enters angular error on the right");
        require(changed.angularVelocity().distance(equivalent.angularVelocity()) < 1e-12, "child-frame prediction preserves world angular response");
        require(changed.orientation().angle() > 1e-3, "nonidentity child frame actually moves the physical body");
        require(childFrame.equals(new Quaterniond().rotationX(.06).rotateZ(-.02), 0), "prediction leaves caller frame unchanged");
    }

    private static ArticulatedServoPredictor.BodyState body(Quaterniond q) {
        return new ArticulatedServoPredictor.BodyState(3, new Matrix3d().scaling(.5), new Vector3d(), q, new Vector3d(), new Vector3d());
    }

    private static ArticulatedServoPredictor.WorldMotor world(Quaterniond childFrame, boolean explicit) {
        double[] k = {0, 0, 0, 10000, 10000, 10000}, d = {0, 0, 0, 100, 100, 100};
        double[] caps = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        return explicit ? new ArticulatedServoPredictor.WorldMotor(0, new Quaterniond(), new Vector3d(), new Vector3d(),
                new double[3], new double[3], k, d, caps, 56, false, 0, childFrame)
                : new ArticulatedServoPredictor.WorldMotor(0, new Quaterniond(), new Vector3d(), new Vector3d(),
                new double[3], new double[3], k, d, caps, 56, false, 0);
    }

    private static ArticulatedServoPredictor.BodyState predict(ArticulatedServoPredictor.BodyState body,
                                                               ArticulatedServoPredictor.WorldMotor world) {
        return ArticulatedServoPredictor.predict(List.of(body), List.of(), null, List.of(world), new Vector3d(),
                .025, 18, 2, 2, 550, 4, false).bodies().getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Object tracked(Object handle) throws Exception {
        Field field = SableServoConstraints.class.getDeclaredField("TRACKED"); field.setAccessible(true);
        return ((Map<Object, ?>) field.get(null)).get(handle);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Vector3d vector(Object target, String name) throws Exception { return (Vector3d) field(target, name); }
    private static Quaterniond rotation(Object target, String name) throws Exception { return (Quaterniond) field(target, name); }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }

    private static final class DummyHandle implements FreeConstraintHandle {
        @Override public void getJointImpulses(Vector3d linear, Vector3d angular) { throw new AssertionError("tracker mutates handle"); }
        @Override public void setContactsEnabled(boolean enabled) { throw new AssertionError("tracker mutates handle"); }
        @Override public void setMotor(ConstraintJointAxis axis, double goal, double k, double d, boolean limited, double cap) { throw new AssertionError("tracker mutates handle"); }
        @Override public void remove() { throw new AssertionError("tracker mutates handle"); }
        @Override public boolean isValid() { throw new AssertionError("tracker queries native handle"); }
    }
}
