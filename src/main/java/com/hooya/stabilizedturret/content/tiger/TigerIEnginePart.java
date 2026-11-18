package com.hooya.stabilizedturret.content.tiger;

import net.minecraft.util.StringRepresentable;

/** Temporary layout; keep placement and output offsets independent of the eventual mesh. */
public enum TigerIEnginePart implements StringRepresentable {
    CENTER(0), LEFT(-1), RIGHT(1);
    public final int x;
    TigerIEnginePart(int x) { this.x = x; }
    public boolean isController() { return this == CENTER; }
    public boolean isOutput() { return this != CENTER; }
    @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
