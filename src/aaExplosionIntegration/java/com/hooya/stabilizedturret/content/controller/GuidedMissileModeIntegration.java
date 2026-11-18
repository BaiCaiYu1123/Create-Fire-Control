package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** Real join-event routing from global configuration, with restart and compatibility-command checks. */
final class GuidedMissileModeIntegration {
    static void verify(ServerLevel level) {
        boolean reload = Boolean.getBoolean("create_fire_control.modePolicyReload");
        boolean initialMath = !reload;
        require(FireControlSettings.mathematicalMissileFlight() == initialMath, "Global file startup mode is wrong");
        level.getChunk(0, 0);
        List<String> supported = List.of("antiairmissiletanshe", "farmissile_1tanshe", "farmissile_2tanshe",
                "missile_2tanshe", "medium_aim_missile_2tanshe", "closemissile_1tanshe", "closemissile_2tanshe",
                "mica_em_tanshe", "aim_120_tanshe", "agm_1tanshe", "agm_2tanshe", "agm_3tanshe", "agm_4tanshe",
                "missiletanshe", "cruisemissiletanshe", "cruisemissilelowtanshe", "nuclearcruisemissiletanshe",
                "groundnuclearcruisemissiletanshe", "groundmissiletanshe", "groundmissile_2tanshe",
                "groundmissile_3tanshe", "groundmissile_4tanshe");
        for (String path : supported) {
            Entity missile = create(level, path);
            require(GuidedMissileMode.eligible(missile), path + " lost its supported mathematical route");
            require(level.addFreshEntity(missile) != initialMath, path + " startup default is wrong");
            require(MathematicalMissileManager.contains(missile) == initialMath, path + " wrong actual backend");
            missile.discard();
        }
        Entity legacyShot = create(level, "aim_120_tanshe");
        legacyShot.getPersistentData().putString(GuidedMissileMode.ROUTE, "entity");
        require(level.addFreshEntity(legacyShot) != initialMath, "Unversioned saved entity shot ignored the new policy");
        require(GuidedMissileMode.mathematical(legacyShot) == initialMath, "Old shot route was not migrated");
        legacyShot.discard();

        for (String path : List.of("portableantiairmissiletanshe", "portableantiairmissiletanshe_2", "opticalguidedmissiletanshe")) {
            Entity unsupported = create(level, path);
            require(!GuidedMissileMode.eligible(unsupported), path + " was unexpectedly added to the math backend");
            require(level.addFreshEntity(unsupported) && !GuidedMissileMode.mathematical(unsupported),
                    path + " changed its unsupported flight route");
            require(!unsupported.getPersistentData().contains(GuidedMissileMode.ROUTE_POLICY_TAG),
                    "Ineligible missile received a flight policy tag");
            unsupported.discard();
        }
        Entity nativeOnly = create(level, "aim_120_tanshe");
        nativeOnly.getPersistentData().remove("CreateFireControlAircraftGuided");
        require(level.addFreshEntity(nativeOnly) && !MathematicalMissileManager.contains(nativeOnly),
                "An unadopted native missile was captured");
        nativeOnly.discard();

        command(level, "entity");
        for (String path : supported) {
            Entity missile = create(level, path);
            require(level.addFreshEntity(missile) && !GuidedMissileMode.mathematical(missile),
                    path + " ignored the explicit entity command");
            missile.discard();
        }
        Entity entityShot = create(level, "aim_120_tanshe");
        require(level.addFreshEntity(entityShot), "Explicit entity shot was detached");
        command(level, "math");
        require(!GuidedMissileMode.mathematical(entityShot), "Math command changed an in-flight entity shot");
        Entity mathShot = create(level, "aim_120_tanshe");
        require(!level.addFreshEntity(mathShot) && MathematicalMissileManager.contains(mathShot),
                "Math command did not restore detached flight");
        command(level, "entity");
        require(GuidedMissileMode.mathematical(mathShot), "Entity command changed an in-flight math shot");
        entityShot.discard(); mathShot.discard();
        // First process leaves an explicit entity choice for the restart check.
        // The second process leaves math enabled and confirms clearing the opt-in.
        if (reload) command(level, "math");
        StabilizedTurret.LOGGER.info("MODE POLICY PASS: startupMath={}, {} supported types, legacy shot, 3 exclusions, native-only shot, actual commands and pinned in-flight routes",
                initialMath, supported.size());
    }

    private static Entity create(ServerLevel level, String path) {
        Entity missile = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("mianbaos_modernwarfare:" + path)).create(level);
        require(missile != null, "Missing native missile " + path);
        missile.setPos(8.5, 240, 8.5); missile.setDeltaMovement(1, 0, 0); missile.noPhysics = true;
        missile.setInvulnerable(true); missile.setNoGravity(true);
        missile.getPersistentData().putBoolean("jamed", true);
        missile.getPersistentData().putBoolean("CreateFireControlAircraftGuided", MianbaoAircraftCompat.isMissile(missile));
        missile.getPersistentData().putBoolean("CreateFireControlAaGuided", MianbaoAirDefenseCompat.isHighSpeedMissile(missile));
        missile.getPersistentData().putBoolean("CreateFireControlCruiseGuided", MianbaoCruiseMissileCompat.isMissile(missile));
        return missile;
    }

    private static void command(ServerLevel level, String mode) {
        FireControlSettings.saveServerChanges(java.util.Map.of("flight.trajectoryMode",
                mode.equals("math") ? "MATHEMATICAL" : "ENTITY"));
        require(FireControlSettings.mathematicalMissileFlight() == mode.equals("math"), "Config selection was not applied");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
