package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Vanilla caps entity pairing to the player's chunk view distance even when an
 * EntityType requests a larger tracking range. Guided ATGMs deliberately live
 * for at most twenty seconds, so pair only those exact entities out to the
 * fire-control range without changing culling for anything else.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity", priority = 2000)
public abstract class GuidedMissileTrackedEntityMixin {
    private static final double MAX_TRACKING_DISTANCE_SQR = 20000.0 * 20000.0;

    @Shadow
    @Final
    private Entity entity;

    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;

    @Shadow
    @Final
    private ServerEntity serverEntity;

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void createFireControl$trackGuidedMissileBeyondViewDistance(
            ServerPlayer player,
            CallbackInfo ci
    ) {
        if (!MianbaoAirDefenseCompat.isAnyFireControlMissile(entity)
                || (!entity.getPersistentData().getBoolean("CreateFireControlGuided")
                && !entity.getPersistentData().getBoolean("CreateFireControlAaGuided")
                && !entity.getPersistentData().getBoolean("CreateFireControlAircraftGuided"))) {
            return;
        }
        ci.cancel();
        if (player == entity) {
            return;
        }

        Vec3 worldPosition = MianbaoMissileCompat.getWorldPosition(entity);
        Vec3 delta = player.position().subtract(worldPosition);
        boolean shouldTrack = delta.x * delta.x + delta.z * delta.z <= MAX_TRACKING_DISTANCE_SQR
                && entity.broadcastToPlayer(player);
        if (shouldTrack) {
            if (seenBy.add(player.connection)) {
                serverEntity.addPairing(player);
            }
        } else if (seenBy.remove(player.connection)) {
            serverEntity.removePairing(player);
        }
    }
}
