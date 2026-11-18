package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.preview.StructureHullCache;
import com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer;
import com.hooya.stabilizedturret.client.preview.StructurePreviewMaterials;
import com.hooya.stabilizedturret.client.preview.PreviewModelResources;
import com.hooya.stabilizedturret.content.bearing.PreviewBearingSettings;
import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.network.*;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Shared physical-structure view for the controller and the automatic-defense display. */
public final class StructurePreviewScreen extends Screen {
    private final Screen parent;
    private final BlockPos hostPos;
    private final UUID hostBody;
    private final boolean display;
    private final Consumer<StructurePreviewStatePayload.Device> onSaved;
    private final StructurePreviewRenderer renderer = new StructurePreviewRenderer();
    private StructurePreviewStatePayload state;
    private StructurePreviewMaterials materials;
    private final Map<UUID, Matrix4f> bodyMatrices = new LinkedHashMap<>();
    private final Map<StructureHullCache.SectionKey, StructureHullCache.Section> completed = new LinkedHashMap<>();
    private List<StructureHullCache.SectionKey> queue = List.of();
    private int sampled, ticks, nextSample = 1200, listPage;
    private int modelGeneration=PreviewModelResources.generation();
    private boolean fitted;
    private String selectedKey, pendingKey;
    private PreviewBearingSettings pendingSettings;
    private int pendingSince;
    private StructurePreviewStatePayload.Device editing;
    private double depression, elevation, speed, ox, oy, oz, leftLimit, rightLimit;
    private boolean reversed, hidden;
    private int centerDirection, fixedView;
    private double yaw = -35, pitch = 25, zoom = 1, panX, panY;
    private Vector3f center = new Vector3f();
    private float radius = 4;
    private double pressX, pressY;
    private int draggingButton = -1;
    private boolean dragged;
    private int editorScroll;
    private Component status = text("loading");
    private final List<AbstractWidget> sideWidgets = new ArrayList<>();
    private final List<Label> sideLabels = new ArrayList<>();
    private final List<EditBox> numericFields = new ArrayList<>();
    private Button applyButton;
    private boolean sidebarCollapsed;
    private Button drawerToggle;
    private Button outlineToggle;
    private boolean showDeviceBoxes=true;
    private boolean closed=true;
    private final Map<AbstractWidget,Component> previewTooltips=new IdentityHashMap<>();
    private Button contextButton;
    private StructurePreviewRenderer.Target contextTarget;
    private StructurePreviewRenderer.Target rightClickTarget;
    private record Label(Component text, int y) {}

    public StructurePreviewScreen(Screen parent, BlockPos hostPos, UUID hostBody, boolean display,
                                  Consumer<StructurePreviewStatePayload.Device> onSaved) {
        super(text("title")); this.parent=parent; this.hostPos=hostPos; this.hostBody=hostBody;
        this.display=display; this.onSaved=onSaved;
    }
    public static Component text(String key, Object... args) {
        return Component.translatable("screen.create_fire_control.preview."+key,args);
    }
    public static boolean available(BlockPos pos, UUID body) {
        var level=Minecraft.getInstance().level;
        if (level==null || body==null) return false;
        SubLevel sub=SubLevelContainer.getContainer(level).getSubLevel(body);
        if (sub==null || sub.isRemoved()) return false;
        var containing=Sable.HELPER.getContaining(sub.getLevel(),pos);
        return containing!=null && body.equals(containing.getUniqueId());
    }
    public static void accept(StructurePreviewStatePayload p) {
        if (Minecraft.getInstance().screen instanceof StructurePreviewScreen screen
                && screen.hostPos.equals(p.pos()) && screen.hostBody.equals(p.body()) && screen.display==p.display()) screen.receive(p);
    }
    private void receive(StructurePreviewStatePayload p) {
        if(closed)return;
        boolean first=state==null;
        var nextMaterials=StructurePreviewMaterials.from(p);
        boolean changed=first || !state.bodies().equals(p.bodies()) || !nextMaterials.equals(materials);
        materials=nextMaterials;
        state=p;
        if (first) status=text("ready");
        if (pendingKey!=null) {
            var match=p.devices().stream().filter(d->key(d).equals(pendingKey)).findFirst().orElse(null);
            if (match==null || pendingSettings.equals(match.settings())) {
                if (match!=null) onSaved.accept(match);
                status=text(match==null ? "stale" : "saved"); pendingKey=null;pendingSettings=null;editing=null;editorScroll=0;
            }
        }
        if (changed) { updateMatrices(1); startSampling(); }
        if (first || editing==null) rebuild();
    }
    private void request() { if(!closed)PacketDistributor.sendToServer(new RequestStructurePreviewPayload(hostPos,hostBody,display)); }
    @Override protected void init() { closed=false;contextButton=null;rebuild(); if (state==null) request(); }
    private int sidebarWidth() { return sidebarCollapsed?0:editing!=null?Math.min(176,Math.max(142,width/4)):Math.min(148,Math.max(116,width/5)); }
    private int sideX() { return width-sidebarWidth()-12; }
    private int viewWidth() { return Math.max(48,sidebarCollapsed||editing==null?width-16:sideX()-16); }
    private int footerTop() { return height-3*(font.lineHeight+3)-12; }
    private int viewHeight() { return Math.max(30,footerTop()-5-52); }
    private int contentTop() { return 56; }
    private int contentBottom() { return footerTop()-31; }
    private int sidebarBottom() {
        if(editing!=null)return footerTop()-5;
        int count=state==null?0:Math.min(listCapacity(),state.devices().size());
        return Math.min(footerTop()-5,contentTop()+22+count*22+27);
    }
    private int listCapacity(){return Math.max(1,(contentBottom()-contentTop()-29)/22);}
    private static BrassBuilder brass(Component message,Button.OnPress action){return new BrassBuilder(message,action);}
    private record BrassBuilder(Component message,Button.OnPress action,int x,int y,int width,int height) {
        BrassBuilder(Component message,Button.OnPress action){this(message,action,0,0,0,0);}
        BrassBuilder bounds(int x,int y,int width,int height){return new BrassBuilder(message,action,x,y,width,height);}
        Button build(){return StabilizerControllerScreen.createBrassButton(message,action,x,y,width,height,message.equals(text("apply")));}
    }
    private void rebuild() {
        clearWidgets(); sideWidgets.clear(); sideLabels.clear(); numericFields.clear(); previewTooltips.clear();applyButton=null;
        addRenderableWidget(brass(text("back"),b->onClose()).bounds(width-62,8,54,18).build());
        addRenderableWidget(brass(text("reset"),b->{yaw=-35;pitch=25;zoom=1;panX=panY=0;fitted=false;fit();})
                .bounds(8,29,42,18).build());
        addRenderableWidget(brass(text("resample"),b->{startSampling(); request();})
                .bounds(54,29,58,18).build());
        drawerToggle=addRenderableWidget(brass(Component.literal(sidebarCollapsed?"<":">"),b->{
            sidebarCollapsed=!sidebarCollapsed;contextButton=null;rebuild();setFocused(drawerToggle);
        }).bounds(sidebarCollapsed?width-20:sideX()-14,55,14,28).build());
        tooltip(drawerToggle,text(sidebarCollapsed?"show_devices":"collapse"));
        int controlsRight=sidebarCollapsed?width-8:sideX()-4;
        boolean fixedViewVisible=controlsRight>=264;
        if (fixedViewVisible) addRenderableWidget(brass(text("view"),b->{
            fixedView=(fixedView+1)%6;
            yaw=switch(fixedView){case 0->0;case 1->180;case 2->90;case 3->-90;default->0;};
            pitch=fixedView==4?90:fixedView==5?-90:0;
        }).bounds(116,29,58,18).build());
        int outlineX=fixedViewVisible?178:116;
        int outlineWidth=Math.min(82,Math.max(42,controlsRight-outlineX));
        outlineToggle=addRenderableWidget(brass(toggle(outlineWidth<68?"outlines_short":"outlines",showDeviceBoxes),b->{
            showDeviceBoxes=!showDeviceBoxes;contextButton=null;b.setMessage(toggle(outlineWidth<68?"outlines_short":"outlines",showDeviceBoxes));
        }).bounds(outlineX,29,outlineWidth,18).build());
        tooltip(outlineToggle,text("outlines_hint"));
        if (state==null||sidebarCollapsed) return;
        if (editing!=null) buildEditor(); else buildDeviceList();
    }
    private void buildDeviceList() {
        int capacity=listCapacity();
        int pages=Math.max(1,(state.devices().size()+capacity-1)/capacity);
        listPage=Math.clamp(listPage,0,pages-1);
        for (int i=listPage*capacity; i<Math.min(state.devices().size(),(listPage+1)*capacity); i++) {
            var d=state.devices().get(i); int y=contentTop()+22+(i-listPage*capacity)*22;
            Component label=roleName(d).copy().append(" "+(i+1));
            Component shortLabel=Component.literal(font.plainSubstrByWidth(label.getString(),sidebarWidth()-24));
            Button b=brass(shortLabel,button->{selectedKey=key(d); if(d.editable())openEditor(d);})
                    .bounds(sideX()+9,y,sidebarWidth()-18,19).build();
            tooltip(b,Component.translatable(d.name()).copy().append("\n"+d.pos().toShortString())
                    .append("\n").append(text(d.editable()?"right_click":"device_only")));
            sideWidgets.add(addRenderableWidget(b));
        }
        if(pages>1){
            int finalPages=pages;
            addRenderableWidget(brass(Component.literal("<"),b->{listPage=(listPage+finalPages-1)%finalPages;rebuild();})
                    .bounds(sideX()+9,sidebarBottom()-25,24,18).build());
            addRenderableWidget(brass(Component.literal(">"),b->{listPage=(listPage+1)%finalPages;rebuild();})
                    .bounds(width-45,sidebarBottom()-25,24,18).build());
        }
    }
    private static Component roleName(StructurePreviewStatePayload.Device d) {
        return Component.translatable("screen.create_fire_control.device_type."+d.role().toLowerCase(Locale.ROOT));
    }
    private void openEditor(StructurePreviewStatePayload.Device device) {
        if (pendingKey!=null || !device.editable() || device.settings()==null) return;
        editing=device; selectedKey=key(device); editorScroll=0;sidebarCollapsed=false;
        var s=device.settings(); depression=s.depression(); elevation=s.elevation(); speed=s.speed();
        ox=s.offset().x; oy=s.offset().y; oz=s.offset().z;
        reversed=s.reversed(); hidden=s.headHidden(); leftLimit=s.limits().left();rightLimit=s.limits().right();centerDirection=s.limits().centerDirection();
        rebuild();
    }
    private void buildEditor() {
        int y=contentTop()+22-editorScroll;
        field("offset_x",y,ox,-20,20,v->ox=v); y+=27;
        field("offset_y",y,oy,-20,20,v->oy=v); y+=27;
        field("offset_z",y,oz,-20,20,v->oz=v); y+=27;
        field("speed",y,speed,0,720,v->speed=v); y+=27;
        if(editing.role().equals("YAW")) {
            field("left",y,leftLimit,0,180,v->leftLimit=v);y+=27;
            field("right",y,rightLimit,0,180,v->rightLimit=v);y+=27;
            Button b=brass(centerText(),button->{centerDirection=(centerDirection+1)%4;button.setMessage(centerText());})
                    .bounds(sideX()+5,y,sidebarWidth()-10,20).build();
            sideWidgets.add(addRenderableWidget(b));y+=27;
        }else{
            field("depression",y,depression,0,90,v->depression=v);y+=27;
            field("elevation",y,elevation,0,89,v->elevation=v);y+=27;
        }
        sideWidgets.add(addRenderableWidget(brass(toggle("reverse",reversed),b->{reversed=!reversed;b.setMessage(toggle("reverse",reversed));})
                .bounds(sideX()+5,y,sidebarWidth()-10,20).build()));y+=27;
        sideWidgets.add(addRenderableWidget(brass(toggle("hide_head",hidden),b->{hidden=!hidden;b.setMessage(toggle("hide_head",hidden));})
                .bounds(sideX()+5,y,sidebarWidth()-10,20).build()));
        applyButton=addRenderableWidget(brass(text("apply"),b->save())
                .bounds(sideX()+9,footerTop()-28,(sidebarWidth()-23)/2,18).build());
        addRenderableWidget(brass(Component.translatable("gui.cancel"),b->{editing=null;editorScroll=0;rebuild();})
                .bounds(sideX()+14+(sidebarWidth()-23)/2,footerTop()-28,(sidebarWidth()-23)/2,18).build());
        updateEditorVisibility();
    }
    private Component toggle(String key,boolean value){return text(key).copy().append(": ").append(Component.translatable(value?"options.on":"options.off"));}
    private Component centerText(){return text("center").copy().append(": ").append(Component.translatable(
            "screen.create_fire_control.yaw_center_"+new String[]{"east","south","west","north"}[centerDirection]));}
    private void field(String key,int y,double initial,double min,double max,DoubleConsumer setter) {
        int boxWidth=Math.min(66,sidebarWidth()/2-4);
        EditBox box=new EditBox(font,width-23-boxWidth,y,boxWidth,19,text(key));
        box.setMaxLength(14);box.setValue(String.format(Locale.ROOT,"%.3f",initial).replaceAll("0+$", "").replaceAll("\\.$", ".0"));
        box.setFilter(s->s.matches("-?[0-9]*(\\.[0-9]*)?"));
        box.setResponder(s->{
            try{double v=Double.parseDouble(s);boolean valid=Double.isFinite(v)&&v>=min&&v<=max;
                box.setTextColor(valid?0xFFFFFF:0xFF7777);if(valid)setter.accept(v);
            }catch(NumberFormatException ignored){box.setTextColor(0xFF7777);}
        });
        tooltip(box,text("range",min,max));sideWidgets.add(addRenderableWidget(box));numericFields.add(box);
        sideLabels.add(new Label(text(key),y+6));
    }
    private void updateEditorVisibility(){for(var w:sideWidgets)w.visible=w.getY()>=contentTop()+20&&w.getY()+w.getHeight()<=contentBottom();}
    private boolean validEditor(){
        for(EditBox box:numericFields)try{
            double v=Double.parseDouble(box.getValue()); if(!Double.isFinite(v))return false;
            int i=numericFields.indexOf(box);double max=i<3?20:i==3?720:editing.role().equals("YAW")?180:i==4?90:89;
            if(v<(i<3?-20:0)||v>max)return false;
        }catch(NumberFormatException ignored){return false;}
        return true;
    }
    private void save(){
        if(editing==null||pendingKey!=null||!validEditor())return;
        pendingKey=key(editing); pendingSince=ticks; status=text("saving");
        pendingSettings=new PreviewBearingSettings(depression,elevation,speed,reversed,hidden,new Vec3(ox,oy,oz),
                new YawLimits(leftLimit,rightLimit,centerDirection)).clamped();
        PacketDistributor.sendToServer(new StructurePreviewSettingsPayload(hostPos,hostBody,display,editing.role(),editing.pos(),editing.body(),
                pendingSettings));
    }
    private static String key(StructurePreviewStatePayload.Device d){return d.role()+":"+d.body()+":"+d.pos().asLong();}
    private SubLevel reference(){return minecraft==null||minecraft.level==null?null:SubLevelContainer.getContainer(minecraft.level).getSubLevel(hostBody);}
    private void updateMatrices(float partialTick){
        bodyMatrices.clear();SubLevel reference=reference();if(reference==null||state==null)return;
        var container=SubLevelContainer.getContainer(minecraft.level);
        for(UUID id:state.bodies()) {var sub=container.getSubLevel(id);if(sub!=null&&!sub.isRemoved())bodyMatrices.put(id,StructurePreviewRenderer.bodyMatrix(sub,reference,partialTick));}
    }
    private void startSampling(){
        if(closed)return;
        if(minecraft==null||minecraft.level==null||state==null)return;
        queue=new ArrayList<>();completed.clear();sampled=0;
        includeLoadedSections();
        renderer.retainSections(queue);
        nextSample=ticks+1200;fitted=false;fit();
    }
    private void includeLoadedSections(){
        Set<StructureHullCache.SectionKey> included=new HashSet<>(queue);
        var container=SubLevelContainer.getContainer(minecraft.level);
        for(UUID id:state.bodies()){
            var body=container.getSubLevel(id);if(body==null||body.isRemoved())continue;
            for(var section:StructureHullCache.sections(body))if(included.add(section))queue.add(section);
        }
    }
    @Override public void tick(){
        if(closed)return;
        ticks++;
        if(!available(hostPos,hostBody)){onClose();return;}
        if(ticks%40==0)request();
        if(pendingKey!=null && ticks-pendingSince>120){pendingKey=null;pendingSettings=null;status=text("unavailable");}
        if(state==null){if(ticks>120)status=text("unavailable");return;}
        if(ticks%20==0)includeLoadedSections();
        if(modelGeneration!=PreviewModelResources.generation()) {
            modelGeneration=PreviewModelResources.generation();renderer.close();startSampling();
        }
        if(ticks>=nextSample&&sampled>=queue.size())startSampling();
        long deadline=System.nanoTime()+4_000_000;
        for(int steps=0;sampled<queue.size()&&steps<8;steps++){
            var key=queue.get(sampled++);var body=SubLevelContainer.getContainer(minecraft.level).getSubLevel(key.body());
            if(body!=null&&!body.isRemoved()){
                var section=StructureHullCache.sample(minecraft.level,body,key,materials);
                completed.put(key,section);renderer.publishSection(section);
            }
            if(sampled==queue.size())fitted=false;
            if(System.nanoTime()>=deadline)break;
        }
        int entityCount=renderer.entityCount();
        renderer.updateEntities(new HashSet<>(state.bodies()),new HashSet<>(state.opaqueBodies()));
        if(entityCount!=renderer.entityCount())fitted=false;
        if(sampled>=queue.size())renderer.tick();
        if(applyButton!=null)applyButton.active=pendingKey==null&&validEditor();
    }
    private void fit(){
        if(fitted||state==null||reference()==null)return;
        updateMatrices(1); Vector3f min=new Vector3f(Float.POSITIVE_INFINITY),max=new Vector3f(Float.NEGATIVE_INFINITY);
        var container=SubLevelContainer.getContainer(minecraft.level);
        for(var entry:bodyMatrices.entrySet()){
            SubLevel body=container.getSubLevel(entry.getKey());var b=body.getPlot().getBoundingBox();BlockPos origin=body.getPlot().getCenterBlock();
            if(b.minX()>b.maxX()||b.minY()>b.maxY()||b.minZ()>b.maxZ())continue;
            for(int i=0;i<8;i++){
                Vector3f p=new Vector3f(((i&1)==0?b.minX():b.maxX()+1)-origin.getX(),((i&2)==0?b.minY():b.maxY()+1)-origin.getY(),((i&4)==0?b.minZ():b.maxZ()+1)-origin.getZ());
                entry.getValue().transformPosition(p);min.min(p);max.max(p);
            }
        }
        renderer.expandBounds(min,max,bodyMatrices);
        if(Float.isFinite(min.x)){center=min.add(max,new Vector3f()).mul(.5f);radius=Math.max(2,min.distance(max)*.55f);fitted=true;}
    }
    private Matrix4f camera(){return new Matrix4f().translate((float)panX,(float)panY,0).rotateX((float)Math.toRadians(pitch))
            .rotateY((float)Math.toRadians(yaw)).translate(-center.x,-center.y,-center.z);}
    private Matrix4f projection(){float r=(float)(radius/zoom);float aspect=(float)viewWidth()/viewHeight();
        return new Matrix4f().setOrtho(-r*aspect,r*aspect,-r,r,-radius*16,radius*16);}
    private Matrix4f boxMatrix(StructurePreviewStatePayload.Device device){
        Matrix4f body=bodyMatrices.get(device.body());
        BlockPos origin;
        if(device.body()==null){var ref=reference();if(ref==null)return null;body=StructurePreviewRenderer.bodyMatrix(null,ref,1);origin=BlockPos.ZERO;}
        else{var sub=SubLevelContainer.getContainer(minecraft.level).getSubLevel(device.body());if(body==null||sub==null)return null;origin=sub.getPlot().getCenterBlock();}
        return new Matrix4f(body).translate(device.pos().getX()-origin.getX()-.035f,device.pos().getY()-origin.getY()-.035f,device.pos().getZ()-origin.getZ()-.035f).scale(1.07f);
    }
    private StructurePreviewStatePayload.Device pick(double mx,double my){
        if(state==null||!inView(mx,my))return null;
        updateMatrices(1);Matrix4f clip=projection().mul(camera());
        double x=(mx-8)/viewWidth()*2-1,y=1-(my-52)/viewHeight()*2;
        double depth=Double.POSITIVE_INFINITY;StructurePreviewStatePayload.Device selected=null;
        for(var d:state.devices()){
            Matrix4f model=boxMatrix(d);if(model==null)continue;
            double candidate=StructurePreviewRenderer.pickDepth(new Matrix4f(clip).mul(model),x,y);
            if(candidate<depth){depth=candidate;selected=d;}
        }
        return selected;
    }
    private boolean inSidePanel(double x,double y){return !sidebarCollapsed&&x>=sideX()&&x<width-6&&y>=29&&y<sidebarBottom();}
    private boolean inView(double x,double y){return x>=8&&x<8+viewWidth()&&y>=52&&y<52+viewHeight()&&!inSidePanel(x,y);}
    private void openTransparency(double x,double y,StructurePreviewRenderer.Target target) {
        contextTarget=target;
        int labelWidth=Math.max(font.width(toggle("transparency_short",true)),font.width(toggle("transparency_short",false)));
        int popupWidth=Math.min(viewWidth()-8,Math.max(48,labelWidth+12));
        contextButton=StabilizerControllerScreen.createBrassButton(transparencyText(),b->{
            renderer.toggleTransparency(contextTarget);b.setMessage(transparencyText());
        },Math.clamp((int)x,12,Math.max(12,8+viewWidth()-popupWidth-4)),
                Math.clamp((int)y,56,Math.max(56,52+viewHeight()-20)),popupWidth,16,false);
    }
    private void tooltip(AbstractWidget widget,Component text){previewTooltips.put(widget,text);}
    private Component transparencyText(){return toggle("transparency_short",renderer.transparent(contextTarget));}
    private record NorthArrow(float x,float y,float dx,float dy,float depth) {}
    private NorthArrow northArrow() {
        if(closed||state==null||selectedKey==null)return null;
        for(var device:state.devices())if(device.role().equals("YAW")&&key(device).equals(selectedKey)) {
            Matrix4f model=boxMatrix(device);if(model==null)return null;
            Matrix4f view=camera().mul(model);
            Vector3f anchor=projection().mul(view).transformProject(new Vector3f(.5f,.5f,.5f));
            // YawLimits uses the bearing parent's local cardinal directions, including on nested bodies.
            Vector3f north=view.transformDirection(new Vector3f(0,0,-1)).normalize();
            return new NorthArrow(8+(anchor.x+1)*viewWidth()/2,52+(1-anchor.y)*viewHeight()/2,north.x,-north.y,north.z);
        }
        return null;
    }
    private static void northStroke(GuiGraphics g,float x1,float y1,float x2,float y2,int halfWidth,int color) {
        g.pose().pushPose();
        g.pose().translate(x1,y1,0);
        g.pose().mulPose(new Quaternionf().rotationZ((float)Math.atan2(y2-y1,x2-x1)));
        g.fill(-halfWidth,-halfWidth,(int)Math.ceil(Math.hypot(x2-x1,y2-y1))+halfWidth,halfWidth+1,color);
        g.pose().popPose();
    }
    private void renderNorthArrow(GuiGraphics g) {
        NorthArrow arrow=northArrow();if(arrow==null)return;
        float length=(float)Math.hypot(arrow.dx,arrow.dy),labelX,labelY;
        g.enableScissor(8,52,8+viewWidth(),52+viewHeight());
        g.pose().pushPose();g.pose().translate(arrow.x,arrow.y,0);
        try {
            if(length>.08f) {
                float dx=arrow.dx/length,dy=arrow.dy/length;
                g.pose().mulPose(new Quaternionf().rotationZ((float)Math.atan2(dy,dx)));
                for(int pass=0;pass<3;pass++) {
                    int color=pass==0?0xFF10151B:pass==1?0xFFFFF3D4:0xFFFF5549,half=2-pass;
                    northStroke(g,4,0,22,0,half,color);
                    northStroke(g,16,-4,22,0,half,color);
                    northStroke(g,16,4,22,0,half,color);
                }
                g.fill(-2,-2,3,3,0xFFFFF3D4);g.fill(-1,-1,2,2,0xFFFF5549);
                labelX=arrow.x+dx*36;labelY=arrow.y+dy*36;
            } else {
                // Looking straight along north: a dot points toward the viewer, a cross away.
                g.fill(-6,-6,7,7,0xFF10151B);g.fill(-5,-5,6,6,0xFFFFF3D4);g.fill(-4,-4,5,5,0xFFB82D29);
                if(arrow.depth>0)g.fill(-1,-1,2,2,0xFFFFF3D4);
                else {northStroke(g,-2,-2,2,2,0,0xFFFFF3D4);northStroke(g,-2,2,2,-2,0,0xFFFFF3D4);}
                labelX=arrow.x+22;labelY=arrow.y;
            }
        } finally {g.pose().popPose();}
        Component label=text("north");int labelWidth=font.width(label);
        int x=Math.clamp(Math.round(labelX-labelWidth/2f),12,Math.max(12,8+viewWidth()-labelWidth-4));
        int y=Math.clamp(Math.round(labelY-font.lineHeight/2f),56,Math.max(56,52+viewHeight()-font.lineHeight-4));
        g.fill(x-3,y-2,x+labelWidth+3,y+font.lineHeight+2,0xE610151B);
        g.drawString(font,label,x,y,0xFFFFF3D4,false);
        // Flush above the finished scene/boxes, before panels and popup menus are drawn.
        g.flush();g.disableScissor();
    }
    @Override public void render(GuiGraphics g,int mx,int my,float pt){
        if(closed)return;
        g.fill(0,0,width,height,0xFF252A2F);
        StabilizerControllerScreen.renderBrassFrame(g,1,1,width-1,height-1);
        g.fill(6,50,10+viewWidth(),54+viewHeight(),0xFF76664A);
        g.fill(8,52,8+viewWidth(),52+viewHeight(),0xFF151B21);
        g.drawString(font,title,12,12,0xFFE6D5AC,false);
        updateMatrices(pt);fit();
        if(state!=null){
            List<StructurePreviewRenderer.Box> boxes=new ArrayList<>();
            for(var d:showDeviceBoxes?state.devices():List.<StructurePreviewStatePayload.Device>of()){
                Matrix4f matrix=boxMatrix(d);if(matrix==null)continue;
                int color=key(d).equals(selectedKey)?0xFFBD52:d.role().equals("YAW")?0x57B5FF:d.role().equals("PITCH")?0x64E7A5:0xE9D577;
                boxes.add(new StructurePreviewRenderer.Box(matrix,color));
            }
            renderer.draw(g,8,52,viewWidth(),viewHeight(),camera(),projection(),bodyMatrices,boxes);
            renderNorthArrow(g);
        }
        if(!sidebarCollapsed)StabilizerControllerScreen.renderBrassFrame(g,sideX(),29,width-6,sidebarBottom());
        super.render(g,mx,my,pt);
        if(!sidebarCollapsed)g.drawString(font,text(editing==null?"devices":"edit_one"),sideX()+10,39,0xFFF4EBD5,false);
        if(editing!=null&&!sidebarCollapsed){
            g.enableScissor(sideX()+8,contentTop()+20,width-14,contentBottom());
            for(Label label:sideLabels)g.drawString(font,label.text,sideX()+10,label.y,0xFF3D382F,false);
            g.disableScissor();
            int number=1;
            for(int i=0;i<state.devices().size();i++)if(key(state.devices().get(i)).equals(key(editing))){number=i+1;break;}
            g.drawString(font,roleName(editing).copy().append(" "+number),sideX()+10,62,0xFF3D382F,false);
        }else if(state!=null&&!sidebarCollapsed)g.drawString(font,text("count",state.devices().size()),sideX()+10,62,0xFF3D382F,false);
        Component progress=sampled<queue.size()?text("sampling",sampled,queue.size()):state==null?status:pendingKey!=null?text("saving"):status;
        int footer=footerTop()+5,line=font.lineHeight+3;
        g.fill(8,footerTop(),width-8,footerTop()+1,0xFF947436);
        g.drawString(font,font.plainSubstrByWidth(text("help").getString(),width-24),12,footer,0xFF4B4438,false);
        g.drawString(font,font.plainSubstrByWidth(text("legend").getString(),width-24),12,footer+line,0xFF4B4438,false);
        g.drawString(font,font.plainSubstrByWidth(progress.getString(),width-24),12,footer+line*2,0xFF3D382F,false);
        if(contextButton!=null)contextButton.render(g,mx,my,pt);
        Component hovered=null;
        for(var entry:previewTooltips.entrySet())if(entry.getKey().visible&&entry.getKey().isMouseOver(mx,my))hovered=entry.getValue();
        if(contextButton!=null&&contextButton.isMouseOver(mx,my))hovered=null;
        if(hovered!=null)renderPreviewTooltip(g,hovered,mx,my);
    }
    /** Draw after the 3D scene and all controls; opaque model depth must never cut holes in UI text. */
    private void renderPreviewTooltip(GuiGraphics g,Component message,int mouseX,int mouseY) {
        List<FormattedCharSequence> lines=font.split(message,Math.min(210,Math.max(60,width-30)));
        int tipWidth=lines.stream().mapToInt(font::width).max().orElse(0)+10;
        int tipHeight=lines.size()*(font.lineHeight+2)+8;
        int x=Math.clamp(mouseX+10,8,Math.max(8,width-tipWidth-8));
        int y=Math.clamp(mouseY+12,8,Math.max(8,height-tipHeight-8));
        g.flush();g.pose().pushPose();g.pose().translate(0,0,600);
        try {
            g.fill(x-1,y-1,x+tipWidth+1,y+tipHeight+1,0xB0B8903D);
            g.fill(x,y,x+tipWidth,y+tipHeight,0xC021211D);
            for(int i=0;i<lines.size();i++)g.drawString(font,lines.get(i),x+5,y+4+i*(font.lineHeight+2),0xFFF4EBD5,false);
            g.flush();
        } finally {g.pose().popPose();}
    }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float pt) {}
    @Override public boolean mouseClicked(double x,double y,int button){
        draggingButton = -1;
        if(contextButton!=null){if(contextButton.mouseClicked(x,y,button))return true;contextButton=null;}
        if(drawerToggle!=null&&drawerToggle.mouseClicked(x,y,button))return true;
        if(inSidePanel(x,y))return super.mouseClicked(x,y,button);
        if(inView(x,y)){
            if(pendingKey!=null)return true;
            pressX=x;pressY=y;dragged=false;
            if (button==0 || button==2) draggingButton=button;
            if(button==1){
                var d=pick(x,y);
                if(d!=null){selectedKey=key(d);rightClickTarget=null;if(d.editable())openEditor(d);}
                else {
                    draggingButton=1;
                    rightClickTarget=renderer.pickTarget(projection().mul(camera()),bodyMatrices,
                            (x-8)/viewWidth()*2-1,1-(y-52)/viewHeight()*2);
                }
                return true;
            }
            return button==0||button==2;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(draggingButton==button&&(button==0||button==1||button==2)){
            dragged|=Math.hypot(x-pressX,y-pressY)>3;
            if(button==0){yaw+=dx*.65;pitch=Math.clamp(pitch+dy*.65,-90,90);}
            else if(button==2){panX+=dx*radius/zoom*2/viewHeight();panY-=dy*radius/zoom*2/viewHeight();}
            return true;
        }return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        if(draggingButton==1&&button==1){
            draggingButton=-1;
            if(!dragged&&rightClickTarget!=null)openTransparency(pressX,pressY,rightClickTarget);
            rightClickTarget=null;return true;
        }
        boolean clicked=draggingButton==0 && button==0 && inView(x,y) && !dragged;
        draggingButton=-1;
        if(clicked){
            if(pendingKey!=null)return true;
            var d=pick(x,y);
            if(editing!=null){
                if(d!=null && d.editable())openEditor(d);
                else if(d!=null){editing=null;selectedKey=key(d);rebuild();}
            }else selectedKey=d==null?null:key(d);
            return true;
        }
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        contextButton=null;
        if(inView(x,y)){zoom=Math.clamp(zoom*Math.pow(1.15,vertical),.15,30);return true;}
        if(!sidebarCollapsed&&x>=sideX()&&editing!=null){
            int max=Math.max(0,(editing.role().equals("YAW")?9:8)*27+contentTop()+22-contentBottom());
            int next=Math.clamp(editorScroll-(int)Math.round(vertical*23),0,max),delta=editorScroll-next;editorScroll=next;
            for(var w:sideWidgets)w.setY(w.getY()+delta);
            for(int i=0;i<sideLabels.size();i++){var l=sideLabels.get(i);sideLabels.set(i,new Label(l.text,l.y+delta));}
            updateEditorVisibility();return true;
        }
        if(!sidebarCollapsed&&x>=sideX()&&state!=null){listPage+=vertical>0?-1:1;rebuild();return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==256&&contextButton!=null){contextButton=null;return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public void removed(){
        closed=true;renderer.dispose();StructureHullCache.clear();
        completed.clear();queue=List.of();sampled=0;bodyMatrices.clear();
        state=null;materials=null;editing=null;pendingKey=null;pendingSettings=null;
        contextButton=null;contextTarget=null;rightClickTarget=null;
        sideWidgets.clear();sideLabels.clear();numericFields.clear();previewTooltips.clear();clearWidgets();
    }
    @Override public boolean isPauseScreen(){return false;}

}
