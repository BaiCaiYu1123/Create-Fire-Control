package com.hooya.stabilizedturret.content.turbine;

import net.minecraft.util.StringRepresentable;

public enum GasTurbinePart implements StringRepresentable {
    NEGATIVE("negative", -1),
    CENTER("center", 0),
    POSITIVE("positive", 1);

    private final String serializedName;
    private final int offset;

    GasTurbinePart(String serializedName, int offset) {
        this.serializedName = serializedName;
        this.offset = offset;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    public int offset() {
        return offset;
    }
}
