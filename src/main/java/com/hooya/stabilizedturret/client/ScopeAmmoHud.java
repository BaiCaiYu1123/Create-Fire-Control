package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.ScopeAmmoPayload;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** The cartridge's existing item model and total APFSDS in the loaders and cannon chamber. */
public final class ScopeAmmoHud {
    private static int remaining = -1;
    private static ItemStack icon = ItemStack.EMPTY;

    public static void accept(ScopeAmmoPayload payload) {
        remaining = ClientAimState.isScoped() && !ClientAimState.isPodView() ? payload.remaining() : -1;
    }

    static void clear() { remaining = -1; }

    static boolean visible() {
        return remaining >= 0 && ClientAimState.isScoped() && !ClientAimState.isPodView()
                && !ClientWeaponViewState.isActive();
    }

    static void draw(GuiGraphics graphics) {
        if (!visible()) return;
        if (icon.isEmpty()) icon = ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance();
        float scale = graphics.guiHeight() / 800.0F;
        graphics.pose().pushPose();
        // Left margin matches the zoom readout; the badge sits just above it.
        graphics.pose().translate(12, graphics.guiHeight() - 112 * scale, 0);
        graphics.pose().scale(2 * scale, 2 * scale, 1);
        graphics.renderItem(icon, 0, 0);
        graphics.drawString(Minecraft.getInstance().font, Integer.toString(remaining), 20, 4, 0xFFFFFFFF, true);
        graphics.pose().popPose();
    }

    private ScopeAmmoHud() {}
}
