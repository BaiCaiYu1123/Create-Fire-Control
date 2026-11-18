package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.LiveHeadAimRig;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class LiveHeadAimClientChecks {
    private static int age,phase=-1,phaseAge,readyTicks;
    private static boolean launched,done;
    private static CompletableFuture<?> job;
    private static final StringBuilder trace=new StringBuilder("phase,tick,rayAge,maxBarrelError,cameraPlayerDistance,axes\n");
    private static int rejectedSamples,acceptedSamples;
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ClientTickEvent.Post event) {
        if(done)return;var mc=Minecraft.getInstance();
        try {
            if(++age>6000)throw new AssertionError("live camera test timeout: phase="+phase);
            if(!launched&&age>60&&mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){mc.setScreen(new TitleScreen());return;}
            if(!launched&&age>60&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null) {
                launched=true;mc.createWorldOpenFlows().createFreshLevel("LiveHeadAim-"+System.currentTimeMillis(),
                        new LevelSettings("Live head aim regression",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);return;
            }
            if(mc.level==null||mc.player==null)return;
            if(job!=null){if(!job.isDone())return;job.join();job=null;}
            if(phase==-1) {
                phase=0;job=mc.getSingleplayerServer().submit(()->{try {LiveHeadAimRig.create(mc.getSingleplayerServer().overworld());}catch(Exception e){throw new RuntimeException(e);}});return;
            }
            if(phase==0) {
                if(++readyTicks<30)return;
                readyTicks=0;
                job=mc.getSingleplayerServer().submit(()->{
                    LiveHeadAimRig.forceAll();var p=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    Vec3 pos=Sable.HELPER.projectOutOfSubLevel(p.level(),LiveHeadAimRig.seatPos.getCenter());
                    p.teleportTo(pos.x,pos.y+1,pos.z);try{LiveHeadAimRig.mount(p);}catch(Exception e){throw new RuntimeException(e);}
                });
                phase=1;return;
            }
            if(phase==1) {
                if(!ClientAimState.isActive()||mc.player.getVehicle()==null||Sable.HELPER.getTrackingOrVehicleSubLevel(mc.player)==null){if(++readyTicks>300)throw new AssertionError("real seat/session did not synchronize");return;}
                mc.setScreen(null);mc.options.setCameraType(SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED);
                phase=2;phaseAge=0;return;
            }
            if(phaseAge++==0) {
                float[] yaws={0,90,180,270,0,90,180,270};
                set("stableWorldYaw",yaws[phase-2]);set("stableWorldPitch",-8.0F);
                SableCameraZoomCompat.setZoomUnclamped(mc,mc.gameRenderer.getMainCamera(),-1.5F);
                int current=phase;
                job=mc.getSingleplayerServer().submit(()->{try{LiveHeadAimRig.forceAll();LiveHeadAimRig.prepare(current>=6,current>=6?200:0);}catch(Exception e){throw new RuntimeException(e);}});
                return;
            }
            if(phaseAge<30)return;
            int current=phase,tick=phaseAge;
            float yaw=ClientAimState.getStableWorldYaw(),pitch=ClientAimState.getStableWorldPitch();
            double ry=Math.toRadians(yaw),rp=Math.toRadians(pitch);
            Vec3 requested=new Vec3(-Math.sin(ry)*Math.cos(rp),-Math.sin(rp),Math.cos(ry)*Math.cos(rp));
            double distance=mc.gameRenderer.getMainCamera().getPosition().distanceTo(mc.player.position());
            job=mc.getSingleplayerServer().submit(()->{
                try {
                    LiveHeadAimRig.forceAll();long rayAge=LiveHeadAimRig.rayAge();double error=LiveHeadAimRig.maxError(requested);
                    trace.append(current).append(',').append(tick).append(',').append(rayAge).append(',').append(error).append(',').append(distance).append(',').append(LiveHeadAimRig.axisSummary()).append('\n');
                    if(rayAge>5)rejectedSamples++;else acceptedSamples++;
                    if(!Boolean.getBoolean("create_fire_control.headAimLiveBaseline")&&tick>70&&rayAge>5)
                        throw new AssertionError("actual camera/network input stopped: phase="+current+" age="+rayAge+" distance="+distance);
                    if(!Boolean.getBoolean("create_fire_control.headAimLiveBaseline")&&tick==150&&error>3)
                        throw new AssertionError("actual multi-turret barrels did not follow: phase="+current+" maxError="+error+" axes="+LiveHeadAimRig.axisSummary());
                }catch(Exception e){throw new RuntimeException(e);}
            });
            if(phaseAge==150) {
                try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(Path.of("head-aim-phase-"+phase+".png"));}
                System.out.println("LIVE HEAD AIM phase="+phase+" accepted="+acceptedSamples+" stale="+rejectedSamples);
                phase++;phaseAge=0;
                if(phase==10||phase==6&&Boolean.getBoolean("create_fire_control.headAimLiveBaseline")) {
                    job.join();job=null;
                    if(Boolean.getBoolean("create_fire_control.headAimLiveBaseline")&&(rejectedSamples==0||acceptedSamples==0))throw new AssertionError("baseline did not reproduce both moving and stopped input periods");
                    finish(mc,null);
                }
            }
        }catch(Throwable failure){finish(mc,failure);}
    }
    private static void set(String name,Object value) throws Exception {var f=ClientAimState.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    private static void finish(Minecraft mc,Throwable failure){done=true;try{Files.writeString(Path.of("head-aim-live-trace.csv"),trace);Files.writeString(Path.of("head-aim-live-result.txt"),failure==null?
            "PASS: "+(Boolean.getBoolean("create_fire_control.headAimLiveBaseline")?"baseline reproduced stale input":"rendered camera, real network and six CBC muzzle references followed through eight directions/manual/stabilized phases")+"; fresh="+acceptedSamples+" stale="+rejectedSamples:"FAIL: "+failure);}
        catch(Exception e){throw new RuntimeException(e);}if(failure!=null)StabilizedTurret.LOGGER.error("LIVE HEAD AIM CHECK FAILED",failure);mc.stop();}
}
