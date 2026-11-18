package com.hooya.stabilizedturret.mixin.client;

import com.caca.copypoly.client.PolyScreen;
import com.hooya.stabilizedturret.client.StabilizerControllerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Repaints the original buttons, retaining actions, tooltips, focus and menu lifecycle. */
@Mixin(AbstractButton.class)
public abstract class PolyArmorEditorButtonMixin {
    @Inject(method="renderWidget",at=@At("HEAD"),cancellable=true)
    private void fireControl$brass(GuiGraphics g,int mouseX,int mouseY,float partial,CallbackInfo ci){
        if(Minecraft.getInstance().screen instanceof PolyScreen&&(Object)this instanceof Button button){
            boolean apply=button.getMessage().getContents() instanceof TranslatableContents text&&text.getKey().equals("screen.copypoly.apply");
            StabilizerControllerScreen.renderBrassButton(g,button,apply);ci.cancel();
        }
    }
}
