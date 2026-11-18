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
        ),
        WIRELESS(ResourceLocation.fromNamespaceAndPath("create_fire_control", "wireless_controller"),
                ResourceLocation.fromNamespaceAndPath("create_fire_control", "wireless_signal_processor"));

        private final ResourceLocation itemId;
        private final ResourceLocation terminalId;

        Type(ResourceLocation itemId, ResourceLocation terminalId) {
            this.itemId = itemId;
            this.terminalId = terminalId;
        }
    }

    public static Optional<BlockPos> findHeldBoundHub(Player player, Type type) {
        Optional<BoundHub> main = readBoundHub(player.getMainHandItem(), type);
        Optional<BoundHub> selected = main.isPresent()
                ? main : readBoundHub(player.getOffhandItem(), type);
        ResourceLocation currentDimension = player.level().dimension().location();
        return selected.filter(hub -> hub.dimension == null
                        || hub.dimension.equals(currentDimension))
                .map(BoundHub::pos);
    }

    public static boolean isHoldingBoundController(Player player, Type type, BlockPos hubPos) {
        return findHeldBoundHub(player, type).filter(hubPos::equals).isPresent();
    }

    /** Persist this mod's dimension sidecar after the server has verified the hub. */
    public static void rememberCurrentDimension(Player player, Type type, BlockPos hubPos) {
        if (type == Type.WIRELESS) return;
        rememberCurrentDimension(player.getMainHandItem(), type, hubPos, player.level().dimension().location());
        rememberCurrentDimension(player.getOffhandItem(), type, hubPos, player.level().dimension().location());
    }

    public static boolean isTerminal(Level level, BlockPos pos, Type type) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return type.terminalId.equals(id);
    }

    public static boolean isAnyTerminal(Level level, BlockPos pos) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return Type.NORMAL.terminalId.equals(id) || Type.ADVANCED.terminalId.equals(id) || Type.WIRELESS.terminalId.equals(id);
    }

    private static Optional<BoundHub> readBoundHub(ItemStack stack, Type type) {
        if (stack.isEmpty() || !type.itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
            return Optional.empty();
        }
        if (type == Type.WIRELESS) {
            CompoundTag data = com.hooya.stabilizedturret.content.remote.ControllerSettings.data(stack);
            if (!data.contains("Hub")) return Optional.empty();
            BindingRef ref = BindingRef.loadTag(data.getCompound("Hub"));
            return Optional.of(new BoundHub(ref.pos(), null));
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        BlockPos pos;
        if (tag.contains("DriveByWireHub", CompoundTag.TAG_LONG)) {
            pos = BlockPos.of(tag.getLong("DriveByWireHub"));
        } else if (tag.contains("Hub", CompoundTag.TAG_LONG)) {
            pos = BlockPos.of(tag.getLong("Hub"));
        } else return Optional.empty();
        // Newer controller implementations may persist the hub's dimension.
        // Honour the common spellings when present.  Legacy items have no such
        // field and deliberately remain valid in the player's current world.
        ResourceLocation dimension = firstDimension(tag,
                "DriveByWireHubDimension", "HubDimension", "Dimension");
        if (dimension == null && tag.contains("StabilizedTurretHub", CompoundTag.TAG_LONG)
                && tag.getLong("StabilizedTurretHub") == pos.asLong()) {
            dimension = firstDimension(tag, "StabilizedTurretHubDimension");
        }
        return Optional.of(new BoundHub(pos, dimension));
    }

    private static void rememberCurrentDimension(
            ItemStack stack, Type type, BlockPos hubPos, ResourceLocation dimension
    ) {
        Optional<BoundHub> bound = readBoundHub(stack, type);
        if (bound.isEmpty() || !bound.get().pos.equals(hubPos)) return;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putLong("StabilizedTurretHub", hubPos.asLong());
            tag.putString("StabilizedTurretHubDimension", dimension.toString());
        });
    }

    private static ResourceLocation firstDimension(CompoundTag tag, String... keys) {
        for (String key : keys) {
            if (tag.contains(key, CompoundTag.TAG_STRING)) {
                ResourceLocation value = ResourceLocation.tryParse(tag.getString(key));
                if (value != null) return value;
            }
        }
        return null;
    }

    private record BoundHub(BlockPos pos, ResourceLocation dimension) {
    }

    private RemoteControllerCompat() {
    }
}
