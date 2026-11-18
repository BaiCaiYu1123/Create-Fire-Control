package com.hooya.stabilizedturret.content.iff;

import com.hooya.stabilizedturret.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Stores the case-sensitive IFF channel configured on one transponder. */
public final class IffTransponderBlockEntity extends BlockEntity {
    public static final int MAX_CHANNEL_LENGTH = 9;
    private static final Set<IffTransponderBlockEntity> LOADED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private String channel = "";
    private int friendlyTransponderCount;

    public IffTransponderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.IFF_TRANSPONDER.get(), pos, state);
    }

    public String getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return !channel.isEmpty();
    }

    public int getFriendlyTransponderCount() {
        return friendlyTransponderCount;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LOADED.add(this);
            refreshLoadedTranspondersInWorld();
        }
    }

    @Override
    public void setRemoved() {
        LOADED.remove(this);
        refreshLoadedTranspondersInWorld();
        super.setRemoved();
    }

    public @Nullable UUID subLevelId() {
        SubLevel containing = Sable.HELPER.getContaining(this);
        return containing == null ? null : containing.getUniqueId();
    }

    public static @Nullable IffTransponderBlockEntity findLoaded(
            ServerPlayer player, BlockPos pos, @Nullable UUID subLevelId) {
        if (player == null || pos == null) return null;
        for (IffTransponderBlockEntity transponder : Set.copyOf(LOADED)) {
            if (transponder == null || transponder.isRemoved()
                    || transponder.getLevel() == null
                    || transponder.getLevel().getServer() != player.getServer()
                    || !transponder.getLevel().dimension().equals(player.level().dimension())
                    || !transponder.getBlockPos().equals(pos)
                    || !java.util.Objects.equals(subLevelId, transponder.subLevelId())) {
                continue;
            }
            return transponder;
        }
        return null;
    }

    public static void serverTick(
            net.minecraft.world.level.Level level,
            BlockPos pos,
            BlockState state,
            IffTransponderBlockEntity transponder) {
        if (level.isClientSide
                || Math.floorMod(level.getGameTime() + pos.asLong(), 10L) != 0L) {
            return;
        }
        transponder.refreshDetectionStatus();
    }

    public void setChannel(String value) {
        if (!isValidChannel(value) || channel.equals(value)) return;
        channel = value;
        friendlyTransponderCount = 0;
        setChanged();
        refreshLoadedTranspondersInWorld();
        if (level != null) {
            BlockState current = getBlockState();
            level.sendBlockUpdated(
                    worldPosition, current, current, Block.UPDATE_CLIENTS);
        }
    }

    public static boolean isValidChannel(String value) {
        if (value == null || value.length() > MAX_CHANNEL_LENGTH) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("Channel", channel);
        tag.putInt("FriendlyTransponderCount", friendlyTransponderCount);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        String saved = tag.getString("Channel");
        channel = isValidChannel(saved) ? saved : "";
        friendlyTransponderCount = Math.max(
                0, tag.getInt("FriendlyTransponderCount"));
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
    public void onDataPacket(
            Connection connection,
            ClientboundBlockEntityDataPacket packet,
            HolderLookup.Provider registries
    ) {
        if (packet.getTag() != null) loadAdditional(packet.getTag(), registries);
    }

    private void refreshDetectionStatus() {
        if (level == null || level.isClientSide) return;
        int detected = isEnabled() ? countMatchingLoadedTransponders() : 0;
        IffTransponderBlock.Indicator indicator = !isEnabled()
                ? IffTransponderBlock.Indicator.OFF
                : detected > 0
                ? IffTransponderBlock.Indicator.GREEN
                : IffTransponderBlock.Indicator.RED;
        BlockState oldState = getBlockState();
        BlockState newState = oldState.hasProperty(IffTransponderBlock.INDICATOR)
                ? oldState.setValue(IffTransponderBlock.INDICATOR, indicator)
                : oldState;
        boolean countChanged = friendlyTransponderCount != detected;
        friendlyTransponderCount = detected;
        if (!newState.equals(oldState)) {
            level.setBlock(worldPosition, newState, Block.UPDATE_CLIENTS);
        }
        if (countChanged || !newState.equals(oldState)) {
            setChanged();
            level.sendBlockUpdated(
                    worldPosition, oldState, newState, Block.UPDATE_CLIENTS);
        }
    }

    private int countMatchingLoadedTransponders() {
        if (level == null || channel.isEmpty()) return 0;
        int count = 0;
        for (IffTransponderBlockEntity other : Set.copyOf(LOADED)) {
            if (other == null || other == this || other.isRemoved()
                    || other.level == null
                    || other.level.getServer() != level.getServer()
                    || !other.level.dimension().equals(level.dimension())
                    || !channel.equals(other.channel)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private void refreshLoadedTranspondersInWorld() {
        if (level == null || level.isClientSide) return;
        for (IffTransponderBlockEntity transponder : Set.copyOf(LOADED)) {
            if (transponder == null || transponder.isRemoved()
                    || transponder.level == null
                    || transponder.level.getServer() != level.getServer()
                    || !transponder.level.dimension().equals(level.dimension())) {
                continue;
            }
            transponder.refreshDetectionStatus();
        }
    }
}
