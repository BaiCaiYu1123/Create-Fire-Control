package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AutoDefenseSettingsPayload(BlockPos pos, UUID subLevelId, boolean automaticAttack,
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
                                         int mapResolution)
        implements CustomPacketPayload {
    public static final Type<AutoDefenseSettingsPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"auto_defense_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf,AutoDefenseSettingsPayload> STREAM_CODEC=StreamCodec.of(
            (b,v)->{b.writeBlockPos(v.pos);b.writeBoolean(v.subLevelId!=null);if(v.subLevelId!=null)b.writeUUID(v.subLevelId);b.writeBoolean(v.automaticAttack);b.writeBoolean(v.scanStructures);b.writeBoolean(v.scanLiving);b.writeBoolean(v.scanMissiles);b.writeBoolean(v.automaticMissileDefense);b.writeDouble(v.automaticMissileDefenseRange);b.writeDouble(v.missileProximityRange);b.writeDouble(v.radarRange);b.writeDouble(v.yawSpeed);b.writeDouble(v.pitchSpeed);b.writeDouble(v.depressionLimit);b.writeDouble(v.elevationLimit);b.writeBoolean(v.yawReversed);b.writeBoolean(v.pitchReversed);b.writeDouble(v.cannonFireRange);b.writeVarInt(v.cannonFirePower);b.writeVarInt(v.mapUpdateIntervalSeconds);b.writeVarInt(v.mapResolution);},
            b->{BlockPos p=b.readBlockPos();UUID s=b.readBoolean()?b.readUUID():null;return new AutoDefenseSettingsPayload(p,s,b.readBoolean(),b.readBoolean(),b.readBoolean(),b.readBoolean(),b.readBoolean(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readDouble(),b.readBoolean(),b.readBoolean(),b.readDouble(),b.readVarInt(),b.readVarInt(),b.readVarInt());});
    public static void handle(AutoDefenseSettingsPayload payload, IPayloadContext context){context.enqueueWork(()->{
        if (!(context.player() instanceof ServerPlayer player)
                || player.isSpectator() || !player.mayBuild()
                || !finite(payload)) return;
        Level lookup=player.level();
        if(payload.subLevelId!=null){var sub=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(lookup).getSubLevel(payload.subLevelId);if(sub==null||sub.isRemoved())return;lookup=sub.getLevel();}
        Vec3 worldPos=dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(
                lookup,payload.pos.getCenter());
        // Validate the cheap, non-loading facts before asking either level for
        // a block entity.  The settings screen is opened by a direct shift-use,
        // so the vanilla interaction reach is the appropriate authority here.
        if (!finite(worldPos.x, worldPos.y, worldPos.z)
                || player.distanceToSqr(worldPos)>8.0*8.0
                || !lookup.hasChunkAt(payload.pos)) return;
        if(lookup.getBlockEntity(payload.pos) instanceof ConnectedDisplayBlockEntity display) {
                display.setSettings(
                        payload.automaticAttack,payload.radarRange,payload.yawSpeed,payload.pitchSpeed,
                        payload.depressionLimit,payload.elevationLimit,
                        payload.yawReversed,payload.pitchReversed,
                        payload.cannonFireRange,payload.cannonFirePower,
                        payload.mapUpdateIntervalSeconds,
                        payload.mapResolution);
                display.setScanFilters(payload.scanStructures,
                        payload.scanLiving,
                        payload.scanMissiles);
                display.setAutomaticMissileDefense(payload.automaticMissileDefense);
                display.setAutomaticMissileDefenseRange(payload.automaticMissileDefenseRange);
                display.setMissileProximityRange(payload.missileProximityRange);
        }
    });}
    private static boolean finite(AutoDefenseSettingsPayload payload) {
        return finite(payload.automaticMissileDefenseRange, payload.missileProximityRange,
                payload.radarRange, payload.yawSpeed, payload.pitchSpeed,
                payload.depressionLimit, payload.elevationLimit, payload.cannonFireRange);
    }
    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
