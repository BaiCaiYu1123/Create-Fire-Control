package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Live attachment update after installation or a one-pixel spacing change. */
public record MountedHeavyMachineGunSyncPayload(
        int entityId,
        @Nullable MountedHeavyMachineGunCarrier.Attachment left,
        @Nullable MountedHeavyMachineGunCarrier.Attachment right
) implements CustomPacketPayload {
    public static final Type<MountedHeavyMachineGunSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "mounted_heavy_machine_gun_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            MountedHeavyMachineGunSyncPayload> STREAM_CODEC = StreamCodec.of(
            MountedHeavyMachineGunSyncPayload::write,
            MountedHeavyMachineGunSyncPayload::read);

    public static MountedHeavyMachineGunSyncPayload from(
            PitchOrientedContraptionEntity entity,
            MountedHeavyMachineGunCarrier carrier
    ) {
        return new MountedHeavyMachineGunSyncPayload(
                entity.getId(),
                carrier.createFireControl$getMountedHeavyMachineGun(
                        MountedHeavyMachineGunCarrier.Side.LEFT),
                carrier.createFireControl$getMountedHeavyMachineGun(
                        MountedHeavyMachineGunCarrier.Side.RIGHT));
    }

    private static void write(
            RegistryFriendlyByteBuf buffer,
            MountedHeavyMachineGunSyncPayload payload
    ) {
        buffer.writeVarInt(payload.entityId);
        writeAttachment(buffer, payload.left);
        writeAttachment(buffer, payload.right);
    }

    private static MountedHeavyMachineGunSyncPayload read(RegistryFriendlyByteBuf buffer) {
        return new MountedHeavyMachineGunSyncPayload(
                buffer.readVarInt(), readAttachment(buffer), readAttachment(buffer));
    }

    private static void writeAttachment(
            RegistryFriendlyByteBuf buffer,
            @Nullable MountedHeavyMachineGunCarrier.Attachment attachment
    ) {
        buffer.writeBoolean(attachment != null);
        if (attachment == null) return;
        buffer.writeBlockPos(attachment.anchor());
        buffer.writeByte(attachment.spacingPixels());
    }

    private static @Nullable MountedHeavyMachineGunCarrier.Attachment readAttachment(
            RegistryFriendlyByteBuf buffer
    ) {
        if (!buffer.readBoolean()) return null;
        BlockPos anchor = buffer.readBlockPos();
        return new MountedHeavyMachineGunCarrier.Attachment(
                anchor, buffer.readByte());
    }

    public static void handle(
            MountedHeavyMachineGunSyncPayload payload, IPayloadContext context
    ) {
        context.enqueueWork(() -> ClientPayloadHandlers.handleMountedHeavyMachineGunSync(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
