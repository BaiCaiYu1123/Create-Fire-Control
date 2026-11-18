package com.hooya.stabilizedturret.content.missile;

import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Server-side heat source with analytical, entity-free movement. */
public record VirtualFlare(UUID id, Vec3 position, Vec3 velocity,
                           long birthTime, int lifetimeTicks, float heat,
                           long seed, double drag, double gravity) {
    public Vec3 positionAt(long gameTime) {
        double ticks = Math.max(0L, gameTime - birthTime);
        if (ticks == 0.0) return position;
        if (Math.abs(1.0 - drag) < 1.0e-8) {
            return position.add(velocity.scale(ticks)).add(0.0,
                    -gravity * ticks * (ticks + 1.0) * 0.5, 0.0);
        }
        double dragPower = Math.pow(drag, ticks);
        double velocitySum = drag * (1.0 - dragPower) / (1.0 - drag);
        double gravitySum = (ticks - velocitySum) / (1.0 - drag);
        return position.add(velocity.scale(velocitySum))
                .add(0.0, -gravity * gravitySum, 0.0);
    }
    public boolean expired(long gameTime) { return gameTime - birthTime >= lifetimeTicks; }
}
