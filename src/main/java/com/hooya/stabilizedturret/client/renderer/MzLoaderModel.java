package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.loader.MzLoaderBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

public class MzLoaderModel extends GeoModel<MzLoaderBlockEntity> {
    /** Bind rotation (degrees) of the rack drum bone "1" in the model; keeps the rest pose aligned. */
    public static final float WHEEL_BIND_DEG = 105.0f;

    /**
     * Horizontal center of the lower roulette/drum in model pixels. The rear loading rod,
     * slider, chain and lift assembly deliberately do not participate in this measurement.
     * Their asymmetric overhang previously shifted the all-geometry bbox center to -14.68315,
     * leaving the actual drum center 1.31685 pixels forward of the block center.
     */
    public static final double CENTER_X = 0.0;
    public static final double CENTER_Z = -16.0;

    public static final String[] SHELL_BONES = {
            "APFSDS3",
            "APFSDS19",
            "APFSDS17",
            "APFSDS16",
            "APFSDS15",
            "APFSDS14",
            "APFSDS13",
            "APFSDS12",
            "APFSDS11",
            "APFSDS10",
            "APFSDS9",
            "APFSDS8",
            "APFSDS7",
            "APFSDS6",
            "APFSDS5",
            "APFSDS4",
            "APFSDS2",
            "APFSDS26",
            "APFSDS25",
            "APFSDS24",
            "APFSDS23",
            "APFSDS22",
            "APFSDS21",
            "APFSDS20"
    };

    public static final String[] PROP_CHARGE_BONES = {
            "Propellant",
            "Propellant18",
            "Propellant16",
            "Propellant15",
            "Propellant14",
            "Propellant13",
            "Propellant12",
            "Propellant11",
            "Propellant10",
            "Propellant9",
            "Propellant8",
            "Propellant7",
            "Propellant6",
            "Propellant5",
            "Propellant4",
            "Propellant3",
            "Propellant2",
            "Propellant25",
            "Propellant24",
            "Propellant23",
            "Propellant22",
            "Propellant21",
            "Propellant20",
            "Propellant19"
    };

    @Override
    public ResourceLocation getModelResource(MzLoaderBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "geo/mz_loader.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(MzLoaderBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "textures/block/mz_loader.png");
    }

    @Override
    public ResourceLocation getAnimationResource(MzLoaderBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "animations/mz_loader.animation.json");
    }

    @Override
    public void setCustomAnimations(
            MzLoaderBlockEntity animatable,
            long instanceId,
            AnimationState<MzLoaderBlockEntity> animationState
    ) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        int slots = animatable.getSlots();
        for (int i = 0; i < SHELL_BONES.length; i++) {
            // Empty slots hide only the shell body and the propellant charge.
            // The shell casing (Shell_casing) and all rack structure stay visible and rotate with the rack.
            boolean visible = (slots & (1 << i)) != 0;
            getBone(SHELL_BONES[i]).ifPresent(bone -> bone.setHidden(!visible));
            getBone(PROP_CHARGE_BONES[i]).ifPresent(bone -> bone.setHidden(!visible));
        }
        // The drum rests at its bind angle plus one 15-degree step per completed shot, and swings
        // +15 degrees during the loading animation. Set absolutely every frame (no accumulation).
        float wheelDeg = WHEEL_BIND_DEG + animatable.getWheelIndex() * 15.0f
                + animatable.getWheelSwingDegrees();
        float wheelRot = (float) -Math.toRadians(wheelDeg);
        getBone("1").ifPresent(bone -> bone.setRotY(wheelRot));
    }
}
