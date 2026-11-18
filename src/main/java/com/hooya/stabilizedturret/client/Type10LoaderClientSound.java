package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.loader.Type10LoaderBlockEntity;
import com.hooya.stabilizedturret.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Plays the Type 10 loading cycle as a positional block sound. */
@OnlyIn(Dist.CLIENT)
public final class Type10LoaderClientSound {
    public static void play(Type10LoaderBlockEntity loader) {
        if (loader == null || loader.getLevel() == null) return;
        BlockPos pos = loader.getBlockPos();
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                ModSounds.TYPE_10_LOADER.get(),
                SoundSource.BLOCKS,
                1.0F,
                1.0F,
                SoundInstance.createUnseededRandom(),
                pos
        ));
    }

    private Type10LoaderClientSound() {
    }
}
