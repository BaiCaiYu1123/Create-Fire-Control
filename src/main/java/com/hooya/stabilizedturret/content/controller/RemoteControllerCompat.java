package com.hooya.stabilizedturret.content.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.Optional;

public final class RemoteControllerCompat {
    public enum Type {
        NORMAL(
                ResourceLocation.fromNamespaceAndPath("create", "linked_controller"),
                ResourceLocation.fromNamespaceAndPath("drivebywire", "controller_hub")
        ),
        ADVANCED(
                ResourceLocation.fromNamespaceAndPath("create_tweaked_controllers", "tweaked_linked_controller"),
                ResourceLocation.fromNamespaceAndPath("drivebywire", "tweaked_controller_hub")
        );

        private final ResourceLocation itemId;
        private final ResourceLocation terminalId;

        Type(ResourceLocation itemId, ResourceLocation terminalId) {
            this.itemId = itemId;
            this.terminalId = terminalId;
        }
    }

    public static Optional<BlockPos> findHeldBoundHub(Player player, Type type) {
        Optional<BlockPos> main = readBoundHub(player.getMainHandItem(), type);
        return main.isPresent() ? main : readBoundHub(player.getOffhandItem(), type);
    }

    public static boolean isHoldingBoundController(Player player, Type type, BlockPos hubPos) {
        return findHeldBoundHub(player, type).filter(hubPos::equals).isPresent();
    }

    public static boolean isTerminal(Level level, BlockPos pos, Type type) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return type.terminalId.equals(id);
    }

    public static boolean isAnyTerminal(Level level, BlockPos pos) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return Type.NORMAL.terminalId.equals(id) || Type.ADVANCED.terminalId.equals(id);
    }

    private static Optional<BlockPos> readBoundHub(ItemStack stack, Type type) {
        if (stack.isEmpty() || !type.itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
            return Optional.empty();
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (tag.contains("DriveByWireHub", CompoundTag.TAG_LONG)) {
            return Optional.of(BlockPos.of(tag.getLong("DriveByWireHub")));
        }
        if (tag.contains("Hub", CompoundTag.TAG_LONG)) {
            return Optional.of(BlockPos.of(tag.getLong("Hub")));
        }
        return Optional.empty();
    }

    private RemoteControllerCompat() {
    }
}
