package com.hooya.stabilizedturret.content.display;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public enum DisplayConnection implements StringRepresentable {
    SINGLE("single"),
    TOP("top"),
    BOTTOM("bottom"),
    LEFT("left"),
    RIGHT("right"),
    TOP_LEFT("top_left"),
    TOP_RIGHT("top_right"),
    BOTTOM_LEFT("bottom_left"),
    BOTTOM_RIGHT("bottom_right"),
    CENTER("center");

    private final String name;

    DisplayConnection(String name) {
        this.name = name;
    }

    @Override
    @NotNull
    public String getSerializedName() {
        return name;
    }
}
