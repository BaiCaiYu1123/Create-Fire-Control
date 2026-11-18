package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import net.minecraft.core.BlockPos;
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

public record OpenAutoDefenseDisplayPayload(BlockPos pos, UUID subLevelId, boolean automaticAttack,
                                             boolean scanStructures,
                                             boolean scanLiving,
                                             boolean scanMissiles,
                                             boolean automaticMissileDefense,
                                             double automaticMissileDefenseRange,
                                             double missileProximityRange,
                                             double radarRange, double yawSpeed, double pitchSpeed,
                                             double depressionLimit, double elevationLimit,
                                             boolean yawReversed, boolean pitchReversed,
                                             double cannonFireRange,
                                             int cannonFirePower,
                                             int mapUpdateIntervalSeconds,
                                             int mapResolution,
                                             List<Device> devices)
        implements CustomPacketPayload {
    private static final int MAX_DEVICES = 64;
    public static final Type<OpenAutoDefenseDisplayPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "open_auto_defense_display"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAutoDefenseDisplayPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> { buf.writeBlockPos(value.pos); buf.writeBoolean(value.subLevelId!=null);if(value.subLevelId!=null)buf.writeUUID(value.subLevelId); buf.writeBoolean(value.automaticAttack);buf.writeBoolean(value.scanStructures);buf.writeBoolean(value.scanLiving);buf.writeBoolean(value.scanMissiles); buf.writeBoolean(value.automaticMissileDefense); buf.writeDouble(value.automaticMissileDefenseRange); buf.writeDouble(value.missileProximityRange); buf.writeDouble(value.radarRange);buf.writeDouble(value.yawSpeed);buf.writeDouble(value.pitchSpeed);buf.writeDouble(value.depressionLimit);buf.writeDouble(value.elevationLimit);buf.writeBoolean(value.yawReversed);buf.writeBoolean(value.pitchReversed);buf.writeDouble(value.cannonFireRange);buf.writeVarInt(value.cannonFirePower);buf.writeVarInt(value.mapUpdateIntervalSeconds);buf.writeVarInt(value.mapResolution);
                int size=Math.min(MAX_DEVICES,value.devices.size()); buf.writeVarInt(size); for (int i=0;i<size;i++) { Device device=value.devices.get(i); buf.writeUtf(device.slot,64); buf.writeBlockPos(device.pos); buf.writeDouble(device.offsetX); buf.writeDouble(device.offsetY); buf.writeDouble(device.offsetZ); } },
            buf -> { BlockPos pos=buf.readBlockPos();UUID sub=buf.readBoolean()?buf.readUUID():null; boolean active=buf.readBoolean();boolean scanStructures=buf.readBoolean();boolean scanLiving=buf.readBoolean();boolean scanMissiles=buf.readBoolean(); boolean automaticMissileDefense=buf.readBoolean(); double automaticMissileDefenseRange=buf.readDouble(); double missileProximityRange=buf.readDouble(); double range=buf.readDouble();double yaw=buf.readDouble();double pitch=buf.readDouble();double depression=buf.readDouble();double elevation=buf.readDouble();boolean yawReversed=buf.readBoolean();boolean pitchReversed=buf.readBoolean();double cannonFireRange=buf.readDouble();int cannonFirePower=buf.readVarInt();int mapUpdateIntervalSeconds=buf.readVarInt();int mapResolution=buf.readVarInt();
                int size=buf.readVarInt(); if(size<0||size>MAX_DEVICES)throw new IllegalArgumentException("Too many auto-defense devices"); List<Device> devices=new ArrayList<>(size);
                for(int i=0;i<size;i++) devices.add(new Device(buf.readUtf(64),buf.readBlockPos(),buf.readDouble(),buf.readDouble(),buf.readDouble()));
                return new OpenAutoDefenseDisplayPayload(pos,sub,active,scanStructures,scanLiving,scanMissiles,automaticMissileDefense,automaticMissileDefenseRange,missileProximityRange,range,yaw,pitch,depression,elevation,yawReversed,pitchReversed,cannonFireRange,cannonFirePower,mapUpdateIntervalSeconds,mapResolution,List.copyOf(devices)); });
    public static OpenAutoDefenseDisplayPayload from(ConnectedDisplayBlockEntity display) {
        var sub=com.hooya.stabilizedturret.content.controller.BindingRef.capture(display.getLevel(),display.getBlockPos()).subLevelId();
        return new OpenAutoDefenseDisplayPayload(display.getBlockPos(),sub, display.isAutomaticAttack(),
                display.isScanningStructures(), display.isScanningLiving(),
                display.isScanningMissiles(),
                display.isAutomaticMissileDefense(), display.getAutomaticMissileDefenseRange(), display.getMissileProximityRange(), display.getRadarRange(),
                display.getYawSpeed(),display.getPitchSpeed(),display.getDepressionLimit(),display.getElevationLimit(),
                display.isYawReversed(),display.isPitchReversed(),
                display.getCannonFireRange(),
                display.getCannonFirePower(),
                display.getMapUpdateIntervalSeconds(),
                display.getMapResolution(),
                display.getDevices().stream().map(device -> {
                    var offset = device.slot() == com.hooya.stabilizedturret.content.controller.BindingSlot.PITCH
                            ? display.getPitchOffset(device.ref()) : net.minecraft.world.phys.Vec3.ZERO;
                    return new Device(device.slot().name(), device.ref().pos(), offset.x, offset.y, offset.z);
                }).toList());
    }
    public static void handle(OpenAutoDefenseDisplayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> { if (FMLEnvironment.dist==Dist.CLIENT) ClientPayloadHandlers.openAutoDefenseDisplay(payload); });
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public record Device(String slot, BlockPos pos, double offsetX, double offsetY, double offsetZ) {
        public Device(String slot, BlockPos pos) { this(slot, pos, 0.0, 0.0, 0.0); }
    }
}
