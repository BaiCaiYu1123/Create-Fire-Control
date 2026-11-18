package com.hooya.stabilizedturret.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.util.RandomSource;
import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import java.lang.Math;
import java.util.*;

/** Textured native models in cached section VBOs, with live articulated body matrices. */
public final class StructurePreviewRenderer implements AutoCloseable {
    private record Mesh(StructureHullCache.Section source, List<PreviewModelBuffers.Layer> opaque,
                        List<PreviewModelBuffers.Layer> context, List<PreviewModelBuffers.Layer> movingOpaque,
                        List<PreviewModelBuffers.Layer> movingContext) implements AutoCloseable {
        @Override public void close() { release(opaque);release(context);release(movingOpaque);release(movingContext); }
        int vertices() { return count(opaque)+count(context)+count(movingOpaque)+count(movingContext); }
    }
    private record DrawMesh(Mesh mesh, Matrix4f model, float depth) {}
    private final Map<StructureHullCache.SectionKey, Mesh> meshes = new LinkedHashMap<>();
    private final List<StructureHullCache.SectionKey> movingSections=new ArrayList<>();
    private final PreviewContraptions contraptions=new PreviewContraptions();
    private Set<UUID> opaqueBodies=Set.of();
    private final Map<String,Boolean> transparency=new HashMap<>();
    public record Target(String key,UUID body,UUID entity,boolean defaultTransparent) {}
    public Target bodyTarget(UUID body){return new Target("body:"+body,body,null,!opaqueBodies.contains(body));}
    public boolean transparent(Target target){return transparency.getOrDefault(target.key(),target.defaultTransparent());}
    public int entityCount(){return contraptions.entries().size();}
    public void toggleTransparency(Target target){transparency.put(target.key(),!transparent(target));}
    public void updateEntities(Set<UUID> bodies,Set<UUID> opaque) {
        opaqueBodies=Set.copyOf(opaque);
        Thread previous=PreviewCaptureScope.enter();
        try {contraptions.tick(bodies);} finally {PreviewCaptureScope.exit(previous);}
    }
    private Target entityTarget(PreviewContraptions.Entry entry) {
        return new Target("entity:"+entry.entity().getUUID(),entry.body(),entry.entity().getUUID(),
                transparency.getOrDefault("body:"+entry.body(),!entry.cannon()&&!opaqueBodies.contains(entry.body())));
    }
    private final Set<String> failedModels=new HashSet<>();
    private int refreshTicks,refreshIndex;
    public static boolean capturingModels() {return PreviewCaptureScope.active();}
    private static void release(List<PreviewModelBuffers.Layer> layers) { if(layers!=null)layers.forEach(PreviewModelBuffers.Layer::close); }
    private static int count(List<PreviewModelBuffers.Layer> layers) { return layers==null?0:layers.stream().mapToInt(PreviewModelBuffers.Layer::vertices).sum(); }
    private VertexBuffer outline;
    private static final int[][] EDGES = {{0,1},{0,2},{0,4},{1,3},{1,5},{2,3},{2,6},{3,7},{4,5},{4,6},{5,7},{6,7}};
    public static final int[][] TRIANGLES = {
            {0,1,3},{0,3,2},{4,6,7},{4,7,5},{0,4,5},{0,5,1},
            {2,3,7},{2,7,6},{0,2,6},{0,6,4},{1,5,7},{1,7,3}};

    public void publish(Collection<StructureHullCache.Section> sections) {
        retainSections(sections.stream().map(StructureHullCache.Section::key).toList());
        for(var section:sections)publishSection(section);
    }
    /** Keep the previous generation visible while the screen replaces one section at a time. */
    public void retainSections(Collection<StructureHullCache.SectionKey> keys) {
        Set<StructureHullCache.SectionKey> keep = new HashSet<>(keys);
        var stale=meshes.entrySet().iterator();
        while(stale.hasNext()){var entry=stale.next();if(!keep.contains(entry.getKey())){entry.getValue().close();stale.remove();}}
        movingSections.removeIf(key->!keep.contains(key));
    }
    public void publishSection(StructureHullCache.Section section) {
        Mesh old = meshes.get(section.key());
        if (old != null && old.source == section) return;
        List<PreviewModelBuffers.Layer> opaque=null,context=null,movingOpaque=null,movingContext=null;
        try {
            opaque=upload(section,true,false);
            context=upload(section,false,false);
            movingOpaque=upload(section,true,true);
            movingContext=upload(section,false,true);
            meshes.put(section.key(),new Mesh(section,opaque,context,movingOpaque,movingContext));
        } catch(RuntimeException failure){release(opaque);release(context);release(movingOpaque);release(movingContext);throw failure;}
        if(old!=null)old.close();
        boolean moving=section.blocks().stream().anyMatch(b->b.state().hasBlockEntity());
        if(moving&&!movingSections.contains(section.key()))movingSections.add(section.key());
        else if(!moving)movingSections.remove(section.key());
    }

    private List<PreviewModelBuffers.Layer> upload(StructureHullCache.Section section,boolean opaque,boolean moving) {
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)return null;
        var dispatcher=mc.getBlockRenderer();var view=section.view(mc.level,opaque);
        BlockPos origin=section.key().origin();var random=RandomSource.create();
        Thread previous=PreviewCaptureScope.enter();
        try(PreviewExternalModels external=new PreviewExternalModels();PreviewModelBuffers buffers=new PreviewModelBuffers()) {
            for(var block:section.blocks()) {
                if(block.opaque()!=opaque)continue;
                if(moving!=block.state().hasBlockEntity())continue;
                var be=block.state().hasBlockEntity()?mc.level.getBlockEntity(block.pos()):null;
                if(moving&&be==null)continue;
                var state=moving?be.getBlockState():block.state();
                PoseStack poses=new PoseStack();poses.translate(block.pos().getX()-origin.getX(),block.pos().getY()-origin.getY(),block.pos().getZ()-origin.getZ());
                try {
                    if(moving&&LittleTilesPreviewBridge.render(be,poses,buffers))continue;
                    boolean modeled=be instanceof com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity
                            || be instanceof com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;
                    if(state.getRenderShape()==RenderShape.MODEL&&!modeled) {
                        var model=dispatcher.getBlockModel(state);
                        var data=model.getModelData(view,block.pos(),state,be==null?net.neoforged.neoforge.client.model.data.ModelData.EMPTY:be.getModelData());
                        random.setSeed(state.getSeed(block.pos()));
                        for(var type:model.getRenderTypes(state,random,data)) {
                            poses.pushPose();
                            dispatcher.renderBatched(state,block.pos(),view,poses,buffers.getBuffer(type),true,random,data,type);
                            poses.popPose();
                        }
                    }
                    if(moving) {
                        if(be instanceof com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity item)
                            com.hooya.stabilizedturret.client.compat.YuushyaCannonComponentRenderer.render(item,1,poses,buffers,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);
                        else if(be instanceof com.yuushya.modelling.blockentity.showblock.ShowBlockEntity show)
                            com.hooya.stabilizedturret.client.compat.YuushyaCannonComponentRenderer.render(show,1,poses,buffers,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);
                        else {
                            var renderer=mc.getBlockEntityRenderDispatcher().getRenderer(be);
                            if(renderer!=null)renderer.render(be,1,poses,buffers,LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY);
                        }
                    }
                } catch(RuntimeException failure) {
                    String id=block.state().getBlock().getDescriptionId();
                    if(failedModels.add(id))StabilizedTurret.LOGGER.warn("Cannot capture structure preview model {}",id,failure);
                }
            }
            return buffers.upload();
        } finally { PreviewCaptureScope.exit(previous); }
    }

    /** Update ordinary block-entity partial models without rebuilding static block geometry. */
    public void tick() {
        if(movingSections.isEmpty()||++refreshTicks<10)return;
        var key=movingSections.get(Math.floorMod(refreshIndex++,movingSections.size()));
        Mesh mesh=meshes.get(key);
        var opaque=upload(mesh.source,true,true);
        List<PreviewModelBuffers.Layer> context;
        try { context=upload(mesh.source,false,true); }
        catch(RuntimeException failure){release(opaque);throw failure;}
        release(mesh.movingOpaque);release(mesh.movingContext);
        meshes.put(key,new Mesh(mesh.source,mesh.opaque,mesh.context,opaque,context));
        if(refreshIndex%movingSections.size()==0)refreshTicks=0;
    }

    /** Local block coordinates to a fixed host-local frame, preserving child articulation. */
    public static Matrix4f bodyMatrix(SubLevel body, SubLevel reference, float partialTick) {
        var referencePose = reference instanceof ClientSubLevel c ? c.renderPose(partialTick) : reference.logicalPose();
        BlockPos origin = body == null ? BlockPos.ZERO : body.getPlot().getCenterBlock();
        Vec3[] points = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            Vec3 p = new Vec3(origin.getX() + (i == 1 ? 1 : 0), origin.getY() + (i == 2 ? 1 : 0), origin.getZ() + (i == 3 ? 1 : 0));
            if (body != null) p = (body instanceof ClientSubLevel c ? c.renderPose(partialTick) : body.logicalPose()).transformPosition(p);
            points[i] = referencePose.transformPositionInverse(p);
        }
        BlockPos center = reference.getPlot().getCenterBlock();
        Vec3 p = points[0].subtract(center.getX(), center.getY(), center.getZ());
        Vec3 x = points[1].subtract(points[0]), y = points[2].subtract(points[0]), z = points[3].subtract(points[0]);
        return new Matrix4f().setColumn(0, new Vector4f((float)x.x,(float)x.y,(float)x.z,0))
                .setColumn(1,new Vector4f((float)y.x,(float)y.y,(float)y.z,0))
                .setColumn(2,new Vector4f((float)z.x,(float)z.y,(float)z.z,0))
                .setColumn(3,new Vector4f((float)p.x,(float)p.y,(float)p.z,1));
    }

    public record Box(Matrix4f model, int color) {}
    public void draw(GuiGraphics graphics, int x, int y, int width, int height,
                     Matrix4f camera, Matrix4f projection, Map<UUID, Matrix4f> bodies, List<Box> boxes) {
        graphics.flush();
        Minecraft mc = Minecraft.getInstance(); double scale = mc.getWindow().getGuiScale();
        int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        graphics.enableScissor(x,y,x+width,y+height);
        RenderSystem.viewport((int)(x*scale), mc.getWindow().getHeight()-(int)((y+height)*scale),
                Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)));
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        int srcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int texture=RenderSystem.getShaderTexture(0);
        var shader=RenderSystem.getShader();
        try {
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_LEQUAL); RenderSystem.depthMask(true);
            RenderSystem.enableCull(); RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            List<DrawMesh> drawing = new ArrayList<>();
            for (var entry : meshes.entrySet()) {
                Mesh mesh = entry.getValue(); Matrix4f body = bodies.get(entry.getKey().body());
                if (body == null) continue;
                var key = entry.getKey();
                Matrix4f model = new Matrix4f(camera).mul(body);
                // Matrices use body coordinates relative to its plot center.
                var sub = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level).getSubLevel(key.body());
                if (sub == null || sub.isRemoved()) continue;
                BlockPos origin = sub.getPlot().getCenterBlock();
                model.translate((key.x()<<4)-origin.getX(), (key.y()<<4)-origin.getY(), (key.z()<<4)-origin.getZ());
                drawing.add(new DrawMesh(mesh,model,model.transformPosition(new Vector3f(8,8,8)).z));
            }
            // Equipment and driven turret bodies establish depth first.
            RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1,1,1,1);
            for (DrawMesh draw : drawing) {
                Boolean forced=transparency.get("body:"+draw.mesh.source.key().body());
                if(Boolean.TRUE.equals(forced))continue;
                drawLayers(draw.mesh.opaque,draw.model,projection);drawLayers(draw.mesh.movingOpaque,draw.model,projection);
                if(Boolean.FALSE.equals(forced)){drawLayers(draw.mesh.context,draw.model,projection);drawLayers(draw.mesh.movingContext,draw.model,projection);}
            }
            for(var entry:contraptions.entries())if(!transparent(entityTarget(entry))){
                var model=entry.model(bodies,1);if(model!=null)drawLayers(entry.layers(),new Matrix4f(camera).mul(model),projection);
            }
            // Context contributes color, never opaque depth; internal equipment remains visible.
            RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.depthMask(false);
            RenderSystem.setShaderColor(1,1,1,.45f);
            drawing.sort(Comparator.comparingDouble(DrawMesh::depth));
            for (DrawMesh draw : drawing) {
                Boolean forced=transparency.get("body:"+draw.mesh.source.key().body());
                if(Boolean.FALSE.equals(forced))continue;
                drawLayers(draw.mesh.context,draw.model,projection);drawLayers(draw.mesh.movingContext,draw.model,projection);
                if(Boolean.TRUE.equals(forced)){drawLayers(draw.mesh.opaque,draw.model,projection);drawLayers(draw.mesh.movingOpaque,draw.model,projection);}
            }
            for(var entry:contraptions.entries())if(transparent(entityTarget(entry))){
                var model=entry.model(bodies,1);if(model!=null)drawLayers(entry.layers(),new Matrix4f(camera).mul(model),projection);
            }
            RenderSystem.depthMask(true);
            VertexBuffer.unbind();
            // Every bound-device outline remains visible through the hull.
            RenderSystem.disableDepthTest();RenderSystem.disableCull();RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.setShaderColor(1,1,1,1);
            drawBoxes(boxes,new Matrix4f(projection).mul(camera),width,height,(float)scale);
            VertexBuffer.unbind();
        } finally {
            // The scene depth is private to this preview. Later GUI menus/tooltips always sit above it.
            RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT,Minecraft.ON_OSX);
            RenderSystem.setShaderColor(1,1,1,1); RenderSystem.lineWidth(1);
            RenderSystem.setShaderTexture(0,texture);RenderSystem.setShader(()->shader);
            RenderSystem.depthFunc(depthFunction); RenderSystem.depthMask(true);
            RenderSystem.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            graphics.disableScissor();
        }
    }

    private static void drawLayers(List<PreviewModelBuffers.Layer> layers,Matrix4f model,Matrix4f projection) {
        if(layers==null)return;
        for(var layer:layers) {
            RenderSystem.setShaderTexture(0,layer.texture());
            layer.buffer().bind();layer.buffer().drawWithShader(model,projection,GameRenderer.getPositionTexColorShader());
        }
    }

    /** Screen-space ribbons give reliable thickness even on drivers that clamp GL line width to 1. */
    private void drawBoxes(List<Box> boxes,Matrix4f clip,int width,int height,float scale) {
        if(boxes.isEmpty())return;
        try(ByteBufferBuilder bytes=new ByteBufferBuilder(65536)) {
            BufferBuilder b=new BufferBuilder(bytes,VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
            for(int pass=0;pass<2;pass++)for(Box box:boxes) {
                Matrix4f matrix=new Matrix4f(clip).mul(box.model);
                Vector3f[] corners=new Vector3f[8];
                for(int i=0;i<8;i++)corners[i]=matrix.transformProject(new Vector3f((i&1)==0?0:1,(i&2)==0?0:1,(i&4)==0?0:1));
                for(int[] edge:EDGES) {
                    Vector3f a=corners[edge[0]],c=corners[edge[1]];
                    float dx=(c.x-a.x)*width,dy=(c.y-a.y)*height,length=(float)Math.hypot(dx,dy);
                    if(length<.001)continue;
                    float half=pass==0?1.05f:.65f;
                    float ex=-dy/length*half*2/width,ey=dx/length*half*2/height;
                    int color=pass==0?0x66211C14:0xA6000000|(box.color&0xFFFFFF);
                    b.addVertex(a.x+ex,a.y+ey,0).setColor(color);b.addVertex(a.x-ex,a.y-ey,0).setColor(color);
                    b.addVertex(c.x-ex,c.y-ey,0).setColor(color);b.addVertex(c.x+ex,c.y+ey,0).setColor(color);
                }
            }
            if(outline==null)outline=new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            outline.bind();outline.upload(b.buildOrThrow());outline.drawWithShader(new Matrix4f(),new Matrix4f(),GameRenderer.getPositionColorShader());VertexBuffer.unbind();
        }
    }

    public Target pickTarget(Matrix4f clip,Map<UUID,Matrix4f> bodies,double x,double y) {
        Target hit=null;double nearest=Double.POSITIVE_INFINITY;Minecraft mc=Minecraft.getInstance();
        for(var mesh:meshes.values()) {
            var body=bodies.get(mesh.source.key().body());if(body==null)continue;
            var sub=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level).getSubLevel(mesh.source.key().body());if(sub==null)continue;
            BlockPos origin=sub.getPlot().getCenterBlock(),section=mesh.source.key().origin();
            Matrix4f matrix=new Matrix4f(clip).mul(body).translate(section.getX()-origin.getX(),section.getY()-origin.getY(),section.getZ()-origin.getZ());
            for(var layers:Arrays.asList(mesh.opaque,mesh.context,mesh.movingOpaque,mesh.movingContext)) {
                double depth=pickLayers(layers,matrix,x,y);
                if(depth<nearest){nearest=depth;hit=bodyTarget(mesh.source.key().body());}
            }
        }
        for(var entry:contraptions.entries()) {
            Matrix4f model=entry.model(bodies,1);if(model==null)continue;
            double depth=pickLayers(entry.layers(),new Matrix4f(clip).mul(model),x,y);
            if(depth<nearest){nearest=depth;hit=entityTarget(entry);}
        }
        return hit;
    }
    private static double pickLayers(List<PreviewModelBuffers.Layer> layers,Matrix4f clip,double x,double y) {
        if(layers==null)return Double.POSITIVE_INFINITY;
        Matrix4f inverse=new Matrix4f(clip).invert();
        Vector3f near=inverse.transformProject(new Vector3f((float)x,(float)y,-1));
        Vector3f direction=inverse.transformProject(new Vector3f((float)x,(float)y,1)).sub(near);
        double depth=Double.POSITIVE_INFINITY;
        for(var layer:layers){
            var bounds=layer.bounds();
            if(!Intersectionf.testRayAab(near.x,near.y,near.z,direction.x,direction.y,direction.z,
                    (float)bounds.minX,(float)bounds.minY,(float)bounds.minZ,(float)bounds.maxX,(float)bounds.maxY,(float)bounds.maxZ))continue;
            float[] p=layer.positions();for(int i=0;i+11<p.length;i+=12)for(int triangle=0;triangle<2;triangle++){
            int a=i,b=i+(triangle==0?3:6),c=i+(triangle==0?6:9);
            float t=Intersectionf.intersectRayTriangle(near.x,near.y,near.z,direction.x,direction.y,direction.z,
                    p[a],p[a+1],p[a+2],p[b],p[b+1],p[b+2],p[c],p[c+1],p[c+2],.000001f);
            if(t>=0)depth=Math.min(depth,t);
        }}return depth;
    }

    /** Native modeled blocks and assembled barrels may extend far outside their anchor block. */
    public void expandBounds(Vector3f min,Vector3f max,Map<UUID,Matrix4f> bodies) {
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)return;
        for(var mesh:meshes.values()) {
            var body=bodies.get(mesh.source.key().body());if(body==null)continue;
            var sub=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level).getSubLevel(mesh.source.key().body());if(sub==null)continue;
            BlockPos origin=sub.getPlot().getCenterBlock(),section=mesh.source.key().origin();
            Matrix4f model=new Matrix4f(body).translate(section.getX()-origin.getX(),section.getY()-origin.getY(),section.getZ()-origin.getZ());
            for(var layers:Arrays.asList(mesh.opaque,mesh.context,mesh.movingOpaque,mesh.movingContext))include(min,max,model,layers);
        }
        for(var entry:contraptions.entries()){Matrix4f model=entry.model(bodies,1);if(model!=null)include(min,max,model,entry.layers());}
    }
    private static void include(Vector3f min,Vector3f max,Matrix4f model,List<PreviewModelBuffers.Layer> layers) {
        if(layers==null)return;
        for(var layer:layers){var box=layer.bounds();for(int i=0;i<8;i++){
            var p=model.transformPosition(new Vector3f((float)((i&1)==0?box.minX:box.maxX),(float)((i&2)==0?box.minY:box.maxY),(float)((i&4)==0?box.minZ:box.maxZ)));
            min.min(p);max.max(p);
        }}
    }

    /** Returns frontmost box-surface depth, not its center; handles overlapping projected boxes. */
    public static double pickDepth(Matrix4f clip, double x, double y) {
        Vector3f[] p = new Vector3f[8];
        for (int i=0;i<8;i++) p[i]=clip.transformProject(new Vector3f((i&1)==0?0:1,(i&2)==0?0:1,(i&4)==0?0:1));
        double depth = Double.POSITIVE_INFINITY;
        for (int[] t : TRIANGLES) {
            Vector3f a=p[t[0]], b=p[t[1]], c=p[t[2]];
            double denominator=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);
            if (Math.abs(denominator)<1e-12) continue;
            double u=((b.y-c.y)*(x-c.x)+(c.x-b.x)*(y-c.y))/denominator;
            double v=((c.y-a.y)*(x-c.x)+(a.x-c.x)*(y-c.y))/denominator;
            if (u>=-1e-6 && v>=-1e-6 && u+v<=1.000001)
                depth=Math.min(depth,u*a.z+v*b.z+(1-u-v)*c.z);
        }
        return depth;
    }

    @Override public void close() {
        meshes.values().forEach(Mesh::close); meshes.clear();
        movingSections.clear();
        contraptions.close();
        if (outline != null) { outline.close(); outline=null; }
    }
    public void dispose(){close();transparency.clear();opaqueBodies=Set.of();failedModels.clear();}
}
