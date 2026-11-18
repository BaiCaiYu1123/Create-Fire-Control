package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EditableArmorMeshTest {
    private static ArmorMesh bored(Vec3 direction){return ArmorMesh.of(Polyhedron.CUBE).subtract(ArmorHole.along(new Vec3(.5,.5,.5),direction));}
    @Test void simpleSquareHoleAddsExactlyEightControlsAndNoTriangulationWires(){
        var damage=new ArmorDamage();damage.add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));var mesh=damage.editable(Polyhedron.CUBE);
        assertEquals(16,mesh.vertices().size());assertEquals(24,mesh.edges().size());assertEquals(8,mesh.kinds().stream().filter(k->k==2).count());
        for(int i=0;i<8;i++){var p=Polyhedron.CUBE.points().get(i);assertEquals(new Vec3(p.x()/16,p.y()/16,p.z()/16),mesh.vertices().get(i));assertEquals(0,mesh.kinds().get(i));}mesh.validate();
    }
    @Test void profileImpactStages(){
        for(var direction:List.of(new Vec3(0,0,1),new Vec3(.6,.23,1))){var timings=new ArrayList<double[]>();int count=0;
            for(int repeat=0;repeat<7;repeat++){var d=new ArmorDamage();long begin=System.nanoTime();d.add(ArmorHole.along(new Vec3(.5,.5,.5),direction));d.volume(Polyhedron.CUBE);long a=System.nanoTime();var boxes=d.boxes(Polyhedron.CUBE);long b=System.nanoTime();d.shape(Polyhedron.CUBE);long c=System.nanoTime();var edit=d.editable(Polyhedron.CUBE);long end=System.nanoTime();count=boxes.size();assertEquals(16,edit.vertices().size());assertTrue(boxes.size()<400);if(repeat>=4)timings.add(new double[]{(a-begin)/1e6,(b-a)/1e6,(c-b)/1e6,(end-c)/1e6});}
            var median=new double[4];for(int i=0;i<4;i++){final int axis=i;median[i]=timings.stream().mapToDouble(t->t[axis]).sorted().toArray()[1];}
            System.out.printf(Locale.ROOT,"ARMOR_PROFILE warm median %s csg=%.3f contacts=%.3f shape=%.3f editor=%.3f ms boxes=%d vertices=16%n",direction,median[0],median[1],median[2],median[3],count);}
    }
    @Test void originalCustomControlsAndPlayerConnectionsSurviveTheShot(){
        var builder=com.caca.copypoly.api.PolyShape.fromGeometry(Polyhedron.CUBE).toBuilder();int custom=builder.addVertex(com.caca.copypoly.api.VertexType.CUSTOM,new Polyhedron.Point(0,8,0));builder.connect(custom,0);builder.connect(custom,2);var doc=builder.build();
        var damage=new ArmorDamage();damage.add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));var edit=damage.editable(doc.compile().geometry(),doc);
        assertEquals(doc.vertices().size()+8,edit.vertices().size());assertEquals(new Vec3(0,.5,0),edit.vertices().get(8));assertEquals(1,edit.kinds().get(8));assertEquals(2,edit.guides().size());edit.validate();
    }
    @Test void legacyTessellationIsCompactedWithoutChangingSavedGeometry(){
        var edit=EditableArmorMesh.from(bored(new Vec3(0,0,1)));for(int i=0;i<6;i++)edit=edit.split(edit.edges().getFirst());
        var tag=edit.save();tag.remove("Kinds");tag.remove("Guides");var saved=new CompoundTag();saved.put(ArmorDamage.EDITED_TAG,tag);var damage=new ArmorDamage();damage.read(saved);
        var compact=damage.editable(Polyhedron.CUBE);assertEquals(16,compact.vertices().size());assertEquals(edit.mesh().volume(),compact.mesh().volume(),1e-9);compact.validate();
    }
    @Test void explicitSurfaceConnectionsDoNotExposeOtherTriangulationEdges(){
        var cube=EditableArmorMesh.from(ArmorMesh.of(Polyhedron.CUBE));EditableArmorMesh connected=null;
        for(int i=0;i<cube.vertices().size()&&connected==null;i++)for(int j=i+1;j<cube.vertices().size();j++){var e=new EditableArmorMesh.Edge(i,j);if(cube.edges().contains(e))continue;try{connected=cube.connect(e);break;}catch(IllegalArgumentException ignored){}}
        assertNotNull(connected);connected.validate();assertEquals(13,connected.edges().size());assertEquals(1,connected.guides().size());assertEquals(1,connected.mesh().volume(),1e-9);
    }
    @Test void incrementalServerAndClientCutsAgreeWithSavedReconstruction(){
        var server=new ArmorDamage();var client=new ArmorDamage();server.mesh(Polyhedron.CUBE);client.mesh(Polyhedron.CUBE);
        for(int i=0;i<4;i++){
            server.add(ArmorHole.along(new Vec3(.2+.2*i,.5,0),new Vec3(.07*i,.02*i,1)));var tag=new CompoundTag();server.write(tag);client.read(tag);var reload=new ArmorDamage();reload.read(tag);
            assertEquals(reload.volume(Polyhedron.CUBE),server.volume(Polyhedron.CUBE),1e-8);assertEquals(reload.volume(Polyhedron.CUBE),client.volume(Polyhedron.CUBE),1e-8);
            var edit=server.editable(Polyhedron.CUBE);edit.validate();assertEquals(server.volume(Polyhedron.CUBE),edit.mesh().volume(),1e-8);
        }
    }
    @Test void generatedHoleVerticesAreClosedWeldedAndPreserveExactGeometry(){
        for(var direction:List.of(new Vec3(0,0,1),new Vec3(.6,.23,1),new Vec3(1,0,0),new Vec3(.7,1,-.2))){
            var mesh=bored(direction);var editable=EditableArmorMesh.from(mesh);assertDoesNotThrow(editable::validate,"Direction "+direction);
            assertEquals(mesh.volume(),editable.mesh().volume(),1e-9);Vec3 center=new Vec3(.5,.5,.5),ray=direction.normalize().scale(2);
            assertNull(editable.mesh().clip(center.subtract(ray),center.add(ray)));assertTrue(editable.vertices().stream().anyMatch(p->(p.x>0&&p.x<1?1:0)+(p.y>0&&p.y<1?1:0)+(p.z>0&&p.z<1?1:0)>=2));
        }
    }
    @Test void largeTiledSurfaceWeldsToLongUntessellatedEdges() {
        var faces = new ArrayList<ArmorMesh.Face>();
        for (var face : Polyhedron.CUBE.faces()) {
            var vertices = face.vertices().stream().map(p -> new Vec3(p.x()/16, p.y()/16, p.z()/16)).toList();
            if (vertices.stream().anyMatch(p -> p.y < 1)) faces.add(new ArmorMesh.Face(vertices));
        }
        int tiles = 24;
        for (int x = 0; x < tiles; x++) {
            for (int z = 0; z < tiles; z++) {
                double x0 = x / (double) tiles, x1 = (x + 1) / (double) tiles;
                double z0 = z / (double) tiles, z1 = (z + 1) / (double) tiles;
                faces.add(new ArmorMesh.Face(List.of(new Vec3(x0, 1, z0), new Vec3(x0, 1, z1),
                        new Vec3(x1, 1, z1), new Vec3(x1, 1, z0))));
            }
        }
        var mesh = EditableArmorMesh.triangulated(new ArmorMesh(faces));
        assertTrue(mesh.triangles().size() > 2300);
        assertDoesNotThrow(mesh::validate);
        assertEquals(1.0, mesh.mesh().volume(), 1e-9);
    }
    @Test void crossingAndOverlappingBoresCanBeMaterializedAndShotAgain(){
        var damage=new ArmorDamage();damage.add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));damage.add(ArmorHole.along(new Vec3(0,.5,.5),new Vec3(1,0,0)));
        var editable=damage.editable(Polyhedron.CUBE);assertDoesNotThrow(editable::validate);double volume=damage.volume(Polyhedron.CUBE);damage.install(editable);
        assertEquals(volume,damage.volume(Polyhedron.CUBE),1e-8);assertTrue(damage.holes().isEmpty());damage.add(ArmorHole.along(new Vec3(.55,.5,0),new Vec3(.1,.2,1)));
        assertTrue(damage.volume(Polyhedron.CUBE)<volume);assertDoesNotThrow(damage.editable(Polyhedron.CUBE)::validate);
    }
    @Test void selectingHoleWallAndMovingItChangesTheRealOpeningAndSurvivesNbt(){
        var damage=new ArmorDamage();damage.add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));var editable=damage.editable(Polyhedron.CUBE);
        var selected=new HashSet<Integer>();for(int i=0;i<editable.vertices().size();i++)if(Math.abs(editable.vertices().get(i).x-(.5+1.0/16))<1e-8)selected.add(i);
        assertTrue(selected.size()>=4);var moved=editable.move(selected,new Vec3(1.0/16,0,0));assertDoesNotThrow(moved::validate);damage.install(moved);
        var from=new Vec3(.62,.5,-1);var to=new Vec3(.62,.5,2);assertNotNull(editable.mesh().clip(from,to));assertNull(damage.mesh(Polyhedron.CUBE).clip(from,to));
        var tag=new CompoundTag();damage.write(tag);var saved=new ArmorDamage();saved.read(tag);assertEquals(moved,saved.editable(Polyhedron.CUBE));assertNull(saved.mesh(Polyhedron.CUBE).clip(from,to));
        assertEquals(1-(3.0*2/256),saved.volume(Polyhedron.CUBE),1e-8);
    }
    @Test void splittingEdgeAddsAnEditableVertexWithoutBreakingTheSolid(){
        var editable=EditableArmorMesh.from(bored(new Vec3(.6,.2,1)));var split=editable.split(editable.edges().getFirst());assertEquals(editable.vertices().size()+1,split.vertices().size());assertDoesNotThrow(split::validate);assertEquals(editable.mesh().volume(),split.mesh().volume(),1e-9);
    }
    @Test void brokenAndOverlappingSurfacesAreRejectedBeforeSaving(){
        var mesh=EditableArmorMesh.from(ArmorMesh.of(Polyhedron.CUBE));
        assertThrows(IllegalArgumentException.class,()->new EditableArmorMesh(mesh.vertices(),mesh.triangles().subList(1,mesh.triangles().size())).validate());
        var duplicate=new ArrayList<>(mesh.triangles());duplicate.add(duplicate.getFirst());assertThrows(IllegalArgumentException.class,()->new EditableArmorMesh(mesh.vertices(),duplicate).validate());
        assertThrows(IllegalArgumentException.class,()->mesh.move(Set.of(0),new Vec3(2,0,0)));
        var points=new ArrayList<>(mesh.vertices());points.addAll(mesh.vertices().stream().map(p->p.scale(.7).add(.1,.1,.1)).toList());
        var faces=new ArrayList<>(mesh.triangles());for(var t:mesh.triangles())faces.add(new EditableArmorMesh.Triangle(t.a()+mesh.vertices().size(),t.b()+mesh.vertices().size(),t.c()+mesh.vertices().size()));
        // Two overlapping cubes with crossing boundaries, both individually closed.
        for(int i=mesh.vertices().size();i<points.size();i++){var p=points.get(i);points.set(i,p.add(.2,0,0));}
        var overlap=new EditableArmorMesh(points,faces);assertThrows(IllegalArgumentException.class,overlap::validate);
    }
    @Test void contactMeshFollowsEditedHoleAndNewOuterSurface(){
        var mesh=EditableArmorMesh.from(bored(new Vec3(0,1,0))).transform(p->new Vec3(p.x,p.y*.5,p.z),false);mesh.validate();
        var boxes=ArmorMeshContacts.build(mesh.mesh());assertFalse(boxes.isEmpty());assertTrue(boxes.stream().allMatch(b->b.maxY<=.5+1e-8));
        assertTrue(boxes.stream().noneMatch(b->b.minX<.5&&b.maxX>.5&&b.minZ<.5&&b.maxZ>.5));
        assertEquals(.5*(1-4.0/256),boxes.stream().mapToDouble(b->b.getXsize()*b.getYsize()*b.getZsize()).sum(),1e-8);
    }
    @Test void contactsCannotFillATunnelNarrowerThanTheContactGrid(){
        var mesh=EditableArmorMesh.from(bored(new Vec3(0,1,0))).transform(p->{double x=p.x,z=p.z;if(x>0&&x<1)x=.503+(x-.5)*.01;if(z>0&&z<1)z=.503+(z-.5)*.01;return new Vec3(x,p.y,z);},false);mesh.validate();
        var boxes=ArmorMeshContacts.build(mesh.mesh());assertFalse(boxes.isEmpty());assertTrue(boxes.stream().noneMatch(b->b.minX<.503&&b.maxX>.503&&b.minZ<.503&&b.maxZ>.503));
    }
}
