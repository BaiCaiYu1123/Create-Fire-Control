package com.hooya.stabilizedturret.content.controller;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;

/** Removal of the servo's acquired compensation from the rendered physical bore. */
public final class CannonSightDirection {
    private CannonSightDirection() {}

    /** Apply a measured correction to a bore at the matching render time. */
    public static Vec3 direction(Vec3 measured,Vec3 sampledBore,Vec3 sampledSight) {
        Vector3d ray=correction(sampledBore,sampledSight).transform(vector(measured.normalize()));
        return new Vec3(ray.x,ray.y,ray.z).normalize();
    }

    private static Quaterniond correction(Vec3 bore,Vec3 sight) {
        return new Quaterniond().rotationTo(vector(bore.normalize()),vector(sight.normalize()));
    }

    /** Frame evaluation never integrates an angular ratio or waits for exact physical arrival. */
    public static final class History {
        private record Sample(int tick,Quaterniond correction) {}
        private final List<Sample> samples=new ArrayList<>();
        public void add(int tick,Vec3 bore,Vec3 sight) {
            samples.removeIf(sample->sample.tick()==tick);
            samples.add(new Sample(tick,correction(bore,sight)));
            samples.sort(java.util.Comparator.comparingInt(Sample::tick));
            while (samples.size()>24) samples.removeFirst();
        }
        public Vec3 direction(Vec3 bore,double tick) {
            if (samples.isEmpty()) return bore.normalize();
            // A sleeping interpolation clock can trail all retained aim packets.
            // There is no matching buffered correction; never select an arbitrary oldest one.
            if (tick<samples.getFirst().tick()) tick=samples.getLast().tick();
            Quaterniond selected=new Quaterniond(samples.getFirst().correction());
            if (tick>samples.getFirst().tick()) {
                selected.set(samples.getLast().correction());
                for (int index=1;index<samples.size();index++) {
                    Sample previous=samples.get(index-1),next=samples.get(index);
                    if (tick>next.tick()) continue;
                    double alpha=Math.clamp((tick-previous.tick())/(next.tick()-previous.tick()),0,1);
                    selected.set(previous.correction()).slerp(next.correction(),alpha);
                    break;
                }
            }
            Vector3d ray=selected.transform(vector(bore.normalize()));
            return new Vec3(ray.x,ray.y,ray.z).normalize();
        }
    }
    private static Vector3d vector(Vec3 value) { return new Vector3d(value.x,value.y,value.z); }
}
