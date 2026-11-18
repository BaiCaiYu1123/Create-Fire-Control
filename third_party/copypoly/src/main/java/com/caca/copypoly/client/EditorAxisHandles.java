// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.client;

import java.util.function.IntFunction;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

/** Screen geometry shared by axis rendering and picking in every armor editor. */
final class EditorAxisHandles {
    private static final int[] COLORS = {0xffff6868, 0xff91e66c, 0xff65aaff};
    private static final double LENGTH_PIXELS = 144;
    private static final double MIN_LENGTH_PIXELS = 30;

    private final Vec3 pivot;
    private final Vec3[] tips;
    private final double pickRadius;

    private EditorAxisHandles(Vec3 pivot, Vec3[] tips, double pickRadius) {
        this.pivot = pivot;
        this.tips = tips;
        this.pickRadius = pickRadius;
    }

    static EditorAxisHandles layout(Vec3 pivot, IntFunction<Vec3> direction, double pixel) {
        Vec3[] tips = new Vec3[3];
        double longest = 0;
        for (int axis = 0; axis < tips.length; axis++) {
            Vec3 projected = direction.apply(axis);
            tips[axis] = projected;
            longest = Math.max(longest, Math.hypot(projected.x, projected.y));
        }
        if (!Double.isFinite(longest) || longest == 0) return null;

        // Share one screen scale across the axes to keep their foreshortening.
        double scale = LENGTH_PIXELS * pixel / longest;
        for (int axis = 0; axis < tips.length; axis++) {
            Vec3 offset = tips[axis].scale(scale);
            tips[axis] = Math.hypot(offset.x, offset.y) >= MIN_LENGTH_PIXELS * pixel ? pivot.add(offset) : null;
        }
        return new EditorAxisHandles(pivot, tips, Math.max(4, 10 * pixel));
    }

    Vec3 tip(int axis) { return tips[axis]; }

    int pick(double x, double y) {
        int closest = -1;
        double best = pickRadius;
        for (int axis = 0; axis < tips.length; axis++) {
            Vec3 tip = tip(axis);
            if (tip == null) continue;
            double dx = tip.x - pivot.x, dy = tip.y - pivot.y;
            double t = ((x - pivot.x) * dx + (y - pivot.y) * dy) / (dx * dx + dy * dy);
            if (t < .15) continue;
            t = Math.min(t, 1);
            double distance = Math.hypot(x - pivot.x - t * dx, y - pivot.y - t * dy);
            if (distance < best) {
                closest = axis;
                best = distance;
            }
        }
        return closest;
    }

    void draw(GuiGraphics graphics, Font font, int dragAxis) {
        for (int axis = 0; axis < tips.length; axis++) {
            Vec3 tip = tip(axis);
            if (tip == null) continue;
            int color = axis == dragAxis ? 0xffffff8b : COLORS[axis];
            SmoothOverlay.arrow(graphics, pivot, tip, color);
            graphics.drawString(font, "XYZ".substring(axis, axis + 1), (int) tip.x + 5, (int) tip.y - 7, color, false);
        }
    }
}
