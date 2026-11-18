package com.hooya.stabilizedturret.content.missile.client.depth;
import com.hooya.stabilizedturret.client.compat.VoxyLodRaycaster;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.util.HashMap;
import java.util.Map;

/** Uses Voxy's persisted LOD terrain and falls back to loaded vanilla chunks. */
public final class VoxyDepthProvider implements SceneDepthProvider {
 private static final int MAX_QUERIES_PER_TICK = 64;
 private final VanillaSceneDepthProvider vanilla = new VanillaSceneDepthProvider();
 private final Map<Long,Boolean> cache = new HashMap<>();
 private long cacheTick = Long.MIN_VALUE;
 private int queries;
 public boolean isAvailable(){return ModList.get().isLoaded("voxy");}
 public boolean isOccluded(Vec3 p){
  Minecraft minecraft=Minecraft.getInstance();
  if(minecraft.level==null)return false;
  long now=minecraft.level.getGameTime();
  if(now!=cacheTick){cacheTick=now;cache.clear();queries=0;}
  BlockPos cell=BlockPos.containing(p.x/16.0,p.y/16.0,p.z/16.0);
  long key=cell.asLong();
  Boolean known=cache.get(key);if(known!=null)return known;
  Vec3 origin=minecraft.gameRenderer.getMainCamera().getPosition();
  Vec3 ray=p.subtract(origin);double distance=ray.length();
  if(distance<=2.0)return false;
  boolean occluded;
  if(queries++>=MAX_QUERIES_PER_TICK){occluded=vanilla.isOccluded(p);}
  else {Vec3 hit=VoxyLodRaycaster.clip(minecraft.level,origin,ray,distance);
   occluded=(hit!=null&&origin.distanceTo(hit)<distance-1.0)||vanilla.isOccluded(p);}
  cache.put(key,occluded);return occluded;
 }
}
