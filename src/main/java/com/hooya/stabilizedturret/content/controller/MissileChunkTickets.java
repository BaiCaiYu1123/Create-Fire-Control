package com.hooya.stabilizedturret.content.controller;

import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Compatibility shim kept in each guidance state. Guided missiles deliberately
 * hold no chunk tickets: the server handler advances them outside loaded
 * terrain and the arrow mixin bypasses collision there. A Mach-2 moving ticket
 * can generate hundreds of remote chunks per second and must never be used.
 */
final class MissileChunkTickets {
    MissileChunkTickets(TicketType<UUID> ignoredType) {
    }

    void update(Entity ignoredMissile, @Nullable Vec3 ignoredVelocity) {
    }

    void release() {
    }
}