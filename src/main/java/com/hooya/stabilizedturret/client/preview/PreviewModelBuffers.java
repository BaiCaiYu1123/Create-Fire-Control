package com.hooya.stabilizedturret.client.preview;

import com.hooya.stabilizedturret.mixin.client.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Captures native block/BE vertices, retaining UVs, tint and texture without world shader state. */
final class PreviewModelBuffers implements MultiBufferSource, AutoCloseable {
    record Layer(ResourceLocation texture, VertexBuffer buffer, int vertices, float[] positions,net.minecraft.world.phys.AABB bounds) implements AutoCloseable {
        @Override public void close() { buffer.close(); }
    }
    private final Map<ResourceLocation, Sink> sinks = new LinkedHashMap<>();
    @Override public VertexConsumer getBuffer(RenderType type) {
        if (type.mode() != VertexFormat.Mode.QUADS) return DISCARD;
        ResourceLocation texture = TextureAtlas.LOCATION_BLOCKS;
        if (type instanceof PreviewRenderTypeAccessor access) {
            var state = (PreviewRenderStateAccessor)(Object)access.createFireControl$previewState();
            var shard = (PreviewTextureStateAccessor)state.createFireControl$previewTexture();
            texture = shard.createFireControl$previewTextureLocation().orElse(texture);
        }
        return sinks.computeIfAbsent(texture, ignored -> new Sink());
    }
    List<Layer> upload() {
        List<Layer> result = new ArrayList<>();
        try {
            for (var entry : sinks.entrySet()) {
                Sink sink = entry.getValue(); sink.flush();
                MeshData data = sink.builder.build();
                if (data == null) continue;
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                try { buffer.bind(); buffer.upload(data); }
                catch (RuntimeException failure) { buffer.close(); throw failure; }
                finally { VertexBuffer.unbind(); }
                result.add(new Layer(entry.getKey(), buffer, sink.count, sink.positions.toFloatArray(),
                        new net.minecraft.world.phys.AABB(sink.minX,sink.minY,sink.minZ,sink.maxX,sink.maxY,sink.maxZ)));
            }
            return result.isEmpty() ? null : List.copyOf(result);
        } catch (RuntimeException failure) { result.forEach(Layer::close); throw failure; }
    }
    private final class Sink implements VertexConsumer {
        final ByteBufferBuilder bytes = new ByteBufferBuilder(65536);
        final BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        final it.unimi.dsi.fastutil.floats.FloatArrayList positions=new it.unimi.dsi.fastutil.floats.FloatArrayList();
        float x,y,z,u,v,shade=1;
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        int red=255,green=255,blue=255,alpha=255,count;
        boolean pending;
        void flush() {
            if (!pending) return;
            builder.addVertex(x,y,z).setUv(u,v).setColor((int)(red*shade),(int)(green*shade),(int)(blue*shade),alpha);
            positions.add(x);positions.add(y);positions.add(z);
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
            count++;
            pending=false;
        }
        @Override public VertexConsumer addVertex(float x,float y,float z) {
            flush();this.x=x;this.y=y;this.z=z;u=v=0;red=green=blue=alpha=255;shade=1;pending=true;return this;
        }
        @Override public VertexConsumer setColor(int r,int g,int b,int a){red=r;green=g;blue=b;alpha=a;return this;}
        @Override public VertexConsumer setUv(float u,float v){this.u=u;this.v=v;return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){shade=.65f+.25f*Math.max(0,y)+.1f*Math.abs(x);return this;}
    }
    private static final VertexConsumer DISCARD = new VertexConsumer() {
        @Override public VertexConsumer addVertex(float x,float y,float z){return this;}
        @Override public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        @Override public VertexConsumer setUv(float u,float v){return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
    };
    @Override public void close(){sinks.values().forEach(s->s.bytes.close());sinks.clear();}
}
