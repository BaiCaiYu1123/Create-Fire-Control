package com.hooya.stabilizedturret.content.cannon.munition;

/** Implemented directly on Entity, so mod overrides of remove/kill/setHealth are bypassed. */
public interface AnnihilationErasure {
    void stabilizedTurret$erase();
}
