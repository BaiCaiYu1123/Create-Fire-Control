package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Stores the original item, including its identity, channels and terminal pairing. */
public final class WirelessLecternControllerBlockEntity extends BlockEntity {
    private ItemStack controller = ItemStack.EMPTY;
    private UUID user;
    private Set<String> visualPressed = Set.of();
    private boolean animationInitialized, animationUsing;
    private double animationFrom, animationChangedAt;

    public WirelessLecternControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WIRELESS_LECTERN_CONTROLLER.get(), pos, state);
    }
    public ItemStack controller() { return controller; }
    public boolean inUse() { return user != null; }
    public Set<String> visualPressed() { return visualPressed; }
    /** Presentation state is transient; packets containing held keys keep the current transition. */
    public double animationTime(double clock,double length) {
        if (!animationInitialized) {
            animationInitialized = true; animationUsing = inUse();
            animationFrom = length; animationChangedAt = clock;
        }
        double time = Math.clamp(animationFrom + (animationUsing ? -1 : 1) * (clock-animationChangedAt)/20,0,length);
        if (animationUsing != inUse()) {
            animationUsing = inUse(); animationFrom = time; animationChangedAt = clock;
        }
        return time;
    }

    public void setController(ItemStack stack) {
        if (level != null && !level.isClientSide) ControllerSessions.stopLectern(level, worldPosition);
        controller = stack.copyWithCount(1);
        if (!controller.isEmpty()) ControllerSettings.ensureId(controller);
        changed();
    }
    public void updateControllerData(CompoundTag data) {
        ControllerSettings.write(controller, data); changed();
    }
    public void beginUsing(UUID player) {
        user = player; visualPressed = Set.of(); changed();
    }
    public void endUsing(UUID player) {
        if (!Objects.equals(user, player)) return;
        user = null; visualPressed = Set.of(); changed();
    }
    public void updateVisualInputs(Set<String> inputs) {
        if (visualPressed.equals(inputs)) return;
        visualPressed = Set.copyOf(inputs); changed();
    }
    private void changed() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public static boolean playerInRange(Player player, Level level, BlockPos pos) {
        var center = BindingRef.capture(level, pos).worldCenter(level);
        double range = player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) * 0.4;
        return center != null && player.distanceToSqr(center) < range * range;
    }
    @Override public void setRemoved() {
        if (level != null && !level.isClientSide) ControllerSessions.stopLectern(level, worldPosition);
        super.setRemoved();
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Controller", controller.saveOptional(registries));
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        controller = ItemStack.parseOptional(registries, tag.getCompound("Controller"));
        user = tag.hasUUID("User") ? tag.getUUID("User") : null;
        visualPressed = ControllerSessions.readStrings(tag, "VisualPressed");
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        if (user != null) tag.putUUID("User", user);
        tag.put("VisualPressed", ControllerSessions.strings(visualPressed));
        return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
