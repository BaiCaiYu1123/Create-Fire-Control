package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.controller.ScopeOrientationMath;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Raw physical headings, independent of ballistic corrections and the player's look input. */
public record ScopeOrientationPayload(Heading hull, Heading turret) implements CustomPacketPayload {
    public record Heading(UUID bodyId, Vec3 localForward, Vec3 worldForward) {
        public Heading {
            if (!ScopeOrientationMath.valid(localForward) || !ScopeOrientationMath.valid(worldForward))
                throw new IllegalArgumentException("Invalid scope heading");
        }
    }

    public static final Type<ScopeOrientationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "scope_orientation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScopeOrientationPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                write(buffer, payload.hull);
                write(buffer, payload.turret);
            }, buffer -> new ScopeOrientationPayload(read(buffer), read(buffer)));

    public static ScopeOrientationPayload inactive() { return new ScopeOrientationPayload(null, null); }

    private static void write(RegistryFriendlyByteBuf buffer, Heading heading) {
        buffer.writeBoolean(heading != null);
        if (heading == null) return;
        buffer.writeBoolean(heading.bodyId != null);
        if (heading.bodyId != null) buffer.writeUUID(heading.bodyId);
        for (Vec3 vector : new Vec3[]{heading.localForward, heading.worldForward}) {
            buffer.writeDouble(vector.x); buffer.writeDouble(vector.y); buffer.writeDouble(vector.z);
        }
    }

    private static Heading read(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return null;
        UUID body = buffer.readBoolean() ? buffer.readUUID() : null;
        return new Heading(body, new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ScopeOrientationPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT)
            context.enqueueWork(() -> ClientPayloadHandlers.handleScopeOrientation(payload));
    }
}
