package com.hooya.stabilizedturret.compat.yuushya;

/**
 * Marks a CBC-MW recoil barrel whose native five-tick animation is replaced by
 * the same recoil profile used by Yuushya-modelled cannon barrels.
 */
public interface MediumCannonRecoilOverride extends YuushyaCannonRecoilAnimation {
    void stabilizedTurret$setRecoilOverride(boolean enabled);

    boolean stabilizedTurret$hasRecoilOverride();
}
