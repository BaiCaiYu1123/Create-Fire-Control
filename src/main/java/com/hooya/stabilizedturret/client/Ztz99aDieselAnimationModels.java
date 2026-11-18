package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.diesel.Ztz99aDieselEnginePart;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/** Separate moving fan meshes share the original block's atlas texture. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID, value=Dist.CLIENT, bus=EventBusSubscriber.Bus.MOD)
public final class Ztz99aDieselAnimationModels {
    public static final ModelResourceLocation[] FANS = {
        fan("group4"), fan("group6")
    };
    public static final ModelResourceLocation[] SLICES = slices();
    private static ModelResourceLocation[] slices() {
        var parts = Ztz99aDieselEnginePart.values();
        var models = new ModelResourceLocation[parts.length];
        for (var part : parts) models[part.ordinal()] = ModelResourceLocation.standalone(
                ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,
                        "block/ztz99a_diesel_engine/" + part.getSerializedName()));
        return models;
    }
    private static ModelResourceLocation fan(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(
                StabilizedTurret.MOD_ID, "block/ztz99a_diesel_engine/fan_" + name));
    }
    @SubscribeEvent public static void register(ModelEvent.RegisterAdditional event) {
        for (var model : FANS) event.register(model);
        for (var part : Ztz99aDieselEnginePart.values())
            if (!part.isOccupied()) event.register(SLICES[part.ordinal()]);
    }
    private Ztz99aDieselAnimationModels() {}
}
