// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// Copyright (c) 2026 BaiCaiYu1123 (Fire Control adapter)
// Bridges the existing MIT camera, projection and overlay controls without copying them into the host.
package com.caca.copypoly.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class ArmorEditorView {
    private final WorldEditorCamera camera=new WorldEditorCamera();
    private final BlockPos pos;
    private EditorWorldProjection view;
    private final ArmorEditorOverlay overlay=new ArmorEditorOverlay();
    public ArmorEditorView(BlockPos pos){this.pos=pos;}
    public void start(){camera.start();focus();}
    public void stop(){camera.stop();overlay.close();}
    public boolean active(){return camera.active();}
    public void tick(boolean enabled){camera.tick(enabled);}
    public void clear(){camera.clear();}
    public void reset(){camera.resetView();focus();}
    private void focus(){
        if(!camera.active())return;var mc=Minecraft.getInstance();
        var p=EditorBlockTransform.capture(mc.level,pos,1).transformPosition(new org.joml.Vector3d(.5,.5,.5));
        mc.getCameraEntity().lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,new Vec3(p.x,p.y,p.z));
        mc.getCameraEntity().setOldPosAndRot();
    }
    public boolean key(int key,int scan,boolean down){return camera.key(key,scan,down);}
    public void pan(double x,double y){camera.pan(x,y);}
    public void look(double x,double y){camera.look(x,y);}
    public void dolly(double amount){camera.dolly(amount);}
    public void movement(Input input){WorldEditorCamera.suppressPlayerInput(input);}
    public void capture(Matrix4f modelView,Matrix4f projection,Vec3 cameraPosition,float partial,int width,int height){
        view=new EditorWorldProjection(modelView,projection,cameraPosition,width,height,EditorBlockTransform.capture(Minecraft.getInstance().level,pos,partial));
        overlay.capture(width,height);
    }
    public Vec3 project(Vec3 point){return view==null?null:view.project(point);}
    public Vec3[] ray(double x,double y){if(view==null)return null;var ray=view.ray(x,y);return new Vec3[]{ray.origin(),ray.direction()};}
    public double axis(double x,double y,Vec3 origin,int axis){return view==null?Double.NaN:view.axisCoordinate(x,y,origin,axis);}
    public boolean visible(Vec3 projected){return overlay.visible(projected);}
    public void occludeMesh(GuiGraphics graphics,java.util.List<Vec3[]> projectedTriangles){overlay.occlude(graphics,projectedTriangles);}
    public boolean beginOverlay(GuiGraphics graphics,boolean xray){return overlay.begin(graphics,xray);}
    public void overlayLine(GuiGraphics graphics,Vec3 a,Vec3 b,int color,boolean selected){overlay.line(graphics,a,b,color,selected);}
    public void overlayPoint(GuiGraphics graphics,Vec3 p,int color,boolean selected,boolean primary,boolean pending){overlay.point(graphics,p,color,selected,primary,pending);}
    public void overlayFace(GuiGraphics graphics,Vec3 a,Vec3 b,Vec3 c,int color){overlay.face(graphics,a,b,c,color);}
    public static void registerShader(net.neoforged.neoforge.client.event.RegisterShadersEvent event) throws java.io.IOException {ArmorEditorOverlay.register(event);}
    public static void line(GuiGraphics graphics,Vec3 a,Vec3 b,int color,boolean selected){SmoothOverlay.line(graphics,a,b,color,(selected?6:3)*SmoothOverlay.pixel());}
    public static void point(GuiGraphics graphics,Vec3 point,int color,boolean selected,boolean primary,boolean pending,int index){
        if(point==null)return;double unit=SmoothOverlay.pixel()*1.5,r=(selected?8:6)*unit;
        SmoothOverlay.circle(graphics,point.x,point.y,r+2*unit,color);SmoothOverlay.circle(graphics,point.x,point.y,r+unit,0xff17212a);
        SmoothOverlay.circle(graphics,point.x,point.y,r,selected?(primary?0xffffffff:0xff86e7ff):pending?0xff263240:color);
        if(selected)graphics.drawString(Minecraft.getInstance().font,Integer.toString(index+1),(int)(point.x+7),(int)(point.y-5),0xffffffff,false);
    }
    public static double pixel(){return SmoothOverlay.pixel();}
    public static void arrow(GuiGraphics graphics,Vec3 start,Vec3 end,int color){if(start!=null&&end!=null)SmoothOverlay.arrow(graphics,start,end,color);}
    public void drawAxes(GuiGraphics graphics, Vec3 origin, int dragAxis) {
        var handles = view == null ? null : view.axisHandles(origin, pixel());
        if (handles != null) handles.draw(graphics, Minecraft.getInstance().font, dragAxis);
    }
    public int pickAxis(double x, double y, Vec3 origin) {
        var handles = view == null ? null : view.axisHandles(origin, pixel());
        return handles == null ? -1 : handles.pick(x, y);
    }
}
