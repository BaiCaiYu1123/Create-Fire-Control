package com.hooya.stabilizedturret.content.turbine;

import net.minecraft.world.item.ItemStack;

/** Create's ordered pair of frequency items; empty pairs never receive input. */
public record EngineRedstoneChannel(ItemStack first, ItemStack second) {
    public static final EngineRedstoneChannel EMPTY = new EngineRedstoneChannel(ItemStack.EMPTY, ItemStack.EMPTY);
    public EngineRedstoneChannel {
        first = first.copyWithCount(first.isEmpty() ? 0 : 1);
        second = second.copyWithCount(second.isEmpty() ? 0 : 1);
    }
    @Override public ItemStack first() { return first.copy(); }
    @Override public ItemStack second() { return second.copy(); }
    public boolean configured() { return !first.isEmpty() || !second.isEmpty(); }
    @Override public boolean equals(Object other) {
        return other instanceof EngineRedstoneChannel channel
                && ItemStack.isSameItemSameComponents(first, channel.first)
                && ItemStack.isSameItemSameComponents(second, channel.second);
    }
    @Override public int hashCode() {
        return 31 * ItemStack.hashItemAndComponents(first) + ItemStack.hashItemAndComponents(second);
    }
}
