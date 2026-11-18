// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.client;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.caca.copypoly.geometry.MeshOpenings;
import com.caca.copypoly.geometry.VertexEditing;
import com.caca.copypoly.geometry.ShapeBounds;
import com.caca.copypoly.api.PolyShape;
import com.caca.copypoly.api.PolyShapeCodec;
import com.caca.copypoly.api.VertexType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import java.util.*;

public class PolyScreen extends Screen implements MenuAccess<PolyMenu> {
    private final PolyMenu menu;
    private int leftPos,topPos,imageWidth,imageHeight;
    private final List<String[]> rows=new ArrayList<>();
    private int page, visibleRows, tableWidth, previewX, previewWidth;
    private Button apply, multiEdit, repair;
    private final ShapeBounds bounds;
    private final Vec3 center;
    private final double span;
    private final Polyhedron editBox;
    private Polyhedron preview;
    private PolyShape document;
    private PolyShape.Compilation compiled;
    private final ShapeHistory history=new ShapeHistory();
    private EditBox coordinateSession;
    private boolean dragCheckpoint;
    private List<BakedQuad> previewQuads=List.of();
    private BlockState material;
    private boolean dirty, valid=true, waiting, previewLooking;
    private long changedAt;
    private Component error=Component.empty(), notice=Component.empty();
    private double yaw=.65,pitch=.45,zoom=1;
    private final PolyGizmo gizmo;
    private final WorldEditorCamera camera=new WorldEditorCamera();
    private final VertexMarquee marquee=new VertexMarquee();
    private boolean panelDragging;
    private final EditorMouseCapture cameraMouse=new EditorMouseCapture();
    private boolean rightPending,linkDragging,popupOpen;
    private double rightX,rightY,linkX,linkY;
    private int linkSource=-1;
    private boolean linkControl,axesVisible=true,advanced;
    private Polyhedron.Point contextPoint;
    private int popupX,popupY;
    private final List<Button> contextButtons=new ArrayList<>();
    private int panelX=8,panelY=8;
    private double pointerX,pointerY;
    private boolean worldMode=true, minimized, conflict;
    private EditorWorldProjection worldView;
    private PolyBlockEntity previewTarget;
    private final List<EditBox[]> fields=new ArrayList<>();
    private final List<Integer> fieldIndices=new ArrayList<>();
    private boolean facePicking;
    private int selectionMode;
    private boolean xray=EditorPreferences.xray();
    private boolean groupTextures=EditorPreferences.groupTextures();
    private final Set<PolyShape.Connection> selectedConnections=new LinkedHashSet<>();
    private final ArmorEditorOverlay overlay=new ArmorEditorOverlay();
    private int controlsY,statusY;
    private final Set<Integer> selectedFaces=new LinkedHashSet<>();
    private PolyShape.Connection selectedConnection;
    private boolean showOpenings;
    private long openingsUntil;
    private List<Polyhedron.Triangle> openingFaces=List.of();
    private List<Polyhedron.Point> openingPoints=List.of();
    private boolean updatingFields;
    public PolyScreen(PolyMenu menu,Inventory inv,Component title) {
        super(title);this.menu=menu;preview=menu.initial;
        document=menu.document;compiled=document.compile();
        bounds=document.bounds();gizmo=new PolyGizmo(bounds);
        center=new Vec3(bounds.x()/32.0,bounds.y()/32.0,bounds.z()/32.0);
        span=Math.max(bounds.x(),Math.max(bounds.y(),bounds.z()))/16.0;
        editBox=menu.multiBlock()?boundingBox(bounds):Polyhedron.CUBE;
        setRows(document.vertices().stream().map(PolyShape.Vertex::position).toList());
    }
    @Override public PolyMenu getMenu(){return menu;}

    protected BlockPos editOrigin() { return menu.pos; }
    protected int editingBlockCount() { return 1; }
    protected boolean canRepairGroup() { return false; }
    protected void requestGroupRepair() {}
    protected boolean targetChanged() { return previewTarget != null && previewTarget.revision() != menu.revision; }
    public record MaterialPreview(BlockPos pos, BlockState material, Vec3 offset, List<BakedQuad> quads) {}

    protected List<MaterialPreview> previewMaterials(Polyhedron draft) {
        return List.of(new MaterialPreview(menu.pos,material,Vec3.ZERO,previewQuads));
    }

    protected void renderWorldDraft(Polyhedron draft,Matrix4f view,Matrix4d transform,Vec3 camera) {}

    protected void setWorldPreview(Polyhedron draft) {
        if (previewTarget != null) previewTarget.setClientPreview(draft);
    }
    protected void submit(PolyShape shape) {
        PacketDistributor.sendToServer(new PolyPackets.Apply(menu.containerId,menu.revision,shape));
    }
    private static Polyhedron boundingBox(ShapeBounds bounds) {
        List<Polyhedron.Point> corners = new ArrayList<>();
        for (int x : new int[]{0, bounds.x()}) for (int y : new int[]{0, bounds.y()}) for (int z : new int[]{0, bounds.z()})
            corners.add(bounds.point(x,y,z));
        return Polyhedron.of(corners,bounds);
    }

    private void focusGroup() {
        if (!menu.multiBlock() || !camera.active()) return;
        var target = blockTransform(1).transformPosition(new org.joml.Vector3d(center.x, center.y, center.z));
        var cameraEntity = minecraft.getCameraEntity();
        cameraEntity.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, new Vec3(target.x,target.y,target.z));
        cameraEntity.setOldPosAndRot();
    }

    private org.joml.Matrix4d blockTransform(float partialTick) {
        var from = editOrigin().subtract(menu.pos);
        return EditorBlockTransform.capture(minecraft.level,menu.pos,partialTick).translate(from.getX(),from.getY(),from.getZ());
    }
    @Override public boolean isPauseScreen(){return false;}
    private void setRows(List<Polyhedron.Point> points) {
        replaceRows(points);
        page=0;gizmo.clearSelection();
    }
    private void replaceRows(List<Polyhedron.Point> points) {
        rows.clear();for(var p:points)rows.add(new String[]{VertexEditing.format(p.x()),VertexEditing.format(p.y()),VertexEditing.format(p.z())});
    }
    @Override protected void init() {
        imageWidth=worldMode?Math.min(176,width-12):Math.min(440,width-12);imageHeight=Math.min(320,height-16);
        super.init();
        leftPos=(width-imageWidth)/2;topPos=(height-imageHeight)/2;
        if(worldMode)clampPanel();
        tableWidth=worldMode?imageWidth-24:152;previewX=leftPos+tableWidth+16;previewWidth=imageWidth-tableWidth-28;
        visibleRows=2;
        material=minecraft.level.getBlockEntity(menu.pos) instanceof PolyBlockEntity be?be.getMaterial():AllBlocks.COPYCAT_BASE.getDefaultState();
        if(!menu.multiBlock() && minecraft.level.getBlockEntity(menu.pos) instanceof PolyBlockEntity be)previewTarget=be;
        stopMouseAction();marquee.cancel();camera.clear();if(worldMode){boolean started=camera.active();camera.start();if(!started)focusGroup();}else camera.stop();
        worldView=null;
        rebuild();updatePreview();
    }
    private Component tr(String key,Object...args) {return Component.translatable("screen.copypoly."+key,args);}
    private Button button(String key,int x,int y,int w,Runnable action) {
        return addRenderableWidget(Button.builder(tr(key),b->action.run()).bounds(x,y,w,18).build());
    }
    private void rebuild() {
        setFocused(null);coordinateSession=null;clearWidgets();fields.clear();fieldIndices.clear();contextButtons.clear();popupOpen=false;multiEdit=null;repair=null;
        if(worldMode){addRenderableWidget(Button.builder(Component.literal(minimized?"+":"−"),b->{minimized=!minimized;gizmo.release();rebuild();}).bounds(leftPos+imageWidth-25,topPos+3,18,18).build());if(minimized)return;}
        int third=(tableWidth-4)/3,half=(tableWidth-3)/2;
        String[] modes={"select_vertices","select_edges","select_faces"};
        for(int i=0;i<3;i++){final int mode=i;button(modes[i],leftPos+10+i*(third+2),topPos+28,third,()->selectionMode(mode)).active=selectionMode!=i;}
        button(xray?"xray_on":"xray_off",leftPos+10,topPos+49,half,()->{xray=!xray;EditorPreferences.xray(xray);rebuild();}).setTooltip(Tooltip.create(tr("xray_help")));
        button("reset_view",leftPos+13+half,topPos+49,half,()->{if(worldMode){camera.resetView();focusGroup();}else{yaw=.65;pitch=.45;zoom=1;}});
        var selected=gizmo.selectedIndices().stream().filter(i->i<rows.size()).toList();page=Math.min(page,Math.max(0,(selected.size()-1)/visibleRows));
        int fieldWidth=(tableWidth-40)/3,rowCount=Math.min(visibleRows,Math.max(0,selected.size()-page*visibleRows));
        for(int line=0;line<rowCount;line++){
            int index=selected.get(page*visibleRows+line),y=topPos+99+line*22;EditBox[] rowFields=new EditBox[3];fields.add(rowFields);fieldIndices.add(index);
            final int row=index;addRenderableWidget(Button.builder(Component.literal(""+(index+1)),b->{selectionMode(0);gizmo.select(row,false);rebuild();}).bounds(leftPos+4,y,17,18).build());
            for(int axis=0;axis<3;axis++){final int a=axis;var box=new EditBox(font,leftPos+23+axis*(fieldWidth+2),y,fieldWidth,18,Component.literal((index+1)+" "+"XYZ".charAt(axis)));box.setMaxLength(8);box.setValue(rows.get(index)[axis]);box.setResponder(value->{if(!updatingFields){if(coordinateSession!=box){remember();coordinateSession=box;}rows.get(index)[a]=value;gizmo.selected=index;syncDocument();markDirty();}});rowFields[axis]=addRenderableWidget(box);}
            addRenderableWidget(Button.builder(Component.literal("×"),b->deleteVertices(List.of(document.vertices().get(index).id()))).bounds(leftPos+tableWidth-8,y,16,18).build());
        }
        int y=topPos+(selected.isEmpty()?101:103+rowCount*22);
        if(selected.size()>visibleRows){final int nav=y;addRenderableWidget(Button.builder(Component.literal("<"),b->{page--;rebuild();}).bounds(leftPos+10,nav,24,18).build()).active=page>0;addRenderableWidget(Button.builder(Component.literal(">"),b->{page++;rebuild();}).bounds(leftPos+36,nav,24,18).build()).active=page<(selected.size()-1)/visibleRows;y+=21;}
        controlsY=y;
        button("create_face",leftPos+10,y,half,this::createFace).active=selectionMode==0&&gizmo.selectedCount()>=3&&!waiting&&!conflict;
        button("undo",leftPos+13+half,y,half,this::undo);y+=21;
        boolean showRepair=canRepairGroup();
        button("simplify_vertices",leftPos+10,y,showRepair?half:tableWidth,this::simplifyVertices)
            .setTooltip(Tooltip.create(tr("simplify_vertices_help")));
        if (showRepair) {
            repair=button("clear_group_holes",leftPos+13+half,y,half,this::repairGroup);
            updateSessionButtons();
        }
        y+=21;
        if(advanced){button("delete_faces",leftPos+10,y,half,this::deleteFaces);button("clear_faces",leftPos+13+half,y,half,this::clearFaces).setTooltip(Tooltip.create(tr("clear_faces_help")));y+=21;}
        boolean enterGroup = !menu.multiBlock() && PolyMenu.hasMultiEditor();
        button(advanced?"advanced_close":"advanced",leftPos+10,y,enterGroup || menu.multiBlock()?half:tableWidth,()->{advanced=!advanced;rebuild();});
        if (enterGroup) {
            multiEdit=button("multi_edit",leftPos+13+half,y,half,this::openMultiEdit);
            updateSessionButtons();
        } else if (menu.multiBlock()) {
            button(groupTextures?"group_textures_on":"group_textures_off",leftPos+13+half,y,half,()->{
                groupTextures=!groupTextures;
                EditorPreferences.groupTextures(groupTextures);
                rebuild();
            }).setTooltip(Tooltip.create(tr("group_textures_help")));
        }
        y+=21;
        for(int axis=0;axis<3;axis++){final int a=axis;var mirror=addRenderableWidget(Button.builder(tr("mirror_axis",""+"XYZ".charAt(axis)),b->transform(true,a)).bounds(leftPos+10+axis*(third+2),y,third,18).build());mirror.setTooltip(Tooltip.create(tr("mirror_help",""+"XYZ".charAt(axis))));mirror.active=!waiting&&!conflict;var rotate=addRenderableWidget(Button.builder(tr("rotate_axis",""+"XYZ".charAt(axis)),b->transform(false,a)).bounds(leftPos+10+axis*(third+2),y+21,third,18).build());rotate.active=!waiting&&!conflict;}
        y+=42;button("copy",leftPos+10,y,third,this::copyWholeShape).setTooltip(Tooltip.create(tr("copy_all_help")));button("paste",leftPos+12+third,y,third,this::pasteWholeShape).setTooltip(Tooltip.create(tr("paste_replace_help")));button("reset",leftPos+14+2*third,y,third,()->{remember();loadDocument(menu.multiBlock()?menu.document:PolyShape.fromGeometry(Polyhedron.CUBE),List.of());});y+=23;
        button(worldMode?"table":"world",leftPos+10,y,third,()->{worldMode=!worldMode;minimized=false;init();});
        apply=button("apply",leftPos+12+third,y,third,()->{validate();if(waiting||conflict)return;if(!valid){showOpenings=true;openingsUntil=net.minecraft.Util.getMillis()+5000;updateOpenings();return;}clearOpenings();waiting=true;apply.active=false;error=tr("saving");submit(document);});
        button("cancel",leftPos+14+2*third,y,third,this::onClose);statusY=y+23;imageHeight=Math.max(worldMode?0:300,statusY-topPos+24);apply.active=!waiting&&!conflict;
        if(worldMode){int oldX=leftPos,oldY=topPos;clampPanel();if(leftPos!=oldX||topPos!=oldY)rebuild();}
    }
    private boolean canUseOriginalShape() {
        return !waiting && !conflict && !dirty && valid && document.equals(menu.document);
    }
    private void updateSessionButtons() {
        boolean unchanged=canUseOriginalShape();
        if (multiEdit != null) {
            multiEdit.active=unchanged;
            multiEdit.setTooltip(Tooltip.create(tr(unchanged?"multi_help":"multi_pending")));
        }
        if (repair != null) {
            repair.active=unchanged;
            repair.setTooltip(Tooltip.create(tr(unchanged?"clear_group_holes_help":"clear_group_holes_pending")));
        }
    }
    private void repairGroup() {
        if (!canUseOriginalShape() || !canRepairGroup() || minecraft.gameMode == null) return;
        waiting=true;apply.active=false;error=tr("clearing_group_holes");updateSessionButtons();
        requestGroupRepair();
    }
    private void openMultiEdit() {
        if (!canUseOriginalShape() || minecraft.gameMode == null) return;
        waiting=true;apply.active=false;error=tr("multi_loading");updateSessionButtons();
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,PolyMenu.MULTI_EDIT_BUTTON);
    }
    private String text() {return String.join("\n",rows.stream().map(r->String.join(", ",r)).toList());}
    private List<Integer> selectedIds() {
        return gizmo.selectedIndices().stream().filter(i->i<document.vertices().size()).map(i->document.vertices().get(i).id()).toList();
    }
    private List<Integer> orderedIds(){return gizmo.selectionOrder().stream().filter(i->i<document.vertices().size()).map(i->document.vertices().get(i).id()).toList();}
    private void selectionMode(boolean faces){selectionMode(faces?2:0);}
    private void selectionMode(int mode){selectionMode=mode;facePicking=mode==2;selectedFaces.clear();selectedConnections.clear();selectedConnection=null;gizmo.clearSelection();stopMouseAction();marquee.cancel();axesVisible=true;rebuild();}
    private PolyEditorTopology topology(){return new PolyEditorTopology(document);}
    private void edgeVertices(){Set<Integer> ids=new LinkedHashSet<>();for(var edge:selectedConnections){ids.add(edge.source());ids.add(edge.target());}gizmo.replaceSelection(java.util.stream.IntStream.range(0,document.vertices().size()).filter(i->ids.contains(document.vertices().get(i).id())).boxed().toList());selectedConnection=selectedConnections.isEmpty()?null:selectedConnections.iterator().next();}
    private void selectEdge(PolyShape.Connection edge,boolean multiple){selectedFaces.clear();if(!multiple)selectedConnections.clear();if(!selectedConnections.add(edge))selectedConnections.remove(edge);edgeVertices();axesVisible=true;page=0;rebuild();}
    private int pickVertex(double x,double y,int exclude){return PolyGizmo.pickVertex(x,y,draftPoints(),this::editProject,exclude,Math.max(6,15*SmoothOverlay.pixel()),p->xray||overlay.visible(p));}
    private void createFace() {
        if(waiting || conflict)return;
        try {
            syncDocument();var selected=orderedIds();
            var changed=document.editableMesh().toBuilder().faceAutomatically(selected).build();changed.draftSurface();
            if(changed.equals(document))return;remember();loadDocument(changed,selected);
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void deleteFaces() {
        if(waiting || conflict)return;
        try {
            syncDocument();var surface=document.surfaceTriangles();var selected=new LinkedHashSet<>(selectedFaces);
            if(selected.isEmpty()){var ids=new HashSet<>(selectedIds());for(int i=0;i<surface.size();i++)if(Arrays.stream(surface.get(i).indices()).allMatch(ids::contains))selected.add(i);}
            if(selected.isEmpty()){error=tr("error.no_faces");return;}
            var changed=document.editableMesh().toBuilder().removeFaces(selected).build();remember();loadDocument(changed,List.of());
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void clearFaces() {
        if(waiting || conflict)return;
        try{var changed=document.toBuilder().meshFaces(List.of()).build();remember();loadDocument(changed,List.of());}
        catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void remember(){history.remember(document,orderedIds(),selectedFaces,axesVisible,facePicking,selectedConnection,selectionMode,selectedConnections);}
    private void syncDocument() {
        try {
            var points=Polyhedron.parse(text(),bounds);if(points.size()!=document.vertices().size())return;
            Map<Integer,Polyhedron.Point> positions=new LinkedHashMap<>();for(int i=0;i<points.size();i++)positions.put(document.vertices().get(i).id(),points.get(i));
            document=document.withPositions(positions);
        }catch(IllegalArgumentException invalid){/* Keep invalid coordinate text editable until it is corrected. */}
    }
    private void loadDocument(PolyShape shape,List<Integer> selection) {
        stopMouseAction();marquee.cancel();camera.clear();selectedFaces.clear();selectedConnections.clear();selectedConnection=null;document=shape;
        replaceRows(shape.vertices().stream().map(PolyShape.Vertex::position).toList());
        gizmo.replaceSelection(selection.stream().map(id->java.util.stream.IntStream.range(0,shape.vertices().size()).filter(i->shape.vertices().get(i).id()==id).findFirst().orElse(-1)).filter(i->i>=0).toList());
        page=0;markDirty();validate();rebuild();
    }
    private void simplifyVertices() {
        if (waiting || conflict) return;
        validate();
        if (!valid) return;
        try {
            var simplified=document.simplifyVertices();
            int removed=document.vertices().size()-simplified.vertices().size();
            if (removed == 0) {
                notice=tr("nothing_to_simplify");
                return;
            }
            remember();
            loadDocument(simplified,selectionMode==0?orderedIds():List.of());
            notice=tr("vertices_simplified",removed);
        } catch (IllegalArgumentException ex) {
            error=tr("error."+ex.getMessage());
        }
    }
    private void deleteVertices(List<Integer> ids) {
        if(ids.isEmpty() || conflict || waiting)return;
        try {
            var builder=document.toBuilder();
            if (menu.multiBlock()) {
                try {
                    builder=document.editableMesh().toBuilder().dissolveVertices(ids);
                } catch (IllegalArgumentException unavailable) {
                    // Keep an open draft available for explicit face repairs.
                    builder=document.editableMesh().toBuilder();
                    for (int id : ids) builder.removeVertex(id);
                }
            } else for (int id : ids) builder.removeVertex(id);
            var changed=builder.build();
            remember();loadDocument(changed,List.of());
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void undo() {
        if(conflict || waiting)return;var state=history.undo();if(state==null)return;
        loadDocument(state.shape(),state.selection());selectedFaces.addAll(state.faces());selectedConnection=state.connection();selectedConnections.addAll(state.connections());selectionMode=state.mode();axesVisible=state.axes();facePicking=selectionMode==2;rebuild();
    }
    private void deleteConnection() {
        if(selectedConnections.isEmpty()||waiting||conflict)return;
        try{var builder=document.toBuilder();Set<PolyShape.Connection> structural=new LinkedHashSet<>();for(var e:selectedConnections){var original=document.connections().stream().filter(c->PolyEditorTopology.edge(c.source(),c.target()).equals(e)).findFirst();if(original.isPresent())builder.disconnect(original.get().source(),original.get().target());else structural.add(e);}var changed=builder.build();
            if(!structural.isEmpty()){var triangles=changed.surfaceTriangles();Set<Integer> remove=new HashSet<>();for(int i=0;i<triangles.size();i++){int[] t=triangles.get(i).indices();for(int j=0;j<3;j++)if(structural.contains(PolyEditorTopology.edge(t[j],t[(j+1)%3])))remove.add(i);}changed=changed.editableMesh().toBuilder().removeFaces(remove).build();}
            remember();loadDocument(changed,List.of());axesVisible=false;
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void createVertex(VertexType type,Polyhedron.Point point) {
        try {
            var builder=document.toBuilder();int id=builder.addVertex(type,point);var changed=builder.build();remember();selectionMode=0;facePicking=false;loadDocument(changed,List.of(id));axesVisible=false;
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void connectVertices(int sourceIndex,int targetIndex) {
        if(conflict || waiting || sourceIndex<0 || targetIndex<0 || sourceIndex==targetIndex)return;
        int source=document.vertices().get(sourceIndex).id(),target=document.vertices().get(targetIndex).id();
        try {
            var builder=document.toBuilder();
            boolean removing=document.targets(source).contains(target);
            if(removing)builder.disconnect(source,target);else builder.connect(source,target);
            var changed=builder.build();
            if(!removing && document.lineMesh() && document.connections().stream().anyMatch(c->!document.pendingConnections().contains(c) && changed.pendingConnections().contains(c))) {
                error=tr("error.line_crossing");return;
            }
            remember();loadDocument(changed,List.of(source));axesVisible=false;
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void copyWholeShape() {
        try{syncDocument();if(valid || document.manualMesh() || document.lineMesh())minecraft.keyboardHandler.setClipboard(PolyShapeCodec.toText(document));}
        catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void pasteWholeShape() {
        try {
            var shape=PolyShapeCodec.fromText(minecraft.keyboardHandler.getClipboard(),bounds);remember();loadDocument(shape,List.of());
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void copySelectedVertices() {
        try {
            syncDocument();minecraft.keyboardHandler.setClipboard(PolyShapeCodec.copyVertices(document,selectedIds()));
            error=Component.empty();
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    private void pasteVertices() {
        if(conflict){error=tr("error.changed");return;}
        try {
            String clipboard=minecraft.keyboardHandler.getClipboard();
            var copied=PolyShapeCodec.readVertices(clipboard,bounds);
            if(rows.size()+copied.vertices().size()>bounds.maxPoints())throw new IllegalArgumentException("paste_limit");
            var builder=document.toBuilder();Map<Integer,Integer> added=new LinkedHashMap<>();
            for(var v:copied.vertices())added.put(v.id(),builder.addVertex(v.type(),v.position()));
            for(var link:copied.connections())builder.connect(added.get(link.source()),added.get(link.target()));
            var shape=builder.build();remember();loadDocument(shape,List.copyOf(added.values()));
        }catch(IllegalArgumentException ex){
            String key=ex.getMessage();
            error="count".equals(key)||"paste_limit".equals(key)
                    ?tr("error.paste_limit",bounds.maxPoints()):tr("error."+key);
        }
    }
    private void transform(boolean mirror,int axis) {
        if(waiting || conflict)return;
        try {
            validate();if(!valid && !document.manualMesh())return;
            var changed=document.transformPositions(p->mirror?VertexEditing.mirror(p,axis,bounds):VertexEditing.rotate(p,axis,hasShiftDown()?-1:1,bounds));
            if(!document.manualMesh())changed.compile();var selection=selectedIds();remember();loadDocument(changed,selection);
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());}
    }
    void updateMovement(net.minecraft.client.player.Input input) {
        WorldEditorCamera.suppressPlayerInput(input);
    }
    boolean worldCameraActive() {return worldMode && camera.active();}
    private void markDirty() {notice=Component.empty();dirty=true;changedAt=System.currentTimeMillis();updateSessionButtons();}
    private void validate() {
        dirty=false;
        boolean coordinatesValid=false;
        try {
            var parsed=Polyhedron.parse(text(),bounds);if(parsed.size()!=document.vertices().size())throw new IllegalArgumentException("format");
            coordinatesValid=true;
            syncDocument();compiled=document.compile();preview=compiled.geometry();valid=true;error=Component.empty();updatePreview();
        }
        catch(IllegalArgumentException ex){
            valid=false;error=tr("error."+ex.getMessage());
            if(coordinatesValid && (document.manualMesh() || document.lineMesh()))try {
                preview=document.draftSurface();compiled=new PolyShape.Compilation(preview,Set.of(),document.lineMesh()?document.pendingVertices():Map.of());updatePreview();
                Map<Long,Integer> edges=new HashMap<>();for(var t:document.baseFaces()){int[] p=t.indices();for(int i=0;i<3;i++){int a=p[i],b=p[(i+1)%3];long e=(long)Math.min(a,b)<<32|Math.max(a,b)&0xffffffffL;edges.merge(e,1,Integer::sum);}}
                long open=edges.values().stream().filter(n->n==1).count();
                error=open>0 || edges.isEmpty()?tr("draft_open",open):tr("draft_invalid");
            }catch(IllegalArgumentException ignored){/* Retain coordinates and show the validation error until repaired. */}
        }
        if(conflict)error=tr("error.changed");
        expireOpenings();
        if(valid)clearOpenings();else if(showOpenings)updateOpenings();
        if(apply!=null)apply.active=!waiting && !conflict;
        updateSessionButtons();
    }
    private void clearOpenings(){showOpenings=false;openingsUntil=0;openingFaces=List.of();openingPoints=List.of();}
    private void expireOpenings() {
        if(showOpenings && net.minecraft.Util.getMillis()>=openingsUntil) {
            clearOpenings();if(error.equals(tr("openings_marked")))error=Component.empty();
        }
    }
    private void updateOpenings() {
        try {
            var vertices=document.vertices();Map<Integer,Integer> index=new HashMap<>();for(int i=0;i<vertices.size();i++)index.put(vertices.get(i).id(),i);
            openingPoints=vertices.stream().map(PolyShape.Vertex::position).toList();
            var faces=document.surfaceTriangles().stream().map(t->new Polyhedron.Triangle(index.get(t.a()),index.get(t.b()),index.get(t.c()))).toList();
            openingFaces=MeshOpenings.previewCaps(openingPoints,faces);
            if(faces.isEmpty() && openingPoints.size()>=4)try {
                var hull=Polyhedron.of(openingPoints,bounds);var indices=hull.points().stream().map(openingPoints::indexOf).toList();
                openingFaces=hull.triangles().stream().map(t->new Polyhedron.Triangle(indices.get(t.a()),indices.get(t.b()),indices.get(t.c()))).toList();
            }catch(IllegalArgumentException empty){/* Flat point sets have no enclosing volume to highlight. */}
            if(!openingFaces.isEmpty())error=tr("openings_marked");
        }catch(IllegalArgumentException ex){openingFaces=List.of();}
    }
    private void updatePreview() {
        if(!menu.multiBlock() && minecraft.getBlockRenderer().getBlockModel(Copypoly.POLY.get().defaultBlockState()) instanceof PolyModel model)
            previewQuads=model.preview(preview,material);
        setWorldPreview(worldMode && !conflict?preview:null);
    }
    public void result(String message) {
        waiting=false;
        if(message.isEmpty()){onClose();return;}
        error=tr("error."+message);if(apply!=null)apply.active=!conflict;updateSessionButtons();
    }
    @Override public void tick() {
        super.tick();
        expireOpenings();
        if(minecraft.player==null || !minecraft.player.isAlive() || minecraft.player.isRemoved() || minecraft.player.containerMenu!=menu){onClose();return;}
        if(!minecraft.isWindowActive()){stopMouseAction();marquee.cancel();}
        if(worldMode) {
            if(!camera.active()){onClose();return;}
            camera.tick(!waiting && !(getFocused() instanceof EditBox) && !gizmo.dragging() && !marquee.pending() && !panelDragging && !popupOpen && !rightPending && linkSource<0 && (cameraMouse.active() || inCanvas(pointerX,pointerY)));
        }
        if(dirty && (gizmo.dragging() || System.currentTimeMillis()-changedAt>=160))validate();
        if(!menu.stillValid(minecraft.player)){onClose();return;}
        if(!conflict && !waiting && targetChanged()) {
            conflict=true;gizmo.release();error=tr("error.changed");if(apply!=null)apply.active=false;setWorldPreview(null);updateSessionButtons();
        }
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        if (cameraMouse.active()) {
            super.render(g,-1,-1,partial);
            clearTooltipForNextRenderPass();
        } else {
            super.render(g,mx,my,partial);
        }
    }
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float partial) {
        if(!worldMode)g.fillGradient(0,0,width,height,0xb0101820,0xd0101820);
        renderBg(g,partial,mx,my);
    }
    private void renderBg(GuiGraphics g,float partial,int mx,int my) {
        if (!cameraMouse.active()) {pointerX=mx;pointerY=my;}
        if(worldMode){drawWorld(g);drawMarquee(g);}
        int panelHeight=worldMode && minimized?25:imageHeight;
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+panelHeight,0xf218252b);
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+25,0xff273c43);
        g.drawString(font,menu.multiBlock()?title:worldMode?tr("world_title"):title,leftPos+12,topPos+9,0xffe3f2ec,false);
        if(worldMode && minimized)return;
        g.drawString(font,tr("mode_selected",tr(selectionMode==0?"select_vertices":selectionMode==1?"select_edges":"select_faces"),selectionMode==1?selectedConnections.size():selectionMode==2?selectedFaceCount():gizmo.selectedCount()),leftPos+10,topPos+73,0xffa6b4b9,false);
        int fieldWidth=(tableWidth-40)/3;
        if(!fieldIndices.isEmpty())for(int a=0;a<3;a++)g.drawString(font,""+"XYZ".charAt(a),leftPos+23+a*(fieldWidth+2),topPos+88,0xff94b5ad,false);
        else g.drawWordWrap(font,tr(selectionMode==0?"empty_selection":selectionMode==1?"empty_edge_selection":"empty_face_selection"),leftPos+10,topPos+88,tableWidth,0xffa6b4b9);
        if(!worldMode) {
            g.fill(previewX,topPos+30,previewX+previewWidth,topPos+imageHeight-64,0xff101c22);
            drawPreview(g);
            g.drawString(font,tr("count",rows.size(),bounds.maxPoints()),previewX+5,topPos+35,0xffa6c9bd,false);
            g.drawWordWrap(font,tr("drag"),previewX+5,topPos+imageHeight-91,previewWidth-10,0xffa6b4b9);
            g.drawString(font,tr("step",hasControlDown()?"0.1":"1"),previewX+5,topPos+imageHeight-55,0xffa6b4b9,false);
        }
        var status=error.getString().isEmpty()?notice:error;
        if(!status.getString().isEmpty()){g.enableScissor(leftPos+8,statusY,leftPos+tableWidth+12,statusY+20);g.drawWordWrap(font,status,leftPos+10,statusY,tableWidth,status==notice||waiting?0xffaacfc1:0xffffa599);g.disableScissor();}
    }
    private Vec3 rotate(Vec3 p) {
        double x=Math.cos(yaw)*p.x+Math.sin(yaw)*p.z,z=-Math.sin(yaw)*p.x+Math.cos(yaw)*p.z;
        return new Vec3(x,Math.cos(pitch)*p.y-Math.sin(pitch)*z,Math.sin(pitch)*p.y+Math.cos(pitch)*z);
    }
    private int selectedFaceCount(){var top=topology();Set<Set<Integer>> patches=Collections.newSetFromMap(new IdentityHashMap<>());for(int face:selectedFaces)if(face<top.faces.size())patches.add(top.patch(face));return patches.size();}
    private Vec3 project(Vec3 p) {
        Vec3 r=rotate(p.subtract(center));double scale=Math.min(previewWidth-30,imageHeight-125)*.42*zoom/span;
        return new Vec3(previewX+previewWidth*.5+r.x*scale,topPos+30+(imageHeight-94)*.5-r.y*scale,r.z);
    }
    private record PreviewQuad(MaterialPreview material,BakedQuad source,Vec3[] points,double depth,boolean reverse) {}
    private boolean translucentDraft() { return menu.multiBlock() && !groupTextures; }

    private void drawPreview(GuiGraphics g) {
        g.enableScissor(previewX,topPos+47,previewX+previewWidth,topPos+imageHeight-104);
        if (!translucentDraft()) drawPreviewMaterials(g);
        var top=topology();
        var projected=top.faces.stream().map(t->Arrays.stream(t.indices()).mapToObj(id->editProject(top.point(id))).toArray(Vec3[]::new)).toList();
        overlay.preview(g,width,height,projected);
        if (translucentDraft()) drawTranslucentDraft(g,projected);
        drawOpeningFaces(g);drawEditingPoints(g);
        drawMarquee(g);
        g.disableScissor();
    }

    private void drawPreviewMaterials(GuiGraphics g) {
        List<PreviewQuad> sorted=new ArrayList<>();
        for (var batch : previewMaterials(preview)) for (BakedQuad q : batch.quads()) {
            int[] data=q.getVertices();int stride=data.length/4;Vec3[] p=new Vec3[4];double depth=0;
            for(int i=0;i<4;i++){p[i]=project(new Vec3(Float.intBitsToFloat(data[i*stride]),Float.intBitsToFloat(data[i*stride+1]),Float.intBitsToFloat(data[i*stride+2])).add(batch.offset()));depth+=p[i].z;}
            // Screen Y is inverted; clockwise screen triangles face the viewer.
            double winding=(p[1].x-p[0].x)*(p[2].y-p[0].y)-(p[1].y-p[0].y)*(p[2].x-p[0].x);
            if (preview.closed() && winding>=0) continue;
            // The single-block model already supplies reverse quads for open faces.
            sorted.add(new PreviewQuad(batch,q,p,depth/4,menu.multiBlock() && !preview.closed() && winding>0));
        }
        sorted.sort(Comparator.comparingDouble(PreviewQuad::depth));
        g.flush();
        for(PreviewQuad quad:sorted) {
            int tint=quad.source.isTinted()?minecraft.getBlockColors().getColor(quad.material.material(),minecraft.level,quad.material.pos(),quad.source.getTintIndex()):0xffffff;
            float shade=.68f+.32f*Math.max(0,(float)rotate(Vec3.atLowerCornerOf(quad.source.getDirection().getNormal())).z);
            int color=0xff000000 | ((int)(((tint>>16)&255)*shade)<<16) | ((int)(((tint>>8)&255)*shade)<<8) | (int)((tint&255)*shade);
            VertexConsumer v=g.bufferSource().getBuffer(RenderType.text(TextureAtlas.LOCATION_BLOCKS));
            int[] data=quad.source.getVertices();int stride=data.length/4;
            for (int i=0;i<4;i++) {
                int n=quad.reverse?3-i:i;
                v.addVertex(g.pose().last().pose(),(float)quad.points[n].x,(float)quad.points[n].y,0)
                    .setColor(color).setUv(Float.intBitsToFloat(data[n*stride+4]),Float.intBitsToFloat(data[n*stride+5])).setLight(LightTexture.FULL_BRIGHT);
            }
        }
        g.flush();
    }
    private static void line(GuiGraphics g,Vec3 a,Vec3 b,int color) {
        PolyGizmo.line(g,a,b,color,1);
    }
    private List<Polyhedron.Point> draftPoints() {
        try{return Polyhedron.parse(text(),bounds);}catch(IllegalArgumentException ex){return List.of();}
    }
    public void captureWorldView(Matrix4f view,Matrix4f projection,Vec3 camera,float partialTick) {
        if (!worldMode) return;
        var transform=blockTransform(partialTick);
        if (!conflict && !translucentDraft()) renderWorldDraft(preview,view,transform,camera);
        worldView=new EditorWorldProjection(view,projection,camera,width,height,transform);
        overlay.capture(width,height);
    }
    private Vec3 editProject(Vec3 local) {
        if(!worldMode){Vec3 p=project(local);return new Vec3(p.x,p.y,-p.z);}
        return worldView==null?null:worldView.project(local);
    }
    private EditorAxisHandles axisHandles(Vec3 origin) {
        double pixel = SmoothOverlay.pixel();
        if (worldMode) return worldView == null ? null : worldView.axisHandles(origin, pixel);
        return EditorAxisHandles.layout(project(origin), axis -> {
            Vec3 direction = rotate(PolyGizmo.axis(axis));
            return new Vec3(direction.x, -direction.y, 0);
        }, pixel);
    }
    private PolyGizmo.AxisCoordinate axisMapper() {
        if(!worldMode)return (x,y,origin,axis)->{
            Vec3 a=project(origin),b=project(origin.add(PolyGizmo.axis(axis).scale(1/16.0)));
            double dx=b.x-a.x,dy=b.y-a.y;
            return ((x-a.x)*dx+(y-a.y)*dy)/(dx*dx+dy*dy);
        };
        // Read the latest frame while dragging: a structure can move or rotate
        // between mouse events, so the click-time world transform must not be frozen.
        return (x,y,origin,axis)->worldView==null?Double.NaN:worldView.axisCoordinate(x,y,origin,axis);
    }
    private void drawWorld(GuiGraphics g) {
        if(!conflict) {
            var top=topology();
            var projected=top.faces.stream().map(t->Arrays.stream(t.indices()).mapToObj(id->editProject(top.point(id))).toArray(Vec3[]::new)).toList();
            overlay.occlude(g,projected);
            if (translucentDraft()) drawTranslucentDraft(g,projected);
            drawOpeningFaces(g);

            drawEditingPoints(g);
        }
        Component status=tr("world_help");
        int hudLeft=!minimized && leftPos<width/2?leftPos+imageWidth+10:10;
        int hudRight=!minimized && leftPos>=width/2?leftPos-10:width-10;
        int helpWidth=Math.max(60,hudRight-hudLeft);
        int lines=font.split(status,helpWidth).size(),y=height-52-lines*10;
        g.fill(hudLeft-5,y-4,hudLeft+helpWidth+5,height-46,0xb0101c22);
        g.drawWordWrap(font,status,hudLeft,y,helpWidth,0xffd3e7e2);
        Component info=tr("count",rows.size(),bounds.maxPoints()).copy().append("  ·  ").append(tr("step",hasControlDown()?"0.1":"1"));
        if(menu.multiBlock())info=info.copy().append("  ·  ").append(tr("multi_blocks",editingBlockCount()));
        if(gizmo.selectedCount()>0)info=info.copy().append("  ·  ").append(tr("selection",gizmo.selectedCount()));
        if(gizmo.selected>=0 && gizmo.selected<rows.size())info=info.copy().append("  #"+(gizmo.selected+1)+"  "+String.join(", ",rows.get(gizmo.selected)));
        int infoLines=font.split(info,helpWidth).size();
        g.drawWordWrap(font,info,hudLeft,y-8-infoLines*10,helpWidth,0xffffdc8b);
    }
    private void drawTranslucentDraft(GuiGraphics g,List<Vec3[]> projected) {
        if (!overlay.begin(g,false)) return;
        for (var triangle : projected)
            overlay.face(g,triangle[0],triangle[1],triangle[2],0xc54e6972);
        g.flush();
    }
    private void drawEditingPoints(GuiGraphics g) {
        var top=topology();
        if(overlay.begin(g,false)){for(int i:selectedFaces)if(i<top.faces.size()){var t=top.faces.get(i);overlay.face(g,editProject(top.point(t.a())),editProject(top.point(t.b())),editProject(top.point(t.c())),0x666acbff);}g.flush();}
        if(overlay.begin(g,xray)){
            Set<PolyShape.Connection> guides=new HashSet<>();for(var e:document.connections())guides.add(PolyEditorTopology.edge(e.source(),e.target()));
            for(var edge:top.edges){Vec3 a=editProject(top.point(edge.source())),b=editProject(top.point(edge.target()));boolean selected=selectedConnections.contains(edge);boolean pending=document.connections().stream().anyMatch(c->PolyEditorTopology.edge(c.source(),c.target()).equals(edge)&&(document.lineMesh()?document.pendingConnections().contains(c):compiled.pending().containsKey(c.source())));int color=selected?0xffffec86:guides.contains(edge)?0xffcf96ff:0xffa4d4c3;
                if(pending&&a!=null&&b!=null){int n=Math.max(1,Math.min(512,(int)Math.ceil(Math.hypot(b.x-a.x,b.y-a.y)/(6*SmoothOverlay.pixel()))));for(int i=0;i<n;i+=2)overlay.line(g,a.lerp(b,(double)i/n),a.lerp(b,(double)Math.min(i+1,n)/n),color,selected,1.5);}else overlay.line(g,a,b,color,selected,1.5);}
            if(selectionMode==0)for(int i=0;i<document.vertices().size();i++){var v=document.vertices().get(i);overlay.point(g,editProject(PolyGizmo.position(v.position())),v.type()==VertexType.CUSTOM?0xffcf96ff:0xffffdc8b,gizmo.isSelected(i),gizmo.selected==i,compiled.pending().containsKey(v.id()));}g.flush();
        }
        if(linkDragging&&linkSource>=0&&linkSource<rows.size()){Vec3 a=editProject(PolyGizmo.position(document.vertices().get(linkSource).position()));int target=pickVertex(pointerX,pointerY,linkSource);Vec3 b=target<0?new Vec3(pointerX,pointerY,0):editProject(PolyGizmo.position(document.vertices().get(target).position()));SmoothOverlay.line(g,a,b,0xffe3c3ff,4.5*SmoothOverlay.pixel());}
        if (handlesVisible()) gizmo.drawAxes(g, font, draftPoints(), this::axisHandles);
    }
    private boolean handlesVisible(){return axesVisible && linkSource<0 && !linkDragging && !marquee.active();}
    private void drawOpeningFaces(GuiGraphics g) {
        if(!showOpenings || conflict)return;
        for(var t:openingFaces) {
            Vec3 a=editProject(PolyGizmo.position(openingPoints.get(t.a()))),b=editProject(PolyGizmo.position(openingPoints.get(t.b()))),c=editProject(PolyGizmo.position(openingPoints.get(t.c())));
            if(a==null || b==null || c==null)continue;
            SmoothOverlay.triangle(g,a.x,a.y,b.x,b.y,c.x,c.y,0x88ff4343);
            for(var e:new Vec3[][]{{a,b},{b,c},{c,a}})SmoothOverlay.line(g,e[0],e[1],0xddff6868,2*SmoothOverlay.pixel());
        }
    }
    private EditorWorldProjection.Ray editRay(double x,double y) {
        EditorWorldProjection.Ray ray;
        if(worldMode){if(worldView==null)return null;ray=worldView.ray(x,y);}
        else {
            double scale=Math.min(previewWidth-30,imageHeight-125)*.42*zoom/span;
            Vec3 origin=new Vec3((x-previewX-previewWidth*.5)/scale,-(y-topPos-30-(imageHeight-94)*.5)/scale,10);
            ray=new EditorWorldProjection.Ray(unrotate(origin).add(center),unrotate(new Vec3(0,0,-1)));
        }
        return ray;
    }
    private Polyhedron.Point surfaceHit(double x,double y) {
        var ray=editRay(x,y);if(ray==null)return null;
        var point=EditorSurfacePicking.hit(preview,ray.origin(),ray.direction(),hasControlDown());
        // An empty/open mesh can still add vertices inside the target block's bounds.
        return point==null && document.manualMesh()?EditorSurfacePicking.hit(editBox,ray.origin(),ray.direction(),hasControlDown()):point;
    }
    private boolean pickFace(double x,double y,boolean multiple) {
        var ray=editRay(x,y);if(ray==null)return false;
        selectedConnection=null;selectedConnections.clear();
        try {
            var triangles=document.surfaceTriangles();
            List<PolyShape.Vertex> vertices=document.vertices();Map<Integer,Integer> index=new HashMap<>();for(int i=0;i<vertices.size();i++)index.put(vertices.get(i).id(),i);
            var indexed=triangles.stream().map(t->new Polyhedron.Triangle(index.get(t.a()),index.get(t.b()),index.get(t.c()))).toList();
            var mesh=Polyhedron.surface(vertices.stream().map(PolyShape.Vertex::position).toList(),indexed,bounds);
            var hit=EditorSurfacePicking.triangle(mesh,ray.origin(),ray.direction());
            if(hit!=null&&!overlay.visible(editProject(ray.origin().add(ray.direction().scale(hit.distance())))))hit=null;
            if(!multiple)selectedFaces.clear();
            if(hit!=null) {
                Set<Integer> patch=new LinkedHashSet<>();patch.add(indexed.indexOf(mesh.triangles().get(hit.index())));
                if(!hasShiftDown()) {
                    var plane=mesh.faces().get(hit.index()).plane();boolean progress=true;
                    while(progress){progress=false;for(int i=0;i<mesh.triangles().size();i++) {
                        final int candidate=indexed.indexOf(mesh.triangles().get(i));
                        if(!patch.contains(candidate) && mesh.faces().get(i).plane().equals(plane)
                                && patch.stream().anyMatch(j->shareUncutEdge(triangles.get(candidate),triangles.get(j)))){patch.add(candidate);progress=true;}
                    }}
                }
                if(multiple && selectedFaces.containsAll(patch))selectedFaces.removeAll(patch);else selectedFaces.addAll(patch);
            }
            Set<Integer> ids=new LinkedHashSet<>();for(int i:selectedFaces)for(int id:triangles.get(i).indices())ids.add(index.get(id));
            if(facePicking)gizmo.replaceSelection(ids);else gizmo.clearSelection();page=0;rebuild();return hit!=null;
        }catch(IllegalArgumentException ex){error=tr("error."+ex.getMessage());return false;}
    }
    private boolean shareUncutEdge(Polyhedron.Triangle a,Polyhedron.Triangle b) {
        int[] shared=Arrays.stream(a.indices()).filter(b::uses).toArray();return shared.length==2 && !document.isDrawnEdge(shared[0],shared[1]);
    }
    private Vec3 unrotate(Vec3 p) {
        double y=Math.cos(pitch)*p.y+Math.sin(pitch)*p.z,z=-Math.sin(pitch)*p.y+Math.cos(pitch)*p.z;
        return new Vec3(Math.cos(yaw)*p.x-Math.sin(yaw)*z,y,Math.sin(yaw)*p.x+Math.cos(yaw)*z);
    }
    private void closePopup() {
        for(var b:contextButtons)removeWidget(b);contextButtons.clear();popupOpen=false;setFocused(null);
    }
    private void openPopup(double x,double y,Polyhedron.Point point) {
        closePopup();camera.clear();popupOpen=true;
        int w=Math.max(130,Math.max(font.width(tr("add_custom")),font.width(tr("add_normal")))+16);
        popupX=Math.clamp((int)x,0,Math.max(0,width-w));popupY=Math.clamp((int)y,0,Math.max(0,height-44));
        for(int i=0;i<2;i++) {
            VertexType type=i==0?VertexType.CUSTOM:VertexType.NORMAL;
            Button b=button(i==0?"add_custom":"add_normal",popupX,popupY+i*22,w,()->{closePopup();createVertex(type,point);});
            b.active=rows.size()<bounds.maxPoints();contextButtons.add(b);
        }
    }
    private boolean inCanvas(double x,double y) {
        if(worldMode)return !(x>=leftPos && x<leftPos+imageWidth && y>=topPos && y<topPos+(minimized?25:imageHeight));
        return x>=previewX && x<previewX+previewWidth && y>=topPos+47 && y<topPos+imageHeight-96;
    }
    private void clampPanel() {
        panelX=Math.clamp(panelX,0,Math.max(0,width-imageWidth));
        panelY=Math.clamp(panelY,0,Math.max(0,height-(minimized?25:imageHeight)));
        leftPos=panelX;topPos=panelY;
    }
    private void stopMouseAction() {
        previewLooking=false;panelDragging=false;
        gizmo.release();
        rightPending=false;linkSource=-1;linkDragging=false;linkControl=false;dragCheckpoint=false;
        cameraMouse.release();
    }
    private void updateMarquee() {
        if(!marquee.active())return;
        var top=topology();var box=new PolyEditorTopology.Box(marquee.minX(),marquee.minY(),marquee.maxX(),marquee.maxY());selectedFaces.clear();selectedConnections.clear();selectedConnection=null;
        if(selectionMode==0)gizmo.replaceSelection(marquee.find(draftPoints(),this::editProject,p->inCanvas(p.x,p.y)));
        else if(selectionMode==1){selectedConnections.addAll(top.edgesIn(box,this::editProject));edgeVertices();}
        else{selectedFaces.addAll(top.facesIn(box,this::editProject));Set<Integer> ids=new HashSet<>();for(int i:selectedFaces)for(int id:top.faces.get(i).indices())ids.add(id);gizmo.replaceSelection(java.util.stream.IntStream.range(0,document.vertices().size()).filter(i->ids.contains(document.vertices().get(i).id())).boxed().toList());}
        rebuild();
    }
    private void drawMarquee(GuiGraphics g) {
        if(!marquee.active())return;
        double x0=marquee.minX(),y0=marquee.minY(),x1=marquee.maxX(),y1=marquee.maxY();
        g.fill((int)x0,(int)y0,(int)Math.ceil(x1),(int)Math.ceil(y1),0x286acbff);
        Vec3 a=new Vec3(x0,y0,0),b=new Vec3(x1,y0,0),c=new Vec3(x1,y1,0),d=new Vec3(x0,y1,0);
        for(Vec3[] edge:new Vec3[][]{{a,b},{b,c},{c,d},{d,a}})SmoothOverlay.line(g,edge[0],edge[1],0xff86d9ff,2*SmoothOverlay.pixel());
    }
    @Override public void mouseMoved(double x,double y){if(cameraMouse.active())return;pointerX=x;pointerY=y;super.mouseMoved(x,y);}
    @Override public boolean mouseClicked(double x,double y,int button) {
        if (waiting || cameraMouse.active()) return true;
        pointerX=x;pointerY=y;
        if(popupOpen) {
            boolean over=contextButtons.stream().anyMatch(b->b.isMouseOver(x,y));
            if(over){for(var b:List.copyOf(contextButtons))if(b.mouseClicked(x,y,button))break;return true;}
            closePopup();return true;
        }
        if(inCanvas(x,y)) {
            setFocused(null);coordinateSession=null;
            boolean control=hasControlDown();
            if(button==1){rightPending=true;rightX=x;rightY=y;contextPoint=surfaceHit(x,y);return true;}
            if(button==0&&!conflict){
                int vertex=selectionMode==0?pickVertex(x,y,-1):-1;
                if(vertex>=0){linkSource=vertex;linkX=x;linkY=y;linkControl=control;linkDragging=false;axesVisible=false;if(control)marquee.begin(x,y);return true;}
                if(handlesVisible()&&gizmo.clickAxes(x,y,draftPoints(),this::axisHandles,axisMapper()))return true;
                if(control)marquee.begin(x,y);
                if(selectionMode==1){var edge=topology().pickEdge(x,y,this::editProject,p->xray||overlay.visible(p),Math.max(4,9*SmoothOverlay.pixel()));if(edge!=null){selectEdge(edge,control);return true;}}
                if(selectionMode==2){boolean hit=pickFace(x,y,control);axesVisible=true;if(hit||control)return true;}
                if(marquee.pending())return true;
                if(!control){selectedFaces.clear();selectedConnections.clear();selectedConnection=null;gizmo.clearSelection();rebuild();}
            }
            return true;
        }
        if(super.mouseClicked(x,y,button))return true;
        if(worldMode && button==0 && y>=topPos && y<topPos+25){panelDragging=true;return true;}
        return true;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy) {
        if (cameraMouse.active()) {
            if (cameraMouse.owns(button)) camera.look(dx,dy);
            return true;
        }
        pointerX=x;pointerY=y;
        if(button==0 && gizmo.dragging()) {
            boolean changed=false;
            for(var moved:gizmo.dragSelection(x,y,hasControlDown()).entrySet()) {
                int index=moved.getKey();var p=moved.getValue();
                if(index<0 || index>=rows.size())continue;
                String[] row={VertexEditing.format(p.x()),VertexEditing.format(p.y()),VertexEditing.format(p.z())};
                if(!Arrays.equals(rows.get(index),row)){
                    if(!dragCheckpoint){remember();dragCheckpoint=true;}
                    rows.set(index,row);changed=true;
                }
            }
            if(changed){syncDocument();markDirty();syncFields();}
            return true;
        }
        if(button==0 && marquee.pending()){marquee.drag(x,y,3*SmoothOverlay.pixel());if(marquee.active()){linkSource=-1;linkDragging=false;axesVisible=false;}updateMarquee();return true;}
        if(button==0 && linkSource>=0) {
            if(Math.hypot(x-linkX,y-linkY)>3*SmoothOverlay.pixel())linkDragging=true;
            return true;
        }
        if(button==0 && panelDragging){panelX+=(int)Math.round(dx);panelY+=(int)Math.round(dy);clampPanel();rebuild();return true;}
        if(button==1 && rightPending && Math.hypot(x-rightX,y-rightY)>3*SmoothOverlay.pixel()) {
            rightPending=false;
            if (worldMode) {
                pointerX=rightX;pointerY=rightY;
                cameraMouse.grab(button,rightX,rightY);
                camera.look(dx,dy);
                return true;
            }
            previewLooking=true;
        }
        if(button==1 && previewLooking){yaw+=dx*.015;pitch=Math.max(-1.4,Math.min(1.4,pitch+dy*.015));return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    private void syncFields() {
        updatingFields=true;
        try{for(int line=0;line<fields.size();line++)for(int axis=0;axis<3;axis++)
            fields.get(line)[axis].setValue(rows.get(fieldIndices.get(line))[axis]);}
        finally{updatingFields=false;}
    }
    @Override public boolean mouseReleased(double x,double y,int button){
        if (cameraMouse.active()) {
            if (cameraMouse.owns(button)) stopMouseAction();
            return true;
        }
        boolean consumed=gizmo.dragging() || previewLooking || panelDragging || marquee.pending() || rightPending || linkSource>=0;
        if(button==1 && rightPending) {
            Polyhedron.Point point=contextPoint;stopMouseAction();
            if(point!=null && !conflict)openPopup(x,y,point);
            return true;
        }
        if(button==0 && marquee.pending()) {
            marquee.drag(x,y,3*SmoothOverlay.pixel());updateMarquee();
            if(marquee.active()){marquee.cancel();stopMouseAction();axesVisible=true;rebuild();return true;}
            marquee.cancel();
        }
        if(button==0 && linkSource>=0) {
            int source=linkSource;boolean control=linkControl;
            boolean dragged=linkDragging || Math.hypot(x-linkX,y-linkY)>3*SmoothOverlay.pixel();
            int target=dragged?pickVertex(x,y,source):-1;
            stopMouseAction();
            if(dragged){axesVisible=false;if(target>=0)connectVertices(source,target);}
            else{if(!selectedFaces.isEmpty() || selectedConnection!=null){selectedFaces.clear();selectedConnections.clear();selectedConnection=null;gizmo.clearSelection();}gizmo.select(source,control);axesVisible=true;page=0;rebuild();}
            return true;
        }
        if(button==0){gizmo.release();if(dirty)validate();}
        stopMouseAction();
        return consumed || super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if (cameraMouse.active()) { camera.dolly(vertical);return true; }
        if(popupOpen)return true;
        if(gizmo.dragging())return true;
        if(inCanvas(x,y)) {
            if(worldMode)camera.dolly(vertical);else zoom=Math.max(.5,Math.min(1.5,zoom+vertical*.08));
            return true;
        }
        page=Math.max(0,Math.min(Math.max(0,(gizmo.selectedCount()-1)/visibleRows),page-(int)Math.signum(vertical)));rebuild();return true;
    }
    @Override public void removed() {
        stopMouseAction();marquee.cancel();camera.stop();overlay.close();gizmo.release();setWorldPreview(null);
        if(minecraft.player!=null) {
            menu.removed(minecraft.player);
            if(minecraft.player.containerMenu==menu) {
                minecraft.player.connection.send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(menu.containerId));
                minecraft.player.containerMenu=minecraft.player.inventoryMenu;
            }
        }
        super.removed();
    }
    @Override public void onClose() {
        if(minecraft.player!=null && minecraft.player.containerMenu==menu)minecraft.player.closeContainer();
        else super.onClose();
    }
    @Override public boolean keyPressed(int key,int scan,int mods) {
        if(key==GLFW.GLFW_KEY_ESCAPE){if(popupOpen)closePopup();else onClose();return true;}
        if(waiting)return true;
        if(cameraMouse.active() && camera.key(key,scan,true))return true;
        if(((mods&GLFW.GLFW_MOD_CONTROL)!=0 || hasControlDown()) && key==GLFW.GLFW_KEY_Z){undo();return true;}
        if(getFocused() instanceof EditBox box && box.keyPressed(key,scan,mods))return true;
        // Inventory hotkey must not close an editor while a text field is focused.
        if(getFocused() instanceof EditBox)return false;
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_3){selectionMode(key-GLFW.GLFW_KEY_1);return true;}
        if(key==GLFW.GLFW_KEY_F){createFace();return true;}
        if(key==GLFW.GLFW_KEY_DELETE){if(selectionMode==1)deleteConnection();else if(selectionMode==2)deleteFaces();else deleteVertices(selectedIds());return true;}
        if((mods&GLFW.GLFW_MOD_CONTROL)!=0 || hasControlDown()) {
            if(key==GLFW.GLFW_KEY_A){
                selectedConnection=null;selectedConnections.clear();selectedFaces.clear();
                if(selectionMode==1){selectedConnections.addAll(topology().edges);edgeVertices();}else if(selectionMode==2){var top=topology();for(int i=0;i<top.faces.size();i++)selectedFaces.add(i);gizmo.replaceSelection(java.util.stream.IntStream.range(0,rows.size()).boxed().toList());}else gizmo.replaceSelection(java.util.stream.IntStream.range(0,rows.size()).boxed().toList());rebuild();return true;
            }
            if(key==GLFW.GLFW_KEY_C){copySelectedVertices();return true;}
            if(key==GLFW.GLFW_KEY_V){pasteVertices();return true;}
        }
        if(worldMode && camera.key(key,scan,true))return true;
        return super.keyPressed(key,scan,mods);
    }
    @Override public boolean keyReleased(int key,int scan,int mods) {
        boolean handled=camera.key(key,scan,false);
        return handled || super.keyReleased(key,scan,mods);
    }
    @Override public boolean charTyped(char character,int modifiers) {
        return waiting || super.charTyped(character,modifiers);
    }
}
