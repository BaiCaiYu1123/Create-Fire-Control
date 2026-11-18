package com.hooya.stabilizedturret.content.missile.client.depth;
import net.minecraft.world.phys.Vec3;
public interface SceneDepthProvider {
    boolean isAvailable();
    boolean isOccluded(Vec3 worldPosition);
    SceneDepthProvider VANILLA = new VanillaSceneDepthProvider();
    SceneDepthProvider VOXY = new VoxyDepthProvider();
    static SceneDepthProvider current(){ return VOXY.isAvailable() ? VOXY : VANILLA; }
}
