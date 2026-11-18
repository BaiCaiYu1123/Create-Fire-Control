package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record ControllerBearingSettingsPayload(
        BlockPos controllerPos, UUID controllerSubLevelId, String role, BlockPos bearingPos,
        double depression, double elevation, double speed, boolean reversed, boolean headHidden,
        double offsetX, double offsetY, double offsetZ, YawLimits yawLimits) implements CustomPacketPayload {
    public static final Type<ControllerBearingSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "controller_bearing_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerBearingSettingsPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> {
                buf.writeBlockPos(value.controllerPos);
                buf.writeBoolean(value.controllerSubLevelId != null);
                if (value.controllerSubLevelId != null) buf.writeUUID(value.controllerSubLevelId);
                buf.writeUtf(value.role, 8); buf.writeBlockPos(value.bearingPos);
                buf.writeDouble(value.depression); buf.writeDouble(value.elevation);
                buf.writeDouble(value.speed); buf.writeBoolean(value.reversed);
                buf.writeBoolean(value.headHidden); buf.writeDouble(value.offsetX);
                buf.writeDouble(value.offsetY); buf.writeDouble(value.offsetZ);
                buf.writeDouble(value.yawLimits.left()); buf.writeDouble(value.yawLimits.right());
                buf.writeVarInt(value.yawLimits.centerDirection());
            }, buf -> new ControllerBearingSettingsPayload(
                    buf.readBlockPos(), buf.readBoolean() ? buf.readUUID() : null,
                    buf.readUtf(8), buf.readBlockPos(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), new YawLimits(buf.readDouble(), buf.readDouble(), buf.readVarInt())));

    public static void handle(ControllerBearingSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || player.isSpectator() || !player.mayBuild()
                    || !finite(payload)) return;
            BindingSlot slot;
            try { slot = BindingSlot.valueOf(payload.role); }
            catch (IllegalArgumentException ignored) { return; }
            if (slot != BindingSlot.YAW && slot != BindingSlot.PITCH) return;
            Level lookup = player.level();
            if (payload.controllerSubLevelId != null) {
                var sub = SubLevelContainer.getContainer(lookup).getSubLevel(payload.controllerSubLevelId);
                if (sub == null || sub.isRemoved()) return;
                lookup = sub.getLevel();
            }
            if (!lookup.hasChunkAt(payload.controllerPos)
                    || !(lookup.getBlockEntity(payload.controllerPos)
                    instanceof StabilizerControllerBlockEntity controller)) return;
            if (player.distanceToSqr(controller.getBlockPos().getCenter()) > 8.0 * 8.0) return;
            controller.setBearingSettings(slot, payload.bearingPos, payload.depression,
                    payload.elevation, payload.speed, payload.reversed, payload.headHidden,
                    new Vec3(payload.offsetX, payload.offsetY, payload.offsetZ), payload.yawLimits);
        });
    }

    private static boolean finite(ControllerBearingSettingsPayload value) {
        return finite(value.depression, value.elevation, value.speed,
                value.offsetX, value.offsetY, value.offsetZ);
    }
    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
