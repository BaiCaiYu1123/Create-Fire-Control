package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Builds north-up display textures from Xaero World Map's cached terrain. */
public final class XaeroDisplayMapCompat {
    private static boolean attempted;
    private static Method currentSession, processor, minimapInterface, fboRenderer, safeRenderer,
            framebufferTexture, abstractTextureId;
    private static Field scalingFramebuffer, rotationFramebuffer, safeMapTexture;
    private static boolean worldMapAttempted;
    private static Method worldMapCurrentSession, worldMapProcessor,
            worldMapCurrentCaveLayer, worldMapTile, worldMapTileLoaded,
            worldMapBlock, worldMapBlockState, worldMapBlockHeight,
            worldMapRegionExists, worldMapLeafRegion, worldMapSaveLoad,
            worldMapRequestLoad, worldMapSetNextToLoadByViewing,
            worldMapRegionLoaded, worldMapRegionTexture,
            worldMapLeveledRegion, worldMapLeveledTexture,
            worldMapLeveledHasTextures,
            worldMapRegionCacheFile, worldMapRegionFindCacheFile,
            worldMapTextureBuffer, worldMapTextureFormat,
            worldMapTextureCompressed, worldMapTextureId,
            worldMapRequestBranchCache;
    private static final Map<Object,MapTextureState> mapTextures=
            new IdentityHashMap<>();
    private static final Map<Object,ScreenMapTextureState> screenMapTextures=
            new IdentityHashMap<>();
    private static final Map<Object,DirectConsoleViewState> directConsoleViews=
            new IdentityHashMap<>();
    private static ClientLevel mapTextureLevel;
    private static int mapTextureSerial;
    private static final int MAX_REGION_LOAD_REQUESTS_PER_REFRESH=128;
    private static final int MAX_DIRECT_CACHE_REGIONS=512;
    private static final int MAP_BACKGROUND_RGB=0x101820;
    private static final int MAP_BACKGROUND_NATIVE=FastColor.ABGR32.color(
            255,0x20,0x18,0x10);
    private static final int XAERO_RGBA8=32856;
    private static final int XAERO_TEXTURE_BYTES=64*64*4;
    private static final int MAX_CACHE_BUFFER_BYTES=16*1024*1024;
    private static final ExecutorService CACHE_READER=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"create-fire-control-xaero-cache");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Path,DirectCacheEntry> DIRECT_CACHE=
            new LinkedHashMap<>(16,0.75f,true);
    private static final Set<Path> LOGGED_CACHE_FAILURES=
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean worldMapFailureLogged;
    private static boolean worldMapRuntimeFailureLogged;
    private static boolean directConsoleWaitingLogged;
    private static boolean directConsoleTilesLogged;
    private static boolean directCachePathLogged;
    private static boolean directCacheSuccessLogged;

    public static int liveTextureId(Object owner,Vec3 center,double range,
                                    int updateIntervalSeconds,int resolution) {
        // Do not fall back to Xaero Minimap: World Map is the optional terrain
        // provider for this feature, and absence of that mod means no terrain.
        return fallbackTexture(
                owner,center,range,updateIntervalSeconds,resolution);
    }

    /** Registered texture for depth-tested, buffered rendering in the world. */
    public static @Nullable ResourceLocation liveTextureLocation(
            Object owner,Vec3 center,double range,int updateIntervalSeconds,int resolution) {
        if(liveTextureId(owner,center,range,updateIntervalSeconds,resolution)<=0)return null;
        return mapTextures.get(owner).location;
    }

    /**
     * Builds a north-up rectangular texture at the console map's framebuffer
     * resolution. Unlike the wall texture, this is independent of the
     * display's low-resolution map setting and supports an arbitrary pan
     * center without stretching the world-space X/Z scale.
     */
    public static int consoleTextureId(
            Object owner,
            Vec3 center,
            double halfSpanX,
            double halfSpanZ,
            int updateIntervalSeconds,
            int requestedWidth,
            int requestedHeight
    ) {
        Minecraft minecraft=Minecraft.getInstance();ClientLevel level=minecraft.level;
        if(level==null)return -1;
        WorldMapSampler xaero=worldMapSampler();
        if(xaero==null)return -1;
        clearForLevelChange(level);
        int textureWidth=quantizedDimension(requestedWidth);
        int textureHeight=quantizedDimension(requestedHeight);
        long pixels=(long)textureWidth*textureHeight;
        if(pixels>2_097_152L){
            double scale=Math.sqrt(2_097_152.0/pixels);
            textureWidth=quantizedDimension((int)Math.floor(textureWidth*scale));
            textureHeight=quantizedDimension((int)Math.floor(textureHeight*scale));
        }
        ScreenMapTextureState state=screenMapTextures.get(owner);
        if(state==null||state.width!=textureWidth||state.height!=textureHeight){
            if(state!=null)state.texture.close();
            DynamicTexture texture=new DynamicTexture(textureWidth,textureHeight,false);
            minecraft.getTextureManager().register(
                    "create_fire_control_console_map_"+(mapTextureSerial++),texture);
            state=new ScreenMapTextureState(texture,textureWidth,textureHeight);
            screenMapTextures.put(owner,state);
        }
        halfSpanX=Math.max(100.0,halfSpanX);
        halfSpanZ=Math.max(100.0,halfSpanZ);
        long now=level.getGameTime();
        double thresholdX=Math.max(1.0,halfSpanX*2.0/textureWidth);
        double thresholdZ=Math.max(1.0,halfSpanZ*2.0/textureHeight);
        boolean moved=Math.abs(center.x-state.center.x)>thresholdX
                ||Math.abs(center.z-state.center.z)>thresholdZ;
        boolean spanChanged=Math.abs(halfSpanX-state.halfSpanX)>1.0
                ||Math.abs(halfSpanZ-state.halfSpanZ)>1.0;
        long updateTicks=Math.max(20L,Math.min(1200L,updateIntervalSeconds*20L));
        boolean first=state.updatedAt==Long.MIN_VALUE;
        boolean pendingPoll=state.worldMapLoadPending&&!moved
                &&now-state.updatedAt>=20L;
        if(first||now-state.updatedAt>=updateTicks
                ||(moved&&now-state.updatedAt>=5L)||pendingPoll||spanChanged){
            NativeImage image=state.texture.getPixels();
            if(image!=null){
                boolean reset=first;
                if(!first&&(moved||spanChanged)){
                    reprojectConsoleImage(image,state.width,state.height,
                            state.center,state.halfSpanX,state.halfSpanZ,
                            center,halfSpanX,halfSpanZ);
                }
                BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
                for(int py=0;py<state.height;py++)for(int px=0;px<state.width;px++){
                    int worldX=(int)Math.floor(center.x
                            +(px+0.5-state.width/2.0)*halfSpanX*2.0/state.width);
                    int worldZ=(int)Math.floor(center.z
                            +(py+0.5-state.height/2.0)*halfSpanZ*2.0/state.height);
                    int rgb=MAP_BACKGROUND_RGB;
                    boolean resolved=false;
                    Integer xaeroRgb=xaero==null?null:xaero.color(level,cursor,worldX,worldZ);
                    if(xaeroRgb!=null){rgb=xaeroRgb;resolved=true;}
                    if(!resolved&&!reset)continue;
                    int red=(rgb>>16)&255,green=(rgb>>8)&255,blue=rgb&255;
                    image.setPixelRGBA(px,py,
                            FastColor.ABGR32.color(255,blue,green,red));
                }
                state.texture.upload();
                state.worldMapLoadPending=xaero!=null&&xaero.hasPendingLoads();
            }
            state.updatedAt=now;
            state.center=center;
            state.halfSpanX=halfSpanX;
            state.halfSpanZ=halfSpanZ;
        }
        return state.texture.getId();
    }

    /**
     * Returns Xaero World Map's own uploaded 64x64 textures for the console
     * viewport. A suitable Xaero branch level is selected so the UI never
     * performs a per-world-block CPU resample and never asks the GPU to draw
     * substantially more detail than the physical screen can display.
     */
    public static List<ConsoleMapTile> consoleMapTiles(
            Object owner, Vec3 center, double halfSpanX, double halfSpanZ,
            int viewportWidth, int viewportHeight) {
        Minecraft minecraft=Minecraft.getInstance();
        ClientLevel level=minecraft.level;
        if(level==null)return List.of();
        clearForLevelChange(level);
        WorldMapSampler sampler=worldMapSampler();
        if(sampler==null)return List.of();
        double blocksPerPixel=Math.max(
                halfSpanX*2.0/Math.max(1,viewportWidth),
                halfSpanZ*2.0/Math.max(1,viewportHeight));
        int lod=0;
        // Xaero 1.45 stores branch levels 0..3. getLeveledRegion() throws an
        // IllegalArgumentException for any higher value, which previously
        // made wide radar ranges abort the complete map render.
        while(lod<3&&(1L<<(lod+1))<=blocksPerPixel)lod++;
        int tileSize=64<<lod;
        int minTileX=Math.floorDiv((int)Math.floor(center.x-halfSpanX),tileSize);
        int maxTileX=Math.floorDiv((int)Math.floor(center.x+halfSpanX),tileSize);
        int minTileZ=Math.floorDiv((int)Math.floor(center.z-halfSpanZ),tileSize);
        int maxTileZ=Math.floorDiv((int)Math.floor(center.z+halfSpanZ),tileSize);
        long now=level.getGameTime();
        DirectConsoleViewState cached=directConsoleViews.get(owner);
        if(cached!=null&&cached.matches(lod,minTileX,maxTileX,minTileZ,maxTileZ)
                &&now-cached.refreshedAt<10L)return cached.tiles;
        List<ConsoleMapTile> tiles=sampler.directCacheTiles(
                (int)Math.floor(center.x-halfSpanX),
                (int)Math.floor(center.x+halfSpanX),
                (int)Math.floor(center.z-halfSpanZ),
                (int)Math.floor(center.z+halfSpanZ));
        if(tiles.isEmpty()&&!directConsoleWaitingLogged){
            directConsoleWaitingLogged=true;
            StabilizedTurret.LOGGER.info(
                    "Waiting for Xaero console map cache textures");
        }else if(!tiles.isEmpty()&&!directConsoleTilesLogged){
            directConsoleTilesLogged=true;
            StabilizedTurret.LOGGER.info(
                    "Rendering {} direct Xaero texture tiles in the console",tiles.size());
        }
        directConsoleViews.put(owner,new DirectConsoleViewState(lod,minTileX,
                maxTileX,minTileZ,maxTileZ,now,tiles));
        return tiles;
    }

    public static void releaseConsoleTexture(Object owner) {
        ScreenMapTextureState removed=screenMapTextures.remove(owner);
        if(removed!=null)removed.texture.close();
        directConsoleViews.remove(owner);
    }

    private static int quantizedDimension(int requested) {
        int clamped=Math.max(128,Math.min(2048,requested));
        return Math.max(128,(clamped+31)/32*32);
    }

    private static void clearForLevelChange(ClientLevel level) {
        if(level==mapTextureLevel)return;
        mapTextures.values().forEach(state->state.texture.close());
        screenMapTextures.values().forEach(state->state.texture.close());
        mapTextures.clear();
        screenMapTextures.clear();
        directConsoleViews.clear();
        mapTextureLevel=level;
        synchronized(DIRECT_CACHE){
            DIRECT_CACHE.values().forEach(DirectCacheEntry::closeGpuIfReady);
            DIRECT_CACHE.clear();
        }
        directCachePathLogged=false;
        directCacheSuccessLogged=false;
        directConsoleWaitingLogged=false;
        directConsoleTilesLogged=false;
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

    private static int fallbackTexture(Object owner,Vec3 center,double range,
                                       int updateIntervalSeconds,int resolution){
        Minecraft minecraft=Minecraft.getInstance();ClientLevel level=minecraft.level;
        if(level==null)return -1;
        // Xaero World Map is optional. Without it, leave both the console and
        // the physical display's terrain background empty instead of silently
        // rebuilding a substitute map from loaded Minecraft chunks.
        WorldMapSampler xaero=worldMapSampler();
        if(xaero==null)return -1;
        int textureSize=resolution<=128?128:resolution<=256?256:512;
        clearForLevelChange(level);
        MapTextureState state=mapTextures.get(owner);
        if(state==null||state.resolution!=textureSize){
            if(state!=null)state.texture.close();
            DynamicTexture texture=new DynamicTexture(
                    textureSize,textureSize,false);
            ResourceLocation location=minecraft.getTextureManager().register(
                    "create_fire_control_radar_map_"+(mapTextureSerial++),texture);
            state=new MapTextureState(texture,location,textureSize);
            mapTextures.put(owner,state);
        }
        long now=level.getGameTime();
        // Keep the terrain under a moving Sable display aligned with radar
        // contacts and click coordinates. One source pixel is the smallest
        // useful recentering step for this generated texture.
        double movementThreshold=Math.max(1.0,Math.max(200.0,range*2.0)/textureSize);
        boolean moved=center.distanceToSqr(state.center)>movementThreshold*movementThreshold;
        boolean rangeChanged=Math.abs(range-state.range)>1.0;
        long updateTicks=Math.max(20L,Math.min(1200L,updateIntervalSeconds*20L));
        boolean firstUpdate=state.updatedAt==Long.MIN_VALUE;
        boolean pendingPoll=state.worldMapLoadPending&&!moved
                &&now-state.updatedAt>=20L;
        if(firstUpdate||now-state.updatedAt>=updateTicks
                ||(moved&&now-state.updatedAt>=5L)
                ||pendingPoll||rangeChanged){
            NativeImage image=state.texture.getPixels();if(image!=null){
                BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
                double diameter=Math.max(200.0,range*2.0);
                boolean resetImage=firstUpdate;
                // Keep unresolved asynchronous Xaero regions, but first move
                // their old pixels into the new world-space projection. Raw
                // pixel retention mixes two zoom levels in one image and
                // produces displaced tiles and severe stretching while the
                // new regions are loading.
                if(!firstUpdate&&(moved||rangeChanged)){
                    reprojectExistingImage(image,textureSize,state.center,
                            state.range,center,range);
                }
                for(int py=0;py<textureSize;py++)for(int px=0;px<textureSize;px++){
                    int worldX=(int)Math.floor(center.x+(px+0.5-textureSize/2.0)*diameter/textureSize);
                    int worldZ=(int)Math.floor(center.z+(py+0.5-textureSize/2.0)*diameter/textureSize);
                    int rgb=MAP_BACKGROUND_RGB;
                    boolean resolved=false;
                    Integer xaeroRgb=xaero==null?null:xaero.color(level,cursor,worldX,worldZ);
                    if(xaeroRgb!=null){
                        rgb=xaeroRgb;resolved=true;
                    }
                    if(!resolved&&!resetImage)continue;
                    int red=(rgb>>16)&255,green=(rgb>>8)&255,blue=rgb&255;
                    image.setPixelRGBA(px,py,FastColor.ABGR32.color(255,blue,green,red));
                }
                state.texture.upload();
                state.worldMapLoadPending=
                        xaero!=null&&xaero.hasPendingLoads();
            }
            state.updatedAt=now;state.center=center;state.range=range;
        }
        return state.texture.getId();
    }

    private static void reprojectExistingImage(
            NativeImage image,int textureSize,Vec3 oldCenter,double oldRange,
            Vec3 newCenter,double newRange){
        int[] previous=new int[textureSize*textureSize];
        for(int y=0;y<textureSize;y++)for(int x=0;x<textureSize;x++)
            previous[y*textureSize+x]=image.getPixelRGBA(x,y);
        double oldDiameter=Math.max(200.0,oldRange*2.0);
        double newDiameter=Math.max(200.0,newRange*2.0);
        for(int y=0;y<textureSize;y++)for(int x=0;x<textureSize;x++){
            double worldX=newCenter.x
                    +(x+0.5-textureSize/2.0)*newDiameter/textureSize;
            double worldZ=newCenter.z
                    +(y+0.5-textureSize/2.0)*newDiameter/textureSize;
            int oldX=(int)Math.floor((worldX-oldCenter.x)
                    *textureSize/oldDiameter+textureSize/2.0);
            int oldY=(int)Math.floor((worldZ-oldCenter.z)
                    *textureSize/oldDiameter+textureSize/2.0);
            image.setPixelRGBA(x,y,oldX>=0&&oldX<textureSize
                    &&oldY>=0&&oldY<textureSize
                    ?previous[oldY*textureSize+oldX]
                    :MAP_BACKGROUND_NATIVE);
        }
    }

    private static void reprojectConsoleImage(
            NativeImage image,int width,int height,
            Vec3 oldCenter,double oldHalfX,double oldHalfZ,
            Vec3 newCenter,double newHalfX,double newHalfZ){
        int[] previous=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)
            previous[y*width+x]=image.getPixelRGBA(x,y);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            double worldX=newCenter.x
                    +(x+0.5-width/2.0)*newHalfX*2.0/width;
            double worldZ=newCenter.z
                    +(y+0.5-height/2.0)*newHalfZ*2.0/height;
            int oldX=(int)Math.floor((worldX-oldCenter.x)
                    *width/(oldHalfX*2.0)+width/2.0);
            int oldY=(int)Math.floor((worldZ-oldCenter.z)
                    *height/(oldHalfZ*2.0)+height/2.0);
            image.setPixelRGBA(x,y,oldX>=0&&oldX<width&&oldY>=0&&oldY<height
                    ?previous[oldY*width+oldX]:MAP_BACKGROUND_NATIVE);
        }
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
        worldMapLeveledRegion=processor.getMethod(
                "getLeveledRegion",int.class,int.class,int.class,int.class);
        worldMapRegionExists=processor.getMethod(
                "regionDetectionExists",int.class,int.class,int.class);
        worldMapLeafRegion=processor.getMethod(
                "getLeafMapRegion",int.class,int.class,int.class,boolean.class);
        worldMapSaveLoad=processor.getMethod("getMapSaveLoad");
        Class<?> tile=Class.forName("xaero.map.region.MapTile");
        worldMapTileLoaded=tile.getMethod("isLoaded");
        worldMapBlock=tile.getMethod("getBlock",int.class,int.class);
        Class<?> pixel=Class.forName("xaero.map.region.MapPixel");
        worldMapBlockState=pixel.getMethod("getState");
        Class<?> block=Class.forName("xaero.map.region.MapBlock");
        worldMapBlockHeight=block.getMethod("getHeight");
        Class<?> region=Class.forName("xaero.map.region.MapRegion");
        worldMapRegionLoaded=region.getMethod("isLoaded");
        worldMapRegionTexture=region.getMethod(
                "getTexture",int.class,int.class);
        Class<?> leveledRegion=Class.forName(
                "xaero.map.region.LeveledRegion");
        worldMapLeveledTexture=leveledRegion.getMethod(
                "getTexture",int.class,int.class);
        worldMapLeveledHasTextures=leveledRegion.getMethod("hasTextures");
        worldMapRegionCacheFile=leveledRegion.getMethod("getCacheFile");
        worldMapRegionFindCacheFile=region.getMethod(
                "findCacheFile",Class.forName("xaero.map.file.MapSaveLoad"));
        Class<?> texture=Class.forName(
                "xaero.map.region.texture.RegionTexture");
        worldMapTextureBuffer=texture.getMethod("getDirectColorBuffer");
        worldMapTextureFormat=texture.getMethod("getColorBufferFormat");
        worldMapTextureCompressed=texture.getMethod(
                "isColorBufferCompressed");
        worldMapTextureId=texture.getMethod("getGlColorTexture");
        Class<?> saveLoad=Class.forName("xaero.map.file.MapSaveLoad");
        worldMapRequestLoad=saveLoad.getMethod(
                "requestLoad",region,String.class,boolean.class);
        worldMapSetNextToLoadByViewing=saveLoad.getMethod(
                "setNextToLoadByViewing",
                leveledRegion);
        worldMapRequestBranchCache=saveLoad.getMethod(
                "requestBranchCache",
                Class.forName("xaero.map.region.BranchLeveledRegion"),
                String.class,boolean.class);
        StabilizedTurret.LOGGER.info(
                "Xaero World Map display compatibility initialized with direct cache reading");
    }

    private static @Nullable WorldMapSampler worldMapSampler(){
        try{
            if(!worldMapAttempted)initializeWorldMap();
            if(worldMapCurrentSession==null)return null;
            Object session=worldMapCurrentSession.invoke(null);
            Object processor=session==null?null:worldMapProcessor.invoke(session);
            if(processor==null)return null;
            int layer=((Number)worldMapCurrentCaveLayer.invoke(processor)).intValue();
            Object saveLoad=worldMapSaveLoad.invoke(processor);
            return saveLoad==null?null:new WorldMapSampler(processor,saveLoad,layer);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){
            if(!worldMapFailureLogged){
                worldMapFailureLogged=true;
                StabilizedTurret.LOGGER.warn(
                        "Could not initialize Xaero World Map display compatibility",failure);
            }
            return null;
        }
    }

    private static final class WorldMapSampler{
        private final Object processor;
        private final Object saveLoad;
        private final int layer;
        private final Map<Long,Object> tiles=new HashMap<>();
        private final java.util.Set<Long> missing=new java.util.HashSet<>();
        private final Map<Long,Object> regions=new HashMap<>();
        private final java.util.Set<Long> missingRegions=new java.util.HashSet<>();
        private final java.util.Set<Long> requestedRegions=new java.util.HashSet<>();
        private final Map<Long,@Nullable DirectCacheEntry> directCaches=new HashMap<>();
        private final java.util.Set<Long> resolvedDirectCachePaths=new java.util.HashSet<>();
        private int regionLoadRequests;
        private int cacheAtlasUploads;
        private boolean pendingLoads;
        private WorldMapSampler(Object processor,Object saveLoad,int layer){
            this.processor=processor;this.saveLoad=saveLoad;this.layer=layer;
        }
        private @Nullable Integer color(ClientLevel level,BlockPos.MutableBlockPos cursor,
                                        int worldX,int worldZ){
            int chunkX=Math.floorDiv(worldX,16),chunkZ=Math.floorDiv(worldZ,16);
            long key=((long)chunkX<<32)^(chunkZ&0xffffffffL);
            if(missing.contains(key))return null;
            try{
                Object region=region(chunkX,chunkZ);
                if(region==null)return null;
                Integer cachedColor=textureColor(region,worldX,worldZ);
                if(cachedColor!=null)return cachedColor;
                Integer directColor=directCacheColor(
                        region,chunkX,chunkZ,worldX,worldZ);
                if(directColor!=null)return directColor;
                requestRegionLoad(region,chunkX,chunkZ);
                if(!Boolean.TRUE.equals(worldMapRegionLoaded.invoke(region)))return null;
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
            }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){
                if(!worldMapRuntimeFailureLogged){
                    worldMapRuntimeFailureLogged=true;
                    StabilizedTurret.LOGGER.warn(
                            "Failed while sampling Xaero World Map data for a display",failure);
                }
                missing.add(key);return null;
            }
        }

        private @Nullable Object region(int chunkX,int chunkZ)
                throws ReflectiveOperationException {
            int regionX=Math.floorDiv(chunkX,32);
            int regionZ=Math.floorDiv(chunkZ,32);
            long key=((long)regionX<<32)^(regionZ&0xffffffffL);
            if(missingRegions.contains(key))return null;
            Object region=regions.get(key);
            if(region==null){
                // Creating a MapRegion consumes its RegionDetection entry.
                // Always look for the already-created object before consulting
                // detection, otherwise the next display refresh incorrectly
                // treats a perfectly valid cached region as missing.
                region=worldMapLeafRegion.invoke(
                        processor,layer,regionX,regionZ,false);
                if(region==null){
                    boolean exists=Boolean.TRUE.equals(worldMapRegionExists.invoke(
                            processor,layer,regionX,regionZ));
                    if(!exists){missingRegions.add(key);return null;}
                    region=worldMapLeafRegion.invoke(
                            processor,layer,regionX,regionZ,true);
                }
                if(region==null){missingRegions.add(key);return null;}
                regions.put(key,region);
            }
            return region;
        }

        private @Nullable Integer textureColor(Object region,int worldX,int worldZ)
                throws ReflectiveOperationException {
            int textureX=Math.floorMod(Math.floorDiv(worldX,64),8);
            int textureZ=Math.floorMod(Math.floorDiv(worldZ,64),8);
            Object texture=worldMapRegionTexture.invoke(region,textureX,textureZ);
            if(texture==null
                    ||Boolean.TRUE.equals(worldMapTextureCompressed.invoke(texture))
                    ||((Number)worldMapTextureFormat.invoke(texture)).intValue()!=XAERO_RGBA8)
                return null;
            Object rawBuffer=worldMapTextureBuffer.invoke(texture);
            if(!(rawBuffer instanceof ByteBuffer buffer))return null;
            int pixelX=Math.floorMod(worldX,64);
            int pixelZ=Math.floorMod(worldZ,64);
            int offset=(pixelZ*64+pixelX)*4;
            if(offset<0||offset+4>buffer.limit())return null;
            // Xaero writes the native-order packed value as 0xBBGGRRAA.
            int packed=buffer.getInt(offset);
            int red=(packed>>>8)&255;
            int green=(packed>>>16)&255;
            int blue=(packed>>>24)&255;
            // The low byte carries Xaero's optional light value, not normal
            // image alpha, and is commonly zero for fully valid terrain.
            if((red|green|blue)==0)return null;
            return red<<16|green<<8|blue;
        }

        private @Nullable Integer directCacheColor(Object region,int chunkX,
                                                    int chunkZ,int worldX,int worldZ)
                throws ReflectiveOperationException {
            int regionX=Math.floorDiv(chunkX,32);
            int regionZ=Math.floorDiv(chunkZ,32);
            DirectCacheEntry entry=resolveDirectCacheEntry(region,regionX,regionZ);
            if(entry==null)return null;
            XaeroCacheRegion cache=entry.ready();
            if(cache==null){pendingLoads=true;return null;}
            return cache.color(worldX,worldZ);
        }

        private @Nullable DirectCacheEntry resolveDirectCacheEntry(
                Object region,int regionX,int regionZ)
                throws ReflectiveOperationException {
            long regionKey=((long)regionX<<32)^(regionZ&0xffffffffL);
            DirectCacheEntry entry=directCaches.get(regionKey);
            if(resolvedDirectCachePaths.add(regionKey)){
                File cacheFile=(File)worldMapRegionCacheFile.invoke(region);
                if(cacheFile==null||!cacheFile.isFile())
                    cacheFile=(File)worldMapRegionFindCacheFile.invoke(region,saveLoad);
                if(cacheFile!=null&&cacheFile.isFile()){
                    Path path=cacheFile.toPath().toAbsolutePath().normalize();
                    entry=directCacheEntry(path);
                    directCaches.put(regionKey,entry);
                    if(!directCachePathLogged){
                        directCachePathLogged=true;
                        StabilizedTurret.LOGGER.info(
                                "Reading Xaero display terrain directly from {}",path);
                    }
                }
            }
            return entry;
        }

        private void requestRegionLoad(Object region,int chunkX,int chunkZ)
                throws ReflectiveOperationException {
            if(Boolean.TRUE.equals(worldMapRegionLoaded.invoke(region)))return;
            pendingLoads=true;
            int regionX=Math.floorDiv(chunkX,32);
            int regionZ=Math.floorDiv(chunkZ,32);
            long key=((long)regionX<<32)^(regionZ&0xffffffffL);
            if(regionLoadRequests<MAX_REGION_LOAD_REQUESTS_PER_REFRESH
                    &&requestedRegions.add(key)){
                worldMapRequestLoad.invoke(
                        saveLoad,region,"Create Fire Control display",false);
                if(regionLoadRequests==0)
                    worldMapSetNextToLoadByViewing.invoke(saveLoad,region);
                regionLoadRequests++;
            }
        }

        private boolean hasPendingLoads(){return pendingLoads;}

        private List<ConsoleMapTile> directTiles(
                int lod,int minTileX,int maxTileX,int minTileZ,int maxTileZ) {
            java.util.ArrayList<ConsoleMapTile> result=new java.util.ArrayList<>();
            int tileWorldSize=64<<lod;
            java.util.Set<Long> requestedLeveledRegions=new java.util.HashSet<>();
            try{
                for(int tileZ=minTileZ;tileZ<=maxTileZ;tileZ++){
                    for(int tileX=minTileX;tileX<=maxTileX;tileX++){
                        int regionX=Math.floorDiv(tileX,8);
                        int regionZ=Math.floorDiv(tileZ,8);
                        long regionKey=((long)regionX<<32)^(regionZ&0xffffffffL);
                        Object region=worldMapLeveledRegion.invoke(
                                processor,layer,regionX,regionZ,lod);
                        if(region==null){
                            ensureLeveledRegion(regionX,regionZ,lod);
                            region=worldMapLeveledRegion.invoke(
                                    processor,layer,regionX,regionZ,lod);
                        }
                        if(region==null)continue;
                        if(requestedLeveledRegions.add(regionKey)){
                            if(lod==0)requestRegionLoad(region,regionX*32,regionZ*32);
                            else{
                                pendingLoads=true;
                                worldMapRequestBranchCache.invoke(saveLoad,region,
                                        "Create Fire Control console",false);
                                worldMapSetNextToLoadByViewing.invoke(saveLoad,region);
                            }
                        }
                        // A BranchLeveledRegion exists before its asynchronous
                        // cache load allocates the texture array. Calling
                        // getTexture() during that window throws inside Xaero
                        // and used to abort the entire console map pass.
                        if(!Boolean.TRUE.equals(
                                worldMapLeveledHasTextures.invoke(region)))continue;
                        Object texture=worldMapLeveledTexture.invoke(region,
                                Math.floorMod(tileX,8),Math.floorMod(tileZ,8));
                        if(texture==null)continue;
                        int textureId=((Number)worldMapTextureId.invoke(texture)).intValue();
                        if(textureId<=0)continue;
                        result.add(new ConsoleMapTile(textureId,null,
                                tileX*tileWorldSize,tileZ*tileWorldSize,tileWorldSize));
                    }
                }
            }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){
                if(!worldMapRuntimeFailureLogged){
                    worldMapRuntimeFailureLogged=true;
                    StabilizedTurret.LOGGER.warn(
                            "Failed while directly rendering Xaero World Map textures",failure);
                }
            }
            return List.copyOf(result);
        }

        private List<ConsoleMapTile> directCacheTiles(
                int minWorldX,int maxWorldX,int minWorldZ,int maxWorldZ) {
            java.util.ArrayList<ConsoleMapTile> result=new java.util.ArrayList<>();
            int minRegionX=Math.floorDiv(minWorldX,512);
            int maxRegionX=Math.floorDiv(maxWorldX,512);
            int minRegionZ=Math.floorDiv(minWorldZ,512);
            int maxRegionZ=Math.floorDiv(maxWorldZ,512);
            long regionCount=(long)(maxRegionX-minRegionX+1)
                    *(maxRegionZ-minRegionZ+1);
            if(regionCount>4096L)return List.of();
            try{
                for(int regionZ=minRegionZ;regionZ<=maxRegionZ;regionZ++){
                    for(int regionX=minRegionX;regionX<=maxRegionX;regionX++){
                        Object region=region(regionX*32,regionZ*32);
                        if(region==null)continue;
                        DirectCacheEntry entry=resolveDirectCacheEntry(
                                region,regionX,regionZ);
                        if(entry==null)continue;
                        XaeroCacheRegion cache=entry.ready();
                        if(cache==null){pendingLoads=true;continue;}
                        if(!cache.hasGpuTexture()&&cacheAtlasUploads>=2)continue;
                        if(!cache.hasGpuTexture())cacheAtlasUploads++;
                        int textureId=cache.textureId();
                        if(textureId>0)result.add(new ConsoleMapTile(textureId,
                                cache.textureLocation(),
                                regionX*512,regionZ*512,512));
                    }
                }
            }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){
                if(!worldMapRuntimeFailureLogged){
                    worldMapRuntimeFailureLogged=true;
                    StabilizedTurret.LOGGER.warn(
                            "Failed while loading Xaero cache atlases for the console",failure);
                }
            }
            return List.copyOf(result);
        }

        private void ensureLeveledRegion(int regionX,int regionZ,int lod)
                throws ReflectiveOperationException {
            int leafCount=1<<lod;
            int leafStartX=regionX*leafCount;
            int leafStartZ=regionZ*leafCount;
            for(int dz=0;dz<leafCount;dz++)for(int dx=0;dx<leafCount;dx++){
                int leafX=leafStartX+dx,leafZ=leafStartZ+dz;
                if(!Boolean.TRUE.equals(worldMapRegionExists.invoke(
                        processor,layer,leafX,leafZ)))continue;
                worldMapLeafRegion.invoke(processor,layer,leafX,leafZ,true);
                return;
            }
        }
    }

    public record ConsoleMapTile(int textureId,
                                 @Nullable ResourceLocation textureLocation,
                                 int minX,int minZ,int size) {}

    private record DirectConsoleViewState(
            int lod,int minTileX,int maxTileX,int minTileZ,int maxTileZ,
            long refreshedAt,List<ConsoleMapTile> tiles) {
        private boolean matches(int otherLod,int otherMinX,int otherMaxX,
                                int otherMinZ,int otherMaxZ) {
            return lod==otherLod&&minTileX==otherMinX&&maxTileX==otherMaxX
                    &&minTileZ==otherMinZ&&maxTileZ==otherMaxZ;
        }
    }

    private static DirectCacheEntry directCacheEntry(Path path){
        long modified;
        long size;
        try{
            modified=Files.getLastModifiedTime(path).toMillis();
            size=Files.size(path);
        }catch(IOException failure){
            logCacheFailure(path,failure);
            return DirectCacheEntry.failed();
        }
        synchronized(DIRECT_CACHE){
            DirectCacheEntry existing=DIRECT_CACHE.get(path);
            if(existing!=null&&existing.modified==modified&&existing.size==size)
                return existing;
            if(existing!=null)existing.closeGpuIfReady();
            DirectCacheEntry created=new DirectCacheEntry(modified,size,
                    CompletableFuture.supplyAsync(()->readDirectCache(path),CACHE_READER));
            DIRECT_CACHE.put(path,created);
            while(DIRECT_CACHE.size()>MAX_DIRECT_CACHE_REGIONS){
                Path oldest=DIRECT_CACHE.keySet().iterator().next();
                DirectCacheEntry removed=DIRECT_CACHE.remove(oldest);
                if(removed!=null)removed.closeGpuIfReady();
            }
            return created;
        }
    }

    private static XaeroCacheRegion readDirectCache(Path path){
        try(ZipFile zip=new ZipFile(path.toFile())){
            ZipEntry zipEntry=zip.getEntry("cache.xaero");
            if(zipEntry==null){
                var entries=zip.entries();
                while(entries.hasMoreElements()){
                    ZipEntry candidate=entries.nextElement();
                    if(!candidate.isDirectory()){zipEntry=candidate;break;}
                }
            }
            if(zipEntry==null)throw new IOException("Xaero cache ZIP is empty");
            XaeroCacheRegion result;
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(
                    zip.getInputStream(zipEntry)))){
                result=readXaeroCacheData(input);
            }
            if(!directCacheSuccessLogged){
                directCacheSuccessLogged=true;
                StabilizedTurret.LOGGER.info(
                        "Loaded {} saved Xaero terrain textures for connected displays from {}",
                        result.textureCount(),path);
            }
            return result;
        }catch(IOException|RuntimeException failure){
            logCacheFailure(path,failure);
            return XaeroCacheRegion.EMPTY;
        }
    }

    private static XaeroCacheRegion readXaeroCacheData(DataInputStream input)
            throws IOException {
        int combinedVersion=input.readInt();
        int major=combinedVersion&0xffff;
        int minor=combinedVersion>>>16&0xffff;
        if(major<19||major>25)
            throw new IOException("Unsupported Xaero cache version "+major+'.'+minor);

        if(major>=9)input.readInt();
        if(major>=11)input.readInt();
        if(major>=18)input.readInt();
        if(major>=23)input.readInt();
        if(major>=24)input.readInt();

        int coordinate;
        while((coordinate=input.readUnsignedByte())!=255)input.readInt();
        skipBiomePalette(input,major);

        byte[][] textures=new byte[64][];
        while((coordinate=input.readUnsignedByte())!=255){
            int textureX=coordinate>>>4;
            int textureZ=coordinate&15;
            boolean compressed=input.readUnsignedByte()==1;
            int format=input.readInt();
            int dataLength=input.readInt();
            if(dataLength<0||dataLength>MAX_CACHE_BUFFER_BYTES)
                throw new IOException("Invalid Xaero texture byte length "+dataLength);
            byte[] colorData=input.readNBytes(dataLength);
            if(colorData.length!=dataLength)throw new EOFException(
                    "Truncated Xaero texture color data");
            if(!compressed&&format==XAERO_RGBA8
                    &&dataLength>=XAERO_TEXTURE_BYTES
                    &&textureX<8&&textureZ<8)
                textures[textureX*8+textureZ]=colorData;

            if(major>=14)input.readBoolean();
            else if(major>2){
                int lightLength=input.readInt();
                skipChecked(input,lightLength);
            }
            if(major>=13){
                int heightLongs=minor==0?586:1024;
                skipChecked(input,heightLongs*Long.BYTES);
                if(major>=17)skipChecked(input,heightLongs*Long.BYTES);
            }
            skipBiomeIndexStorage(input,major);
            if(major==16)skipChecked(input,64L*Long.BYTES);
        }
        return new XaeroCacheRegion(textures);
    }

    private static void skipBiomePalette(DataInputStream input,int major)
            throws IOException {
        if(major<19)return;
        int count=input.readInt();
        if(count<0||count>65536)throw new IOException(
                "Invalid Xaero biome palette size "+count);
        for(int i=0;i<count;i++){
            int marker=input.readUnsignedByte();
            if(marker==255)continue;
            if(marker==0)input.readUTF();
            else{
                input.readInt();
                if(major<=20)input.readShort();
            }
        }
    }

    private static void skipBiomeIndexStorage(DataInputStream input,int major)
            throws IOException {
        if(major<19)return;
        int paletteSize=input.readInt();
        if(paletteSize<0||paletteSize>4096)throw new IOException(
                "Invalid Xaero texture biome palette size "+paletteSize);
        if(paletteSize==0)return;
        for(int i=0;i<paletteSize;i++){
            int value=input.readInt();
            if(value!=-1)input.readShort();
        }
        if(major==19||input.readUnsignedByte()==1)
            skipChecked(input,1024L*Long.BYTES);
    }

    private static void skipChecked(DataInputStream input,long byteCount)
            throws IOException {
        if(byteCount<0||byteCount>MAX_CACHE_BUFFER_BYTES)
            throw new IOException("Invalid Xaero cache section length "+byteCount);
        input.skipNBytes(byteCount);
    }

    private static void logCacheFailure(Path path,Throwable failure){
        if(LOGGED_CACHE_FAILURES.add(path))
            StabilizedTurret.LOGGER.warn(
                    "Could not read Xaero terrain cache {} for connected displays",
                    path,failure);
    }

    private static final class DirectCacheEntry{
        private final long modified;
        private final long size;
        private final CompletableFuture<XaeroCacheRegion> future;
        private DirectCacheEntry(long modified,long size,
                                 CompletableFuture<XaeroCacheRegion> future){
            this.modified=modified;this.size=size;this.future=future;
        }
        private static DirectCacheEntry failed(){
            return new DirectCacheEntry(-1,-1,
                    CompletableFuture.completedFuture(XaeroCacheRegion.EMPTY));
        }
        private @Nullable XaeroCacheRegion ready(){
            return future.isDone()?future.getNow(XaeroCacheRegion.EMPTY):null;
        }
        private void closeGpuIfReady(){
            XaeroCacheRegion ready=ready();
            if(ready!=null)ready.closeGpu();
        }
    }

    private static final class XaeroCacheRegion{
        private static final XaeroCacheRegion EMPTY=
                new XaeroCacheRegion(new byte[64][]);
        private final byte[][] textures;
        private @Nullable DynamicTexture gpuTexture;
        private @Nullable ResourceLocation gpuLocation;
        private XaeroCacheRegion(byte[][] textures){this.textures=textures;}
        private int textureCount(){
            int count=0;
            for(byte[] texture:textures)if(texture!=null)count++;
            return count;
        }
        private boolean hasGpuTexture(){return gpuTexture!=null;}
        private int textureId(){
            if(textureCount()==0)return -1;
            if(gpuTexture==null){
                Minecraft minecraft=Minecraft.getInstance();
                DynamicTexture created=new DynamicTexture(512,512,false);
                NativeImage image=created.getPixels();
                if(image==null){created.close();return -1;}
                for(int z=0;z<512;z++)for(int x=0;x<512;x++)
                    image.setPixelRGBA(x,z,MAP_BACKGROUND_NATIVE);
                for(int textureX=0;textureX<8;textureX++){
                    for(int textureZ=0;textureZ<8;textureZ++){
                        byte[] data=textures[textureX*8+textureZ];
                        if(data==null)continue;
                        for(int pixelZ=0;pixelZ<64;pixelZ++){
                            for(int pixelX=0;pixelX<64;pixelX++){
                                int offset=(pixelZ*64+pixelX)*4;
                                int red=data[offset+1]&255;
                                int green=data[offset+2]&255;
                                int blue=data[offset+3]&255;
                                image.setPixelRGBA(textureX*64+pixelX,
                                        textureZ*64+pixelZ,
                                        FastColor.ABGR32.color(
                                                255,blue,green,red));
                            }
                        }
                    }
                }
                gpuLocation=minecraft.getTextureManager().register(
                        "create_fire_control_xaero_region_"+(mapTextureSerial++),
                        created);
                created.upload();
                gpuTexture=created;
            }
            return gpuTexture.getId();
        }
        private @Nullable ResourceLocation textureLocation(){return gpuLocation;}
        private void closeGpu(){
            if(gpuTexture!=null){gpuTexture.close();gpuTexture=null;}
            gpuLocation=null;
        }
        private @Nullable Integer color(int worldX,int worldZ){
            int textureX=Math.floorMod(Math.floorDiv(worldX,64),8);
            int textureZ=Math.floorMod(Math.floorDiv(worldZ,64),8);
            byte[] texture=textures[textureX*8+textureZ];
            if(texture==null)return null;
            int pixelX=Math.floorMod(worldX,64);
            int pixelZ=Math.floorMod(worldZ,64);
            int offset=(pixelZ*64+pixelX)*4;
            int red=texture[offset+1]&255;
            int green=texture[offset+2]&255;
            int blue=texture[offset+3]&255;
            if((red|green|blue)==0)return null;
            return red<<16|green<<8|blue;
        }
    }

    private static final class MapTextureState{
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final int resolution;
        private long updatedAt=Long.MIN_VALUE;
        private Vec3 center=Vec3.ZERO;
        private double range;
        private boolean worldMapLoadPending;
        private MapTextureState(DynamicTexture texture,ResourceLocation location,int resolution){
            this.texture=texture;this.location=location;this.resolution=resolution;
        }
    }
    private static final class ScreenMapTextureState{
        private final DynamicTexture texture;
        private final int width;
        private final int height;
        private long updatedAt=Long.MIN_VALUE;
        private Vec3 center=Vec3.ZERO;
        private double halfSpanX=1.0;
        private double halfSpanZ=1.0;
        private boolean worldMapLoadPending;
        private ScreenMapTextureState(DynamicTexture texture,int width,int height){
            this.texture=texture;this.width=width;this.height=height;
        }
    }
    private XaeroDisplayMapCompat(){}
}
