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
                                             double radarRange, double yawSpeed, double pitchSpeed,
                                             double depressionLimit, double elevationLimit,
                                             boolean yawReversed, boolean pitchReversed,
                                             double cannonFireRange,
                                             List<Device> devices)
        implements CustomPacketPayload {
    public static final Type<OpenAutoDefenseDisplayPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "open_auto_defense_display"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenAutoDefenseDisplayPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> { buf.writeBlockPos(value.pos); buf.writeBoolean(value.subLevelId!=null);if(value.subLevelId!=null)buf.writeUUID(value.subLevelId); buf.writeBoolean(value.automaticAttack); buf.writeDouble(value.radarRange);buf.writeDouble(value.yawSpeed);buf.writeDouble(value.pitchSpeed);buf.writeDouble(value.depressionLimit);buf.writeDouble(value.elevationLimit);buf.writeBoolean(value.yawReversed);buf.writeBoolean(value.pitchReversed);buf.writeDouble(value.cannonFireRange);
                buf.writeVarInt(value.devices.size()); for (Device device : value.devices) { buf.writeUtf(device.slot); buf.writeBlockPos(device.pos); } },
            buf -> { BlockPos pos=buf.readBlockPos();UUID sub=buf.readBoolean()?buf.readUUID():null; boolean active=buf.readBoolean(); double range=buf.readDouble();double yaw=buf.readDouble();double pitch=buf.readDouble();double depression=buf.readDouble();double elevation=buf.readDouble();boolean yawReversed=buf.readBoolean();boolean pitchReversed=buf.readBoolean();double cannonFireRange=buf.readDouble();
                int size=Math.min(64,Math.max(0,buf.readVarInt())); List<Device> devices=new ArrayList<>();
                for(int i=0;i<size;i++) devices.add(new Device(buf.readUtf(64),buf.readBlockPos()));
                return new OpenAutoDefenseDisplayPayload(pos,sub,active,range,yaw,pitch,depression,elevation,yawReversed,pitchReversed,cannonFireRange,List.copyOf(devices)); });
    public static OpenAutoDefenseDisplayPayload from(ConnectedDisplayBlockEntity display) {
        var sub=com.hooya.stabilizedturret.content.controller.BindingRef.capture(display.getLevel(),display.getBlockPos()).subLevelId();
        return new OpenAutoDefenseDisplayPayload(display.getBlockPos(),sub, display.isAutomaticAttack(), display.getRadarRange(),
                display.getYawSpeed(),display.getPitchSpeed(),display.getDepressionLimit(),display.getElevationLimit(),
                display.isYawReversed(),display.isPitchReversed(),
                display.getCannonFireRange(),
                display.getDevices().stream().map(device -> new Device(device.slot().name(),device.ref().pos())).toList());
    }
    public static void handle(OpenAutoDefenseDisplayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> { if (FMLEnvironment.dist==Dist.CLIENT) ClientPayloadHandlers.openAutoDefenseDisplay(payload); });
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public record Device(String slot, BlockPos pos) {}
}
