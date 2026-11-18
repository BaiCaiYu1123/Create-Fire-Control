package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.AutoDefenseSettingsPayload;
import com.hooya.stabilizedturret.network.OpenAutoDefenseDisplayPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AutoDefenseDisplayScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 380;
    private static final int NORMAL_PANEL_HEIGHT = 308;
    private static final int COMPACT_PANEL_HEIGHT = 276;
    private static final int PANEL_MARGIN = 8;
    private static final int CONTENT_MARGIN = 18;
    private static final int COLUMN_GAP = 8;
    private final OpenAutoDefenseDisplayPayload initial;
    private boolean automaticAttack;
    private boolean scanStructures;
    private boolean scanLiving;
    private boolean scanMissiles;
    private boolean automaticMissileDefense;
    private double automaticMissileDefenseRange;
    private double missileProximityRange;
    private boolean yawReversed, pitchReversed;
    private double radarRange;
    private double cannonFireRange;
    private int cannonFirePower;
    private int mapUpdateIntervalSeconds;
    private int mapResolution;
    private double yawSpeed, pitchSpeed, depressionLimit, elevationLimit;
    private EditBox rangeBox, fireRangeBox, mapUpdateIntervalBox,
            yawBox, pitchBox, depressionBox, elevationBox;
    private DefenseRangeSlider defenseRangeSlider;
    private Button structurePreviewButton;
    private Button doneButton;
    private final java.util.List<AbstractWidget> contentWidgets = new java.util.ArrayList<>();
    private int scrollOffset;
    private boolean draggingScroll;

    public AutoDefenseDisplayScreen(OpenAutoDefenseDisplayPayload initial) {
        super(Component.translatable("screen.create_fire_control.auto_defense.title"));
        this.initial=initial; this.automaticAttack=initial.automaticAttack();
        this.scanStructures=initial.scanStructures();
        this.scanLiving=initial.scanLiving();
        this.scanMissiles=initial.scanMissiles();
        this.automaticMissileDefense=initial.automaticMissileDefense();
        this.automaticMissileDefenseRange=initial.automaticMissileDefenseRange();
        this.missileProximityRange=initial.missileProximityRange();
        this.radarRange=initial.radarRange();
        this.yawSpeed=initial.yawSpeed();this.pitchSpeed=initial.pitchSpeed();
        this.depressionLimit=initial.depressionLimit();this.elevationLimit=initial.elevationLimit();
        this.yawReversed=initial.yawReversed();this.pitchReversed=initial.pitchReversed();
        this.cannonFireRange=initial.cannonFireRange();
        this.cannonFirePower=initial.cannonFirePower();
        this.mapUpdateIntervalSeconds=initial.mapUpdateIntervalSeconds();
        this.mapResolution=initial.mapResolution();
    }
    @Override protected void init() {
        contentWidgets.clear();
        scrollOffset=0;
        int panelWidth=panelWidth(), left=(width-panelWidth)/2, top=panelTop();
        int contentWidth=panelWidth-CONTENT_MARGIN*2;
        int columnWidth=(contentWidth-COLUMN_GAP)/2;
        int firstColumn=left+CONTENT_MARGIN;
        int secondColumn=firstColumn+columnWidth+COLUMN_GAP;
        int fieldWidth=Math.min(76,Math.max(58,columnWidth/2));
        int firstField=firstColumn+columnWidth-fieldWidth;
        int secondField=secondColumn+columnWidth-fieldWidth;
        structurePreviewButton = null;
        if (initial.subLevelId() != null) {
            structurePreviewButton = addRenderableWidget(brass(StructurePreviewScreen.text("entry"),
                    b -> minecraft.setScreen(new StructurePreviewScreen(this, initial.pos(), initial.subLevelId(), true, p -> {})),
                    left+panelWidth-(compactLayout()?128:64),top+9,52,16));
            structurePreviewButton.visible = StructurePreviewScreen.available(initial.pos(), initial.subLevelId());
        }
        addRenderableWidget(brass(toggleText(), b->{automaticAttack=!automaticAttack;b.setMessage(toggleText());},
                firstColumn,top+32,columnWidth,20));
        addRenderableWidget(brass(defenseToggleText(), b->{automaticMissileDefense=!automaticMissileDefense;b.setMessage(defenseToggleText());},
                secondColumn,top+32,columnWidth,20));
        int filterGap=4;
        int filterWidth=(contentWidth-filterGap*2)/3;
        addRenderableWidget(brass(filterText(0), b->{scanStructures=!scanStructures;b.setMessage(filterText(0));},
                firstColumn,top+76,filterWidth,18));
        addRenderableWidget(brass(filterText(1), b->{scanLiving=!scanLiving;b.setMessage(filterText(1));},
                firstColumn+filterWidth+filterGap,top+76,filterWidth,18));
        addRenderableWidget(brass(filterText(2), b->{scanMissiles=!scanMissiles;b.setMessage(filterText(2));},
                firstColumn+(filterWidth+filterGap)*2,top+76,contentWidth-filterWidth*2-filterGap*2,18));
        rangeBox=numericBox(secondField,top+102,fieldWidth,radarRange,value->radarRange=value);
        defenseRangeSlider=new DefenseRangeSlider(firstColumn,top+126,contentWidth,20,
                automaticMissileDefenseRange, value->automaticMissileDefenseRange=value);
        addRenderableWidget(defenseRangeSlider);
        yawBox=numericBox(firstField,top+150,fieldWidth,yawSpeed,value->yawSpeed=value);
        pitchBox=numericBox(secondField,top+150,fieldWidth,pitchSpeed,value->pitchSpeed=value);
        yawBox.setTooltip(Tooltip.create(Component.translatable("screen.create_fire_control.auto_defense.yaw_speed")));
        pitchBox.setTooltip(Tooltip.create(Component.translatable("screen.create_fire_control.auto_defense.pitch_speed")));
        depressionBox=numericBox(firstField,top+174,fieldWidth,depressionLimit,value->depressionLimit=value);
        elevationBox=numericBox(secondField,top+174,fieldWidth,elevationLimit,value->elevationLimit=value);
        addRenderableWidget(brass(reverseText(true),b->{
            yawReversed=!yawReversed;b.setMessage(reverseText(true));
        },firstColumn,top+198,columnWidth,20));
        addRenderableWidget(brass(reverseText(false),b->{
            pitchReversed=!pitchReversed;b.setMessage(reverseText(false));
        },secondColumn,top+198,columnWidth,20));
        fireRangeBox=numericBox(firstField,top+222,fieldWidth,cannonFireRange,
                value->cannonFireRange=value);
        fireRangeBox.setTooltip(Tooltip.create(Component.translatable("screen.create_fire_control.auto_defense.fire_range")));
        EditBox powerBox=integerBox(secondField,top+222,fieldWidth,cannonFirePower,
                value->cannonFirePower=value);
        powerBox.setTooltip(Tooltip.create(Component.translatable("screen.create_fire_control.auto_defense.fire_power")));
        mapUpdateIntervalBox=integerBox(
                firstField,top+246,fieldWidth,mapUpdateIntervalSeconds,
                value->mapUpdateIntervalSeconds=value);
        addRenderableWidget(brass(resolutionText(),button->{
            mapResolution=mapResolution==128?256:mapResolution==256?512:128;
            button.setMessage(resolutionText());
        },secondColumn,top+246,columnWidth,20));
        int doneY = compactLayout() ? top + 8 : top + panelHeight() - 30;
        int doneX = compactLayout() ? left + panelWidth - 68 : width / 2 - 60;
        doneButton=addRenderableWidget(StabilizerControllerScreen.createBrassButton(Component.translatable("gui.done"),b->{
            PacketDistributor.sendToServer(new AutoDefenseSettingsPayload(initial.pos(),initial.subLevelId(),automaticAttack,
                    scanStructures, scanLiving, scanMissiles,
                    automaticMissileDefense,
                    automaticMissileDefenseRange,
                    missileProximityRange,
                    radarRange,yawSpeed,pitchSpeed,depressionLimit,elevationLimit,
                    yawReversed,pitchReversed,cannonFireRange,
                    cannonFirePower,
                    mapUpdateIntervalSeconds,mapResolution)); onClose();
        },doneX,doneY,compactLayout() ? 60 : 120,compactLayout()?18:22,true));
        for(var child:children())if(child instanceof AbstractWidget widget && widget!=structurePreviewButton && widget!=doneButton)contentWidgets.add(widget);
        for(var widget:contentWidgets)widget.visible=widget.getY()+widget.getHeight()>top+29&&widget.getY()<contentBottom();
    }

    private Button brass(Component message,Button.OnPress action,int x,int y,int width,int height){
        return StabilizerControllerScreen.createBrassButton(message,action,x,y,width,height,false);
    }

    private int panelWidth() {
        return Math.max(280,Math.min(MAX_PANEL_WIDTH,width-PANEL_MARGIN*2));
    }

    private int panelHeight() {
        return Math.min(compactLayout() ? COMPACT_PANEL_HEIGHT : NORMAL_PANEL_HEIGHT,Math.max(80,height-PANEL_MARGIN*2));
    }

    private boolean compactLayout() {
        return height < NORMAL_PANEL_HEIGHT + PANEL_MARGIN * 2;
    }

    private int panelTop() {
        int panelHeight = panelHeight();
        return Math.max(PANEL_MARGIN,Math.min(height-panelHeight-PANEL_MARGIN,
                height/2-panelHeight/2));
    }
    private EditBox numericBox(int x,int y,int width,double initialValue,java.util.function.DoubleConsumer setter){
        EditBox box=new EditBox(font,x,y,width,20,Component.empty());
        box.setValue(String.format(java.util.Locale.ROOT,"%.1f",initialValue));
        box.setFilter(s->s.isEmpty()||s.matches("[0-9]*\\.?[0-9]*"));
        box.setResponder(s->{try{setter.accept(Double.parseDouble(s));}catch(NumberFormatException ignored){}});
        addRenderableWidget(box);return box;
    }
    private EditBox integerBox(int x,int y,int width,int initialValue,
                               java.util.function.IntConsumer setter){
        EditBox box=new EditBox(font,x,y,width,20,Component.empty());
        box.setValue(Integer.toString(initialValue));
        box.setFilter(s->s.isEmpty()||s.matches("[0-9]*"));
        box.setResponder(s->{try{setter.accept(Integer.parseInt(s));}catch(NumberFormatException ignored){}});
        addRenderableWidget(box);return box;
    }
    private Component toggleText(){return Component.translatable("screen.create_fire_control.auto_defense.attack",Component.translatable(automaticAttack?"options.on":"options.off"));}
    private Component defenseToggleText(){return Component.translatable("screen.create_fire_control.auto_defense.missile_defense",
            Component.translatable(automaticMissileDefense?"options.on":"options.off"));}
    private Component filterText(int category){
        String key = switch (category) {
            case 0 -> "screen.create_fire_control.auto_defense.filter.structures";
            case 1 -> "screen.create_fire_control.auto_defense.filter.living";
            default -> "screen.create_fire_control.auto_defense.filter.missiles";
        };
        boolean enabled = switch (category) {
            case 0 -> scanStructures;
            case 1 -> scanLiving;
            default -> scanMissiles;
        };
        return Component.literal(enabled ? "[\u221a] " : "[ ] ")
                .append(Component.translatable(key));
    }
    private Component resolutionText(){return Component.translatable("screen.create_fire_control.auto_defense.map_resolution_value",mapResolution,mapResolution);}
    private Component reverseText(boolean yaw){
        boolean reversed=yaw?yawReversed:pitchReversed;
        return Component.translatable(yaw?"screen.create_fire_control.auto_defense.yaw_reverse":"screen.create_fire_control.auto_defense.pitch_reverse",
                Component.translatable(reversed?"options.on":"options.off"));
    }
    @Override public void render(GuiGraphics g,int mx,int my,float pt){
        if (structurePreviewButton != null) structurePreviewButton.visible = StructurePreviewScreen.available(initial.pos(), initial.subLevelId());
        g.fill(0,0,width,height,0x68000000); int panelWidth=panelWidth(),l=(width-panelWidth)/2,t=panelTop(),r=l+panelWidth,b=t+panelHeight();
        int contentWidth=panelWidth-CONTENT_MARGIN*2;
        int columnWidth=(contentWidth-COLUMN_GAP)/2;
        int firstColumn=l+CONTENT_MARGIN;
        int secondColumn=firstColumn+columnWidth+COLUMN_GAP;
        g.fill(l+4,t+5,r+5,b+6,0x90000000);
        g.fill(l,t,r,b,0xFF342F28);g.fill(l+2,t+2,r-2,b-2,0xFFB8903D);
        g.fill(l+5,t+5,r-5,b-5,0xFF76664A);g.fill(l+7,t+28,r-7,b-7,0xFFE2D2AC);
        g.fill(l+7,t+7,r-7,t+27,0xFF4B4438);g.fill(l+8,t+27,r-8,t+29,0xFF947436);
        for(int x:new int[]{l+4,r-6})for(int y:new int[]{t+4,b-6}){
            g.fill(x,y,x+2,y+2,0xFFDEC36C);g.fill(x+1,y+1,x+2,y+2,0xFF5A4828);
        }
        g.enableScissor(l+7,t+29,r-7,contentBottom());
        super.render(g,mx,my,pt);
        t-=scrollOffset;
        g.drawString(font,Component.translatable("screen.create_fire_control.auto_defense.filter"),
                firstColumn,t+59,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.create_fire_control.auto_defense.range"),firstColumn,t+108,0xFF3D382F,false);
        int labelWidth=columnWidth-Math.min(76,Math.max(58,columnWidth/2))-4;
        fieldLabel(g,"yaw_speed",firstColumn,t+156,labelWidth);
        fieldLabel(g,"pitch_speed",secondColumn,t+156,labelWidth);
        g.drawString(font,Component.translatable("screen.create_fire_control.auto_defense.depression"),firstColumn,t+180,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.create_fire_control.auto_defense.elevation"),secondColumn,t+180,0xFF3D382F,false);
        g.drawString(font,Component.translatable("screen.create_fire_control.auto_defense.fire_range_short"),firstColumn,t+228,0xFF3D382F,false);
        fieldLabel(g,"fire_power",secondColumn,t+228,labelWidth);
        g.drawString(font,Component.translatable("screen.create_fire_control.auto_defense.map_update_interval_short"),firstColumn,t+252,0xFF3D382F,false);
        g.disableScissor();
        t=panelTop();
        int titleRight=compactLayout()?(structurePreviewButton!=null?structurePreviewButton.getX()-6:doneButton.getX()-6):r-7;
        int titleCenter=compactLayout()?(l+10+titleRight)/2:width/2;
        g.drawString(font,title,titleCenter-font.width(title)/2,t+12,0xFFFFFFFF,false);
        if(structurePreviewButton!=null)structurePreviewButton.render(g,mx,my,pt);
        doneButton.render(g,mx,my,pt);
        if(maxScroll()>0){
            int trackTop=t+32,trackBottom=contentBottom()-3,trackX=r-11;
            g.fill(trackX,trackTop,trackX+2,trackBottom,0xFF76664A);
            int handle=trackTop+(int)((double)scrollOffset/maxScroll()*Math.max(0,trackBottom-trackTop-12));
            g.fill(trackX-1,handle,trackX+3,handle+12,0xFFB8903D);
        }
    }
    private int contentBottom(){return compactLayout()?panelTop()+panelHeight()-7:doneButton.getY()-6;}
    private int maxScroll(){return Math.max(0,panelTop()+267-contentBottom());}
    private void fieldLabel(GuiGraphics graphics,String key,int x,int y,int width){
        Component full=Component.translatable("screen.create_fire_control.auto_defense."+key);
        Component label=font.width(full)<=width?full:Component.translatable("screen.create_fire_control.auto_defense."+key+"_short");
        graphics.drawString(font,label,x,y,0xFF3D382F,false);
    }
    private void setScroll(int next){
        next=Math.clamp(next,0,maxScroll());int delta=scrollOffset-next;scrollOffset=next;
        for(var widget:contentWidgets){widget.setY(widget.getY()+delta);widget.visible=widget.getY()+widget.getHeight()>panelTop()+29&&widget.getY()<contentBottom();}
    }
    private void scrollFromMouse(double y){
        int top=panelTop()+32,bottom=contentBottom()-3;
        setScroll((int)Math.round((y-top-6)/Math.max(1,bottom-top-12)*maxScroll()));
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        int l=(width-panelWidth())/2;
        if(x>=l+7&&x<l+panelWidth()-7&&y>=panelTop()+29&&y<contentBottom()&&maxScroll()>0){
            setScroll(scrollOffset-(int)Math.round(vertical*24));
            return true;
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        int right=(width+panelWidth())/2;
        if(button==0&&maxScroll()>0&&x>=right-14&&x<right-7&&y>=panelTop()+32&&y<contentBottom()-3){draggingScroll=true;scrollFromMouse(y);return true;}
        if(y<panelTop()+29||y>=contentBottom()){
            if(structurePreviewButton!=null&&structurePreviewButton.mouseClicked(x,y,button))return true;
            return doneButton.mouseClicked(x,y,button);
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(draggingScroll&&button==0){scrollFromMouse(y);return true;}return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){draggingScroll=false;return super.mouseReleased(x,y,button);}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float p){}
    @Override public boolean isPauseScreen(){return false;}

    private static final class DefenseRangeSlider extends AbstractSliderButton {
        private final java.util.function.DoubleConsumer consumer;

        private DefenseRangeSlider(int x, int y, int width, int height, double range,
                                   java.util.function.DoubleConsumer consumer) {
            super(x, y, width, height, Component.empty(), toSlider(range));
            this.consumer = consumer;
            updateMessage();
        }

        @Override protected void updateMessage() {
            double range = fromSlider(value);
            setMessage(Component.translatable("screen.create_fire_control.auto_defense.defense_range",
                    range <= 0.0 ? Component.translatable("screen.create_fire_control.connected_display.range.unlimited") : formatRange(range)));
        }

        @Override protected void applyValue() {
            double range = fromSlider(value);
            consumer.accept(range);
            updateMessage();
        }

        @Override public void renderWidget(GuiGraphics g,int mx,int my,float partialTick){
            int x=getX(),y=getY(),right=x+width;
            g.fill(x,y,right,y+height,0xFF514C42);
            g.fill(x+1,y+1,right-1,y+height-1,isHovered()?0xFFEAD9AE:0xFFD8C59B);
            int track=y+height-5,handle=x+7+(int)Math.round(value*(width-14));
            g.fill(x+7,track-1,right-7,track+1,0xFF5B554A);
            g.fill(handle-3,track-3,handle+4,track+4,0xFF493F31);
            g.fill(handle-2,track-2,handle+3,track+3,0xFFC9A34A);
            var font=Minecraft.getInstance().font;
            g.drawString(font,getMessage(),x+(width-font.width(getMessage()))/2,y+3,0xFF2F2A23,false);
        }

        private static double toSlider(double range) {
            if (range <= 0.0 || range >= 10_000.0) return 1.0;
            return Math.max(0.0, Math.min(1.0, (range - 50.0) / 9_950.0));
        }

        private static double fromSlider(double value) {
            if (value >= 0.999) return 0.0;
            return 50.0 + Math.round(value * 9_950.0 / 10.0) * 10.0;
        }

        private static Component formatRange(double range) {
            return range >= 1000.0
                    ? Component.translatable("screen.create_fire_control.connected_display.range.kilometers",
                            String.format(java.util.Locale.ROOT, "%.1f", range / 1000.0))
                    : Component.translatable("screen.create_fire_control.connected_display.range.meters",
                            String.format(java.util.Locale.ROOT, "%.0f", range));
        }
    }
}
