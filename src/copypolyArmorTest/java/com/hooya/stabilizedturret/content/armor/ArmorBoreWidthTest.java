package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmorBoreWidthTest {
    private static final Vec3 CENTER=new Vec3(.5,.5,.5),DIRECTION=new Vec3(0,0,1);
    @Test void differentCalibersHaveExactIndependentEditableOpenings() {
        for(double width:new double[]{.75/16,1.0/16,2.0/16,4.0/16,6.0/16,10.0/16}) {
            var damage=new ArmorDamage();damage.add(ArmorHole.along(CENTER,DIRECTION,width));
            assertEquals(1-width*width,damage.volume(Polyhedron.CUBE),1e-9);
            assertNull(damage.mesh(Polyhedron.CUBE).clip(CENTER.subtract(DIRECTION),CENTER.add(DIRECTION)));
            var edit=damage.editable(Polyhedron.CUBE);edit.validate();assertEquals(16,edit.vertices().size());
            assertEquals(damage.volume(Polyhedron.CUBE),edit.mesh().volume(),1e-9);
            var shape=damage.shape(Polyhedron.CUBE);
            assertTrue(shape.toAabbs().stream().noneMatch(b->b.contains(CENTER)));
            assertEquals(24,ArmorMeshOutline.build(damage.mesh(Polyhedron.CUBE)).size());
        }
        assertEquals(2.0/16,ArmorHole.along(CENTER,DIRECTION).width());
    }
    @Test void legacyNineValueBoresRemainThreePixelsAndNewWidthsSurviveAppendSync() {
        var source=new ArmorDamage();source.add(ArmorHole.along(CENTER,DIRECTION,3.0/16));
        var tag=new CompoundTag();source.write(tag);tag.getList(ArmorDamage.TAG,Tag.TAG_LIST).getList(0).remove(9);
        var restored=new ArmorDamage();restored.read(tag);
        assertEquals(3.0/16,restored.holes().getFirst().width());
        assertEquals(1-9.0/256,restored.volume(Polyhedron.CUBE),1e-9);
        restored.add(ArmorHole.along(new Vec3(.15,.15,.5),DIRECTION,1.0/16));restored.write(tag);
        var client=new ArmorDamage();client.read(tag);client.mesh(Polyhedron.CUBE);
        restored.add(ArmorHole.along(new Vec3(.8,.8,.5),DIRECTION,2.0/16));restored.write(tag);client.read(tag);
        assertEquals(restored.holes(),client.holes());assertEquals(restored.volume(Polyhedron.CUBE),client.volume(Polyhedron.CUBE),1e-9);
    }
    @Test void largerHitsWidenAnExistingHoleAndSmallerCoveredHitsDoNotGrowGeometry() {
        var damage=new ArmorDamage();assertTrue(damage.add(ArmorHole.along(CENTER,DIRECTION,2.0/16)));
        assertTrue(damage.add(ArmorHole.along(CENTER,DIRECTION,6.0/16)));
        assertEquals(1-36.0/256,damage.volume(Polyhedron.CUBE),1e-9);
        var before=damage.mesh(Polyhedron.CUBE);
        assertFalse(damage.add(ArmorHole.along(CENTER,DIRECTION,2.0/16)));
        assertSame(before,damage.mesh(Polyhedron.CUBE));assertEquals(2,damage.holes().size());
    }
    @Test void invalidWidthsCannotPoisonSavedGeometry() {
        for(double width:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,5})
            assertThrows(IllegalArgumentException.class,()->ArmorHole.along(CENTER,DIRECTION,width));
    }
}
