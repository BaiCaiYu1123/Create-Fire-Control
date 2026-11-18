package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record BindingInspectionPayload(
        boolean active,
        List<BindingSessionPayload.Candidate> bindings,
        List<BindingSessionPayload.Candidate> scopeReferences,
        double scopeOffsetX,
        double scopeOffsetY,
        double scopeOffsetZ
) implements CustomPacketPayload {
    private static final int MAX_ENTRIES = 1024;
    public static final Type<BindingInspectionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "binding_inspection")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, BindingInspectionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBoolean(payload.active);
                        writeCandidates(buffer, payload.bindings);
                        writeCandidates(buffer, payload.scopeReferences);
                        buffer.writeDouble(payload.scopeOffsetX);
                        buffer.writeDouble(payload.scopeOffsetY);
                        buffer.writeDouble(payload.scopeOffsetZ);
                    },
                    buffer -> new BindingInspectionPayload(
                            buffer.readBoolean(),
                            readCandidates(buffer),
                            readCandidates(buffer),
                            buffer.readDouble(),
                            buffer.readDouble(),
                            buffer.readDouble()
                    )
            );

    public BindingInspectionPayload {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        scopeReferences = scopeReferences == null ? List.of() : List.copyOf(scopeReferences);
    }

    public static BindingInspectionPayload inactive() {
        return new BindingInspectionPayload(false, List.of(), List.of(), 0.0, 0.0, 0.0);
    }

    private static void writeCandidates(
            RegistryFriendlyByteBuf buffer,
            List<BindingSessionPayload.Candidate> candidates
    ) {
        int size = Math.min(MAX_ENTRIES, candidates.size());
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            BindingSessionPayload.Candidate candidate = candidates.get(index);
            buffer.writeBlockPos(candidate.pos());
            buffer.writeBoolean(candidate.subLevelId() != null);
            if (candidate.subLevelId() != null) buffer.writeUUID(candidate.subLevelId());
        }
    }

    private static List<BindingSessionPayload.Candidate> readCandidates(
            RegistryFriendlyByteBuf buffer
    ) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid binding inspection entry count: " + size);
        }
        List<BindingSessionPayload.Candidate> candidates = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            BlockPos pos = buffer.readBlockPos();
            UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
            candidates.add(new BindingSessionPayload.Candidate(pos, subLevelId, true));
        }
        return List.copyOf(candidates);
    }

    public static void handle(BindingInspectionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleBindingInspection(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
