package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.AimGroupSessionPayload;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import java.util.*;

/** Retains the velocities Sable already transmits, alongside their exact pose interpolation tick. */
public final class ClientSableMotionHistory {
    private static final Map<UUID,List<CannonRenderFrame.TimedMotion>> HISTORY=new LinkedHashMap<>();
    private ClientSableMotionHistory() {}

    public static void capture(Level level,int tick,List<ClientboundSableSnapshotDualPacket.Entry> entries) {
        if (!(level instanceof ClientLevel client)) return;
        var container=ClientSubLevelContainer.getContainer(client);
        if (container==null) return;
        for (var entry : entries) {
            var body=container.getSubLevel(ChunkPos.getX(entry.plotCoordinate()),ChunkPos.getZ(entry.plotCoordinate()));
            if (body==null || body.isRemoved()) continue;
            var pose=entry.pose(); var linear=entry.linearVelocity(); var angular=entry.angularVelocity();
            var p=pose.position(); var com=pose.rotationPoint();
            if (!p.isFinite() || !com.isFinite() || !pose.orientation().isFinite()
                    || !linear.isFinite() || !angular.isFinite()) continue;
            var motion=new AimGroupSessionPayload.BodyMotion(new Vec3(p.x,p.y,p.z),new Vec3(com.x,com.y,com.z),
                    new Vec3(linear.x(),linear.y(),linear.z()),new Vec3(angular.x(),angular.y(),angular.z()),
                    new Quaterniond(pose.orientation()));
            List<CannonRenderFrame.TimedMotion> samples=new ArrayList<>(HISTORY.getOrDefault(body.getUniqueId(),List.of()));
            // UDP may arrive out of order. Match Sable's tick ordering, including replacement of a duplicate tick.
            samples.removeIf(value->value.tick()==tick);
            samples.add(new CannonRenderFrame.TimedMotion(tick,motion));
            samples.sort(Comparator.comparingDouble(CannonRenderFrame.TimedMotion::tick));
            while (samples.size()>24) samples.removeFirst();
            HISTORY.put(body.getUniqueId(),List.copyOf(samples));
        }
        while (HISTORY.size()>128) HISTORY.remove(HISTORY.keySet().iterator().next());
    }

    public static Vec3 velocity(AimGroupSessionPayload.MuzzleFrame frame,ClientSubLevel body,float partialTick) {
        List<CannonRenderFrame.TimedMotion> history=HISTORY.get(frame.bodyId());
        if (history==null || history.isEmpty()) return null;
        var container=ClientSubLevelContainer.getContainer(body.getLevel());
        if (container==null) return null;
        var clock=container.getInterpolation();
        if (clock.isStopped()) return Vec3.ZERO;
        return CannonRenderFrame.carrierVelocityAt(frame,body.renderPose(partialTick),history,
                clock.lastInterpolationTick,clock.mostRecentInterpolationTick,Math.clamp(partialTick,0,1));
    }

    public static void clear() { HISTORY.clear(); }
}
