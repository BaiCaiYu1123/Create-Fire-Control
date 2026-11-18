package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.client.StabilizerControllerScreen;
import com.hooya.stabilizedturret.content.remote.ControllerSettings;
import com.hooya.stabilizedturret.network.SetFireControlInputPayload;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Compact configuration above the same Create inventory panel used by the wireless controller. */
public final class FireControlFrequencyScreen extends ControllerPanelScreen {
    private final Screen parent;
    private final java.util.function.BiConsumer<ItemStack,ItemStack> save;
    private ItemStack first,second,cursor=ItemStack.EMPTY;
    private int inventoryX,inventoryY,frequencyX,frequencyY;
    public FireControlFrequencyScreen(StabilizerControllerScreen parent,BlockPos pos,int action,ItemStack first,ItemStack second) {
        this(parent,com.hooya.stabilizedturret.content.controller.FireControlWeaponInputs.label(action),first,second,
                (a,b)->PacketDistributor.sendToServer(new SetFireControlInputPayload(pos,action,-1,"",a,b)));
    }
    public FireControlFrequencyScreen(Screen parent,Component title,ItemStack first,ItemStack second,
                                     java.util.function.BiConsumer<ItemStack,ItemStack> save) {
        super(title);
        this.parent=parent;this.save=save;this.first=first.copy();this.second=second.copy();
    }
    @Override protected void init() {
        panel(244,102);top=(height-panelHeight-6-AllGuiTextures.PLAYER_INVENTORY.getHeight())/2;
        frequencyX=left+96;frequencyY=top+38;
        button(left+10,top+76,"clear_channel",b->{first=ItemStack.EMPTY;second=ItemStack.EMPTY;});
        int back=left+panelWidth-10-buttonWidth("back");
        button(back-buttonWidth("save")-4,top+76,"save",b->{
            save.accept(first.copy(),second.copy());onClose();
        });
        button(back,top+76,"back",b->onClose());
        inventoryX=(width-AllGuiTextures.PLAYER_INVENTORY.getWidth())/2+8;
        inventoryY=top+panelHeight+6+18;
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(button==0||button==1) {
            for(int i=0;i<2;i++) {
                int sx=frequencyX+i*26;
                if(x>=sx&&x<sx+20&&y>=frequencyY&&y<frequencyY+20) {
                    ItemStack value=button==1?ItemStack.EMPTY:cursor.copy();
                    if(i==0)first=value;else second=value;cursor=ItemStack.EMPTY;return true;
                }
            }
            for(int i=0;i<36;i++) {
                int sx=inventoryX+i%9*18,sy=slotY(i);
                if(x>=sx&&x<sx+16&&y>=sy&&y<sy+16) {
                    cursor=ControllerSettings.normalize(minecraft.player.getInventory().getItem(i<27?i+9:i-27));return true;
                }
            }
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public void render(GuiGraphics g,int x,int y,float partial) {
        super.render(g,x,y,partial);
        g.drawString(font,text("frequency"),left+10,frequencyY+6,INK,false);
        for(int i=0;i<2;i++) {
            int sx=frequencyX+i*26;
            g.fill(sx,frequencyY,sx+20,frequencyY+20,BRASS);g.fill(sx+1,frequencyY+1,sx+19,frequencyY+19,0xFF665A42);
            ItemStack stack=i==0?first:second;g.renderItem(stack,sx+2,frequencyY+2);
            if(x>=sx&&x<sx+20&&y>=frequencyY&&y<frequencyY+20&&!stack.isEmpty())g.renderTooltip(font,stack,x,y);
        }
        AllGuiTextures.PLAYER_INVENTORY.render(g,inventoryX-8,inventoryY-18);
        g.drawString(font,Component.translatable("container.inventory"),inventoryX,inventoryY-12,0xFF404040,false);
        ItemStack hovered=ItemStack.EMPTY;
        for(int i=0;i<36;i++) {
            int sx=inventoryX+i%9*18,sy=slotY(i);
            ItemStack stack=minecraft.player.getInventory().getItem(i<27?i+9:i-27);
            g.renderItem(stack,sx,sy);g.renderItemDecorations(font,stack,sx,sy);
            if(x>=sx&&x<sx+16&&y>=sy&&y<sy+16){g.fill(sx,sy,sx+16,sy+16,0x40FFFFFF);hovered=stack;}
        }
        if(!hovered.isEmpty())g.renderTooltip(font,hovered,x,y);
        if(!cursor.isEmpty())g.renderItem(cursor,x-8,y-8);
    }
    private int slotY(int i){return inventoryY+(i<27?i/9*18:58);}
}
