package com.hooya.stabilizedturret.content.controller;

import java.util.*;

public final class RadarContactMemoryChecks {
    private record Plot(int id, double x, double velocity) {}
    private static Plot coast(Plot p, double ticks) { return new Plot(p.id, p.x + p.velocity * ticks, p.velocity); }
    public static void main(String[] args) {
        for (int hold : new int[]{40, 60}) {
            var memory = new RadarContactMemory<Integer, Plot>(128);
            Plot initial = new Plot(1, 100, 2);
            require(memory.update(100, hold, List.of(initial), Plot::id, RadarContactMemoryChecks::coast).equals(List.of(initial)), "confirmed contact");
            for (int i = 1; i < hold; i++) {
                var plots = memory.update(100 + i, hold, List.of(), Plot::id, RadarContactMemoryChecks::coast);
                require(plots.size() == 1 && plots.getFirst().x == 100 + 2 * i && memory.isRemembered(1), "constant velocity coast from last observation");
            }
            require(memory.update(100 + hold, hold, List.of(), Plot::id, RadarContactMemoryChecks::coast).isEmpty(), "exact expiry; no refresh from missing samples");
            memory.update(200, hold, List.of(initial), Plot::id, RadarContactMemoryChecks::coast);
            memory.update(210, hold, List.of(), Plot::id, RadarContactMemoryChecks::coast);
            Plot reacquired = new Plot(1, 900, -3);
            require(memory.update(211, hold, List.of(reacquired), Plot::id, RadarContactMemoryChecks::coast).getFirst().equals(reacquired)
                    && !memory.isRemembered(1), "reacquisition becomes solid immediately and replaces prediction");
            require(memory.update(211 + hold - 1, hold, List.of(), Plot::id, RadarContactMemoryChecks::coast).size() == 1, "new loss starts from new real detection");
            require(memory.update(211 + hold, hold, List.of(), Plot::id, RadarContactMemoryChecks::coast).isEmpty(), "renewed expiry");
            memory.update(500, hold, List.of(initial), Plot::id, RadarContactMemoryChecks::coast);
            require(memory.update(0, hold, List.of(), Plot::id, RadarContactMemoryChecks::coast).isEmpty(), "world clock rollback clears memory");
            memory.update(1, hold, List.of(initial), Plot::id, RadarContactMemoryChecks::coast); memory.clear();
            require(memory.update(2, hold, List.of(), Plot::id, RadarContactMemoryChecks::coast).isEmpty(), "off/world change clears memory");
        }
        var crowd = new ArrayList<Plot>(); for (int i=0;i<20_000;i++) crowd.add(new Plot(i,i,0));
        var memory = new RadarContactMemory<Integer, Plot>(128);
        require(memory.update(10,60,crowd,Plot::id,RadarContactMemoryChecks::coast).size()==128 && memory.size()==128,"bounded 20k input");
        for (int i=0;i<30;i++) memory.update(11+i,60,List.of(new Plot(30_000+i,0,0)),Plot::id,RadarContactMemoryChecks::coast);
        require(memory.size()<=128,"bounded churn");
        for (double t : new double[]{0,1.25,2.5,5,7.5,9.9})
            require(RadarContactMemory.blinkColor(0xFFAABBCC,t)==RadarContactMemory.blinkColor(0xFFAABBCC,t+10),"2Hz period");
        require((RadarContactMemory.blinkColor(0xFFFFFFFF,0)>>>24)==51,"20% minimum alpha");
        require((RadarContactMemory.blinkColor(0xFFFFFFFF,5)>>>24)==153,"half-cosine at midpoint");
        require((RadarContactMemory.blinkColor(0xFFFFFFFF,9.99)>>>24)==255,"100% near cycle end");
        System.out.println("PASS: radar memory 40/60-tick expiry, no unseen acquisition, bounded extrapolation, recovery resets timer, mode/world/off clears, 20k cap and War Thunder 2Hz envelope");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
