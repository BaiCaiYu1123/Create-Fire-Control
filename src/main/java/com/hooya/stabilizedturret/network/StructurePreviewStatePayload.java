package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.StructurePreviewScreen;
import com.hooya.stabilizedturret.content.bearing.PreviewBearingSettings;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.iff.IffStructureTopology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;

public record StructurePreviewStatePayload(BlockPos pos, UUID body, boolean display, List<UUID> bodies, List<UUID> opaqueBodies,
                                          List<Device> devices) implements CustomPacketPayload {
    public record Device(String role, BlockPos pos, UUID body, String name, PreviewBearingSettings settings) {
        public boolean editable() { return role.equals("YAW") || role.equals("PITCH"); }
    }
    public static final Type<StructurePreviewStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            StabilizedTurret.MOD_ID, "structure_preview_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StructurePreviewStatePayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeBlockPos(p.pos); b.writeUUID(p.body); b.writeBoolean(p.display);
                b.writeVarInt(p.bodies.size()); p.bodies.forEach(b::writeUUID);
                b.writeVarInt(p.opaqueBodies.size()); p.opaqueBodies.forEach(b::writeUUID);
                b.writeVarInt(p.devices.size());
                for (Device d : p.devices) {
                    b.writeUtf(d.role, 64); b.writeBlockPos(d.pos); b.writeBoolean(d.body != null);
                    if (d.body != null) b.writeUUID(d.body);
                    b.writeUtf(d.name, 256); b.writeBoolean(d.settings != null);
                    if (d.settings != null) b.writeNbt(d.settings.save());
                }
            }, b -> {
                BlockPos pos = b.readBlockPos(); UUID body = b.readUUID(); boolean display = b.readBoolean();
                int count = bounded(b.readVarInt()); List<UUID> bodies = new ArrayList<>();
                for (int i = 0; i < count; i++) bodies.add(b.readUUID());
                count = bounded(b.readVarInt()); List<UUID> opaqueBodies = new ArrayList<>();
                for (int i = 0; i < count; i++) opaqueBodies.add(b.readUUID());
                count = bounded(b.readVarInt()); List<Device> devices = new ArrayList<>();
                for (int i = 0; i < count; i++) devices.add(new Device(b.readUtf(64), b.readBlockPos(),
                        b.readBoolean() ? b.readUUID() : null, b.readUtf(256),
                        b.readBoolean() ? PreviewBearingSettings.load(Objects.requireNonNull(b.readNbt())) : null));
                return new StructurePreviewStatePayload(pos, body, display, List.copyOf(bodies), List.copyOf(opaqueBodies), List.copyOf(devices));
            });
    private static int bounded(int count) {
        if (count < 0 || count > 4096) throw new IllegalArgumentException("Invalid structure preview count");
        return count;
    }
    static StructurePreviewStatePayload from(BlockEntity host, UUID body, boolean display) {
        List<Device> result = new ArrayList<>();
        for (var d : StructurePreviewAccess.devices(host)) {
            if (result.size() >= 4096) break;
            PreviewBearingSettings settings = null;
            if (d.slot() == com.hooya.stabilizedturret.content.controller.BindingSlot.YAW
                    || d.slot() == com.hooya.stabilizedturret.content.controller.BindingSlot.PITCH) {
                if (host instanceof StabilizerControllerBlockEntity controller) {
                    var s = controller.getBearingSettings(d.slot(), d.ref());
                    settings = new PreviewBearingSettings(s.depression(), s.elevation(), s.speed(), s.reversed(),
                            s.headHidden(), s.offset(), s.yawLimits());
                } else settings = ((ConnectedDisplayBlockEntity)host).getPreviewBearingSettings(d.slot(), d.ref());
            }
            String name = d.ref().isValidIn(host.getLevel()) && host.getLevel().hasChunkAt(d.ref().pos())
                    ? host.getLevel().getBlockState(d.ref().pos()).getBlock().getDescriptionId()
                    : "screen.create_fire_control.preview.unloaded";
            result.add(new Device(d.slot().name(), d.ref().pos(), d.ref().subLevelId(), name, settings));
        }
        Set<UUID> bodies = new LinkedHashSet<>(IffStructureTopology.expand(host.getLevel(), Set.of(body)));
        result.stream().map(Device::body).filter(Objects::nonNull).forEach(bodies::add);
        Set<UUID> opaque = turretBodies(host);
        bodies.addAll(opaque);
        return new StructurePreviewStatePayload(host.getBlockPos(), body, display,
                bodies.stream().limit(4096).toList(), opaque.stream().limit(4096).toList(), List.copyOf(result));
    }

    private static Set<UUID> turretBodies(BlockEntity host) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(host.getLevel());
        Map<UUID, Set<UUID>> children = new HashMap<>();
        for (var parent : container.getAllSubLevels()) {
            if (parent.isRemoved()) continue;
            for (var actor : parent.getPlot().getBlockEntityActors()) {
                if (actor instanceof dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity bearing
                        && bearing.isAssembled() && bearing.getSubLevelID() != null)
                    children.computeIfAbsent(parent.getUniqueId(), ignored -> new HashSet<>()).add(bearing.getSubLevelID());
            }
        }
        Set<UUID> roots = new HashSet<>();
        for (var device : StructurePreviewAccess.devices(host)) {
            if (device.slot() != com.hooya.stabilizedturret.content.controller.BindingSlot.YAW
                    && device.slot() != com.hooya.stabilizedturret.content.controller.BindingSlot.PITCH) continue;
            if (device.ref().resolve(host.getLevel()) instanceof dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity bearing
                    && bearing.isAssembled() && bearing.getSubLevelID() != null) roots.add(bearing.getSubLevelID());
        }
        return container.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                ? com.hooya.stabilizedturret.content.bearing.PreviewTurretBodies.select(level, roots, children) : Set.of();
    }
    public static void handle(StructurePreviewStatePayload p, IPayloadContext context) {
        context.enqueueWork(() -> { if (FMLEnvironment.dist == Dist.CLIENT) StructurePreviewScreen.accept(p); });
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
