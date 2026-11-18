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
public final class TigerIAudioClientChecks {
    private static final List<Probe> probes=new ArrayList<>();
    private static final Map<String,Integer> starts=new ConcurrentHashMap<>();
    private static final Map<String,Double> latency=new ConcurrentHashMap<>();
    private static final List<TigerISpatialAudio.FilterChannel> filters=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean efxAvailable;
    private static final boolean RESOURCE_ONLY=Boolean.getBoolean("create_fire_control.tigerResourceChecksOnly");
    private static boolean started,finished;
    private static boolean screenshotRequested;
    private static volatile boolean screenshotDone;
    private static long beginning;
    private static int cleanupTicks;
    private static final StringBuilder report=new StringBuilder("Actual Minecraft JOrbis decode / static OpenAL loop QA\n");
    @SubscribeEvent public static void source(PlaySoundSourceEvent event) {
        if(event.getSound() instanceof Probe p) {
            starts.merge(p.sample,1,Integer::sum);
            latency.put(p.sample,(System.nanoTime()-p.issued)/1e6);
            ((TigerISpatialAudio.FilterChannel)event.getChannel()).tigerI$highFrequency(()->.5);
            filters.add((TigerISpatialAudio.FilterChannel)event.getChannel());
            efxAvailable=org.lwjgl.openal.AL.getCapabilities().ALC_EXT_EFX;
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("create_fire_control.tigerAudioChecks") || finished) return;
        var mc=Minecraft.getInstance();
        try {
            if(!started && mc.screen!=null && mc.getOverlay()==null) {
                started=true;
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
                mc.options.getSoundSourceOptionInstance(SoundSource.BLOCKS).set(1.0);
                mc.getSoundManager().updateSourceVolume(SoundSource.MASTER,1);
                org.lwjgl.glfw.GLFW.glfwHideWindow(mc.getWindow().getWindow());
                checkModelsAndGui(mc);
                for(String sample:TigerIAudioCalibration.SAMPLES) {
                    if(sample.equals("idle_not_load"))continue;
                    var id=ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"sounds/engine/tiger_i/"+sample+".ogg");
                    try(var stream=new JOrbisAudioStream(mc.getResourceManager().open(id))) {
                        var buffer=stream.readAll();var format=stream.getFormat();
                        require(format.getChannels()==1,"positional mono decoded by JOrbis "+sample);
                        double seconds=buffer.remaining()/format.getFrameSize()/format.getSampleRate();
                        double error=seconds-TigerIAudioCalibration.duration(sample);
                        report.append(String.format(Locale.ROOT,"%s JOrbis=%.7fs measured=%.7fs delta=%.7fs%n",sample,seconds,TigerIAudioCalibration.duration(sample),error));
                        require(Math.abs(error)<.06,"Vorbis decoded duration "+sample);
                    }
                    var definition=mc.getSoundManager().getSoundEvent(ModSounds.TIGER_I.get(sample).get().getLocation()).getSound(RandomSource.create());
                    require(!definition.shouldStream() && definition.shouldPreload(),"resolved static/preloaded sound "+sample);
                }
                if(!RESOURCE_ONLY)for(String sample:TigerISoundProfile.LOOPS) {
                    var probe=new Probe(sample);probes.add(probe);mc.getSoundManager().play(probe);
                }
                beginning=System.nanoTime();
            } else if(started && !screenshotRequested && (System.nanoTime()-beginning)/1e9>2) {
                screenshotRequested=true;
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,"tiger_i_v8_model.png",
                        mc.getMainRenderTarget(),message->screenshotDone=true);
            } else if(started && RESOURCE_ONLY && screenshotDone) {
                report.append("resource-only check: models, item, texture, translations and 30 decoded sounds\n");
                finish(mc,null);
            } else if(started && (System.nanoTime()-beginning)/1e9>32) {
                if(cleanupTicks++>0) {
                    if(cleanupTicks<5)return;
                    for(Probe p:probes)require(!mc.getSoundManager().isActive(p),"halted source released "+p.sample);
                    require(filters.stream().noneMatch(TigerISpatialAudio.FilterChannel::tigerI$filterActive),"EFX filters released with channels");
                    report.append("all seven halted sources released after five ticks\n");
                    require(screenshotDone,"V8 item screenshot captured");
                    finish(mc,null);return;
                }
                if(efxAvailable)require(filters.size()==7&&filters.stream().allMatch(TigerISpatialAudio.FilterChannel::tigerI$filterActive),"seven actual EFX low-pass filters active");
                report.append("OpenAL EFX available="+efxAvailable+" active_filters="+filters.stream().filter(TigerISpatialAudio.FilterChannel::tigerI$filterActive).count()+"\n");
                for(Probe p:probes) {
                    require(starts.getOrDefault(p.sample,0)==1,"exactly one OpenAL source start across multiple wraps "+p.sample);
                    require(mc.getSoundManager().isActive(p),"loop still active after 32s "+p.sample+" looping="+p.isLooping()+" stopped="+p.isStopped()+" master="+mc.options.getSoundSourceVolume(SoundSource.MASTER));
                    report.append(p.sample+" starts="+starts.get(p.sample)+" latency_ms="+latency.get(p.sample)+" active_after_32s=true\n");
                    p.halt();
                }
            }
        } catch(Throwable error) { finish(mc,error); }
    }
    private static void checkModelsAndGui(Minecraft mc) throws Exception {
        require(TigerISoundConfig.SPEC.isLoaded(),"independent Tiger config loaded");
        require(TigerISoundConfig.mode()==TigerISoundConfig.Mode.FMOD_REFERENCE,"reference default active");
        report.append("mode=FMOD_REFERENCE layers="+TigerISoundConfig.layers()+"; Tiger EFX opt-in applied to seven probes\n");
        var block=com.hooya.stabilizedturret.registry.ModBlocks.TIGER_I_ENGINE.get();
        var missing=mc.getModelManager().getMissingModel();
        int triangles;
        try(var reader=new java.io.InputStreamReader(mc.getResourceManager().open(
                ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"tiger_i_engine/geometry_manifest.json")),
                java.nio.charset.StandardCharsets.UTF_8)) {
            triangles=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().get("triangle_count").getAsInt();
        }
        for(var state:block.getStateDefinition().getPossibleStates()) {
            var model=mc.getBlockRenderer().getBlockModel(state);
            require(model!=missing,"Tiger block model baked "+state);
            require(model.getParticleIcon().contents().name().equals(ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID,"block/tiger_i_engine")),"supplied V8 texture resolved "+state);
            if(state.getValue(com.hooya.stabilizedturret.content.tiger.TigerIEngineBlock.PART)
                    ==com.hooya.stabilizedturret.content.tiger.TigerIEnginePart.CENTER) {
                int count=model.getQuads(state,null,RandomSource.create()).size();
                for(var side:net.minecraft.core.Direction.values())
                    count+=model.getQuads(state,side,RandomSource.create()).size();
                require(count==triangles,"all supplied V8 geometry baked "+state+" actual="+count+" expected="+triangles);
                report.append("V8 "+state+" baked_triangles="+count+" texture=resolved\n");
            }
        }
        var item=mc.getItemRenderer().getModel(new net.minecraft.world.item.ItemStack(block),null,null,0);
        require(item!=missing && !item.getQuads(null,null,RandomSource.create()).isEmpty(),"V8 inventory model baked");
        for(var quad:item.getQuads(null,null,RandomSource.create()))require(quad.getSprite().contents().name().equals(
                ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"block/tiger_i_engine")),"V8 inventory texture resolved");
        try(var reader=new java.io.InputStreamReader(mc.getResourceManager().open(
                ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"lang/zh_cn.json")),
                java.nio.charset.StandardCharsets.UTF_8)) {
            require(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().get("block.create_fire_control.tiger_i_engine")
                    .getAsString().equals("8缸汽油发动机"),"V8 Chinese display name");
        }
        report.append("V8 inventory model and texture resolved; Chinese name=8缸汽油发动机\n");
        var payload=new com.hooya.stabilizedturret.network.OpenTigerIEnginePayload(net.minecraft.core.BlockPos.ZERO,null,true,false,
                com.hooya.stabilizedturret.content.turbine.EngineControlSettings.defaults());
        ClientPayloadHandlers.openTigerIEngine(payload);
        require(mc.screen.getTitle().getString().contains("Tiger I"),"independent Tiger GUI title");
        mc.setScreen(new ModelPreviewScreen());
    }
    private static void finish(Minecraft mc,Throwable error) {
        finished=true;
        report.append(error==null?"PASS\n":"FAIL "+error+"\n");
        if(error!=null) StabilizedTurret.LOGGER.error("Tiger I AUDIO CLIENT QA FAILED",error);
        try {
            Files.writeString(Path.of("tiger-audio-client-report.txt"),report);
            Files.writeString(Path.of("tiger-audio-client-result.txt"),error==null?"PASS":"FAIL "+error);
        }
        catch(Exception e) { throw new RuntimeException(e); }
        mc.stop();
    }
    private static void require(boolean condition,String label) { if(!condition) throw new AssertionError(label); }
    private static final class ModelPreviewScreen extends net.minecraft.client.gui.screens.Screen {
        ModelPreviewScreen() { super(net.minecraft.network.chat.Component.literal("Tiger I V8 model / Y +90")); }
        @Override public void render(net.minecraft.client.gui.GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
            graphics.fill(0,0,width,height,0xff182127);
            graphics.drawCenteredString(font,title,width/2,25,0xffffff);
            graphics.pose().pushPose();
            graphics.pose().translate(width/2F,height/2F+15,200);
            graphics.pose().scale(115,-115,115);
            graphics.pose().mulPose(com.mojang.math.Axis.XP.rotationDegrees(25));
            graphics.pose().mulPose(com.mojang.math.Axis.YP.rotationDegrees(45));
            graphics.pose().translate(-.5,-.5,-.5);
            var state=com.hooya.stabilizedturret.registry.ModBlocks.TIGER_I_ENGINE.get().defaultBlockState();
            var buffers=Minecraft.getInstance().renderBuffers().bufferSource();
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state,graphics.pose(),buffers,
                    net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            for(var direction:new net.minecraft.core.Direction[]{net.minecraft.core.Direction.WEST,net.minecraft.core.Direction.EAST})
                net.createmod.catnip.render.CachedBuffers.partialFacing(com.simibubi.create.AllPartialModels.SHAFT_HALF,state,direction)
                        .light(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)
                        .renderInto(graphics.pose(),buffers.getBuffer(net.minecraft.client.renderer.RenderType.solid()));
            graphics.pose().popPose();
            graphics.flush();
        }
    }
    private static final class Probe extends AbstractTickableSoundInstance {
        final String sample;final long issued=System.nanoTime();
        Probe(String sample) {
            super(ModSounds.TIGER_I.get(sample).get(),SoundSource.BLOCKS,RandomSource.create());
            this.sample=sample;looping=true;delay=0;relative=true;attenuation=SoundInstance.Attenuation.NONE;
            volume=.0001F;pitch=1;
        }
        public void tick() {}
        void halt() { stop(); }
        public boolean canStartSilent() { return true; }
    }
}
