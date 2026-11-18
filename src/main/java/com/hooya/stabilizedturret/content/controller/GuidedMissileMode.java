package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.function.BooleanSupplier;

/** Instance-wide config default, captured once per shot. Switching never changes an in-flight shot. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class GuidedMissileMode {
    public static final String ROUTE = "CreateFireControlMissileFlightRoute";
    static final String ROUTE_POLICY_TAG = "CreateFireControlMissileFlightRoutePolicy";
    static final int DEFAULT_POLICY_VERSION = 1;

    public static boolean eligible(Entity missile) {
        if (missile == null) return false;
        var type = MianbaoAircraftCompat.typeOf(missile);
        return MianbaoAirDefenseCompat.isHighSpeedMissile(missile)
                || type != null && !MianbaoAircraftCompat.isOpticalGuided(type)
                || MianbaoCruiseMissileCompat.isMissile(missile);
    }

    public static boolean mathematical(Entity missile) {
        if (missile == null || missile.level().isClientSide() || !eligible(missile)) return false;
        if (MianbaoAircraftCompat.isAntiRadiation(MianbaoAircraftCompat.typeOf(missile))) {
            missile.getPersistentData().putString(ROUTE, "math");
            missile.getPersistentData().putInt(ROUTE_POLICY_TAG, DEFAULT_POLICY_VERSION);
            return true;
        }
        return captureRoute(missile.getPersistentData(), FireControlSettings::mathematicalMissileFlight);
    }

    static boolean captureRoute(CompoundTag tag, BooleanSupplier defaultMode) {
        String route = tag.getString(ROUTE);
        if (tag.getInt(ROUTE_POLICY_TAG) < DEFAULT_POLICY_VERSION
                || !(route.equals("math") || route.equals("entity"))) {
            // Old entity shots restored from disk also adopt the new default.
            // Once selected under this policy, an in-flight route stays fixed.
            route = defaultMode.getAsBoolean() ? "math" : "entity";
            tag.putString(ROUTE, route);
            tag.putInt(ROUTE_POLICY_TAG, DEFAULT_POLICY_VERSION);
        }
        return route.equals("math");
    }

    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cfc").requires(source -> source.hasPermission(2))
                .then(Commands.literal("missile")
                        .then(Commands.literal("mode").executes(context -> {
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "commands.create_fire_control.missile_mode.current", modeName(FireControlSettings.mathematicalMissileFlight()),
                                    MathematicalMissileManager.activeCount()), false);
                            return 1;
                        }).then(Commands.literal("math").executes(context -> set(context.getSource(), true)))
                          .then(Commands.literal("entity").executes(context -> set(context.getSource(), false))))));
    }

    private static int set(net.minecraft.commands.CommandSourceStack source, boolean math) {
        // Compatibility aliases update the same TOML setting as the menu; no world SavedData remains.
        FireControlSettings.saveServerChanges(java.util.Map.of("flight.trajectoryMode", math
                ? FireControlSettings.TrajectoryMode.MATHEMATICAL : FireControlSettings.TrajectoryMode.ENTITY));
        source.sendSuccess(() -> Component.translatable("commands.create_fire_control.missile_mode.saved", modeName(math)), true);
        return 1;
    }

    private static Component modeName(boolean math) {
        return Component.translatable("config.create_fire_control.enum." + (math ? "mathematical" : "entity"));
    }

    private GuidedMissileMode() {}
}
