package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.FancySubLevelRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.stream.StreamSupport;

/** Covers Sable's fancy renderer, used by configurations without Sodium's 1.x dispatcher. */
@Mixin(value = FancySubLevelRenderDispatcher.class, remap = false)
public abstract class FancySubLevelScopeVisibilityMixin {
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
