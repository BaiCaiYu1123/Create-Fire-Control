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

public record BindingSessionPayload(boolean active, List<Candidate> candidates)
        implements CustomPacketPayload {
    public static final Type<BindingSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "binding_session")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, BindingSessionPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBoolean(payload.active);
                        int size = Math.min(1024, payload.candidates.size());
                        buffer.writeVarInt(size);
                        for (int index = 0; index < size; index++) {
                            Candidate candidate = payload.candidates.get(index);
                            buffer.writeBlockPos(candidate.pos());
                            buffer.writeBoolean(candidate.subLevelId() != null);
                            if (candidate.subLevelId() != null) {
                                buffer.writeUUID(candidate.subLevelId());
                            }
                            buffer.writeBoolean(candidate.bound());
                        }
                    },
                    buffer -> {
                        boolean active = buffer.readBoolean();
                        int size = Math.min(1024, Math.max(0, buffer.readVarInt()));
                        List<Candidate> candidates = new ArrayList<>(size);
                        for (int index = 0; index < size; index++) {
                            BlockPos pos = buffer.readBlockPos();
                            UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
                            boolean bound = buffer.readBoolean();
                            candidates.add(new Candidate(pos, subLevelId, bound));
                        }
                        return new BindingSessionPayload(active, List.copyOf(candidates));
                    }
            );

    public static BindingSessionPayload inactive() {
        return new BindingSessionPayload(false, List.of());
    }

    public record Candidate(BlockPos pos, UUID subLevelId, boolean bound) {
        public Candidate {
            pos = pos.immutable();
        }
    }

    public static void handle(BindingSessionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                ClientPayloadHandlers.handleBindingSession(payload);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
