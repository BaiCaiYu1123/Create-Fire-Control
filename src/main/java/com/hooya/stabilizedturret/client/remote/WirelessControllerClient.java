package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.remote.*;
import com.hooya.stabilizedturret.network.*;
import com.hooya.stabilizedturret.registry.ModItems;
import com.mojang.blaze3d.platform.InputConstants;
import dev.simulated_team.simulated.index.SimSoundEvents;
import net.minecraft.client.*;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import com.simibubi.create.AllSoundEvents;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import java.util.*;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class WirelessControllerClient {
    public static final KeyMapping SELECT_CHANNEL = new KeyMapping("key.create_fire_control.controller_channel",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.create_fire_control");
    private static final ControllerInputRouting ROUTING = new ControllerInputRouting();
    private static UUID session, controller;
    private static InteractionHand hand;
    private static int slot, ticks;
    private static Set<String> sent = Set.of();
    private static String exitChannel=ControllerInput.DEFAULT_EXIT;
    private static Map<String,String> chords = Map.of();
    private static Set<String> outputUses = Set.of(),liveInputs=Set.of();
    private static boolean readingPhysical;
    private static double modelEnabledAt;
    private static Set<String> modelPressed = Set.of();
    private static com.hooya.stabilizedturret.content.controller.BindingRef lectern;
    private static UUID closingController;
    private static boolean closingMounted;
    private static double closingAt, closingFrom;
    private static final LerpedFloat equipProgress=LerpedFloat.linear().startWithValue(0);
    public static void register(IEventBus bus) { bus.addListener(WirelessControllerClient::keys); }
    private static void keys(RegisterKeyMappingsEvent event) { event.register(SELECT_CHANNEL); }
    public static boolean active() { return session != null; }
    public static boolean activeFor(net.minecraft.world.item.ItemStack stack) {
        return active() && lectern==null && stack.is(ModItems.WIRELESS_CONTROLLER.get()) && controller.equals(ControllerSettings.id(stack));
    }
    private static double visualClock() {
        Minecraft mc=Minecraft.getInstance();
        return mc.level==null?0:mc.level.getGameTime()+mc.getTimer().getGameTimeDeltaPartialTick(false);
    }
    private static boolean closingFor(net.minecraft.world.item.ItemStack stack) {
        return !closingMounted&&closingController!=null&&closingController.equals(ControllerSettings.id(stack))
                &&closingFrom+(visualClock()-closingAt)/20<KeyboardControllerModel.animationLength();
    }
    public static double modelFoldTime(net.minecraft.world.item.ItemStack stack) {
        double length=KeyboardControllerModel.animationLength();
        if(activeFor(stack))return Math.max(0,length-modelElapsedSeconds());
        if(closingFor(stack))return Math.min(length,closingFrom+(visualClock()-closingAt)/20);
        return length;
    }
    public static double modelCenterProgress(net.minecraft.world.item.ItemStack stack) {
        if(!activeFor(stack)&&!closingFor(stack))return 0;
        double progress=equipProgress.getValue(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        return progress*progress*(3-2*progress);
    }
    static SimpleSoundInstance keySound() { return SimpleSoundInstance.forUI(SimSoundEvents.LINKED_TYPEWRITER_TAP.event(),1,1); }
    static SimpleSoundInstance activationSound() { return SimpleSoundInstance.forUI(AllSoundEvents.CONTROLLER_PUT.getMainEvent(),1,1); }
    public static Set<String> modelPressed() { return modelPressed; }
    public static double modelElapsedSeconds() {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || mc.level == null) return 0;
        return Math.max(0, (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false) - modelEnabledAt) / 20);
    }
    public static boolean blocks(boolean mouse, int code) {
        if (code < 0) return false;
        if (readingPhysical) return false;
        if (active() && ControllerInput.channel(mouse,code).equals(exitChannel)) return true;
        if (ROUTING.blocks(ControllerInput.channel(mouse, code))) return true;
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null && ControllerWireBridge.available() && ControllerWireClient.canSelect()
                && (mouse ? SELECT_CHANNEL.matchesMouse(code) : SELECT_CHANNEL.matches(code, GLFW.glfwGetKeyScancode(code)));
    }
    public static Component name(String channel) {
        ControllerInput input = ControllerInput.fromChannel(channel);
        if(input!=null&&input.scroll())return Component.translatable("remote.create_fire_control."+channel);
        return input == null ? Component.literal(channel) : (input.mouse() ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM)
                .getOrCreate(input.code()).getDisplayName();
    }
    private static Set<String> physicallyDown() {
        Minecraft mc = Minecraft.getInstance(); Set<String> down = new HashSet<>();
        if (!mc.isWindowActive()) return down;
        readingPhysical = true;
        try {
            long window = mc.getWindow().getWindow();
            for (ControllerInput input : ControllerInput.ALL)
                if (!input.scroll() && (input.mouse() ? GLFW.glfwGetMouseButton(window, input.code()) == GLFW.GLFW_PRESS
                        : InputConstants.isKeyDown(window, input.code()))) down.add(input.channel());
        } finally { readingPhysical = false; }
        return down;
    }
    public static void receive(WirelessControllerStatePayload payload) {
        Minecraft mc = Minecraft.getInstance(); if (mc.player == null || mc.level == null) return;
        CompoundTag data = payload.data();
        if (payload.mode() == WirelessControllerStatePayload.CONFIG) {
            stop(false);
            if(mc.screen instanceof WirelessControllerScreen screen && screen.refreshConfig(data))return;
            mc.setScreen(new WirelessControllerScreen(data)); return;
        }
        if (payload.mode() == WirelessControllerStatePayload.STOPPED) {
            if (session != null && data.hasUUID("Session") && session.equals(data.getUUID("Session"))) stop(false);
            return;
        }
        if (!data.hasUUID("Session") || !data.hasUUID("Id")) return;
        if (payload.mode() == WirelessControllerStatePayload.ACTIVE) {
            stop(false); session = data.getUUID("Session"); controller = data.getUUID("Id");
            lectern=data.contains("Lectern")?com.hooya.stabilizedturret.content.controller.BindingRef.loadTag(data.getCompound("Lectern")):null;
            hand = data.getBoolean("Offhand") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            slot = mc.player.getInventory().selected; ticks = 0; sent = Set.of();
            modelEnabledAt = mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false);
            if (mc.screen != null || !mc.isWindowActive()) { stop(true); return; }
            mc.getSoundManager().play(activationSound());
        } else if (!data.getUUID("Session").equals(session)) return;
        exitChannel=ControllerSettings.exitChannel(data);
        chords=ControllerSettings.chords(data);
        outputUses=data.contains("EffectiveOutputs") ? ControllerSessions.readStrings(data,"EffectiveOutputs") : ControllerSessions.readStrings(data,"Effective");
        Set<String> effective=new HashSet<>(ControllerSessions.readStrings(data, "Effective"));effective.remove(exitChannel);
        Set<String> physical=physicallyDown();
        modelPressed = Set.copyOf(physical);
        Set<String> added=new HashSet<>(effective);added.removeAll(liveInputs);added.retainAll(physical);
        for(String channel:added)ROUTING.suppressUntilRelease(channel);
        liveInputs=Set.copyOf(effective);
        effective.addAll(ControllerInput.DEFAULT_MOUSE_BUTTONS);
        outputUses=new HashSet<>(outputUses);outputUses.addAll(ControllerInput.DEFAULT_MOUSE_BUTTONS);
        ROUTING.updateUses(effective, physical);
        releaseMappings();
        if (payload.mode() == WirelessControllerStatePayload.ACTIVE)
            mc.player.displayClientMessage(Component.translatable("remote.create_fire_control.active",name(exitChannel)), true);
        sendInput();
    }
    /** Called before vanilla key/mouse handlers, including debug and narrator shortcuts. */
    public static boolean input(long window, boolean mouse, int code, int scanCode, int action) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow() || mc.player == null) return false;
        if (mc.screen != null) return false;
        // Visualize physical keycaps even when this key remains available to the game.
        // GLFW repeats keep a cap down without generating additional movement.
        if (active()) {
            String visualChannel = ControllerInput.channel(mouse, code);
            if (!visualChannel.isEmpty()) {
                Set<String> visual = new HashSet<>(modelPressed);
                if (action == GLFW.GLFW_RELEASE) visual.remove(visualChannel); else visual.add(visualChannel);
                modelPressed = Set.copyOf(visual);
            }
        }
        if (ControllerInput.channel(mouse,code).equals(exitChannel) && active()) {
            if (action == GLFW.GLFW_PRESS) {
                stop(true);ROUTING.suppressUntilRelease(exitChannel);releaseMappings();
            }
            return true;
        }
        if (ControllerWireBridge.available() && SELECT_CHANNEL.matchesMouse(mouse ? code : -1)
                && mouse && ControllerWireClient.canSelect()) {
            if (action == GLFW.GLFW_PRESS) openSelector(); return true;
        }
        if (!mouse && SELECT_CHANNEL.matches(code, scanCode) && ControllerWireBridge.available()
                && ControllerWireClient.canSelect()) {
            if (action == GLFW.GLFW_PRESS) openSelector(); return true;
        }
        String channel = ControllerInput.channel(mouse, code);
        Set<String> before = ControllerChords.outputs(ROUTING.pressed(), outputUses, chords, exitChannel);
        if (!ROUTING.event(channel, action != GLFW.GLFW_RELEASE)) return false;
        if (active() && action == GLFW.GLFW_PRESS) {
            Set<String> triggered = new HashSet<>(ControllerChords.outputs(ROUTING.pressed(), outputUses, chords, exitChannel));
            triggered.removeAll(before);
            for (String output : triggered)
                mc.getSoundManager().play(keySound());
        }
        KeyMapping.set((mouse ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM).getOrCreate(code), false);
        sendInput(); return true;
    }
    public static boolean scroll(long window,double delta) {
        Minecraft mc=Minecraft.getInstance();
        String channel=ControllerInput.scrollChannel(delta);
        if(window!=mc.getWindow().getWindow()||mc.player==null||mc.screen!=null||!active()||channel.isEmpty())return false;
        if(channel.equals(exitChannel)) {stop(true);return true;}
        if(!ROUTING.blocks(channel))return false;
        sendInput();
        Set<String> pulse=new HashSet<>(ROUTING.pressed());pulse.add(channel);
        if(!ControllerChords.outputs(pulse,outputUses,chords,exitChannel).equals(ControllerChords.outputs(ROUTING.pressed(),outputUses,chords,exitChannel)))
            mc.getSoundManager().play(keySound());
        CompoundTag data=new CompoundTag();data.putUUID("Session",session);data.putString("Wheel",channel);data.putInt("Steps",ControllerInput.scrollSteps(delta));
        PacketDistributor.sendToServer(new WirelessControllerActionPayload(WirelessControllerActionPayload.SCROLL,data));return true;
    }
    private static void openSelector() {
        stop(true); Minecraft.getInstance().setScreen(new ControllerChannelScreen(ControllerWireClient.selected()));
    }
    private static void sendInput() {
        if (session == null) return;
        CompoundTag data = new CompoundTag(); data.putUUID("Session", session);
        sent = ROUTING.pressed(); data.put("Pressed", ControllerSessions.strings(sent));
        if(lectern!=null)data.put("VisualPressed",ControllerSessions.strings(modelPressed));
        PacketDistributor.sendToServer(new WirelessControllerActionPayload(WirelessControllerActionPayload.INPUT, data));
    }
    public static void stop(boolean notify) {
        if(session!=null) {
            closingController=controller;closingMounted=lectern!=null;closingAt=visualClock();
            closingFrom=Math.max(0,KeyboardControllerModel.animationLength()-modelElapsedSeconds());
        }
        if (session != null && notify && Minecraft.getInstance().getConnection() != null) {
            CompoundTag data = new CompoundTag(); data.putUUID("Session", session);
            PacketDistributor.sendToServer(new WirelessControllerActionPayload(WirelessControllerActionPayload.STOP, data));
        }
        ROUTING.stop(physicallyDown()); session = null; controller = null; sent = Set.of();liveInputs=Set.of();
        modelPressed = Set.of();lectern=null; releaseMappings();
    }
    private static void releaseMappings() {
        Minecraft mc = Minecraft.getInstance();
        for (KeyMapping mapping : mc.options.keyMappings) {
            var key = mapping.getKey();
            boolean mouse = key.getType() == InputConstants.Type.MOUSE;
            if (blocks(mouse, key.getValue())) {
                mapping.setDown(false); while (mapping.consumeClick()) {}
            }
        }
    }
    private static boolean controllerStillPresent(Minecraft mc) {
        if(lectern!=null) {
            boolean present=lectern.resolve(mc.level) instanceof WirelessLecternControllerBlockEntity mounted
                    &&controller.equals(ControllerSettings.id(mounted.controller()));
            // A custom ACTIVE payload can be applied before vanilla's block-entity
            // update. Keep the server-validated session while that initial data arrives.
            if(!present&&modelElapsedSeconds()<0.5)return true;
            return present&&WirelessLecternControllerBlockEntity.playerInRange(mc.player,mc.level,lectern.pos());
        }
        return (hand!=InteractionHand.MAIN_HAND||mc.player.getInventory().selected==slot)
                &&mc.player.getItemInHand(hand).is(ModItems.WIRELESS_CONTROLLER.get())
                &&controller.equals(ControllerSettings.id(mc.player.getItemInHand(hand)));
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { session = null; controller = null; closingController=null;lectern=null;modelPressed = Set.of(); ROUTING.reset();equipProgress.startWithValue(0); return; }
        if (!mc.isPaused()) equipProgress.chase(active()&&lectern==null?1:0,0.2,LerpedFloat.Chaser.EXP).tickChaser();
        Set<String> physical = physicallyDown(); ROUTING.reconcile(physical);
        modelPressed = active() ? Set.copyOf(physical) : Set.of();
        if (session != null && (mc.screen != null || !mc.isWindowActive() || !mc.player.isAlive()
                || !controllerStillPresent(mc))) stop(true);
        if (session != null && (!sent.equals(ROUTING.pressed()) || ++ticks % 5 == 0)) sendInput();
        releaseMappings();
        if (mc.screen == null && ControllerWireBridge.available() && ControllerWireClient.canSelect()
                && mc.player.tickCount % 40 == 0)
            mc.player.displayClientMessage(Component.translatable("remote.create_fire_control.select_hint",
                    SELECT_CHANNEL.getTranslatedKeyMessage()), true);
    }
    private WirelessControllerClient() {}
}
