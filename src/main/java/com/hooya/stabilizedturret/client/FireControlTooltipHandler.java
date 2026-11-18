package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.caca.copypoly.PolyItem;
import com.hooya.stabilizedturret.content.armor.ArmorUpgrade;
import com.hooya.stabilizedturret.content.controller.MianbaoAirDefenseCompat;
import com.hooya.stabilizedturret.content.controller.MianbaoRocketPodCompat;
import com.hooya.stabilizedturret.registry.ModItems;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.foundation.item.TooltipHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class FireControlTooltipHandler {
    private static final Style DESCRIPTION = TooltipHelper.styleFromColor(ChatFormatting.GRAY);
    private static final Style HIGHLIGHT = TooltipHelper.styleFromColor(ChatFormatting.WHITE);
    private static final int MAX_SECTIONS = 12;

    private static final Set<String> MIANBAO_ANTI_TANK_LAUNCHERS = Set.of(
            "antitankemissilelauncher",
            "antitankemissilelauncherh_2",
            "antitankmissilelaunchererxing_right",
            "antitankmissilelaunchererxing_left",
            "antitankmissilelaunchersixing",
            "antitankmissilelaunchersanxing",
            "antitank_missile_launcher_type_1",
            "antitank_missile_launcher_type_2_left",
            "antitank_missile_launcher_type_2_right",
            "antitank_missile_launcher_type_3",
            "antitank_missile_launcher_type_4"
    );
    private static final Set<String> MIANBAO_AIRCRAFT_RACKS = Set.of(
            "rack",
            "far_missile_1rack",
            "far_missile_2rack",
            "medium_missile_rack",
            "medium_ai_mmissile_2rack",
            "close_missile_1rack",
            "close_missile_2rack",
            "agm_missile_1rack",
            "agm_missile_2rack",
            "agm_missile_3rack",
            "agm_missile_4rack",
            "laser_guided_missile_rack",
            "mica_em_rack",
            "aim_120_rack"
    );
    private static final Set<String> MIANBAO_DIRECT_MISSILE_BLOCKS = Set.of(
            "cruisemissileblock",
            "cruisemissilelowblock",
            "nuclearcruisemissile",
            "groundnuclearcruisemissile",
            "groundmissile_1",
            "groundmissile_2",
            "groundmissile_3",
            "groundmissile_4"
    );
    private static final Set<String> MIANBAO_VLS_MISSILES = Set.of(
            "cruisemissileitem",
            "cruisemissilelowitem",
            "nuclearcruisemissileitem",
            "groundnuclearcruisemissileitem",
            "groundmissile_1item",
            "groundmissile_2item",
            "groundmissile_3item",
            "groundmissile_4item"
    );
    private static final Set<String> MIANBAO_AUTOCANNON_BREECHES = Set.of(
            "autocannonbreech",
            "rotarycannonbreech"
    );
    private static final Set<String> MIANBAO_AUTOCANNON_BARRELS = Set.of(
            "autocannonwidebarrel",
            "autocannonmidbarrel",
            "autocannonfinebarrel",
            "autocannonmuzzlebrake",
            "rotarycannonbarrel",
            "rotarygunbarrel"
    );

    /**
     * LOWEST is intentional: the owning mod gets to build its complete tooltip first,
     * then this handler appends the fire-control compatibility section without replacing it.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().getItem() instanceof PolyItem) {
            event.getToolTip().add(Component.translatable("tooltip.create_fire_control.armor_level",
                    ArmorUpgrade.level(event.getItemStack()), ArmorUpgrade.MAX_LEVEL).withStyle(ChatFormatting.GOLD));
            return;
        }
        if (event.getItemStack().is(ModItems.COMPOSITE_ARMOR_PLATE.get())) {
            for (String method : List.of("smithing", "pressing", "mixing")) {
                event.getToolTip().add(Component.translatable("tooltip.create_fire_control.composite_armor_plate." + method)
                        .withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        if (event.getItemStack().is(ModItems.STABILIZER_CONTROLLER.get())) {
            addOriginalFireControlTooltip(event);
            return;
        }
        if (event.getItemStack().is(ModItems.CONNECTED_DISPLAY.get())) {
            addCreateSummary(event, "connected_display");
            return;
        }
        if (event.getItemStack().is(ModItems.CONNECTED_DISPLAY_CONSOLE.get())) {
            addCreateSummary(event, "connected_display_console");
            return;
        }

        Item item = event.getItemStack().getItem();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        String profile = compatibilityProfile(item, itemId);
        if (profile == null) {
            return;
        }

        List<Component> tooltip = event.getToolTip();
        boolean shiftDown = Screen.hasShiftDown();
        boolean alreadyHasShiftSummary = hasShiftPrompt(tooltip);

        if (!alreadyHasShiftSummary) {
            tooltip.add(Component.empty());
            tooltip.add(shiftPrompt(shiftDown));
        }
        if (!shiftDown) {
            return;
        }

        // The original mod's expanded Shift summary is already present at LOWEST priority.
        // Our clearly labelled compatibility section is appended after it.
        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("tooltip.create_fire_control.compat.title")
                .withStyle(ChatFormatting.GOLD));
        addProfile(tooltip, profile);
    }

    private static void addOriginalFireControlTooltip(ItemTooltipEvent event) {
        event.getToolTip().add(Component.empty());
        if (!Screen.hasShiftDown()) {
            event.getToolTip().add(Component.translatable("tooltip.create_fire_control.hold_shift")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        addOriginalSection(event, "stabilization", "tooltip.create_fire_control.fire_control.stabilization");
        addOriginalSection(event, "aiming", "tooltip.create_fire_control.fire_control.aiming");
        addOriginalSection(event, "binding", "tooltip.create_fire_control.fire_control.binding");
        addOriginalSection(event, "remote", "tooltip.create_fire_control.fire_control.remote");
        addFireControlDetail(event, "ballistics");
        addFireControlDetail(event, "optics");
        addFireControlDetail(event, "anti_tank");
        addFireControlDetail(event, "air_defense");
        addFireControlDetail(event, "aircraft");
        addFireControlDetail(event, "electro_optical");
        addFireControlDetail(event, "inputs");
        addFireControlDetail(event, "configuration");
    }

    private static void addOriginalSection(ItemTooltipEvent event, String title, String body) {
        event.getToolTip().add(Component.translatable(
                        "tooltip.create_fire_control.fire_control." + title + ".title")
                .withStyle(ChatFormatting.GOLD));
        event.getToolTip().add(Component.literal("  ").append(Component.translatable(body))
                .withStyle(ChatFormatting.GRAY));
    }

    private static void addFireControlDetail(ItemTooltipEvent event, String section) {
        String baseKey = "tooltip.create_fire_control.fire_control.details." + section;
        event.getToolTip().add(Component.translatable(baseKey + ".title")
                .withStyle(ChatFormatting.GOLD));
        addWrapped(event.getToolTip(), baseKey, 1);
    }

    private static void addCreateSummary(ItemTooltipEvent event, String profile) {
        List<Component> tooltip = event.getToolTip();
        boolean shiftDown = Screen.hasShiftDown();
        tooltip.add(Component.empty());
        tooltip.add(shiftPrompt(shiftDown));
        if (!shiftDown) return;
        tooltip.add(Component.empty());
        addProfileFromBase(tooltip, "tooltip.create_fire_control." + profile);
    }

    private static String compatibilityProfile(Item item, ResourceLocation itemId) {
        String namespace = itemId.getNamespace();
        String path = itemId.getPath();
        return switch (namespace) {
            case "create_fire_control" -> path.equals("wireless_signal_processor")?"controller_hub":null;
            case "create" -> createProfile(item, path);
            case "create_tweaked_controllers" ->
                    path.equals("tweaked_linked_controller") ? "remote_controller" : null;
            case "drivebywire" -> switch (path) {
                case "controller_hub", "tweaked_controller_hub" -> "controller_hub";
                default -> null;
            };
            case "interiors" -> isInteriorsSeat(path) ? "operator_seat" : null;
            case "simulated" -> path.equals("swivel_bearing") ? "physical_bearing" : null;
            case "createbigcannons" -> createBigCannonsProfile(item, path);
            case "cbcmodernwarfare" -> path.equals("compact_mount") ? "cannon_mount" : null;
            case "mianbaos_modernwarfare" -> mianbaoProfile(itemId);
            case "yuushya" -> switch (path) {
                case "itemblock", "showblock" -> "yuushya_barrel";
                default -> null;
            };
            case "sable_schematic_api" -> switch (path) {
                case "blueprint_tool", "survival_blueprint" -> "blueprint";
                default -> null;
            };
            default -> null;
        };
    }

    private static String createProfile(Item item, String path) {
        return switch (path) {
            case "wrench" -> "binding_tool";
            case "linked_controller" -> "remote_controller";
            case "mechanical_bearing" -> "radar_bearing";
            default -> item instanceof BlockItem blockItem && blockItem.getBlock() instanceof SeatBlock
                    ? "operator_seat"
                    : null;
        };
    }

    private static String createBigCannonsProfile(Item item, String path) {
        if (path.equals("cannon_mount") || path.equals("fixed_cannon_mount")) {
            return "cannon_mount";
        }
        if (hasTypeInHierarchy(item, "rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem")) {
            return "autocannon_ammo";
        }
        if (hasTypeInHierarchy(item,
                "rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem")) {
            return "autocannon_ammo_container";
        }
        return null;
    }

    private static String mianbaoProfile(ResourceLocation itemId) {
        String path = itemId.getPath();
        if (MIANBAO_ANTI_TANK_LAUNCHERS.contains(path)) return "antitank_launcher";
        if (isMianbaoAntiTankMissile(path)) return "antitank_missile";
        if (MianbaoAirDefenseCompat.isLauncherId(itemId)) return "air_defense_launcher";
        if (isMianbaoAirDefenseMissile(path)) return "air_defense_missile";
        if (path.equals("electroopticalpod")
                || path.equals("electroopticalpod_2")
                || path.equals("electroopticalpod_3")) {
            return "electro_optical_pod";
        }
        if (path.equals("jetradar")) return "aircraft_radar";
        String aircraftMissile = aircraftMissileProfile(path);
        if (aircraftMissile != null) return aircraftMissile;
        if (MIANBAO_AIRCRAFT_RACKS.contains(path)
                || com.hooya.stabilizedturret.content.controller.MianbaoBombCompat.rackIds().contains(itemId)) return "aircraft_missile_rack";
        if (MianbaoRocketPodCompat.isPodId(itemId)) return "aircraft_rocket_pod";
        if (MIANBAO_DIRECT_MISSILE_BLOCKS.contains(path)) return "cruise_launcher";
        if (path.equals("groundmissilelauncherhead") || path.equals("groundmissilelaunchertail")) {
            return "vls_structure";
        }
        if (MIANBAO_VLS_MISSILES.contains(path)) return switch (path) {
            case "cruisemissileitem" -> "cruise_missile";
            case "cruisemissilelowitem" -> "low_altitude_cruise_missile";
            case "nuclearcruisemissileitem" -> "nuclear_cruise_missile";
            case "groundnuclearcruisemissileitem" -> "ground_nuclear_missile";
            case "groundmissile_1item" -> "ground_he_missile";
            case "groundmissile_2item" -> "ground_cluster_missile";
            case "groundmissile_3item" -> "ground_cruise_missile";
            case "groundmissile_4item" -> "ground_low_cruise_missile";
            default -> null;
        };
        if (MIANBAO_AUTOCANNON_BREECHES.contains(path)) return "autocannon_breech";
        if (MIANBAO_AUTOCANNON_BARRELS.contains(path)) return "autocannon_barrel";
        return null;
    }

    private static boolean isMianbaoAntiTankMissile(String path) {
        return path.startsWith("antitankmissile") && !path.contains("launcher");
    }

    private static boolean isMianbaoAirDefenseMissile(String path) {
        return path.startsWith("antiairmissile") && !path.contains("launcher");
    }

    private static String aircraftMissileProfile(String path) {
        if (path.equals("mica_em") || path.equals("mica_em_rack")) {
            return "aircraft_mica_em_missile";
        }
        if (path.equals("aim_120") || path.equals("aim_120_rack")) {
            return "aircraft_aim_120d_missile";
        }
        String compact = path.replace("_", "");
        if (!compact.contains("missile")) return null;
        if (compact.contains("farmissile1")) return "aircraft_far_1_missile";
        if (compact.contains("farmissile2")) return "aircraft_far_2_missile";
        if (compact.contains("mediumaimissile2") || compact.contains("mediumaimmissile2")) {
            return "aircraft_medium_ai_2_missile";
        }
        if (compact.contains("mediummissile") || compact.equals("missile2")
                || compact.equals("missile2item")) return "aircraft_medium_missile";
        if (compact.contains("closemissile1")) return "aircraft_close_1_missile";
        if (compact.contains("closemissile2")) return "aircraft_close_2_missile";
        if (compact.contains("agmmissile1")) return "aircraft_agm_1_missile";
        if (compact.contains("agmmissile2")) return "aircraft_agm_2_missile";
        if (compact.contains("agmmissile3")) return "aircraft_agm_1_missile";
        if (compact.contains("agmmissile4")) return "aircraft_agm_2_missile";
        if (compact.contains("laserguidedmissile")) return "aircraft_laser_guided_missile";
        return null;
    }

    private static boolean isInteriorsSeat(String path) {
        return path.equals("kelp_seat") || path.endsWith("_chair") || path.endsWith("_floor_chair");
    }

    private static boolean hasTypeInHierarchy(Item item, String expectedClassName) {
        for (Class<?> type = item.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(expectedClassName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasShiftPrompt(List<Component> tooltip) {
        String createPrompt = shiftPrompt(false).getString();
        String shiftName = Component.translatable("create.tooltip.keyShift")
                .getString()
                .toLowerCase(Locale.ROOT);
        for (Component line : tooltip) {
            String text = line.getString();
            if (text.equals(createPrompt)) {
                return true;
            }
            String normalized = text.toLowerCase(Locale.ROOT);
            if (!shiftName.isBlank() && normalized.contains(shiftName)
                    && (normalized.contains("hold") || normalized.contains("按住")
                    || normalized.contains("概要") || normalized.contains("summary")
                    || normalized.contains("description") || normalized.contains("详细"))) {
                return true;
            }
        }
        return false;
    }

    /** Matches Create's own inactive/active Shift summary prompt. */
    private static Component shiftPrompt(boolean shiftDown) {
        return Component.translatable(
                        "create.tooltip.holdForDescription",
                        Component.translatable("create.tooltip.keyShift")
                                .withStyle(shiftDown ? ChatFormatting.WHITE : ChatFormatting.GRAY)
                )
                .withStyle(ChatFormatting.DARK_GRAY);
    }

    private static void addProfile(List<Component> tooltip, String profile) {
        addProfileFromBase(tooltip, "tooltip.create_fire_control.compat." + profile);
    }

    private static void addProfileFromBase(List<Component> tooltip, String baseKey) {
        addWrapped(tooltip, baseKey + ".summary", 0);
        for (int section = 1; section <= MAX_SECTIONS; section++) {
            String conditionKey = baseKey + ".condition" + section;
            String behaviourKey = baseKey + ".behaviour" + section;
            if (!I18n.exists(conditionKey) || !I18n.exists(behaviourKey)) {
                break;
            }
            tooltip.add(Component.empty());
            tooltip.add(Component.translatable(conditionKey).withStyle(ChatFormatting.GRAY));
            addWrapped(tooltip, behaviourKey, 1);
        }
    }

    /** Uses Create's wrapping and underscore-delimited highlights. */
    private static void addWrapped(List<Component> tooltip, String translationKey, int indent) {
        tooltip.addAll(TooltipHelper.cutStringTextComponent(
                I18n.get(translationKey), DESCRIPTION, HIGHLIGHT, indent));
    }

    private FireControlTooltipHandler() {
    }
}
