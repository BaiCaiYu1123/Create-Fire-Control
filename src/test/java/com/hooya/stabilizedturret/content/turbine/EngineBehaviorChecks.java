package com.hooya.stabilizedturret.content.turbine;

import com.hooya.stabilizedturret.client.EngineControlLayout;
import com.hooya.stabilizedturret.network.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class EngineBehaviorChecks {
    public static void main(String[] args) {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var chord=com.hooya.stabilizedturret.content.remote.ControllerInput.chordInputs("key_341+key_87");
        check(chord.equals(List.of("key_341","key_87")),"physical two-key expression validated");
        check(com.hooya.stabilizedturret.content.remote.ControllerInput.chordInputs("key_341+key_87+key_65").isEmpty(),"more than two keys rejected");
        check(com.hooya.stabilizedturret.content.controller.DriveByWireCompat.signal(java.util.Map.of("key_341",15,"key_87",15),"key_341+key_87")==15,"terminal chord requires both signals");
        check(com.hooya.stabilizedturret.content.controller.DriveByWireCompat.signal(java.util.Map.of("key_341",15),"key_341+key_87")==0,"incomplete terminal chord has zero output");
        activationAndTransitions();
        outgoingTransitionRates();
        explicitTransitionRules();
        instantRates();
        independentSoundRpm();
        soundSynchronizationUnderLag();
        turbineLayersUnderLag();
        com.hooya.stabilizedturret.client.EngineSoundRampChecks.verify();
        persistenceAndProtocol();
        validation();
        responsiveLayout();
        System.out.println("PASS: behavior transitions, signed independent sound RPM, release braking, NBT migration, four payloads and responsive layout");
    }
    private static EngineDriveBehavior withRules(EngineDriveBehavior behavior, EngineTransitionRule entering, EngineTransitionRule leaving) {
        return new EngineDriveBehavior(behavior.keys(),behavior.leftSpeed(),behavior.leftAcceleration(),behavior.leftDeceleration(),
                behavior.rightSpeed(),behavior.rightAcceleration(),behavior.rightDeceleration(),entering,leaving);
    }
    private static void explicitTransitionRules() {
        var a=rule(List.of("W"),256,256);
        var b=withRules(new EngineDriveBehavior(List.of("W","A"),128,200,300,400,400,500),
                EngineTransitionRule.INCOMING,EngineTransitionRule.DEFAULT);
        var drive=new EngineBehaviorController();var rules=List.of(a,b);
        drive.select(rules,Set.of("W")::contains);drive.select(rules,Set.of("W","A")::contains);
        var rates=drive.motionBehavior();
        close(EngineBehaviorController.advance(256,128,rates.leftAcceleration(),rates.leftDeceleration(),.05F),241,
                "B's explicit entry overrides A's default for left braking");
        close(EngineBehaviorController.advance(256,400,rates.rightAcceleration(),rates.rightDeceleration(),.05F),276,
                "B's explicit entry overrides A's default for right acceleration");
        var outgoing=withRules(a,EngineTransitionRule.DEFAULT,EngineTransitionRule.INCOMING);
        var defaultB=withRules(b,EngineTransitionRule.DEFAULT,EngineTransitionRule.DEFAULT);
        check(EngineTransitionRules.rates(outgoing,defaultB).equals(defaultB),"explicit departure overrides default arrival");
        check(EngineTransitionRules.rates(a,defaultB).equals(a),"two defaults preserve outgoing rates");
        drive.select(rules,Set.of()::contains);
        check(drive.motionBehavior().equals(b),"release uses last behavior rather than entry/exit rules");
        var conflictA=withRules(a,EngineTransitionRule.OUTGOING,EngineTransitionRule.OUTGOING);
        var conflictB=withRules(b,EngineTransitionRule.INCOMING,EngineTransitionRule.INCOMING);
        rules=List.of(conflictA,conflictB);drive.reset(conflictA);
        check(drive.select(rules,Set.of("W")::contains)==0,"single-key behavior starts first");
        check(drive.select(rules,Set.of("W","A")::contains)==1,"newly activated chord takes priority");
        check(drive.motionBehavior().equals(conflictB),"selected chord's explicit entry wins a conflicting exit");
        check(drive.select(rules,Set.of("W")::contains)==0,"held single-key behavior resumes after chord release");
        check(drive.motionBehavior().equals(conflictB),"resumed behavior's entry selects previous rates despite its conflicting exit");
        check(conflictA.leavingRates()==EngineTransitionRule.OUTGOING && conflictB.enteringRates()==EngineTransitionRule.INCOMING,
                "runtime conflict resolution never rewrites either saved choice");
    }
    private static void instantRates() {
        close(EngineBehaviorController.advance(0,256,1024,128,.05F),256,"1024 acceleration snaps to positive target");
        close(EngineBehaviorController.advance(0,-256,1024,128,.05F),-256,"1024 acceleration snaps to negative target");
        close(EngineBehaviorController.advance(256,0,128,1024,.05F),0,"1024 braking snaps to zero");
        close(EngineBehaviorController.advance(-256,-128,128,1024,.05F),-128,"instant braking retains signed slower target");
        close(EngineBehaviorController.advance(256,-256,1024,1024,.05F),-256,"two instant rates reverse within one tick");
        close(EngineBehaviorController.advance(256,-256,128,1024,.05F),-6.4F,"instant braking then normal reverse acceleration");
        close(EngineBehaviorController.advance(256,-256,1024,128,.05F),249.6F,"normal braking remains gradual before instant reverse acceleration");
        close(EngineBehaviorController.advance(6.4F,-256,1024,128,.05F),-256,"instant reverse acceleration when braking completes at tick boundary");
        close(EngineBehaviorController.advance(0,256,1023,128,.05F),51.15F,"1023 remains a continuous RPM-per-second rate");
        var settings=new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),true,1024,1024,256);
        var audio=new EngineSoundRpmController();audio.tick(settings,true,256,.05F);
        close(audio.rpm(),256,"independent audio uses the same instant-rate meaning");
        audio.tick(settings,true,0,.05F);close(audio.rpm(),0,"independent audio instant braking");
    }
    private static EngineDriveBehavior rule(List<String> keys, float left, float right) {
        return new EngineDriveBehavior(keys, left, 40, 60, right, 80, 100);
    }
    private static void activationAndTransitions() {
        var a = rule(List.of("W"), 256, 256);
        var b = rule(List.of("W", "A"), 128, 400);
        var c = rule(List.of("S"), -256, -128);
        var rules = List.of(a,b,c);
        var drive = new EngineBehaviorController();
        check(drive.select(rules, Set.of("W")::contains) == 0, "single key");
        check(drive.select(rules, Set.of("W","A")::contains) == 1, "W held then A switches directly to chord");
        var rates=drive.motionBehavior();
        close(EngineBehaviorController.advance(256,b.leftSpeed(),rates.leftAcceleration(),rates.leftDeceleration(),.05F),253,
                "outgoing left deceleration without reset");
        close(EngineBehaviorController.advance(256,b.rightSpeed(),rates.rightAcceleration(),rates.rightDeceleration(),.05F),260,
                "outgoing right acceleration without reset");
        check(drive.select(rules, Set.of("W","A")::contains) == 1, "held chord does not retrigger W");
        check(drive.select(rules, Set.of("W","A","S")::contains) == 2, "latest unrelated behavior wins");
        check(drive.select(rules, Set.of("W","A")::contains) == 1, "fall back to still-held chord");
        check(drive.select(rules, Set.of()::contains) == -1, "no held behavior");
        check(drive.lastBehavior().equals(b), "release retains last executed rates");
        close(EngineBehaviorController.advance(200,0,b.leftAcceleration(),b.leftDeceleration(),.05F),197,"left release braking");
        close(EngineBehaviorController.advance(200,0,b.rightAcceleration(),b.rightDeceleration(),.05F),195,"right release braking");
        drive.reset(a);
        check(drive.select(rules,Set.of("W","A")::contains)==1,"simultaneous W+A favors specific chord");
        check(drive.select(rules,Set.of("W")::contains)==0,"releasing A resumes W");
        close(EngineBehaviorController.advance(1,-100,40,100,.05F),-1.6F,"reversal consumes remainder of tick; no neutral dwell");
        close(EngineBehaviorController.advance(-1,100,40,100,.05F),1.6F,"reverse-to-forward symmetry");
        close(EngineBehaviorController.advance(300,128,100,40,.05F),298,"slower target decelerates directly");
        close(EngineBehaviorController.advance(-300,-128,100,40,.05F),-298,"negative target deceleration");
        close(EngineBehaviorController.advance(300,-128,100,0,.05F),300,"zero deceleration holds speed");
        close(EngineBehaviorController.advance(0,128,0,100,.05F),0,"zero acceleration holds speed");
        float speed=100;
        for(int i=0;i<200;i++) speed=EngineBehaviorController.advance(speed,0,128,60,.05F);
        close(speed,0,"braking reaches exact zero");
        speed=0;
        for(int i=0;i<200;i++) speed=EngineBehaviorController.advance(speed,-128,100,60,.05F);
        close(speed,-128,"reverse reaches exact target without overshoot");
    }
    private static void outgoingTransitionRates() {
        var a=new EngineDriveBehavior(List.of("W"),256,40,60,256,80,100);
        var b=new EngineDriveBehavior(List.of("W","A"),128,200,300,400,400,500);
        var c=new EngineDriveBehavior(List.of("S"),-256,600,700,-128,800,900);
        var rules=List.of(a,b,c);var drive=new EngineBehaviorController();
        drive.select(rules,Set.of("W")::contains);
        check(drive.motionBehavior().equals(a),"initial activation uses its own rates");
        drive.select(rules,Set.of("W","A")::contains);
        float left=256,right=256;
        for(int tick=0;tick<8;tick++) {
            drive.select(rules,Set.of("W","A")::contains);
            var rates=drive.motionBehavior();
            left=EngineBehaviorController.advance(left,b.leftSpeed(),rates.leftAcceleration(),rates.leftDeceleration(),.05F);
            right=EngineBehaviorController.advance(right,b.rightSpeed(),rates.rightAcceleration(),rates.rightDeceleration(),.05F);
        }
        close(left,232,"A to B keeps A's left deceleration for the complete ramp");
        close(right,288,"A to B keeps A's right acceleration for the complete ramp");
        drive.select(rules,Set.of("W","A","S")::contains);
        check(drive.motionBehavior().equals(b),"B to C during an unfinished ramp uses B's rates");
        var rates=drive.motionBehavior();
        close(EngineBehaviorController.advance(left,c.leftSpeed(),rates.leftAcceleration(),rates.leftDeceleration(),.05F),217,
                "reversal starts with outgoing B's left deceleration");
        close(EngineBehaviorController.advance(1,-100,rates.leftAcceleration(),rates.leftDeceleration(),.05F),-28F/3,
                "reversal accelerates across zero using outgoing B's acceleration");
        drive.select(rules,Set.of("W","A")::contains);
        check(drive.motionBehavior().equals(c),"fallback C to B uses C's rates");
        drive.select(rules,Set.of("W")::contains);
        check(drive.motionBehavior().equals(b),"releasing chord A returns B to A using B's rates");
        drive.select(rules,Set.of()::contains);
        check(drive.motionBehavior().equals(a),"release overrides transition rates with last behavior's deceleration");
        drive.select(rules,Set.of("W","A")::contains);
        check(drive.motionBehavior().equals(b),"activation after idle uses the newly activated behavior's own rates");
        drive.completeTransition();
        check(drive.motionBehavior().equals(b),"settled behavior restores its own rates");
        var held=new EngineDriveBehavior(List.of("H"),256,0,0,256,0,0);
        drive.reset(held);drive.select(List.of(held,b),Set.of("H")::contains);
        drive.select(List.of(held,b),Set.of("H","W","A")::contains);
        rates=drive.motionBehavior();
        close(EngineBehaviorController.advance(256,b.leftSpeed(),rates.leftAcceleration(),rates.leftDeceleration(),1),256,
                "outgoing zero deceleration holds speed despite the new behavior's nonzero deceleration");
        drive.reset(a);drive.select(rules,Set.of("W")::contains);
        drive.select(rules,Set.of("W","A")::contains);
        drive.select(rules,Set.of()::contains);
        rates=drive.motionBehavior();
        close(EngineBehaviorController.advance(232,0,rates.leftAcceleration(),rates.leftDeceleration(),.05F),217,
                "release during A to B uses last active B's braking rather than outgoing A's rates");
    }
    private static void persistenceAndProtocol() {
        var settings=new EngineControlSettings(175.5F,"buttonGuide",List.of(
                withRules(rule(List.of("buttonA"),256,256),EngineTransitionRule.DEFAULT,EngineTransitionRule.OUTGOING),
                withRules(rule(List.of("buttonA","buttonX"),-120,360),
                EngineTransitionRule.INCOMING,EngineTransitionRule.OUTGOING)),true,64,96,512);
        var tag=new CompoundTag();
        EngineSettingsCodec.save(tag,settings);
        check(EngineSettingsCodec.load(tag).equals(settings),"independent side values and chords persist");
        check(EngineSettingsCodec.load(new CompoundTag()).equals(EngineControlSettings.defaults()),"new engines have one default behavior");
        var previousVersion=tag.copy();
        for(var entry:previousVersion.getList("EngineBehaviors",10)) {
            ((CompoundTag)entry).remove("EnteringRates");((CompoundTag)entry).remove("LeavingRates");
        }
        check(EngineSettingsCodec.load(previousVersion).behaviors().stream().allMatch(b->b.enteringRates()==EngineTransitionRule.DEFAULT
                && b.leavingRates()==EngineTransitionRule.DEFAULT),"existing saves migrate both choices to default");
        var oldBehaviors=tag.copy();
        oldBehaviors.remove("SoundRpmEnabled");oldBehaviors.remove("SoundRpmAcceleration");
        oldBehaviors.remove("SoundRpmDeceleration");oldBehaviors.remove("SoundMaxRpm");
        var oldSettings=EngineSettingsCodec.load(oldBehaviors);
        check(!oldSettings.soundRpmEnabled() && oldSettings.soundRpmAcceleration()==128
                && oldSettings.soundRpmDeceleration()==128 && oldSettings.soundMaxRpm()==256,
                "existing behavior saves retain output-driven audio by default");
        var combinedRate=tag.copy();
        combinedRate.remove("SoundRpmAcceleration");combinedRate.remove("SoundRpmDeceleration");
        combinedRate.putFloat("SoundRpmRate",23);
        var splitRate=EngineSettingsCodec.load(combinedRate);
        check(splitRate.soundRpmAcceleration()==23 && splitRate.soundRpmDeceleration()==23,
                "previous combined audio rate migrates to both separate rates");
        EngineSettingsCodec.save(combinedRate,splitRate);
        check(!combinedRate.contains("SoundRpmRate"),"new saves remove the obsolete combined rate");
        var legacy=new CompoundTag();
        for(int i=0;i<5;i++) legacy.putString("AdvancedRemoteKey_"+i,List.of("buttonA","buttonB","buttonX","buttonY","buttonGuide").get(i));
        legacy.putFloat("ForwardSpeed",400); legacy.putFloat("ReverseSpeed",180);
        legacy.putFloat("ForwardAcceleration",75); legacy.putFloat("ReverseDeceleration",95);
        legacy.putFloat("SoundVolume",180);
        var migrated=EngineSettingsCodec.load(legacy);
        check(migrated.behaviors().size()==8,"old controls migrate to singles and chords");
        close(migrated.behaviors().get(0).leftSpeed(),400,"legacy forward RPM");
        close(migrated.behaviors().get(1).rightSpeed(),-180,"legacy reverse sign");
        close(migrated.behaviors().get(1).rightDeceleration(),95,"legacy braking");
        check(migrated.behaviors().get(4).keys().equals(List.of("buttonA","buttonX")),"legacy forward-left chord");
        var pos=new BlockPos(12,64,-20);
        roundTrip(OpenGasTurbinePayload.STREAM_CODEC,new OpenGasTurbinePayload(pos,true,true,settings));
        roundTrip(OpenGasTurbinePayload.STREAM_CODEC,new OpenGasTurbinePayload(pos,true,true,settings,true));
        roundTrip(OpenTigerIEnginePayload.STREAM_CODEC,new OpenTigerIEnginePayload(pos,null,true,false,settings,true));
        roundTrip(OpenZtz99aDieselEnginePayload.STREAM_CODEC,new OpenZtz99aDieselEnginePayload(pos,null,true,false,settings,true));
        roundTrip(SetGasTurbineSettingsPayload.STREAM_CODEC,new SetGasTurbineSettingsPayload(pos,settings));
        for(var id:new UUID[]{null,new UUID(2,3)}) {
            roundTrip(OpenZtz99aDieselEnginePayload.STREAM_CODEC,new OpenZtz99aDieselEnginePayload(pos,id,true,false,settings));
            roundTrip(SetZtz99aDieselEngineSettingsPayload.STREAM_CODEC,new SetZtz99aDieselEngineSettingsPayload(pos,id,settings));
        }
        var maxSettings=new EngineControlSettings(300,"",java.util.Collections.nCopies(64,
                rule(List.of("a","b","c","d","e","f","g","h"),-1024,1024)));
        roundTrip(SetGasTurbineSettingsPayload.STREAM_CODEC,new SetGasTurbineSettingsPayload(pos,maxSettings));
        for(int count:new int[]{-1,0,65,Integer.MAX_VALUE}) {
            var buffer=buffer();
            try { buffer.writeFloat(150);buffer.writeUtf("");buffer.writeBoolean(false);buffer.writeFloat(128);buffer.writeFloat(128);buffer.writeFloat(256);buffer.writeVarInt(count);
                rejected(()->EngineSettingsCodec.read(buffer));
            } finally { buffer.release(); }
        }
        for(int count:new int[]{-1,9,Integer.MAX_VALUE}) {
            var buffer=buffer();
            try { buffer.writeFloat(150);buffer.writeUtf("");buffer.writeBoolean(false);buffer.writeFloat(128);buffer.writeFloat(128);buffer.writeFloat(256);buffer.writeVarInt(1);buffer.writeVarInt(count);
                rejected(()->EngineSettingsCodec.read(buffer));
            } finally { buffer.release(); }
        }
    }
    private static void validation() {
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1025,1025})
            rejected(()->rule(List.of("W"),invalid,0));
        for(float invalid:new float[]{Float.NaN,Float.NEGATIVE_INFINITY,-1,1025}) {
            rejected(()->new EngineDriveBehavior(List.of(),0,invalid,128,0,128,128));
            rejected(()->new EngineDriveBehavior(List.of(),0,128,invalid,0,128,128));
            rejected(()->new EngineDriveBehavior(List.of(),0,128,128,0,invalid,128));
            rejected(()->new EngineDriveBehavior(List.of(),0,128,128,0,128,invalid));
        }
        rejected(()->rule(List.of("W","W"),0,0));
        rejected(()->rule(List.of(""),0,0));
        rejected(()->new EngineControlSettings(-1,"",List.of(EngineDriveBehavior.defaults())));
        rejected(()->new EngineControlSettings(150,"",List.of()));
        for(float invalid:new float[]{-1,1025,Float.NaN,Float.POSITIVE_INFINITY}) {
            rejected(()->new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),true,invalid,128,256));
            rejected(()->new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),true,128,invalid,256));
            rejected(()->new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),false,128,128,invalid));
        }
    }
    private static void independentSoundRpm() {
        var forward=rule(List.of("W"),256,256);
        var reverse=rule(List.of("S"),-128,-128);
        var mixed=rule(List.of("A"),400,-16);
        var zero=rule(List.of("Z"),0,0);
        var settings=new EngineControlSettings(150,"",List.of(forward,reverse,mixed,zero),true,40,60,300);
        var sound=new EngineSoundRpmController();
        for(var behavior:settings.behaviors())
            close(Math.abs(EngineSoundRpmController.target(settings,behavior,true)),300,"every active behavior uses independent maximum");
        close(EngineSoundRpmController.target(settings,mixed,true),-300,"either reversing side reverses audio");
        close(EngineSoundRpmController.target(settings,zero,true),300,"zero output behavior still revs audio");
        sound.tick(settings,true,EngineSoundRpmController.target(settings,forward,true),1);
        close(sound.rpm(),40,"audio rate independent of drive acceleration");
        sound.tick(settings,true,EngineSoundRpmController.target(settings,mixed,true),.05F);
        close(sound.rpm(),37,"reversal uses distinct deceleration without reset");
        sound.tick(settings,true,-300,1);
        close(sound.rpm(),-46F/3,"audio crosses zero using deceleration then acceleration");
        sound.tick(settings,true,0,.05F);
        close(sound.rpm(),-37F/3,"release uses independent sound deceleration");
        sound.tick(settings,true,0,1);
        close(sound.rpm(),0,"release reaches idle");
        for(int i=0;i<200;i++) sound.tick(settings,true,-300,.05F);
        close(sound.rpm(),-300,"reverse sound maximum without overshoot");
        sound.tick(settings,false,-300,.05F);
        close(sound.rpm(),0,"stop resets audio state");
        var off=new EngineControlSettings(150,"",settings.behaviors());
        close(EngineSoundRpmController.target(off,forward,true),0,"disabled audio simulation");
        var noAcceleration=new EngineControlSettings(150,"",settings.behaviors(),true,0,60,300);
        sound.restore(90);sound.tick(noAcceleration,true,300,1);
        close(sound.rpm(),90,"zero audio acceleration holds speed");
        sound.tick(noAcceleration,true,0,.5F);
        close(sound.rpm(),60,"deceleration still works with zero acceleration");
        var noDeceleration=new EngineControlSettings(150,"",settings.behaviors(),true,40,0,300);
        sound.tick(noDeceleration,true,0,1);
        close(sound.rpm(),60,"zero audio deceleration holds speed");
        sound.restore(0);sound.tick(noDeceleration,true,300,1);
        close(sound.rpm(),40,"acceleration still works with zero deceleration");
        sound.restore(Float.NaN);close(sound.rpm(),0,"invalid persisted audio RPM repaired");
    }
    private static void soundSynchronizationUnderLag() {
        var settings=new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),true,128,96,256);
        var csv=new StringBuilder("client_tick,server_rpm,old_client_rpm,repaired_client_rpm\n");
        int oldRewinds=0;
        for(int interval:new int[]{1,2,4}) for(int sign:new int[]{1,-1}) {
            var server=new EngineSoundRpmController();
            var client=new EngineSoundRpmController();
            var oldClient=new EngineSoundRpmController();
            client.synchronize(0,true);
            int serverTicks=0;
            float previous=0,oldPrevious=0;
            for(int tick=0;tick<400;tick++) {
                // One steady held input, both slow TPS and a temporary server stall.
                boolean serverAdvanced=tick%interval==0 && !(tick>=20 && tick<35);
                if(serverAdvanced) {
                    server.tick(settings,true,sign*256,.05F);
                    if(++serverTicks%2==0) {
                        client.synchronize(server.rpm(),false);
                        // Reproduce the previous client prediction/correction behavior.
                        if(Math.abs(server.rpm()-oldClient.rpm())>128*.15F) oldClient.restore(server.rpm());
                    }
                }
                client.tickClient(settings,true,.05F);
                oldClient.tick(settings,true,sign*256,.05F);
                check(sign*(client.rpm()-previous)>=-.001F,"held input never rewinds audio during server lag");
                check(Math.abs(client.rpm()-previous)<=6.401F,"snapshot delivery respects configured acceleration");
                if(sign*(oldClient.rpm()-oldPrevious)<-.001F) oldRewinds++;
                if(interval==1 && sign==1 && tick<100)
                    csv.append(tick).append(',').append(server.rpm()).append(',').append(oldClient.rpm())
                            .append(',').append(client.rpm()).append('\n');
                previous=client.rpm();oldPrevious=oldClient.rpm();
            }
            close(client.rpm(),sign*256,"lagged audio ultimately reaches the authoritative maximum");
            for(int tick=0;tick<240;tick++) {
                server.tick(settings,true,-sign*256,.05F);
                if(tick%2==1) client.synchronize(server.rpm(),false);
                float before=client.rpm();client.tickClient(settings,true,.05F);
                check(Math.abs(client.rpm()-before)<=6.401F,"reversal never snaps on a synchronization packet");
            }
            close(client.rpm(),-sign*256,"snapshot-following audio reverses completely");
            for(int tick=0;tick<120;tick++) {
                server.tick(settings,true,0,.05F);
                if(tick%2==1) client.synchronize(server.rpm(),false);
                client.tickClient(settings,true,.05F);
            }
            close(client.rpm(),0,"snapshot-following release reaches idle");
        }
        check(oldRewinds>0,"regression scenario reproduces the old audible RPM rewind");
        try {
            var report=java.nio.file.Path.of("build/reports/engine-sound-rpm-sync.csv");
            java.nio.file.Files.createDirectories(report.getParent());
            java.nio.file.Files.writeString(report,csv);
        } catch(java.io.IOException error) { throw new RuntimeException(error); }
        System.out.println("PASS lagged audio synchronization: old prediction rewinds="+oldRewinds+", repaired rewinds=0");
    }
    private static void turbineLayersUnderLag() {
        try {
            var type=Class.forName("com.hooya.stabilizedturret.client.GasTurbineSoundManager$EngineSoundController");
            var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
            Object mix=constructor.newInstance();
            var update=type.getDeclaredMethod("updateMixParameters",float.class,float.class,float.class,
                    float.class,boolean.class,boolean.class);update.setAccessible(true);
            var weightsField=type.getDeclaredField("rpmWeights");weightsField.setAccessible(true);
            var pitchesField=type.getDeclaredField("pitches");pitchesField.setAccessible(true);
            var settings=new EngineControlSettings(150,"",List.of(EngineDriveBehavior.defaults()),true,128,96,256);
            var server=new EngineSoundRpmController();var client=new EngineSoundRpmController();
            client.synchronize(0,true);
            int serverTicks=0,previousLayer=0,seen=0;
            float previousPitch=0;
            for(int tick=0;tick<100;tick++) {
                if(!(tick>=20 && tick<35)) {
                    server.tick(settings,true,256,.05F);
                    if(++serverTicks%2==0) client.synchronize(server.rpm(),false);
                }
                client.tickClient(settings,true,.05F);
                update.invoke(mix,client.rpm(),256F,0F,128F,true,true);
                float[] weights=(float[])weightsField.get(mix),pitches=(float[])pitchesField.get(mix);
                int layer=0;
                for(int i=1;i<4;i++) if(weights[i]>weights[layer]) layer=i;
                check(layer>=previousLayer,"production turbine layers advance low-to-mid-to-high without retreat");
                if(tick>=20 && tick<35 && layer==previousLayer)
                    check(pitches[layer]>=previousPitch-.00001F,"server stall does not falsely release acceleration pitch");
                previousLayer=layer;previousPitch=pitches[layer];seen|=1<<layer;
            }
            check(seen==15,"all four production RPM layers are traversed in order");
            check(!EngineSoundRpmController.acceleratingMagnitude(100,-256),"reversal braking has no climb boost");
            check(EngineSoundRpmController.acceleratingMagnitude(-100,-256),"reverse acceleration retains climb boost");
            check(!EngineSoundRpmController.acceleratingMagnitude(-100,0),"release has no climb boost");
            System.out.println("PASS production turbine mixer: ordered RPM layers and no false pitch release during stalled updates");
        } catch(ReflectiveOperationException error) { throw new RuntimeException(error); }
    }
    private static void responsiveLayout() {
        for(int[] size:new int[][]{{320,180},{427,240},{480,270},{640,360},{854,480},{1920,1080}}) {
            for(int count:new int[]{1,2,12,64}) {
              for(boolean audio:new boolean[]{false,true}) {
                var layout=EngineControlLayout.calculate(size[0],size[1],count,audio);
                check(layout.left()>=0 && layout.top()>=0 && layout.right()<=size[0] && layout.bottom()<=size[1],"panel fits viewport");
                check(layout.listHeight()>=20,"a full editable row remains visible");
                if(size[1]>=240) check(layout.listHeight()>=65,"initial left-side row is fully visible at normal Minecraft GUI sizes");
                check(layout.columnWidth()>=64,"numeric fields have readable width");
                check(layout.columnX(2)+layout.columnWidth()<=layout.cardLeft()+layout.cardWidth()-6,"columns fit card");
                check(layout.listBottom()<layout.bottom()-26,"scrolling does not overlap footer");
                if(count==1 && size[1]>=300) check(layout.maxScroll(count)==0,"one behavior uses compact panel");
              }
            }
        }
    }
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY); }
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value) {
        var buffer=buffer();
        try { codec.encode(buffer,value);check(value.equals(codec.decode(buffer)) && !buffer.isReadable(),"complete packet round trip"); }
        finally { buffer.release(); }
    }
    private static void close(float actual,float expected,String label) { check(Math.abs(actual-expected)<.001F,label+": "+actual+" != "+expected); }
    private static void check(boolean condition,String label) { if(!condition) throw new AssertionError(label); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid setting to be rejected");
    }
}
