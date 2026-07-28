package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.stream.StreamSupport;

/** Removes the cannon's physical child structure from every Sable render pass while scoped. */
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class SubLevelScopeVisibilityMixin {
    @ModifyVariable(
            method = {"renderSectionLayer", "renderAfterSections", "renderBlockEntities"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> stabilizedTurret$hideScopedCannonSubLevel(
            Iterable<ClientSubLevel> subLevels
    ) {
        if (!ClientAimState.isScoped()) {
            return subLevels;
        }
        return StreamSupport.stream(subLevels.spliterator(), false)
                .filter(subLevel -> !ClientAimState.shouldHideScopedSubLevel(subLevel))
                .toList();
    }
}
