package com.hooya.stabilizedturret.content.controller;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Per-controller Sable recoil multiplier, in the same units as CBC's setting. */
public final class CannonRecoilSettings {
    public static final double DEFAULT = 40.0;
    public static final double MAX = 100.0;
    public static final String TAG = "CannonRecoilMagnitude";

    public static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(MAX, value)) : DEFAULT;
    }

    public static double read(CompoundTag tag) {
        return tag.contains(TAG, Tag.TAG_ANY_NUMERIC) ? clamp(tag.getDouble(TAG)) : DEFAULT;
    }

    private CannonRecoilSettings() {}
}
