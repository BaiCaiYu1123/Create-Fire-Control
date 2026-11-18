package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** A small terminal that forwards every operation to one connected display. */
public final class ConnectedDisplayConsoleBlockEntity extends BlockEntity {
    private static final long CONTROL_LEASE_TICKS = 60L;
    private @Nullable BindingRef displayRef;
    private @Nullable UUID controllingPlayer;
    private long controlHeartbeat = Long.MIN_VALUE;

    public ConnectedDisplayConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONNECTED_DISPLAY_CONSOLE.get(), pos, state);
    }

    public void bind(ConnectedDisplayBlockEntity display) {
        if (display == null || display.getLevel() == null) return;
        displayRef = BindingRef.capture(display.getLevel(), display.getBlockPos());
        sync();
    }

    public @Nullable ConnectedDisplayBlockEntity resolveDisplay() {
        if (displayRef == null || level == null) return null;
        BlockEntity resolved = displayRef.resolve(level);
        return resolved instanceof ConnectedDisplayBlockEntity display ? display : null;
    }

    public @Nullable BindingRef getDisplayRef() {
        return displayRef;
    }

    public boolean isBound() {
        return displayRef != null;
    }

    public boolean tryAcquireControl(ServerPlayer player) {
        if (player == null || level == null) return false;
        long now = level.getGameTime();
        boolean ownerOnline = controllingPlayer != null && level.getServer() != null
                && level.getServer().getPlayerList().getPlayer(controllingPlayer) != null;
        if (controllingPlayer == null || controllingPlayer.equals(player.getUUID())
                || !ownerOnline || now - controlHeartbeat > CONTROL_LEASE_TICKS) {
            controllingPlayer = player.getUUID();
            controlHeartbeat = now;
            return true;
        }
        return false;
    }

    public boolean refreshControl(ServerPlayer player) {
        if (player == null || level == null
                || !player.getUUID().equals(controllingPlayer)) return false;
        controlHeartbeat = level.getGameTime();
        return true;
    }

    public void releaseControl(ServerPlayer player) {
        if (player != null && player.getUUID().equals(controllingPlayer)) {
            controllingPlayer = null;
            controlHeartbeat = Long.MIN_VALUE;
        }
    }

    private void sync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (displayRef != null) tag.put("Display", displayRef.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        displayRef = BindingRef.load(tag, "Display");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        if (packet.getTag() != null) loadAdditional(packet.getTag(), registries);
    }
}
