package com.hooya.stabilizedturret.content.diesel;
/** Original Blockbench rotation keyframes and baked pivots; no mechanical state changes. */
public final class Ztz99aDieselModelAnimation {
    public static final float LENGTH_SECONDS = 4F;
    public static final float[][] PIVOTS = {{1.2125F, 1.36649375F, 0.45242312F}, {-0.2125F, 1.36649375F, 0.45242312F}};
    public static final float[][] AXES = {{0F, -1F, 0F}, {0F, -1F, 0F}};
    private static final float[][] TIMES = {{0F, 0.48F, 1F, 1.48F, 2F, 2.52F, 3F, 3.48F, 4F}, {0F, 0.48F, 1F, 1.48F, 2F, 2.52F, 3F, 3.48F, 4F}};
    private static final float[][] ANGLES = {{0F, 45F, 90F, 135F, 180F, 225F, 270F, 315F, 360F}, {0F, -45F, -90F, -135F, -180F, -225F, -270F, -315F, -360F}};
    public static float angle(int fan, float seconds) {
        float t = (seconds % LENGTH_SECONDS + LENGTH_SECONDS) % LENGTH_SECONDS;
        float[] times = TIMES[fan], angles = ANGLES[fan];
        for (int i = 1; i < times.length; i++) if (t <= times[i])
            return angles[i-1] + (angles[i]-angles[i-1]) * (t-times[i-1]) / (times[i]-times[i-1]);
        return angles[angles.length-1];
    }
    private Ztz99aDieselModelAnimation() {}
}
