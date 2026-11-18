package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

/** One render-only weapon pose for its observer; never changes server camera or chunk tracking. */
public record WeaponViewPayload(ResourceLocation dimension, UUID id, ResourceLocation entityType,
                                Vec3 position, Vec3 velocity, long serverTick) implements CustomPacketPayload {
    public static final Type<WeaponViewPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "weapon_view"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponViewPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeResourceLocation(p.dimension); b.writeBoolean(p.id != null);
                if (p.id != null) {
                    b.writeUUID(p.id); b.writeResourceLocation(p.entityType);
                    b.writeDouble(p.position.x); b.writeDouble(p.position.y); b.writeDouble(p.position.z);
                    b.writeDouble(p.velocity.x); b.writeDouble(p.velocity.y); b.writeDouble(p.velocity.z);
                    b.writeLong(p.serverTick);
                }
            }, b -> {
                ResourceLocation dimension = b.readResourceLocation();
                return b.readBoolean() ? new WeaponViewPayload(dimension, b.readUUID(), b.readResourceLocation(),
                        new Vec3(b.readDouble(), b.readDouble(), b.readDouble()),
                        new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), b.readLong())
                        : new WeaponViewPayload(dimension, null, null, Vec3.ZERO, Vec3.ZERO, 0);
            });

    public static void handle(WeaponViewPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT)
                ClientPayloadHandlers.handleWeaponView(payload);
        });
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
