package com.hooya.stabilizedturret.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Instance-wide visual preferences; device HUD placement and live weapon settings stay device-owned. */
public final class ClientVisualConfig {
    public static final String FILE = "create_fire_control-visuals-client.toml";
    public static final String DEFAULT_HUD_COLOR = "#DDE5E8";
    public static final double DEFAULT_CAMERA_DELAY = 1.0 / 8.0;
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue SMOKE_LIFETIME_MULTIPLIER, THIRD_PERSON_CAMERA_DELAY;
    public static final ModConfigSpec.BooleanValue SHOW_BOMB_INDICATOR, SHOW_ROCKET_IMPACT, SHOW_AIRCRAFT_HUD, ARMOR_EDITOR_XRAY, ARMOR_GROUP_TEXTURES;
    public static final ModConfigSpec.ConfigValue<String> HUD_TEXT_COLOR;
    static {
        var b = new ModConfigSpec.Builder();
        b.push("missileSmoke");
        SMOKE_LIFETIME_MULTIPLIER = b.comment("Multiplier of each missile smoke particle's original lifetime, including native randomness. 1 preserves the installed provider exactly.")
                .defineInRange("lifetimeMultiplier", 1.0, .05, 10.0);
        b.pop().push("aircraftHud");
        SHOW_BOMB_INDICATOR = b.define("showBombIndicator", true);
        SHOW_ROCKET_IMPACT = b.define("showRocketImpact", true);
        SHOW_AIRCRAFT_HUD = b.define("showAircraftHud", true);
        HUD_TEXT_COLOR = b.comment("Aircraft flight data and weapon-list text. #RRGGBB or #AARRGGBB. Default preserves the original colours of each existing text path.")
                .define("textColor", DEFAULT_HUD_COLOR, ClientVisualConfig::validColor);
        b.pop().push("headAim");
        THIRD_PERSON_CAMERA_DELAY = b.comment("Third-person head-aim camera response time constant in seconds. 0 follows immediately; 0.125 preserves the original 8-per-second response.")
                .defineInRange("thirdPersonCameraDelaySeconds", DEFAULT_CAMERA_DELAY, 0.0, 5.0);
        b.pop().push("armorEditor");
        ARMOR_EDITOR_XRAY = b.comment("Show occluded armor vertices and edges at 28% opacity. Marquee selection always includes hidden elements.")
                .define("xray", true);
        ARMOR_GROUP_TEXTURES = b.comment("Show each copied material in multi-block armor editing. Off shows the translucent edit surface.")
                .define("groupTextures", true);
        b.pop();
        SPEC = b.build();
    }

    public static boolean showBombIndicator() { return flag(SHOW_BOMB_INDICATOR); }
    public static boolean showRocketImpact() { return flag(SHOW_ROCKET_IMPACT); }
    public static boolean showAircraftHud() { return flag(SHOW_AIRCRAFT_HUD); }
    public static boolean armorEditorXray() { return flag(ARMOR_EDITOR_XRAY); }
    public static void armorEditorXray(boolean enabled) { ARMOR_EDITOR_XRAY.set(enabled); if(SPEC.isLoaded())SPEC.save(); }
    public static boolean armorGroupTextures() { return flag(ARMOR_GROUP_TEXTURES); }
    public static void armorGroupTextures(boolean enabled) { ARMOR_GROUP_TEXTURES.set(enabled); if(SPEC.isLoaded())SPEC.save(); }
    private static boolean flag(ModConfigSpec.BooleanValue value) { return SPEC.isLoaded() ? value.get() : value.getDefault(); }
    public static int smokeLifetime(int originalTicks) {
        double multiplier = SPEC.isLoaded() ? SMOKE_LIFETIME_MULTIPLIER.get() : SMOKE_LIFETIME_MULTIPLIER.getDefault();
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, Math.round(originalTicks * multiplier)));
    }
    public static double cameraFollowAlpha(double elapsedSeconds) {
        double delay = SPEC.isLoaded() ? THIRD_PERSON_CAMERA_DELAY.get() : THIRD_PERSON_CAMERA_DELAY.getDefault();
        return delay <= 0 ? 1.0 : 1.0 - Math.exp(-Math.max(0.0, elapsedSeconds) / delay);
    }
    public static boolean validColor(Object value) {
        return value instanceof String color && color.matches("#?(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    }
    public static int hudTextColor(int originalColor) {
        String color = SPEC.isLoaded() ? HUD_TEXT_COLOR.get() : HUD_TEXT_COLOR.getDefault();
        if (DEFAULT_HUD_COLOR.equalsIgnoreCase(color) || !validColor(color)) return originalColor;
        String digits = color.startsWith("#") ? color.substring(1) : color;
        long parsed = Long.parseLong(digits, 16);
        return digits.length() == 8 ? (int) parsed : (originalColor & 0xFF000000) | (int) parsed;
    }
    private ClientVisualConfig() {}
}
