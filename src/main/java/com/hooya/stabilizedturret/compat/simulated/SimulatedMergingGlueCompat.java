package com.hooya.stabilizedturret.compat.simulated;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.merging_glue.MergingGlueBlockEntity;

/** Real-time state queries for Simulated's temporary structure-merging glue. */
public final class SimulatedMergingGlueCompat {
    /**
     * The paired glue block entities exist only while Simulated is aligning
     * and merging the two bodies. This is deliberately state-driven: there is
     * no post-placement or post-weld timer.
     */
    public static boolean isActivelyMerging(ServerSubLevel body) {
        if (body == null || body.isRemoved()) return false;
        for (BlockEntitySubLevelActor actor : body.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof MergingGlueBlockEntity glue)) continue;
            MergingGlueBlockEntity partner = glue.getPartnerGlue();
            if (partner != null && (glue.isController() || partner.isController())) {
                return true;
            }
        }
        return false;
    }

    private SimulatedMergingGlueCompat() {
    }
}
