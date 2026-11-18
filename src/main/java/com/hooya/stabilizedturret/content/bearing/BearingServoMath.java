package com.hooya.stabilizedturret.content.bearing;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Coordinate conversion and bounded motion shared by the controller and native checks. */
public final class BearingServoMath {
    private BearingServoMath() {}

    public static double wrapRadians(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }

    // Simulated's canonical twist is around facing-local Y. Map it to hinge-local X.
    public static Quaterniond hingeFrame(Quaterniondc facing, double zeroRadians) {
        return new Quaterniond(facing).rotateY(-zeroRadians).rotateZ(Math.PI / 2.0);
    }

    public static double jointAngle(Quaterniondc parentFrame, Quaterniondc childFrame) {
        Quaterniond relative = new Quaterniond(parentFrame).conjugate().mul(childFrame).normalize();
        return wrapRadians(2.0 * Math.atan2(relative.x, relative.w));
    }

    /** A free yaw hinge can rebase its twist without changing the anchor or its locked axes. */
    public static Quaterniond yawMotorFrame(Quaterniondc baseFrame, double measuredTwist) {
        return new Quaterniond(baseFrame).rotateX(-wrapRadians(measuredTwist));
    }

    /** Passive bearing feedback while a blueprint's cannon entities are still loading. */
    public static Vector3d startupLocalRay(Vector3d plateAxis) {
        Vector3d ray = new Vector3d(plateAxis).cross(new Vector3d(0, 1, 0));
        if (ray.lengthSquared() < 1e-8) ray.set(0, 0, 1);
        return ray.normalize();
    }

    /** Inertia about the anchor, including the parallel-axis contribution. */
    public static double pivotInertia(double centerInertia, double mass, Vector3d centerToAnchor,
                                      Vector3d localAxis) {
        double axial = centerToAnchor.dot(localAxis);
        return Math.max(0.01, centerInertia + mass * Math.max(0.0,
                centerToAnchor.lengthSquared() - axial * axial));
    }

    public static Motor motor(double childInertia, double parentInertia) {
        double child = Math.max(0.01, childInertia);
        double effective = Double.isFinite(parentInertia) && parentInertia > 0.0
                ? 1.0 / (1.0 / child + 1.0 / parentInertia) : child;
        // Sable uses Rapier's acceleration-based motor. These are tuned gains,
        // scaled for the articulated load; they are not force-based spring units.
        double frequency = 240.0;
        return new Motor(effective * frequency * frequency,
                effective * 2.0 * frequency,
                // Finite acceleration authority; external constraints can overpower the servo.
                effective * 3600.0);
    }

    public record Motor(double stiffness, double damping, double maxTorque) {}

    /** Position + velocity feed-forward through Sable's position-only motor API. */
    public static double servoGoal(double position, double positionRate, double velocityRate,
                                   double dt, Motor motor) {
        return wrapRadians(position + positionRate * dt
                + velocityRate * motor.damping() / motor.stiffness());
    }

    /** A child-only manual motor follows the carrier without applying a motor impulse to it. */
    public static double manualWorldMotorGoal(double position, double operatorRate, double parentRate,
                                               double dt, Motor motor) {
        return servoGoal(position, operatorRate + parentRate, operatorRate + parentRate, dt, motor);
    }

    /** Velocity feed-forward for a reference held fixed for one complete physics step. */
    public static double heldTargetVelocityRate(double rate, double dt, Motor motor) {
        if (!(motor.damping() > 0.0)) return 0.0;
        double ratio = dt * motor.stiffness() / motor.damping();
        double weight = ratio < 1e-6 ? 1.0 - ratio * 0.5
                : ratio < 700.0 ? ratio / Math.expm1(ratio) : 0.0;
        return rate * weight;
    }

    /** Keep look-ahead from commanding a pose beyond the operator's final aim. */
    public static double boundAimMotorGoal(double motorGoal, double aimGoal, double measured) {
        double step = wrapRadians(motorGoal - measured);
        double remaining = wrapRadians(aimGoal - measured);
        if (Math.abs(remaining) < 1e-9) return wrapRadians(aimGoal);
        // Leave half the remaining travel for braking. An eccentric nested load
        // retains momentum through its locked linear axes, and native pose correction
        // advances farther than the reported angular velocity predicts.
        double landing = remaining * 0.5;
        step = Math.clamp(step, Math.min(0.0, landing), Math.max(0.0, landing));
        return wrapRadians(measured + step);
    }

    /** The operator's braking envelope must never reduce carrier compensation. */
    public static double boundOperatorMotorGoal(double motorGoal, double aimGoal, double measured,
                                                double carrierPrediction) {
        return wrapRadians(boundAimMotorGoal(wrapRadians(motorGoal - carrierPrediction), aimGoal, measured)
                + carrierPrediction);
    }

    /** Active braking uses the visible world motion, independently of carrier prediction. */
    public static double brakeAimMotorGoal(double motorGoal, double remaining, double worldRate,
                                          double dt, Motor motor) {
        double allowedRate = Math.clamp(worldRate, Math.min(0.0, remaining / dt), Math.max(0.0, remaining / dt));
        return wrapRadians(motorGoal - (worldRate - allowedRate) * motor.damping() / motor.stiffness());
    }

    public static final class AimVelocity {
        private final Vector3d previous = new Vector3d();
        private final Vector3d previousReference = new Vector3d();
        private boolean initialized;
        public void reset() { initialized = false; }
        public double sample(Vector3d actual, Vector3d axis, double dt) {
            double rate = initialized ? signedDirectionAngle(previous, actual, axis) / dt : 0.0;
            previous.set(actual);
            initialized = true;
            return rate;
        }

        /** Measure relative joint travel, then remove only the stabilizer's travel on that same parent. */
        public double sampleCompensated(Vector3d actual, Vector3d compensatedReference,
                                        Vector3d commandedReference, Quaterniondc parent,
                                        Vector3d localAxis, double dt) {
            Vector3d localActual = parent.transformInverse(new Vector3d(actual));
            Vector3d localReference = parent.transformInverse(new Vector3d(compensatedReference));
            double rate = 0.0;
            if (initialized) {
                double relativeTravel = signedDirectionAngle(previous, localActual, localAxis);
                double stabilizationTravel = signedDirectionAngle(previousReference, localReference, localAxis);
                rate = operatorCorrection(relativeTravel, stabilizationTravel) / dt;
            }
            previous.set(localActual);
            // Save the reference AFTER head traverse. Next step's reference BEFORE
            // traverse then differs only by automatic compensation, not mouse input.
            parent.transformInverse(new Vector3d(commandedReference), previousReference);
            initialized = true;
            return rate;
        }
    }

    /** Signed head traverse remaining after the automatic part of a joint correction is removed. */
    public static double operatorCorrection(double totalCorrection, double stabilizationCorrection) {
        return wrapRadians(totalCorrection - stabilizationCorrection);
    }

    /** Head acquisition follows its immediate parent; an acquired sight remains world-stabilized. */
    public static final class ParentTraverse {
        private final Quaterniond previousParent = new Quaterniond();
        private boolean initialized;
        public void reset() { initialized = false; }
        public Vector3d carriedDirection(Vector3d worldDirection, Quaterniondc parent) {
            if (!initialized) return new Vector3d(worldDirection);
            return parent.transform(previousParent.transformInverse(new Vector3d(worldDirection)));
        }
        public void observe(Quaterniondc parent) {
            previousParent.set(parent);
            initialized = true;
        }
    }

    /** Stop inheriting the carrier at acquisition, so a carrier turn cannot carry aim past its endpoint. */
    public static double boundParentTraverse(double headError, double parentTravel) {
        if (headError * parentTravel > 0.0 && Math.abs(parentTravel) >= Math.abs(headError)) return headError;
        return parentTravel;
    }

    /** Finish braking against the acquired world target; an opposing carrier must not keep acquisition alive. */
    public static boolean followParentDuringTraverse(double headError, double maxSpeed, double dt) {
        double acceleration = Math.max(Math.toRadians(720.0), maxSpeed * 24.0);
        double landing = Math.max(1e-6, maxSpeed * dt + maxSpeed * maxSpeed / (2.0 * acceleration));
        return maxSpeed > 0.0 && Math.abs(headError) > landing;
    }

    /** A pose lock transports unfinished head traverse without rate-limiting automatic geometry changes. */
    public static final class StabilizedAimMotion {
        private double remaining;
        private boolean initialized;
        public void reset() { initialized = false; remaining = 0.0; }
        public boolean initialized() { return initialized; }
        public double compensation(double previousGoalErrorNow) { return compensation(previousGoalErrorNow, true); }
        public double compensation(double previousGoalErrorNow, boolean circular) {
            double difference = previousGoalErrorNow - remaining;
            return initialized ? (circular ? wrapRadians(difference) : difference) : 0.0;
        }
        public double advance(double totalCorrection, double compensation, double maxStep) {
            return advance(totalCorrection, compensation, maxStep, true);
        }
        public double advance(double totalCorrection, double compensation, double maxStep, boolean circular) {
            double headError = totalCorrection - compensation;
            if (circular) headError = wrapRadians(headError);
            return compensation + Math.clamp(headError, -Math.max(0.0, maxStep), Math.max(0.0, maxStep));
        }
        public void record(double totalCorrection, double appliedTravel) { record(totalCorrection, appliedTravel, true); }
        public void record(double totalCorrection, double appliedTravel, boolean circular) {
            remaining = totalCorrection - appliedTravel;
            if (circular) remaining = wrapRadians(remaining);
            initialized = true;
        }
    }

    public static double signedDirectionAngle(Vector3d current, Vector3d desired, Vector3d axis) {
        Vector3d from = new Vector3d(current).sub(new Vector3d(axis).mul(current.dot(axis)));
        Vector3d to = new Vector3d(desired).sub(new Vector3d(axis).mul(desired.dot(axis)));
        if (from.lengthSquared() < 1e-10 || to.lengthSquared() < 1e-10) return 0.0;
        from.normalize(); to.normalize();
        return Math.atan2(axis.dot(new Vector3d(from).cross(to)), Math.clamp(from.dot(to), -1.0, 1.0));
    }

    /** Traverse is measured about the bearing axis in its immediate parent's coordinates. */
    public static double parentRelativeDirectionAngle(Quaterniondc parent, Vector3d localAxis,
                                                       Vector3d currentWorld, Vector3d desiredWorld) {
        return signedDirectionAngle(parent.transformInverse(new Vector3d(currentWorld)),
                parent.transformInverse(new Vector3d(desiredWorld)), localAxis);
    }

    /** Elevation owns elevation; a target behind the turret must not request a half-turn in pitch. */
    public static Vector3d pitchDirection(Vector3d requested,Vector3d barrelHeading,Quaterniondc parent) {
        Vector3d up=parent.transform(new Vector3d(0,1,0)).normalize();
        double vertical=Math.clamp(requested.dot(up),-1,1);
        Vector3d horizontal=new Vector3d(barrelHeading).sub(new Vector3d(up).mul(barrelHeading.dot(up)));
        if (horizontal.lengthSquared()<1e-12) horizontal=parent.transform(new Vector3d(0,0,1));
        return horizontal.normalize(Math.sqrt(Math.max(0,1-vertical*vertical)))
                .add(up.mul(vertical)).normalize();
    }

    public static Vector3d advancePitchDirection(Vector3d held,Vector3d requested,Vector3d axis,
                                                 Vector3d barrelHeading,Quaterniondc parent,double step) {
        Vector3d next=rotateAimDirection(pitchDirection(held,barrelHeading,parent),
                pitchDirection(requested,barrelHeading,parent),axis,step);
        // Store the selected world azimuth for carrier compensation; only the
        // physical elevation loop projects it onto the current turret heading.
        return pitchDirection(next,requested,parent);
    }

    /** Advance only operator motion around this axis; carrier motion never consumes this rate budget. */
    public static Vector3d advanceAimDirection(Vector3d held, Vector3d requested, Vector3d axis,
                                               double maxRadiansPerSecond, double dt) {
        double angle = signedDirectionAngle(held, requested, axis);
        double step = Math.clamp(angle, -Math.max(0.0, maxRadiansPerSecond) * dt,
                Math.max(0.0, maxRadiansPerSecond) * dt);
        return rotateAimDirection(held, requested, axis, step);
    }

    public static Vector3d rotateAimDirection(Vector3d held, Vector3d requested, Vector3d axis, double step) {
        double axial = Math.clamp(requested.dot(axis), -1.0, 1.0);
        Vector3d transverse = new Vector3d(held).sub(new Vector3d(axis).mul(held.dot(axis)));
        if (transverse.lengthSquared() < 1e-12) {
            transverse.set(requested).sub(new Vector3d(axis).mul(axial));
        }
        if (transverse.lengthSquared() < 1e-12) return new Vector3d(requested);
        transverse.normalize(Math.sqrt(Math.max(0.0, 1.0 - axial * axial)));
        new Quaterniond().rotationAxis(step, axis.x, axis.y, axis.z).transform(transverse);
        return transverse.add(new Vector3d(axis).mul(axial)).normalize();
    }

    /** Short acceleration/braking ramps around a constant-rate traverse. */
    public static final class OperatorMotion {
        private double velocity;

        public void reset() { velocity = 0.0; }
        public double velocity() { return velocity; }

        /** Native manual braking must not wait for an obsolete command to coast through zero. */
        public boolean restartIfReversing(double error) {
            if (Math.abs(error) < 1e-8 || velocity * error >= 0.0) return false;
            reset();
            return true;
        }

        public double advance(double error, double maxSpeed, double dt) {
            return advanceWithAcceleration(error, maxSpeed, dt, Math.max(Math.toRadians(720.0), maxSpeed * 24.0));
        }

        /** Disabled stabilization uses a visible ramp on both starting and stopping. */
        public double advanceDamped(double error, double maxSpeed, double dt) {
            return advanceWithAcceleration(error, maxSpeed, dt, Math.max(1e-6, maxSpeed / 0.8));
        }

        private double advanceWithAcceleration(double error, double maxSpeed, double dt, double acceleration) {
            maxSpeed = Math.max(0.0, maxSpeed);
            if (dt <= 0.0 || maxSpeed == 0.0 || Math.abs(error) < 1e-8) {
                reset();
                return 0.0;
            }
            velocity=Math.clamp(velocity,-maxSpeed,maxSpeed);
            double brakingSpeed = Math.sqrt(2.0 * acceleration * Math.abs(error)
                    + acceleration * acceleration * dt * dt) - acceleration * dt;
            double requested = Math.copySign(Math.min(maxSpeed, brakingSpeed), error);
            double previous = velocity;
            velocity += Math.clamp(requested - velocity, -acceleration * dt, acceleration * dt);
            double step = (previous + velocity) * 0.5 * dt;
            if (Math.abs(step) >= Math.abs(error) && step * error > 0.0) {
                reset();
                return error;
            }
            return step;
        }
    }

    /** Physics can sample before the controller refreshes this game tick's command. */
    public static boolean liveAimCommand(long commandTick, long currentTick) {
        return commandTick != Long.MIN_VALUE && currentTick >= commandTick
                && currentTick - commandTick <= 1;
    }

    /** A manual aim sample rides its immediate parent until the next operator command. */
    public static final class ParentAimTarget {
        private final Vector3d localDirection = new Vector3d();
        public void set(Vector3d worldDirection, Quaterniondc parent) {
            parent.transformInverse(new Vector3d(worldDirection), localDirection).normalize();
        }
        public Vector3d worldDirection(Quaterniondc parent) {
            return parent.transform(new Vector3d(localDirection));
        }
    }

    /** Relative joint velocity needed to keep a world direction fixed on a tilted carrier. */
    public static double stabilizationRate(Vector3d direction, Vector3d axis, Vector3d parentAngularVelocity) {
        double axial = Math.clamp(direction.dot(axis), -1.0, 1.0);
        double transverseSqr = 1.0 - axial * axial;
        if (transverseSqr < 1e-6) return -parentAngularVelocity.dot(axis);
        return (axial * parentAngularVelocity.dot(direction) - parentAngularVelocity.dot(axis)) / transverseSqr;
    }

    public static final class MotorTarget {
        private boolean initialized;
        private double previous;
        private boolean carrierInitialized;
        private double previousCarrierRate;
        private double carrierRateChange;
        public void reset() {
            initialized = false;
            carrierInitialized = false;
            carrierRateChange = 0.0;
        }
        /** A past carrier step can still contribute look-ahead after its real velocity stops. */
        public boolean hasCarrierPrediction(double threshold) {
            return carrierInitialized && (Math.abs(previousCarrierRate) > threshold
                    || Math.abs(carrierRateChange) > threshold);
        }
        public double velocity(double position, double initialVelocity, double dt) {
            double rate = initialized ? wrapRadians(position - previous) / dt : initialVelocity;
            previous = position;
            initialized = true;
            return rate;
        }
        /** Predict carrier displacement without extrapolating coupled hinge oscillations. */
        public double predictHeldRate(double position, double initialVelocity, double averageOperatorRate,
                                      double terminalOperatorRate, double dt) {
            double rate = velocity(position, initialVelocity, dt);
            double carrierRate = rate - averageOperatorRate;
            double change = carrierInitialized ? carrierRate - previousCarrierRate : 0.0;
            // Filter only acceleration look-ahead. The complete carrier rate is
            // retained, independently of operator traverse speed. Unfiltered
            // second differences excite eccentric yaw/pitch loads at 40 Hz.
            carrierRateChange += (change - carrierRateChange) * -Math.expm1(-2.0 * dt);
            previousCarrierRate = carrierRate;
            carrierInitialized = true;
            return carrierRate + carrierRateChange + terminalOperatorRate;
        }
    }

    /** Removes small native pose/velocity bias; large external disturbances never wind it up. */
    public static final class MotorResidual {
        private double correction;
        public void reset() { correction = 0.0; }
        public double update(double goal, double measured) {
            return updateWithGain(goal, measured, 0.4, Math.toRadians(1.0));
        }
        /** Integrate slowly enough for the coupled hinge/translation response to settle. */
        public double update(double goal, double measured, double dt) {
            return updateWithGain(goal, measured, Math.clamp(6.0 * dt, 0.0, 0.1), Math.toRadians(2.0));
        }
        private double updateWithGain(double goal, double measured, double gain, double limit) {
            double error = wrapRadians(goal - measured);
            if (Math.abs(error) > Math.toRadians(2.0)) {
                correction = 0.0;
            } else {
                correction = Math.clamp(correction + error * gain,
                        -limit, limit);
            }
            return correction;
        }
    }

    /** Narrowing a configured stop must not abruptly correct an already-outside body. */
    public static final class PitchStops {
        private boolean initialized;
        private double min;
        private double max;
        private boolean transitioning;

        public void reset() { initialized = false; }

        public void update(double requestedMin, double requestedMax, double measured, double dt) {
            if (!initialized) {
                min = Math.min(requestedMin, wrapRadians(measured));
                max = Math.max(requestedMax, wrapRadians(measured));
                initialized = true;
            }
            double step = Math.toRadians(60.0) * dt;
            min = requestedMin <= min ? requestedMin : Math.min(requestedMin, min + step);
            max = requestedMax >= max ? requestedMax : Math.max(requestedMax, max - step);
            transitioning = Math.abs(min - requestedMin) > 1e-10 || Math.abs(max - requestedMax) > 1e-10;
        }

        public boolean transitioning() { return transitioning; }
        public double goal(double requested, double measured, double dt) {
            double goal = wrapRadians(requested);
            if (transitioning) {
                double step = Math.toRadians(60.0) * dt;
                goal = wrapRadians(measured) + Math.clamp(wrapRadians(goal - measured), -step, step);
            }
            return Math.clamp(goal, min, max);
        }

        public double min() { return min; }
        public double max() { return max; }
    }

    /** Yaw stops use a moving native frame so Rapier never solves a twist near +/-pi. */
    public static final class YawStops {
        private boolean initialized;
        private boolean transitioning;
        private double min, max, reference;

        public void reset() { initialized = false; }

        public void update(double requestedMin, double requestedMax, double measured, double dt) {
            double wrapped = wrapRadians(measured);
            // Preserve the return side if an outside body drifts across the rear seam.
            reference = initialized && transitioning ? reference + wrapRadians(wrapped - reference) : wrapped;
            if (!initialized) {
                min = Math.min(requestedMin, reference);
                max = Math.max(requestedMax, reference);
                initialized = true;
            }
            double step = Math.toRadians(60.0) * dt;
            min = requestedMin <= min ? requestedMin : Math.min(requestedMin, min + step);
            max = requestedMax >= max ? requestedMax : Math.max(requestedMax, max - step);
            transitioning = Math.abs(min - requestedMin) > 1e-10 || Math.abs(max - requestedMax) > 1e-10;
        }

        public boolean transitioning() { return transitioning; }
        public double reference() { return reference; }
        public double min() { return min; }
        public double max() { return max; }

        public double goal(double requested, double dt) {
            double goal = wrapRadians(requested);
            if (transitioning) {
                double step = Math.toRadians(60.0) * dt;
                goal = reference + Math.clamp(goal - reference, -step, step);
            }
            return Math.clamp(goal, min, max);
        }

        // Distant stops are clipped in the moving frame. They cannot be reached in
        // a normal step and move with the body until the real stop is within range.
        public double nativeMin() { return Math.clamp(min - reference, -Math.PI / 2, Math.PI / 2); }
        public double nativeMax() { return Math.clamp(max - reference, -Math.PI / 2, Math.PI / 2); }
        public double motorGoal(double goal) { return Math.clamp(goal, nativeMin(), nativeMax()); }
    }

    /** Constant-rate operator command. Carrier compensation must bypass this limiter. */
    public static final class Trajectory {
        private double position;
        private boolean initialized;

        public void reset(double angle) {
            position = angle;
            initialized = true;
        }

        public double advance(double desired, double measured, double maxSpeed, double dt) {
            if (!initialized) reset(measured);
            position = measured + wrapRadians(position - measured);
            double error = wrapRadians(desired - position);
            double speed = Math.max(0.0, maxSpeed);
            position += Math.clamp(error, -speed * dt, speed * dt);
            return wrapRadians(position);
        }
    }
}
