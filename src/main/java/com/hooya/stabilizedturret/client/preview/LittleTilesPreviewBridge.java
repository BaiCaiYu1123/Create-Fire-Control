package com.hooya.stabilizedturret.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import java.lang.reflect.*;
import java.util.*;

/** Uses LT's exact RenderBox tessellator (including slopes), independent of its world/Sodium buffers. */
public final class LittleTilesPreviewBridge {
    private static Api api;
    private static boolean checked;
    private record Api(Class<?> tiles,Method allTiles,Method grid,Method addBoxes,Method quads,
                       Field state,Constructor<?> context,Object[] faces) {}
    private static Api api() {
        if(checked)return api;checked=true;
        try {
            Class<?> be=Class.forName("team.creative.littletiles.common.block.entity.BETiles");
            Class<?> grid=Class.forName("team.creative.littletiles.common.grid.LittleGrid");
            Class<?> tile=Class.forName("team.creative.littletiles.common.block.little.tile.LittleTile");
            Class<?> box=Class.forName("team.creative.creativecore.client.render.box.RenderBox");
            Class<?> facing=Class.forName("team.creative.creativecore.common.util.math.base.Facing");
            Class<?> context=Class.forName("team.creative.creativecore.client.render.box.QuadGeneratorContext");
            Method quads=Arrays.stream(box.getMethods()).filter(m->m.getName().equals("getBakedQuad")&&m.getParameterCount()==12).findFirst().orElseThrow();
            api=new Api(be,be.getMethod("allTiles"),be.getMethod("getGrid"),tile.getMethod("addRenderingBoxes",grid,List.class),
                    quads,box.getField("state"),context.getConstructor(),facing.getEnumConstants());
        } catch(ClassNotFoundException ignored) {
        } catch(ReflectiveOperationException error){throw new IllegalStateException("Unsupported LittleTiles preview API",error);}
        return api;
    }
    public static boolean render(BlockEntity be,PoseStack poses,MultiBufferSource buffers) {
        Api a=api();if(a==null||!a.tiles.isInstance(be))return false;
        try {
            Object grid=a.grid.invoke(be),context=a.context.newInstance();
            List<Object> boxes=new ArrayList<>();
            for(Object pair:(Iterable<?>)a.allTiles.invoke(be)) {
                Object tile=((Map.Entry<?,?>)pair).getValue();
                a.addBoxes.invoke(tile,grid,boxes);
            }
            Minecraft mc=Minecraft.getInstance();RandomSource random=RandomSource.create(42);
            for(Object box:boxes) {
                BlockState state=(BlockState)a.state.get(box);var model=mc.getBlockRenderer().getBlockModel(state);
                for(RenderType type:model.getRenderTypes(state,random,ModelData.EMPTY))for(Object face:a.faces) {
                    @SuppressWarnings("unchecked") List<BakedQuad> quads=(List<BakedQuad>)a.quads.invoke(box,context,be.getLevel(),be.getBlockPos(),BlockPos.ZERO,state,model,ModelData.EMPTY,face,type,random,false,-1);
                    var vertices=buffers.getBuffer(type);
                    for(BakedQuad quad:quads) {
                        int color=quad.isTinted()?mc.getBlockColors().getColor(state,be.getLevel(),be.getBlockPos(),quad.getTintIndex()):0xFFFFFF;
                        vertices.putBulkData(poses.last(),quad,new float[]{1,1,1,1},((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,1,
                                new int[]{LightTexture.FULL_BRIGHT,LightTexture.FULL_BRIGHT,LightTexture.FULL_BRIGHT,LightTexture.FULL_BRIGHT},OverlayTexture.NO_OVERLAY,true);
                    }
                }
            }
            return true;
        } catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot capture LittleTiles native geometry",error);}
    }
    static boolean handles(BlockEntity be){Api a=api();return a!=null&&a.tiles.isInstance(be);}
    private LittleTilesPreviewBridge() {}
}
