package com.hooya.stabilizedturret.network;

import com.caca.copypoly.PolyPackets;
import com.caca.copypoly.api.PolyShape;
import com.caca.copypoly.geometry.ShapeBounds;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.ArmorGroupMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ApplyArmorGroupPayload(int menu, int[] graph) implements CustomPacketPayload {
    public static final Type<ApplyArmorGroupPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"apply_armor_group"));
    private static final int MAX_GRAPH = PolyShape.maxPacked(new ShapeBounds(32,16,16));
    public static final StreamCodec<RegistryFriendlyByteBuf,ApplyArmorGroupPayload> CODEC = StreamCodec.of(
        (buf, payload) -> { buf.writeVarInt(payload.menu); buf.writeVarIntArray(payload.graph); },
        buf -> new ApplyArmorGroupPayload(buf.readVarInt(),buf.readVarIntArray(MAX_GRAPH)));

    public static void handle(ApplyArmorGroupPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof ArmorGroupMenu menu)
                    || menu.containerId != payload.menu) return;
            PacketDistributor.sendToPlayer(player,new PolyPackets.Result(payload.menu,menu.apply(player,payload.graph)));
        });
    }

    @Override public Type<ApplyArmorGroupPayload> type() { return TYPE; }
}
