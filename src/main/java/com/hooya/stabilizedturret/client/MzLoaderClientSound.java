package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.loader.MzLoaderBlockEntity;
import com.hooya.stabilizedturret.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Keeps client-only sound classes out of the common loader block-entity class. */
@OnlyIn(Dist.CLIENT)
public final class MzLoaderClientSound {
    public static void play(MzLoaderBlockEntity loader) {
        if (loader == null || loader.getLevel() == null) return;
        BlockPos pos = loader.getBlockPos();
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                ModSounds.MZ_LOADER.get(),
                SoundSource.BLOCKS,
                1.0F,
                1.0F,
                SoundInstance.createUnseededRandom(),
                pos
        ));
    }

    private MzLoaderClientSound() {
    }
}
