package com.hooya.stabilizedturret.content.controller;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import java.util.Map;

/** Known upstream weapon renames. Keeps saved bindings across Mianbao's ID migration. */
final class MianbaoLauncherIdMigration {
    private static final Map<String, String> RENAMES = Map.ofEntries(
            Map.entry("weaponcontrolsystem_1", "weapon_station_type_1_sand"),
            Map.entry("weaponcontrolsystem_2", "weapon_station_type_1_green"),
            Map.entry("antiairmissilelauncherblock", "antiair_missile_launcher_type_1"),
            Map.entry("antiairmissilelauncherleft", "antiair_missile_launcher_type_2_left"),
            Map.entry("antiairmissilelauncherright", "antiair_missile_launcher_type_2_right"),
            Map.entry("antiairmissilelauncherthird_h_1", "antiair_missile_launcher_type_3_green"),
            Map.entry("antiairmissilelauncherthird_h_2", "antiair_missile_launcher_type_3_green"),
            Map.entry("antiairmissilelauncherthird_h_1sand", "antiair_missile_launcher_type_3_sand"),
            Map.entry("antiairmissilelauncherthird_h_2sand", "antiair_missile_launcher_type_3_sand"),
            Map.entry("antitankemissilelauncher", "antitank_missile_launcher_type_1"),
            Map.entry("antitankemissilelauncherh_2", "antitank_missile_launcher_type_1"),
            Map.entry("antitankmissilelaunchererxing_left", "antitank_missile_launcher_type_2_left"),
            Map.entry("antitankmissilelaunchererxing_right", "antitank_missile_launcher_type_2_right"),
            Map.entry("antitankmissilelaunchersanxing", "antitank_missile_launcher_type_3"),
            Map.entry("antitankmissilelaunchersixing", "antitank_missile_launcher_type_4"));

    private MianbaoLauncherIdMigration() { }

    private static ResourceLocation canonical(ResourceLocation id) {
        String path = RENAMES.get(id.getPath());
        return path == null ? id : ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }

    static boolean isRenamedLauncher(@Nullable ResourceLocation first, @Nullable ResourceLocation second) {
        return first != null && second != null && !first.equals(second)
                && first.getNamespace().equals("mianbaos_modernwarfare")
                && second.getNamespace().equals(first.getNamespace())
                && canonical(first).equals(canonical(second));
    }
}
