package com.hooya.stabilizedturret.content.bearing;

/** Persistent, synchronized visibility state attached to a Simulated bearing plate. */
public interface BearingHeadVisibility {
    boolean stabilizedTurret$isBearingHeadHidden();

    void stabilizedTurret$setBearingHeadHidden(boolean hidden);
}
