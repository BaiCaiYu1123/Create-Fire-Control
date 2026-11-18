package com.hooya.stabilizedturret.content.bearing;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Topology taken from the affected Leopard blueprint: 21 bodies, 18 rigid welds and two hinges. */
final class BearingBodyChainChecks {
    static void run() {
        largeWeldedBlueprint();
        Map<UUID,Set<UUID>> graph=new HashMap<>();
        int[][] welds={{3,10},{3,4},{6,2},{3,5},{6,7},{8,11},{10,0},{8,1},{6,9},
                {12,14},{12,13},{6,12},{3,15},{6,16},{13,17},{14,18},{6,19},{10,20}};
        for (int[] weld:welds) link(graph,weld[0],weld[1]);
        var yaw=joint(3,6); var pitch=joint(2,8);
        List<BearingBodyChain.Joint> axes=new ArrayList<>(List.of(yaw,pitch));
        for (int muzzle:new int[]{8,11,1}) {
            var chain=BearingBodyChain.resolve(id(muzzle),axes,graph);
            require(chain!=null && chain.joints().equals(List.of(pitch,yaw)),
                    "muzzle and welded barrel decorations inherit both independent controlled axes");
            require(chain.carrier().equals(id(0)),"carrier belongs to the chassis weld group, never the turret group");
        }
        UUID yawCarrier=BearingBodyChain.resolve(id(3),axes,graph).carrier();
        UUID pitchCarrier=BearingBodyChain.resolve(id(2),axes,graph).carrier();
        require(yawCarrier.equals(pitchCarrier),"pitch on a welded turret member shares the yaw predictor component");
        require(BearingBodyChain.resolve(id(2),axes,graph).joints().equals(List.of(yaw)),
                "pitch's parent ascends through its welded yaw plate");
        axes.add(joint(20,22)); axes.add(joint(22,23));
        var sibling=BearingBodyChain.resolve(id(23),axes,graph);
        require(sibling!=null && sibling.carrier().equals(yawCarrier)
                && sibling.joints().equals(List.of(joint(22,23),joint(20,22))),
                "another turret on a welded chassis member joins the component without borrowing this turret's axis history");
        axes.add(joint(50,51)); axes.add(joint(51,52));
        require(BearingBodyChain.resolve(id(52),axes,graph).carrier().equals(id(50)),
                "a separate vehicle retains its own carrier");
        Map<UUID,Set<UUID>> bypass=new HashMap<>(graph);
        bypass.replaceAll((key,value)->new HashSet<>(value)); link(bypass,6,8);
        require(BearingBodyChain.resolve(id(8),axes,bypass)==null,"a weld bypassing a controlled hinge safely rejects");
        axes.add(joint(3,2));
        require(BearingBodyChain.resolve(id(8),axes,graph)==null,"ambiguous incoming hinges cannot select an arbitrary ancestor");
        require(BearingBodyChain.resolve(id(52),axes,graph).carrier().equals(id(50)),
                "an ambiguous structure does not disable a separate vehicle");
        System.out.println("PASS: affected 21-body/18-weld blueprint topology; welded pitch, muzzle members, shared chassis and independent turrets");
    }
    private static void largeWeldedBlueprint() {
        Map<UUID,Set<UUID>> graph=new HashMap<>();
        int[][] welds={{23,21},{23,5},{20,13},{20,18},{20,22},{20,3},{6,16},{13,26},
                {23,12},{23,8},{23,25},{23,17},{23,2},{23,10},{23,11},{23,0},{23,19},
                {23,14},{23,1},{23,24},{23,4},{23,7},{27,9},{5,27},{16,15},{29,28},
                {28,30},{29,31},{30,32},{20,28},{22,33},{3,34},{23,35}};
        for (int[] weld:welds) link(graph,weld[0],weld[1]);
        var yaw=joint(23,13); var pitch=joint(20,6);
        var axes=List.of(yaw,pitch);
        var muzzle=BearingBodyChain.resolve(id(16),axes,graph);
        require(muzzle!=null && muzzle.joints().equals(List.of(pitch,yaw)),
                "T-80 36-body/33-weld blueprint retains both controlled ancestors");
        require(BearingBodyChain.resolve(id(23),axes,graph).carrier()
                .equals(BearingBodyChain.resolve(id(20),axes,graph).carrier()),
                "T-80 welded dual bearings share their complete 36-body component");
        Map<UUID,Set<UUID>> large=new HashMap<>();
        for (int i=1;i<64;i++) link(large,0,i);
        require(BearingBodyChain.resolve(id(1),List.of(),large)!=null,"64-body rigid cluster is supported");
        link(large,0,64);
        require(BearingBodyChain.resolve(id(1),List.of(),large)==null,"oversized clusters remain bounded");
        for (Vector3d axis:List.of(new Vector3d(0,1,0),new Vector3d(0,-1,0),
                new Vector3d(1,0,0),new Vector3d(-1,0,0),new Vector3d(0,0,1),new Vector3d(0,0,-1))) {
            Vector3d ray=BearingServoMath.startupLocalRay(axis);
            require(Math.abs(ray.dot(axis))<1e-12 && Math.abs(ray.length()-1)<1e-12,
                    "startup feedback is a nondegenerate transverse ray for every bearing facing");
        }
        System.out.println("PASS: T-80 36-body/33-weld ancestry, 64-body bounds and passive startup rays");
    }
    private static UUID id(int value) { return new UUID(0,value); }
    private static BearingBodyChain.Joint joint(int parent,int child) { return new BearingBodyChain.Joint(id(parent),id(child)); }
    private static void link(Map<UUID,Set<UUID>> graph,int first,int second) {
        graph.computeIfAbsent(id(first),ignored->new HashSet<>()).add(id(second));
        graph.computeIfAbsent(id(second),ignored->new HashSet<>()).add(id(first));
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
