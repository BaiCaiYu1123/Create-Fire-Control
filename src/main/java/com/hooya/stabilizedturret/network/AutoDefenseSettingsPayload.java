package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AutoDefenseSettingsPayload(BlockPos pos, UUID subLevelId, boolean automaticAttack,
                                         double radarRange, double yawSpeed, double pitchSpeed,
                                         double depressionLimit, double elevationLimit,
                                         boolean yawReversed, boolean pitchReversed,
                                         double cannonFireRange)
        implements CustomPacketPayload {
    public static final Type<AutoDefenseSettingsPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"auto_defense_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AutoDefenseSettingsPayload> STREAM_CODEC=StreamCodec.of(
            (b,v)->{b.writeBlockPos(v.pos);b.writeBoolean(v.subLevelId!=null);if(v.subLevelId!=null)b.writeUUID(v.subLevelId);b.writeBoolean(v.automaticAttack);b.writeDouble(v.radarRange);b.writeDouble(v.yawSpeed);b.writeDouble(v.pitchSpeed);b.writeDouble(v.depressionLimit);b.writeDouble(v.elevationLimit);b.writeBoolean(v.yawReversed);b.writeBoolean(v.pitchReversed);b.writeDouble(v.cannonFireRange);},
            b->{BlockPos p=b.readBlockPos();UUID s=b.readBoolean()?b.readUUID():null;return new AutoDefenseSettingsPayload(p,s,b.readBoolean(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readBoolean(),b.readBoolean(),b.readDouble());});
    public static void handle(AutoDefenseSettingsPayload payload, IPayloadContext context){context.enqueueWork(()->{
        net.minecraft.world.level.Level lookup=context.player().level();
        if(payload.subLevelId!=null){var sub=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(lookup).getSubLevel(payload.subLevelId);if(sub!=null)lookup=sub.getLevel();}
        if(lookup.getBlockEntity(payload.pos) instanceof ConnectedDisplayBlockEntity display) {
            net.minecraft.world.phys.Vec3 worldPos=dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(
                    lookup,payload.pos.getCenter());
            if(context.player().distanceToSqr(worldPos)<=64*64) display.setSettings(
                    payload.automaticAttack,payload.radarRange,payload.yawSpeed,payload.pitchSpeed,
                    payload.depressionLimit,payload.elevationLimit,
                    payload.yawReversed,payload.pitchReversed,
                    payload.cannonFireRange);
        }
    });}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
