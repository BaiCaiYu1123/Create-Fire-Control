package com.hooya.stabilizedturret.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-model performance of missiles adopted by fire control; values are instance-wide. */
public final class MissileSettings {
    public record Defaults(double lifetimeSeconds, double speed, double poweredSeconds, boolean multiplier) {}
    private record Values(ModConfigSpec.DoubleValue lifetime, ModConfigSpec.DoubleValue speed,
                          ModConfigSpec.DoubleValue powered) {}
    private static final Map<String, Defaults> DEFAULTS = defaults();
    private static final Map<String, Values> VALUES = new LinkedHashMap<>();

    private static Map<String, Defaults> defaults() {
        Map<String, Defaults> result = new LinkedHashMap<>();
        add(result, 20, 1, .5, true, "antitankmissile_1tanshe", "antitankmissile_2tanshe",
                "antitankmissile_5tanshe", "antitankmissile_6tanshe", "antitankmissile_7tanshe");
        add(result, 30, 680.6, 10, false, "antiairmissiletanshe");
        add(result, 20, 340.3, 10, false, "portableantiairmissiletanshe");
        add(result, 20, 680.6, 10, false, "portableantiairmissiletanshe_2");
        add(result, 120, 680.6, 12, false, "farmissile_1tanshe", "farmissile_2tanshe");
        add(result, 80, 680.6, 8, false, "missile_2tanshe", "medium_aim_missile_2tanshe", "mica_em_tanshe", "aim_120_tanshe");
        add(result, 30, 680.6, 5, false, "closemissile_1tanshe", "closemissile_2tanshe");
        add(result, 15, 340.3, 2.5, false, "agm_1tanshe", "agm_2tanshe", "agm_3tanshe", "agm_4tanshe");
        add(result, 15, 340.3, .75, false, "missiletanshe");
        add(result, 20, 170.15, 2.5, false, "opticalguidedmissiletanshe");
        add(result, 20, 1020.9, 20, false, "antiradiationmissiletanshe");
        add(result, 120, 340.3, 50, false, "cruisemissiletanshe", "cruisemissilelowtanshe", "nuclearcruisemissiletanshe");
        add(result, 120, 680.6, 50, false, "groundnuclearcruisemissiletanshe", "groundmissiletanshe",
                "groundmissile_2tanshe", "groundmissile_3tanshe", "groundmissile_4tanshe");
        add(result, 120, 1020.9, 50, false, "ballistic_missile_entity", "ballistic_missile_entity_df26");
        return Collections.unmodifiableMap(result);
    }

    private static void add(Map<String, Defaults> result, double life, double speed, double burn,
                            boolean multiplier, String... paths) {
        for (String path : paths) result.put(path, new Defaults(life, speed, burn, multiplier));
    }

    public static void register(ModConfigSpec.Builder builder) {
        builder.translation("create_fire_control.config.missiles").comment("Only missiles adopted by fire control. Times are seconds; one block is one metre.").push("missiles");
        DEFAULTS.forEach((path, defaults) -> {
            builder.translation("create_fire_control.config.missile." + path).push(path);
            ModConfigSpec.DoubleValue life = builder.translation("create_fire_control.config.lifetimeSeconds")
                    .comment("Maximum guided flight lifetime in seconds.")
                    .defineInRange("lifetimeSeconds", defaults.lifetimeSeconds, .05, 3600.0);
            ModConfigSpec.DoubleValue speed = builder.translation(defaults.multiplier
                            ? "create_fire_control.config.speedMultiplier" : "create_fire_control.config.speedBlocksPerSecond")
                    .comment(defaults.multiplier ? "Multiplier of this anti-tank missile's original variable native speed."
                            : "Maximum speed in blocks/second. Ballistic climb/cruise/terminal speeds scale together.")
                    .defineInRange(defaults.multiplier ? "speedMultiplier" : "speedBlocksPerSecond", defaults.speed,
                            defaults.multiplier ? .01 : 1.0, defaults.multiplier ? 20.0 : 10000.0);
            ModConfigSpec.DoubleValue powered = builder.translation("create_fire_control.config.poweredSeconds")
                    .comment("Powered/exhaust duration in seconds. After burnout guidance and coasting continue; changing this scales acceleration time while preserving the default flight profile.")
                    .defineInRange("poweredSeconds", defaults.poweredSeconds, .05, 3600.0);
            VALUES.put(path, new Values(life, speed, powered));
            builder.pop();
        });
        builder.pop();
    }

    public static Map<String, Defaults> defaultsByModel() { return DEFAULTS; }

    public static String model(Entity missile) {
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()).getPath();
        path = switch (path) {
            case "agmmissile_3" -> "agm_3tanshe";
            case "agmmissile_4" -> "agm_4tanshe";
            default -> path;
        };
        return path.equals("ballistic_missile_entity") && missile.getPersistentData().getBoolean("CreateFireControlDf26")
                ? path + "_df26" : path;
    }

    public static boolean adopted(Entity missile) {
        if (missile == null) return false;
        var data = missile.getPersistentData();
        return data.getBoolean("CreateFireControlRemoteVisual") || data.getBoolean("CreateFireControlGuided") || data.getBoolean("CreateFireControlAaGuided")
                || data.getBoolean("CreateFireControlAircraftGuided") || data.getBoolean("CreateFireControlDisplayGuided")
                || data.getBoolean("CreateFireControlCruiseGuided") || data.getBoolean("CreateFireControlBallisticGuided");
    }

    /** Reuses the existing remote age channel for the remaining native missile families. */
    public static void acceptVisualAge(Entity missile, int age) {
        if (missile == null || age < 0 || !DEFAULTS.containsKey(model(missile))) return;
        missile.getPersistentData().putInt("CreateFireControlConfiguredFlightAge", age);
        missile.getPersistentData().putLong("CreateFireControlConfiguredAgeReceived", missile.level().getGameTime());
    }

    public static int visualPacketAge(Entity missile) {
        return adopted(missile) && DEFAULTS.containsKey(model(missile)) ? Math.max(0, missile.tickCount) : -2;
    }

    public static boolean emitting(Entity missile) {
        if (!adopted(missile)) return true;
        var defaults = DEFAULTS.get(model(missile));
        if (defaults == null || poweredTicks(missile) == ticks(defaults.poweredSeconds)) return true;
        var data = missile.getPersistentData();
        long age = missile.level().isClientSide() && data.contains("CreateFireControlConfiguredFlightAge")
                ? data.getInt("CreateFireControlConfiguredFlightAge") + Math.max(0,
                        missile.level().getGameTime() - data.getLong("CreateFireControlConfiguredAgeReceived")) : missile.tickCount;
        return age < poweredTicks(missile);
    }

    public static int lifetimeTicks(Entity missile) { return lifetimeTicks(model(missile)); }
    public static int lifetimeTicks(String path) { return ticks(read(path, 0)); }
    public static boolean supported(Entity missile) { return DEFAULTS.containsKey(model(missile)); }
    public static boolean speedCustomized(Entity missile) {
        var defaults = DEFAULTS.get(model(missile));
        return defaults != null && Double.compare(read(model(missile), 1), defaults.speed) != 0;
    }
    /** Default flights retain the original native timeouts as well as the device's original cap. */
    public static boolean lifetimeCustomized(Entity missile) {
        var defaults = DEFAULTS.get(model(missile));
        return defaults != null && lifetimeTicks(missile) != ticks(defaults.lifetimeSeconds);
    }
    public static int poweredTicks(Entity missile) { return poweredTicks(model(missile)); }
    public static int poweredTicks(String path) { return ticks(read(path, 2)); }
    public static double speedBlocksPerTick(Entity missile) { return speedBlocksPerTick(model(missile)); }
    public static double speedBlocksPerTick(String path) { return read(path, 1) / 20.0; }
    public static double speedScale(Entity missile) { return speedScale(model(missile)); }
    public static double speedScale(String path) { return read(path, 1) / defaultsFor(path).speed; }
    public static double poweredScale(Entity missile) { return poweredScale(model(missile)); }
    public static double poweredScale(String path) { return read(path, 2) / defaultsFor(path).poweredSeconds; }

    /** Keep the original acceleration curve at defaults, with a finite boost for short burns. */
    public static int accelerationTicks(Entity missile, int original) {
        return Math.max(1, (int) Math.round(original * poweredScale(missile)));
    }

    private static Defaults defaultsFor(String path) {
        return DEFAULTS.getOrDefault(path, new Defaults(60, 340.3, 10, false));
    }

    private static double read(String path, int field) {
        Defaults defaults = defaultsFor(path);
        Values values = VALUES.get(path);
        double local = switch (field) {
            case 0 -> defaults.lifetimeSeconds;
            case 1 -> defaults.speed;
            default -> defaults.poweredSeconds;
        };
        if (values != null) {
            ModConfigSpec.DoubleValue value = field == 0 ? values.lifetime : field == 1 ? values.speed : values.powered;
            try { local = value.get(); } catch (IllegalStateException ignored) { /* Bootstrap and standalone checks use defaults. */ }
        }
        String key = field == 0 ? "lifetimeSeconds" : field == 1
                ? defaults.multiplier ? "speedMultiplier" : "speedBlocksPerSecond" : "poweredSeconds";
        return FireControlSettings.number("missiles." + path + "." + key, local);
    }

    private static int ticks(double seconds) { return Math.max(1, (int) Math.round(seconds * 20.0)); }
    private MissileSettings() {}
}
