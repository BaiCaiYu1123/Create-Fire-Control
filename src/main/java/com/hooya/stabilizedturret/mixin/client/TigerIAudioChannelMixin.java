package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.TigerISpatialAudio;
import com.mojang.blaze3d.audio.Channel;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.EXTEfx;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.DoubleSupplier;

/** Only Tiger voices opt into this filter. All OpenAL calls run on the sound executor. */
@Mixin(Channel.class)
public abstract class TigerIAudioChannelMixin implements TigerISpatialAudio.FilterChannel {
    @Shadow @Final private int source;
    @Unique private DoubleSupplier tigerI$hf;
    @Unique private volatile int tigerI$filter;
    @Override public void tigerI$highFrequency(DoubleSupplier supplier) { tigerI$hf=supplier; }
    @Override public boolean tigerI$filterActive() { return tigerI$filter!=0; }
    @Inject(method="setVolume",at=@At("TAIL"))
    private void tigerI$filter(float volume,CallbackInfo ci) {
        if(tigerI$hf==null || !AL.getCapabilities().ALC_EXT_EFX)return;
        if(tigerI$filter==0) {
            tigerI$filter=EXTEfx.alGenFilters();
            if(tigerI$filter==0)return;
            EXTEfx.alFilteri(tigerI$filter,EXTEfx.AL_FILTER_TYPE,EXTEfx.AL_FILTER_LOWPASS);
            EXTEfx.alFilterf(tigerI$filter,EXTEfx.AL_LOWPASS_GAIN,1);
        }
        EXTEfx.alFilterf(tigerI$filter,EXTEfx.AL_LOWPASS_GAINHF,(float)tigerI$hf.getAsDouble());
        AL10.alSourcei(source,EXTEfx.AL_DIRECT_FILTER,tigerI$filter);
    }
    @Inject(method="destroy",at=@At("HEAD"))
    private void tigerI$cleanup(CallbackInfo ci) {
        if(tigerI$filter!=0) {
            AL10.alSourcei(source,EXTEfx.AL_DIRECT_FILTER,EXTEfx.AL_FILTER_NULL);
            EXTEfx.alDeleteFilters(tigerI$filter);tigerI$filter=0;
        }
        tigerI$hf=null;
    }
}
