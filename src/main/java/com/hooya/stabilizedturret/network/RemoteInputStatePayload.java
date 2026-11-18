package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.AimRayLimits;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.controller.RemoteInputTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import com.hooya.stabilizedturret.content.remote.ControllerPulseQueue.Pulse;

public record RemoteInputStatePayload(boolean advanced, BlockPos hubPos, Set<String> pressed,
                                      @Nullable RangeSample rangeSample,List<Pulse> wheelPulses,
                                      Set<String> observed) implements CustomPacketPayload {
    public RemoteInputStatePayload { pressed=Set.copyOf(pressed); wheelPulses=List.copyOf(wheelPulses); observed=Set.copyOf(observed); }
    public RemoteInputStatePayload(boolean advanced, BlockPos hubPos, Set<String> pressed,
                                   @Nullable RangeSample rangeSample, List<Pulse> wheelPulses) {
        this(advanced, hubPos, pressed, rangeSample, wheelPulses, Set.copyOf(
                com.hooya.stabilizedturret.content.controller.DriveByWireCompat.keyList(
                        advanced ? RemoteControllerCompat.Type.ADVANCED : RemoteControllerCompat.Type.NORMAL)));
    }
    public RemoteInputStatePayload(boolean advanced,BlockPos hubPos,Set<String> pressed,@Nullable RangeSample rangeSample) {
        this(advanced,hubPos,pressed,rangeSample,List.of());
    }
    public RemoteInputStatePayload(boolean advanced, BlockPos hubPos, Set<String> pressed) {
        this(advanced, hubPos, pressed, null);
    }

    /** Captured with the input edge, rather than the server's next ordinary aim packet. */
    public record RangeSample(Vec3 origin, Vec3 direction, Vec3 target, boolean hit) {
        /** Transient local camera/LOD results may be unusable; packet decoding remains strict. */
        public static @Nullable RangeSample tryCreate(Vec3 origin, Vec3 direction, Vec3 target, boolean hit) {
            return AimRayLimits.isForwardTarget(origin, direction, target)
                    ? new RangeSample(origin, direction, target, hit) : null;
        }

        public RangeSample {
            for (Vec3 v : new Vec3[]{origin, direction, target})
                if (v == null || !Double.isFinite(v.x) || !Double.isFinite(v.y) || !Double.isFinite(v.z))
                    throw new IllegalArgumentException("Invalid rangefinder ray");
            if (direction.lengthSqr() < 1e-12 || !AimRayLimits.withinTargetRange(origin, target))
                throw new IllegalArgumentException("Invalid rangefinder distance");
            direction = direction.normalize();
            if (target.subtract(origin).dot(direction) <= 0)
                throw new IllegalArgumentException("Rangefinder target behind camera");
        }
    }
    public static final Type<RemoteInputStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "remote_input_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteInputStatePayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.advanced); buffer.writeBlockPos(payload.hubPos);
                buffer.writeVarInt(payload.pressed.size());
                for (String key : payload.pressed) buffer.writeUtf(key, 32);
                buffer.writeBoolean(payload.rangeSample != null);
                if (payload.rangeSample != null) {
                    for (Vec3 v : new Vec3[]{payload.rangeSample.origin(), payload.rangeSample.direction(), payload.rangeSample.target()}) {
                        buffer.writeDouble(v.x); buffer.writeDouble(v.y); buffer.writeDouble(v.z);
                    }
                    buffer.writeBoolean(payload.rangeSample.hit());
                }
                buffer.writeVarInt(payload.wheelPulses.size());
                for(Pulse pulse:payload.wheelPulses) {
                    buffer.writeVarInt(pulse.steps());buffer.writeVarInt(pulse.inputs().size());
                    for(String key:pulse.inputs())buffer.writeUtf(key,32);
                }
                buffer.writeVarInt(payload.observed.size());
                for (String key : payload.observed) buffer.writeUtf(key, 32);
            },
            buffer -> {
                boolean advanced = buffer.readBoolean(); BlockPos hub = buffer.readBlockPos();
                int size = buffer.readVarInt();
                if (size < 0 || size > 26) throw new IllegalArgumentException("Too many remote inputs");
                Set<String> pressed = new HashSet<>();
                for (int i = 0; i < size; i++) pressed.add(buffer.readUtf(32));
                RangeSample sample = buffer.readBoolean() ? new RangeSample(readVector(buffer), readVector(buffer),
                        readVector(buffer), buffer.readBoolean()) : null;
                int count=buffer.readVarInt();if(count<0||count>64)throw new IllegalArgumentException("Too many wheel pulses");
                List<Pulse> pulses=new ArrayList<>();
                for(int i=0;i<count;i++) {
                    int steps=buffer.readVarInt(),keys=buffer.readVarInt();
                    if(steps<1||steps>16||keys<1||keys>26)throw new IllegalArgumentException("Invalid wheel pulse");
                    Set<String> inputs=new HashSet<>();for(int k=0;k<keys;k++)inputs.add(buffer.readUtf(32));
                    pulses.add(new Pulse(inputs,steps));
                }
                int observedSize = buffer.readVarInt();
                if (observedSize < 0 || observedSize > 26) throw new IllegalArgumentException("Too many observed inputs");
                Set<String> observed = new HashSet<>();
                for (int i = 0; i < observedSize; i++) observed.add(buffer.readUtf(32));
                return new RemoteInputStatePayload(advanced, hub, Set.copyOf(pressed), sample,pulses,observed);
            }
    );
    public static void handle(RemoteInputStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> { if (context.player() instanceof ServerPlayer player) RemoteInputTracker.update(player, payload.advanced ? RemoteControllerCompat.Type.ADVANCED : RemoteControllerCompat.Type.NORMAL, payload.hubPos, payload.pressed, payload.rangeSample,payload.wheelPulses,payload.observed); });
    }
    private static Vec3 readVector(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
