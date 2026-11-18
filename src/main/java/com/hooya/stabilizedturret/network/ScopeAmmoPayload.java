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

/** Total APFSDS in the loaders and chamber. -1 hides the badge for an unrelated ammunition type. */
public record ScopeAmmoPayload(int remaining) implements CustomPacketPayload {
    public ScopeAmmoPayload {
        if (remaining < -1) throw new IllegalArgumentException("Invalid scope ammunition count");
    }

    public static final Type<ScopeAmmoPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "scope_ammo"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScopeAmmoPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeBoolean(payload.remaining >= 0);
                if (payload.remaining >= 0) buffer.writeVarInt(payload.remaining);
            }, buffer -> {
                if (!buffer.readBoolean()) return new ScopeAmmoPayload(-1);
                int rounds = buffer.readVarInt();
                if (rounds < 0) throw new IllegalArgumentException("Negative visible ammunition count");
                return new ScopeAmmoPayload(rounds);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ScopeAmmoPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT)
            context.enqueueWork(() -> ClientPayloadHandlers.handleScopeAmmo(payload));
    }
}
