package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.client.Screenshot;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import rbasamoyai.createbigcannons.effects.particles.plumes.BigCannonPlumeParticleData;
import rbasamoyai.createbigcannons.effects.particles.explosions.CannonBlastWaveEffectParticleData;
import rbasamoyai.createbigcannons.effects.particles.smoke.CannonSmokeParticleData;
import rbasamoyai.createbigcannons.index.CBCSoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundSource;
import java.nio.file.*;
import java.util.*;

/** Real CBC emitter factories + actual installed optimizer + live client sound engine. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class CannonFxClientChecks {
    private static int age,phase=-1,phaseAge;
    private static boolean launched,done;
    private static java.lang.reflect.Method allow;
    private static final double[] distances={2,16,40,64,96,192,16,96};
    private static final StringBuilder trace=new StringBuilder("phase,distance,front,plume_allowed,blast_allowed,smoke_allowed,plume_created,blast_created,cannon_sound_channels,rendered_cbc_particles\n");
    private static boolean plumeAllowed,blastAllowed,smokeAllowed;
    private static int plumeCreated,blastCreated;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(done)return;var mc=Minecraft.getInstance();
        try {
            if(++age>6000)throw new AssertionError("cannon FX client timed out");
            if(!launched&&age>60&&mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){mc.setScreen(new TitleScreen());return;}
            if(!launched&&age>60&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
                launched=true;mc.createWorldOpenFlows().createFreshLevel("CannonFX-"+System.currentTimeMillis(),
                        new LevelSettings("CBC firing FX",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);return;
            }
            if(mc.level==null||mc.player==null)return;
            mc.setScreen(null);mc.options.setCameraType(CameraType.FIRST_PERSON);
            if(phase==-1){
                allow=Class.forName("dev.noxora.createfpsoptimizer.OptimizationController").getMethod("allowParticle",ParticleOptions.class,double.class,double.class,double.class);
                // Keep the 192-block visual sample inside the client's fog distance.
                mc.options.renderDistance().set(24);
                mc.player.getAbilities().flying=true;mc.player.getAbilities().mayfly=true;mc.player.setNoGravity(true);
                mc.player.setPos(0,220,0);mc.player.setYRot(0);mc.player.setXRot(0);
                phase=0;phaseAge=0;return;
            }
            if(++phaseAge<15)return;
            Vec3 eye=mc.gameRenderer.getMainCamera().getPosition();boolean front=phase<6;
            Vec3 view=new Vec3(mc.gameRenderer.getMainCamera().getLookVector()).normalize();
            double d=distances[phase];Vec3 origin=eye.add(view.scale(front?d:-d));
            var plume=new BigCannonPlumeParticleData(3,12,10);
            var blast=new CannonBlastWaveEffectParticleData(128,BuiltInRegistries.SOUND_EVENT.wrapAsHolder(CBCSoundEvents.FIRE_BIG_CANNON.getMainEvent()),SoundSource.BLOCKS,24,1,2,0);
            var smoke=new CannonSmokeParticleData(3,12,45,.95F);
            if(phaseAge==15){
                plumeAllowed=(boolean)allow.invoke(null,plume,origin.x,origin.y,origin.z);
                blastAllowed=(boolean)allow.invoke(null,blast,origin.x,origin.y,origin.z);
                smokeAllowed=(boolean)allow.invoke(null,smoke,origin.x,origin.y,origin.z);
                var clear=mc.particleEngine.getClass().getDeclaredMethod("clearParticles");clear.setAccessible(true);clear.invoke(mc.particleEngine);
                // Follow the same packet -> native particle factory path as CBC's nethersteel big cannon.
                mc.getConnection().handleParticleEvent(new ClientboundLevelParticlesPacket(plume,true,origin.x,origin.y,origin.z,(float)view.x,(float)view.y,(float)view.z,1,0));
                mc.getConnection().handleParticleEvent(new ClientboundLevelParticlesPacket(blast,true,origin.x,origin.y,origin.z,0,0,0,1,0));
                plumeCreated=countParticles(mc,"BigCannonPlumeParticle");blastCreated=countParticles(mc,"BlastWaveEffectParticle");
            }
            if(phaseAge==35){
                int sounds=soundChannels(mc);
                int visuals=countVisualParticles(mc);
                trace.append(phase).append(',').append(d).append(',').append(front).append(',').append(plumeAllowed).append(',').append(blastAllowed).append(',').append(smokeAllowed).append(',').append(plumeCreated).append(',').append(blastCreated).append(',').append(sounds).append(',').append(visuals).append('\n');
                System.out.println("CBC native FX phase="+phase+" distance="+d+" front="+front+" allowed="+plumeAllowed+"/"+blastAllowed+" created="+plumeCreated+"/"+blastCreated+" sounds="+sounds+" visible-particles="+visuals);
                if(!Boolean.getBoolean("create_fire_control.cannonFxBaseline")) {
                    require(plumeAllowed&&blastAllowed&&smokeAllowed,"CBC plume/audio/smoke survives optimizer at "+d+" front="+front);
                    require(plumeCreated==1&&blastCreated==1,"exactly one native emitter and audio controller created");
                    require(sounds>0,"native big-cannon sound reaches active sound channels at "+d+" front="+front);
                    require(visuals>0,"native CBC smoke reaches render queues at "+d+" front="+front);
                }
                if(phase==3||phase==5)try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(Path.of("cannon-fx-"+d+".png"));}
                mc.getSoundManager().stop();phase++;phaseAge=0;
                if(phase==distances.length)finish(mc,null);
            }
        }catch(Throwable t){finish(mc,t);}
    }
    private static int countParticles(Minecraft mc,String name)throws Exception {
        int count=0;for(String fieldName:List.of("particlesToAdd","particles")){
            Object collection=get(mc.particleEngine,fieldName);
            if(collection instanceof Map<?,?> map){for(Object queue:map.values())for(Object p:(Iterable<?>)queue)if(p.getClass().getSimpleName().equals(name))count++;}
            else for(Object p:(Iterable<?>)collection)if(p.getClass().getSimpleName().equals(name))count++;
        }return count;
    }
    private static int soundChannels(Minecraft mc)throws Exception {
        Object engine=get(mc.getSoundManager(),"soundEngine");Map<?,?> channels=(Map<?,?>)get(engine,"instanceToChannel");
        return (int)channels.keySet().stream().map(o->((SoundInstance)o).getLocation()).filter(id->id.getNamespace().equals("createbigcannons")).count();
    }
    private static int countVisualParticles(Minecraft mc)throws Exception {
        Map<?,?> queues=(Map<?,?>)get(mc.particleEngine,"particles");int count=0;
        for(Object queue:queues.values())for(Object p:(Iterable<?>)queue)
            if(p.getClass().getName().startsWith("rbasamoyai.createbigcannons.effects.particles.")
                    && !(p instanceof net.minecraft.client.particle.NoRenderParticle))count++;
        return count;
    }
    private static Object get(Object o,String name)throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
    private static void finish(Minecraft mc,Throwable t){done=true;if(t!=null)t.printStackTrace();try{Files.writeString(Path.of("cannon-fx-client-result.txt"),t==null?"PASS: live CBC big-cannon native plume/audio/smoke with installed Create FPS Optimizer, distances 2/16/40/64/96/192 and behind camera; baseline="+Boolean.getBoolean("create_fire_control.cannonFxBaseline"):"FAIL: "+t);Files.writeString(Path.of("cannon-fx-client-trace.csv"),trace);}catch(Exception e){throw new RuntimeException(e);}mc.stop();}
}
