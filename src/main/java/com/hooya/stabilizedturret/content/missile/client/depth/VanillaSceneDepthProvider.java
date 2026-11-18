package com.hooya.stabilizedturret.content.missile.client.depth;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.block.Blocks;
public final class VanillaSceneDepthProvider implements SceneDepthProvider {
 public boolean isAvailable(){return Minecraft.getInstance().level!=null;}
 public boolean isOccluded(Vec3 p){var m=Minecraft.getInstance(); if(m.level==null)return false; Vec3 c=m.gameRenderer.getMainCamera().getPosition(); return m.level.clip(new ClipContext(c,p,ClipContext.Block.VISUAL,ClipContext.Fluid.NONE,m.player)).getType()==HitResult.Type.BLOCK && c.distanceToSqr(p)>4;}
}
