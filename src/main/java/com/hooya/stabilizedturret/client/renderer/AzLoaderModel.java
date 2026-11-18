package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.loader.AzLoaderBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

public class AzLoaderModel extends GeoModel<AzLoaderBlockEntity> {
    private static final float CAROUSEL_BIND_ROTATION = (float) Math.toRadians(15.0F);
    public static final double CENTER_X = 0.0;
    public static final double CENTER_Z = 0.0;

    public static final String[] SHELL_BONES = {
            "APFSDS2", "APFSDS3", "APFSDS4", "APFSDS5", "APFSDS6", "APFSDS7",
            "APFSDS8", "APFSDS9", "APFSDS10", "APFSDS11", "APFSDS12", "APFSDS13",
            "APFSDS14", "APFSDS15", "APFSDS16", "APFSDS17", "APFSDS18", "APFSDS19",
            "APFSDS20", "APFSDS21", "APFSDS22", "APFSDS23", "APFSDS24", "APFSDS25"
    };

    public static final String[] PROP_CHARGE_BONES = {
            "Propellant19", "Propellant2", "Propellant3", "Propellant4", "Propellant5",
            "Propellant6", "Propellant7", "Propellant8", "Propellant9", "Propellant10",
            "Propellant11", "Propellant12", "Propellant13", "Propellant14", "Propellant15",
            "Propellant16", "Propellant17", "Propellant18", "Propellant20", "Propellant21",
            "Propellant22", "Propellant23", "Propellant24", "Propellant25"
    };

    public static final String[] CASING_BONES = {
            "Shell_casing19", "Shell_casing2", "Shell_casing3", "Shell_casing4",
            "Shell_casing5", "Shell_casing6", "Shell_casing7", "Shell_casing8",
            "Shell_casing9", "Shell_casing10", "Shell_casing11", "Shell_casing12",
            "Shell_casing13", "Shell_casing14", "Shell_casing15", "Shell_casing16",
            "Shell_casing17", "Shell_casing18", "Shell_casing20", "Shell_casing21",
            "Shell_casing22", "Shell_casing23", "Shell_casing24", "Shell_casing25"
    };

    @Override
    public ResourceLocation getModelResource(AzLoaderBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                StabilizedTurret.MOD_ID, "geo/az_loader.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(AzLoaderBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                StabilizedTurret.MOD_ID, "textures/block/az_loader.png");
    }

    @Override
    public ResourceLocation getAnimationResource(AzLoaderBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(
                StabilizedTurret.MOD_ID, "animations/az_loader.animation.json");
    }

    @Override
    public void setCustomAnimations(AzLoaderBlockEntity animatable, long instanceId,
                                    AnimationState<AzLoaderBlockEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        int slots = animatable.getSlots();
        for (int i = 0; i < SHELL_BONES.length; i++) {
            boolean visible = (slots & (1 << i)) != 0;
            getBone(SHELL_BONES[i]).ifPresent(bone -> bone.setHidden(!visible));
            getBone(PROP_CHARGE_BONES[i]).ifPresent(bone -> bone.setHidden(!visible));
            // Casings are permanent carousel geometry and do not represent inventory.
            getBone(CASING_BONES[i]).ifPresent(bone -> bone.setHidden(false));
        }
        // The animated child owns the current 15-degree step. Once a cycle finishes,
        // wheelIndex advances while GeckoLib may still be resetting that child from its
        // final keyframe to the bind pose. Compensate with the parent so their combined
        // rotation stays constant instead of briefly double-stepping and rolling back.
        getBone("Artillery shell placement position").ifPresent(carousel ->
                getBone("AZ_carousel_offset").ifPresent(offset -> {
                    float completedSteps = (float) Math.toRadians(
                            animatable.getWheelIndex() * 15.0F);
                    if (animatable.isBusy()) {
                        offset.setRotY(completedSteps);
                    } else {
                        float desiredRestRotation = CAROUSEL_BIND_ROTATION + completedSteps;
                        offset.setRotY(desiredRestRotation - carousel.getRotY());
                    }
                }));

        // Every carousel slot has two permanent tray bones. Their numeric suffixes
        // are 2..25 in this converted model (slot 1 happens to use suffix 4).
        for (int suffix = 2; suffix <= 25; suffix++) {
            getBone("Armor-piercing_projectile_placement" + suffix).ifPresent(
                    bone -> bone.setHidden(false));
            getBone("Placement_of_propellant" + suffix).ifPresent(
                    bone -> bone.setHidden(false));
        }

        if (!animatable.isBusy()) {
            // The ammunition animation ends with its casing far from the rack and nearly
            // zero-sized. GeckoLib normally interpolates that bone back, making the spent
            // casing fly in from a distance. Restore all ammo bones atomically while idle.
            for (int i = 0; i < SHELL_BONES.length; i++) {
                resetAnimatedAmmoBone(SHELL_BONES[i]);
                resetAnimatedAmmoBone(PROP_CHARGE_BONES[i]);
                resetAnimatedAmmoBone(CASING_BONES[i]);
            }
        }
    }

    private void resetAnimatedAmmoBone(String boneName) {
        getBone(boneName).ifPresent(bone -> {
            bone.setPosX(0.0F);
            bone.setPosY(0.0F);
            bone.setPosZ(0.0F);
            bone.setScaleX(1.0F);
            bone.setScaleY(1.0F);
            bone.setScaleZ(1.0F);
        });
    }
}
