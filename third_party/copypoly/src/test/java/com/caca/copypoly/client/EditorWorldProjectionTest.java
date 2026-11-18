// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorWorldProjectionTest {
    private EditorWorldProjection frame(Matrix4d block) {
        return new EditorWorldProjection(new Matrix4f().lookAt(0,0,0,-3,-3,-6,0,1,0),
                new Matrix4f().perspective(1f,1.6f,.05f,100),new Vec3(103,103,106),1280,800,block);
    }
    @Test void rotatedScaledAxesMapBackToLocalPixelSteps() {
        for(double angle:new double[]{0,.4,1.1,2.3})for(double scale:new double[]{.5,1,2}) {
            var block=new Matrix4d().translation(100.5,100.5,100.5).rotate(new Quaterniond().rotateY(angle).rotateX(.2))
                    .scale(scale).translate(-.5,-.5,-.5);
            var projection=frame(block);var origin=new Vec3(.35,.4,.45);
            for(int axis=0;axis<3;axis++)for(double pixels:new double[]{-2,.1,1,3}) {
                var target=origin.add(PolyGizmo.axis(axis).scale(pixels/16));
                var screen=projection.project(target);assertNotNull(screen);
                assertEquals(pixels,projection.axisCoordinate(screen.x,screen.y,origin,axis),.001,
                        "Rotated structure drag no longer follows its local pixel lattice");
            }
        }
    }
    @Test void axisHandlesKeepTheirScreenSizeAtDifferentDistancesAndFovs() {
        Vec3 origin = new Vec3(.5, .5, .5);
        Vec3 offset = new Vec3(3, 2, 6);
        var block = new Matrix4d().translation(100, 100, 100).rotateY(.24);
        var world = block.transformPosition(new org.joml.Vector3d(origin.x, origin.y, origin.z));
        Vec3 pivot = new Vec3(world.x, world.y, world.z);
        var view = new Matrix4f().lookAt(0, 0, 0, -3, -2, -6, 0, 1, 0);
        var near = new EditorWorldProjection(view, new Matrix4f().perspective(1f, 16/9f, .05f, 100),
                pivot.add(offset.scale(.02)), 640, 360, block);
        var far = new EditorWorldProjection(view, new Matrix4f().perspective(.65f, 16/9f, .05f, 100),
                pivot.add(offset.scale(5)), 640, 360, block);
        var nearHandles = near.axisHandles(origin, .25);
        var farHandles = far.axisHandles(origin, .25);
        assertNotNull(nearHandles);
        assertNotNull(farHandles);
        Vec3 nearPivot = near.project(origin), farPivot = far.project(origin);
        double longest = 0;
        for (int axis = 0; axis < 3; axis++) {
            Vec3 nearTip = nearHandles.tip(axis), farTip = farHandles.tip(axis);
            assertNotNull(nearTip);
            assertNotNull(farTip);
            Vec3 nearDelta = nearTip.subtract(nearPivot), farDelta = farTip.subtract(farPivot);
            assertEquals(nearDelta.x, farDelta.x, .0001);
            assertEquals(nearDelta.y, farDelta.y, .0001);
            assertEquals(axis, nearHandles.pick(nearTip.x, nearTip.y));
            assertEquals(axis, farHandles.pick(farTip.x, farTip.y));
            longest = Math.max(longest, nearDelta.length());
        }
        assertEquals(36, longest, .0001);
    }
    @Test void distantPlotPivotCancelsBeforeFloatProjection() {
        var pivot=new Vec3(28000000.5,100.5,-28000000.5);
        var rotation=new Quaterniond().rotateY(.55).rotateZ(.15);
        var plotPose=new Matrix4d().translation(100.5,100.5,100.5).rotate(rotation).translate(-pivot.x,-pivot.y,-pivot.z)
                .translate(pivot.x-.5,pivot.y-.5,pivot.z-.5);
        var direct=new Matrix4d().translation(100.5,100.5,100.5).rotate(rotation).translate(-.5,-.5,-.5);
        var a=frame(plotPose);var b=frame(direct);
        for(var p:com.caca.copypoly.geometry.Polyhedron.CUBE_POINTS) {
            var local=PolyGizmo.position(p);
            assertTrue(a.project(local).distanceTo(b.project(local))<.0001,"Distant plot lost subpixel alignment");
        }
        assertTrue(a.project(new Vec3(.1/16,0,0)).distanceTo(a.project(Vec3.ZERO))>.01,"Fine editing collapsed after plot conversion");
    }
}
