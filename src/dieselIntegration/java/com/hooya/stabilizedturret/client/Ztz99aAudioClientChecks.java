package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.*;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in actual Minecraft decoder/OpenAL QA; never enters the release JAR. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class Ztz99aAudioClientChecks {
    private static final List<Probe> probes=new ArrayList<>();
    private static final Map<String,Integer> starts=new ConcurrentHashMap<>();
    private static final Map<String,Double> latency=new ConcurrentHashMap<>();
    private static boolean started,finished;
    private static long beginning;
    private static final StringBuilder report=new StringBuilder("Actual Minecraft JOrbis decode / static OpenAL loop QA\n");
    @SubscribeEvent public static void source(PlaySoundSourceEvent event) {
        if(event.getSound() instanceof Probe p) {
            starts.merge(p.sample,1,Integer::sum);
            latency.put(p.sample,(System.nanoTime()-p.issued)/1e6);
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("create_fire_control.ztz99aAudioChecks") || finished) return;
        var mc=Minecraft.getInstance();
        try {
            if(!started && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
                started=true;
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
                mc.options.getSoundSourceOptionInstance(SoundSource.BLOCKS).set(1.0);
                mc.getSoundManager().updateSourceVolume(SoundSource.MASTER,1);
                for(String sample:Ztz99aAudioCalibration.SAMPLES) {
                    var id=ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"sounds/engine/ztz99a/"+sample+".ogg");
                    try(var stream=new JOrbisAudioStream(mc.getResourceManager().open(id))) {
                        var buffer=stream.readAll();var format=stream.getFormat();
                        double seconds=buffer.remaining()/format.getFrameSize()/format.getSampleRate();
                        double error=seconds-Ztz99aAudioCalibration.duration(sample);
                        report.append(String.format(Locale.ROOT,"%s JOrbis=%.7fs measured=%.7fs delta=%.7fs%n",sample,seconds,Ztz99aAudioCalibration.duration(sample),error));
                        require(Math.abs(error)<.06,"Vorbis decoded duration "+sample);
                    }
                    var definition=mc.getSoundManager().getSoundEvent(ModSounds.ZTZ99A.get(sample).get().getLocation()).getSound(RandomSource.create());
                    require(!definition.shouldStream() && definition.shouldPreload(),"resolved static/preloaded sound "+sample);
                }
                for(String sample:Ztz99aDieselSoundProfile.LOOPS) {
                    var probe=new Probe(sample);probes.add(probe);mc.getSoundManager().play(probe);
                }
                beginning=System.nanoTime();
            } else if(started && (System.nanoTime()-beginning)/1e9>32) {
                for(Probe p:probes) {
                    require(starts.getOrDefault(p.sample,0)==1,"exactly one OpenAL source start across multiple wraps "+p.sample);
                    require(mc.getSoundManager().isActive(p),"loop still active after 32s "+p.sample+" looping="+p.isLooping()+" stopped="+p.isStopped()+" master="+mc.options.getSoundSourceVolume(SoundSource.MASTER));
                    report.append(p.sample+" starts="+starts.get(p.sample)+" latency_ms="+latency.get(p.sample)+" active_after_32s=true\n");
                    p.halt();
                }
                finish(mc,null);
            }
        } catch(Throwable error) { finish(mc,error); }
    }
    private static void finish(Minecraft mc,Throwable error) {
        finished=true;
        report.append(error==null?"PASS\n":"FAIL "+error+"\n");
        if(error!=null) StabilizedTurret.LOGGER.error("99A AUDIO CLIENT QA FAILED",error);
        try {
            Files.writeString(Path.of("ztz99a-audio-client-result.txt"),report);
            Files.writeString(Path.of("diesel-client-result.txt"),error==null?"PASS":"FAIL "+error);
        }
        catch(Exception e) { throw new RuntimeException(e); }
        mc.stop();
    }
    private static void require(boolean condition,String label) { if(!condition) throw new AssertionError(label); }
    private static final class Probe extends AbstractTickableSoundInstance {
        final String sample;final long issued=System.nanoTime();
        Probe(String sample) {
            super(ModSounds.ZTZ99A.get(sample).get(),SoundSource.BLOCKS,RandomSource.create());
            this.sample=sample;looping=true;delay=0;relative=true;attenuation=SoundInstance.Attenuation.NONE;
            volume=.0001F;pitch=1;
        }
        public void tick() {}
        void halt() { stop(); }
        public boolean canStartSilent() { return true; }
    }
}
