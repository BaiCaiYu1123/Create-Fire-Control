package com.hooya.stabilizedturret.content.controller;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Mirrors the native firing procedures without linking to Mianbao classes. */
final class MianbaoLauncherAmmo {
    // An existing slot is authoritative, including when empty. Backends
    // without a readable inventory slot retain their legacy NBT path.
    private static ItemStack slotZero(BlockEntity launcher) {
        return hasInventorySlot(launcher) ? ((Container) launcher).getItem(0) : null;
    }

    static boolean hasInventorySlot(BlockEntity launcher) {
        return launcher instanceof Container container && container.getContainerSize() > 0;
    }

    private static boolean is(ItemStack stack, String path) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return !stack.isEmpty() && id.getNamespace().equals("mianbaos_modernwarfare")
                && id.getPath().equals(path);
    }

    static int antiTank(BlockEntity launcher, String blockPath) {
        ItemStack stack = slotZero(launcher);
        boolean fourth = blockPath.equals("antitankmissilelaunchersixing")
                || blockPath.equals("antitank_missile_launcher_type_4");
        if (stack != null) {
            return is(stack, "antitankmissile_1") || (!fourth && is(stack, "antitankmissile_2"))
                    ? stack.getCount() : 0;
        }
        return legacy(launcher, "missile1ammo", Integer.MAX_VALUE)
                + (fourth ? 0 : legacy(launcher, "missile2ammo", Integer.MAX_VALUE));
    }

    static int airDefense(BlockEntity launcher) {
        ItemStack stack = slotZero(launcher);
        if (stack != null) return is(stack, "portableantiairmissile_2") || OrdinaryAirDefenseLauncher.isRound(stack)
                ? stack.getCount() : 0;
        return legacy(launcher, "missileammo", Integer.MAX_VALUE);
    }

    static int rocketPod(BlockEntity launcher, boolean heavy) {
        return rocketPod(launcher, heavy, false);
    }

    static int rocketPod(BlockEntity launcher, boolean heavy, boolean modern) {
        ItemStack stack = slotZero(launcher);
        if (stack != null) {
            boolean accepted = heavy
                    ? isHeavyRound(stack) : is(stack, "rockets") || modern && is(stack, "rockets_visual_guided");
            return accepted ? stack.getCount() : 0;
        }
        if (modern) return 0;
        return heavy
                ? legacy(launcher, "\u9ad8\u7206", 5) + legacy(launcher, "\u706b\u7130", 5)
                        + legacy(launcher, "\u5236\u5bfc", 5)
                : legacy(launcher, "rockets", 36);
    }

    static boolean hasVisualGuidedRound(BlockEntity launcher) {
        ItemStack stack = launcher == null ? null : slotZero(launcher);
        return stack != null && is(stack, "rockets_visual_guided");
    }

    static double heavyMuzzleSpeed(BlockEntity launcher) {
        ItemStack stack = slotZero(launcher);
        // All three current heavy warheads launch at 9 blocks/tick, not only HE.
        if (stack != null) return isHeavyRound(stack) ? 9.0 : 10.0;
        var data = launcher.getPersistentData();
        return data.getDouble("\u9ad8\u7206") != 0.0
                && data.getDouble("\u706b\u7130") == 0.0
                && data.getDouble("\u5236\u5bfc") == 0.0 ? 9.0 : 10.0;
    }

    private static boolean isHeavyRound(ItemStack stack) {
        return is(stack, "farrocketsexplosion") || is(stack, "farrocketsfire")
                || is(stack, "farrocketlaserguided");
    }

    private static int legacy(BlockEntity launcher, String key, int maximum) {
        return Math.max(0, Math.min(maximum,
                (int) Math.floor(launcher.getPersistentData().getDouble(key))));
    }

    private MianbaoLauncherAmmo() {}
}
