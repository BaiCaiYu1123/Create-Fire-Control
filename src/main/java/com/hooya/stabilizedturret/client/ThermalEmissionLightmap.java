package com.hooya.stabilizedturret.client;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Encodes source heat in otherwise-unused low bits of packed light coordinates. */
public final class ThermalEmissionLightmap {
    private static final int MARKER_COORDINATE = 248;
    public static final int LIVING_MARKER = packCoordinates(
            MARKER_COORDINATE,
            MARKER_COORDINATE
    );

    public static int encodeBlock(int packedLight, int lightEmission) {
        int emission = Mth.clamp(lightEmission, 0, 15);
        if (emission > 0) {
            return encodeMarker(emission);
        }
        if (LightTexture.block(packedLight) == 15) {
            return LightTexture.pack(14, LightTexture.sky(packedLight));
        }
        return packedLight;
    }

    /**
     * Sodium samples the light texture with normalized coordinates, so its
     * vertices must point at texel centres (8, 24, ... 248). Vanilla's packed
     * light values (0, 16, ... 240) are texel indices for texelFetch and land
     * on filtering boundaries in Sodium.
     */
    public static int encodeSodium(int packedLight, int lightEmission) {
        int emission = Mth.clamp(lightEmission, 0, 15);
        if (emission > 0) {
            return encodeMarker(emission);
        }
        if (((packedLight & 0xFF) >> 4) >= 15) {
            return sodiumCoordinate(14) | packedLight & 0xFFFF0000;
        }
        return packedLight;
    }

    /** Returns the placed-block emission represented by an item entity. */
    public static int getItemEmission(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem blockItem) {
            return Mth.clamp(
                    blockItem.getBlock().defaultBlockState().getLightEmission(),
                    0,
                    15
            );
        }
        // A lava bucket has no BlockItem but still represents a full-heat
        // emissive world material.
        return stack.is(Items.LAVA_BUCKET) ? 15 : 0;
    }

    private static int sodiumCoordinate(int lightLevel) {
        return Math.min(lightLevel * 16 + 8, 248);
    }

    private static int encodeMarker(int emission) {
        return packCoordinates(
                MARKER_COORDINATE,
                sodiumCoordinate(emission)
        );
    }

    private static int packCoordinates(int blockCoordinate, int skyCoordinate) {
        return blockCoordinate | skyCoordinate << 16;
    }

    private ThermalEmissionLightmap() {
    }
}
