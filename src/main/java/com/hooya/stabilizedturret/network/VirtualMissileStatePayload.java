package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.missile.client.ClientMissileManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Idempotent snapshot/delta packet for virtual missiles and flares. */
public record VirtualMissileStatePayload(ResourceLocation dimension, CompoundTag data)
        implements CustomPacketPayload {
    public static final Type<VirtualMissileStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "virtual_missile_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VirtualMissileStatePayload> STREAM_CODEC =
            StreamCodec.of((b, p) -> { b.writeResourceLocation(p.dimension); b.writeNbt(p.data); },
                    b -> new VirtualMissileStatePayload(b.readResourceLocation(), b.readNbt()));

    public static void handle(VirtualMissileStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) ClientMissileManager.accept(payload);
        });
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
