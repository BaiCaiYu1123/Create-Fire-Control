package com.hooya.stabilizedturret.content.loader;

import net.minecraft.nbt.CompoundTag;

/** Covers the legacy int-to-long tick migration and redstone reload initialization. */
public final class MzLoaderPersistenceChecks {
    public static void main(String[] args) {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("PlayUntilTick", Integer.MAX_VALUE - 10);
        if (MzLoaderBlockEntity.readPlayUntilTick(legacy) != Integer.MAX_VALUE - 10L) {
            throw new AssertionError("legacy integer PlayUntilTick did not migrate");
        }

        long future = (long) Integer.MAX_VALUE + 123_456L;
        CompoundTag current = new CompoundTag();
        current.putLong("PlayUntilTick", future);
        if (MzLoaderBlockEntity.readPlayUntilTick(current) != future) {
            throw new AssertionError("long PlayUntilTick was not preserved");
        }
    }

    private MzLoaderPersistenceChecks() {
    }
}
