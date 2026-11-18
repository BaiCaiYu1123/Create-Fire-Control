package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArmorBoreFootprintTest {
    @Test void interiorSeamAndFourBlockCornerReceiveOneTwoAndFourParts() {
        var direction=new Vec3(0,0,1);
        for(var entry:Map.of(new Vec3(.5,.5,0),1,new Vec3(0,.5,0),2,new Vec3(0,0,0),4).entrySet()) {
            var bore=ArmorHole.along(entry.getKey(),direction,2.0/16);
            var cuts=ArmorBoreFootprint.cuts(bore);
            assertEquals(entry.getValue(),cuts.size());
            assertEquals(bore.width()*bore.width(),removedVolume(cuts),1e-9);
            for(var cut:cuts)assertEquals(0,cut.offset().getZ(),"A rear/front layer was included");
        }
    }

    @Test void cornerCutHasTheFullCaliberOnEveryAxisAndCanBeEditedAndSaved() {
        for(var direction:List.of(new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)))
            for(double width:new double[]{.75/16,2.0/16,6.0/16,10.0/16}) {
                var cuts=ArmorBoreFootprint.cuts(ArmorHole.along(Vec3.ZERO,direction,width));
                assertEquals(4,cuts.size());assertEquals(width*width,removedVolume(cuts),1e-9);
                for(var cut:cuts) {
                    var damage=new ArmorDamage();assertTrue(damage.perforate(Polyhedron.CUBE,cut.hole()));
                    assertEquals(1-width*width/4,damage.volume(Polyhedron.CUBE),1e-9);
                    damage.editable(Polyhedron.CUBE).validate();
                    var tag=new net.minecraft.nbt.CompoundTag();damage.write(tag);
                    var restored=new ArmorDamage();restored.read(tag);assertEquals(damage.holes(),restored.holes());
                    assertEquals(damage.volume(Polyhedron.CUBE),restored.volume(Polyhedron.CUBE),1e-9);
                }
            }
    }

    @Test void obliquePartsStayOnTheSameBoreAndRemoveTheExactCombinedCrossSection() {
        for(var direction:List.of(new Vec3(.6,.23,1),new Vec3(1,-.4,.7),new Vec3(.2,-1,.5)))
            for(double width:new double[]{2.0/16,10.0/16}) {
                var bore=ArmorHole.along(Vec3.ZERO,direction,width);var d=bore.direction();
                var cuts=ArmorBoreFootprint.cuts(bore);
                double dominant=Math.max(Math.abs(d.x),Math.max(Math.abs(d.y),Math.abs(d.z)));
                assertEquals(width*width/dominant,removedVolume(cuts),1e-8);
                for(var cut:cuts) {
                    var global=cut.hole().center().add(Vec3.atLowerCornerOf(cut.offset()));
                    assertTrue(global.subtract(bore.center()).cross(d).length()<1e-8);
                    assertEquals(bore.u(),cut.hole().u());assertEquals(width,cut.hole().width());
                }
            }
    }

    @Test void aTangentBoreDoesNotDamageTheBlockOnTheOtherSideOfTheSeam() {
        double width=2.0/16;
        var cuts=ArmorBoreFootprint.cuts(ArmorHole.along(new Vec3(width/2,.5,0),new Vec3(0,0,1),width));
        assertEquals(List.of(BlockPos.ZERO),cuts.stream().map(ArmorBoreFootprint.Cut::offset).toList());
    }

    @Test void emptyNeighborGeometryAndRepeatedShotsDoNotInvalidateShapes() {
        var small=Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x()/4,p.y(),p.z())).toList());
        var damage=new ArmorDamage();var shape=damage.shape(small);int stamp=damage.stamp();
        assertFalse(damage.perforate(small,ArmorHole.along(new Vec3(.8,.5,0),new Vec3(0,0,1))));
        assertEquals(stamp,damage.stamp());assertSame(shape,damage.shape(small));assertTrue(damage.holes().isEmpty());
        for(var cut:ArmorBoreFootprint.cuts(ArmorHole.along(Vec3.ZERO,new Vec3(0,0,1)))) {
            var part=new ArmorDamage();assertTrue(part.perforate(Polyhedron.CUBE,cut.hole()));
            var cached=part.shape(Polyhedron.CUBE);int revision=part.stamp();
            assertFalse(part.perforate(Polyhedron.CUBE,cut.hole()));assertEquals(revision,part.stamp());assertSame(cached,part.shape(Polyhedron.CUBE));
        }
    }

    private static double removedVolume(List<ArmorBoreFootprint.Cut> cuts) {
        double result=0;
        for(var cut:cuts)result+=1-ArmorMesh.of(Polyhedron.CUBE).subtract(cut.hole()).volume();
        return result;
    }
}
