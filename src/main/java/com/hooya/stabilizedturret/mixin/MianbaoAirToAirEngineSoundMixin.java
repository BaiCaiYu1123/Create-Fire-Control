package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.content.controller.AirToAirMissileFlight;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Adopted AAM engine audio is one client loop; native smoke and unrelated weapons stay native. */
@Pseudo
@Mixin(targets = "net.mcreator.myfirstmod.procedures.RocketmissiletrailsmokeProcedure", remap = false)
public abstract class MianbaoAirToAirEngineSoundMixin {
    @WrapWithCondition(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"), require = 0)
    private static boolean createFireControl$oneEngineLoop(Level level, Player player, BlockPos pos,
            SoundEvent sound, SoundSource source, float volume, float pitch,
            LevelAccessor world, double x, double y, double z, Entity missile) {
        return !AirToAirMissileFlight.managed(missile);
    }

    @WrapWithCondition(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"), require = 0)
    private static boolean createFireControl$oneLocalEngineLoop(Level level, double px, double py, double pz,
            SoundEvent sound, SoundSource source, float volume, float pitch, boolean delayed,
            LevelAccessor world, double x, double y, double z, Entity missile) {
        return !AirToAirMissileFlight.managed(missile);
    }
}
