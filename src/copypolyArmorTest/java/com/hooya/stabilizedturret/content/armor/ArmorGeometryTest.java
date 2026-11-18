package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmorGeometryTest {
    private static final Vec3 ENTRY=new Vec3(.5,.5,0), FORWARD=new Vec3(0,0,1);
    @Test void exactSquareBoreHasTwoPixelCrossSectionAndInteriorWalls(){
        var damage=new ArmorDamage();damage.add(ArmorHole.along(ENTRY,FORWARD));var mesh=damage.mesh(Polyhedron.CUBE);
        assertEquals(1-4.0/256,mesh.volume(),1e-8);
        assertNull(mesh.clip(new Vec3(.5,.5,-1),new Vec3(.5,.5,2)));
        assertNull(mesh.clip(new Vec3(.5+.99/16,.5,-1),new Vec3(.5+.99/16,.5,2)));
        assertNotNull(mesh.clip(new Vec3(.5+1.01/16,.5,-1),new Vec3(.5+1.01/16,.5,2)));
        var wall=mesh.clip(new Vec3(.5,.5,.5),new Vec3(.8,.5,.5));assertNotNull(wall);
        assertEquals(1.0/16/.3,wall.fraction(),1e-7);
    }
    @Test void angledBoreFollowsIncomingRayRatherThanBlockFace(){
        Vec3 dir=new Vec3(1,.3,1).normalize(),center=new Vec3(.5,.5,.5);var hole=ArmorHole.along(center,dir);
        var mesh=ArmorMesh.of(Polyhedron.CUBE).subtract(hole);
        assertNull(mesh.clip(center.subtract(dir.scale(2)),center.add(dir.scale(2))));
        var inside=center.add(hole.u().scale(.99/16));assertNull(mesh.clip(inside.subtract(dir.scale(2)),inside.add(dir.scale(2))));
        var outside=center.add(hole.u().scale(1.01/16));assertNotNull(mesh.clip(outside.subtract(dir.scale(2)),outside.add(dir.scale(2))));
        assertTrue(mesh.volume()>.97&&mesh.volume()<.99);
    }
    @Test void editingShapeKeepsBoreAndRecomputesRemainingVolume(){
        var damage=new ArmorDamage();damage.add(ArmorHole.along(ENTRY,FORWARD));damage.mesh(Polyhedron.CUBE);
        var plate=Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/4)).toList());
        var changed=damage.mesh(plate);assertEquals(.25*(1-4.0/256),changed.volume(),1e-8);
        assertNull(changed.clip(new Vec3(.5,.5,-1),new Vec3(.5,.5,2)));
        assertNotNull(changed.clip(new Vec3(.2,.5,-1),new Vec3(.2,.5,2)));
        assertEquals(1-4.0/256,damage.mesh(Polyhedron.CUBE).volume(),1e-8);
    }
    @Test void intersectingBoresSubtractTheirUnionAndRepeatedShotsDoNotGrowGeometry(){
        var damage=new ArmorDamage();var first=ArmorHole.along(new Vec3(.4,.5,0),FORWARD);
        assertTrue(damage.add(first));assertFalse(damage.add(ArmorHole.along(new Vec3(.4,.5,1),FORWARD.reverse())));
        damage.add(ArmorHole.along(new Vec3(.5,.5,0),FORWARD));double w=ArmorHole.WIDTH;
        assertEquals(1-(2*w*w-(w-.1)*w),damage.mesh(Polyhedron.CUBE).volume(),1e-8);
        assertEquals(2,damage.holes().size());
    }
    @Test void saveDataRetainsExactAnglesAndSubPixelHitLocations(){
        var damage=new ArmorDamage();damage.add(ArmorHole.along(new Vec3(.427381,.615917,.1328),new Vec3(.3,-.72,1)));
        var tag=new CompoundTag();damage.write(tag);var restored=new ArmorDamage();restored.read(tag);
        assertEquals(damage.holes().getFirst().center(),restored.holes().getFirst().center());
        assertEquals(1,restored.holes().size());
        assertEquals(damage.volume(Polyhedron.CUBE),restored.volume(Polyhedron.CUBE),1e-9);
    }
    @Test void smallAndDisconnectedSolidVolumesAreNotBoundingBoxVolumes(){
        var tetra=Polyhedron.of(Polyhedron.parse("0,0,0;16,0,0;0,16,0;0,0,16"));
        assertEquals(1.0/6,ArmorMesh.of(tetra).volume(),1e-9);
        var small=Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x()/2,p.y()/2,p.z()/2)).toList());
        assertEquals(.125,ArmorMesh.of(small).volume(),1e-9);
    }
    @Test void aBoreCrossesDisconnectedSolidsWithoutFillingTheirGap(){
        var points=new java.util.ArrayList<Polyhedron.Point>();var faces=new java.util.ArrayList<Polyhedron.Triangle>();
        for(int part=0;part<2;part++){
            int offset=points.size();double origin=part==0?0:10;
            for(var p:Polyhedron.CUBE_POINTS)points.add(new Polyhedron.Point(origin+p.x()*6/16,p.y(),p.z()));
            for(var t:Polyhedron.CUBE.triangles())faces.add(new Polyhedron.Triangle(t.a()+offset,t.b()+offset,t.c()+offset));
        }
        var base=Polyhedron.orientedMesh(points,faces);assertEquals(.75,ArmorMesh.of(base).volume(),1e-8);
        var cut=ArmorMesh.of(base).subtract(ArmorHole.along(new Vec3(0,.5,.5),new Vec3(1,0,0)));
        assertEquals(.75*(1-4.0/256),cut.volume(),1e-8);
        assertNull(cut.clip(new Vec3(-1,.5,.5),new Vec3(2,.5,.5)));
        assertNotNull(cut.clip(new Vec3(-1,.2,.5),new Vec3(2,.2,.5)));
    }
    @Test void crossingBoresKeepTheExistingConcaveTunnelAndRemoveOnlyTheirUnion(){
        double w=ArmorHole.WIDTH;var base=ArmorMesh.of(Polyhedron.CUBE);
        var cut=base.subtract(ArmorHole.along(ENTRY,FORWARD))
                .subtract(ArmorHole.along(new Vec3(0,.5,.5),new Vec3(1,0,0)));
        assertEquals(1-2*w*w+w*w*w,cut.volume(),1e-8);
        assertNull(cut.clip(new Vec3(-1,.5,.5),new Vec3(2,.5,.5)));
        assertNull(cut.clip(new Vec3(.5,.5,-1),new Vec3(.5,.5,2)));
    }
    @Test void edgeHitsClipTheBoreAndMovingMaterialAwayDoesNotCreateFloatingWalls(){
        double w=ArmorHole.WIDTH;var damage=new ArmorDamage();damage.add(ArmorHole.along(new Vec3(.99,.5,0),FORWARD));
        assertEquals(1-(.01+w/2)*w,damage.volume(Polyhedron.CUBE),1e-8);
        var small=Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x()/4,p.y(),p.z())).toList());
        assertEquals(.25,damage.volume(small),1e-8);
        for(var face:damage.mesh(small).faces())for(var p:face.vertices())assertTrue(p.x<=.25+1e-8);
    }
}
