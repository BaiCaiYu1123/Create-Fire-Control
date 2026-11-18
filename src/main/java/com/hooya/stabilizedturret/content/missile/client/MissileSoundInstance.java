package com.hooya.stabilizedturret.content.missile.client;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import java.util.UUID;
public final class MissileSoundInstance extends AbstractTickableSoundInstance {
 private final UUID id;
 public MissileSoundInstance(UUID id, Vec3 p){super(SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare","ballistic_missile_booster_loop")),SoundSource.NEUTRAL,net.minecraft.util.RandomSource.create());this.id=id;this.looping=true;this.delay=0;this.x=p.x;this.y=p.y;this.z=p.z;}
 public void tick(){var m=ClientMissileManager.get(id);if(m==null){stop();return;}var p=m.position();x=p.x;y=p.y;z=p.z;volume=Mth.clamp((float)(1.0/(1.0+Math.sqrt(p.distanceToSqr(net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()))/32.0)),0,1);}
}
