package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.client.VirtualScopeVisibility;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Skip every solid/translucent layer and block-entity pass on all Sable backends. */
@Mixin(targets = {
        "dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher",
        "dev.ryanhcode.sable.sublevel.render.dispatcher.FancySubLevelRenderDispatcher"
}, remap = false)
public abstract class VirtualScopeSubLevelRenderMixin {
    @ModifyVariable(method = {"renderSectionLayer", "renderAfterSections", "renderBlockEntities"},
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Iterable<ClientSubLevel> createFireControl$visibleTurrets(Iterable<ClientSubLevel> bodies) {
        return VirtualScopeVisibility.visibleBodies(bodies);
    }
}
