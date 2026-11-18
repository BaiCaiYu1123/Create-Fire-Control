package com.hooya.stabilizedturret.client.remote;

import com.google.gson.*;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.remote.ControllerSettings;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original Blockbench group hierarchy, with a separate baked part for every keycap. */
public final class KeyboardControllerModel {
    private static final ResourceLocation DATA = id("keyboard/controller.json");
    private static volatile Variant active, folded;

    record Part(ModelResourceLocation model, String input, double depth, boolean glow) {}
    record Frame(double time, double[] value, String interpolation, double[] leftTime,
                 double[] leftValue, double[] rightTime, double[] rightValue) {}
    record Node(double[] pivot, double[] rotation, List<Part> parts, List<Node> children,
                Map<String, List<Frame>> tracks) {}
    record Variant(List<Node> nodes, double length) {}

    public static void register(ModelEvent.RegisterAdditional event) {
        // ModelBakery may run against a new manager while Minecraft still exposes
        // the old one (e.g. Lightspeed). Register the bundled catalog without
        // consulting Minecraft's current resource manager at this event boundary.
        JsonObject root = bundled();
        for (String name : List.of("active", "folded")) {
            for (var model : root.getAsJsonObject(name).getAsJsonArray("models"))
                for (String color : ControllerSettings.COLORS)
                    event.register(colored(ModelResourceLocation.standalone(id(model.getAsString())),color));
        }
        if (active == null || folded == null) load(root);
    }

    public static void reloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) KeyboardControllerModel::reload);
    }
    private static void reload(ResourceManager manager) {
        // Use the manager supplied to this reload, including resource-pack overrides.
        try (var reader = manager.openAsReader(DATA)) {
            load(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException | RuntimeException error) {
            StabilizedTurret.LOGGER.warn("Cannot load keyboard resource override; using the bundled keyboard", error);
            load(bundled());
        }
    }
    private static JsonObject bundled() {
        try (var stream = KeyboardControllerModel.class.getResourceAsStream("/assets/create_fire_control/keyboard/controller.json")) {
            if (stream == null) throw new IOException("Bundled keyboard data missing");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) { throw new IllegalStateException("Missing bundled keyboard model",error); }
    }
    private static void load(JsonObject root) {
        Variant nextActive = variant(root.getAsJsonObject("active"));
        Variant nextFolded = variant(root.getAsJsonObject("folded"));
        active = nextActive; folded = nextFolded;
    }
    private static Variant variant(JsonObject json) {
        return new Variant(nodes(json.getAsJsonArray("nodes")), json.get("length").getAsDouble());
    }

    private static List<Node> nodes(JsonArray json) {
        List<Node> result = new ArrayList<>();
        for (var element : json) {
            var node = element.getAsJsonObject();
            List<Part> parts = new ArrayList<>();
            for (var p : node.getAsJsonArray("parts")) {
                var part = p.getAsJsonObject();
                parts.add(new Part(ModelResourceLocation.standalone(id(part.get("model").getAsString())),
                        part.get("input").getAsString(), part.get("depth").getAsDouble(), part.get("glow").getAsBoolean()));
            }
            Map<String, List<Frame>> tracks = new HashMap<>();
            for (var entry : node.getAsJsonObject("tracks").entrySet()) {
                List<Frame> frames = new ArrayList<>();
                for (var f : entry.getValue().getAsJsonArray()) {
                    var frame = f.getAsJsonObject();
                    frames.add(new Frame(frame.get("time").getAsDouble(), vector(frame, "value"),
                            frame.get("interpolation").getAsString(), vector(frame, "left_time"),
                            vector(frame, "left_value"), vector(frame, "right_time"), vector(frame, "right_value")));
                }
                tracks.put(entry.getKey(), List.copyOf(frames));
            }
            result.add(new Node(vector(node, "pivot"), vector(node, "rotation"), List.copyOf(parts),
                    nodes(node.getAsJsonArray("children")), Map.copyOf(tracks)));
        }
        return List.copyOf(result);
    }

    private static double[] vector(JsonObject json, String name) {
        var values = json.getAsJsonArray(name);
        return new double[]{values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble()};
    }

    public static double animationLength() { return folded == null ? 1.5 : folded.length(); }

    public static void render(PoseStack pose, MultiBufferSource buffer, int light, int overlay,
                              boolean enabled, double elapsed, Set<String> pressed) {
        if (active == null || folded == null) return;
        boolean split = enabled && elapsed >= folded.length();
        double time = enabled ? Math.max(0, folded.length() - elapsed) : folded.length();
        renderAt(pose,buffer,light,overlay,time,split,pressed);
    }
    public static void renderAt(PoseStack pose, MultiBufferSource buffer, int light, int overlay,
                                double time, boolean split, Set<String> pressed) {
        renderAt(pose,buffer,light,overlay,time,split,pressed,"default");
    }
    public static void renderAt(PoseStack pose, MultiBufferSource buffer, int light, int overlay,
                                double time, boolean split, Set<String> pressed, String color) {
        if (active == null || folded == null) return;
        Variant model = split ? active : folded;
        pose.pushPose();
        // ItemRenderer centers a full block. This shallow keyboard sits on its base.
        pose.translate(0, 0.5, 0);
        for (Node node : model.nodes()) renderNode(node, pose, buffer, light, overlay, time,
                split ? pressed : Set.of(),color);
        pose.popPose();
    }

    private static void renderNode(Node node, PoseStack pose, MultiBufferSource buffer,
                                   int light, int overlay, double time, Set<String> pressed, String color) {
        pose.pushPose();
        double[] position = sample(node.tracks().get("position"), time, 0);
        double[] rotation = sample(node.tracks().get("rotation"), time, 0);
        double[] scale = sample(node.tracks().get("scale"), time, 1);
        pose.translate(position[0] / 16, position[1] / 16, position[2] / 16);
        double[] pivot = node.pivot();
        pose.translate(pivot[0] / 16, pivot[1] / 16, pivot[2] / 16);
        pose.mulPose(Axis.ZP.rotationDegrees((float)(node.rotation()[2] + rotation[2])));
        pose.mulPose(Axis.YP.rotationDegrees((float)(node.rotation()[1] + rotation[1])));
        pose.mulPose(Axis.XP.rotationDegrees((float)(node.rotation()[0] + rotation[0])));
        pose.scale((float)scale[0], (float)scale[1], (float)scale[2]);
        pose.translate(-pivot[0] / 16, -pivot[1] / 16, -pivot[2] / 16);
        Minecraft mc = Minecraft.getInstance();
        for (Part part : node.parts()) {
            pose.pushPose();
            if (pressed.contains(part.input())) pose.translate(0, -part.depth() / 16, 0);
            var baked = mc.getModelManager().getModel(colored(part.model(),color));
            mc.getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffer.getBuffer(RenderType.cutout()),
                    null, baked, 1, 1, 1, part.glow() ? LightTexture.FULL_BRIGHT : light, overlay);
            pose.popPose();
        }
        for (Node child : node.children()) renderNode(child, pose, buffer, light, overlay, time, pressed,color);
        pose.popPose();
    }

    /** Bezier interpolation in time/value space, including the original per-axis handles. */
    static double[] sample(List<Frame> frames, double time, double fallback) {
        if (frames == null || frames.isEmpty()) return new double[]{fallback, fallback, fallback};
        Frame first = frames.getFirst(), last = frames.getLast();
        if (time <= first.time()) return first.value().clone();
        if (time >= last.time()) return last.value().clone();
        Frame a = first, b = last;
        for (int i = 1; i < frames.size(); i++) if (time < frames.get(i).time()) {
            a = frames.get(i - 1); b = frames.get(i); break;
        }
        double[] result = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            if (a.interpolation().equals("step")) { result[axis] = a.value()[axis]; continue; }
            if (!a.interpolation().equals("bezier") && !b.interpolation().equals("bezier")) {
                double t = (time - a.time()) / (b.time() - a.time());
                result[axis] = a.value()[axis] + t * (b.value()[axis] - a.value()[axis]); continue;
            }
            double x1 = Math.min(b.time(), a.time() + a.rightTime()[axis]);
            double x2 = Math.max(a.time(), b.time() + b.leftTime()[axis]);
            double lo = 0, hi = 1;
            for (int iteration = 0; iteration < 24; iteration++) {
                double t = (lo + hi) / 2;
                if (bezier(a.time(), x1, x2, b.time(), t) < time) lo = t; else hi = t;
            }
            result[axis] = bezier(a.value()[axis], a.value()[axis] + a.rightValue()[axis],
                    b.value()[axis] + b.leftValue()[axis], b.value()[axis], (lo + hi) / 2);
        }
        return result;
    }

    private static double bezier(double a, double b, double c, double d, double t) {
        double s = 1 - t;
        return s*s*s*a + 3*s*s*t*b + 3*s*t*t*c + t*t*t*d;
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, path); }
    static ModelResourceLocation colored(ModelResourceLocation model,String color) {
        return color.equals("default") ? model : ModelResourceLocation.standalone(
                model.id().withPath(model.id().getPath()+"_"+color));
    }
    private KeyboardControllerModel() {}
}
