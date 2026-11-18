package com.hooya.stabilizedturret.compat.schematic;

import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.remote.*;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import dev.rew1nd.sableschematicapi.api.blueprint.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** Restores native connections once Photomancy has finished registering all placed plots. */
final class ControllerConnectionBlueprintEvent implements SableBlueprintEvent {
    @Override public ResourceLocation id() {
        return ResourceLocation.fromNamespaceAndPath("create_fire_control", "keyboard_connections");
    }

    @Override public void onSaveAfterBlocks(BlueprintSaveSession session, CompoundTag extraData) {
        ListTag connections = new ListTag();
        for (var block : session.savedBlocks().blocks()) {
            var owner = block.blockEntity();
            BindingRef binding = null;
            if (owner instanceof StabilizerControllerBlockEntity computer)
                binding = computer.getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB);
            else if (owner instanceof GasTurbineBlockEntity engine
                    && engine.getTerminalType() == RemoteControllerCompat.Type.WIRELESS)
                binding = engine.getAdvancedHub();
            else if (owner instanceof WirelessLecternControllerBlockEntity mounted) {
                CompoundTag settings = ControllerSettings.data(mounted.controller());
                var terminal = ControllerSessions.apiResolveTerminal(session.level(), settings);
                if (terminal.isPresent()) binding = BindingRef.capture(session.level(), terminal.get());
            }
            if (binding == null || !(binding.resolve(session.level()) instanceof WirelessSignalProcessorBlockEntity)) continue;
            var terminal = session.blockRef(binding.pos());
            if (terminal.isEmpty()) continue; // A controller outside the saved vehicle is not portable.
            CompoundTag entry = new CompoundTag();
            entry.put("Owner", saveRef(block.ref())); entry.put("Terminal", saveRef(terminal.get()));
            connections.add(entry);
        }
        if (!connections.isEmpty()) extraData.put("Connections", connections);
    }

    @Override public void onPlaceAfterBlocks(BlueprintPlaceSession session, CompoundTag extraData) {
        ListTag connections = extraData.getList("Connections", Tag.TAG_COMPOUND);
        for (int index = 0; index < connections.size(); index++) {
            CompoundTag entry = connections.getCompound(index);
            BlockPos ownerPos = mapRef(session, entry.getCompound("Owner"));
            BlockPos terminalPos = mapRef(session, entry.getCompound("Terminal"));
            if (ownerPos == null || terminalPos == null
                    || !session.level().hasChunkAt(ownerPos) || !session.level().hasChunkAt(terminalPos)
                    || !(session.level().getBlockEntity(terminalPos) instanceof WirelessSignalProcessorBlockEntity terminal)) continue;
            BindingRef binding = BindingRef.capture(session.level(), terminalPos);
            var owner = session.level().getBlockEntity(ownerPos);
            if (owner instanceof StabilizerControllerBlockEntity computer) {
                if (!RemoteTerminalDetector.ownsTerminal(computer, session.level(), terminalPos, RemoteControllerCompat.Type.WIRELESS))
                    computer.bind(BindingSlot.WIRELESS_CONTROLLER_HUB, binding);
            } else if (owner instanceof GasTurbineBlockEntity engine) {
                if (!engine.hasValidAdvancedHub() || !engine.getAdvancedHub().pos().equals(terminalPos))
                    engine.bindAdvancedHub(binding);
            } else if (owner instanceof WirelessLecternControllerBlockEntity mounted) {
                CompoundTag settings = ControllerSettings.data(mounted.controller());
                if (!ControllerSessions.apiResolveTerminal(session.level(), settings).filter(terminalPos::equals).isPresent()) {
                    settings.put("Hub", binding.save()); settings.putUUID("Processor", terminal.identity());
                    mounted.updateControllerData(settings);
                }
            }
        }
    }

    private static CompoundTag saveRef(BlueprintBlockRef ref) {
        CompoundTag tag = new CompoundTag(); tag.putInt("SubLevelId", ref.subLevelId());
        tag.putLong("LocalPos", ref.localPos().asLong()); return tag;
    }
    private static BlockPos mapRef(BlueprintPlaceSession session, CompoundTag tag) {
        if (!tag.contains("SubLevelId", Tag.TAG_ANY_NUMERIC) || !tag.contains("LocalPos", Tag.TAG_LONG)) return null;
        return session.mapBlock(new BlueprintBlockRef(tag.getInt("SubLevelId"), BlockPos.of(tag.getLong("LocalPos"))));
    }
}
