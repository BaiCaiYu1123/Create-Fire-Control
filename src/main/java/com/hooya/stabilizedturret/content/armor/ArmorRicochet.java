package com.hooya.stabilizedturret.content.armor;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.CBCCompatTransformers;
import rbasamoyai.createbigcannons.utils.CBCUtils;

public final class ArmorRicochet {
    // More than the mesh's intersection tolerance, and well below a model pixel.
    private static final double SURFACE_CLEARANCE = 1e-4;

    public static Vec3 departure(Level level, BlockHitResult hit) {
        var pos = hit.getBlockPos();
        Vec3 localContact = PolyArmorImpacts.localPoint(level, pos, hit.getLocation());
        Vec3 worldContact = CBCCompatTransformers.transformVec3(level, localContact, Vec3.atCenterOf(pos));
        Vec3 normal = CBCUtils.getSurfaceNormalVector(level, hit).normalize();
        return worldContact.add(normal.scale(SURFACE_CLEARANCE));
    }

    public interface ClientPrediction {
        void fireControl$clearPredictedImpact();
    }

    private ArmorRicochet() {}
}
