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

public record ControllerGlobalBearingSettingsPayload(
        BlockPos controllerPos, UUID controllerSubLevelId, String role, int mask,
        double depression, double elevation, double speed, boolean reversed, boolean headHidden,
        double offsetX, double offsetY, double offsetZ, YawLimits yawLimits) implements CustomPacketPayload {
    public static final Type<ControllerGlobalBearingSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "controller_global_bearing_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerGlobalBearingSettingsPayload> STREAM_CODEC =
            StreamCodec.of((buf, value) -> {
                buf.writeBlockPos(value.controllerPos); buf.writeBoolean(value.controllerSubLevelId != null);
                if (value.controllerSubLevelId != null) buf.writeUUID(value.controllerSubLevelId);
                buf.writeUtf(value.role, 8); buf.writeVarInt(value.mask);
                buf.writeDouble(value.depression); buf.writeDouble(value.elevation); buf.writeDouble(value.speed);
                buf.writeBoolean(value.reversed); buf.writeBoolean(value.headHidden);
                buf.writeDouble(value.offsetX); buf.writeDouble(value.offsetY); buf.writeDouble(value.offsetZ);
                buf.writeDouble(value.yawLimits.left()); buf.writeDouble(value.yawLimits.right());
                buf.writeVarInt(value.yawLimits.centerDirection());
            }, buf -> new ControllerGlobalBearingSettingsPayload(
                    buf.readBlockPos(), buf.readBoolean() ? buf.readUUID() : null, buf.readUtf(8), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean(), buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), new YawLimits(buf.readDouble(), buf.readDouble(), buf.readVarInt())));

    public static void handle(ControllerGlobalBearingSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()
                    || !player.mayBuild() || !finite(payload) || payload.mask < 0 || payload.mask > 511) return;
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
                    || !(lookup.getBlockEntity(payload.controllerPos) instanceof StabilizerControllerBlockEntity controller)
                    || player.distanceToSqr(controller.getBlockPos().getCenter()) > 64.0) return;
            controller.applyGlobalBearingSettings(slot, payload.mask, payload.depression, payload.elevation,
                    payload.speed, payload.reversed, payload.headHidden,
                    new Vec3(payload.offsetX, payload.offsetY, payload.offsetZ), payload.yawLimits);
        });
    }
    private static boolean finite(ControllerGlobalBearingSettingsPayload value) {
        return finite(value.depression, value.elevation, value.speed, value.offsetX, value.offsetY, value.offsetZ);
    }
    private static boolean finite(double... values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
