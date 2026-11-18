package com.hooya.stabilizedturret.client.armor;

import com.hooya.stabilizedturret.client.StabilizerControllerScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;

/** Fire Control's own brass UI; the independently licensed CopyPoly editor keeps its logic. */
public final class ArmorEditorTheme {
    public static final int LABEL=0xff3d382f, MUTED=0xff6e6250, HEADER_TEXT=0xfff4ebd5;
    public static final int BRASS=0xffb8903d, BACKGROUND=0xffe2d2ac;
    public static void panel(GuiGraphics g,int left,int top,int right,int bottom){
        if(bottom-top>=40)StabilizerControllerScreen.renderBrassFrame(g,left,top,right,bottom);
        else {inset(g,left,top,right,bottom);g.fill(left+4,top+4,right-4,bottom-4,0xff4b4438);}
    }
    public static void inset(GuiGraphics g,int left,int top,int right,int bottom){
        g.fill(left+3,top+4,right+4,bottom+4,0x90000000);
        g.fill(left,top,right,bottom,0xff342f28);g.fill(left+1,top+1,right-1,bottom-1,BRASS);
        g.fill(left+3,top+3,right-3,bottom-3,BACKGROUND);
    }
    public static void field(EditBox box){box.setTextShadow(false);box.setTextColor(0xfff4eacf);box.setTextColorUneditable(0xff9d9585);}
    public static int panelText(int original){return switch(original){
        case 0xffe3f2ec -> HEADER_TEXT;
        case 0xffffa599 -> 0xff8b352d;
        case 0xffd6adff,0xffcfb7e8 -> 0xff695075;
        case 0xffffdc8b -> 0xff74551f;
        case 0xff94b5ad,0xffa6b4b9,0xffaacfc1 -> MUTED;
        default -> LABEL;
    };}
    private ArmorEditorTheme(){}
}
