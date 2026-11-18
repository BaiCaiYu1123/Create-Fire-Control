package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.loader.AzLoaderBlockEntity;
import com.hooya.stabilizedturret.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Plays the AZ loading cycle as a positional block sound. */
@OnlyIn(Dist.CLIENT)
public final class AzLoaderClientSound {
    public static void play(AzLoaderBlockEntity loader) {
        if (loader == null || loader.getLevel() == null) return;
        BlockPos pos = loader.getBlockPos();
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                ModSounds.AZ_LOADER.get(),
                SoundSource.BLOCKS,
                1.0F,
                1.0F,
                SoundInstance.createUnseededRandom(),
                pos
        ));
    }

    private AzLoaderClientSound() {
    }
}
