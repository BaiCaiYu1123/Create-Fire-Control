package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.jetbrains.annotations.Nullable;

/** Actual firing rays and optional ammunition trajectories, one per independent turret. */
public record AimGroupSessionPayload(List<GroupAim> groups) implements CustomPacketPayload {
    private static final int MAX_GROUPS = 128;
    public static final Type<AimGroupSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "aim_groups")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, AimGroupSessionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        int size = Math.min(MAX_GROUPS, payload.groups.size());
                        buffer.writeVarInt(size);
                        for (int index = 0; index < size; index++) {
                            GroupAim group = payload.groups.get(index);
                            buffer.writeDouble(group.originX);
                            buffer.writeDouble(group.originY);
                            buffer.writeDouble(group.originZ);
                            buffer.writeDouble(group.directionX);
                            buffer.writeDouble(group.directionY);
                            buffer.writeDouble(group.directionZ);
                            buffer.writeBoolean(group.ballistic != null);
                            if (group.ballistic != null) {
                                BallisticAim ballistic = group.ballistic;
                                buffer.writeDouble(ballistic.velocityX);
                                buffer.writeDouble(ballistic.velocityY);
                                buffer.writeDouble(ballistic.velocityZ);
                                buffer.writeDouble(ballistic.gravity);
                                buffer.writeDouble(ballistic.drag);
                                buffer.writeBoolean(ballistic.quadraticDrag);
                                buffer.writeVarInt(ballistic.maxTicks);
                                buffer.writeBoolean(ballistic.impactAvailable);
                                if (ballistic.impactAvailable) {
                                    buffer.writeDouble(ballistic.impactX);
                                    buffer.writeDouble(ballistic.impactY);
                                    buffer.writeDouble(ballistic.impactZ);
                                }
                            }
                            buffer.writeBoolean(group.frame != null);
                            if (group.frame != null) {
                                MuzzleFrame frame=group.frame;
                                buffer.writeUUID(frame.bodyId);
                                for (Vec3 value : List.of(frame.localOrigin,frame.localForward)) {
                                    buffer.writeDouble(value.x); buffer.writeDouble(value.y); buffer.writeDouble(value.z);
                                }
                                buffer.writeDouble(frame.muzzleSpeed);
                                buffer.writeBoolean(frame.motion != null);
                                if (frame.motion != null) {
                                    BodyMotion m=frame.motion;
                                    for (Vec3 value : List.of(m.position,m.localCOM,m.linearVelocity,m.angularVelocity)) {
                                        buffer.writeDouble(value.x); buffer.writeDouble(value.y); buffer.writeDouble(value.z);
                                    }
                                    Quaterniond q=m.orientation();
                                    buffer.writeDouble(q.x); buffer.writeDouble(q.y); buffer.writeDouble(q.z); buffer.writeDouble(q.w);
                                }
                            }
                            buffer.writeBoolean(group.sightAim != null);
                            if (group.sightAim != null) {
                                buffer.writeInt(group.sightAim.interpolationTick);
                                for (Vec3 value : List.of(group.sightAim.launchGoal, group.sightAim.geometricGoal)) {
                                    buffer.writeDouble(value.x); buffer.writeDouble(value.y); buffer.writeDouble(value.z);
                                }
                            }
                        }
                    },
                    buffer -> {
                        int size = buffer.readVarInt();
                        if (size < 0 || size > MAX_GROUPS) {
                            throw new IllegalArgumentException("Invalid aim group count: " + size);
                        }
                        List<GroupAim> groups = new ArrayList<>(size);
                        for (int index = 0; index < size; index++) {
                            double ox = buffer.readDouble(), oy = buffer.readDouble(), oz = buffer.readDouble();
                            double dx = buffer.readDouble(), dy = buffer.readDouble(), dz = buffer.readDouble();
                            BallisticAim ballistic = null;
                            if (buffer.readBoolean()) {
                                double vx = buffer.readDouble(), vy = buffer.readDouble(), vz = buffer.readDouble();
                                double gravity = buffer.readDouble(), drag = buffer.readDouble();
                                boolean quadratic = buffer.readBoolean();
                                int ticks = buffer.readVarInt();
                                boolean hit = buffer.readBoolean();
                                ballistic = new BallisticAim(vx, vy, vz, gravity, drag, quadratic, ticks, hit,
                                        hit ? buffer.readDouble() : 0.0,
                                        hit ? buffer.readDouble() : 0.0,
                                        hit ? buffer.readDouble() : 0.0);
                            }
                            MuzzleFrame frame=null;
                            if (buffer.readBoolean()) {
                                UUID id=buffer.readUUID();
                                Vec3 local=readVector(buffer),ray=readVector(buffer);
                                double speed=buffer.readDouble();
                                BodyMotion motion=null;
                                if (buffer.readBoolean()) {
                                    Vec3 position=readVector(buffer),com=readVector(buffer),linear=readVector(buffer),angular=readVector(buffer);
                                    motion=new BodyMotion(position,com,linear,angular,new Quaterniond(
                                            buffer.readDouble(),buffer.readDouble(),buffer.readDouble(),buffer.readDouble()));
                                }
                                frame=new MuzzleFrame(id,local,ray,speed,motion);
                            }
                            SightAim sightAim = null;
                            if (buffer.readBoolean()) {
                                int tick=buffer.readInt();
                                sightAim=new SightAim(readVector(buffer),readVector(buffer),tick);
                            }
                            groups.add(new GroupAim(ox, oy, oz, dx, dy, dz, ballistic, frame, sightAim));
                        }
                        return new AimGroupSessionPayload(List.copyOf(groups));
                    }
            );

    public AimGroupSessionPayload {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public static AimGroupSessionPayload inactive() {
        return new AimGroupSessionPayload(List.of());
    }

    public static void handle(AimGroupSessionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleAimGroups(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record GroupAim(
            double originX, double originY, double originZ,
            double directionX, double directionY, double directionZ,
            @Nullable BallisticAim ballistic, @Nullable MuzzleFrame frame, @Nullable SightAim sightAim
    ) {
        public GroupAim(double ox,double oy,double oz,double dx,double dy,double dz,
                        @Nullable BallisticAim ballistic,@Nullable MuzzleFrame frame) {
            this(ox,oy,oz,dx,dy,dz,ballistic,frame,null);
        }
        public GroupAim(double ox,double oy,double oz,double dx,double dy,double dz,@Nullable BallisticAim ballistic) {
            this(ox,oy,oz,dx,dy,dz,ballistic,null);
        }
        public GroupAim(double ox, double oy, double oz, double dx, double dy, double dz) {
            this(ox, oy, oz, dx, dy, dz, null);
        }

        public GroupAim {
            requireFinite(originX, originY, originZ, directionX, directionY, directionZ);
        }
    }

    /** Measured bore and that same bore with acquired axis compensation removed. */
    public record SightAim(Vec3 launchGoal, Vec3 geometricGoal,int interpolationTick) {
        public SightAim(Vec3 bore,Vec3 sight) { this(bore,sight,0); }
        public SightAim {
            if (launchGoal == null || geometricGoal == null
                    || launchGoal.lengthSqr() < 1e-12 || geometricGoal.lengthSqr() < 1e-12)
                throw new IllegalArgumentException("Invalid cannon sight goals");
            requireFinite(launchGoal.x, launchGoal.y, launchGoal.z, geometricGoal.x, geometricGoal.y, geometricGoal.z);
        }
    }

    /** Geometry only; never contains the player's desired target or an invented aligned impact. */
    public record MuzzleFrame(UUID bodyId, Vec3 localOrigin, Vec3 localForward, double muzzleSpeed,
                              @Nullable BodyMotion motion) {
        public MuzzleFrame(UUID id,Vec3 localOrigin,Vec3 localForward,double speed) {
            this(id,localOrigin,localForward,speed,null);
        }
        public MuzzleFrame {
            if (bodyId==null || localOrigin==null || localForward==null || muzzleSpeed<0
                    || localForward.lengthSqr()<1e-12) throw new IllegalArgumentException("Invalid muzzle frame");
            requireFinite(localOrigin.x,localOrigin.y,localOrigin.z,localForward.x,localForward.y,localForward.z,muzzleSpeed);
            localForward=localForward.normalize();
        }
    }

    /** Physical COM state; velocity is blocks/second and radians/second, exactly as Sable. */
    public record BodyMotion(Vec3 position,Vec3 localCOM,Vec3 linearVelocity,Vec3 angularVelocity,Quaterniond orientation) {
        public BodyMotion {
            if (position==null || localCOM==null || linearVelocity==null || angularVelocity==null || orientation==null)
                throw new IllegalArgumentException("Missing muzzle motion");
            for (Vec3 v : List.of(position,localCOM,linearVelocity,angularVelocity)) requireFinite(v.x,v.y,v.z);
            requireFinite(orientation.x,orientation.y,orientation.z,orientation.w);
            if (orientation.lengthSquared()<1e-12) throw new IllegalArgumentException("Invalid body orientation");
            orientation=new Quaterniond(orientation);
        }
        @Override public Quaterniond orientation() { return new Quaterniond(orientation); }
    }

    private static Vec3 readVector(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(),buffer.readDouble(),buffer.readDouble());
    }

    public record BallisticAim(
            double velocityX, double velocityY, double velocityZ,
            double gravity, double drag, boolean quadraticDrag, int maxTicks,
            boolean impactAvailable, double impactX, double impactY, double impactZ
    ) {
        public BallisticAim {
            requireFinite(velocityX, velocityY, velocityZ, gravity, drag, impactX, impactY, impactZ);
            if (maxTicks < 1 || maxTicks > 1200 || drag < 0.0) {
                throw new IllegalArgumentException("Invalid cannon trajectory limits");
            }
        }
    }

    private static void requireFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite cannon aim");
        }
    }
}
