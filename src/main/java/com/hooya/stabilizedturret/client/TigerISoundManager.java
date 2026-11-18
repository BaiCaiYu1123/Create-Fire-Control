package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.tiger.TigerIEngineBlockEntity;
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
import static com.hooya.stabilizedturret.client.TigerISoundProfile.*;

@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class TigerISoundManager {
    @SubscribeEvent
    public static void onSourceStarted(PlaySoundSourceEvent event) {
        SoundInstance instance=event.getSound();
        while(instance instanceof MovingSoundInstanceDelegate delegate) instance=delegate.instance;
        if(instance instanceof EngineSound sound) {
            sound.playbackInstance=event.getSound();
            sound.sourceStarted=true;
            ((TigerISpatialAudio.FilterChannel)event.getChannel()).tigerI$highFrequency(()->sound.highFrequencyGain);
        }
    }
    private static final Map<TigerIEngineBlockEntity, Controller> CONTROLLERS = new WeakHashMap<>();
    public static void tick(TigerIEngineBlockEntity engine) {
        Controller control = CONTROLLERS.get(engine);
        if (control == null || !control.active) {
            control = new Controller();
            CONTROLLERS.put(engine, control);
        }
        control.tick(engine);
    }

    public static void remove(TigerIEngineBlockEntity engine) {
        Controller controller=CONTROLLERS.remove(engine);if(controller!=null)controller.invalidate();
    }

    private static final class Controller {
        private final EngineSound[] loops = new EngineSound[7];
        private final List<EngineSound> shots = new ArrayList<>();
        private final float[] volumes = new float[7], pitches = new float[7];
        private final TigerIGain.State mixer = new TigerIGain.State();
        private final TigerITakes.ReferenceGate referenceGate = new TigerITakes.ReferenceGate();
        private final java.util.Random referenceRandom = new java.util.Random();
        private TigerIGain.Parameters parameters = TigerIGain.DEFAULTS;
        private boolean initialized, running, starting, active = true;
        private float envelope, startElapsed, rpm, load, previousRpm, derivative, settledSeconds;
        private boolean hadMotion;
        private EngineSound startVoice;
        private float transitionCooldown, blipCooldown;
        private float guiGain, headroomGain = 1;
        private String lastSample = "";
        private int debugTicks;
        private boolean reference, audible=true;

        private void tick(TigerIEngineBlockEntity engine) {
            parameters = TigerISoundConfig.parameters();
            reference=TigerISoundConfig.reference();
            Vec3 location=Sable.HELPER.projectOutOfSubLevel(engine.getLevel(),Vec3.atCenterOf(engine.getBlockPos()));
            double listenerDistance=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(location);
            audible=!reference || listenerDistance<TigerISoundConfig.cullDistance()
                    && TigerISpatialAudio.gain((float)listenerDistance,TigerISoundConfig.distanceScale())>.0001F;
            var soundManager=Minecraft.getInstance().getSoundManager();
            for(EngineSound shot:shots) {
                shot.pendingSeconds+=.05F;
                if(shot.sourceStarted && shot.age>.25F && !soundManager.isActive(shot.playbackInstance)
                        || !shot.sourceStarted && shot.pendingSeconds>5 && !soundManager.isActive(shot.playbackInstance)) shot.halt();
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
                blipCooldown = TigerISoundConfig.blipCooldown();
                StabilizedTurret.LOGGER.info("Tiger I audio mode={} layers={} at {}",TigerISoundConfig.mode(),TigerISoundConfig.layers(),engine.getBlockPos());
            } else if (enabled != running) {
                running = enabled;
                // Interrupt old start/stop and dynamic takes on an explicit new engine state.
                for (EngineSound sound : shots) sound.halt();
                shots.clear();
                starting = enabled && (!reference || TigerISoundConfig.layers()==TigerISoundConfig.Layers.FULL);
                startElapsed = 0;
                if (enabled) envelope = 0;
                startVoice=reference && TigerISoundConfig.layers()!=TigerISoundConfig.Layers.FULL?null:
                        oneShot(engine, enabled ? "start" : "stop",1,0);
                transitionCooldown = TigerISoundConfig.transientCooldown();
            }
            if (starting) {
                // Begin the handoff clock only after the preloaded OpenAL source actually starts.
                float length = TigerIAudioCalibration.duration("start");
                startElapsed = startVoice==null ? startElapsed+.05F : startVoice.isStopped()?length:startVoice.age;
                envelope = startupEnvelope(startElapsed,length);
                if (startElapsed >= length) starting = false;
            } else envelope = clamp(envelope + (running ? .05F/.4F : -.05F/RELEASE_SECONDS),0,1);

            float magnitude = engine.getSoundRpmMagnitude();
            mixer.tick(normalizedRpm(magnitude, engine.getSoundMaxRpm()),
                    running ? engine.calculateEngineLoad() : 0, .05F, parameters,TigerISoundConfig.mode(),
                    TigerISoundConfig.climbEnabled(),TigerISoundConfig.climbFullAcceleration(),
                    TigerISoundConfig.climbAttack(),TigerISoundConfig.climbRelease());
            rpm = mixer.rpm(); load = mixer.load();
            guiGain = TigerIGain.smooth(guiGain, clamp(engine.getSoundVolume()/100F, 0, 3), .05F, .15F);
            derivative=TigerIGain.smooth(derivative,(rpm-previousRpm)/.05F,.05F,.18F);
            previousRpm = rpm;
            for (int i=0;i<7;i++) {
                // RPM weights and actual SU dB automation are independent presentation parameters.
                // Bypass RPM loudness compensation even when an old config still enables it.
                volumes[i] = mixer.gain(i)*envelope;
                pitches[i] = mixer.pitch(i);
            }
            if (running && audible) {
                for (int i=0;i<7;i++) {
                    if(loops[i]!=null) loops[i].pendingSeconds+=.05F;
                    if (loops[i] == null || loops[i].isStopped()
                            || loops[i].sourceStarted && !soundManager.isActive(loops[i].playbackInstance)
                            || !loops[i].sourceStarted && loops[i].pendingSeconds>5 && !soundManager.isActive(loops[i].playbackInstance)) {
                        if(loops[i]!=null) loops[i].halt();
                        // One preloaded static buffer, looped by OpenAL. No Java seam timer,
                        // duplicated voices, recurring decoder latency or restart phase drift.
                        loops[i] = new EngineSound(engine,this,LOOPS[i],i);
                        soundManager.play(loops[i]);
                    }
                }
            }
            if(!audible) { stopLoops();for(var shot:shots)shot.release(); }
            if (!running && envelope == 0) stopLoops();
            transitionCooldown = Math.max(0,transitionCooldown-.05F);
            blipCooldown = Math.max(0,blipCooldown-.05F);
            for(EngineSound shot:shots) if(!reference && shot.dynamic()) {
                String family=shot.sample.startsWith("revv")?"revv":shot.sample.startsWith("release")?"release":"blips";
                boolean reversed=family.equals("revv")?derivative<3:family.equals("release")?derivative>-3:false;
                float cue=TigerITakes.cueAt(shot.sample,shot.sourceElapsed/shot.duration)*shot.takePitch;
                boolean mismatched=shot.age>.35F && Math.abs(Math.log(cue/TigerITakes.tone(rpm,load)))>.32;
                if(reversed || mismatched || !TigerISoundConfig.takeEnabled(family)) shot.release();
            }
            if(Math.abs(derivative)>1.5F) { hadMotion=true; settledSeconds=0; }
            else settledSeconds+=.05F;
            // Match the current audio RPM domain; disabled simulation uses the mechanical target.
            float revvTarget=engine.getControlSettings().soundRpmEnabled()
                    ?engine.getSoundRpmTarget():engine.getTargetMagnitude();
            float demand=running?clamp((normalizedRpm(revvTarget,engine.getSoundMaxRpm())
                    -normalizedRpm(magnitude,engine.getSoundMaxRpm()))/TigerISoundConfig.loadswitchFullDemand(),0,1):0;
            boolean gateEntered=referenceGate.tick(demand,.05F);
            if(reference && gateEntered && running && !starting && audible && TigerISoundConfig.revvEnabled()) {
                var selected=TigerITakes.referenceRevv(referenceRandom,lastSample,TigerISoundConfig.randomSemitones());
                if(oneShot(engine,selected.sample(),selected.pitch(),0)!=null)lastSample=selected.sample();
            }
            if (!reference && running && !starting && transitionCooldown == 0 && shots.isEmpty()
                    && Math.abs(derivative)>=TigerISoundConfig.triggerSpeed()) {
                String family=derivative>0?"revv":"release";
                if(TigerISoundConfig.takeEnabled(family)) {
                    var selected=TigerITakes.select(family,rpm,load,derivative,lastSample);
                    if(selected!=null) {
                        oneShot(engine,selected.sample(),selected.pitch(),selected.trimDb());
                        lastSample=selected.sample();
                        transitionCooldown=Math.max(TigerISoundConfig.transientCooldown(),
                                TigerIAudioCalibration.duration(lastSample)/selected.pitch()+.5F);
                        blipCooldown=TigerISoundConfig.blipCooldown();
                    }
                }
            } else if(!reference && running && !starting && hadMotion && settledSeconds>.35F && settledSeconds<2
                    && rpm>1 && blipCooldown==0 && shots.isEmpty() && TigerISoundConfig.takeEnabled("blips")) {
                var selected=TigerITakes.select("blips",rpm,load,0,lastSample);
                if(selected!=null) {
                    oneShot(engine,selected.sample(),selected.pitch(),selected.trimDb());lastSample=selected.sample();
                }
                hadMotion=false; blipCooldown=TigerISoundConfig.blipCooldown();
            }
            // Reserve worst-case shot peaks before any voice updates this tick.
            // Immediate safety attack, slow release; defaults leave headroom without audible ducking.
            float peak = TigerIGain.loopPeak(volumes);
            for (EngineSound shot : shots) {
                shot.tickGain(parameters);
                peak += TigerIGain.amplitude(shot.dbGain)*TigerIAudioCalibration.peak(shot.sample);
            }
            float safe = TigerIGain.headroom(peak*guiGain, TigerISoundConfig.peakCeiling());
            headroomGain = reference?1:Math.min(safe, TigerIGain.smooth(headroomGain, safe, .05F, .3F));
            if(TigerISoundConfig.debug() && ++debugTicks>=20) {
                debugTicks=0;float[] weights=new float[7],gainDb=new float[7];
                for(int i=0;i<7;i++){weights[i]=mixer.weight(i);gainDb[i]=volumes[i]>0?20*(float)Math.log10(volumes[i]):-100;}
                long activeSources=java.util.stream.Stream.concat(Arrays.stream(loops),shots.stream())
                        .filter(Objects::nonNull).filter(v->soundManager.isActive(v.playbackInstance)).count();
                org.slf4j.LoggerFactory.getLogger("create_fire_control.tiger_i").info(
                    "Tiger I {} mode={} rpm={} SU={} climb={} loadswitch={} weights={} pitch={} gain={} gainDb={} shots={} instances={} activeSources={}",
                    engine.getBlockPos(),TigerISoundConfig.mode(),rpm,load,mixer.climb(),referenceGate.value(),Arrays.toString(weights),Arrays.toString(pitches),Arrays.toString(volumes),Arrays.toString(gainDb),
                    shots.stream().map(v->v.sample).toList(),(int)Arrays.stream(loops).filter(Objects::nonNull).count()+shots.size(),activeSources);
            }
        }
        private EngineSound oneShot(TigerIEngineBlockEntity engine,String sample,float pitch,float trim) {
            if (!audible || shots.size()>=TigerISoundConfig.maxShots()) return null;
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
        private final WeakReference<TigerIEngineBlockEntity> engine;
        private final WeakReference<ClientLevel> rootLevel;
        private final Controller controller;
        private final int layer;
        private final String sample;
        private final float duration;
        private float age, sourceElapsed, dbGain, takePitch=1,takeTrim,releaseAge=-1,pendingSeconds;
        private volatile boolean sourceStarted;
        private SoundInstance playbackInstance=this;
        private volatile float highFrequencyGain=1;
        EngineSound(TigerIEngineBlockEntity source,Controller controller,String sample,int layer) {
            super(ModSounds.TIGER_I.get(sample).get(), SoundSource.BLOCKS, RandomSource.create());
            this.engine=new WeakReference<>(source);
            this.rootLevel=new WeakReference<>(Minecraft.getInstance().level);
            this.controller=controller; this.layer=layer; this.sample=sample;
            dbGain=controller.reference?TigerIGain.referenceShotDb(sample,TigerISoundConfig.categoryDb(sample),controller.parameters):
                    TigerIGain.shotDb(sample,TigerISoundConfig.categoryDb(sample),controller.parameters);
            this.duration=TigerIAudioCalibration.duration(sample);
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
            double distance=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(location);
            float radius=Math.max(4.001F,(layer>=0 ? 32 : 64)*setting);
            float attenuationGain=controller.reference?TigerISpatialAudio.gain((float)distance,TigerISoundConfig.distanceScale()):
                    clamp((float)((radius-distance)/(radius-4)),0,1);
            if(controller.reference) {
                var camera=Minecraft.getInstance().gameRenderer.getMainCamera();
                Vec3 delta=location.subtract(camera.getPosition());
                var look=camera.getLookVector();
                double horizontal=Math.hypot(delta.x,delta.z)*Math.hypot(look.x(),look.z());
                float angle=horizontal>.0001?(float)Math.toDegrees(Math.acos(net.minecraft.util.Mth.clamp(
                        (delta.x*look.x()+delta.z*look.z())/horizontal,-1,1))):0;
                attenuationGain*=TigerISpatialAudio.directionalGain(angle);
            }
            highFrequencyGain=controller.reference?TigerISpatialAudio.highFrequencyGain((float)distance,
                    TigerISoundConfig.distanceScale(),TigerISoundConfig.highFrequencyLoss()):1;
            pitch=layer>=0 ? controller.pitches[layer] : takePitch;
            volume=setting*attenuationGain*controller.headroomGain*(layer>=0
                    ? controller.volumes[layer]*TigerIGain.shotEnvelope(age,Float.POSITIVE_INFINITY)
                    : TigerIGain.amplitude(dbGain)*(controller.reference?1:TigerIGain.shotEnvelope(age,remainingSeconds()))*releaseEnvelope());
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
        void tickGain(TigerIGain.Parameters p) {
            if(controller.reference) {
                dbGain=TigerIGain.referenceShotDb(sample,TigerISoundConfig.categoryDb(sample),p)+takeTrim;return;
            }
            dbGain=TigerIGain.smoothDb(dbGain,
                    TigerIGain.shotDb(sample,TigerISoundConfig.categoryDb(sample),p)
                            +takeTrim,.05F,p);
        }
        boolean dynamic() { return sample.startsWith("revv") || sample.startsWith("release") || sample.startsWith("blips"); }
        void release() { if(releaseAge<0) releaseAge=0; }
        float releaseEnvelope() {
            return TigerITakes.releaseEnvelope(releaseAge);
        }
        @Override public boolean canStartSilent() { return true; }
        void halt() { stop(); }
    }
    private TigerISoundManager() {}
}
