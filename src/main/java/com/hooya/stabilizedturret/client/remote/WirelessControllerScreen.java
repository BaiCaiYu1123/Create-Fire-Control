package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.content.remote.*;
import com.hooya.stabilizedturret.network.WirelessControllerActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import org.lwjgl.glfw.GLFW;
import java.util.*;

public final class WirelessControllerScreen extends ControllerPanelScreen {
    private CompoundTag state;
    private final Map<String,ControllerSettings.Binding> bindings=new LinkedHashMap<>();
    private Set<String> wired,actions;
    private final ControllerInputPanel inputs=new ControllerInputPanel();
    private String editing,originalEditing;private EditBox label;private ItemStack first=ItemStack.EMPTY,second=ItemStack.EMPTY,cursor=ItemStack.EMPTY;
    private boolean enabled,recording,confirmClear;
    private String exitChannel;private boolean choosingExit;private BrassButton exitButton;
    private int inventoryX,inventoryY,frequencyX,frequencyY;
    private int inventoryPanelX,inventoryPanelY;
    private String draftLabel="",secondInput="";
    private String draftSignalStrength=Integer.toString(ControllerSettings.DEFAULT_SIGNAL_STRENGTH);
    private String color;
    private final InputChordCapture capture=new InputChordCapture();
    public WirelessControllerScreen(CompoundTag state) {
        super(text("title"));this.state=state.copy();exitChannel=ControllerSettings.exitChannel(state);color=ControllerSettings.color(state);
        var registries=net.minecraft.client.Minecraft.getInstance().level.registryAccess();
        for(var binding:ControllerSettings.bindings(state,registries))bindings.put(binding.channel(),binding);
        wired=ControllerSessions.readStrings(state,"Wired");actions=ControllerSessions.readStrings(state,"Actions");
        inputs.tooltips(this::inputDetails);
    }
    /** A pairing update refreshes server-owned fields without discarding unsaved frequency edits. */
    boolean refreshConfig(CompoundTag incoming) {
        if(!state.hasUUID("Id")||!incoming.hasUUID("Id")||!state.getUUID("Id").equals(incoming.getUUID("Id"))
                ||!state.hasUUID("EditToken")||!incoming.hasUUID("EditToken")
                ||!state.getUUID("EditToken").equals(incoming.getUUID("EditToken")))return false;
        state=incoming.copy();
        wired=ControllerSessions.readStrings(state,"Wired");actions=ControllerSessions.readStrings(state,"Actions");
        rebuildWidgets();return true;
    }
    private List<Component> inputDetails(String channel) {
        List<Component> details=new ArrayList<>();details.add(WirelessControllerClient.name(channel));
        var binding=bindings.get(channel);
        if(binding!=null) {
            if(!binding.label().isBlank())details.add(Component.literal(binding.label()));
            if(!binding.secondInput().isEmpty())details.add(Component.literal("+ ").append(WirelessControllerClient.name(binding.secondInput())));
            if(!binding.first().isEmpty()||!binding.second().isEmpty())
                details.add(text(binding.wireless()?"wireless_used":"wireless_disabled"));
        }
        if(wired.contains(channel))details.add(text("wire_used"));
        if(actions.contains(channel))details.add(text("action_used"));
        return details;
    }
    private Set<String> effective() {
        List<String> wireless=bindings.values().stream().filter(ControllerSettings.Binding::wireless).map(ControllerSettings.Binding::channel).toList();
        Map<String,String> chords=new HashMap<>();for(var binding:bindings.values())if(!binding.secondInput().isEmpty())chords.put(binding.channel(),binding.secondInput());
        Set<String> result=new HashSet<>(ControllerChords.physicalUses(ControllerInput.effective(wireless,wired,actions,exitChannel),chords,exitChannel));
        result.addAll(ControllerInput.DEFAULT_MOUSE_BUTTONS);result.remove(exitChannel);return Set.copyOf(result);
    }
    @Override protected void init() {
        if(editing==null)panel(216);
        else {
            panel(244,122);
            top=(height-panelHeight-6-AllGuiTextures.PLAYER_INVENTORY.getHeight())/2;
        }
        if(editing==null) main();else editor();
    }
    private void main() {
        int y=top+28,right=left+panelWidth-10;
        right-=buttonWidth("next");button(right,y,"next",b->inputs.page(1));right-=3;
        right-=buttonWidth("previous");button(right,y,"previous",b->inputs.page(-1));right-=5;
        right-=buttonWidth("show_enabled");button(right,y,"show_enabled",b->inputs.toggleEnabled());right-=4;
        right-=buttonWidth("all_inputs");button(right,y,"all_inputs",b->inputs.toggle());
        EditBox search=addRenderableWidget(new EditBox(font,left+10,y,right-left-14,16,text("search")));
        search.setHint(text("search"));search.setResponder(inputs::query);
        inputs.bounds(left+10,top+50,panelWidth-20,panelHeight-92);inputs.enabled(effective());inputs.reserved(exitChannel);
        int bottom=top+panelHeight;
        exitButton=button(left+10,bottom-42,exitButtonWidth(),"exit_capture",b->{
            choosingExit=!choosingExit;setFocused(null);updateExitButton();
        });updateExitButton();
        int clearWidth=Math.max(buttonWidth("clear_all"),buttonWidth("clear_confirm"));
        button(left+10,bottom-22,clearWidth,"clear_all",b->{
            if(confirmClear){bindings.clear();confirmClear=false;inputs.enabled(effective());b.setMessage(text("clear_all"));}
            else{confirmClear=true;b.setMessage(text("clear_confirm"));}
        });
        var colorButton=button(left+15+clearWidth,bottom-22,"switch_color",b->{
            color=ControllerSettings.COLORS.get((ControllerSettings.COLORS.indexOf(color)+1)%ControllerSettings.COLORS.size());
            b.setTooltip(colorTooltip());
        });
        colorButton.setTooltip(colorTooltip());
        int cancelX=left+panelWidth-10-buttonWidth("cancel");
        button(cancelX-buttonWidth("save")-4,bottom-22,"save",b->submit(WirelessControllerActionPayload.SAVE));
        button(cancelX,bottom-22,"cancel",b->onClose());
    }
    private void openEditor(String channel) {
        if(channel.equals(exitChannel))return;
        choosingExit=false;
        editing=channel;originalEditing=channel;var binding=bindings.get(channel);
        first=binding==null?ItemStack.EMPTY:binding.first().copy();second=binding==null?ItemStack.EMPTY:binding.second().copy();
        draftLabel=binding==null?"":binding.label();
        draftSignalStrength=Integer.toString(binding==null?ControllerSettings.DEFAULT_SIGNAL_STRENGTH:binding.signalStrength());
        secondInput=binding==null?"":binding.secondInput();
        enabled=binding==null||binding.enabled();cursor=ItemStack.EMPTY;recording=false;rebuildWidgets();
    }
    private void editor() {
        int y=top+24;
        label=addRenderableWidget(new EditBox(font,left+10,y,panelWidth-108,16,text("label")));
        label.setMaxLength(48);label.setHint(text("label"));
        label.setValue(draftLabel);label.setResponder(value->draftLabel=value);
        EditBox strength=addRenderableWidget(new EditBox(font,left+panelWidth-38,y,28,16,text("signal_strength")));
        strength.setMaxLength(2);
        strength.setFilter(value->value.matches("[0-9]{0,2}")&&(value.isEmpty()||Integer.parseInt(value)<=ControllerSettings.MAX_SIGNAL_STRENGTH));
        strength.setValue(draftSignalStrength);strength.setResponder(value->draftSignalStrength=value);
        strength.setTooltip(Tooltip.create(text("signal_strength_hint")));
        int toggleWidth=Math.max(buttonWidth("enabled"),buttonWidth("disabled"));
        int toggleX=left+panelWidth-10-toggleWidth;
        button(toggleX-buttonWidth("record")-4,top+42,"record",b->beginRecord());
        button(toggleX,top+42,toggleWidth,enabled?"enabled":"disabled",b->{enabled=!enabled;b.setMessage(text(enabled?"enabled":"disabled"));});
        int comboWidth=panelWidth-24-buttonWidth("combo_clear");
        BrassButton combo=button(left+10,top+60,comboWidth,"combo_add",b->beginRecord());
        combo.setMessage(secondInput.isEmpty()?text("combo_add"):Component.translatable("remote.create_fire_control.combo_second",WirelessControllerClient.name(secondInput)));
        button(left+panelWidth-10-buttonWidth("combo_clear"),top+60,"combo_clear",b->{secondInput="";combo.setMessage(text("combo_add"));});
        frequencyX=left+60;frequencyY=top+78;
        button(left+panelWidth-10-buttonWidth("clear_channel"),frequencyY+2,"clear_channel",b->{first=ItemStack.EMPTY;second=ItemStack.EMPTY;});
        inventoryPanelX=(width-AllGuiTextures.PLAYER_INVENTORY.getWidth())/2;
        inventoryPanelY=top+panelHeight+6;
        inventoryX=inventoryPanelX+8;inventoryY=inventoryPanelY+18;
        int backX=left+panelWidth-10-buttonWidth("back");
        button(backX-buttonWidth("save_input")-4,top+100,"save_input",b->{
            String labelText=label.getValue();
            int signalStrength=draftSignalStrength.isEmpty()?ControllerSettings.DEFAULT_SIGNAL_STRENGTH:Integer.parseInt(draftSignalStrength);
            bindings.remove(originalEditing);
            if(first.isEmpty()&&second.isEmpty()&&labelText.isBlank()&&secondInput.isEmpty()&&signalStrength==ControllerSettings.DEFAULT_SIGNAL_STRENGTH) bindings.remove(editing);
            else if(!editing.isEmpty())bindings.put(editing,new ControllerSettings.Binding(editing,labelText,first,second,enabled,secondInput,signalStrength));
            editing=null;recording=false;cursor=ItemStack.EMPTY;rebuildWidgets();
        });
        button(backX,top+100,"back",b->{editing=null;recording=false;cursor=ItemStack.EMPTY;rebuildWidgets();});
    }
    private CompoundTag draft() {
        CompoundTag data=state.copy();data.putString("ExitChannel",exitChannel);
        data.putString("Color",color);
        ControllerSettings.putBindings(data,new ArrayList<>(bindings.values()),minecraft.level.registryAccess());return data;
    }
    private Tooltip colorTooltip() { return Tooltip.create(Component.translatable("remote.create_fire_control.color_selected",text("color_"+color))); }
    private Component exitLabel() { return Component.translatable("remote.create_fire_control.exit_key",WirelessControllerClient.name(exitChannel)); }
    private int exitButtonWidth() { return font.width(choosingExit?text("exit_capture"):exitLabel())+8; }
    private void updateExitButton() { exitButton.setWidth(exitButtonWidth());exitButton.setMessage(choosingExit?text("exit_capture"):exitLabel()); }
    private void chooseExit(String channel) {
        if(channel==null||channel.isEmpty())return;
        exitChannel=channel;choosingExit=false;inputs.reserved(channel);inputs.enabled(effective());updateExitButton();
    }
    private void send(int action) { PacketDistributor.sendToServer(new WirelessControllerActionPayload(action,draft())); }
    private void submit(int action) { send(action);onClose(); }
    private void beginRecord(){recording=true;capture.clear();setFocused(null);}
    private void captureInput(String channel){if(!channel.equals(exitChannel))capture.press(channel);}
    private void finishRecord(String channel) {
        var keys=capture.release(channel);if(keys.isEmpty())return;
        String primary=keys.getFirst();
        if(!primary.equals(originalEditing)&&bindings.containsKey(primary)){recording=false;return;}
        editing=primary;secondInput=keys.size()==2?keys.get(1):"";recording=false;rebuildWidgets();
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){if(recording){finishRecord(ControllerInput.channel(false,key));return true;}return super.keyReleased(key,scan,modifiers);}
    @Override public boolean mouseReleased(double x,double y,int button){if(recording){finishRecord(ControllerInput.channel(true,button));return true;}return super.mouseReleased(x,y,button);}
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(choosingExit) {
            if(button!=0){chooseExit(ControllerInput.channel(true,button));return true;}
            String channel=inputs.hit(x,y,true);
            if(channel!=null&&!channel.isEmpty()){chooseExit(channel);return true;}
        }
        if(recording){captureInput(ControllerInput.channel(true,button));return true;}
        if(editing==null) {
            if(button==0){String channel=inputs.hit(x,y);if(channel!=null&&!channel.isEmpty()){openEditor(channel);return true;}}
        } else {
            for(int index=0;index<2;index++) {
                int slotX=frequencyX+index*26;
                if(x>=slotX&&x<slotX+20&&y>=frequencyY&&y<frequencyY+20){
                    ItemStack value=button==1?ItemStack.EMPTY:cursor.copy();
                    if(index==0)first=value;else second=value;cursor=ItemStack.EMPTY;return true;
                }
            }
            for(int index=0;index<36;index++) {
                int sx=inventoryX+index%9*18,sy=inventorySlotY(index);
                if(x>=sx&&x<sx+16&&y>=sy&&y<sy+16){
                    int inventoryIndex=index<27?index+9:index-27;
                    cursor=ControllerSettings.normalize(minecraft.player.getInventory().getItem(inventoryIndex));return true;
                }
            }
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        String channel=ControllerInput.scrollChannel(vertical);
        if(choosingExit&&!channel.isEmpty()){chooseExit(channel);return true;}
        if(recording&&!channel.isEmpty()){captureInput(channel);finishRecord(channel);return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(choosingExit){chooseExit(ControllerInput.channel(false,key));return true;}
        if(recording){if(key==GLFW.GLFW_KEY_ESCAPE){editing="";secondInput="";recording=false;capture.clear();rebuildWidgets();}else captureInput(ControllerInput.channel(false,key));return true;}
        if(key==GLFW.GLFW_KEY_ESCAPE&&editing!=null){editing=null;cursor=ItemStack.EMPTY;rebuildWidgets();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(GuiGraphics graphics,int x,int y,float partial) {
        super.render(graphics,x,y,partial);
        ItemStack preview=com.hooya.stabilizedturret.registry.ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();
        CompoundTag previewData=new CompoundTag();previewData.putString("Color",color);ControllerSettings.write(preview,previewData);
        graphics.renderItem(preview,left+panelWidth-25,top+6);
        if(editing==null) {
            inputs.render(graphics,x,y,null);
            if(state.getBoolean("WireAvailable")) graphics.drawString(font,text(state.getBoolean("HubValid")?"hub_ready":"hub_none"),
                    left+14+exitButton.getWidth(),top+panelHeight-38,state.getBoolean("HubValid")?0xFF3F7B39:0xFF9A3F35,false);
        } else {
            graphics.drawString(font,text("signal_strength"),left+panelWidth-86,top+28,INK,false);
            Component keyName=recording?capture.preview(text("record_short")):editing.isEmpty()?Component.literal("—"):WirelessControllerClient.name(editing);
            int keyWidth=panelWidth-24-Math.max(buttonWidth("enabled"),buttonWidth("disabled"))-buttonWidth("record");
            graphics.drawString(font,font.plainSubstrByWidth(keyName.getString(),keyWidth),left+10,top+46,INK,false);
            if(x>=left+10&&x<left+10+keyWidth&&y>=top+42&&y<top+58)
                graphics.renderTooltip(font,recording?text("record_hint"):keyName,x,y);
            graphics.drawString(font,text("frequency"),left+10,frequencyY+6,INK,false);
            ghostSlot(graphics,frequencyX,frequencyY,first);ghostSlot(graphics,frequencyX+26,frequencyY,second);
            if(state.getBoolean("WireAvailable")) {
                graphics.drawString(font,text(wired.contains(editing)?"wire_short_used":"wire_short_unused"),left+10,top+104,
                        wired.contains(editing)?0xFF3F7B39:0xFF85765D,false);
                if(x>=left+10&&x<left+96&&y>=top+100&&y<top+116)
                    graphics.renderTooltip(font,text(actions.contains(editing)?"action_used":wired.contains(editing)?"wire_used":"wire_unused"),x,y);
            }
            AllGuiTextures.PLAYER_INVENTORY.render(graphics,inventoryPanelX,inventoryPanelY);
            graphics.drawString(font,Component.translatable("container.inventory"),inventoryPanelX+8,inventoryPanelY+6,0xFF404040,false);
            ItemStack hovered=ItemStack.EMPTY;
            for(int index=0;index<36;index++) {
                int sx=inventoryX+index%9*18,sy=inventorySlotY(index);
                ItemStack stack=minecraft.player.getInventory().getItem(index<27?index+9:index-27);
                graphics.renderItem(stack,sx,sy);graphics.renderItemDecorations(font,stack,sx,sy);
                if(x>=sx&&x<sx+16&&y>=sy&&y<sy+16){graphics.fill(sx,sy,sx+16,sy+16,0x40FFFFFF);hovered=stack;}
            }
            if(!hovered.isEmpty())graphics.renderTooltip(font,hovered,x,y);
            if(!cursor.isEmpty())graphics.renderItem(cursor,x-8,y-8);
        }
    }
    private void ghostSlot(GuiGraphics graphics,int x,int y,ItemStack stack) {
        graphics.fill(x,y,x+20,y+20,BRASS);graphics.fill(x+1,y+1,x+19,y+19,0xFF665A42);
        graphics.renderItem(stack,x+2,y+2);
    }
    private int inventorySlotY(int index) { return inventoryY+(index<27?index/9*18:58); }
}
