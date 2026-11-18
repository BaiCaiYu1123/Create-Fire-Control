package com.hooya.stabilizedturret.client.renderer;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.loader.Type10LoaderBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

public final class Type10LoaderModel extends GeoModel<Type10LoaderBlockEntity> {
    // Preserve the source names, including the two differently named final groups.
    public static final String[] SHELL_BONES = {
            "APFSDS-1", "APFSDS-2", "APFSDS-3", "APFSDS-4", "APFSDS-5",
            "APFSDS-6", "APFSDS-7", "APFSDS-8", "APFSDS-9", "APFSDS-10",
            "APFSDS-11", "APFSD-12", "APFSDS13"
    };

    private static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, path);
    }

    @Override
    public ResourceLocation getModelResource(Type10LoaderBlockEntity loader) {
        return resource("geo/type_10_loader.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(Type10LoaderBlockEntity loader) {
        return resource("textures/block/type_10_loader.png");
    }

    @Override
    public ResourceLocation getAnimationResource(Type10LoaderBlockEntity loader) {
        return resource("animations/type_10_loader.animation.json");
    }

    @Override
    public void setCustomAnimations(Type10LoaderBlockEntity loader, long instanceId,
                                    AnimationState<Type10LoaderBlockEntity> state) {
        super.setCustomAnimations(loader, instanceId, state);
        for (int i = 0; i < SHELL_BONES.length; i++) {
            final int slot = i;
            getBone(SHELL_BONES[i]).ifPresent(bone -> {
                bone.setHidden((loader.getSlots() & (1 << slot)) == 0);
                if (!loader.isAnimationPlaying()) {
                    bone.setPosX(0); bone.setPosY(0); bone.setPosZ(0);
                    bone.setScaleX(1); bone.setScaleY(1); bone.setScaleZ(1);
                }
            });
        }
    }
}
