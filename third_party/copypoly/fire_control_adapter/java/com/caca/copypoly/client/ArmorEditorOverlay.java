// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// Copyright (c) 2026 BaiCaiYu1123 (Fire Control adapter)
// Antialiased primitives adapted from CopyPoly's SmoothOverlay; depth-aware armor editing.
package com.caca.copypoly.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import java.io.IOException;
import java.util.*;

/** Copies the real world depth before GUI rendering clears it, including moving Sable structures. */
final class ArmorEditorOverlay extends RenderType {
    private static ShaderInstance shader;
    private static final RenderType TYPE=create("armor_editor_depth",DefaultVertexFormat.POSITION_TEX_COLOR,VertexFormat.Mode.QUADS,4096,false,true,
            CompositeState.builder().setShaderState(new ShaderStateShard(()->shader)).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(NO_DEPTH_TEST).setCullState(NO_CULL).setWriteMaskState(COLOR_WRITE).createCompositeState(false));
    private static final RenderType DEPTH_TYPE=create("armor_editor_preview_depth",DefaultVertexFormat.POSITION_TEX_COLOR,VertexFormat.Mode.QUADS,4096,false,false,
            CompositeState.builder().setShaderState(new ShaderStateShard(()->shader)).setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL).setWriteMaskState(DEPTH_WRITE).createCompositeState(false));
    private static final double BIAS=.000025;
    private RenderTarget depth;
    private int guiWidth,guiHeight;
    private final Map<Long,Float> samples=new HashMap<>();
    private ArmorEditorOverlay(String name,VertexFormat format,VertexFormat.Mode mode,int size,boolean crumbling,boolean sorting,Runnable setup,Runnable clear){super(name,format,mode,size,crumbling,sorting,setup,clear);}
    ArmorEditorOverlay(){this("unused",DefaultVertexFormat.POSITION_TEX_COLOR,VertexFormat.Mode.QUADS,256,false,false,()->{},()->{});}
    static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),ResourceLocation.fromNamespaceAndPath("copypoly","armor_overlay"),DefaultVertexFormat.POSITION_TEX_COLOR),value->shader=value);
    }
    void capture(int width,int height) {
        var source=target(width,height);
        depth.copyDepthFrom(source);source.bindWrite(false);
    }
    private RenderTarget target(int width,int height) {
        var source=Minecraft.getInstance().getMainRenderTarget();guiWidth=width;guiHeight=height;samples.clear();
        if(depth==null || depth.width!=source.width || depth.height!=source.height || depth.isStencilEnabled()!=source.isStencilEnabled()) {
            close();depth=new TextureTarget(source.width,source.height,true,Minecraft.ON_OSX);
            if(source.isStencilEnabled())depth.enableStencil();
        }
        return source;
    }
    void preview(GuiGraphics graphics,int width,int height,List<Vec3[]> triangles) {
        if(shader==null)return;graphics.flush();var main=target(width,height);
        depth.clear(Minecraft.ON_OSX);writeDepth(graphics,triangles,main);
    }
    void occlude(GuiGraphics graphics,List<Vec3[]> triangles) {
        if(shader==null||depth==null)return;graphics.flush();writeDepth(graphics,triangles,Minecraft.getInstance().getMainRenderTarget());
    }
    private void writeDepth(GuiGraphics graphics,List<Vec3[]> triangles,RenderTarget main) {
        samples.clear();depth.bindWrite(false);
        shader.setSampler("SceneDepth",main.getDepthTextureId());shader.safeGetUniform("DepthPass").set(1f);
        try{for(var triangle:triangles)if(Arrays.stream(triangle).allMatch(Objects::nonNull))face(graphics,triangle[0],triangle[1],triangle[2],0xffffffff,DEPTH_TYPE);graphics.flush();}
        finally{main.bindWrite(false);shader.safeGetUniform("DepthPass").set(0f);}
    }
    void close(){if(depth!=null){depth.destroyBuffers();depth=null;}samples.clear();}
    boolean visible(Vec3 p) {
        if(p==null || depth==null || guiWidth<=0 || guiHeight<=0)return false;
        int x=(int)Math.floor(p.x*depth.width/guiWidth),y=depth.height-1-(int)Math.floor(p.y*depth.height/guiHeight);
        if(x<0 || x>=depth.width || y<0 || y>=depth.height)return false;
        long key=(long)x<<32|y&0xffffffffL;
        float scene=samples.computeIfAbsent(key,ignored->{
            int previous=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            try(var stack=MemoryStack.stackPush()) {
                var value=stack.mallocFloat(1);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,depth.frameBufferId);
                GL11.glReadPixels(x,y,1,1,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,value);return value.get(0);
            }finally{GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,previous);}
        });
        return p.z*.5+.5<=scene+BIAS;
    }
    boolean begin(GuiGraphics graphics,boolean xray) {
        if(depth==null || shader==null)return false;
        graphics.flush();shader.setSampler("SceneDepth",depth.getDepthTextureId());
        shader.safeGetUniform("DepthPass").set(0f);
        shader.safeGetUniform("Xray").set(xray?1f:0f);shader.safeGetUniform("DepthBias").set((float)BIAS);
        shader.safeGetUniform("ViewportSize").set((float)depth.width,(float)depth.height);return true;
    }
    private record Paint(GuiGraphics graphics,Vec3 a,Vec3 b,float anchorX,float anchorY) {
        void vertex(VertexConsumer consumer,double x,double y,int color) {
            double dx=b.x-a.x,dy=b.y-a.y,n=dx*dx+dy*dy;
            double t=n<1e-18?0:Math.clamp(((x-a.x)*dx+(y-a.y)*dy)/n,0,1);
            consumer.addVertex(graphics.pose().last().pose(),(float)x,(float)y,(float)(a.z+t*(b.z-a.z))).setColor(color).setUv(anchorX,anchorY);
        }
        void quad(double ax,double ay,double bx,double by,double cx,double cy,double dx,double dy,int ca,int cb,int cc,int cd) {
            var consumer=graphics.bufferSource().getBuffer(TYPE);
            if((bx-ax)*(cy-ay)-(by-ay)*(cx-ax)>0){vertex(consumer,dx,dy,cd);vertex(consumer,cx,cy,cc);vertex(consumer,bx,by,cb);vertex(consumer,ax,ay,ca);}
            else{vertex(consumer,ax,ay,ca);vertex(consumer,bx,by,cb);vertex(consumer,cx,cy,cc);vertex(consumer,dx,dy,cd);}
        }
    }
    void line(GuiGraphics g,Vec3 a,Vec3 b,int color,boolean selected) {
        line(g,a,b,color,selected,1);
    }
    void line(GuiGraphics g,Vec3 a,Vec3 b,int color,boolean selected,double scale) {
        if(a==null || b==null)return;
        double dx=b.x-a.x,dy=b.y-a.y,length=Math.hypot(dx,dy);if(!Double.isFinite(length) || length<1e-8)return;
        // Bound the overlay to the viewport in floating point before emitting GPU geometry.
        double lo=0,hi=1;double[] p={-dx,dx,-dy,dy},q={a.x+8,guiWidth+8-a.x,a.y+8,guiHeight+8-a.y};
        for(int i=0;i<4;i++){if(Math.abs(p[i])<1e-12){if(q[i]<0)return;}else if(p[i]<0)lo=Math.max(lo,q[i]/p[i]);else hi=Math.min(hi,q[i]/p[i]);}if(lo>hi)return;
        Vec3 start=a.lerp(b,lo),end=a.lerp(b,hi);var paint=new Paint(g,a,b,-1,-1);
        double unit=SmoothOverlay.pixel(),inner=((selected?6:3)*scale-1)*unit/2,outer=inner+unit,nx=-dy/length,ny=dx/length;int clear=color&0xffffff;
        paint.quad(start.x+nx*inner,start.y+ny*inner,end.x+nx*inner,end.y+ny*inner,end.x-nx*inner,end.y-ny*inner,start.x-nx*inner,start.y-ny*inner,color,color,color,color);
        paint.quad(start.x+nx*outer,start.y+ny*outer,end.x+nx*outer,end.y+ny*outer,end.x+nx*inner,end.y+ny*inner,start.x+nx*inner,start.y+ny*inner,clear,clear,color,color);
        paint.quad(start.x-nx*inner,start.y-ny*inner,end.x-nx*inner,end.y-ny*inner,end.x-nx*outer,end.y-ny*outer,start.x-nx*outer,start.y-ny*outer,color,color,clear,clear);
    }
    private void circle(GuiGraphics g,Vec3 p,double radius,int color) {
        var paint=new Paint(g,p,p,(float)(p.x/guiWidth),(float)(1-p.y/guiHeight));int clear=color&0xffffff;
        double inner=Math.max(0,radius-SmoothOverlay.pixel()/2),outer=radius+SmoothOverlay.pixel()/2;
        for(int i=0;i<32;i++) {
            double a=2*Math.PI*i/32,b=2*Math.PI*(i+1)/32,ax=Math.cos(a),ay=Math.sin(a),bx=Math.cos(b),by=Math.sin(b);
            paint.quad(p.x,p.y,p.x+ax*inner,p.y+ay*inner,p.x+bx*inner,p.y+by*inner,p.x,p.y,color,color,color,color);
            paint.quad(p.x+ax*inner,p.y+ay*inner,p.x+ax*outer,p.y+ay*outer,p.x+bx*outer,p.y+by*outer,p.x+bx*inner,p.y+by*inner,color,clear,clear,color);
        }
    }
    void point(GuiGraphics g,Vec3 p,int color,boolean selected,boolean primary,boolean pending) {
        if(p==null || p.x< -24 || p.y< -24 || p.x>guiWidth+24 || p.y>guiHeight+24)return;
        double unit=SmoothOverlay.pixel()*1.5,r=(selected?8:6)*unit;
        circle(g,p,r+2*unit,color);circle(g,p,r+unit,0xff17212a);
        circle(g,p,r,selected?(primary?0xffffffff:0xff86e7ff):pending?0xff263240:color);
    }
    void face(GuiGraphics g,Vec3 a,Vec3 b,Vec3 c,int color) {
        face(g,a,b,c,color,TYPE);
    }
    private void face(GuiGraphics g,Vec3 a,Vec3 b,Vec3 c,int color,RenderType type) {
        if(a==null || b==null || c==null)return;
        var consumer=g.bufferSource().getBuffer(type);Matrix4f pose=g.pose().last().pose();
        Vec3[] points=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)>0?new Vec3[]{c,b,a,a}:new Vec3[]{a,b,c,c};
        for(var p:points)consumer.addVertex(pose,(float)p.x,(float)p.y,(float)p.z).setColor(color).setUv(-1,-1);
    }
}
