package com.hooya.stabilizedturret.client.remote;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import java.util.Objects;
import org.lwjgl.glfw.GLFW;
import com.hooya.stabilizedturret.content.remote.ControllerInput;

public final class ControllerChannelScreen extends ControllerPanelScreen {
    private final BlockPos source; private final ControllerInputPanel inputs=new ControllerInputPanel();
    private String selected; private boolean recording;
    private BrassButton confirm, record;
    private java.util.function.Consumer<String> selection;
    private net.minecraft.client.gui.screens.Screen parent;
    private java.util.Set<String> excluded=java.util.Set.of();
    public ControllerChannelScreen exclude(String... channels){excluded=java.util.Set.of(channels);return this;}
    public ControllerChannelScreen(BlockPos source) { super(text("select_title"));this.source=source;
        String current=ControllerWireClient.current();if(ControllerInput.fromChannel(current)!=null)selected=current;
    }
    public ControllerChannelScreen(net.minecraft.client.gui.screens.Screen parent,String current,java.util.function.Consumer<String> selection) {
        super(text("select_title"));this.source=null;this.parent=parent;this.selection=selection;
        if(ControllerInput.fromChannel(current)!=null)selected=current;
    }
    @Override public void onClose(){if(parent!=null)minecraft.setScreen(parent);else super.onClose();}
    @Override protected void init() {
        panel(198);int y=top+28,right=left+panelWidth-10;
        right-=buttonWidth("record");record=button(right,y,"record",b->{recording=true;setFocused(null);});right-=4;
        right-=buttonWidth("next");button(right,y,"next",b->inputs.page(1));right-=3;
        right-=buttonWidth("previous");button(right,y,"previous",b->inputs.page(-1));right-=4;
        right-=buttonWidth("all_inputs");button(right,y,"all_inputs",b->inputs.toggle());
        EditBox search=addRenderableWidget(new EditBox(font,left+10,y,right-left-14,16,text("search")));
        search.setHint(text("search"));search.setResponder(inputs::query);
        inputs.bounds(left+10,top+50,panelWidth-20,panelHeight-78);
        int cancelX=left+panelWidth-10-buttonWidth("cancel");
        confirm=button(cancelX-buttonWidth("confirm")-4,top+panelHeight-22,"confirm",b->{
            if(selected!=null && valid()) {if(selection!=null)selection.accept(selected);else ControllerWireClient.select(selected);onClose();}
        });confirm.active=selected!=null;
        button(cancelX,top+panelHeight-22,"cancel",b->onClose());
        if(selection!=null)button(left+10,top+panelHeight-42,"clear_channel",b->{selection.accept("");onClose();});
    }
    private boolean valid() { return selection!=null || ControllerWireClient.canSelect() && Objects.equals(source,ControllerWireClient.selected()); }
    @Override public void tick() { if(!valid()) onClose(); }
    private void select(String channel) { if(channel!=null&&!channel.isEmpty()&&!excluded.contains(channel)){selected=channel;recording=false;confirm.active=true;} }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(recording) {select(ControllerInput.channel(true,button));return true;}
        if(button==0){String channel=inputs.hit(x,y);if(channel!=null){select(channel);return true;}}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(recording){if(key==GLFW.GLFW_KEY_ESCAPE)recording=false;else select(ControllerInput.channel(false,key));return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(GuiGraphics graphics,int x,int y,float partial) {
        super.render(graphics,x,y,partial);inputs.render(graphics,x,y,selected);
        var message=recording?text("record_hint"):selected==null?text("select_input"):WirelessControllerClient.name(selected);
        graphics.drawString(font,font.plainSubstrByWidth(message.getString(),panelWidth-buttonWidth("confirm")-buttonWidth("cancel")-34),
                left+10,top+panelHeight-18,INK,false);
    }
}
