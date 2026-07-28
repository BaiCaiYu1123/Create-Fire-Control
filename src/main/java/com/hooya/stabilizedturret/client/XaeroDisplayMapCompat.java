package com.hooya.stabilizedturret.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Reflection-only access to Xaero's live north-up minimap framebuffer. */
public final class XaeroDisplayMapCompat {
    private static boolean attempted;
    private static Method currentSession, processor, minimapInterface, fboRenderer, safeRenderer,
            framebufferTexture, abstractTextureId;
    private static Field scalingFramebuffer, rotationFramebuffer, safeMapTexture;
    private static boolean worldMapAttempted;
    private static Method worldMapCurrentSession, worldMapProcessor,
            worldMapCurrentCaveLayer, worldMapTile, worldMapTileLoaded,
            worldMapBlock, worldMapBlockState, worldMapBlockHeight;
    private static DynamicTexture fallbackTexture;
    private static long fallbackUpdatedAt=Long.MIN_VALUE;
    private static Vec3 fallbackCenter=Vec3.ZERO;
    private static double fallbackRange;
    private static final int FALLBACK_SIZE=128;

    public static int liveTextureId(Vec3 center,double range) {
        // The minimap FBO represents Xaero's own current zoom and therefore
        // cannot follow an independently adjustable 100 m—10 km radar scale.
        // Build a north-up range texture from the same live client world
        // first; Xaero's framebuffer remains a fallback if no level exists.
        int scaled = fallbackTexture(center, range);
        if (scaled > 0) return scaled;
        try {
            if (!attempted) initialize();
            if (currentSession != null) {
                Object session=currentSession.invoke(null);
                Object proc=session==null?null:processor.invoke(session);
                Object minimap=proc==null?null:minimapInterface.invoke(proc);
                Object renderer=minimap==null?null:fboRenderer.invoke(minimap);
                Object framebuffer=renderer==null?null:scalingFramebuffer.get(renderer);
                if(framebuffer==null&&renderer!=null)framebuffer=rotationFramebuffer.get(renderer);
                if(framebuffer!=null){Object id=framebufferTexture.invoke(framebuffer);if(id instanceof Number n&&n.intValue()>0)return n.intValue();}
                Object safe=minimap==null?null:safeRenderer.invoke(minimap);
                Object mapTexture=safe==null?null:safeMapTexture.get(safe);
                if(mapTexture!=null){Object id=abstractTextureId.invoke(mapTexture);if(id instanceof Number n&&n.intValue()>0)return n.intValue();}
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
        return fallbackTexture(center,range);
    }
    private static synchronized void initialize() throws ReflectiveOperationException {
        if(attempted)return; attempted=true;
        Class<?> session=Class.forName("xaero.common.XaeroMinimapSession");
        currentSession=session.getMethod("getCurrentSession"); processor=session.getMethod("getMinimapProcessor");
        Class<?> processorType=Class.forName("xaero.common.minimap.MinimapProcessor");
        minimapInterface=processorType.getMethod("getMinimapInterface");
        Class<?> interfaceType=Class.forName("xaero.common.minimap.MinimapInterface");
        fboRenderer=interfaceType.getMethod("getMinimapFBORenderer");
        safeRenderer=interfaceType.getMethod("getMinimapSafeModeRenderer");
        Class<?> rendererType=Class.forName("xaero.common.minimap.render.MinimapFBORenderer");
        scalingFramebuffer=rendererType.getDeclaredField("scalingFramebuffer"); scalingFramebuffer.setAccessible(true);
        rotationFramebuffer=rendererType.getDeclaredField("rotationFramebuffer"); rotationFramebuffer.setAccessible(true);
        Class<?> framebufferType=Class.forName("xaero.common.graphics.ImprovedFramebuffer");
        framebufferTexture=framebufferType.getMethod("getFramebufferTexture");
        Class<?> safeType=Class.forName("xaero.common.minimap.render.MinimapSafeModeRenderer");
        safeMapTexture=safeType.getDeclaredField("mapTexture");safeMapTexture.setAccessible(true);
        abstractTextureId=Class.forName("net.minecraft.client.renderer.texture.AbstractTexture").getMethod("getId");
    }

    private static int fallbackTexture(Vec3 center,double range){
        Minecraft minecraft=Minecraft.getInstance();ClientLevel level=minecraft.level;
        if(level==null)return -1;
        if(fallbackTexture==null){fallbackTexture=new DynamicTexture(FALLBACK_SIZE,FALLBACK_SIZE,false);
            minecraft.getTextureManager().register("create_fire_control_radar_map",fallbackTexture);}
        long now=level.getGameTime();
        double movementThreshold=Math.max(64.0,range/FALLBACK_SIZE*4.0);
        boolean moved=center.distanceToSqr(fallbackCenter)>movementThreshold*movementThreshold;
        boolean rangeChanged=Math.abs(range-fallbackRange)>1.0;
        if(fallbackUpdatedAt==Long.MIN_VALUE||now-fallbackUpdatedAt>=20L
                ||(moved&&now-fallbackUpdatedAt>=5L)||rangeChanged){
            NativeImage image=fallbackTexture.getPixels();if(image!=null){
                BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
                WorldMapSampler xaero=worldMapSampler();
                double diameter=Math.max(200.0,range*2.0);
                for(int py=0;py<FALLBACK_SIZE;py++)for(int px=0;px<FALLBACK_SIZE;px++){
                    int worldX=(int)Math.floor(center.x+(px+0.5-FALLBACK_SIZE/2.0)*diameter/FALLBACK_SIZE);
                    int worldZ=(int)Math.floor(center.z+(py+0.5-FALLBACK_SIZE/2.0)*diameter/FALLBACK_SIZE);
                    int rgb=0x101820;
                    Integer xaeroRgb=xaero==null?null:xaero.color(level,cursor,worldX,worldZ);
                    if(xaeroRgb!=null){
                        rgb=xaeroRgb;
                    }else if(level.hasChunk(worldX>>4,worldZ>>4)){
                        int worldY=level.getHeight(Heightmap.Types.WORLD_SURFACE,worldX,worldZ)-1;
                        cursor.set(worldX,worldY,worldZ);
                        MapColor map=level.getBlockState(cursor).getMapColor(level,cursor);
                        if(map!=MapColor.NONE)rgb=map.col;
                    }
                    int red=(rgb>>16)&255,green=(rgb>>8)&255,blue=rgb&255;
                    image.setPixelRGBA(px,py,FastColor.ABGR32.color(255,blue,green,red));
                }
                fallbackTexture.upload();
            }
            fallbackUpdatedAt=now;fallbackCenter=center;fallbackRange=range;
        }
        return fallbackTexture.getId();
    }

    private static synchronized void initializeWorldMap()
            throws ReflectiveOperationException {
        if(worldMapAttempted)return;
        worldMapAttempted=true;
        Class<?> session=Class.forName("xaero.map.WorldMapSession");
        worldMapCurrentSession=session.getMethod("getCurrentSession");
        worldMapProcessor=session.getMethod("getMapProcessor");
        Class<?> processor=Class.forName("xaero.map.MapProcessor");
        worldMapCurrentCaveLayer=processor.getMethod("getCurrentCaveLayer");
        worldMapTile=processor.getMethod("getMapTile",int.class,int.class,int.class);
        Class<?> tile=Class.forName("xaero.map.region.MapTile");
        worldMapTileLoaded=tile.getMethod("isLoaded");
        worldMapBlock=tile.getMethod("getBlock",int.class,int.class);
        Class<?> pixel=Class.forName("xaero.map.region.MapPixel");
        worldMapBlockState=pixel.getMethod("getState");
        Class<?> block=Class.forName("xaero.map.region.MapBlock");
        worldMapBlockHeight=block.getMethod("getHeight");
    }

    private static @Nullable WorldMapSampler worldMapSampler(){
        try{
            if(!worldMapAttempted)initializeWorldMap();
            if(worldMapCurrentSession==null)return null;
            Object session=worldMapCurrentSession.invoke(null);
            Object processor=session==null?null:worldMapProcessor.invoke(session);
            if(processor==null)return null;
            int layer=((Number)worldMapCurrentCaveLayer.invoke(processor)).intValue();
            return new WorldMapSampler(processor,layer);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){
            return null;
        }
    }

    private static final class WorldMapSampler{
        private final Object processor;
        private final int layer;
        private final Map<Long,Object> tiles=new HashMap<>();
        private final java.util.Set<Long> missing=new java.util.HashSet<>();
        private WorldMapSampler(Object processor,int layer){this.processor=processor;this.layer=layer;}
        private @Nullable Integer color(ClientLevel level,BlockPos.MutableBlockPos cursor,
                                        int worldX,int worldZ){
            int chunkX=Math.floorDiv(worldX,16),chunkZ=Math.floorDiv(worldZ,16);
            long key=((long)chunkX<<32)^(chunkZ&0xffffffffL);
            if(missing.contains(key))return null;
            try{
                Object tile=tiles.get(key);
                if(tile==null){
                    tile=worldMapTile.invoke(processor,layer,chunkX,chunkZ);
                    if(tile==null||!Boolean.TRUE.equals(worldMapTileLoaded.invoke(tile))){
                        missing.add(key);return null;
                    }
                    tiles.put(key,tile);
                }
                Object block=worldMapBlock.invoke(tile,Math.floorMod(worldX,16),Math.floorMod(worldZ,16));
                if(block==null)return null;
                Object rawState=worldMapBlockState.invoke(block);
                if(!(rawState instanceof BlockState state)||state.isAir())return null;
                int y=((Number)worldMapBlockHeight.invoke(block)).intValue();
                cursor.set(worldX,y,worldZ);
                MapColor map=state.getMapColor(level,cursor);
                return map==MapColor.NONE?null:map.col;
            }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){
                missing.add(key);return null;
            }
        }
    }
    private XaeroDisplayMapCompat(){}
}
