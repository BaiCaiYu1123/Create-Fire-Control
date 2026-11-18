package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.geometry.Polyhedron;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArmorRayEnvelopeTest {
    private static final ArmorMesh CUBE=ArmorMesh.of(Polyhedron.CUBE);
    @Test void cubeFrontFaceDiagonalAndBodyDiagonalUseGeometricLength(){
        assertEquals(1,ArmorRayEnvelope.mesh(CUBE,new Vec3(.5,.5,-1),new Vec3(0,0,1)).length(),1e-9);
        assertEquals(Math.sqrt(2),ArmorRayEnvelope.mesh(CUBE,new Vec3(-1,-1,.5),new Vec3(1,1,0).normalize()).length(),1e-9);
        assertEquals(Math.sqrt(3),ArmorRayEnvelope.mesh(CUBE,new Vec3(-1,-1,-1),new Vec3(1,1,1).normalize()).length(),1e-9);
    }
    @Test void hitPositionChangesThePathAndMissesHaveZeroThickness(){
        var direction=new Vec3(.6,.8,0);
        assertEquals(.0625,ArmorRayEnvelope.mesh(CUBE,new Vec3(0,.95,.5),direction).length(),1e-9);
        assertTrue(ArmorRayEnvelope.mesh(CUBE,new Vec3(0,.2,.5),direction).length()>.9);
        assertEquals(0,ArmorRayEnvelope.mesh(CUBE,new Vec3(-1,2,.5),new Vec3(1,0,0)).length());
    }
    @Test void uShapedChannelCountsTheGapBetweenFirstAndLastWallButNotAnOpenShot(){
        var u=CUBE.subtract(ArmorHole.along(new Vec3(.5,.75,.5),new Vec3(0,0,1),.5));
        assertEquals(1,ArmorRayEnvelope.mesh(u,new Vec3(-1,.75,.5),new Vec3(1,0,0)).length(),1e-9);
        assertEquals(0,ArmorRayEnvelope.mesh(u,new Vec3(.5,.75,-1),new Vec3(0,0,1)).length());
        assertEquals(.5,ArmorRayEnvelope.mesh(u,new Vec3(.5,-1,.5),new Vec3(0,1,0)).length(),1e-9);
    }
    @Test void voxelSlabsDisconnectedBoxesAndTriangleSurfacesAgree(){
        var boxes=List.of(new AABB(0,0,0,.2,1,1),new AABB(.8,0,0,1,1,1));
        assertEquals(1,ArmorRayEnvelope.boxes(boxes,new Vec3(-1,.5,.5),new Vec3(1,0,0)).length(),1e-9);
        assertEquals(0,ArmorRayEnvelope.boxes(boxes,new Vec3(.5,.5,-1),new Vec3(0,0,1)).length());
        var slab=List.of(new AABB(0,0,0,1,.25,1));
        assertEquals(.25,ArmorRayEnvelope.boxes(slab,new Vec3(.5,-1,.5),new Vec3(0,1,0)).length(),1e-9);
        var cube=List.of(new AABB(0,0,0,1,1,1));var random=new Random(72);
        for(int i=0;i<200;i++){
            var origin=new Vec3(random.nextDouble(),random.nextDouble(),random.nextDouble());
            var direction=new Vec3(random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5).normalize();
            assertEquals(ArmorRayEnvelope.mesh(CUBE,origin,direction).length(),ArmorRayEnvelope.boxes(cube,origin,direction).length(),1e-8);
        }
    }
}
