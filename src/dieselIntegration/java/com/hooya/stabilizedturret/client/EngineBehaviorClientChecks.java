package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.turbine.EngineControlSettings;
import com.hooya.stabilizedturret.content.turbine.EngineDriveBehavior;
import com.hooya.stabilizedturret.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Actual Minecraft input widgets, resizing, scrolling and framebuffer screenshots. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class EngineBehaviorClientChecks {
    private static final int[][] SCENARIOS = {{854,480,1,1,0},{1280,720,3,12,1},{1920,1080,4,12,1},
            {1920,1080,3,1,1},{854,480,2,1,1},{1280,720,2,64,1}};
    private static int scenario = -1, age;
    private static boolean finished;
    private static volatile boolean screenshotDone;
    private static final StringBuilder report = new StringBuilder();

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.engineBehaviorClientChecks") || finished) return;
        var mc = Minecraft.getInstance();
        try {
            if (scenario < 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                interactionChecks(mc);
                startScenario(mc,0);
            } else if (scenario >= 0) {
                if (++age == 5) showScenario(mc);
                if (age == 20) Screenshot.grab(mc.gameDirectory, "engine_behavior_"+scenario+".png",
                        mc.getMainRenderTarget(), message -> screenshotDone = true);
                if (screenshotDone) {
                    if (scenario + 1 == SCENARIOS.length) finish(mc,null);
                    else startScenario(mc,scenario+1);
                }
            }
        } catch (Throwable error) { finish(mc,error); }
    }
    private static void startScenario(Minecraft mc,int index) {
        scenario=index;age=0;screenshotDone=false;
        int[] sample=SCENARIOS[index];
        GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),sample[0],sample[1]);
        mc.options.guiScale().set(sample[2]);
        mc.resizeDisplay();
    }
    private static void showScenario(Minecraft mc) {
        mc.resizeDisplay();
        int count=SCENARIOS[scenario][3];
        var rules=new ArrayList<EngineDriveBehavior>();
        for(int i=0;i<count;i++) rules.add(new EngineDriveBehavior(i==0?List.of("buttonA"):List.of("buttonA","buttonX"),
                i==0?256:-128,64,32,i==0?256:400,96,48));
        boolean audio=SCENARIOS[scenario][4]!=0;
        var settings=new EngineControlSettings(150,"buttonGuide",rules,audio,64,96,512);
        var initial=new OpenGasTurbinePayload(BlockPos.ZERO,scenario!=4,false,settings);
        if(scenario%2==0) ClientPayloadHandlers.openZtz99aDieselEngine(OpenZtz99aDieselEnginePayload.from(initial,null));
        else ClientPayloadHandlers.openGasTurbine(initial);
        var screen=(GasTurbineScreen)mc.screen;
        var layout=EngineControlLayout.calculate(screen.width,screen.height,count,audio);
        if(scenario==5) screen.mouseScrolled(layout.cardLeft()+20,layout.listTop()+10,0,-10000);
        var transitions=transitionButtons(screen);
        require(transitions.size()==count,"every behavior has a transition dropdown");
        for(var transition:transitions) require(transition.getWidth()==84,"compact transition button width");
        if(scenario==1 || scenario==2 || scenario==4 || scenario==5) {
            var transition=transitions.get(scenario==5?count-1:0);
            transition.onPress();
            var options=transitionOptions(screen);
            if(scenario==2) options.get(2).onPress();
            for(var option:options) require(option.getX()>=0 && option.getY()>=0
                    && option.getX()+option.getWidth()<=screen.width && option.getY()+option.getHeight()<=screen.height,
                    "dropdown options fit scaled viewport including the bottom card");
        }
        require(layout.right()<=screen.width && layout.bottom()<=screen.height,"actual scaled viewport bounds");
        require(layout.listHeight()>=20,"editable row fits actual GUI scale");
        report.append("scenario ").append(scenario).append(" physical=").append(SCENARIOS[scenario][0]).append('x')
                .append(SCENARIOS[scenario][1]).append(" GUI=").append(screen.width).append('x').append(screen.height)
                .append(" behaviors=").append(count).append(" soundRpm=").append(audio).append(" PASS\n");
    }
    private static void interactionChecks(Minecraft mc) {
        soundSnapshotChecks();
        mc.options.guiScale().set(1);mc.resizeDisplay();
        var result=new AtomicReference<SetGasTurbineSettingsPayload>();
        var screen=new GasTurbineScreen(new OpenGasTurbinePayload(BlockPos.ZERO,true,false,EngineControlSettings.defaults()),
                Component.translatable("screen.create_fire_control.gas_turbine.title"),result::set);
        mc.setScreen(screen);
        button(screen,"binding").onPress();
        screen.keyPressed(87,0,0);screen.keyPressed(65,0,0);
        screen.keyReleased(65,0,0);screen.keyReleased(87,0,0);
        require(button(screen,"binding").getMessage().getString().contains("W + A"),"actual W+A capture through terminal profile");
        var fields=fields(screen);
        fields.get(1).setValue("-300");fields.get(2).setValue("64");fields.get(3).setValue("32");
        fields.get(4).setValue("600");fields.get(5).setValue("96");fields.get(6).setValue("16");
        fields.get(2).setValue("-5");
        require(fields.get(2).getValue().equals("64"),"negative acceleration rejected by actual EditBox");
        button(screen,"sound_rpm").onPress();
        fields=fields(screen);
        require(fields.size()==10 && fields.get(4).getValue().equals("-300"),"three audio fields appear without losing behavior draft");
        fields.get(1).setValue("32");fields.get(2).setValue("96");fields.get(3).setValue("512");
        fields.get(1).setValue("-10");
        require(fields.get(1).getValue().equals("32"),"audio acceleration rejects negative values");
        fields.get(2).setValue("-96");
        require(fields.get(2).getValue().equals("96"),"audio deceleration rejects negative values");
        fields.get(3).setValue("-512");
        require(fields.get(3).getValue().equals("512"),"audio maximum is a magnitude with behavior-driven sign");
        button(screen,"sound_rpm").onPress();
        require(fields(screen).size()==7,"disabling audio hides its fields");
        button(screen,"sound_rpm").onPress();
        require(fields(screen).get(1).getValue().equals("32"),"audio draft survives toggling");
        button(screen,"add").onPress();
        transitionInteractionChecks(screen);
        mc.options.guiScale().set(2);mc.resizeDisplay();
        fields=fields(screen);
        require(fields.get(4).getValue().equals("-300") && fields.get(9).getValue().equals("16"),"draft survives add and resolution change");
        require(fields.get(1).getValue().equals("32") && fields.get(2).getValue().equals("96")
                && fields.get(3).getValue().equals("512"),"three independent audio drafts survive resize");
        var layout=EngineControlLayout.calculate(screen.width,screen.height,2,true);
        screen.mouseScrolled(layout.cardLeft()+20,layout.listTop()+10,0,-10000);
        // A hidden card must not consume a fixed footer click.
        screen.mouseClicked(layout.right()-140,layout.bottom()-17,0);
        require(result.get()!=null,"fixed Done remains clickable after scrolling");
        var saved=result.get().controls();
        require(saved.behaviors().size()==2 && saved.behaviors().getFirst().keys().equals(List.of("buttonA","buttonX")),"chord saved as terminal signals");
        require(saved.behaviors().get(1).enteringRates()==com.hooya.stabilizedturret.content.turbine.EngineTransitionRule.INCOMING
                && saved.behaviors().getFirst().leavingRates()==com.hooya.stabilizedturret.content.turbine.EngineTransitionRule.DEFAULT,
                "explicit transition draft and untouched default survive resize and save");
        require(saved.behaviors().getFirst().leftSpeed()==-300 && saved.behaviors().getFirst().rightSpeed()==600,"independent signed RPM saved");
        require(saved.behaviors().getFirst().leftDeceleration()==32 && saved.behaviors().getFirst().rightDeceleration()==16,"independent rates saved");
        require(saved.soundRpmEnabled() && saved.soundRpmAcceleration()==32
                && saved.soundRpmDeceleration()==96 && saved.soundMaxRpm()==512,"separate audio-only rates saved");
        report.append("actual widgets: chord capture, signed values, audio toggle/fields, rate validation, draft retention, add, resize, scroll and save PASS\n");
    }
    private static List<Button> transitionButtons(GasTurbineScreen screen) {
        String label=Component.translatable("screen.create_fire_control.engine_behavior.transition").getString();
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b->b.getMessage().getString().startsWith(label)).toList();
    }
    private static List<Button> transitionOptions(GasTurbineScreen screen) {
        var buttons=screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
        return buttons.subList(buttons.size()-6,buttons.size());
    }
    private static boolean selected(Button option) { return option.getMessage().getString().startsWith("✓"); }
    private static void transitionInteractionChecks(GasTurbineScreen screen) {
        var buttons=transitionButtons(screen);require(buttons.size()==2,"one dropdown per behavior");
        var binding=button(screen,"binding");
        require(binding.getWidth()<150 && binding.getX()+binding.getWidth()<buttons.getFirst().getX(),
                "binding button is shortened with a separate transition button alongside it");
        buttons.getFirst().onPress();var options=transitionOptions(screen);
        require(selected(options.get(0)) && selected(options.get(3)),"both groups initially select Default");
        screen.keyPressed(264,0,0);screen.keyPressed(262,0,0);screen.keyPressed(257,0,0);
        require(selected(options.get(4)),"explicit departure choice shown immediately");
        screen.keyPressed(256,0,0);
        buttons.get(1).onPress();options=transitionOptions(screen);
        require(selected(options.get(0)) && selected(options.get(3)),"other defaults remain defaults");
        // Use the actual mouse path; an overlay option must not activate the underlying card.
        var current=options.get(2);
        screen.mouseClicked(current.getX()+10,current.getY()+current.getHeight()/2,0);
        require(selected(options.get(2)),"explicit incoming choice overrides the default");
        screen.keyPressed(256,0,0);
        buttons.getFirst().onPress();options=transitionOptions(screen);
        require(selected(options.get(4)) && selected(options.get(0)),"conflicting explicit exit remains unchanged; default entry retained");
        options.get(3).onPress(); // Return departure to Default; preserve the other explicit choice.
        screen.keyPressed(256,0,0);
        buttons.get(1).onPress();options=transitionOptions(screen);
        require(selected(options.get(2)),"clearing another rule to default cannot override explicit entry");
        screen.mouseClicked(0,0,0);
        require(transitionButtons(screen).size()==2,"outside click closes popup without changing behaviors");
        report.append("transition dropdown: six compact choices, independent conflicting drafts, default precedence, mouse overlay, Esc/outside close and shorter binding PASS\n");
    }
    private static void soundSnapshotChecks() {
        try {
            var engine=new SnapshotTurbine();
            var settings=new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),true,64,96,256);
            var tag=new net.minecraft.nbt.CompoundTag();
            EngineSettingsCodec.save(tag,settings);
            tag.putBoolean("EngineEnabled",true);
            tag.putFloat("CurrentLeftSpeed",64);tag.putFloat("CurrentRightSpeed",64);
            tag.putFloat("SoundRpmTarget",256);tag.putFloat("SimulatedSoundRpm",128);
            engine.receive(tag);
            require(engine.getSimulatedSoundRpm()==128,"initial client packet restores signed audio state");
            var field=com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity.class.getDeclaredField("soundRpmController");
            field.setAccessible(true);
            var audio=(com.hooya.stabilizedturret.content.turbine.EngineSoundRpmController)field.get(engine);
            for(int tick=0;tick<20;tick++) audio.tickClient(engine.getControlSettings(),true,.05F);
            require(engine.getSimulatedSoundRpm()==128,"client waits for authoritative audio snapshot during a stalled server");
            tag.putFloat("SimulatedSoundRpm",134.4F);engine.receive(tag);
            require(engine.getSimulatedSoundRpm()==128,"subsequent block-entity packet does not snap audio RPM");
            audio.tickClient(engine.getControlSettings(),true,.05F);
            require(Math.abs(engine.getSoundRpmMagnitude()-131.2F)<.001F,"block-entity audio getter follows independent acceleration");
            audio.tickClient(engine.getControlSettings(),true,.05F);
            require(Math.abs(engine.getSoundRpmMagnitude()-134.4F)<.001F,"continuous client ramp reaches snapshot");
            tag.putFloat("SoundRpmTarget",-256);tag.putFloat("SimulatedSoundRpm",124.8F);engine.receive(tag);
            require(Math.abs(engine.getSimulatedSoundRpm()-134.4F)<.001F,"direction-change packet preserves current audio RPM");
            audio.tickClient(engine.getControlSettings(),true,.05F);
            require(Math.abs(engine.getSimulatedSoundRpm()-129.6F)<.001F,"client reversal uses separate deceleration");
            require(engine.getCurrentLeftSpeed()==64 && engine.getCurrentRightSpeed()==64,
                    "audio synchronization does not modify actual transmitted RPM");
            report.append("actual client block-entity NBT: initial restore, no prediction rewind, continuous audio rates and separate physical output PASS\n");
            soundMixRampChecks();
        } catch(ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static void soundMixRampChecks() throws ReflectiveOperationException {
        var type=Class.forName("com.hooya.stabilizedturret.client.GasTurbineSoundManager$EngineSoundController");
        var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        var update=type.getDeclaredMethod("updateMix",com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity.class,
                boolean.class);update.setAccessible(true);
        var climb=type.getDeclaredField("smoothedClimb");climb.setAccessible(true);
        var pitch=type.getDeclaredField("pitches");pitch.setAccessible(true);
        var envelope=type.getDeclaredField("masterEnvelope");envelope.setAccessible(true);
        var audioField=com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity.class.getDeclaredField("soundRpmController");
        audioField.setAccessible(true);
        var settings=new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),true,128,128,256);
        for(int sign:new int[]{1,-1}) {
            var engine=new SnapshotTurbine();
            var tag=new net.minecraft.nbt.CompoundTag();EngineSettingsCodec.save(tag,settings);
            tag.putBoolean("EngineEnabled",true);
            tag.putFloat("CurrentLeftSpeed",64);tag.putFloat("CurrentRightSpeed",64);
            engine.receive(tag);
            var client=(com.hooya.stabilizedturret.content.turbine.EngineSoundRpmController)audioField.get(engine);
            var server=new com.hooya.stabilizedturret.content.turbine.EngineSoundRpmController();
            Object mix=constructor.newInstance();envelope.setFloat(mix,1);
            for(int tick=0;tick<220;tick++) {
                float target=tick>=100 && tick<180?sign*256:0;
                server.tick(settings,true,target,.05F);
                if(tick%2==1) {
                    tag.putFloat("SoundRpmTarget",target);tag.putFloat("SimulatedSoundRpm",server.rpm());engine.receive(tag);
                }
                client.tickClient(settings,true,.05F);
                update.invoke(mix,engine,true);
                require(climb.getFloat(mix)==0,"live independent mix has no secondary climb envelope");
                if(engine.getSoundRpmMagnitude()>=255.999F && tick<180)
                    require(Math.abs(((float[])pitch.get(mix))[3]-1)<.00001F,"live held maximum does not relax from a higher pitch");
                require(engine.getCurrentLeftSpeed()==64 && engine.getCurrentRightSpeed()==64,
                        "live audio ramp does not change transmission RPM");
            }
        }
        report.append("actual client 128/256 mixer entry: idle, held forward/reverse, steady maximum and release without pitch overshoot PASS\n");
    }
    private static final class SnapshotTurbine extends com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity {
        private SnapshotTurbine() {
            super(BlockPos.ZERO,com.hooya.stabilizedturret.registry.ModBlocks.GAS_TURBINE.get().defaultBlockState());
        }
        private void receive(net.minecraft.nbt.CompoundTag tag) { read(tag,net.minecraft.core.RegistryAccess.EMPTY,true); }
    }
    private static List<EditBox> fields(GasTurbineScreen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
    }
    private static Button button(GasTurbineScreen screen,String key) {
        String label=Component.translatable("screen.create_fire_control.engine_behavior."+key).getString();
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b->b.getMessage().getString().startsWith(label)).findFirst().orElseThrow();
    }
    private static void require(boolean condition,String label) { if(!condition) throw new AssertionError(label); }
    private static void finish(Minecraft mc,Throwable error) {
        finished=true;
        if(error!=null) StabilizedTurret.LOGGER.error("ENGINE BEHAVIOR CLIENT FAILED",error);
        try {
            Files.writeString(Path.of("engine-behavior-client-result.txt"),error==null?"PASS":"FAIL: "+error);
            Files.writeString(Path.of("engine-behavior-client-report.txt"),report.toString());
        } catch(Exception failure) { throw new RuntimeException(failure); }
        mc.stop();
    }
}
