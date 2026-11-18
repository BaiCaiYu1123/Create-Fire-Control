package com.hooya.stabilizedturret.content.diesel;
import net.minecraft.util.StringRepresentable;
public enum Ztz99aDieselEnginePart implements StringRepresentable {
    M1_P0_M2("m1_p0_m2", -1, 0, -2),
    P0_P0_M2("p0_p0_m2", 0, 0, -2),
    P1_P0_M2("p1_p0_m2", 1, 0, -2),
    M1_P0_M1("m1_p0_m1", -1, 0, -1),
    P0_P0_M1("p0_p0_m1", 0, 0, -1),
    P1_P0_M1("p1_p0_m1", 1, 0, -1),
    M1_P0_P0("m1_p0_p0", -1, 0, 0),
    P0_P0_P0("p0_p0_p0", 0, 0, 0),
    P1_P0_P0("p1_p0_p0", 1, 0, 0),
    M1_P1_M2("m1_p1_m2", -1, 1, -2),
    P0_P1_M2("p0_p1_m2", 0, 1, -2),
    P1_P1_M2("p1_p1_m2", 1, 1, -2),
    M1_P1_M1("m1_p1_m1", -1, 1, -1),
    P0_P1_M1("p0_p1_m1", 0, 1, -1),
    P1_P1_M1("p1_p1_m1", 1, 1, -1),
    M1_P1_P0("m1_p1_p0", -1, 1, 0),
    P0_P1_P0("p0_p1_p0", 0, 1, 0),
    P1_P1_P0("p1_p1_p0", 1, 1, 0);
    private final String name; public final int x,y,z;
    Ztz99aDieselEnginePart(String name,int x,int y,int z) { this.name=name; this.x=x; this.y=y; this.z=z; }
    public String getSerializedName() { return name; }
    public boolean isController() { return x==0 && y==0 && z==0; }
    public boolean isOutput() { return Math.abs(x)==1 && y==0 && z==0; }
    /** Only these five T cells exist as blocks; the other enum values remain for old-save decoding. */
    public boolean isOccupied() { return (x==0 && y==0) || isOutput(); }
}
