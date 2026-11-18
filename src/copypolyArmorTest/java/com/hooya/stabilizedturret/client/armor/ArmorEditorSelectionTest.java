package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.content.armor.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ArmorEditorSelectionTest {
    private static final EditableArmorMesh CUBE=EditableArmorMesh.from(ArmorMesh.of(Polyhedron.CUBE));
    private static final Function<Vec3,Vec3> PROJECT=p->new Vec3(100+160*p.x,200-160*p.y,p.z);
    @Test void hiddenVertexCannotBeClickedButMarqueeStillIncludesIt() {
        var picking=new ArmorEditorSelection(CUBE);
        int front=picking.vertex(100,200,PROJECT,p->p.z<.5,8);assertEquals(0,CUBE.vertices().get(front).z);
        assertEquals(-1,picking.vertex(100,200,PROJECT,p->false,8));
        var boxed=picking.verticesIn(ArmorEditorSelection.Box.between(95,195,105,205),PROJECT);
        assertEquals(2,boxed.size());assertTrue(boxed.stream().anyMatch(i->CUBE.vertices().get(i).z==1));
    }
    @Test void linePickingHonorsVisibilityAndOverlappingWireDepth() {
        var picking=new ArmorEditorSelection(CUBE);
        var hit=picking.edge(180,200,PROJECT,p->true,5);assertNotNull(hit);
        assertEquals(0,CUBE.vertices().get(hit.a()).z);assertEquals(0,CUBE.vertices().get(hit.b()).z);
        assertNull(picking.edge(180,200,PROJECT,p->false,5));assertNull(picking.edge(180,210,PROJECT,p->true,5));
    }
    @Test void lineBoxFindsCrossingEdgesWithoutSelectingEndpointsOrInvisibleDiagonals() {
        var picking=new ArmorEditorSelection(CUBE);var box=ArmorEditorSelection.Box.between(170,195,190,205);
        assertTrue(picking.verticesIn(box,PROJECT).isEmpty());var edges=picking.edgesIn(box,PROJECT);assertEquals(2,edges.size());
        assertEquals(12,picking.edges().size());
    }
    @Test void facePatchesMergeOnlyAcrossUncutCoplanarEdgesAndRayHitsFrontSurface() {
        var picking=new ArmorEditorSelection(CUBE);var faces=new HashSet<Set<Integer>>();for(int i=0;i<CUBE.triangles().size();i++)faces.add(picking.patch(i));
        assertEquals(6,faces.size());assertTrue(faces.stream().allMatch(p->p.size()==2));
        var hit=picking.face(new Vec3(.3,.2,-2),new Vec3(0,0,1));assertNotNull(hit);assertEquals(0,hit.point().z,1e-9);assertEquals(2,hit.distance(),1e-9);
        assertNull(picking.face(new Vec3(2,2,-2),new Vec3(0,0,1)));
    }
    @Test void faceMarqueeCanSelectFaceInteriorAndItsHiddenCounterpart() {
        var picking=new ArmorEditorSelection(CUBE);var box=ArmorEditorSelection.Box.between(150,110,160,120);
        assertTrue(picking.verticesIn(box,PROJECT).isEmpty());assertTrue(picking.edgesIn(box,PROJECT).isEmpty());
        var faces=picking.facesIn(box,PROJECT);assertEquals(4,faces.size());
    }
    @Test void playerCutPreventsFaceSelectionFromCrossingIt() {
        EditableArmorMesh connected=null;
        outer:for(int a=0;a<CUBE.vertices().size();a++)for(int b=a+1;b<CUBE.vertices().size();b++) {
            var edge=new EditableArmorMesh.Edge(a,b);if(CUBE.edges().contains(edge))continue;
            try{connected=CUBE.connect(edge);break outer;}catch(IllegalArgumentException ignored){}
        }
        assertNotNull(connected);var picking=new ArmorEditorSelection(connected);var patches=new HashSet<Set<Integer>>();
        for(int i=0;i<connected.triangles().size();i++)patches.add(picking.patch(i));assertEquals(7,patches.size());
    }
    @Test void rectangleHandlesReverseDragDegenerateProjectionAndOutsideEdges() {
        var box=ArmorEditorSelection.Box.between(20,30,10,15);assertEquals(new ArmorEditorSelection.Box(10,15,20,30),box);
        assertTrue(box.intersects(new Vec3(0,20,0),new Vec3(40,20,0)));assertFalse(box.intersects(new Vec3(0,0,0),new Vec3(40,0,0)));
        assertNull(ArmorEditorSelection.segmentPoint(10,10,null,Vec3.ZERO));assertNull(ArmorEditorSelection.segmentPoint(0,0,Vec3.ZERO,Vec3.ZERO));
        var sample=ArmorEditorSelection.segmentPoint(5,2,Vec3.ZERO,new Vec3(10,0,1));assertEquals(.5,sample.projected().z,1e-9);assertEquals(2,sample.distance(),1e-9);
    }
}
