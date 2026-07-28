package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.ClientAimState;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.stream.StreamSupport;

/**
 * Sable 1.x uses a separate Sodium dispatcher whenever Embeddium/Sodium is
 * active. The vanilla dispatcher mixin is never reached in that mode.
 * Sable 2.x removed this class, so the pseudo target keeps the same build
 * usable with both renderer generations.
 */
@Pseudo
@Mixin(
        targets = "dev.ryanhcode.sable.sublevel.render.dispatcher.SodiumSubLevelRenderDispatcher",
        remap = false
)
public abstract class SodiumSubLevelScopeVisibilityMixin {
    @ModifyVariable(
            method = {"renderSectionLayer", "renderAfterSections", "renderBlockEntities"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
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
