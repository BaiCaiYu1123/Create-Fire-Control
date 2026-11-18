package com.hooya.stabilizedturret.mixin.client;

import com.caca.copypoly.client.PolyScreen;
import com.hooya.stabilizedturret.client.armor.ArmorEditorTheme;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Host-side appearance only; leaves the separately MIT-licensed editor source intact. */
@Mixin(value=PolyScreen.class,remap=false)
public abstract class PolyArmorEditorStyleMixin {
    @Shadow private int leftPos,topPos,imageWidth,imageHeight,previewX;
    @Shadow private boolean worldMode;
    @Inject(method="rebuild",at=@At("RETURN"))
    private void fireControl$fields(CallbackInfo ci){
        for(var child:((Screen)(Object)this).children()){
            if(child instanceof EditBox field)ArmorEditorTheme.field(field);
            if(worldMode&&child instanceof Button button&&button.getY()==topPos+3){button.setY(topPos+7);button.setX(button.getX()-3);}
        }
    }
    @WrapOperation(method="renderBackground",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;fillGradient(IIIIII)V"))
    private void fireControl$tableBackdrop(GuiGraphics g,int x0,int y0,int x1,int y1,int first,int last,Operation<Void> original){g.fill(x0,y0,x1,y1,0x68000000);}
    @WrapOperation(method="renderBg",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void fireControl$panel(GuiGraphics g,int x0,int y0,int x1,int y1,int color,Operation<Void> original){
        if(color==0xf218252b){ArmorEditorTheme.panel(g,x0,y0,x1,y1);return;}
        if(color==0xff273c43)return;
        original.call(g,x0,y0,x1,y1,color==0xff86e7ff?ArmorEditorTheme.BRASS:color==0xff101c22?0xff4a504d:color);
    }
    @WrapOperation(method="renderBg",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
    private int fireControl$labels(GuiGraphics g,Font font,Component text,int x,int y,int color,boolean shadow,Operation<Integer> original){
        if(color==0xffe3f2ec){
            int right=leftPos+imageWidth-(worldMode?32:10);g.enableScissor(leftPos+8,topPos+5,right,topPos+25);
            int result=original.call(g,font,text,x,y+2,ArmorEditorTheme.HEADER_TEXT,false);g.disableScissor();return result;
        }
        return original.call(g,font,text,x,y,worldMode||x<previewX||y>=topPos+imageHeight-64?ArmorEditorTheme.panelText(color):color,false);
    }
    @WrapOperation(method="renderBg",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"))
    private int fireControl$axes(GuiGraphics g,Font font,String text,int x,int y,int color,boolean shadow,Operation<Integer> original){return original.call(g,font,text,x,y,ArmorEditorTheme.panelText(color),false);}
    @WrapOperation(method="renderBg",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;drawWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIII)V"))
    private void fireControl$help(GuiGraphics g,Font font,FormattedText text,int x,int y,int width,int color,Operation<Void> original){
        original.call(g,font,text,x,y,width,worldMode||x<previewX?ArmorEditorTheme.panelText(color):color);
    }
    @WrapOperation(method="drawWorld",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;drawWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIII)V"))
    private void fireControl$worldHelp(GuiGraphics g,Font font,FormattedText text,int x,int y,int width,int color,Operation<Void> original){
        original.call(g,font,text,x,y,width,color==0xffffdc8b?color:ArmorEditorTheme.LABEL);
    }
    @WrapOperation(method="drawWorld",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void fireControl$helpPanel(GuiGraphics g,int x0,int y0,int x1,int y1,int color,Operation<Void> original){ArmorEditorTheme.inset(g,x0,y0,x1,y1);}
}
