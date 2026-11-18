package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.compat.mianbao.MianbaoMountedAutocannonContraption;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.render.ClientContraption;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removes animated barrels from Create's static mesh; actor visuals draw them instead. */
@Mixin(ClientContraption.class)
public abstract class MianbaoClientContraptionMixin {
    @Shadow
    @Final
    private Contraption contraption;

    @Inject(method = "getRenderedBlocks", at = @At("RETURN"), cancellable = true)
    private void stabilizedTurret$hideStaticRotaryBarrels(
            CallbackInfoReturnable<ClientContraption.RenderedBlocks> cir
    ) {
        if (!(this.contraption instanceof MianbaoMountedAutocannonContraption cannon)
                || !cannon.hasAnimatedBarrels()) {
            return;
        }
        ClientContraption.RenderedBlocks original = cir.getReturnValue();
        cir.setReturnValue(new ClientContraption.RenderedBlocks(
                pos -> cannon.isBarrelPosition(pos)
                        ? Blocks.AIR.defaultBlockState()
                        : original.lookup().apply(pos),
                original.positions()
        ));
    }
}
