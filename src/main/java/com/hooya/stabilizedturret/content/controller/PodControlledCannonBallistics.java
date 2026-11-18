package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.RegistryMigration;
import java.util.Locale;

/**
 * Deterministic fire-control parameters used only by cannon mounts that the
 * controller is actively driving from the electro-optical pod.
 *
 * <p>CBC normally derives launch speed from barrel length and the propellant
 * currently loaded in the contraption. That is useful for ordinary guns, but
 * it gives an aircraft fire-control solution too many changing inputs. This
 * table deliberately assigns one launch condition to each cannon family and
 * projectile family. Big cannons are treated as eight standard powder charges
 * (8 * 2 = 16 blocks/tick) regardless of the assembled barrel length.</p>
 */
public final class PodControlledCannonBallistics {
    private static final double EIGHT_CHARGE_SPEED = 16.0;
    private static final double EIGHT_CHARGE_RECOIL = 16.0;

    public static CannonBallisticsCompat.ShotProfile apply(
            CannonBallisticsCompat.ShotProfile source
    ) {
        if (source == null) return null;
        String cannon = source.cannonType().toLowerCase(Locale.ROOT);
        String projectile = normalizeProjectile(source.projectile());
        boolean automatic = cannon.contains("autocannon")
                || cannon.contains("rotarycannon")
                || cannon.contains("rotary_cannon")
                || cannon.contains("mediumcannon")
                || cannon.contains("medium_cannon")
                || cannon.contains("rocketpod")
                || cannon.contains("rocket_pod");

        FixedProjectile fixedProjectile = projectile(projectile, source);
        double speed = automatic
                ? taggedNumber(cannon, "fixed_speed", automaticCannonSpeed(cannon))
                : EIGHT_CHARGE_SPEED + fixedProjectile.addedCharge();
        double recoil = automatic
                ? taggedNumber(cannon, "fixed_recoil", automaticCannonRecoil(cannon))
                        + fixedProjectile.addedRecoil()
                : EIGHT_CHARGE_RECOIL + fixedProjectile.addedRecoil();

        return new CannonBallisticsCompat.ShotProfile(
                source.muzzle(),
                source.currentForward(),
                source.carrierVelocity(),
                speed,
                fixedProjectile.gravity(),
                fixedProjectile.drag(),
                fixedProjectile.quadraticDrag(),
                source.projectile(),
                source.cannonType(),
                0,
                automatic ? 1 : 8,
                source.maxFlightTicks(),
                automatic ? 0.0 : EIGHT_CHARGE_SPEED + fixedProjectile.addedCharge(),
                fixedProjectile.mass(),
                recoil
        );
    }

    private static double automaticCannonSpeed(String cannon) {
        if (cannon.contains("rotarycannon")) {
            if (cannon.contains("nethersteel")) return 9.0;
            if (cannon.contains("steel")) return 7.5;
            return 6.0;
        }
        if (cannon.contains("cast_iron")) return 9.0;
        if (cannon.contains("bronze")) return 7.5;
        if (cannon.contains("steel")) return 9.0;
        // CBC and add-on automatic cannon materials use 9 blocks/tick as the
        // stable full-length reference when their material cannot be named.
        return 9.0;
    }

    private static double automaticCannonRecoil(String cannon) {
        if (cannon.contains("rotarycannon")) {
            return cannon.contains("nethersteel") ? 0.75 : 0.5;
        }
        if (cannon.contains("cast_iron")) return 1.0;
        if (cannon.contains("bronze")) return 2.0;
        if (cannon.contains("steel")) return 3.0;
        return 2.0;
    }

    private static FixedProjectile projectile(
            String id,
            CannonBallisticsCompat.ShotProfile source
    ) {
        String path = id.substring(id.indexOf(':') + 1);
        if (id.startsWith(StabilizedTurret.MOD_ID + ":")
                || id.startsWith(RegistryMigration.LEGACY_MOD_ID + ":")) {
            return modernWarfare(path, source);
        }
        if (id.startsWith("createbigcannons:")) {
            return createBigCannons(path, source);
        }
        if (id.startsWith("cbc_enhanced_shells:")) {
            return enhancedShells(path, source);
        }
        if (id.startsWith("cbc_at:")) {
            return advancedTechnology(path, source);
        }
        return new FixedProjectile(
                source.gravity(),
                source.drag(),
                source.quadraticDrag(),
                source.projectileMass(),
                source.recoil(),
                0.0
        );
    }

    private static FixedProjectile advancedTechnology(
            String path,
            CannonBallisticsCompat.ShotProfile source
    ) {
        return switch (path) {
            // CBC AT ordinary-autocannon projectiles.
            case "apds_projectile" ->
                    fixed(-0.020, 0.010, false, 1.75, 0.70);
            case "apdsfs_projectile" ->
                    fixed(-0.016, 0.00825, false, 1.66, 0.70);
            case "he_projectile", "hei_projectile" ->
                    fixed(-0.025, 0.010, false, 1.00, 0.70);

            // CBC AT heavy-autocannon projectiles. These seven rounds use
            // quadratic drag in 0.1.4c; retaining that flag matters much more
            // at the heavy cannon's fixed full-length muzzle velocity.
            case "ha_ap_projectile" ->
                    fixed(-0.030, 0.013, true, 3.25, 0.66);
            case "ha_apds_projectile" ->
                    fixed(-0.020, 0.011, true, 3.00, 0.66);
            case "ha_apdsfs_projectile" ->
                    fixed(-0.0175, 0.008, true, 2.67, 0.66);
            case "ha_he_projectile" ->
                    fixed(-0.030, 0.013, true, 1.60, 0.66);
            case "ha_hef_projectile" ->
                    fixed(-0.033, 0.013, true, 1.60, 0.66);
            case "ha_heat_projectile" ->
                    fixed(-0.035, 0.012, true, 1.55, 0.66);
            case "ha_smoke_projectile" ->
                    fixed(-0.035, 0.005, true, 0.75, 0.70);
            case "cluster", "cluster_projectile" ->
                    fixed(-0.050, 0.020, false, 1.75, 0.70);

            // Rocket and medium-rocket entities derive their ballistic
            // component from the warhead installed in the cartridge. Keep
            // that instantiated component instead of replacing different
            // warheads with one generic rocket value.
            case "ap_rocket", "flak_rocket", "he_rocket", "hei_rocket",
                    "medium_ap_rocket", "medium_he_rocket",
                    "medium_hef_rocket", "medium_heat_rocket" ->
                    fallback(source);
            default -> fallback(source);
        };
    }

    private static FixedProjectile modernWarfare(
            String path,
            CannonBallisticsCompat.ShotProfile source
    ) {
        return switch (path) {
            case "apds_autocannon" -> fixed(-0.010, 0.01, 3.5, 1.0);
            case "hvap_autocannon" -> fixed(-0.015, 0.01, 2.5, 1.0);
            case "he_autocannon", "canister_autocannon" ->
                    fixed(-0.025, 0.01, 1.0, 1.0);
            case "apds_mediumshell" -> fixed(-0.0325, 0.01, 5.0, 1.0);
            case "apfsds_mediumshell" -> fixed(-0.030, 0.01, 6.0, 1.0);
            case "ap_mediumshell" -> fixed(-0.040, 0.01, 5.0, 1.0);
            case "aphe_mediumshell" -> fixed(-0.040, 0.01, 4.0, 1.0);
            case "he_mediumshell", "hef_mediumshell", "heap_mediumshell",
                    "canister_mediumshell", "smoke_mediumshell" ->
                    fixed(-0.040, 0.01, 1.0, 1.0);
            case "apds_shot" -> fixed(-0.030, 0.01, 8.0, 1.0);
            case "heap_shell", "hefrag_shell" ->
                    fixed(-0.050, 0.01, 2.0, 1.0);
            default -> fallback(source);
        };
    }

    private static FixedProjectile createBigCannons(
            String path,
            CannonBallisticsCompat.ShotProfile source
    ) {
        return switch (path) {
            case "ap_autocannon" -> fixed(-0.025, 0.01, 2.0, 1.0);
            case "flak_autocannon" -> fixed(-0.025, 0.01, 1.0, 1.0);
            case "machine_gun_bullet" -> fixed(-0.025, 0.01, 0.5, 0.5);
            case "ap_shell" -> fixed(-0.050, 0.01, 6.0, 1.0);
            case "ap_shot" -> fixed(-0.050, 0.01, 8.0, 1.0);
            case "shot" -> fixed(-0.050, 0.01, 3.0, 1.0);
            case "mortar_stone" -> fixed(-0.025, 0.01, 4.0, 1.0);
            case "drop_mortar_shell" ->
                    fixed(-0.050, 0.01, 2.0, 1.0, 2.0);
            case "he_shell", "fluid_shell", "shrapnel_shell", "smoke_shell" ->
                    fixed(-0.050, 0.01, 2.0, 1.0);
            case "traffic_cone" -> fixed(-0.050, 0.01, 30.0, 1.0);
            default -> fallback(source);
        };
    }

    private static FixedProjectile enhancedShells(
            String path,
            CannonBallisticsCompat.ShotProfile source
    ) {
        int mark = mark(path);
        if (path.startsWith("bunker_buster_shell_")) {
            return fixed(-0.040, 0.008,
                    switch (mark) {
                        case 1 -> 25.0;
                        case 2 -> 35.0;
                        case 3 -> 45.0;
                        case 4 -> 55.0;
                        default -> 65.0;
                    },
                    1.5);
        }
        if (path.startsWith("mortar_stone_")) {
            return fixed(-0.025, 0.01,
                    markedMass(mark, 12, 20, 40, 60, 80), 1.0);
        }
        if (path.startsWith("drop_mortar_shell_")) {
            return fixed(-0.050, 0.01,
                    markedMass(mark, 6, 10, 20, 30, 40), 1.0, 2.0);
        }
        if (path.equals("rocket")) {
            return fixed(-0.040, 0.01, 10.0, 0.0);
        }
        // Every other fired shell in CBC Enhanced Shells 1.2.2 uses this
        // gravity/drag pair. Keep its exact registered mass because mass does
        // not alter the trajectory integrator but is useful for recoil data.
        return fixed(-0.050, 0.01, source.projectileMass(),
                path.startsWith("bag_of_grapeshot_") ? 1.0 : 1.0);
    }

    private static FixedProjectile fallback(
            CannonBallisticsCompat.ShotProfile source
    ) {
        return new FixedProjectile(
                source.gravity(),
                source.drag(),
                source.quadraticDrag(),
                source.projectileMass(),
                Math.max(0.0, source.recoil()),
                0.0
        );
    }

    private static FixedProjectile fixed(
            double gravity, double drag, double mass, double recoil
    ) {
        return fixed(gravity, drag, mass, recoil, 0.0);
    }

    private static FixedProjectile fixed(
            double gravity,
            double drag,
            boolean quadraticDrag,
            double mass,
            double recoil
    ) {
        return new FixedProjectile(
                gravity, drag, quadraticDrag, mass, recoil, 0.0);
    }

    private static FixedProjectile fixed(
            double gravity,
            double drag,
            double mass,
            double recoil,
            double addedCharge
    ) {
        return new FixedProjectile(
                gravity, drag, false, mass, recoil, addedCharge);
    }

    private static int mark(String path) {
        int index = path.lastIndexOf("_mk");
        if (index < 0 || index + 3 >= path.length()) return 1;
        try {
            return Math.max(1, Math.min(5,
                    Integer.parseInt(path.substring(index + 3))));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static double markedMass(
            int mark, double m1, double m2, double m3, double m4, double m5
    ) {
        return switch (mark) {
            case 1 -> m1;
            case 2 -> m2;
            case 3 -> m3;
            case 4 -> m4;
            default -> m5;
        };
    }

    private static double taggedNumber(String description, String key, double fallback) {
        String marker = "|" + key + "=";
        int start = description.indexOf(marker);
        if (start < 0) return fallback;
        start += marker.length();
        int end = description.indexOf('|', start);
        String raw = end < 0
                ? description.substring(start)
                : description.substring(start, end);
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) && value > 0.0 ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String normalizeProjectile(String projectile) {
        if (projectile == null || projectile.isBlank()) return "unknown:unknown";
        String result = projectile.toLowerCase(Locale.ROOT);
        if (!result.contains(":")) result = "unknown:" + result;
        if (result.endsWith("_round")) {
            result = result.substring(0, result.length() - "_round".length());
        }
        return result;
    }

    private record FixedProjectile(
            double gravity,
            double drag,
            boolean quadraticDrag,
            double mass,
            double addedRecoil,
            double addedCharge
    ) {
    }

    private PodControlledCannonBallistics() {
    }
}
