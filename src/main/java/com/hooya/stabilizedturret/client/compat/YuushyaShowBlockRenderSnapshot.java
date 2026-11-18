package com.hooya.stabilizedturret.client.compat;

import com.yuushya.modelling.blockentity.transformData.TransformBlockData;

import java.util.ArrayList;
import java.util.List;

/** Detaches chunk meshing from Yuushya's editable block entity component list. */
public final class YuushyaShowBlockRenderSnapshot {
    public static List<TransformBlockData> copyOf(List<TransformBlockData> source) {
        // Yuushya 2.4.1 uses ArrayList. Its toArray() copies without the fail-fast
        // iterator that crashes when a block entity update reloads the components.
        Object[] components = source.toArray();
        List<TransformBlockData> snapshot = new ArrayList<>(components.length);
        for (Object component : components) {
            // A concurrent ArrayList.clear() can leave nulls in the copied array.
            if (component == null) continue;
            TransformBlockData transform = (TransformBlockData) component;
            // The editor also mutates vectors in place. Copy their values, not just
            // the list, so subsequent edits cannot change an in-progress mesh.
            snapshot.add(new TransformBlockData(transform.pos, transform.rot,
                    transform.scales, transform.blockState, transform.isShown));
        }
        return List.copyOf(snapshot);
    }

    private YuushyaShowBlockRenderSnapshot() {
    }
}
