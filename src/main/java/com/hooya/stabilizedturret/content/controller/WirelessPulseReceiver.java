package com.hooya.stabilizedturret.content.controller;

import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A receiver endpoint embedded in the controller block entity. */
final class WirelessPulseReceiver implements IRedstoneLinkable {
    private final StabilizerControllerBlockEntity owner;
    private final Consumer<Boolean> risingEdgeCallback;
    private Frequency first = Frequency.EMPTY;
    private Frequency second = Frequency.EMPTY;
    private int receivedStrength;

    WirelessPulseReceiver(StabilizerControllerBlockEntity owner, Consumer<Boolean> risingEdgeCallback) {
        this.owner = owner;
        this.risingEdgeCallback = risingEdgeCallback;
    }

    @Override
    public int getTransmittedStrength() {
        return 0;
    }

    @Override
    public void setReceivedStrength(int strength) {
        if (!hasConfiguredFrequency()) {
            receivedStrength = 0;
            return;
        }
        boolean rising = receivedStrength == 0 && strength > 0;
        receivedStrength = strength;
        if (rising) {
            risingEdgeCallback.accept(Boolean.TRUE);
        }
    }

    @Override
    public boolean isListening() {
        return hasConfiguredFrequency();
    }

    @Override
    public boolean isAlive() {
        return owner.getLevel() != null
                && !owner.isRemoved()
                && owner.getLevel().isLoaded(owner.getBlockPos())
                && owner.getLevel().getBlockEntity(owner.getBlockPos()) == owner;
    }

    @Override
    public Couple<Frequency> getNetworkKey() {
        return Couple.create(first, second);
    }

    @Override
    public BlockPos getLocation() {
        return owner.getBlockPos();
    }

    void setFrequency(boolean setFirst, ItemStack stack) {
        ItemStack normalized = stack.copy();
        normalized.setCount(normalized.isEmpty() ? 0 : 1);
        if (setFirst) {
            first = Frequency.of(normalized);
        } else {
            second = Frequency.of(normalized);
        }
        // A changed channel must observe the next powered packet as a new
        // rising edge. More importantly, EMPTY + EMPTY is never a valid
        // receiver channel and cannot be triggered by an unconfigured remote.
        receivedStrength = 0;
    }

    boolean hasConfiguredFrequency() {
        return !first.getStack().isEmpty() || !second.getStack().isEmpty();
    }

    ItemStack getFrequency(boolean getFirst) {
        return (getFirst ? first : second).getStack();
    }

    void save(CompoundTag ownerTag, String key, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("First", first.getStack().saveOptional(registries));
        tag.put("Second", second.getStack().saveOptional(registries));
        ownerTag.put(key, tag);
    }

    void load(CompoundTag ownerTag, String key, HolderLookup.Provider registries) {
        if (!ownerTag.contains(key)) {
            return;
        }
        CompoundTag tag = ownerTag.getCompound(key);
        first = Frequency.of(ItemStack.parseOptional(registries, tag.getCompound("First")));
        second = Frequency.of(ItemStack.parseOptional(registries, tag.getCompound("Second")));
    }
}
