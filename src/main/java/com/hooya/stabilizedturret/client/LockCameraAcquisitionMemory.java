package com.hooya.stabilizedturret.client;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Survives seat/view changes, and is cleared only when the game connection ends. */
public final class LockCameraAcquisitionMemory {
    private final Set<UUID> observed = new HashSet<>();

    public boolean begin(UUID acquisition) { return acquisition != null && observed.add(acquisition); }
    public void clear() { observed.clear(); }
}
