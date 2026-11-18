package com.hooya.stabilizedturret.config;

import com.hooya.stabilizedturret.content.controller.MianbaoMissileFlightProfile;
import net.minecraft.nbt.CompoundTag;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exercises defaults, independent variants, authoritative snapshots, and real effect windows. */
public final class MissileSettingsChecks {
    public static void main(String[] args) {
        // Initialising the owner registers all values in the same spec used by the GUI and disk file.
        var spec = FireControlSettings.SERVER_SPEC;
        for (var entry : MissileSettings.defaultsByModel().entrySet()) {
            String model = entry.getKey(); var defaults = entry.getValue();
            require(MissileSettings.lifetimeTicks(model) == Math.round(defaults.lifetimeSeconds() * 20), "Lifetime default: " + model);
            near(MissileSettings.speedScale(model), 1, "Speed default: " + model);
            require(MissileSettings.poweredTicks(model) == Math.round(defaults.poweredSeconds() * 20), "Burn default: " + model);
            require(spec.getValues().get("missiles." + model + ".lifetimeSeconds") != null, "Model absent from config: " + model);
        }
        require(MissileSettings.defaultsByModel().size() == 33, "Missing registered model or exposed obsolete alias");
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("missiles.farmissile_1tanshe.lifetimeSeconds", 1.25);
        values.put("missiles.farmissile_1tanshe.speedBlocksPerSecond", 120.0);
        values.put("missiles.farmissile_1tanshe.poweredSeconds", .25);
        values.put("missiles.antitankmissile_1tanshe.speedMultiplier", 2.5);
        values.put("missiles.groundmissile_4tanshe.lifetimeSeconds", 600.0);
        values.put("missiles.groundmissile_4tanshe.poweredSeconds", 80.0);
        values.put("missiles.ballistic_missile_entity_df26.speedBlocksPerSecond", 2041.8);
        FireControlSettings.receiveSnapshot(FireControlSettings.encode(values), false);
        require(MissileSettings.lifetimeTicks("farmissile_1tanshe") == 25, "Server lifetime not used");
        near(MissileSettings.speedBlocksPerTick("farmissile_1tanshe"), 6, "Server speed not used");
        require(MissileSettings.poweredTicks("farmissile_1tanshe") == 5, "Server burn not used");
        require(MissileSettings.lifetimeTicks("farmissile_2tanshe") == 2400, "Sibling model changed");
        near(MissileSettings.speedScale("antitankmissile_1tanshe"), 2.5, "Native multiplier not applied");
        near(MissileSettings.speedScale("antitankmissile_2tanshe"), 1, "Sibling native multiplier changed");
        require(MissileSettings.lifetimeTicks("groundmissile_4tanshe") == 12000, "Long lifetime truncated");
        near(MissileSettings.speedScale("ballistic_missile_entity_df26"), 2, "DF26 speed missing");
        near(MissileSettings.speedScale("ballistic_missile_entity"), 1, "Other ballistic model changed");
        var shortTrail = MianbaoMissileFlightProfile.of("farmissile_1tanshe");
        require(shortTrail.smoke(4) && !shortTrail.smoke(5), "Short configured exhaust boundary");
        var longTrail = MianbaoMissileFlightProfile.of("groundmissile_4tanshe");
        require(longTrail.smoke(1599) && !longTrail.smoke(1600), "Long configured exhaust boundary");
        FireControlSettings.clearRemoteView();
        require(MissileSettings.lifetimeTicks("farmissile_1tanshe") == 2400, "Local lifetime not restored");
        near(MissileSettings.speedScale("ballistic_missile_entity_df26"), 1, "Local speed not restored");
        require(MianbaoMissileFlightProfile.of("groundmissile_4tanshe").smoke(999), "Default land trail changed");
        System.out.println("PASS: 33 registered missile defaults, per-variant lifetime/speed/burn edits, authoritative effect windows and disconnect restore");
    }
    private static void near(double actual,double expected,String message) { require(Math.abs(actual-expected)<1e-8,message); }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
