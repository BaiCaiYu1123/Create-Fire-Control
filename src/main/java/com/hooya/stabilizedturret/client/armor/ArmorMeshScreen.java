package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.PolyBlockEntity;
import com.caca.copypoly.PolyMenu;
import com.caca.copypoly.geometry.VertexEditing;
import com.caca.copypoly.client.ArmorEditorView;
import com.caca.copypoly.client.EditorMouseCapture;
import com.hooya.stabilizedturret.client.StabilizerControllerScreen;
import com.hooya.stabilizedturret.client.ClientVisualConfig;
import com.hooya.stabilizedturret.content.armor.*;
import com.hooya.stabilizedturret.network.ApplyArmorMeshPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Host-owned editor for the actual solid, including the vertices of projectile-made tunnels. */
public final class ArmorMeshScreen extends Screen implements MenuAccess<ArmorMeshMenu> {
    private final ArmorMeshMenu menu;
    final ArmorEditorView view;
    private EditableArmorMesh document;
    private List<EditableArmorMesh.Edge> displayEdges=List.of();
    private Set<EditableArmorMesh.Edge> guideEdges=Set.of();
    private Set<Integer> activeVertices=Set.of();
    private final LinkedHashSet<Integer> selection=new LinkedHashSet<>();
    private final LinkedHashSet<EditableArmorMesh.Edge> selectedEdges=new LinkedHashSet<>();
    private final LinkedHashSet<Integer> selectedFaces=new LinkedHashSet<>();
    private ArmorEditorSelection.Mode mode=ArmorEditorSelection.Mode.VERTEX;
    private ArmorEditorSelection topology;
    private record State(EditableArmorMesh mesh,ArmorEditorSelection.Mode mode,List<Integer> vertices,Set<EditableArmorMesh.Edge> edges,Set<Integer> faces) {}
    private final Deque<State> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private final EditBox[] coordinates=new EditBox[3];
    private PolyBlockEntity target;
    private Component status=tr("loading");
    private Button apply, clearHolesButton, multiEditButton, simplifyButton;
    private int panelWidth,panelHeight,statusY,dragAxis=-1;
    private boolean coordinateRowsVisible,xray=ClientVisualConfig.armorEditorXray();
    private Button splitButton,createFaceButton;
    private boolean waiting,conflict,updating,coordinateSession,looking,panning,marquee;
    private final EditorMouseCapture cameraMouse=new EditorMouseCapture();
    private double pointerX,pointerY,startX,startY,startParameter;
    private Vec3 dragOrigin;
    private Vec3 dragDelta;
    private EditableArmorMesh dragStart;
    private int linkSource=-1;private double linkX,linkY;private boolean linkDragging,linkControl;
    private EditableArmorMesh.Edge pressedEdge;
    private int pressedFace=-1;
    private boolean pendingClick;
    private static final int[] COLORS={0xffff6868,0xff91e66c,0xff65aaff};
    public ArmorMeshScreen(ArmorMeshMenu menu,Inventory inventory,Component title){super(title);this.menu=menu;view=new ArmorEditorView(menu.pos);}
    private static Component tr(String key,Object...args){return Component.translatable("screen.create_fire_control.armor."+key,args);}
    @Override public ArmorMeshMenu getMenu(){return menu;}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init(){
        panelWidth=Math.min(176,width-16);
        target=minecraft.level.getBlockEntity(menu.pos) instanceof PolyBlockEntity be?be:null;
        cancelGesture();view.start();rebuild();
    }
    private Button button(String key,int x,int y,int w,Runnable action){return addRenderableWidget(StabilizerControllerScreen.createBrassButton(tr(key),b->action.run(),x,y,w,18,key.equals("apply")));}
    private boolean editable(){return document!=null&&!waiting&&!conflict;}
    private void rebuild(){
        clearWidgets();setFocused(null);coordinateSession=false;
        int column=(panelWidth-20)/2,third=(panelWidth-24)/3;
        for(var value:ArmorEditorSelection.Mode.values()) {
            var select=button("mode_"+value.name().toLowerCase(Locale.ROOT),16+value.ordinal()*(third+4),38,third,()->selectionMode(value));
            select.active=value!=mode;select.setTooltip(Tooltip.create(tr("mode_help",value.ordinal()+1)));
        }
        button(xray?"xray_on":"xray_off",16,59,column,()->{xray=!xray;ClientVisualConfig.armorEditorXray(xray);rebuild();}).setTooltip(Tooltip.create(tr("xray_help")));
        button("reset_view",20+column,59,column,view::reset);
        coordinateRowsVisible=!selection.isEmpty();
        int fieldWidth=(panelWidth-24)/3;
        for(int axis=0;axis<3;axis++){
            final int a=axis;var box=new EditBox(font,16+axis*(fieldWidth+4),106,fieldWidth,18,Component.literal("XYZ".substring(axis,axis+1)));ArmorEditorTheme.field(box);box.visible=coordinateRowsVisible;
            box.setMaxLength(6);coordinates[axis]=addRenderableWidget(box);
            box.setResponder(value->{if(updating||!editable()||selection.isEmpty())return;
                try{double pixel=Double.parseDouble(value);double n=pixel/16;
                    if(!Double.isFinite(n)||n<0||n>1||Math.abs(pixel*1000-Math.rint(pixel*1000))>1e-7)return;
                    if(!coordinateSession){remember();coordinateSession=true;}
                    Vec3 delta=axis(a).scale(n-component(center(),a));document=document.move(selection,bounded(document,selection,delta),true);changed(false);
                }catch(IllegalArgumentException invalid){status=tr("error.range");}
            });
        }
        int spacing=21,y=coordinateRowsVisible?129:96,w=column;
        splitButton=button("split",16,y,w,this::split);button("select_all_elements",20+w,y,w,this::selectAll);
        y+=spacing;createFaceButton=button("create_face",16,y,w,this::createFace);button("delete_faces",20+w,y,w,this::deleteFaces);
        y+=spacing;button("undo",16,y,w,()->history(false));button("redo",20+w,y,w,()->history(true));
        y+=spacing;
        int tw=(panelWidth-24)/3;
        for(int i=0;i<3;i++){final int a=i;button("mirror_"+"xyz".charAt(i),16+i*(tw+4),y,tw,()->transform(a,hasShiftDown()));}
        y+=spacing;button("reset_mesh",16,y,tw,this::resetMesh);button("copy",20+tw,y,tw,this::copy);button("paste",24+2*tw,y,tw,this::paste);
        int footer=y+23;
        multiEditButton=button("multi_edit",16,footer,w,this::openMultiEdit);
        simplifyButton=button("simplify_vertices",20+w,footer,w,this::simplifyVertices);
        simplifyButton.setTooltip(Tooltip.create(tr("simplify_vertices_help")));
        footer+=spacing;
        clearHolesButton=null;
        if(hasHoles()){
            clearHolesButton=button("clear_holes",16,footer,panelWidth-16,this::clearHoles);
            footer+=spacing;
        }
        statusY=footer+23;panelHeight=statusY+22-8;
        apply=button("apply",16,footer,w,this::apply);button("cancel",20+w,footer,w,this::onClose);
        updateFields();
    }
    private static Vec3 axis(int a){return a==0?new Vec3(1,0,0):a==1?new Vec3(0,1,0):new Vec3(0,0,1);}
    private static double component(Vec3 p,int axis){return axis==0?p.x:axis==1?p.y:p.z;}
    private Vec3 center(){Vec3 sum=Vec3.ZERO;for(int i:selection)sum=sum.add(document.vertices().get(i));return selection.isEmpty()?sum:sum.scale(1.0/selection.size());}
    private void updateFields(){
        if(coordinateRowsVisible!=!selection.isEmpty()){rebuild();return;}
        updating=true;Vec3 c=document==null?Vec3.ZERO:center();
        for(int a=0;a<3;a++){coordinates[a].setEditable(editable()&&!selection.isEmpty());coordinates[a].setValue(selection.isEmpty()?"":VertexEditing.format(16*component(c,a)));}
        updating=false;if(apply!=null)apply.active=editable();
        updateMenuButtons();
        if(splitButton!=null)splitButton.active=editable()&&(mode==ArmorEditorSelection.Mode.EDGE?selectedEdges.size()==1:mode==ArmorEditorSelection.Mode.VERTEX&&selection.size()==2);
        if(createFaceButton!=null)createFaceButton.active=editable()&&mode==ArmorEditorSelection.Mode.VERTEX&&selection.size()>=3;
    }
    private State state(){return new State(document,mode,List.copyOf(selection),Set.copyOf(selectedEdges),Set.copyOf(selectedFaces));}
    private void remember(){undo.push(state());while(undo.size()>64)undo.removeLast();redo.clear();}
    private void clearSelection(){selection.clear();selectedEdges.clear();selectedFaces.clear();}
    private void selectionMode(ArmorEditorSelection.Mode value){mode=value;clearSelection();cancelGesture();updateFields();rebuild();}
    private void elementVertices(){
        if(mode==ArmorEditorSelection.Mode.VERTEX)return;selection.clear();
        if(mode==ArmorEditorSelection.Mode.EDGE)for(var edge:selectedEdges){selection.add(edge.a());selection.add(edge.b());}
        else if(document!=null)for(int i:selectedFaces)for(int id:document.triangles().get(i).indices())selection.add(id);
    }
    private void selectAll(){
        if(document==null)return;clearSelection();
        switch(mode){case VERTEX->{for(int i=0;i<document.vertices().size();i++)selection.add(i);}case EDGE->selectedEdges.addAll(displayEdges);case FACE->{for(int i=0;i<document.triangles().size();i++)selectedFaces.add(i);}}
        elementVertices();updateFields();
    }
    private void changed(boolean fields){
        status=tr("unsaved");updateTopology();if(fields)updateFields();
        else updateMenuButtons();
        if(target instanceof PolyArmorAccess access){access.fireControl$armorDamage().setClientPreview(document.mesh());refresh();}
    }
    private void updateTopology() {
        topology = new ArmorEditorSelection(document);
        displayEdges = topology.edges();
        guideEdges = Set.copyOf(document.guides());
        var active = new HashSet<Integer>();
        for (var triangle : document.triangles()) {
            active.add(triangle.a());
            active.add(triangle.b());
            active.add(triangle.c());
        }
        activeVertices = active;
    }
    private void refresh(){if(target!=null&&target.getLevel()!=null){target.requestModelDataUpdate();target.getLevel().sendBlockUpdated(menu.pos,target.getBlockState(),target.getBlockState(),3);}}
    private void clearPreview(){if(target instanceof PolyArmorAccess access){access.fireControl$armorDamage().setClientPreview(null);refresh();}}
    private void history(boolean forward){
        if(!editable())return;var from=forward?redo:undo;if(from.isEmpty())return;(forward?undo:redo).push(state());var saved=from.pop();document=saved.mesh();mode=saved.mode();clearSelection();selection.addAll(saved.vertices());selectedEdges.addAll(saved.edges());selectedFaces.addAll(saved.faces());cancelGesture();changed(true);rebuild();
    }
    private void split(){
        if(!editable())return;var ids=selection.stream().toList();var edge=mode==ArmorEditorSelection.Mode.EDGE&&selectedEdges.size()==1?selectedEdges.getFirst():mode==ArmorEditorSelection.Mode.VERTEX&&ids.size()==2?new EditableArmorMesh.Edge(ids.get(0),ids.get(1)):null;if(edge==null||!displayEdges.contains(edge)){status=tr("error.edge");return;}
        try{var next=document.split(edge);remember();document=next;clearSelection();mode=ArmorEditorSelection.Mode.VERTEX;selection.add(document.vertices().size()-1);changed(true);rebuild();}catch(IllegalArgumentException ex){status=tr("error."+ex.getMessage());}
    }
    private void createFace(){
        if(!editable()||mode!=ArmorEditorSelection.Mode.VERTEX||selection.size()<3)return;var ids=selection.stream().toList();var faces=new ArrayList<>(document.triangles());for(int i=1;i+1<ids.size();i++)faces.add(new EditableArmorMesh.Triangle(ids.getFirst(),ids.get(i),ids.get(i+1)));
        try{var next=document.withTriangles(faces);remember();document=next;changed(true);}catch(IllegalArgumentException ex){status=tr("error."+ex.getMessage());}
    }
    private void deleteFaces(){
        if(!editable())return;var faces=new ArrayList<EditableArmorMesh.Triangle>();
        for(int i=0;i<document.triangles().size();i++){var t=document.triangles().get(i);boolean remove=mode==ArmorEditorSelection.Mode.FACE?selectedFaces.contains(i):selection.contains(t.a())&&selection.contains(t.b())&&selection.contains(t.c());if(!remove)faces.add(t);}
        if(faces.size()==document.triangles().size())return;remember();document=document.withTriangles(faces);clearSelection();changed(true);
    }
    private void deleteSelected(){
        if(!editable()||selection.isEmpty())return;
        if(mode==ArmorEditorSelection.Mode.FACE){deleteFaces();return;}
        if(mode==ArmorEditorSelection.Mode.EDGE){
            var guides=document.guides().stream().filter(e->!selectedEdges.contains(e)).toList();var structural=new HashSet<>(selectedEdges);structural.removeAll(document.guides());
            var faces=document.triangles().stream().filter(t->!structural.contains(new EditableArmorMesh.Edge(t.a(),t.b()))&&!structural.contains(new EditableArmorMesh.Edge(t.b(),t.c()))&&!structural.contains(new EditableArmorMesh.Edge(t.c(),t.a()))).toList();
            remember();document=new EditableArmorMesh(document.vertices(),faces,document.kinds(),guides);
        }else{
            int[] indices=new int[document.vertices().size()];Arrays.fill(indices,-1);var points=new ArrayList<Vec3>();var kinds=new ArrayList<Integer>();
            for(int i=0;i<indices.length;i++)if(!selection.contains(i)){indices[i]=points.size();points.add(document.vertices().get(i));kinds.add(document.kinds().get(i));}
            var faces=document.triangles().stream().filter(t->Arrays.stream(t.indices()).allMatch(i->indices[i]>=0)).map(t->new EditableArmorMesh.Triangle(indices[t.a()],indices[t.b()],indices[t.c()])).toList();
            var guides=document.guides().stream().filter(e->indices[e.a()]>=0&&indices[e.b()]>=0).map(e->new EditableArmorMesh.Edge(indices[e.a()],indices[e.b()])).toList();
            remember();document=new EditableArmorMesh(points,faces,kinds,guides);
        }
        clearSelection();changed(true);
    }
    private void transform(int a,boolean rotate){
        if(!editable())return;remember();document=document.transform(p->{double[] n={p.x,p.y,p.z};if(rotate){int b=(a+1)%3,c=(a+2)%3;double t=n[b];n[b]=1-n[c];n[c]=t;}else n[a]=1-n[a];return new Vec3(n[0],n[1],n[2]);},!rotate);changed(true);
    }
    private void simplifyVertices() {
        if (!editable()) return;
        try {
            var simplified=document.simplifyVertices();
            int removed=document.vertices().size()-simplified.vertices().size();
            if (removed == 0) {
                status=tr("nothing_to_simplify");
                return;
            }
            remember();
            document=simplified;
            clearSelection();
            changed(true);
            status=tr("vertices_simplified",removed);
        } catch (IllegalArgumentException ex) {
            status=tr("error."+ex.getMessage());
        }
    }
    private void resetMesh(){if(!editable())return;remember();document=EditableArmorMesh.from(ArmorMesh.of(com.caca.copypoly.geometry.Polyhedron.CUBE));clearSelection();changed(true);}
    private void copy(){if(document!=null){minecraft.keyboardHandler.setClipboard("fire_control_armor_mesh:"+document.save());status=tr("copied");}}
    private void paste(){
        if(!editable())return;String text=minecraft.keyboardHandler.getClipboard(),prefix="fire_control_armor_mesh:";
        try{if(!text.startsWith(prefix)||text.length()>2_000_000)throw new IllegalArgumentException("clipboard");var next=EditableArmorMesh.load(net.minecraft.nbt.TagParser.parseTag(text.substring(prefix.length())));remember();document=next;clearSelection();changed(true);}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException|IllegalArgumentException invalid){status=tr("error.clipboard");}
    }
    private void apply(){
        if(!editable())return;
        try{document.validate();waiting=true;status=tr("saving");apply.active=false;PacketDistributor.sendToServer(new ApplyArmorMeshPayload(menu.containerId,menu.pos,menu.revision,document));}
        catch(IllegalArgumentException ex){status=tr("error."+ex.getMessage());}
    }
    private boolean hasHoles() {
        return target instanceof PolyArmorAccess armor && !armor.fireControl$armorDamage().holes().isEmpty();
    }

    private boolean hasPendingEdits() {
        return document != null && !document.equals(menu.snapshot());
    }

    private void updateMenuButtons() {
        if (simplifyButton != null) simplifyButton.active=editable();
        if (multiEditButton != null) {
            multiEditButton.active = editable() && !hasPendingEdits();
            multiEditButton.setTooltip(Tooltip.create(Component.translatable(multiEditButton.active
                ? "screen.copypoly.multi_help" : "screen.copypoly.multi_pending")));
        }
        updateClearHolesButton();
    }

    private void openMultiEdit() {
        if (!editable() || hasPendingEdits() || minecraft.gameMode == null) return;
        waiting=true;
        status=Component.translatable("screen.copypoly.multi_loading");
        updateFields();
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,PolyMenu.MULTI_EDIT_BUTTON);
    }

    private void updateClearHolesButton() {
        if (clearHolesButton == null) return;
        boolean pendingEdits = hasPendingEdits();
        clearHolesButton.active = !waiting && !conflict && !pendingEdits;
        clearHolesButton.setTooltip(Tooltip.create(tr(pendingEdits ? "clear_holes_pending" : "clear_holes_help")));
    }

    private void clearHoles() {
        if (waiting || conflict || hasPendingEdits() || !hasHoles() || minecraft.gameMode == null) return;
        waiting = true;
        status = tr("saving");
        updateFields();
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ArmorMeshMenu.CLEAR_HOLES_BUTTON);
    }

    @Override public void tick(){
        super.tick();
        if(minecraft.player==null||minecraft.player.containerMenu!=menu||!menu.stillValid(minecraft.player)||!view.active()){onClose();return;}
        String result=menu.takeResult();if(result!=null){waiting=false;if(result.isEmpty()){onClose();return;}status=tr("error."+result);updateFields();}
        if(target==null&&minecraft.level.getBlockEntity(menu.pos) instanceof PolyBlockEntity be){target=be;rebuild();}
        if(document==null&&menu.snapshot()!=null){document=menu.snapshot();updateTopology();status=tr("ready");updateFields();}
        if(!waiting&&target!=null&&target.revision()!=menu.revision&&!conflict){conflict=true;dragAxis=-1;status=tr("error.changed");clearPreview();updateFields();}
        if(!minecraft.isWindowActive()){cancelGesture();view.clear();}
        view.tick(!waiting&&!(getFocused() instanceof EditBox)&&dragAxis<0&&!pendingClick&&!marquee&&(cameraMouse.active()||canvas(pointerX,pointerY)));
    }
    @Override public void renderBackground(GuiGraphics graphics,int x,int y,float partial){}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        if (cameraMouse.active()) {mx=-1;my=-1;}
        else {pointerX=mx;pointerY=my;}
        if(document!=null&&!conflict){
            var points=document.vertices().stream().map(view::project).toList();
            view.occludeMesh(g,document.triangles().stream().map(t->new Vec3[]{points.get(t.a()),points.get(t.b()),points.get(t.c())}).toList());
            if(view.beginOverlay(g,false)){
                for(int id:selectedFaces){var t=document.triangles().get(id);view.overlayFace(g,points.get(t.a()),points.get(t.b()),points.get(t.c()),0x7755bfff);}g.flush();
            }
            if(view.beginOverlay(g,xray)){
                for(var edge:displayEdges){boolean selected=mode==ArmorEditorSelection.Mode.EDGE?selectedEdges.contains(edge):selection.contains(edge.a())&&selection.contains(edge.b());boolean custom=guideEdges.contains(edge)||document.kinds().get(edge.a())>0&&document.kinds().get(edge.b())>0;view.overlayLine(g,points.get(edge.a()),points.get(edge.b()),selected?0xffffdc8b:custom?0xffcf96ff:0xffa4d4c3,selected);}
                if(mode==ArmorEditorSelection.Mode.VERTEX)for(int i=0;i<points.size();i++)view.overlayPoint(g,points.get(i),document.kinds().get(i)>0?0xffcf96ff:0xffffdc8b,selection.contains(i),!selection.isEmpty()&&selection.getLast()==i,!activeVertices.contains(i));g.flush();
            }
            if(linkDragging&&linkSource>=0)ArmorEditorView.line(g,points.get(linkSource),new Vec3(mx,my,0),0xffcf96ff,true);
            if (!selection.isEmpty() && !pendingClick && !marquee) view.drawAxes(g, center(), dragAxis);
        }
        if(marquee){int x0=(int)Math.min(startX,mx),y0=(int)Math.min(startY,my),x1=(int)Math.max(startX,mx),y1=(int)Math.max(startY,my);g.fill(x0,y0,x1,y1,0x336acbff);g.renderOutline(x0,y0,x1-x0,y1-y0,0xff86d9ff);}
        ArmorEditorTheme.panel(g,8,8,8+panelWidth,8+panelHeight);
        g.drawString(font,title,16,23,ArmorEditorTheme.HEADER_TEXT,false);
        int count=mode==ArmorEditorSelection.Mode.VERTEX?selection.size():mode==ArmorEditorSelection.Mode.EDGE?selectedEdges.size():faceCount();
        g.drawString(font,tr("selected_"+mode.name().toLowerCase(Locale.ROOT),count),16,82,ArmorEditorTheme.LABEL,false);
        int[] labels={0xff953e35,0xff456238,0xff355d83};
        if(coordinateRowsVisible)for(int a=0;a<3;a++)g.drawString(font,"XYZ".substring(a,a+1),16+a*((panelWidth-24)/3+4),96,labels[a],false);
        g.enableScissor(16,statusY,8+panelWidth-8,statusY+20);g.drawWordWrap(font,status,16,statusY,panelWidth-16,ArmorEditorTheme.LABEL);g.disableScissor();
        int helpX=panelWidth+24,helpWidth=width-helpX-16;if(helpWidth>100){var help=tr("help");int lines=font.split(help,helpWidth).size();ArmorEditorTheme.inset(g,helpX-6,height-20-lines*10,width-8,height-8);g.drawWordWrap(font,help,helpX,height-14-lines*10,helpWidth,ArmorEditorTheme.LABEL);}
        super.render(g,mx,my,partial);
        if (cameraMouse.active()) clearTooltipForNextRenderPass();
    }
    private int faceCount(){Set<Set<Integer>> patches=Collections.newSetFromMap(new IdentityHashMap<>());if(topology!=null)for(int i:selectedFaces)patches.add(topology.patch(i));return patches.size();}
    private boolean canvas(double x,double y){return !(x>=8&&x<8+panelWidth&&y>=8&&y<8+panelHeight);}
    private int vertex(double x,double y){return topology.vertex(x,y,view::project,p->xray||view.visible(p),Math.max(6,15*ArmorEditorView.pixel()));}
    private EditableArmorMesh.Edge edge(double x,double y){return topology.edge(x,y,view::project,p->xray||view.visible(p),Math.max(4,9*ArmorEditorView.pixel()));}
    private int face(double x,double y){var ray=view.ray(x,y);if(ray==null)return -1;var hit=topology.face(ray[0],ray[1]);return hit!=null&&view.visible(view.project(hit.point()))?hit.triangle():-1;}
    private void cancelGesture(){dragAxis=-1;looking=panning=marquee=pendingClick=linkDragging=false;linkSource=-1;pressedEdge=null;pressedFace=-1;cameraMouse.release();}
    @Override public void mouseMoved(double x,double y){if(cameraMouse.active())return;pointerX=x;pointerY=y;super.mouseMoved(x,y);}
    @Override public boolean mouseClicked(double x,double y,int button){
        if (cameraMouse.active()) return true;
        pointerX=x;pointerY=y;if(!canvas(x,y))return super.mouseClicked(x,y,button);setFocused(null);coordinateSession=false;
        if(button==1||button==2){cancelGesture();looking=button==1;panning=button==2;cameraMouse.grab(button,x,y);return true;}
        if(button!=0||!editable())return true;
        int point=mode==ArmorEditorSelection.Mode.VERTEX?vertex(x,y):-1;
        if(point>=0){beginClick(x,y);linkSource=point;return true;}
        if (!selection.isEmpty()) {
            Vec3 origin = center();
            int axis = view.pickAxis(x, y, origin);
            if (axis >= 0) {
                double parameter = view.axis(x, y, origin, axis);
                if (Double.isFinite(parameter)) {
                    dragAxis = axis;
                    dragOrigin = origin;
                    startParameter = parameter;
                    dragStart = document;
                    dragDelta = Vec3.ZERO;
                    remember();
                    return true;
                }
            }
        }
        beginClick(x,y);
        if(mode==ArmorEditorSelection.Mode.EDGE)pressedEdge=edge(x,y);
        else if(mode==ArmorEditorSelection.Mode.FACE)pressedFace=face(x,y);
        return true;
    }
    private void beginClick(double x,double y){pendingClick=true;linkSource=-1;pressedEdge=null;pressedFace=-1;linkDragging=false;linkControl=hasControlDown();startX=linkX=x;startY=linkY=y;}
    private void updateMarquee(double x,double y){
        var box=ArmorEditorSelection.Box.between(startX,startY,x,y);clearSelection();
        switch(mode){case VERTEX->selection.addAll(topology.verticesIn(box,view::project));case EDGE->selectedEdges.addAll(topology.edgesIn(box,view::project));case FACE->selectedFaces.addAll(topology.facesIn(box,view::project));}
        elementVertices();updateFields();
    }
    private static Vec3 bounded(EditableArmorMesh mesh,Set<Integer> selected,Vec3 delta){double[] d={delta.x,delta.y,delta.z};for(int a=0;a<3;a++){double min=-1,max=1;for(int i:selected){double n=component(mesh.vertices().get(i),a);min=Math.max(min,-n);max=Math.min(max,1-n);}d[a]=Math.clamp(d[a],min,max);}return new Vec3(d[0],d[1],d[2]);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if (cameraMouse.active()) {
            if (cameraMouse.owns(button)) {
                if (looking) view.look(dx,dy);
                else if (panning) view.pan(dx,dy);
            }
            return true;
        }
        pointerX=x;pointerY=y;
        if(pendingClick&&editable()){
            boolean moved=Math.hypot(x-startX,y-startY)>3*ArmorEditorView.pixel();
            if(linkSource>=0&&!linkControl&&!marquee){linkDragging|=moved;return true;}
            marquee|=moved;if(marquee)updateMarquee(x,y);return true;
        }
        if (dragAxis >= 0 && editable()) {
            double parameter = view.axis(x, y, dragOrigin, dragAxis);
            if (Double.isFinite(parameter)) {
                double step = hasControlDown() ? .1 : 1;
                double distance = Math.rint((parameter - startParameter) / step) * step / 16;
                Vec3 delta = bounded(dragStart, selection, axis(dragAxis).scale(distance));
                if (dragDelta.distanceToSqr(delta) > 0) {
                    dragDelta = delta;
                    document = dragStart.move(selection, delta, true);
                    changed(true);
                }
            }
            return true;
        }
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        if(cameraMouse.active()){if(cameraMouse.owns(button))cancelGesture();return true;}
        if(button==0&&pendingClick){
            if(marquee){updateMarquee(x,y);cancelGesture();return true;}
            if(linkDragging&&linkSource>=0){int source=linkSource,target=vertex(x,y);if(target>=0&&target!=source&&editable())try{var next=document.connect(new EditableArmorMesh.Edge(source,target));remember();document=next;changed(true);}catch(IllegalArgumentException ex){status=tr("error.edge");}cancelGesture();return true;}
            if(!linkControl)clearSelection();
            if(mode==ArmorEditorSelection.Mode.VERTEX&&linkSource>=0){if(!selection.add(linkSource))selection.remove(linkSource);}
            else if(mode==ArmorEditorSelection.Mode.EDGE&&pressedEdge!=null){if(!selectedEdges.add(pressedEdge))selectedEdges.remove(pressedEdge);}
            else if(mode==ArmorEditorSelection.Mode.FACE&&pressedFace>=0){var patch=hasShiftDown()?Set.of(pressedFace):topology.patch(pressedFace);if(linkControl&&selectedFaces.containsAll(patch))selectedFaces.removeAll(patch);else selectedFaces.addAll(patch);}
            elementVertices();cancelGesture();updateFields();return true;
        }
        cancelGesture();return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(cameraMouse.active()||canvas(x,y)){view.dolly(vertical);return true;}return super.mouseScrolled(x,y,horizontal,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}
        if(cameraMouse.active()&&view.key(key,scan,true))return true;
        if(getFocused() instanceof EditBox)return super.keyPressed(key,scan,mods);
        boolean control=hasControlDown()||(mods&GLFW.GLFW_MOD_CONTROL)!=0;
        if(control&&key==GLFW.GLFW_KEY_Z){history(hasShiftDown()||(mods&GLFW.GLFW_MOD_SHIFT)!=0);return true;}
        if(control&&key==GLFW.GLFW_KEY_Y){history(true);return true;}
        if(control&&key==GLFW.GLFW_KEY_C){copy();return true;}if(control&&key==GLFW.GLFW_KEY_V){paste();return true;}
        if(control&&key==GLFW.GLFW_KEY_R){resetMesh();return true;}
        if(control&&key==GLFW.GLFW_KEY_A&&document!=null){selectAll();return true;}
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_3){selectionMode(ArmorEditorSelection.Mode.values()[key-GLFW.GLFW_KEY_1]);return true;}
        if(key==GLFW.GLFW_KEY_F){createFace();return true;}if(key==GLFW.GLFW_KEY_DELETE){deleteSelected();return true;}
        if(key==GLFW.GLFW_KEY_TAB&&editable()&&!document.vertices().isEmpty()){cycleSelection();return true;}
        return view.key(key,scan,true)||super.keyPressed(key,scan,mods);
    }
    private void cycleSelection(){
        int id;
        switch(mode){
            case VERTEX->{id=selection.isEmpty()?0:(selection.getLast()+1)%document.vertices().size();clearSelection();selection.add(id);}
            case EDGE->{if(displayEdges.isEmpty())return;id=selectedEdges.isEmpty()?0:(displayEdges.indexOf(selectedEdges.getLast())+1)%displayEdges.size();clearSelection();selectedEdges.add(displayEdges.get(id));}
            case FACE->{if(document.triangles().isEmpty())return;id=selectedFaces.isEmpty()?0:(selectedFaces.getLast()+1)%document.triangles().size();clearSelection();selectedFaces.addAll(topology.patch(id));}
        }
        elementVertices();updateFields();
    }
    @Override public boolean keyReleased(int key,int scan,int mods){return view.key(key,scan,false)||super.keyReleased(key,scan,mods);}
    @Override public void onClose(){if(minecraft.player!=null&&minecraft.player.containerMenu==menu)minecraft.player.closeContainer();else super.onClose();}
    @Override public void removed(){cancelGesture();view.stop();clearPreview();if(minecraft.player!=null&&minecraft.player.containerMenu==menu){menu.removed(minecraft.player);minecraft.player.connection.send(new ServerboundContainerClosePacket(menu.containerId));minecraft.player.containerMenu=minecraft.player.inventoryMenu;}super.removed();}
}
