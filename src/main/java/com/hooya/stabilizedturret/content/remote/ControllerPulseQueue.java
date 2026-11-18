package com.hooya.stabilizedturret.content.remote;

import java.util.*;

/** Bounded wheel impulses with at least one game tick high and one tick low. */
public final class ControllerPulseQueue {
    public record Pulse(Set<String> inputs,int steps) {
        public Pulse { inputs=Set.copyOf(inputs);steps=Math.clamp(steps,1,16); }
    }
    private final Deque<Set<String>> pending=new ArrayDeque<>();
    private Set<String> active=Set.of();
    private long nextChange=Long.MIN_VALUE;
    public void add(Set<String> inputs,int steps) {
        if(inputs.isEmpty())return;
        Set<String> snapshot=Set.copyOf(inputs);
        for(int i=0;i<Math.clamp(steps,1,16)&&pending.size()<64;i++)pending.addLast(snapshot);
    }
    public Set<String> active(long tick) {
        if(tick>=nextChange) {
            if(!active.isEmpty()) {active=Set.of();nextChange=tick+1;}
            else if(!pending.isEmpty()) {active=pending.removeFirst();nextChange=tick+1;}
        }
        return active;
    }
    public boolean idle() { return pending.isEmpty()&&active.isEmpty(); }
    public void clear() {pending.clear();active=Set.of();nextChange=Long.MIN_VALUE;}
}
