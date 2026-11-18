package com.hooya.stabilizedturret.client;

import net.createmod.catnip.config.ui.ConfigScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;

/** Translation keys stay independent of config paths and persisted enum values. */
final class FireControlConfigLanguage {
    static MutableComponent label(List<String> path, String explicitKey) {
        if (explicitKey != null && I18n.exists(explicitKey)) return Component.translatable(explicitKey);
        String key = "config.create_fire_control." + String.join(".", path);
        if (I18n.exists(key)) return Component.translatable(key);
        String leaf = path.get(path.size() - 1);
        String leafKey = "config.create_fire_control." + leaf;
        return I18n.exists(leafKey) ? Component.translatable(leafKey)
                : Component.literal(ConfigScreen.toHumanReadable(leaf));
    }

    static String tooltipKey(List<String> path, String explicitKey) {
        String[] keys = {
                explicitKey == null ? "" : explicitKey + ".tooltip",
                "config.create_fire_control." + String.join(".", path) + ".tooltip",
                "config.create_fire_control." + path.get(path.size() - 1) + ".tooltip"
        };
        for (String key : keys) if (I18n.exists(key)) return key;
        return null;
    }

    static MutableComponent value(Object value) {
        if (value instanceof Enum<?> enumeration) {
            String key = "config.create_fire_control.enum." + enumeration.name().toLowerCase(Locale.ROOT);
            return I18n.exists(key) ? Component.translatable(key)
                    : Component.literal(ConfigScreen.toHumanReadable(enumeration.name().toLowerCase(Locale.ROOT)));
        }
        if (value instanceof Boolean flag) return Component.translatable(flag ? "options.on" : "options.off");
        return Component.literal(String.valueOf(value));
    }

    private FireControlConfigLanguage() {}
}
