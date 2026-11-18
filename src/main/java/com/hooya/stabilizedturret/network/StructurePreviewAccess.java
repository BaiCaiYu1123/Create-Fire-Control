package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class StructurePreviewAccess {
    record Device(BindingSlot slot, BindingRef ref) {}

    static BlockEntity host(ServerPlayer player, BlockPos pos, UUID body, boolean display) {
        if (body == null) return null;
        var sub = SubLevelContainer.getContainer(player.level()).getSubLevel(body);
        if (sub == null || sub.isRemoved() || !sub.getPlot().contains(pos.getCenter())
                || !sub.getLevel().hasChunkAt(pos)) return null;
        BlockEntity host = sub.getLevel().getBlockEntity(pos);
        if (!(display ? host instanceof ConnectedDisplayBlockEntity
                : host instanceof StabilizerControllerBlockEntity)
                || !C2SWriteAuthorization.canEditNearby(player, host)) return null;
        var actual = Sable.HELPER.getContaining(host);
        return actual != null && body.equals(actual.getUniqueId()) ? host : null;
    }

    static List<Device> devices(BlockEntity host) {
        if (host instanceof StabilizerControllerBlockEntity controller)
            return controller.getBoundDevices().stream().map(d -> new Device(d.slot(), d.ref())).toList();
        if (host instanceof ConnectedDisplayBlockEntity display)
            return display.getDevices().stream().map(d -> new Device(d.slot(), d.ref())).toList();
        return List.of();
    }

    static Device bound(BlockEntity host, String role, BlockPos pos, UUID body) {
        return devices(host).stream().filter(d -> d.slot().name().equals(role)
                && d.ref().pos().equals(pos) && Objects.equals(d.ref().subLevelId(), body)
                && d.ref().isSameTarget(host.getLevel())).findFirst().orElse(null);
    }
}
