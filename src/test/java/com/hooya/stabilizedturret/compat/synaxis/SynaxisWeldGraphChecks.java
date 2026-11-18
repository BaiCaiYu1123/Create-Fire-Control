package com.hooya.stabilizedturret.compat.synaxis;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure graph checks for welded turret topology; no live Synaxis instance is required. */
public final class SynaxisWeldGraphChecks {
    public static void main(String[] args) {
        UUID yawChild = UUID.randomUUID();
        UUID weldedMiddle = UUID.randomUUID();
        UUID launcherBody = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        Map<UUID, Set<UUID>> graph = Map.of(
                yawChild, Set.of(weldedMiddle),
                weldedMiddle, Set.of(yawChild, launcherBody),
                launcherBody, Set.of(weldedMiddle)
        );

        require(SynaxisWeldCompat.graphDistance(yawChild, launcherBody, graph) == 2,
                "multi-edge welded launcher path must be discovered");
        require(SynaxisWeldCompat.graphDistance(yawChild, yawChild, graph) == 0,
                "a body must remain its own zero-distance parent");
        require(SynaxisWeldCompat.graphDistance(yawChild, unrelated, graph) == -1,
                "an unrelated launcher body must not be adopted");
        require(SynaxisWeldCompat.expandConnected(Set.of(yawChild), graph)
                        .equals(Set.of(yawChild, weldedMiddle, launcherBody)),
                "weld expansion must include the complete connected component");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private SynaxisWeldGraphChecks() {
    }
}
