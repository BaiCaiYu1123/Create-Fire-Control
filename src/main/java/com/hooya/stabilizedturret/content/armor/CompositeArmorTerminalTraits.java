package com.hooya.stabilizedturret.content.armor;

import com.cbc_terminal_ballistics.data.MaterialStats;

/** Loaded only by the optional CTB mixins. */
public final class CompositeArmorTerminalTraits {
    private CompositeArmorTerminalTraits() {
    }

    public static MaterialStats scale(MaterialStats stats, int level) {
        if (level == ArmorUpgrade.DEFAULT_LEVEL) return stats;
        return new MaterialStats(stats.surface(), stats.toughnessMultiplier(),
                stats.brittleness() * level, stats.ductility() * level, stats.spallMultiplier() * level,
                stats.caliberDamageScale());
    }
}
