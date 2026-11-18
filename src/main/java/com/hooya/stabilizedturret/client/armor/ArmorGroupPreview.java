package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.PolyBlockEntity;
import com.caca.copypoly.client.PolyModel;
import com.caca.copypoly.client.PolyScreen;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.content.armor.ArmorGroupMenu;
import com.hooya.stabilizedturret.content.armor.ArmorMesh;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Bakes the draft's outer surface in the same material coordinates as the saved blocks. */
final class ArmorGroupPreview {
    private record Source(BlockPos pos,BlockPos offset,BlockState material,BakedModel model) {}
    private record Surface(Direction side,ArmorMesh.Face face,AABB bounds) {}
    private record Tile(Source source,Map<RenderType,List<BakedQuad>> layers) {}
    private enum Pass { SOLID, CUTOUT, TRANSLUCENT }

    private final ArmorGroupMenu menu;
    private final Set<BlockPos> cells;
    private Polyhedron cachedDraft;
    private List<Source> cachedSources=List.of();
    private List<Tile> tiles=List.of();
    private List<PolyScreen.MaterialPreview> table=List.of();

    ArmorGroupPreview(ArmorGroupMenu menu) {
        this.menu=menu;
        cells=menu.members().stream().map(m->m.pos().subtract(menu.origin())).collect(Collectors.toUnmodifiableSet());
    }

    List<PolyScreen.MaterialPreview> table(Minecraft mc,Polyhedron draft) {
        update(mc,draft);
        return table;
    }

    private void update(Minecraft mc,Polyhedron draft) {
        var level=mc.level;
        if (level==null) return;
        var sources=new ArrayList<Source>(cells.size());
        for (var member : menu.members()) {
            var pos=member.pos();
            var material=level.getBlockEntity(pos) instanceof PolyBlockEntity entity
                ? entity.getMaterial() : AllBlocks.COPYCAT_BASE.getDefaultState();
            sources.add(new Source(pos,pos.subtract(menu.origin()),material,mc.getBlockRenderer().getBlockModel(material)));
        }
        if (draft==cachedDraft && sources.equals(cachedSources)) return;

        var surfaces=draft.faces().stream().map(ArmorGroupPreview::surface).toList();
        var baked=new ArrayList<Tile>(sources.size());
        var preview=new ArrayList<PolyScreen.MaterialPreview>(sources.size());
        for (var source : sources) {
            var layers=PolyArmorModel.bakeMaterial(inCell(surfaces,source.offset),source.material,source.model,
                source.model.getModelData(level,source.pos,source.material,ModelData.EMPTY),source.material.getSeed(source.pos));
            baked.add(new Tile(source,layers));
            var quads=layers.values().stream().flatMap(List::stream).toList();
            preview.add(new PolyScreen.MaterialPreview(source.pos,source.material,Vec3.atLowerCornerOf(source.offset),quads));
        }
        cachedDraft=draft;
        cachedSources=List.copyOf(sources);
        tiles=List.copyOf(baked);
        table=List.copyOf(preview);
    }

    void renderWorld(Minecraft mc,Polyhedron draft,Matrix4f view,Matrix4d localToWorld,Vec3 camera) {
        if (mc.level==null) return;
        update(mc,draft);
        var relative=new Matrix4d(localToWorld).setTranslation(localToWorld.m30()-camera.x,
            localToWorld.m31()-camera.y,localToWorld.m32()-camera.z);
        var pose=new PoseStack();
        pose.mulPose(view);
        pose.mulPose(new Matrix4f(relative));
        var buffers=mc.renderBuffers().bufferSource();
        boolean open=!draft.closed();
        RenderSystem.getModelViewStack().pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        try {
            for (var pass : Pass.values()) {
                var renderType=worldType(pass,open);
                for (var tile : tiles) {
                    var source=tile.source;
                    pose.pushPose();
                    pose.translate(source.offset.getX(),source.offset.getY(),source.offset.getZ());
                    for (var layer : tile.layers.entrySet()) {
                        if (pass(layer.getKey())!=pass || layer.getValue().isEmpty()) continue;
                        var vertices=buffers.getBuffer(renderType);
                        int light=LevelRenderer.getLightColor(mc.level,source.material,source.pos);
                        for (var quad : layer.getValue()) {
                            int tint=quad.isTinted() ? mc.getBlockColors().getColor(source.material,mc.level,source.pos,quad.getTintIndex()) : 0xffffff;
                            vertices.putBulkData(pose.last(),quad,((tint>>16)&255)/255f,((tint>>8)&255)/255f,
                                (tint&255)/255f,1,light,OverlayTexture.NO_OVERLAY);
                        }
                    }
                    pose.popPose();
                }
                buffers.endBatch(renderType);
            }
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static Pass pass(RenderType layer) {
        if (layer==RenderType.translucent() || layer==RenderType.translucentMovingBlock() || layer==RenderType.tripwire())
            return Pass.TRANSLUCENT;
        return layer==RenderType.solid() ? Pass.SOLID : Pass.CUTOUT;
    }

    private static RenderType worldType(Pass pass,boolean open) {
        var atlas=TextureAtlas.LOCATION_BLOCKS;
        return switch (pass) {
            case SOLID -> open ? RenderType.entityCutoutNoCull(atlas) : RenderType.entitySolid(atlas);
            case CUTOUT -> open ? RenderType.entityCutoutNoCull(atlas) : RenderType.entityCutout(atlas);
            case TRANSLUCENT -> open ? RenderType.entityTranslucent(atlas) : RenderType.entityTranslucentCull(atlas);
        };
    }

    private static Surface surface(Polyhedron.Face source) {
        var points=source.vertices().stream().map(p->new Vec3(p.x()/16,p.y()/16,p.z()/16)).toList();
        double x0=Double.POSITIVE_INFINITY,y0=x0,z0=x0,x1=Double.NEGATIVE_INFINITY,y1=x1,z1=x1;
        for (var p : points) {
            x0=Math.min(x0,p.x);y0=Math.min(y0,p.y);z0=Math.min(z0,p.z);
            x1=Math.max(x1,p.x);y1=Math.max(y1,p.y);z1=Math.max(z1,p.z);
        }
        return new Surface(PolyModel.direction(source.plane()),new ArmorMesh.Face(points),new AABB(x0,y0,z0,x1,y1,z1));
    }

    private Map<Direction,List<ArmorMesh.Face>> inCell(List<Surface> surfaces,BlockPos offset) {
        var result=new EnumMap<Direction,List<ArmorMesh.Face>>(Direction.class);
        var origin=Vec3.atLowerCornerOf(offset);
        for (var surface : surfaces) {
            var b=surface.bounds;
            if (b.maxX<origin.x || b.minX>origin.x+1 || b.maxY<origin.y || b.minY>origin.y+1
                    || b.maxZ<origin.z || b.minZ>origin.z+1) continue;
            // A face flush with an internal grid plane belongs to the cell on its solid side.
            var axis=surface.side.getAxis();
            double boundary=origin.get(axis)+(surface.side.getAxisDirection()==Direction.AxisDirection.NEGATIVE?1:0);
            if (b.min(axis)==boundary && b.max(axis)==boundary && cells.contains(offset.relative(surface.side.getOpposite()))) continue;
            List<Vec3> polygon=surface.face.vertices();
            for (var dimension : Direction.Axis.values()) {
                double min=origin.get(dimension);
                polygon=clip(clip(polygon,dimension,min,true),dimension,min+1,false);
                if (polygon.size()<3) break;
            }
            if (polygon.size()>=3) {
                var local=polygon.stream().map(p->p.subtract(origin)).toList();
                result.computeIfAbsent(surface.side,side->new ArrayList<>()).add(new ArmorMesh.Face(local,surface.face.normal()));
            }
        }
        return result;
    }

    private static List<Vec3> clip(List<Vec3> polygon,Direction.Axis axis,double limit,boolean lower) {
        if (polygon.isEmpty()) return polygon;
        var result=new ArrayList<Vec3>(polygon.size()+1);
        Vec3 prev=polygon.getLast();
        double prevDistance=prev.get(axis)-limit;
        for (var point : polygon) {
            double distance=point.get(axis)-limit;
            boolean had=lower?prevDistance>=0:prevDistance<=0, has=lower?distance>=0:distance<=0;
            if (has!=had && prevDistance!=0 && distance!=0) result.add(prev.lerp(point,prevDistance/(prevDistance-distance)));
            if (has) result.add(point);
            prev=point;
            prevDistance=distance;
        }
        return result;
    }
}
