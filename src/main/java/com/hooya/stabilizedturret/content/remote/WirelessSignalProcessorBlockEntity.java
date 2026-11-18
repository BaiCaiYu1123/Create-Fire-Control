package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.registry.ModBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;
import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.world.level.Level;

/** Persistent identity prevents a replacement block from inheriting a controller pairing. */
public final class WirelessSignalProcessorBlockEntity extends BlockEntity implements com.simibubi.create.api.equipment.goggles.IHaveHoveringInformation {
    private UUID identity = UUID.randomUUID();
    public static final String PORTABLE_WIRES = "create_fire_control:processor_wires";
    private ListTag pendingWires = new ListTag();
    private int wireRestoreCooldown;
    private static final Map<UUID,List<WeakReference<WirelessSignalProcessorBlockEntity>>> LOADED = new HashMap<>();
    public WirelessSignalProcessorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WIRELESS_SIGNAL_PROCESSOR.get(), pos, state);
    }
    public UUID identity() { return identity; }
    @Override public boolean addToTooltip(List<net.minecraft.network.chat.Component> tooltip,boolean sneaking) {
        tooltip.add(net.minecraft.network.chat.Component.translatable("block.create_fire_control.wireless_signal_processor"));
        tooltip.add(net.minecraft.network.chat.Component.translatable("remote.create_fire_control.processor_connect_hint").withStyle(net.minecraft.ChatFormatting.GRAY));
        if(level!=null) {
            Set<String> channels=new HashSet<>(com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity.channelsForTerminal(level,worldPosition));
            var controller=com.hooya.stabilizedturret.content.controller.ControllerTracker.findForRemoteTerminal(level,worldPosition,
                    com.hooya.stabilizedturret.content.controller.RemoteControllerCompat.Type.WIRELESS);
            if(controller!=null)for(String key:controller.getRemoteKeys(com.hooya.stabilizedturret.content.controller.RemoteControllerCompat.Type.WIRELESS))channels.addAll(ControllerInput.chordInputs(key));
            tooltip.add(net.minecraft.network.chat.Component.translatable("remote.create_fire_control.processor_keys",channels.size()).withStyle(net.minecraft.ChatFormatting.GOLD));
        }
        return true;
    }
    @Override public void onLoad() {
        super.onLoad();
        registerLoaded();
    }
    private void registerLoaded() {
        if(level==null||level.isClientSide||isRemoved())return;
        var entries=LOADED.computeIfAbsent(identity,k->new ArrayList<>());
        entries.removeIf(ref->ref.get()==null||ref.get().isRemoved());
        if(entries.stream().noneMatch(ref->ref.get()==this))entries.add(new WeakReference<>(this));
    }
    public static WirelessSignalProcessorBlockEntity findLoaded(Level world, UUID id) {
        List<WeakReference<WirelessSignalProcessorBlockEntity>> entries=LOADED.get(id);
        if(entries==null)return null;
        entries.removeIf(ref->ref.get()==null||ref.get().isRemoved());
        WirelessSignalProcessorBlockEntity found=null;
        Set<WirelessSignalProcessorBlockEntity> unique=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var ref:entries) {
            var processor=ref.get();
            if(processor==null||processor.level!=world||!world.hasChunkAt(processor.worldPosition)
                    ||world.getBlockEntity(processor.worldPosition)!=processor||!unique.add(processor))continue;
            if(found!=null)return null; // Blueprint copies with the same id cannot silently steal a pairing.
            found=processor;
        }
        return found;
    }
    static void clearDirectory() { LOADED.clear(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("ProcessorIdentity", identity);
        if (!pendingWires.isEmpty()) tag.put(PORTABLE_WIRES, pendingWires.copy());
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pendingWires = tag.getList(PORTABLE_WIRES, Tag.TAG_COMPOUND).copy();
        wireRestoreCooldown = 0;
        if (tag.hasUUID("ProcessorIdentity") && !identity.equals(tag.getUUID("ProcessorIdentity"))) {
            // Blueprint/loadWithComponents may restore the identity after onLoad.
            if(level!=null&&!level.isClientSide) {
                var entries=LOADED.get(identity);
                if(entries!=null)entries.removeIf(ref->ref.get()==null||ref.get()==this);
            }
            identity = tag.getUUID("ProcessorIdentity");
            if(level!=null&&!level.isClientSide&&level.hasChunkAt(worldPosition)
                    &&level.getBlockEntity(worldPosition)==this)registerLoaded();
        }
    }
    public static void serverTick(Level level, BlockPos pos, BlockState state, WirelessSignalProcessorBlockEntity processor) {
        if (processor.pendingWires.isEmpty() || !ControllerWireBridge.available()) return;
        if (processor.wireRestoreCooldown-- > 0) return;
        processor.wireRestoreCooldown = 20;
        int before = processor.pendingWires.size();
        processor.pendingWires = ControllerWireBridge.restore(level, pos, processor.pendingWires);
        if (before != processor.pendingWires.size()) {
            processor.setChanged();
            ControllerSessions.mappingsChanged(level, pos);
        }
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
