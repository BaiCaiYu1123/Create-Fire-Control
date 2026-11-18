// SPDX-License-Identifier: MIT
package com.caca.copypoly.client;

import com.caca.copypoly.api.PolyShape;
import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class PolyEditorTopologyTest {
    private static final PolyShape CUBE=PolyShape.fromGeometry(Polyhedron.CUBE);
    private static final Function<Vec3,Vec3> PROJECT=p->new Vec3(100+160*p.x,200-160*p.y,p.z);
    @Test void cubeHasTwelveEdgesAndSixLogicalFaces(){var top=new PolyEditorTopology(CUBE);assertEquals(12,top.edges.size());Set<Set<Integer>> patches=Collections.newSetFromMap(new IdentityHashMap<>());for(int i=0;i<top.faces.size();i++)patches.add(top.patch(i));assertEquals(6,patches.size());}
    @Test void cutsAreSelectableBoundariesAndSeparateCoplanarPatches(){var top=new PolyEditorTopology(CUBE.toBuilder().connect(3,6).build());assertTrue(top.edges.contains(new PolyShape.Connection(3,6)));Set<Set<Integer>> groups=new HashSet<>();for(int i=0;i<top.faces.size();i++)groups.add(top.patch(i));assertEquals(7,groups.size());}
    @Test void edgePickingFiltersOcclusionAndUsesFrontDepth(){var top=new PolyEditorTopology(CUBE);var edge=top.pickEdge(180,200,PROJECT,p->true,5);assertNotNull(edge);assertEquals(0,top.point(edge.source()).z);assertEquals(0,top.point(edge.target()).z);assertNull(top.pickEdge(180,200,PROJECT,p->false,5));}
    @Test void boxSelectionIncludesHiddenEdgesAndFaceInteriors(){var top=new PolyEditorTopology(CUBE);assertEquals(2,top.edgesIn(new PolyEditorTopology.Box(170,195,190,205),PROJECT).size());assertEquals(4,top.facesIn(new PolyEditorTopology.Box(150,110,160,120),PROJECT).size());}
    @Test void historyRestoresMultipleSelectedEdgesAndMode(){var shape=CUBE.toBuilder().connect(3,6).connect(0,5).build();var edges=shape.connections();var history=new ShapeHistory();history.remember(shape,List.of(0,3,5,6),List.of(),true,false,edges.getFirst(),1,edges);var state=history.undo();assertEquals(1,state.mode());assertEquals(edges,state.connections());assertEquals(shape,state.shape());}
    @Test void vertexVisibilityIsOnlyQueriedNearTheMouse(){var checked=new int[]{0};var points=CUBE.vertices().stream().map(PolyShape.Vertex::position).toList();int picked=PolyGizmo.pickVertex(100,200,points,PROJECT,-1,8,p->{checked[0]++;return p.z==0;});assertTrue(picked>=0);assertTrue(checked[0]<=2);}
}
