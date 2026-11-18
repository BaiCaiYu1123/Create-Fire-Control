package com.hooya.stabilizedturret.content.loader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hooya.stabilizedturret.client.renderer.AzLoaderModel;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Checks AZ inventory ordering and the exported model/animation contract together. */
public final class AzLoaderStateChecks {
    public static void main(String[] args) throws IOException {
        checkModelResources();
        int liveRounds = 0;
        int casings = 0;
        for (int slot = 0; slot < AzLoaderBlockEntity.MAX_LOADED; slot++) {
            int insertedSlot = AzLoaderBlockEntity.firstEmptySlot(liveRounds);
            if (insertedSlot != slot) {
                throw new AssertionError("Expected Blockbench slot " + slot
                        + " but selected " + insertedSlot);
            }
            liveRounds = AzLoaderBlockEntity.insertIntoFirstEmptySlot(liveRounds);
            casings |= 1 << insertedSlot;
        }

        int fullMask = (1 << AzLoaderBlockEntity.MAX_LOADED) - 1;
        if (liveRounds != fullMask || casings != fullMask) {
            throw new AssertionError("Twenty-four insertions must fill every live/casing slot");
        }

        int wheel = 0;
        for (int cycle = 0; cycle < AzLoaderBlockEntity.MAX_LOADED; cycle++) {
            int expectedSlot = cycle;
            String expectedAnimation = "AZ_" + (expectedSlot + 1);
            String actualAnimation = AzLoaderBlockEntity.animationNameForWheelIndex(wheel);
            if (!expectedAnimation.equals(actualAnimation)) {
                throw new AssertionError("Expected " + expectedAnimation
                        + " but selected " + actualAnimation);
            }
            // GeckoLib negates exported X/Y animation values while baking:
            // the JSON -15 degree wheel step is +15 degrees internally.
            int retainedParentRotation = wheel * 15;
            int sourceAnimationStepInternal = 15;
            if (retainedParentRotation != cycle * sourceAnimationStepInternal) {
                throw new AssertionError("Retained wheel direction diverges from the source animation");
            }
            int selectedSlot = Integer.numberOfTrailingZeros(liveRounds);
            if (selectedSlot != expectedSlot) {
                throw new AssertionError("Expected cycle order slot " + expectedSlot
                        + " but selected " + selectedSlot);
            }
            liveRounds = AzLoaderBlockEntity.consumeFrontSlot(liveRounds);
            wheel = AzLoaderBlockEntity.advanceWheelIndex(wheel, liveRounds);
            if (casings != fullMask) {
                throw new AssertionError("Consuming a live round must not remove its casing");
            }
        }
        if (liveRounds != 0 || wheel != 0 || casings != fullMask) {
            throw new AssertionError("Empty carousel must reset its wheel and retain all casings");
        }
    }

    private static void checkModelResources() throws IOException {
        JsonObject geometry = resource("geo/az_loader.geo.json")
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        Map<String, JsonObject> bones = new HashMap<>();
        for (var element : geometry.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            require(bones.put(name, bone) == null, "Duplicate bone: " + name);
        }
        for (var bone : bones.values()) {
            require(!bone.has("parent") || bones.containsKey(bone.get("parent").getAsString()),
                    "Missing parent: " + bone.get("name"));
        }
        String carousel = "Artillery shell placement position";
        require(bones.get(carousel).get("parent").getAsString().equals("AZ_carousel_offset"),
                "The carousel needs its persistent rotation parent");
        require(bones.get(carousel).getAsJsonArray("pivot")
                        .equals(bones.get("AZ_carousel_offset").getAsJsonArray("pivot")),
                "Persistent and animated carousel pivots must coincide");
        require(bones.get(carousel).getAsJsonArray("rotation").get(1).getAsDouble() == -15,
                "The renderer expects a 15-degree carousel bind rotation");

        JsonObject animations = resource("animations/az_loader.animation.json").getAsJsonObject("animations");
        JsonObject base = animations.getAsJsonObject("AZ").getAsJsonObject("bones");
        for (int slot = 0; slot < AzLoaderBlockEntity.MAX_LOADED; slot++) {
            String rack = Integer.toString(slot + 1);
            JsonObject rackBone = bones.get(rack);
            require(rackBone.get("parent").getAsString().equals(carousel), "Wrong rack parent: " + rack);
            double rotation = rackBone.has("rotation")
                    ? rackBone.getAsJsonArray("rotation").get(1).getAsDouble() : 0;
            // The retained Java rotation has the opposite sign to exported JSON Y.
            require(Math.abs(Math.IEEEremainder(rotation - slot * 15 - 30, 360)) < 1e-6,
                    "Rack does not arrive at the same loading position: " + rack);
            JsonObject animation = animations.getAsJsonObject(AzLoaderBlockEntity.animationNameForWheelIndex(slot));
            require(animation != null, "Missing animation for rack " + rack);
            require((int) Math.ceil(animation.get("animation_length").getAsDouble() * 20)
                            == AzLoaderBlockEntity.ANIM_TICKS,
                    "Animation duration and server timeline differ");
            require(animation.getAsJsonObject("sound_effects").getAsJsonObject("0")
                            .get("effect").getAsString().equals("create_fire_control:az_loader"),
                    "Missing cycle sound");
            JsonObject tracks = animation.getAsJsonObject("bones");
            for (String name : tracks.keySet()) require(bones.containsKey(name), "Animation targets missing bone: " + name);
            Set<String> ammo = Set.of(AzLoaderModel.SHELL_BONES[slot],
                    AzLoaderModel.PROP_CHARGE_BONES[slot], AzLoaderModel.CASING_BONES[slot]);
            for (String name : ammo) {
                require(isDescendant(bones, name, rack), "Ammo mapped to wrong inventory slot: " + name);
                require(tracks.has(name), "Current round is not animated: " + name);
                var hiddenScale = tracks.getAsJsonObject(name).getAsJsonObject("scale").getAsJsonArray("5.12");
                require(hiddenScale != null && hiddenScale.get(0).getAsDouble() < 0.001,
                        "Loaded round must disappear at the insertion keyframe: " + name);
            }
            for (String name : tracks.keySet()) {
                if (name.startsWith("APFSDS") || name.startsWith("Propellant") || name.startsWith("Shell_casing")) {
                    require(ammo.contains(name), "Another slot's ammunition moves during " + rack);
                } else if (name.startsWith("Armor-piercing_projectile_placement")
                        || name.startsWith("Placement_of_propellant")) {
                    require(isDescendant(bones, name, rack), "Another slot's tray moves during " + rack);
                } else {
                    require(tracks.get(name).equals(base.get(name)), "Shared mechanism differs in rack " + rack);
                }
            }
            for (int chain = 1; chain <= 16; chain++) {
                require(tracks.has("Chain" + chain), "New model chain segment is not animated: " + chain);
            }
        }
    }

    private static boolean isDescendant(Map<String, JsonObject> bones, String name, String ancestor) {
        for (int depth = 0; depth < bones.size(); depth++) {
            if (name.equals(ancestor)) return true;
            JsonObject bone = bones.get(name);
            if (bone == null || !bone.has("parent")) return false;
            name = bone.get("parent").getAsString();
        }
        return false;
    }

    private static JsonObject resource(String path) throws IOException {
        String location = "/assets/create_fire_control/" + path;
        var stream = AzLoaderStateChecks.class.getResourceAsStream(location);
        if (stream == null) throw new AssertionError("Missing resource " + location);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private AzLoaderStateChecks() {
    }
}
