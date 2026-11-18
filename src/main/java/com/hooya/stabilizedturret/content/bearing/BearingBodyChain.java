package com.hooya.stabilizedturret.content.bearing;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Controlled hinge ancestry through rigid welds, without crossing any other moving joint. */
public final class BearingBodyChain {
    private BearingBodyChain() {}

    public record Joint(UUID parent, UUID child) {}
    public record Chain(UUID carrier, List<Joint> joints) {
        public Chain { joints=List.copyOf(joints); }
    }

    public static @Nullable Chain resolve(UUID start,List<Joint> joints,Map<UUID,Set<UUID>> fixedWelds) {
        if (start==null) return null;
        Set<UUID> visited=new HashSet<>();
        List<Joint> path=new ArrayList<>();
        UUID current=start;
        while (path.size()<=8) {
            Set<UUID> rigid=new HashSet<>();
            ArrayDeque<UUID> pending=new ArrayDeque<>();
            rigid.add(current); pending.add(current);
            while (!pending.isEmpty()) {
                for (UUID next:fixedWelds.getOrDefault(pending.removeFirst(),Set.of()))
                    if (rigid.add(next)) pending.addLast(next);
                if (rigid.size()>ArticulatedServoSolver.MAX_BODIES) return null;
            }
            if (rigid.stream().anyMatch(visited::contains)) return null;
            visited.addAll(rigid);
            Joint incoming=null;
            for (Joint joint:joints) if (rigid.contains(joint.child())) {
                // A welded bypass or two incoming hinges is not a serial controlled chain.
                if (rigid.contains(joint.parent()) || incoming!=null && !incoming.equals(joint)) return null;
                incoming=joint;
            }
            if (incoming==null) return new Chain(rigid.stream().min(UUID::compareTo).orElseThrow(),path);
            path.add(incoming);
            current=incoming.parent();
        }
        return null;
    }
}
