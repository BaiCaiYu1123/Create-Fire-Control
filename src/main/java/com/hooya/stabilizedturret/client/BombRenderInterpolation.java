package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;

/** Render-only reconciliation; the authoritative VirtualBombFlight remains unchanged. */
public final class BombRenderInterpolation {
    private Vec3 previous, current, previousVelocity, velocity;
    public boolean initialized() { return current != null; }
    public void initialize(Vec3 position, Vec3 motion) {
        previous = current = position; previousVelocity = velocity = motion;
    }
    public void advance(Vec3 target, Vec3 targetVelocity) {
        if (!initialized()) { initialize(target, targetVelocity); return; }
        previous = current; previousVelocity = velocity;
        Vec3 predicted = current.add(velocity), error = target.subtract(predicted);
        double limit = 64 + velocity.length() * 10;
        if (error.lengthSqr() > limit * limit) {
            previous = current = target; previousVelocity = targetVelocity;
        } else current = predicted.add(error.scale(1.0 / 3.0));
        velocity = targetVelocity;
    }
    public Vec3 position(float partial) { return previous.lerp(current, partial); }
    public Vec3 velocity(float partial) { return previousVelocity.lerp(velocity, partial); }
}
