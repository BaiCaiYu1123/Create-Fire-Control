package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.remote.*;
import com.hooya.stabilizedturret.network.WirelessControllerStatePayload;
import com.hooya.stabilizedturret.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.*;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** First-person model, transition and keycap checks. Never included in the release. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class KeyboardControllerClientChecks {
    private static int age, stage, wait;
    private static boolean launched;
    private static CompoundTag session;
    private static Field pressed;
    private static net.minecraft.core.BlockPos lecternPos;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static java.util.concurrent.CompletableFuture<Boolean> apiCheck;
    private static com.hooya.stabilizedturret.api.remote.RemoteControllerApi.ControlHandle apiHandle;
    private static int controllerSounds,apiSoundsBefore;
    private static int previewFacing;
    private static final net.minecraft.core.Direction[] PREVIEW_FACINGS={net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.EAST,
            net.minecraft.core.Direction.SOUTH,net.minecraft.core.Direction.WEST};

    @SubscribeEvent public static void sound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        if (event.getSound()==null) return;
        var id=event.getSound().getLocation();
        if (id.equals(dev.simulated_team.simulated.index.SimSoundEvents.LINKED_TYPEWRITER_TAP.id())
                || id.equals(com.simibubi.create.AllSoundEvents.CONTROLLER_PUT.getId())) controllerSounds++;
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.controllerKeyboardChecks")) return;
        Minecraft mc = Minecraft.getInstance(); age++;
        try {
            if (age % 120 == 0) System.out.println("KEYBOARD MODEL CHECK stage=" + stage);
            if (!launched && age > 80 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && mc.screen instanceof TitleScreen && age > 80) {
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("KeyboardModel-" + System.currentTimeMillis(),
                        new LevelSettings("Keyboard model", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234L, false, false), WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.player == null || mc.level == null || stage == 99) return;
            if (stage == 0) {
                if (++wait < 70) return; wait = 0;
                mc.setScreen(null);mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.getToasts().clear();
                mc.getWindow().setWindowed(1280,720);mc.resizeDisplay();
                mc.options.hideGui = false; mc.options.bobView().set(false);
                mc.player.setYRot(0);mc.player.setXRot(0);
                var stack = ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();
                ControllerSettings.ensureId(stack);mc.player.getInventory().selected = 0;mc.player.getInventory().setItem(0,stack);
                session = ControllerSettings.data(stack);session.putUUID("Session",UUID.randomUUID());
                session.put("Effective",ControllerSessions.strings(Set.of("key_87","key_341","mouse_3")));
                pressed = WirelessControllerClient.class.getDeclaredField("modelPressed");pressed.setAccessible(true);
                checkLecternTransforms(mc);checkModels(mc);stage=1;return;
            }
            ++wait;
            if (stage == 1 && wait >= 10) {
                capture(mc,"controller-keyboard-idle");activate();wait=0;stage=2;
            } else if (stage == 2 && wait >= 10) {
                require(WirelessControllerClient.modelElapsedSeconds()<1.5,"opening animation is still playing");
                double progress=WirelessControllerClient.modelCenterProgress(mc.player.getMainHandItem());
                require(progress>.7&&progress<1,"damped hand transition approaches the center smoothly");
                capture(mc,"controller-keyboard-unfolding");
                double elapsed=WirelessControllerClient.modelElapsedSeconds();
                WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.UPDATE,session));
                require(Math.abs(WirelessControllerClient.modelElapsedSeconds()-elapsed)<0.001,"state refresh keeps animation time");
                wait=0;stage=3;
            } else if (stage == 3 && wait >= 28) {
                require(WirelessControllerClient.modelElapsedSeconds()>=1.5,"opening animation completes");
                capture(mc,"controller-keyboard-ready");checkInputs(mc);wait=0;stage=4;
            } else if (stage == 4) {
                pressed.set(null,Set.of("key_87","key_341","mouse_0","mouse_1","mouse_3","mouse_4","key_265"));
                if(wait<4)return;capture(mc,"controller-keyboard-pressed");pressed.set(null,Set.of());
                mc.options.mainHand().set(HumanoidArm.LEFT);wait=0;stage=5;
            } else if (stage == 5 && wait >= 4) {
                capture(mc,"controller-keyboard-left-hand");mc.options.mainHand().set(HumanoidArm.RIGHT);
                WirelessControllerClient.stop(false);
                var stack=mc.player.getMainHandItem();mc.player.getInventory().setItem(0,net.minecraft.world.item.ItemStack.EMPTY);
                mc.player.setItemInHand(InteractionHand.OFF_HAND,stack);session.putBoolean("Offhand",true);
                activate();wait=0;stage=6;
            } else if (stage == 6 && wait >= 38) {
                require(WirelessControllerClient.activeFor(mc.player.getOffhandItem()),"offhand controller is active");
                capture(mc,"controller-keyboard-offhand");WirelessControllerClient.stop(false);wait=0;stage=7;
                require(WirelessControllerClient.modelFoldTime(mc.player.getOffhandItem())<0.1,"exiting begins at the unfolded pose");
            } else if (stage == 7 && wait >= 10) {
                require(WirelessControllerClient.modelPressed().isEmpty(),"stopping clears depressed keycaps");
                double time=WirelessControllerClient.modelFoldTime(mc.player.getOffhandItem());
                require(time>0&&time<1.5,"exit animation advances forward before reaching the idle pose");
                capture(mc,"controller-keyboard-closing");wait=0;stage=8;
            } else if (stage == 8 && wait >= 24) {
                require(WirelessControllerClient.modelFoldTime(mc.player.getOffhandItem())==1.5,"exit finishes at the original final frame");
                capture(mc,"controller-keyboard-closed");
                mount(mc);wait=0;stage=9;
            } else if(stage==9&&wait>=40) {
                System.out.println("LECTERN CLIENT: active="+WirelessControllerClient.active()+", focus="+mc.isWindowActive()+", pos="+mc.player.position()+", target="+lecternPos+", range="+WirelessLecternControllerBlockEntity.playerInRange(mc.player,mc.level,lecternPos));
                if(mc.level.getBlockEntity(lecternPos) instanceof WirelessLecternControllerBlockEntity mounted)
                    System.out.println("LECTERN DATA: inUse="+mounted.inUse()+", id="+ControllerSettings.id(mounted.controller()));
                require(mc.level.getBlockEntity(lecternPos) instanceof WirelessLecternControllerBlockEntity mounted&&mounted.inUse(),"mounted keyboard is visible and active");
                require(WirelessControllerClient.active(),"real lectern session activates on the client");
                require(!WirelessControllerClient.activeFor(mc.player.getMainHandItem()),"lectern control does not require a held controller");
                capture(mc,"controller-keyboard-lectern");WirelessControllerClient.stop(true);wait=0;stage=10;
            } else if(stage==10&&wait>=35) {
                require(mc.level.getBlockEntity(lecternPos) instanceof WirelessLecternControllerBlockEntity mounted&&!mounted.inUse(),"mounted control ends with the client session");
                capture(mc,"controller-keyboard-lectern-idle");
                openCapture(mc);wait=0;stage=11;
            } else if(stage==11&&wait>=4) {
                capture(mc,"fire-control-combo-preview");mc.screen.keyReleased(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);mc.setScreen(null);
                reload=mc.reloadResourcePacks();wait=0;stage=12;
            } else if(stage==12&&reload.isDone()&&mc.getOverlay()==null) {
                reload.join();
                require(mc.getModelManager().getModel(net.minecraft.client.resources.model.ModelResourceLocation.standalone(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_fire_control","item/keyboard/active_001")))!=mc.getModelManager().getMissingModel(),"keyboard parts survive resource reload");
                checkModels(mc);openColors(mc);stage=13;wait=0;
            } else if(stage==13&&wait>=5) {
                capture(mc,"controller-color-picker");mc.setScreen(null);
                var stack=ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();ControllerSettings.ensureId(stack);
                var data=ControllerSettings.data(stack);data.putString("Color","red");ControllerSettings.write(stack,data);
                mc.player.getInventory().setItem(0,stack);mc.player.getInventory().selected=0;
                mc.getSingleplayerServer().execute(()->{
                    var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    player.getInventory().selected=0;player.getInventory().setItem(0,stack.copy());player.containerMenu.broadcastChanges();
                });
                session=data.copy();session.putUUID("Session",UUID.randomUUID());activate();stage=14;wait=0;
            } else if(stage==14&&wait>=38) {
                require(WirelessControllerClient.activeFor(mc.player.getMainHandItem()),"colored handheld controller remains active");
                capture(mc,"controller-keyboard-red");setColor(mc,"green");stage=15;wait=0;
            } else if(stage==15&&wait>=5) {
                require(WirelessControllerClient.activeFor(mc.player.getMainHandItem()),"palette changes keep the active item identity");
                capture(mc,"controller-keyboard-green");setColor(mc,"blue");stage=16;wait=0;
            } else if(stage==16&&wait>=5) {
                capture(mc,"controller-keyboard-blue");WirelessControllerClient.stop(false);stage=17;wait=0;
            } else if(stage==17&&wait>=35) {
                capture(mc,"controller-keyboard-blue-folded");
                apiSoundsBefore=controllerSounds;beginApiCheck(mc);stage=18;wait=0;
            } else if(stage==18&&wait>=10&&apiCheck.isDone()) {
                require(apiCheck.join(),"server starts and inputs the functional API session");
                require(!WirelessControllerClient.active(),"API session does not start the visual client controller");
                var mounted=(WirelessLecternControllerBlockEntity)mc.level.getBlockEntity(lecternPos);
                require(!mounted.inUse()&&mounted.visualPressed().isEmpty(),"API control does not synchronize animation or keycap inputs");
                require(mounted.animationTime(mc.level.getGameTime(),KeyboardControllerModel.animationLength())==KeyboardControllerModel.animationLength(),"API keeps the mounted model folded");
                require(controllerSounds==apiSoundsBefore,"API activation and input are silent");
                var playerId=mc.player.getUUID();var server=mc.getSingleplayerServer();
                apiCheck=server.submit(()->com.hooya.stabilizedturret.api.remote.RemoteControllerApi.stopControl(server.getPlayerList().getPlayer(playerId),apiHandle));
                stage=19;wait=0;
            } else if(stage==19&&wait>=4&&apiCheck.isDone()) {
                require(apiCheck.join()&&controllerSounds==apiSoundsBefore,"API stop cleans up without sound");
                previewFacing=0;previewLectern(mc);stage=20;wait=0;
            } else if(stage==20&&wait>=45&&apiCheck.isDone()) {
                require(apiCheck.join(),"oriented lectern preview is ready");
                var mounted=(WirelessLecternControllerBlockEntity)mc.level.getBlockEntity(lecternPos);
                require(mounted.inUse()&&mounted.getBlockState().getValue(WirelessLecternControllerBlock.FACING)==PREVIEW_FACINGS[previewFacing],"client renders the requested lectern orientation");
                require(mounted.animationTime(mc.level.getGameTime(),KeyboardControllerModel.animationLength())==0,"oriented lectern fully unfolds");
                capture(mc,"controller-lectern-"+PREVIEW_FACINGS[previewFacing].getSerializedName()+"-open");
                var server=mc.getSingleplayerServer();var playerId=mc.player.getUUID();
                apiCheck=server.submit(()->{((WirelessLecternControllerBlockEntity)server.overworld().getBlockEntity(lecternPos)).endUsing(playerId);return true;});
                stage=21;wait=0;
            } else if(stage==21&&wait>=40&&apiCheck.isDone()) {
                require(apiCheck.join(),"oriented lectern closes");
                capture(mc,"controller-lectern-"+PREVIEW_FACINGS[previewFacing].getSerializedName()+"-closed");
                if(++previewFacing<PREVIEW_FACINGS.length) {previewLectern(mc);stage=20;wait=0;return;}
                Files.writeString(Path.of("controller-keyboard-result.txt"),"PASS");
                System.out.println("PASS: all four lectern orientations open/closed, handheld placement, silent functional API, palettes, damping, Lightspeed reload and native animations");
                stage=99;mc.stop();
            }
        } catch(Throwable error) {
            StabilizedTurret.LOGGER.error("KEYBOARD MODEL CHECK FAILED",error);
            try {Files.writeString(Path.of("controller-keyboard-result.txt"),"FAIL: "+error);}catch(Exception ignored){}
            stage=99;mc.stop();
        }
    }

    private static void previewLectern(Minecraft mc) {
        var server=mc.getSingleplayerServer();var playerId=mc.player.getUUID();var facing=PREVIEW_FACINGS[previewFacing];
        apiCheck=server.submit(()->{
            var level=server.overworld();var player=server.getPlayerList().getPlayer(playerId);
            if(previewFacing==0)for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++) {
                var base=lecternPos.offset(dx,-1,dz);level.setBlockAndUpdate(base,net.minecraft.world.level.block.Blocks.WHITE_CONCRETE.defaultBlockState());
                for(int dy=1;dy<5;dy++)level.setBlockAndUpdate(base.above(dy),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            level.setDayTime(6000);
            level.setBlockAndUpdate(lecternPos,com.hooya.stabilizedturret.registry.ModBlocks.WIRELESS_LECTERN_CONTROLLER.get().defaultBlockState().setValue(WirelessLecternControllerBlock.FACING,facing));
            var mounted=(WirelessLecternControllerBlockEntity)level.getBlockEntity(lecternPos);
            mounted.setController(ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance());mounted.beginUsing(playerId);
            player.setItemInHand(InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);player.setItemInHand(InteractionHand.OFF_HAND,net.minecraft.world.item.ItemStack.EMPTY);player.containerMenu.broadcastChanges();
            player.connection.teleport(lecternPos.getX()+.5+facing.getStepX()*2.5,lecternPos.getY()+1,lecternPos.getZ()+.5+facing.getStepZ()*2.5,facing.toYRot()+180,32);
            return true;
        });
    }
    private static void checkLecternTransforms(Minecraft mc)throws Exception {
        var id=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_fire_control","blockstates/wireless_lectern_controller.json");
        com.google.gson.JsonObject variants;
        try(var reader=mc.getResourceManager().openAsReader(id)) {
            variants=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("variants");
        }
        PoseStack north=new PoseStack();WirelessLecternControllerRenderer.transform(north,net.minecraft.core.Direction.NORTH);
        for(var facing:net.minecraft.core.Direction.Plane.HORIZONTAL) {
            PoseStack actual=new PoseStack();WirelessLecternControllerRenderer.transform(actual,facing);
            int blockRotation=variants.getAsJsonObject("facing="+facing.getSerializedName()).get("y").getAsInt();
            var expected=new org.joml.Matrix4f().translation(.5F,0,.5F).rotateY((float)Math.toRadians(-blockRotation))
                    .translate(-.5F,0,-.5F).mul(north.last().pose());
            for(var point:List.of(new org.joml.Vector3f(),new org.joml.Vector3f(1,0,0),new org.joml.Vector3f(0,1,0),
                    new org.joml.Vector3f(0,0,1),new org.joml.Vector3f(.5F,.5F,.5F))) {
                var result=actual.last().pose().transformPosition(new org.joml.Vector3f(point));
                require(result.distance(expected.transformPosition(new org.joml.Vector3f(point)))<1e-6,"keyboard follows the baked lectern rotation for "+facing);
            }
            var center=actual.last().pose().transformPosition(new org.joml.Vector3f(.5F,.5F,.5F));
            require(center.distance(new org.joml.Vector3f(.5F+.13F*facing.getStepX(),.97F,.5F+.13F*facing.getStepZ()))<1e-6,
                    "lectern offset stays toward its facing for "+facing);
            var up=actual.last().normal().transform(new org.joml.Vector3f(0,1,0));
            require(Math.abs(up.y-(float)Math.cos(Math.toRadians(22.5)))<1e-6
                    &&Math.abs(up.x-facing.getStepX()*(float)Math.sin(Math.toRadians(22.5)))<1e-6
                    &&Math.abs(up.z-facing.getStepZ()*(float)Math.sin(Math.toRadians(22.5)))<1e-6,
                    "keyboard tilt stays aligned with the lectern top for "+facing);
        }
        System.out.println("PASS: keyboard placement and tilt match all four baked lectern orientations; scale and north/south placement preserved");
    }
    private static void mount(Minecraft mc)throws Exception {
        var stack=mc.player.getOffhandItem().copy();
        require(stack.is(ModItems.WIRELESS_CONTROLLER.get()),"offhand item is available for lectern placement");
        Field focus=Minecraft.class.getDeclaredField("windowActive");focus.setAccessible(true);focus.setBoolean(mc,true);
        mc.player.getAbilities().flying=true;
        mc.player.setItemInHand(InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);
        mc.player.setItemInHand(InteractionHand.OFF_HAND,net.minecraft.world.item.ItemStack.EMPTY);
        lecternPos=mc.player.blockPosition().south();
        mc.player.setXRot(52);mc.player.xRotO=52;
        var server=mc.getSingleplayerServer();
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(mc.player.getUUID());var level=player.serverLevel();
            player.getAbilities().flying=true;player.onUpdateAbilities();
            player.teleportTo(player.getX(),player.getY()+.6,player.getZ());
            var data=ControllerSettings.data(stack);
            ControllerSettings.putBindings(data,List.of(new ControllerSettings.Binding("key_87","Lectern",new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT),net.minecraft.world.item.ItemStack.EMPTY,true)),level.registryAccess());
            ControllerSettings.write(stack,data);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            level.setBlockAndUpdate(lecternPos,net.minecraft.world.level.block.Blocks.LECTERN.defaultBlockState());
            require(WirelessLecternControllerBlock.placeController(player,InteractionHand.MAIN_HAND,lecternPos),"server mounts the configured controller");
            player.setItemInHand(InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND,net.minecraft.world.item.ItemStack.EMPTY);player.containerMenu.broadcastChanges();
            ControllerSessions.useLectern(player,(WirelessLecternControllerBlockEntity)level.getBlockEntity(lecternPos));
            System.out.println("LECTERN SERVER: pos="+player.position()+", target="+lecternPos+", range="+WirelessLecternControllerBlockEntity.playerInRange(player,level,lecternPos));
        });
    }
    private static void openCapture(Minecraft mc)throws Exception {
        var method=ControllerClientChecks.class.getDeclaredMethod("openFireControl",Minecraft.class,int.class);method.setAccessible(true);method.invoke(null,mc,2);
        var screen=mc.screen;
        var button=(net.minecraft.client.gui.components.Button)screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button btn&&btn.getMessage().getString().startsWith(
                net.minecraft.network.chat.Component.translatable("screen.create_fire_control.input.terminal_wireless").getString())).findFirst().orElseThrow();
        button.onPress();screen.keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT,0,0);
    }

    private static void activate() throws Exception {
        Minecraft mc=Minecraft.getInstance();
        Field focus=Minecraft.class.getDeclaredField("windowActive");focus.setAccessible(true);focus.setBoolean(mc,true);
        WirelessControllerClient.receive(new WirelessControllerStatePayload(WirelessControllerStatePayload.ACTIVE,session));
        require(WirelessControllerClient.active(),"controller session starts");
    }

    private static void checkModels(Minecraft mc)throws Exception {
        for(String variant:List.of("active","folded")) {
            Field field=KeyboardControllerModel.class.getDeclaredField(variant);field.setAccessible(true);
            var model=(KeyboardControllerModel.Variant)field.get(null);
            require(model!=null,"model variant loads");
            List<KeyboardControllerModel.Part> parts=new ArrayList<>();collect(model.nodes(),parts);
            require(parts.stream().allMatch(part->mc.getModelManager().getModel(part.model())!=mc.getModelManager().getMissingModel()),"all keyboard parts are baked");
            for(String color:ControllerSettings.COLORS)for(var part:parts) {
                var baked=mc.getModelManager().getModel(KeyboardControllerModel.colored(part.model(),color));
                require(baked!=mc.getModelManager().getMissingModel(),"every animated and active palette part is baked");
                if(!color.equals("default"))require(baked.getParticleIcon().contents().name().getPath().equals("item/keyboard_"+color),"palette "+color+" model "+part.model()+" uses "+baked.getParticleIcon().contents().name());
            }
            if(variant.equals("active")) {
                require(parts.stream().filter(p->p.input().equals("key_65")).count()==1,"gamepad A is decorative");
                require(parts.stream().anyMatch(p->p.input().equals("key_265")),"D-pad maps to arrow up");
                require(parts.stream().noneMatch(p->p.input().equals("mouse_2")),"missing middle key remains without a cap");
                require(parts.stream().filter(p->p.input().startsWith("mouse_")).allMatch(p->p.depth()==0.08),"mouse depression is 0.08px");
                require(parts.stream().filter(p->p.input().startsWith("key_")).allMatch(p->p.depth()==0.3),"keyboard depression is 0.3px");
            } else require(model.length()==1.5,"original animation lasts 1.5 seconds");
        }
        var extensions=IClientItemExtensions.of(ModItems.WIRELESS_CONTROLLER.get());
        require(extensions.getCustomRenderer() instanceof KeyboardControllerRenderer,"own keyboard renderer is registered");
        require(!extensions.applyForgeHandTransform(new PoseStack(),mc.player,HumanoidArm.RIGHT,mc.player.getMainHandItem(),0,0,0),"idle item keeps regular hand placement");
        for(var sound:List.of(WirelessControllerClient.keySound(),WirelessControllerClient.activationSound())) {
            require(sound.isRelative()&&sound.getAttenuation()==net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE,"controller sounds have no directional attenuation");
            require(sound.getX()==0&&sound.getY()==0&&sound.getZ()==0,"controller sounds originate at the listener for equal left/right volume");
        }
        System.out.println("PASS: all baked keyboard parts, original animation, key mappings and depression distances");
    }

    private static void collect(List<KeyboardControllerModel.Node> nodes,List<KeyboardControllerModel.Part> parts) {
        for(var node:nodes){parts.addAll(node.parts());collect(node.children(),parts);}
    }
    private static void setColor(Minecraft mc,String color) {
        var stack=mc.player.getMainHandItem();var data=ControllerSettings.data(stack);data.putString("Color",color);ControllerSettings.write(stack,data);
        var copy=stack.copy();
        mc.getSingleplayerServer().execute(()->{
            var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            player.getInventory().setItem(0,copy);player.containerMenu.broadcastChanges();
        });
    }
    private static void beginApiCheck(Minecraft mc) {
        var server=mc.getSingleplayerServer();var playerId=mc.player.getUUID();
        apiCheck=server.submit(()->{
            var player=server.getPlayerList().getPlayer(playerId);var level=player.serverLevel();
            var key=com.hooya.stabilizedturret.api.remote.RemoteControllerApi.Key.keyboard(87);
            var config=new com.hooya.stabilizedturret.api.remote.RemoteControllerApi.Configuration(
                    com.hooya.stabilizedturret.api.remote.RemoteControllerApi.Key.keyboard(258),
                    com.hooya.stabilizedturret.api.remote.RemoteControllerApi.Color.ORIGINAL,List.of(
                    new com.hooya.stabilizedturret.api.remote.RemoteControllerApi.Binding(
                    com.hooya.stabilizedturret.api.remote.RemoteControllerApi.Chord.of(key),"API",
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET),net.minecraft.world.item.ItemStack.EMPTY,true)));
            require(com.hooya.stabilizedturret.api.remote.RemoteControllerApi.configureLectern(level,lecternPos,config),"API configures real client-side lectern");
            apiHandle=com.hooya.stabilizedturret.api.remote.RemoteControllerApi.startLecternControl(player,lecternPos).orElseThrow();
            return com.hooya.stabilizedturret.api.remote.RemoteControllerApi.setInputs(player,apiHandle,Set.of(key));
        });
    }
    private static void openColors(Minecraft mc)throws Exception {
        var stack=ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();ControllerSettings.ensureId(stack);
        var original=ControllerSettings.data(stack);
        var data=ControllerSettings.data(stack);data.putUUID("EditToken",UUID.randomUUID());
        var screen=new WirelessControllerScreen(data);mc.setScreen(screen);
        var button=(net.minecraft.client.gui.components.Button)screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals(
                net.minecraft.network.chat.Component.translatable("remote.create_fire_control.switch_color").getString())).findFirst().orElseThrow();
        var draft=WirelessControllerScreen.class.getDeclaredMethod("draft");draft.setAccessible(true);
        for(String color:List.of("red","green","blue","default")) {
            button.onPress();require(ControllerSettings.color((CompoundTag)draft.invoke(screen)).equals(color),"Switch Color cycles every supplied palette and the original");
        }
        button.onPress();
        require(ControllerSettings.data(stack).equals(original),"palette preview keeps the item unchanged until Save");
    }

    private static void checkInputs(Minecraft mc)throws Exception {
        long window=mc.getWindow().getWindow();
        mc.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_LEFT_CONTROL,0,GLFW.GLFW_PRESS,0);
        mc.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_W,0,GLFW.GLFW_PRESS,0);
        require(WirelessControllerClient.modelPressed().containsAll(Set.of("key_341","key_87")),"both chord keycaps depress together");
        mc.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_W,0,GLFW.GLFW_RELEASE,0);
        require(!WirelessControllerClient.modelPressed().contains("key_87")&&WirelessControllerClient.modelPressed().contains("key_341"),"key release only restores its own cap");
        mc.keyboardHandler.keyPress(window,GLFW.GLFW_KEY_LEFT_CONTROL,0,GLFW.GLFW_RELEASE,0);
        var mouse=MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);mouse.setAccessible(true);
        mouse.invoke(mc.mouseHandler,window,3,GLFW.GLFW_PRESS,0);
        require(WirelessControllerClient.modelPressed().contains("mouse_3"),"side button depresses its own model cap");
        mouse.invoke(mc.mouseHandler,window,3,GLFW.GLFW_RELEASE,0);
        require(!WirelessControllerClient.modelPressed().contains("mouse_3"),"side button releases");
        require(WirelessControllerClient.blocks(true,0)&&WirelessControllerClient.blocks(true,1),"main mouse buttons remain captured");
        var pose=new PoseStack();
        require(IClientItemExtensions.of(ModItems.WIRELESS_CONTROLLER.get()).applyForgeHandTransform(pose,mc.player,HumanoidArm.RIGHT,mc.player.getMainHandItem(),0,0,0),"active hand transform is applied");
        require(Math.abs(pose.last().pose().m30())<0.00001,"active item is centered horizontally");
        require(Math.abs(pose.last().pose().m31()+0.63)<0.00001,"active item is raised to the updated red-line position");
        System.out.println("PASS: combined physical keycaps, independent release, mouse caps and centered hand transform");
    }

    private static void capture(Minecraft mc,String name)throws Exception {
        Path directory=Path.of("controller-ui");Files.createDirectories(directory);
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(directory.resolve(name+".png"));}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
