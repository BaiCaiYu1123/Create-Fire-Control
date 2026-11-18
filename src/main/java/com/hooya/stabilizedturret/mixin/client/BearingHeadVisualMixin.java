package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.bearing.BearingHeadVisibility;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Removes the native Flywheel shaft instance while hidden and rebuilds it when restored. */
@Mixin(value = OrientedRotatingVisual.class, remap = false)
public abstract class BearingHeadVisualMixin {
    @Shadow
    @Final
    @Mutable
    protected RotatingInstance rotatingModel;

    @Unique
    private SwivelBearingPlateBlockEntity stabilizedTurret$bearingPlate;
    @Unique
    private VisualizationContext stabilizedTurret$context;
    @Unique
    private Direction stabilizedTurret$sourceFacing;
    @Unique
    private Direction stabilizedTurret$targetFacing;
    @Unique
    private Model stabilizedTurret$model;
    @Unique
    private boolean stabilizedTurret$nativeInstanceDeleted;
    @Unique
    private boolean stabilizedTurret$visualDeleted;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void stabilizedTurret$initializeBearingHeadVisibility(
            VisualizationContext context,
            KineticBlockEntity blockEntity,
            float partialTick,
            Direction sourceFacing,
            Direction targetFacing,
            Model model,
            CallbackInfo ci
    ) {
        if (!(blockEntity instanceof SwivelBearingPlateBlockEntity plate)) {
            return;
        }
        stabilizedTurret$bearingPlate = plate;
        stabilizedTurret$context = context;
        stabilizedTurret$sourceFacing = sourceFacing;
        stabilizedTurret$targetFacing = targetFacing;
        stabilizedTurret$model = model;
        stabilizedTurret$ensureNativeVisibility();
    }

    @Inject(method = "update", at = @At("HEAD"), cancellable = true, remap = false)
    private void stabilizedTurret$updateBearingHead(float partialTick, CallbackInfo ci) {
        if (!stabilizedTurret$ensureNativeVisibility()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateLight", at = @At("HEAD"), cancellable = true, remap = false)
    private void stabilizedTurret$updateBearingHeadLight(float partialTick, CallbackInfo ci) {
        if (!stabilizedTurret$ensureNativeVisibility()) {
            ci.cancel();
        }
    }

    @Inject(method = "_delete", at = @At("HEAD"), cancellable = true, remap = false)
    private void stabilizedTurret$deleteBearingHead(CallbackInfo ci) {
        if (stabilizedTurret$bearingPlate == null) {
            return;
        }
        stabilizedTurret$visualDeleted = true;
        if (stabilizedTurret$nativeInstanceDeleted) {
            ci.cancel();
        } else {
            stabilizedTurret$nativeInstanceDeleted = true;
        }
    }

    @Inject(method = "collectCrumblingInstances", at = @At("HEAD"), cancellable = true, remap = false)
    private void stabilizedTurret$collectBearingHead(
            Consumer<Instance> consumer,
            CallbackInfo ci
    ) {
        if (!stabilizedTurret$ensureNativeVisibility()) {
            ci.cancel();
        }
    }

    @Unique
    private boolean stabilizedTurret$ensureNativeVisibility() {
        if (stabilizedTurret$bearingPlate == null) {
            return true;
        }
        if (stabilizedTurret$visualDeleted) {
            return false;
        }
        boolean hidden = ((BearingHeadVisibility) stabilizedTurret$bearingPlate)
                .stabilizedTurret$isBearingHeadHidden();
        if (hidden) {
            if (!stabilizedTurret$nativeInstanceDeleted) {
                rotatingModel.delete();
                stabilizedTurret$nativeInstanceDeleted = true;
            }
            return false;
        }
        if (stabilizedTurret$nativeInstanceDeleted) {
            stabilizedTurret$recreateNativeInstance();
        }
        return true;
    }

    @Unique
    private void stabilizedTurret$recreateNativeInstance() {
        RotatingInstance restored = (RotatingInstance) stabilizedTurret$context
                .instancerProvider()
                .instancer(AllInstanceTypes.ROTATING, stabilizedTurret$model)
                .createInstance();
        BlockPos visualPos = stabilizedTurret$bearingPlate.getBlockPos()
                .subtract(stabilizedTurret$context.renderOrigin());
        restored.rotateToFace(stabilizedTurret$sourceFacing, stabilizedTurret$targetFacing)
                .setup(stabilizedTurret$bearingPlate)
                .setPosition(visualPos);
        restored.setChanged();
        rotatingModel = restored;
        stabilizedTurret$nativeInstanceDeleted = false;
    }
}
