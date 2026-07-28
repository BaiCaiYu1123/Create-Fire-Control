package com.hooya.stabilizedturret.compat.yuushya;

import net.minecraft.core.Direction;

public interface YuushyaCannonRecoilAnimation {
    void stabilizedTurret$startRecoil(Direction muzzleDirection);

    void stabilizedTurret$tickRecoil();

    float stabilizedTurret$getRecoilOffset(float partialTick);

    Direction stabilizedTurret$getRecoilDirection();
}
