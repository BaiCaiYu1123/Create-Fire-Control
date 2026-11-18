package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public final class AimGroupPayloadChecks {
    public static void main(String[] args) {
        var cannon = new AimGroupSessionPayload.GroupAim(1, 70, 4, 0, 0.1, 0.99,
                new AimGroupSessionPayload.BallisticAim(1.5, 2, 9, -0.05, 0.002, false, 400,
                        true, 80, 64, 450));
        var secondCannon = new AimGroupSessionPayload.GroupAim(-8, 72, 3, 0.2, -0.1, 0.9,
                new AimGroupSessionPayload.BallisticAim(0.2, -0.3, 12, -0.02, 0.0002, true, 1200,
                        false, 0, 0, 0));
        var launcher = new AimGroupSessionPayload.GroupAim(5, 71, 0, 0, 0, 1);
        roundTrip(new AimGroupSessionPayload(List.of(cannon, launcher, secondCannon)));
        roundTrip(new AimGroupSessionPayload(List.of(cannon)));
        roundTrip(AimGroupSessionPayload.inactive());
        roundTrip(new AimGroupSessionPayload(List.of(new AimGroupSessionPayload.GroupAim(
                1,70,4,0,0,1,cannon.ballistic(),new AimGroupSessionPayload.MuzzleFrame(
                new UUID(1,2),new Vec3(20000001,70,-20000000),new Vec3(0,0,1),9)))));
        roundTrip(new AimGroupSessionPayload(List.of(new AimGroupSessionPayload.GroupAim(
                1,70,4,0,0,1,cannon.ballistic(),new AimGroupSessionPayload.MuzzleFrame(
                new UUID(1,2),new Vec3(20000001,70,-20000000),new Vec3(0,0,1),9,
                new AimGroupSessionPayload.BodyMotion(new Vec3(1,70,4),new Vec3(20000000,70,-20000000),
                        new Vec3(0,0,12),new Vec3(0,.1,0),new org.joml.Quaterniond().rotationY(.2)))))));
        cameraRayRoundTrip();
        distantTerrainAimRoundTrip();
        sightGoalsRoundTrip();
        for (int size : new int[]{-1, 129}) {
            var buffer = buffer();
            try {
                buffer.writeVarInt(size);
                rejected(() -> AimGroupSessionPayload.STREAM_CODEC.decode(buffer));
            } finally { buffer.release(); }
        }
        for (int ticks : new int[]{0, -1, 1201, Integer.MAX_VALUE}) {
            rejected(() -> new AimGroupSessionPayload.BallisticAim(0, 0, 1, -0.05, 0, false, ticks,
                    false, 0, 0, 0));
        }
        rejected(() -> new AimGroupSessionPayload.GroupAim(Double.NaN, 1, 2, 0, 0, 1));
        rejected(() -> new AimGroupSessionPayload.BallisticAim(0, 0, 1, -0.05, -1, false, 400, false, 0, 0, 0));
        rejected(() -> new AimGroupSessionPayload.BallisticAim(Double.POSITIVE_INFINITY, 0, 1, 0, 0, false, 400, false, 0, 0, 0));
        System.out.println("PASS: independent turret trajectories, direct-ray fallback and bounded aim protocol");
    }
    private static void sightGoalsRoundTrip() {
        var frame=new AimGroupSessionPayload.MuzzleFrame(new UUID(2,3),new Vec3(20000000,74,-20000000),
                new Vec3(0,0,1),8);
        var first=new AimGroupSessionPayload.GroupAim(1,70,4,0,0,1,null,frame,
                new AimGroupSessionPayload.SightAim(new Vec3(.3,.15,1),new Vec3(.2,.1,1),1271));
        var second=new AimGroupSessionPayload.GroupAim(8,74,3,1,0,0,null,
                new AimGroupSessionPayload.MuzzleFrame(new UUID(4,5),frame.localOrigin(),new Vec3(1,0,0),8),
                new AimGroupSessionPayload.SightAim(new Vec3(1,.2,0),new Vec3(1,-.1,0),1270));
        var sky=new AimGroupSessionPayload.GroupAim(8,74,3,1,.3,0,null,frame,
                new AimGroupSessionPayload.SightAim(new Vec3(1,.3,0),new Vec3(1,.3,0)));
        roundTrip(new AimGroupSessionPayload(List.of(first,second,sky,new AimGroupSessionPayload.GroupAim(0,0,0,0,0,1))));
        rejected(()->new AimGroupSessionPayload.SightAim(Vec3.ZERO,new Vec3(0,0,1)));
        rejected(()->new AimGroupSessionPayload.SightAim(new Vec3(Double.NaN,0,1),new Vec3(0,0,1)));
    }
    private static void cameraRayRoundTrip() {
        var payload=new AimDirectionPayload(1,70,4,1,-.1,0,54,64,4,true,null,0,0,0,
                .01,.01,false,0,0,0,new AimDirectionPayload.CameraFrame(new UUID(1,3),
                new Vec3(20000002,70,-20000003),new Vec3(0,1,0),new Vec3(-8,3,-16)));
        var buffer=buffer();
        try {
            AimDirectionPayload.STREAM_CODEC.encode(buffer,payload);
            if (!payload.equals(AimDirectionPayload.STREAM_CODEC.decode(buffer)) || buffer.readableBytes()!=0)
                throw new AssertionError("Moving camera frame changed in transit");
        } finally { buffer.release(); }
        rejected(()->new AimDirectionPayload.CameraFrame(new UUID(1,2),Vec3.ZERO,Vec3.ZERO));
        rejected(()->new AimDirectionPayload.CameraFrame(new UUID(1,2),Vec3.ZERO,new Vec3(0,1,0),new Vec3(Double.NaN,0,0)));
        rejected(()->new AimGroupSessionPayload.MuzzleFrame(new UUID(1,2),Vec3.ZERO,new Vec3(0,0,1),Double.NaN));
    }
    private static void distantTerrainAimRoundTrip() {
        Vec3 origin = new Vec3(20000000, 70, -20000000);
        for (double range : new double[]{20000, 48000, 65536}) for (boolean moving : new boolean[]{false, true}) {
            Vec3 target = origin.add(0, 0, range);
            if (!AimRayLimits.withinTargetRange(origin, target))
                throw new AssertionError("Distant terrain rejected by the server aim limit");
            var camera = moving ? new AimDirectionPayload.CameraFrame(new UUID(5, 6),
                    new Vec3(20000003, 70, -20000002), new Vec3(0, 0, -1)) : null;
            var payload = new AimDirectionPayload(origin.x, origin.y, origin.z, 0, 0, 1,
                    target.x, target.y, target.z, true, null, 0, 0, 0, .01, .01, false, 0, 0, 0, camera);
            var buffer = buffer();
            try {
                AimDirectionPayload.STREAM_CODEC.encode(buffer, payload);
                var decoded = AimDirectionPayload.STREAM_CODEC.decode(buffer);
                if (!payload.equals(decoded) || buffer.isReadable() || !java.util.Objects.equals(decoded.cameraFrame(), camera)
                        || decoded.hitSubLevelId() != null || !decoded.hit())
                    throw new AssertionError("LOD terrain target changed or gained a physical anchor in transit");
            } finally { buffer.release(); }
        }
        for (Vec3 invalid : new Vec3[]{origin.add(0, 0, 65537),
                new Vec3(Double.NaN, 0, 0), new Vec3(Double.POSITIVE_INFINITY, 0, 0)}) {
            if (AimRayLimits.withinTargetRange(origin, invalid))
                throw new AssertionError("Unbounded terrain aim accepted");
        }
    }
    private static void roundTrip(AimGroupSessionPayload original) {
        var buffer = buffer();
        try {
            AimGroupSessionPayload.STREAM_CODEC.encode(buffer, original);
            var decoded = AimGroupSessionPayload.STREAM_CODEC.decode(buffer);
            if (!original.equals(decoded) || buffer.readableBytes() != 0) throw new AssertionError("Aim group round trip changed data");
        } finally { buffer.release(); }
    }
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid aim data accepted");
    }
    private AimGroupPayloadChecks() {}
}
