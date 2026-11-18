package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.diesel.Ztz99aDieselEngineBlockEntity;
import com.hooya.stabilizedturret.registry.ModSounds;
import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sound.MovingSoundInstanceDelegate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import java.lang.ref.WeakReference;
import java.util.*;
import static com.hooya.stabilizedturret.client.Ztz99aDieselSoundProfile.*;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class Ztz99aDieselSoundManager {
    @SubscribeEvent
    public static void onSourceStarted(PlaySoundSourceEvent event) {
        SoundInstance instance=event.getSound();
        while(instance instanceof MovingSoundInstanceDelegate delegate) instance=delegate.instance;
        if(instance instanceof EngineSound sound) {
            sound.playbackInstance=event.getSound();
            sound.sourceStarted=true;
        }
    }
    private static final Map<Ztz99aDieselEngineBlockEntity, Controller> CONTROLLERS = new WeakHashMap<>();
    public static void tick(Ztz99aDieselEngineBlockEntity engine) {
        Controller control = CONTROLLERS.get(engine);
        if (control == null || !control.active) {
            control = new Controller();
            CONTROLLERS.put(engine, control);
        }
        control.tick(engine);
    }

    private static final class Controller {
        private final EngineSound[] loops = new EngineSound[4];
        private final List<EngineSound> shots = new ArrayList<>();
        private final float[] volumes = new float[4], pitches = new float[4];
        private final Ztz99aDieselGain.State mixer = new Ztz99aDieselGain.State();
        private Ztz99aDieselGain.Parameters parameters = Ztz99aDieselGain.DEFAULTS;
        private boolean initialized, running, starting, active = true;
        private float envelope, startElapsed, rpm, load, previousRpm, derivative, settledSeconds;
        private boolean hadMotion;
        private EngineSound startVoice;
        private float transitionCooldown, blipCooldown;
        private float guiGain, headroomGain = 1;
        private String lastSample = "";

        private void tick(Ztz99aDieselEngineBlockEntity engine) {
            parameters = Ztz99aDieselSoundConfig.parameters();
            var soundManager=Minecraft.getInstance().getSoundManager();
            for(EngineSound shot:shots) {
                shot.pendingSeconds+=.05F;
                if(shot.sourceStarted && shot.age>.25F && !soundManager.isActive(shot.playbackInstance)
                        || !shot.sourceStarted && shot.pendingSeconds>2 && !soundManager.isActive(shot.playbackInstance)) shot.halt();
            }
            shots.removeIf(EngineSound::isStopped);
            boolean enabled = engine.isEngineEnabled();
            if (!initialized) {
                initialized = true;
                running = enabled;
                envelope = enabled ? 1 : 0;
                rpm = normalizedRpm(engine.getSoundRpmMagnitude(), engine.getSoundMaxRpm());
                previousRpm = rpm;
                guiGain = clamp(engine.getSoundVolume()/100F, 0, 3);
                blipCooldown = Ztz99aDieselSoundConfig.blipCooldown();
            } else if (enabled != running) {
                running = enabled;
                // Interrupt old start/stop and dynamic takes on an explicit new engine state.
                for (EngineSound sound : shots) sound.halt();
                shots.clear();
                starting = enabled;
                startElapsed = 0;
                if (enabled) envelope = 0;
                startVoice=oneShot(engine, enabled ? "engine_start" : "engine_stop",1,0);
                transitionCooldown = Ztz99aDieselSoundConfig.transientCooldown();
            }
            if (starting) {
                // Begin the handoff clock only after the preloaded OpenAL source actually starts.
                float length = Ztz99aAudioCalibration.duration("engine_start");
                startElapsed = startVoice==null ? startElapsed+.05F : startVoice.isStopped()?length:startVoice.age;
                envelope = startupEnvelope(startElapsed,length);
                if (startElapsed >= length) starting = false;
            } else envelope = clamp(envelope + (running ? .05F/.4F : -.05F/RELEASE_SECONDS),0,1);

            float magnitude = engine.getSoundRpmMagnitude();
            mixer.tick(normalizedRpm(magnitude, engine.getSoundMaxRpm()),
                    running ? engine.calculateEngineLoad() : 0, .05F, parameters);
            rpm = mixer.rpm(); load = mixer.load();
            guiGain = Ztz99aDieselGain.smooth(guiGain, clamp(engine.getSoundVolume()/100F, 0, 3), .05F, .15F);
            derivative=Ztz99aDieselGain.smooth(derivative,(rpm-previousRpm)/.05F,.05F,.18F);
            previousRpm = rpm;
            for (int i=0;i<4;i++) {
                // RPM weights and actual SU dB automation are independent presentation parameters.
                volumes[i] = mixer.gain(i)*Ztz99aDieselGain.amplitude(Ztz99aDieselSpatialAudio.trimDb(LOOPS[i]))*envelope;
                pitches[i] = mixer.pitch(i);
            }
            if (running) {
                for (int i=0;i<4;i++) {
                    if(loops[i]!=null) loops[i].pendingSeconds+=.05F;
                    if (loops[i] == null || loops[i].isStopped()
                            || loops[i].sourceStarted && !soundManager.isActive(loops[i].playbackInstance)
                            || !loops[i].sourceStarted && loops[i].pendingSeconds>1 && !soundManager.isActive(loops[i].playbackInstance)) {
                        if(loops[i]!=null) loops[i].halt();
                        // One preloaded static buffer, looped by OpenAL. No Java seam timer,
                        // duplicated voices, recurring decoder latency or restart phase drift.
                        loops[i] = new EngineSound(engine,this,LOOPS[i],i);
                        soundManager.play(loops[i]);
                    }
                }
            }
            if (!running && envelope == 0) stopLoops();
            transitionCooldown = Math.max(0,transitionCooldown-.05F);
            blipCooldown = Math.max(0,blipCooldown-.05F);
            for(EngineSound shot:shots) if(shot.dynamic()) {
                String family=shot.sample.startsWith("engine_revv")?"revv":shot.sample.startsWith("engine_release")?"release":"blips";
                boolean reversed=family.equals("revv")?derivative<3:family.equals("release")?derivative>-3:false;
                float cue=Ztz99aDieselTakes.cueAt(shot.sample,shot.sourceElapsed/shot.duration)*shot.takePitch;
                boolean mismatched=shot.age>.35F && Math.abs(Math.log(cue/Ztz99aDieselTakes.tone(rpm)))>.32;
                if(reversed || mismatched || !Ztz99aDieselSoundConfig.takeEnabled(family)) shot.release();
            }
            if(Math.abs(derivative)>1.5F) { hadMotion=true; settledSeconds=0; }
            else settledSeconds+=.05F;
            if (running && !starting && transitionCooldown == 0 && shots.isEmpty()
                    && Math.abs(derivative)>=Ztz99aDieselSoundConfig.triggerSpeed()) {
                String family=derivative>0?"revv":"release";
                if(Ztz99aDieselSoundConfig.takeEnabled(family)) {
                    var selected=Ztz99aDieselTakes.select(family,rpm,derivative,lastSample);
                    if(selected!=null) {
                        oneShot(engine,selected.sample(),selected.pitch(),selected.trimDb());
                        lastSample=selected.sample();
                        transitionCooldown=Math.max(Ztz99aDieselSoundConfig.transientCooldown(),
                                Ztz99aAudioCalibration.duration(lastSample)/selected.pitch()+.5F);
                        blipCooldown=Ztz99aDieselSoundConfig.blipCooldown();
                    }
                }
            } else if(running && !starting && hadMotion && settledSeconds>.35F && settledSeconds<2
                    && rpm>1 && blipCooldown==0 && shots.isEmpty() && Ztz99aDieselSoundConfig.takeEnabled("blips")) {
                var selected=Ztz99aDieselTakes.select("blips",rpm,0,lastSample);
                if(selected!=null) {
                    oneShot(engine,selected.sample(),selected.pitch(),selected.trimDb());lastSample=selected.sample();
                }
                hadMotion=false; blipCooldown=Ztz99aDieselSoundConfig.blipCooldown();
            }
            // Reserve worst-case shot peaks before any voice updates this tick.
            // Immediate safety attack, slow release; defaults leave headroom without audible ducking.
            float peak = Ztz99aDieselGain.loopPeak(volumes);
            for (EngineSound shot : shots) {
                shot.tickGain(parameters);
                peak += Ztz99aDieselGain.amplitude(shot.dbGain)*Ztz99aAudioCalibration.peak(shot.sample);
            }
            float safe = Ztz99aDieselGain.headroom(peak*guiGain, Ztz99aDieselSoundConfig.peakCeiling());
            headroomGain = Math.min(safe, Ztz99aDieselGain.smooth(headroomGain, safe, .05F, .3F));
        }
        private EngineSound oneShot(Ztz99aDieselEngineBlockEntity engine,String sample,float pitch,float trim) {
            if (shots.size()>=Ztz99aDieselSoundConfig.maxShots()) return null;
            EngineSound sound=new EngineSound(engine,this,sample,-1);
            sound.takePitch=pitch; sound.takeTrim=trim; sound.dbGain+=trim;
            sound.update(false);
            shots.add(sound);
            Minecraft.getInstance().getSoundManager().play(sound);
            return sound;
        }
        private void stopLoops() {
            for(EngineSound sound:loops) if(sound!=null) sound.halt();
            Arrays.fill(loops,null);
        }
        private void invalidate() {
            if(!active) return;
            active=false;
            stopLoops();
            for(EngineSound sound:shots) sound.halt();
            shots.clear();
        }
    }

    private static final class EngineSound extends AbstractTickableSoundInstance {
        private final WeakReference<Ztz99aDieselEngineBlockEntity> engine;
        private final WeakReference<ClientLevel> rootLevel;
        private final Controller controller;
        private final int layer;
        private final String sample;
        private final float duration;
        private float age, sourceElapsed, dbGain, takePitch=1,takeTrim,releaseAge=-1,pendingSeconds;
        private volatile boolean sourceStarted;
        private SoundInstance playbackInstance=this;
        EngineSound(Ztz99aDieselEngineBlockEntity source,Controller controller,String sample,int layer) {
            super(ModSounds.ZTZ99A.get(sample).get(), SoundSource.BLOCKS, RandomSource.create());
            this.engine=new WeakReference<>(source);
            this.rootLevel=new WeakReference<>(Minecraft.getInstance().level);
            this.controller=controller; this.layer=layer; this.sample=sample;
            dbGain=Ztz99aDieselGain.shotDb(sample,Ztz99aDieselSoundConfig.categoryDb(sample),controller.parameters)
                    +Ztz99aDieselSpatialAudio.trimDb(sample);
            this.duration=Ztz99aAudioCalibration.duration(sample);
            looping=layer>=0; delay=0; relative=false;
            // Positional mono copies retain native listener-relative left/right panning.
            // NONE disables only OpenAL distance attenuation; the calibrated distance curve is applied below.
            attenuation=SoundInstance.Attenuation.NONE;
            volume=.0001F; pitch=1;
            update(false);
        }
        @Override public void tick() { update(true); }
        private void update(boolean advance) {
            var source=engine.get();
            if(source==null || source.isRemoved() || source.getLevel()==null
                    || rootLevel.get()==null || rootLevel.get()!=Minecraft.getInstance().level || !controller.active) {
                stop(); controller.invalidate(); return;
            }
            Vec3 location=Sable.HELPER.projectOutOfSubLevel(source.getLevel(),Vec3.atCenterOf(source.getBlockPos()));
            x=location.x; y=location.y; z=location.z;
            float setting=controller.guiGain;
            float radius=Math.max(4.001F,(layer>=0 ? 32 : 64)*setting);
            double distance=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(location);
            float attenuationGain=clamp((float)((radius-distance)/(radius-4)),0,1);
            pitch=layer>=0 ? controller.pitches[layer] : takePitch;
            volume=setting*attenuationGain*controller.headroomGain*(layer>=0
                    ? controller.volumes[layer]*Ztz99aDieselGain.shotEnvelope(age,Float.POSITIVE_INFINITY)
                    : Ztz99aDieselGain.amplitude(dbGain)*Ztz99aDieselGain.shotEnvelope(age,remainingSeconds())*releaseEnvelope());
            if(advance && sourceStarted) {
                age+=.05F;
                if(layer<0) sourceElapsed+=.05F*pitch;
                if(releaseAge>=0) releaseAge+=.05F;
            }
            if(layer<0 && releaseAge>=.45F) stop();
            // Shot tails fade before their measured end. Actual playback completion,
            // rather than the estimated clock, determines removal by the controller.
        }
        float remainingSeconds() { return Math.max(0,(duration-sourceElapsed)/Math.max(.1F,pitch)); }
        void tickGain(Ztz99aDieselGain.Parameters p) {
            dbGain=Ztz99aDieselGain.smoothDb(dbGain,
                    Ztz99aDieselGain.shotDb(sample,Ztz99aDieselSoundConfig.categoryDb(sample),p)
                            +Ztz99aDieselSpatialAudio.trimDb(sample)+takeTrim,.05F,p);
        }
        boolean dynamic() { return sample.startsWith("engine_revv") || sample.startsWith("engine_release") || sample.startsWith("engine_blips"); }
        void release() { if(releaseAge<0) releaseAge=0; }
        float releaseEnvelope() {
            return Ztz99aDieselTakes.releaseEnvelope(releaseAge);
        }
        @Override public boolean canStartSilent() { return true; }
        void halt() { stop(); }
    }
    private Ztz99aDieselSoundManager() {}
}
