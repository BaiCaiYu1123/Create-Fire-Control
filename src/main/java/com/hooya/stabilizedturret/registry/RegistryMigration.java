package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Keeps every registry entry from releases that used the
 * {@code stabilized_turret} namespace resolvable after the mod-id rename.
 *
 * <p>NeoForge registry aliases are used instead of registering duplicate
 * blocks or items. Consequently old level palettes, block entities, item
 * stacks and Sable/Create schematic palettes all resolve to the one current
 * object registered as {@code create_fire_control:*}.</p>
 */
public final class RegistryMigration {
    public static final String LEGACY_MOD_ID = "stabilized_turret";

    public static <T> void addLegacyAliases(DeferredRegister<T> register) {
        if (!StabilizedTurret.MOD_ID.equals(register.getNamespace())) {
            throw new IllegalArgumentException("Cannot migrate foreign registry namespace: "
                    + register.getNamespace());
        }
        register.getEntries().forEach(entry ->
                register.addAlias(legacyId(entry.getId()), entry.getId()));
    }

    public static ResourceLocation legacyId(ResourceLocation currentId) {
        if (!StabilizedTurret.MOD_ID.equals(currentId.getNamespace())) {
            throw new IllegalArgumentException("Not a current Fire Control id: " + currentId);
        }
        return ResourceLocation.fromNamespaceAndPath(LEGACY_MOD_ID, currentId.getPath());
    }

    private RegistryMigration() {
    }
}
