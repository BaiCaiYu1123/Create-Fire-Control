package com.hooya.stabilizedturret.content.armor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Carries the actual local surface normal beyond CBC's penetration callback. */
public final class ArmorHitResult extends BlockHitResult {
    private final Vec3 normal;
    public ArmorHitResult(Vec3 location,Direction direction,BlockPos pos,boolean inside,Vec3 normal){
        super(location,direction,pos.immutable(),inside);this.normal=normal;
    }
    public Vec3 normal(){return normal;}
    @Override public ArmorHitResult withDirection(Direction direction){return new ArmorHitResult(getLocation(),direction,getBlockPos(),isInside(),normal);}
    @Override public ArmorHitResult withPosition(BlockPos pos){return new ArmorHitResult(getLocation(),getDirection(),pos,isInside(),normal);}
}
