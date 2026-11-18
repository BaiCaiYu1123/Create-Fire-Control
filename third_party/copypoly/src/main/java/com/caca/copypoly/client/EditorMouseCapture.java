// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.client;

import com.caca.copypoly.mixin.client.EditorMouseHandlerInvoker;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Owns a relative camera gesture and restores the cursor to its press position. */
public final class EditorMouseCapture {
    private int button=-1;
    private int previousMode;
    private long window;
    private double anchorX,anchorY;

    public boolean active() { return button>=0; }
    public boolean owns(int button) { return active() && this.button==button; }

    public void grab(int button,double x,double y) {
        if (active()) return;
        var display=Minecraft.getInstance().getWindow();
        window=display.getWindow();
        previousMode=GLFW.glfwGetInputMode(window,GLFW.GLFW_CURSOR);
        // Use the same screen-to-GUI ratio as Minecraft's mouse event conversion.
        anchorX=x/display.getGuiScaledWidth();
        anchorY=y/display.getGuiScaledHeight();
        this.button=button;
        GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_DISABLED);
    }

    public void release() {
        if (!active()) return;
        button=-1;
        var mc=Minecraft.getInstance();
        var display=mc.getWindow();
        if (window!=display.getWindow() || mc.mouseHandler.isMouseGrabbed()
                || previousMode==GLFW.GLFW_CURSOR_DISABLED
                || GLFW.glfwGetInputMode(window,GLFW.GLFW_CURSOR)!=GLFW.GLFW_CURSOR_DISABLED) return;
        // The synthetic cursor move must not become another camera delta.
        mc.mouseHandler.setIgnoreFirstMove();
        GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,previousMode);
        if (mc.isWindowActive()) {
            double x = anchorX * display.getScreenWidth(), y = anchorY * display.getScreenHeight();
            GLFW.glfwSetCursorPos(window, x, y);
            // GLFW may defer the move callback. Synchronize Minecraft's hover position now.
            mc.mouseHandler.setIgnoreFirstMove();
            ((EditorMouseHandlerInvoker) mc.mouseHandler).copypoly$onMove(window, x, y);
        }
    }
}
