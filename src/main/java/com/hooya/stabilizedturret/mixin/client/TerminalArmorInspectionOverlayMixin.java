package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.armor.EquivalentArmor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Keeps the inspection overlay compact and leaves the full CTB snapshot available on demand. */
@Mixin(targets = "com.cbc_terminal_ballistics.debug.AdvancedInspectionOverlayHooks", remap = false)
public abstract class TerminalArmorInspectionOverlayMixin {
    @WrapOperation(method = "addBlockArmorInfo", at = @At(value = "INVOKE",
            target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private static boolean fireControl$compactDetails(List<Component> tooltip, Object line, Operation<Boolean> original) {
        if (!(line instanceof Component component) || !(component.getContents() instanceof TranslatableContents text)) {
            return original.call(tooltip, line);
        }
        boolean details = Screen.hasShiftDown();
        return switch (text.getKey()) {
            case "tooltip.cbc_terminal_ballistics.advanced_inspector.cbc" -> {
                if (!details) yield false;
                if (!EquivalentArmor.enabled()) yield original.call(tooltip, line);
                yield tooltip.add(detail("target_resistance", text.getArgs()));
            }
            case "tooltip.cbc_terminal_ballistics.advanced_inspector.material_stats",
                 "tooltip.cbc_terminal_ballistics.advanced_inspector.integrity" ->
                    details && original.call(tooltip, line);
            case "tooltip.cbc_terminal_ballistics.advanced_inspector.material" -> {
                boolean added = original.call(tooltip, line);
                if (!details) tooltip.add(Component.translatable("tooltip.create_fire_control.armor.inspection_details")
                        .withStyle(ChatFormatting.GRAY));
                yield added;
            }
            case "tooltip.cbc_terminal_ballistics.advanced_inspector.last_impact" -> {
                if (!details) yield false;
                Object[] args = text.getArgs();
                if (args.length != 7) yield false;
                tooltip.add(detail("impact_outcome", args[0], args[1]));
                tooltip.add(detail("impact_shot", args[2], args[3]));
                tooltip.add(detail("impact_spall", args[4], args[5], args[6]));
                yield true;
            }
            default -> original.call(tooltip, line);
        };
    }

    private static Component detail(String key, Object... args) {
        return Component.translatable("tooltip.create_fire_control.armor." + key, args).withStyle(ChatFormatting.GRAY);
    }
}
