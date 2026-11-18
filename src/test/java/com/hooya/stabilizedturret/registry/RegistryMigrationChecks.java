package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Standalone regression checks for the old-id registry alias migration. */
public final class RegistryMigrationChecks {
    public static void main(String[] args) {
        require("create_fire_control".equals(StabilizedTurret.MOD_ID),
                "The current mod id changed unexpectedly");
        require("stabilized_turret".equals(RegistryMigration.LEGACY_MOD_ID),
                "The legacy namespace changed unexpectedly");

        List<String> migratedPaths = List.of(
                "stabilizer_controller", "connected_display", "gas_turbine",
                "mz_loader", "az_loader", "medium_cannon_breech",
                "mz_cannon_breech", "apfsds_medium_cannon_round",
                "apfsds_medium_cannon_cartridge", "apfsds_medium_cannon_casing",
                "apfsds_medium_shell", "mianbao_autocannon", "main");
        for (String path : migratedPaths) {
            ResourceLocation current = ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, path);
            ResourceLocation expectedLegacy = ResourceLocation.fromNamespaceAndPath(
                    RegistryMigration.LEGACY_MOD_ID, path);
            require(expectedLegacy.equals(RegistryMigration.legacyId(current)),
                    "Legacy alias changed the path for " + current);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private RegistryMigrationChecks() {
    }
}
