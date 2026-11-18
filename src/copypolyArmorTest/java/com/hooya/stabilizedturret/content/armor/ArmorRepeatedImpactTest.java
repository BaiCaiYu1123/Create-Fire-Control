package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArmorRepeatedImpactTest {
    @Test void repeatedObliqueHitsProfileCollisionAndSelection() {
        var damage = new ArmorDamage();
        damage.mesh(Polyhedron.CUBE);
        var random = new Random(417);
        for (int i = 0; i < 24; i++) {
            var hole = ArmorHole.along(new Vec3(.1 + .8 * random.nextDouble(), .1 + .8 * random.nextDouble(), .5),
                    new Vec3(random.nextDouble() - .5, random.nextDouble() - .5, 1));
            long start = System.nanoTime();
            assertTrue(damage.add(hole));
            var mesh = damage.mesh(Polyhedron.CUBE);
            long cut = System.nanoTime();
            var shape = damage.shape(Polyhedron.CUBE);
            long collision = System.nanoTime();
            int[] edges = {0};
            shape.forAllEdges((x0,y0,z0,x1,y1,z1) -> edges[0]++);
            long outline = System.nanoTime();
            assertNull(mesh.clip(hole.center().subtract(hole.direction().scale(2)), hole.center().add(hole.direction().scale(2))));
            assertSame(shape, damage.shape(Polyhedron.CUBE));
            System.out.printf(Locale.ROOT, "ARMOR_REPEATED hit=%d faces=%d boxes=%d edges=%d cut=%.3f collision=%.3f outline=%.3f ms%n",
                    i + 1, mesh.faces().size(), shape.toAabbs().size(), edges[0], (cut-start)/1e6, (collision-cut)/1e6, (outline-collision)/1e6);
        }
        var tag = new net.minecraft.nbt.CompoundTag();
        damage.write(tag);
        var reloaded = new ArmorDamage();
        reloaded.read(tag);
        assertEquals(damage.volume(Polyhedron.CUBE), reloaded.volume(Polyhedron.CUBE), 1e-8);
        assertFalse(reloaded.add(damage.holes().getLast()));
        var before = reloaded.shape(Polyhedron.CUBE);
        assertTrue(reloaded.add(ArmorHole.along(new Vec3(.2,.8,.5),new Vec3(1,.3,.2))));
        assertNotSame(before,reloaded.shape(Polyhedron.CUBE));
    }
}
