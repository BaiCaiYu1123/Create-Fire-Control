package com.hooya.stabilizedturret.client;

import net.minecraft.client.particle.Particle;

import java.util.Locale;

/** Keeps visible-spectrum cannon exhaust from being drawn by the thermal optic. */
public final class ThermalSmokeFilter {
    public static boolean shouldHide(Particle particle) {
        if (!ClientAimState.isThermal() || particle == null) {
            return false;
        }
        String name = particle.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("rbasamoyai.createbigcannons.effects.particles.")) {
            return name.contains(".smoke.") || name.contains(".plumes.");
        }
        // CBC add-ons commonly provide their own muzzle effects. Restrict the
        // fallback to cannon/warfare namespaces so ordinary fog and weather
        // particles are not accidentally removed.
        boolean cannonAddon = name.contains("cbc") || name.contains("cannon")
                || name.contains("warfare") || name.contains("myfirstmod");
        if (!cannonAddon) {
            return false;
        }
        return name.contains("smoke")
                || name.contains("plume")
                || name.contains("rocketmissiletrail")
                || name.contains("countermeasuretrail");
    }

    private ThermalSmokeFilter() {
    }
}
