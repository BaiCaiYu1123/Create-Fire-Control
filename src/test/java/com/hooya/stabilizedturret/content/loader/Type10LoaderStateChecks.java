package com.hooya.stabilizedturret.content.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hooya.stabilizedturret.client.renderer.Type10CannonBreechRenderer;
import com.hooya.stabilizedturret.client.renderer.Type10LoaderModel;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Checks stock removal independently of the unmodified source animation and breech mesh. */
public final class Type10LoaderStateChecks {
    public static void main(String[] args) throws Exception {
        int rounds = 0;
        for (int i = 1; i <= 13; i++) {
            rounds = Type10LoaderBlockEntity.insertIntoFirstEmptySlot(rounds);
            require(rounds == (1 << i) - 1, "Insertion must reveal slots 1 through " + i);
        }
        require(Type10LoaderBlockEntity.insertIntoFirstEmptySlot(rounds) == rounds,
                "Fourteenth insertion must leave the rack full");
        for (int i = 13; i > 0; i--) {
            require(Type10LoaderBlockEntity.highestSlotNumber(rounds) == i, "Wrong consumption order");
            rounds = Type10LoaderBlockEntity.consumeHighestSlot(rounds);
            require(rounds == (1 << (i - 1)) - 1, "Only the highest round may disappear");
        }
        require(Type10LoaderBlockEntity.consumeHighestSlot(0) == 0, "Empty consumption must be harmless");
        // Sparse saved racks must also respect numeric order, including the 9/10 boundary.
        for (int mask = 1; mask <= Type10LoaderBlockEntity.SLOT_MASK; mask++) {
            int consumed = Type10LoaderBlockEntity.consumeHighestSlot(mask);
            require(Integer.bitCount(mask) - Integer.bitCount(consumed) == 1, "Consume exactly one round");
            require(consumed < Integer.highestOneBit(mask), "The largest numbered slot must be consumed");
            int inserted = Type10LoaderBlockEntity.insertIntoFirstEmptySlot(mask);
            if (mask != Type10LoaderBlockEntity.SLOT_MASK) {
                int firstEmpty = Integer.numberOfTrailingZeros(~mask);
                require((inserted ^ mask) == 1 << firstEmpty, "Fill the lowest empty number first");
            }
        }
        checkResources();
        Type10LoaderPlaybackChecks.check();
        System.out.println("Type 10: 13 insertions/removals, all 8192 rack states, animations, sound and breech resources PASS");
    }

    private static void checkResources() throws Exception {
        JsonObject geo = resource("geo/type_10_loader.geo.json")
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        Map<String, JsonObject> bones = new HashMap<>();
        int cubeCount = 0;
        for (var element : geo.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            require(bones.put(bone.get("name").getAsString(), bone) == null, "Duplicate bone");
            if (bone.has("cubes")) cubeCount += bone.getAsJsonArray("cubes").size();
        }
        require(cubeCount == 410, "Preserve all source loader geometry");
        JsonObject animations = resource("animations/type_10_loader.animation.json").getAsJsonObject("animations");
        require(animations.size() == 1, "Stock numbers must not generate or select animation variants");
        for (int slot = 1; slot <= 13; slot++) {
            String shell = Type10LoaderModel.SHELL_BONES[slot - 1];
            require(bones.get(shell).get("parent").getAsString().equals("apfsds"), "Wrong ammunition parent");
        }
        JsonObject animation = animations.getAsJsonObject(Type10LoaderBlockEntity.ANIMATION_NAME);
        require(animation != null, "Play the original animation at every inventory level");
        require(Math.ceil(animation.get("animation_length").getAsDouble() * 20)
                == Type10LoaderBlockEntity.ANIM_TICKS, "Server and animation durations differ");
        require(animation.getAsJsonObject("sound_effects").getAsJsonObject("0").get("effect")
                .getAsString().equals("create_fire_control:type_10_loader"), "Missing source sound cue");
        JsonObject tracks = animation.getAsJsonObject("bones");
        for (String name : tracks.keySet()) require(bones.containsKey(name), "Animation references missing bone");
        for (String shell : Type10LoaderModel.SHELL_BONES) {
            require(tracks.has(shell) == shell.equals("APFSDS-1"),
                    "Original APFSDS-1 animation must not move to the highest stock slot");
        }
        checkOriginalKeyframes(animation);
        require(Type10LoaderBlockEntity.OPEN_BREECH_TICKS < Type10LoaderBlockEntity.INSERT_TICKS
                && Type10LoaderBlockEntity.INSERT_TICKS < Type10LoaderBlockEntity.CLOSE_BREECH_TICKS
                && Type10LoaderBlockEntity.CLOSE_BREECH_TICKS < Type10LoaderBlockEntity.ANIM_TICKS,
                "Open, insert, close and finish must remain ordered");
        require(Type10CannonBreechRenderer.OPEN_TRAVEL == 5.0F / 16.0F, "Breech must open by exactly five pixels");
        require(resource("models/block/type_10_cannon_breech.json").getAsJsonArray("elements").isEmpty(),
                "Static block model must not duplicate the recoil body");
        require(countVertices("body") == 27 * 36, "Keep the 27 non-group cubes in the body");
        require(countVertices("sliding_block") == 9 * 36, "Only the nine group cubes slide");
        require(countVertices("item") == 36 * 36, "Inventory icon needs the whole closed breech");
        try (var sound = Type10LoaderStateChecks.class.getResourceAsStream(
                "/assets/create_fire_control/sounds/type_10_loader.ogg")) {
            require(sound != null && new String(sound.readNBytes(4), StandardCharsets.US_ASCII).equals("OggS"),
                    "Missing playable Vorbis resource");
        }
    }

    private static void checkOriginalKeyframes(JsonObject exported) throws Exception {
        // This fixture is the original uploaded model's data, independent of the converter.
        JsonObject fixture = resource("fixtures/type_10_loader_source_animation.json");
        Map<String, String> groupNames = new HashMap<>();
        for (var element : fixture.getAsJsonArray("groups")) {
            JsonObject group = element.getAsJsonObject();
            groupNames.put(group.get("uuid").getAsString(), group.get("name").getAsString());
        }
        JsonObject original = fixture.getAsJsonObject("animation");
        require(original.get("name").getAsString().equals(Type10LoaderBlockEntity.ANIMATION_NAME),
                "Preserve the source animation name");
        require(exported.get("animation_length").equals(original.get("length")), "Preserve source duration");
        require(exported.get("loop").getAsString().equals("hold_on_last_frame"), "Preserve source hold mode");
        JsonObject expected = new JsonObject();
        for (var entry : original.getAsJsonObject("animators").entrySet()) {
            String name = groupNames.get(entry.getKey());
            if (name == null) continue;
            JsonArray keyframes = entry.getValue().getAsJsonObject().getAsJsonArray("keyframes");
            if (keyframes == null) continue;
            JsonObject channels = new JsonObject();
            for (var element : keyframes) {
                JsonObject frame = element.getAsJsonObject();
                String channel = frame.get("channel").getAsString();
                if (!java.util.Set.of("position", "rotation", "scale").contains(channel)) continue;
                JsonObject point = frame.getAsJsonArray("data_points").get(0).getAsJsonObject();
                JsonArray vector = new JsonArray();
                for (String axis : new String[]{"x", "y", "z"}) {
                    double value = point.get(axis).getAsDouble();
                    if (channel.equals("rotation") && !axis.equals("z")) value = -value;
                    vector.add(value);
                }
                if (!channels.has(channel)) channels.add(channel, new JsonObject());
                String time = frame.get("time").getAsBigDecimal().stripTrailingZeros().toPlainString();
                channels.getAsJsonObject(channel).add(time.contains(".") ? time : time + ".0", vector);
            }
            if (!channels.isEmpty()) expected.add(name, channels);
        }
        require(exported.getAsJsonObject("bones").equals(expected),
                "All original bone targets, keyframe times and values must be preserved exactly");
    }

    private static long countVertices(String part) throws Exception {
        try (var reader = new java.io.BufferedReader(new InputStreamReader(
                Type10LoaderStateChecks.class.getResourceAsStream("/assets/create_fire_control/models/block/"
                        + "type_10_cannon_breech/" + part + ".obj"), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> line.startsWith("v ")).count();
        }
    }

    private static JsonObject resource(String path) throws Exception {
        try (var reader = new InputStreamReader(Type10LoaderStateChecks.class.getResourceAsStream(
                "/assets/create_fire_control/" + path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
