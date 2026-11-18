package com.hooya.stabilizedturret.content.loader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import software.bernie.geckolib.animation.EasingType;
import software.bernie.geckolib.animation.keyframe.AnimationPoint;
import software.bernie.geckolib.animation.keyframe.BoneAnimation;
import software.bernie.geckolib.animation.keyframe.Keyframe;
import software.bernie.geckolib.animation.keyframe.KeyframeStack;
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter;
import software.bernie.geckolib.loading.math.MathValue;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compare the real GeckoLib decoder/easing output with Blockbench's sorted timeline. */
public final class Type10LoaderPlaybackChecks {
    public static void check() throws Exception {
        JsonObject source = resource("fixtures/type_10_loader_source_animation.json");
        JsonObject exported = resource("animations/type_10_loader.animation.json").getAsJsonObject("animations");
        var animation = KeyFramesAdapter.GEO_GSON.fromJson(exported, BakedAnimations.class)
                .getAnimation(Type10LoaderBlockEntity.ANIMATION_NAME);
        require(animation != null, "GeckoLib must decode the original animation");
        Map<String, BoneAnimation> baked = new HashMap<>();
        for (var bone : animation.boneAnimations()) baked.put(bone.boneName(), bone);
        Map<String, String> names = new HashMap<>();
        for (var element : source.getAsJsonArray("groups")) {
            var group = element.getAsJsonObject();
            names.put(group.get("uuid").getAsString(), group.get("name").getAsString());
        }
        int comparisons = 0;
        for (var entry : source.getAsJsonObject("animation").getAsJsonObject("animators").entrySet()) {
            String name = names.get(entry.getKey());
            var raw = entry.getValue().getAsJsonObject().getAsJsonArray("keyframes");
            if (name == null || raw == null) continue;
            Map<String, List<SourceFrame>> channels = new HashMap<>();
            for (var element : raw) {
                var frame = element.getAsJsonObject();
                String channel = frame.get("channel").getAsString();
                if (!List.of("position", "rotation", "scale").contains(channel)) continue;
                var point = frame.getAsJsonArray("data_points").get(0).getAsJsonObject();
                double[] values = {point.get("x").getAsDouble(), point.get("y").getAsDouble(), point.get("z").getAsDouble()};
                if (channel.equals("rotation")) {
                    for (int axis = 0; axis < 3; axis++) values[axis] = Math.toRadians(values[axis]);
                }
                channels.computeIfAbsent(channel, ignored -> new ArrayList<>())
                        .add(new SourceFrame(frame.get("time").getAsDouble() * 20, values));
            }
            for (var channel : channels.entrySet()) {
                channel.getValue().sort(Comparator.comparingDouble(SourceFrame::tick));
                var bone = baked.get(name);
                require(bone != null, "Missing baked bone " + name);
                KeyframeStack<Keyframe<MathValue>> stack = switch (channel.getKey()) {
                    case "position" -> bone.positionKeyFrames();
                    case "rotation" -> bone.rotationKeyFrames();
                    default -> bone.scaleKeyFrames();
                };
                var axes = List.of(stack.xKeyframes(), stack.yKeyframes(), stack.zKeyframes());
                for (int axis = 0; axis < 3; axis++) {
                    var frames = axes.get(axis);
                    for (var frame : frames) {
                        require(frame.length() >= 0, "Negative GeckoLib frame duration: " + name + "/" + channel.getKey());
                    }
                    for (int sample = 0; sample <= 220; sample++) {
                        double tick = sample * .4; // Every 20 ms throughout the 4.4 second animation.
                        double expected = sourceValue(channel.getValue(), tick, axis);
                        double actual = geckoValue(frames, tick);
                        require(Math.abs(expected - actual) < 3e-6,
                                name + "/" + channel.getKey() + " axis=" + axis + " tick=" + tick
                                        + " expected=" + expected + " actual=" + actual);
                        comparisons++;
                    }
                }
            }
        }
        System.out.println("GeckoLib playback matches Blockbench at " + comparisons + " sampled axis values");
    }

    private static double sourceValue(List<SourceFrame> frames, double tick, int axis) {
        SourceFrame previous = frames.getFirst();
        for (SourceFrame next : frames) {
            if (tick <= next.tick()) {
                if (tick <= previous.tick() || next.tick() == previous.tick()) return previous.values()[axis];
                double fraction = (tick - previous.tick()) / (next.tick() - previous.tick());
                return previous.values()[axis] + fraction * (next.values()[axis] - previous.values()[axis]);
            }
            previous = next;
        }
        return previous.values()[axis];
    }

    private static double geckoValue(List<Keyframe<MathValue>> frames, double tick) {
        double elapsed = 0;
        for (var frame : frames) {
            if (tick <= elapsed + frame.length() + 1e-9) {
                return EasingType.lerpWithOverride(new AnimationPoint(frame,
                        Math.clamp(tick - elapsed, 0, frame.length()), frame.length(),
                        frame.startValue().get(), frame.endValue().get()), null);
            }
            elapsed += frame.length();
        }
        return frames.getLast().endValue().get();
    }

    private static JsonObject resource(String path) throws Exception {
        try (var reader = new InputStreamReader(Type10LoaderPlaybackChecks.class.getResourceAsStream(
                "/assets/create_fire_control/" + path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record SourceFrame(double tick, double[] values) { }
}
