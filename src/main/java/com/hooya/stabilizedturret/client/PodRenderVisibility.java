package com.hooya.stabilizedturret.client;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Retains the hidden mesh owner independently of the next camera reference. */
final class PodRenderVisibility {
    // Section compilation can read this on a worker thread. Publish the whole
    // identity together instead of reading mutable session fields separately.
    private volatile Pod hiddenPod;

    Pod hiddenPod() {
        return hiddenPod;
    }

    void update(BlockPos pos, UUID subLevelId, Consumer<Pod> refresh) {
        Pod next = pos == null ? null : new Pod(pos, subLevelId);
        Pod previous = hiddenPod;
        if (Objects.equals(previous, next)) return;

        // Publish visibility before either section starts recompiling. On
        // exit the new session may already refer to a cannon, or to nothing.
        hiddenPod = next;
        if (previous != null) refresh.accept(previous);
        if (next != null) refresh.accept(next);
    }

    record Pod(BlockPos pos, UUID subLevelId) {
        Pod {
            pos = pos.immutable();
        }

        boolean matches(BlockPos otherPos, UUID otherSubLevelId) {
            return pos.equals(otherPos) && Objects.equals(subLevelId, otherSubLevelId);
        }
    }
}
