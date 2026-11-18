package com.hooya.stabilizedturret.client.preview;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.StructurePreviewStatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Separate relevance from occupancy so binding/topology changes invalidate the cached material too. */
public record StructurePreviewMaterials(Set<UUID> opaqueBodies, Map<UUID, Set<BlockPos>> devices) {
    public static StructurePreviewMaterials from(StructurePreviewStatePayload state) {
        Map<UUID, Set<BlockPos>> positions = new HashMap<>();
        positions.computeIfAbsent(state.body(), ignored -> new HashSet<>()).add(state.pos());
        for (var device : state.devices()) positions.computeIfAbsent(device.body(), ignored -> new HashSet<>()).add(device.pos());
        positions.replaceAll((body, blocks) -> Set.copyOf(blocks));
        return new StructurePreviewMaterials(Set.copyOf(state.opaqueBodies()), Collections.unmodifiableMap(positions));
    }

    public boolean opaque(UUID body, BlockPos pos) {
        return opaqueBodies.contains(body) || devices.getOrDefault(body, Set.of()).contains(pos);
    }

    public static boolean fireControlBlock(ResourceLocation id) {
        if (!id.getNamespace().equals(StabilizedTurret.MOD_ID)) return false;
        return switch (id.getPath()) {
            case "gas_turbine", "ztz99a_diesel_engine", "tiger_i_engine" -> false;
            default -> true;
        };
    }
}
