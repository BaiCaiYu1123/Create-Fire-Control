package com.hooya.stabilizedturret.mixin.client;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Ignores exact duplicate Registrate client-extension callbacks produced by
 * Azimuth 1.4.7's {@code RegistrateOTERFix}.
 *
 * <p>Azimuth can mirror the same one-time listener onto a mod event bus twice.
 * NeoForge intentionally rejects the second registration, which makes Create
 * 6.0.10 crash first on its cardboard armor when Aeronautics 1.3.2 changes the
 * Registrate initialization order. Only an identical target list paired with
 * the same extension implementation is suppressed. A different extension
 * competing for an already-seen target still reaches NeoForge and keeps its
 * normal conflict error.</p>
 */
@Mixin(value = RegisterClientExtensionsEvent.class, remap = false)
public abstract class DuplicateClientExtensionsMixin {
    @Unique
    private final Map<Object, Class<?>> createFireControl$blockExtensions =
            new IdentityHashMap<>();
    @Unique
    private final Map<Object, Class<?>> createFireControl$itemExtensions =
            new IdentityHashMap<>();
    @Unique
    private final Map<Object, Class<?>> createFireControl$mobEffectExtensions =
            new IdentityHashMap<>();
    @Unique
    private final Map<Object, Class<?>> createFireControl$fluidTypeExtensions =
            new IdentityHashMap<>();

    @Inject(
            method = "registerBlock(Lnet/neoforged/neoforge/client/extensions/common/IClientBlockExtensions;[Lnet/minecraft/world/level/block/Block;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void createFireControl$ignoreRepeatedBlockExtensions(
            IClientBlockExtensions extension,
            Block[] blocks,
            CallbackInfo ci
    ) {
        if (createFireControl$isExactRepeat(
                createFireControl$blockExtensions, extension, blocks)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "registerItem(Lnet/neoforged/neoforge/client/extensions/common/IClientItemExtensions;[Lnet/minecraft/world/item/Item;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void createFireControl$ignoreRepeatedItemExtensions(
            IClientItemExtensions extension,
            Item[] items,
            CallbackInfo ci
    ) {
        if (createFireControl$isExactRepeat(
                createFireControl$itemExtensions, extension, items)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "registerMobEffect(Lnet/neoforged/neoforge/client/extensions/common/IClientMobEffectExtensions;[Lnet/minecraft/world/effect/MobEffect;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void createFireControl$ignoreRepeatedMobEffectExtensions(
            IClientMobEffectExtensions extension,
            MobEffect[] effects,
            CallbackInfo ci
    ) {
        if (createFireControl$isExactRepeat(
                createFireControl$mobEffectExtensions, extension, effects)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "registerFluidType(Lnet/neoforged/neoforge/client/extensions/common/IClientFluidTypeExtensions;[Lnet/neoforged/neoforge/fluids/FluidType;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void createFireControl$ignoreRepeatedFluidTypeExtensions(
            IClientFluidTypeExtensions extension,
            FluidType[] fluidTypes,
            CallbackInfo ci
    ) {
        if (createFireControl$isExactRepeat(
                createFireControl$fluidTypeExtensions, extension, fluidTypes)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean createFireControl$isExactRepeat(
            Map<Object, Class<?>> seen,
            Object extension,
            Object[] targets
    ) {
        if (extension == null || targets == null || targets.length == 0) {
            return false;
        }
        Class<?> implementation = extension.getClass();
        boolean allSeen = true;
        for (Object target : targets) {
            Class<?> previous = seen.get(target);
            if (previous == null) {
                allSeen = false;
            } else if (previous != implementation) {
                return false;
            }
        }
        if (allSeen) {
            return true;
        }
        for (Object target : targets) {
            if (target != null) {
                seen.putIfAbsent(target, implementation);
            }
        }
        return false;
    }
}
