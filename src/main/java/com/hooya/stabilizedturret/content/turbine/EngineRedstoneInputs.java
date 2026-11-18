package com.hooya.stabilizedturret.content.turbine;

import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** Receivers belong to the engine, so channels work without a remote terminal or wire mod. */
final class EngineRedstoneInputs {
    private final GasTurbineBlockEntity owner;
    private final List<Receiver> receivers = new ArrayList<>();
    private boolean dirty = true;
    EngineRedstoneInputs(GasTurbineBlockEntity owner) { this.owner = owner; }
    void invalidate() { close(); }
    void tick() {
        if (owner.getLevel() == null || owner.getLevel().isClientSide || !owner.isControlPart()) return;
        if (!dirty) {
            // Refresh ranges as physics vehicles move, including a source that was already powered on load.
            if (owner.getLevel().getGameTime() % 10 == 0)
                for (var receiver : receivers) if (receiver.channel.configured())
                    Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(owner.getLevel(), receiver);
            return;
        }
        dirty = false;
        add(owner.getControlSettings().toggleChannel());
        for (var behavior : owner.getControlSettings().behaviors()) add(behavior.redstoneChannel());
    }
    private void add(EngineRedstoneChannel channel) {
        var receiver = new Receiver(channel);
        receivers.add(receiver);
        if (channel.configured()) Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(owner.getLevel(), receiver);
    }
    boolean powered(int index) { return !dirty && index < receivers.size() && receivers.get(index).strength > 0; }
    void close() {
        if (owner.getLevel() != null && !owner.getLevel().isClientSide)
            for (var receiver : receivers) if (receiver.channel.configured())
                Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(owner.getLevel(), receiver);
        receivers.clear(); dirty = true;
    }
    private final class Receiver extends LinkBehaviour {
        private final EngineRedstoneChannel channel;
        private final Couple<Frequency> frequency;
        private int strength;
        Receiver(EngineRedstoneChannel channel) {
            // Channels are edited in the engine screen; these embedded endpoints have no world-space slots.
            super(owner, org.apache.commons.lang3.tuple.Pair.of(null, null));
            this.channel = channel;
            frequency = Couple.create(Frequency.of(channel.first()), Frequency.of(channel.second()));
        }
        @Override public int getTransmittedStrength() { return 0; }
        @Override public void setReceivedStrength(int value) { strength = channel.configured() ? value : 0; }
        @Override public boolean isListening() { return channel.configured(); }
        @Override public boolean isAlive() {
            return !owner.isRemoved() && owner.getLevel() != null && owner.getLevel().hasChunkAt(owner.getBlockPos())
                    && owner.getLevel().getBlockEntity(owner.getBlockPos()) == owner;
        }
        @Override public Couple<Frequency> getNetworkKey() { return frequency; }
        @Override public BlockPos getLocation() { return owner.getBlockPos(); }
    }
}
