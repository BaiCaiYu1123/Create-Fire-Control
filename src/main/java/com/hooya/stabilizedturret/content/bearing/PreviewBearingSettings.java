package com.hooya.stabilizedturret.content.bearing;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** One device's complete override. Defaults remain owned by the host computer. */
public record PreviewBearingSettings(double depression, double elevation, double speed,
                                    boolean reversed, boolean headHidden, Vec3 offset, YawLimits limits) {
    public boolean finite() {
        return Double.isFinite(depression) && Double.isFinite(elevation) && Double.isFinite(speed)
                && offset != null && Double.isFinite(offset.x) && Double.isFinite(offset.y)
                && Double.isFinite(offset.z) && limits != null;
    }

    public PreviewBearingSettings clamped() {
        return new PreviewBearingSettings(Math.clamp(depression, 0, 90), Math.clamp(elevation, 0, 89),
                Math.clamp(speed, 0, 720), reversed, headHidden,
                new Vec3(Math.clamp(offset.x, -20, 20), Math.clamp(offset.y, -20, 20),
                        Math.clamp(offset.z, -20, 20)), limits);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Depression", depression); tag.putDouble("Elevation", elevation);
        tag.putDouble("Speed", speed); tag.putBoolean("Reversed", reversed);
        tag.putBoolean("HeadHidden", headHidden);
        tag.putDouble("OffsetX", offset.x); tag.putDouble("OffsetY", offset.y); tag.putDouble("OffsetZ", offset.z);
        limits.save(tag);
        return tag;
    }

    public static PreviewBearingSettings load(CompoundTag tag) {
        return new PreviewBearingSettings(tag.getDouble("Depression"), tag.getDouble("Elevation"),
                tag.getDouble("Speed"), tag.getBoolean("Reversed"), tag.getBoolean("HeadHidden"),
                new Vec3(tag.getDouble("OffsetX"), tag.getDouble("OffsetY"), tag.getDouble("OffsetZ")),
                YawLimits.load(tag));
    }
}
