package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.AutoDefenseSettingsPayload;
import com.hooya.stabilizedturret.network.OpenAutoDefenseDisplayPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AutoDefenseDisplayScreen extends Screen {
    private final OpenAutoDefenseDisplayPayload initial;
    private boolean automaticAttack;
    private boolean yawReversed, pitchReversed;
    private double radarRange;
    private double cannonFireRange;
    private double yawSpeed, pitchSpeed, depressionLimit, elevationLimit;
    private EditBox rangeBox, fireRangeBox, yawBox, pitchBox, depressionBox, elevationBox;

    public AutoDefenseDisplayScreen(OpenAutoDefenseDisplayPayload initial) {
        super(Component.translatable("screen.stabilized_turret.auto_defense.title"));
        this.initial=initial; this.automaticAttack=initial.automaticAttack(); this.radarRange=initial.radarRange();
        this.yawSpeed=initial.yawSpeed();this.pitchSpeed=initial.pitchSpeed();
        this.depressionLimit=initial.depressionLimit();this.elevationLimit=initial.elevationLimit();
        this.yawReversed=initial.yawReversed();this.pitchReversed=initial.pitchReversed();
        this.cannonFireRange=initial.cannonFireRange();
    }
    @Override protected void init() {
        int left=width/2-190, top=height/2-150;
        addRenderableWidget(Button.builder(toggleText(), b->{automaticAttack=!automaticAttack;b.setMessage(toggleText());})
                .bounds(left+18,top+42,150,20).build());
        rangeBox=numericBox(left+285,top+42,76,radarRange,value->radarRange=value);
        yawBox=numericBox(left+112,top+76,64,yawSpeed,value->yawSpeed=value);
        pitchBox=numericBox(left+298,top+76,64,pitchSpeed,value->pitchSpeed=value);
        depressionBox=numericBox(left+112,top+108,64,depressionLimit,value->depressionLimit=value);
        elevationBox=numericBox(left+298,top+108,64,elevationLimit,value->elevationLimit=value);
        addRenderableWidget(Button.builder(reverseText(true),b->{
            yawReversed=!yawReversed;b.setMessage(reverseText(true));
        }).bounds(left+18,top+140,160,20).build());
        addRenderableWidget(Button.builder(reverseText(false),b->{
            pitchReversed=!pitchReversed;b.setMessage(reverseText(false));
        }).bounds(left+202,top+140,160,20).build());
        fireRangeBox=numericBox(left+285,top+172,76,cannonFireRange,
                value->cannonFireRange=value);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),b->{
            PacketDistributor.sendToServer(new AutoDefenseSettingsPayload(initial.pos(),initial.subLevelId(),automaticAttack,
                    radarRange,yawSpeed,pitchSpeed,depressionLimit,elevationLimit,
                    yawReversed,pitchReversed,cannonFireRange)); onClose();
        }).bounds(width/2-60,top+270,120,20).build());
    }
    private EditBox numericBox(int x,int y,int width,double initialValue,java.util.function.DoubleConsumer setter){
        EditBox box=new EditBox(font,x,y,width,20,Component.empty());
        box.setValue(String.format(java.util.Locale.ROOT,"%.1f",initialValue));
        box.setFilter(s->s.isEmpty()||s.matches("[0-9]*\\.?[0-9]*"));
        box.setResponder(s->{try{setter.accept(Double.parseDouble(s));}catch(NumberFormatException ignored){}});
        addRenderableWidget(box);return box;
    }
    private Component toggleText(){return Component.translatable("screen.stabilized_turret.auto_defense.attack",Component.translatable(automaticAttack?"options.on":"options.off"));}
    private Component reverseText(boolean yaw){
        boolean reversed=yaw?yawReversed:pitchReversed;
        return Component.literal((yaw?"方向机反转：":"高低机反转：")+(reversed?"开启":"关闭"));
    }
    @Override public void render(GuiGraphics g,int mx,int my,float pt){
        g.fill(0,0,width,height,0x68000000); int l=width/2-190,t=height/2-150,r=width/2+190,b=height/2+150;
        g.fill(l,t,r,b,0xFF302D29);g.fill(l+3,t+3,r-3,b-3,0xFFB8903D);g.fill(l+6,t+28,r-6,b-6,0xFFE2D2AC);g.fill(l+6,t+6,r-6,t+27,0xFF42474C);
        super.render(g,mx,my,pt); g.drawCenteredString(font,title,width/2,t+11,0xFFFFFFFF);
        g.drawString(font,Component.translatable("screen.stabilized_turret.auto_defense.range"),l+180,t+48,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.stabilized_turret.auto_defense.yaw_speed"),l+18,t+82,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.stabilized_turret.auto_defense.pitch_speed"),l+200,t+82,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.stabilized_turret.auto_defense.depression"),l+18,t+114,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.stabilized_turret.auto_defense.elevation"),l+200,t+114,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.stabilized_turret.auto_defense.fire_range"),l+180,t+178,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.stabilized_turret.auto_defense.devices"),l+18,t+207,0xFF3D382F,false);
        int y=t+224; for(OpenAutoDefenseDisplayPayload.Device d:initial.devices()){
            g.drawString(font,Component.literal(d.slot()+"  "+d.pos().toShortString()),l+22,y,0xFF504A40,false); y+=13; if(y>t+255)break;
        }
    }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float p){}
    @Override public boolean isPauseScreen(){return false;}
}
