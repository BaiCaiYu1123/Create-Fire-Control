package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.remote.*;
import edn.stratodonut.drivebywire.wire.MultiChannelWireSource;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(WirelessSignalProcessorBlock.class)
public abstract class WirelessProcessorChannelsMixin implements MultiChannelWireSource {
    @Override public List<String> wire$getChannels() { return ControllerInput.CHANNELS; }
    @Override public String wire$nextChannel(String current, boolean forward) {
        int index = ControllerInput.CHANNELS.indexOf(current);
        return ControllerInput.CHANNELS.get(index < 0 ? 0 : Math.floorMod(index + (forward ? 1 : -1),
                ControllerInput.CHANNELS.size()));
    }
}
