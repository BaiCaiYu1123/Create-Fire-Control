package com.hooya.stabilizedturret.content.remote;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.network.*;
import com.hooya.stabilizedturret.registry.ModItems;
import com.hooya.stabilizedturret.registry.ModSounds;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.*;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ControllerSessions {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, UUID> EDITORS = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getItemStack().getItem() instanceof WirelessControllerItem)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getEntity() instanceof ServerPlayer player) use(player, event.getHand(), event.getPos());
    }

    public static void use(ServerPlayer player, InteractionHand hand, BlockPos clicked) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(ModItems.WIRELESS_CONTROLLER.get()) || player.isSpectator()) return;
        // Native item use cannot replace an active session, including sneak-use.
        Session existing = SESSIONS.get(player.getUUID());
        if (existing != null && existing.valid()) return;
        if (clicked != null) {
            if (WirelessLecternControllerBlock.placeController(player,hand,clicked)) { stop(player,true); return; }
            if (player.level().getBlockEntity(clicked) instanceof WirelessLecternControllerBlockEntity mounted) {
                if (player.isShiftKeyDown()) WirelessLecternControllerBlock.swapControllers(player,hand,mounted);
                else useLectern(player,mounted);
                return;
            }
        }
        ControllerSettings.ensureId(stack);
        stop(player, true);
        CompoundTag data = ControllerSettings.data(stack);
        // Pair directly with our own processor; wire support is an optional output extension.
        if(clicked!=null
                &&player.level().getBlockEntity(clicked) instanceof WirelessSignalProcessorBlockEntity processor) {
            EDITORS.remove(player.getUUID());
            data.put("Hub",BindingRef.capture(player.level(),clicked).save());
            data.putUUID("Processor",processor.identity());data.remove("Pairing");
            ControllerSettings.write(stack,data);player.containerMenu.broadcastChanges();
            player.level().playSound(null, clicked, ModSounds.CONTROLLER_PLUG_IN.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
            player.displayClientMessage(text("paired"),true);return;
        }
        if(data.contains("Pairing")){data.remove("Pairing");ControllerSettings.write(stack,data);}
        if (player.isShiftKeyDown()) {
            openConfig(player, hand, data);
            return;
        }
        EDITORS.remove(player.getUUID());
        Session session = new Session(player, hand, data);
        startSession(session);
    }

    public static void useLectern(ServerPlayer player,WirelessLecternControllerBlockEntity mounted) {
        if (player.isSpectator() || mounted.getLevel()!=player.level() || !mounted.controller().is(ModItems.WIRELESS_CONTROLLER.get())
                || !WirelessLecternControllerBlockEntity.playerInRange(player,player.level(),mounted.getBlockPos())) return;
        Session existing=SESSIONS.get(player.getUUID());
        if (existing!=null && existing.lectern!=null && existing.lectern.matches(player.level(),mounted.getBlockPos()) && existing.valid()) return;
        stop(player,true);EDITORS.remove(player.getUUID());
        startSession(new Session(player,InteractionHand.MAIN_HAND,ControllerSettings.data(mounted.controller()),
                BindingRef.capture(player.level(),mounted.getBlockPos())));
    }
    private static boolean startSession(Session session) {
        ServerPlayer player=session.player;
        if (session.lectern!=null && SESSIONS.values().stream().anyMatch(other -> other.level==session.level
                && other.lectern!=null && other.lectern.pos().equals(session.lectern.pos()))) {
            if (!session.apiControlled) player.displayClientMessage(text("busy"),true);return false;
        }
        if (session.hub != null && SESSIONS.values().stream().anyMatch(other -> other.level == session.level
                && Objects.equals(other.hub, session.hub))) {
            if (!session.apiControlled) player.displayClientMessage(text("busy"), true); return false;
        }
        SESSIONS.put(player.getUUID(), session);
        session.started=true;
        if (session.hub != null) com.hooya.stabilizedturret.content.controller.RemoteControlTracker.update(player, true, com.hooya.stabilizedturret.content.controller.RemoteControllerCompat.Type.WIRELESS, session.hub);
        player.containerMenu.broadcastChanges();
        session.transmitters.values().forEach(transmitter -> Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(session.level, transmitter));
        if (!session.apiControlled && session.mounted()!=null) session.mounted().beginUsing(player.getUUID());
        session.sync(WirelessControllerStatePayload.ACTIVE);
        return true;
    }
    /** Logical-only sessions share native routing, ownership and cleanup, without presentation packets. */
    public static Optional<UUID> apiStart(ServerPlayer player,InteractionHand hand,BlockPos lecternPos) {
        if (!player.isAlive() || player.isSpectator() || player.hasDisconnected()) return Optional.empty();
        BindingRef lectern=null;ItemStack stack;
        if (lecternPos!=null) {
            if (!player.serverLevel().hasChunkAt(lecternPos)
                    || !(player.level().getBlockEntity(lecternPos) instanceof WirelessLecternControllerBlockEntity mounted)
                    || !WirelessLecternControllerBlockEntity.playerInRange(player,player.level(),lecternPos)) return Optional.empty();
            lectern=BindingRef.capture(player.level(),lecternPos);stack=mounted.controller();
        } else stack=player.getItemInHand(hand);
        if (!stack.is(ModItems.WIRELESS_CONTROLLER.get())) return Optional.empty();
        UUID id=ControllerSettings.ensureId(stack);Session existing=SESSIONS.get(player.getUUID());
        if (existing!=null && !existing.valid()) { stop(player,true);existing=null; }
        if (existing!=null) {
            if (existing.apiControlled && existing.id.equals(id) && Objects.equals(existing.lectern,lectern)
                    && (lectern!=null || existing.hand==hand)) {
                existing.lastInput=existing.level.getGameTime();return Optional.of(existing.nonce);
            }
            return Optional.empty();
        }
        Session session=new Session(player,hand,ControllerSettings.data(stack),lectern,true);
        if (!startSession(session)) return Optional.empty();
        EDITORS.remove(player.getUUID());return Optional.of(session.nonce);
    }
    public record ApiState(UUID controller,UUID token,BlockPos lectern,BlockPos terminal,
                           Set<String> effective,Set<String> held,Set<String> outputs,boolean apiControlled) {}
    public static Optional<ApiState> apiState(ServerPlayer player) {
        Session session=SESSIONS.get(player.getUUID());
        if (session==null) return Optional.empty();
        if (!session.valid()) { stop(player,true);return Optional.empty(); }
        return Optional.of(new ApiState(session.id,session.nonce,session.lectern==null?null:session.lectern.pos(),
                session.hub,Set.copyOf(session.effective),Set.copyOf(session.held),Set.copyOf(session.pressed),session.apiControlled));
    }
    public static boolean apiInput(ServerPlayer player,UUID token,Set<String> physical) {
        Session session=SESSIONS.get(player.getUUID());
        if (session==null || !session.apiControlled || !session.nonce.equals(token)) return false;
        if (!session.valid() || physical.contains(ControllerSettings.exitChannel(session.data))) { stop(player,true);return false; }
        session.refresh();Set<String> accepted=new HashSet<>(physical);accepted.retainAll(session.effective);
        session.lastInput=session.level.getGameTime();session.apply(accepted);return true;
    }
    public static boolean apiStop(ServerPlayer player,UUID token) {
        Session session=SESSIONS.get(player.getUUID());
        if (session==null || !session.apiControlled || !session.nonce.equals(token)) return false;
        stop(player,true);return true;
    }
    public static boolean apiScroll(ServerPlayer player,UUID token,String channel,int steps) {
        Session session=SESSIONS.get(player.getUUID());
        if(session==null||!session.apiControlled||!session.nonce.equals(token)||!session.valid())return false;
        session.refresh();
        return session.scroll(channel,steps);
    }
    public static void apiConfigurationChanged(ServerLevel level,UUID controller) {
        if (controller==null) return;
        for (Session session:List.copyOf(SESSIONS.values()))
            if (session.level.getServer()==level.getServer() && session.id.equals(controller)) stop(session.player,true);
    }
    public static boolean apiOpenConfiguration(ServerPlayer player,InteractionHand hand) {
        ItemStack stack=player.getItemInHand(hand);
        if (player.isSpectator() || !stack.is(ModItems.WIRELESS_CONTROLLER.get())) return false;
        ControllerSettings.ensureId(stack);stop(player,true);openConfig(player,hand,ControllerSettings.data(stack));return true;
    }
    public static Optional<BlockPos> apiResolveTerminal(ServerLevel level,CompoundTag data) {
        return Optional.ofNullable(resolveHub(level,data));
    }
    public static void stopLectern(net.minecraft.world.level.Level level,BlockPos pos) {
        for(Session session:List.copyOf(SESSIONS.values()))
            if(session.level==level&&session.lectern!=null&&session.lectern.pos().equals(pos))stop(session.player,true);
    }

    public static void handle(ServerPlayer player, WirelessControllerActionPayload payload) {
        CompoundTag incoming = payload.data();
        if (payload.action() == WirelessControllerActionPayload.CONFIG) {
            Session existing = SESSIONS.get(player.getUUID());
            if (existing != null && existing.valid()) return;
            InteractionHand configHand = incoming.getBoolean("Offhand") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack held = player.getItemInHand(configHand);
            if (!player.isSpectator() && held.is(ModItems.WIRELESS_CONTROLLER.get()) && incoming.hasUUID("Id")
                    && incoming.getUUID("Id").equals(ControllerSettings.id(held))) {
                stop(player, true); openConfig(player, configHand, ControllerSettings.data(held));
            }
            return;
        }
        Session session = SESSIONS.get(player.getUUID());
        if (payload.action() == WirelessControllerActionPayload.INPUT || payload.action() == WirelessControllerActionPayload.STOP || payload.action()==WirelessControllerActionPayload.SCROLL) {
            if (session == null || session.apiControlled || !incoming.hasUUID("Session") || !session.nonce.equals(incoming.getUUID("Session"))) return;
            if (payload.action() == WirelessControllerActionPayload.STOP) { stop(player, true); return; }
            if (!session.valid()) { stop(player, true); return; }
            session.refresh();
            if(payload.action()==WirelessControllerActionPayload.SCROLL) {session.scroll(incoming.getString("Wheel"),incoming.getInt("Steps"));return;}
            Set<String> pressed = new HashSet<>();
            ListTag keys = incoming.getList("Pressed", Tag.TAG_STRING);
            if (keys.size() > ControllerInput.ALL.size()) return;
            for (int i = 0; i < keys.size(); i++) if (session.effective.contains(keys.getString(i))) pressed.add(keys.getString(i));
            session.lastInput = session.level.getGameTime();
            if (session.mounted()!=null) session.mounted().updateVisualInputs(readStrings(incoming,"VisualPressed"));
            session.apply(pressed); return;
        }
        if (!incoming.hasUUID("EditToken") || !Objects.equals(EDITORS.get(player.getUUID()), incoming.getUUID("EditToken"))) return;
        InteractionHand hand = incoming.getBoolean("Offhand") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(ModItems.WIRELESS_CONTROLLER.get()) || !incoming.hasUUID("Id")
                || !incoming.getUUID("Id").equals(ControllerSettings.id(stack))) return;
        CompoundTag data = ControllerSettings.data(stack);
        if (payload.action() == WirelessControllerActionPayload.SAVE) {
            data.putString("ExitChannel", ControllerSettings.exitChannel(incoming));
            data.putString("Color", ControllerSettings.color(incoming));
            ControllerSettings.putBindings(data, ControllerSettings.bindings(incoming, player.registryAccess()), player.registryAccess());
            ControllerSettings.write(stack, data); EDITORS.remove(player.getUUID());
        } else if (payload.action() == WirelessControllerActionPayload.UNPAIR) {
            BlockPos previousHub = resolveHub(player.serverLevel(), data);
            data.remove("Hub"); data.remove("Processor"); data.remove("Pairing");
            ControllerSettings.write(stack, data);
            if (previousHub != null) player.level().playSound(null, previousHub, ModSounds.CONTROLLER_PLUG_OUT.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
            CompoundTag state = state(player, hand, data); state.putUUID("EditToken", incoming.getUUID("EditToken"));
            PacketDistributor.sendToPlayer(player, new WirelessControllerStatePayload(WirelessControllerStatePayload.CONFIG, state));
        }
    }

    private static void openConfig(ServerPlayer player, InteractionHand hand, CompoundTag data) {
        UUID token = UUID.randomUUID(); EDITORS.put(player.getUUID(), token);
        CompoundTag state = state(player, hand, data); state.putUUID("EditToken", token);
        player.containerMenu.broadcastChanges();
        PacketDistributor.sendToPlayer(player, new WirelessControllerStatePayload(WirelessControllerStatePayload.CONFIG, state));
    }

    private static CompoundTag state(ServerPlayer player, InteractionHand hand, CompoundTag data) {
        BlockPos hub = resolveHub(player.serverLevel(), data);
        CompoundTag state = data.copy(); state.putBoolean("Offhand", hand == InteractionHand.OFF_HAND);
        Set<String> wired = hub == null ? Set.of() : ControllerWireBridge.connected(player.level(), hub);
        state.putBoolean("WireAvailable", ControllerWireBridge.available());
        state.putBoolean("HubValid", hub != null);
        state.put("Wired", strings(wired));
        state.put("Actions", strings(hub == null ? Set.of() : ControllerActionRegistry.channels(player.serverLevel(), hub)));
        return state;
    }
    private static BlockPos resolveHub(ServerLevel level, CompoundTag data) {
        if (!data.contains("Hub") || !data.hasUUID("Processor")) return null;
        BindingRef ref = BindingRef.loadTag(data.getCompound("Hub"));
        if (ref.resolve(level) instanceof WirelessSignalProcessorBlockEntity processor
                && data.getUUID("Processor").equals(processor.identity())) return ref.pos();
        if (ref.isInDimension(level)) {
            var relocated=WirelessSignalProcessorBlockEntity.findLoaded(level,data.getUUID("Processor"));
            if(relocated!=null) {
                data.put("Hub",BindingRef.capture(level,relocated.getBlockPos()).save());
                return relocated.getBlockPos();
            }
        }
        return null;
    }
    public static boolean controls(ServerPlayer player, BlockPos hub) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.valid() && session.level.getGameTime() - session.lastInput <= 30
                && Objects.equals(session.hub, hub);
    }

    /** Reads our validated session directly, without consulting or writing any wire API. */
    public static com.hooya.stabilizedturret.content.controller.NativeInputSnapshot inputSnapshot(
            net.minecraft.world.level.Level level, BlockPos hub) {
        for (Session session : SESSIONS.values()) {
            if (session.level != level || !Objects.equals(session.hub, hub) || !session.valid()
                    || level.getGameTime() - session.lastInput > 30
                    || !Objects.equals(resolveHub(session.level, session.data), hub)) continue;
            Set<String> observed = new HashSet<>(session.actions);
            observed.retainAll(session.outputs);
            Map<String, Integer> signals = new HashMap<>();
            for (String key : session.pressed) if (observed.contains(key))
                // Function mappings use key state. Redstone/cable strength (including zero) is independent.
                signals.put(key, 16);
            return new com.hooya.stabilizedturret.content.controller.NativeInputSnapshot(
                    session.nonce, signals, observed, Set.of());
        }
        return com.hooya.stabilizedturret.content.controller.NativeInputSnapshot.EMPTY;
    }
    /** Publish live mapping uses immediately when an engine/FCC is configured or rebound. */
    public static void mappingsChanged(net.minecraft.world.level.Level level, BlockPos hub) {
        if (level == null || level.isClientSide || hub == null) return;
        for (Session session : List.copyOf(SESSIONS.values()))
            if (session.level == level && Objects.equals(session.hub, hub) && session.valid()) session.refresh();
    }
    public static ListTag strings(Collection<String> strings) {
        ListTag list = new ListTag(); strings.stream().sorted().forEach(s -> list.add(StringTag.valueOf(s))); return list;
    }
    public static Set<String> readStrings(CompoundTag data, String field) {
        Set<String> result = new HashSet<>(); ListTag values = data.getList(field, Tag.TAG_STRING);
        for (int i = 0; i < Math.min(values.size(), ControllerInput.ALL.size()); i++)
            if (ControllerInput.fromChannel(values.getString(i)) != null) result.add(values.getString(i));
        return Set.copyOf(result);
    }
    private static Component text(String suffix) { return Component.translatable("remote.create_fire_control." + suffix); }
    private static void stop(ServerPlayer player, boolean notify) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) return;
        session.close();
        if (notify && !session.apiControlled) {
            CompoundTag data = new CompoundTag(); data.putUUID("Session", session.nonce);
            PacketDistributor.sendToPlayer(player, new WirelessControllerStatePayload(WirelessControllerStatePayload.STOPPED, data));
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty() && EDITORS.isEmpty()) return;
        for (Session session : List.copyOf(SESSIONS.values())) {
            if (!session.valid() || session.level.getGameTime() - session.lastInput > 30) { stop(session.player, true); continue; }
            if(!session.wheelPulses.idle())session.apply(session.held);
            if (session.level.getGameTime() % 5 == 0) {
                session.refresh();
                if (session.hub != null) com.hooya.stabilizedturret.content.controller.RemoteControlTracker.update(session.player, true, com.hooya.stabilizedturret.content.controller.RemoteControllerCompat.Type.WIRELESS, session.hub);
                else com.hooya.stabilizedturret.content.controller.RemoteControlTracker.clear(session.player);
            }
            BlockPos position = session.origin();
            for (Transmitter transmitter : session.transmitters.values()) if (!transmitter.pos.equals(position)) {
                transmitter.pos = position;
                Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(session.level, transmitter);
            }
        }
        EDITORS.keySet().removeIf(id -> event.getServer().getPlayerList().getPlayer(id) == null);
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        for (Session session : List.copyOf(SESSIONS.values())) session.close();
        SESSIONS.clear(); EDITORS.clear(); WirelessSignalProcessorBlockEntity.clearDirectory();ControllerActionRegistry.clearInputs();
    }
    private static final class Session {
        final ServerPlayer player; final ServerLevel level; final InteractionHand hand; final int slot;
        final BindingRef lectern;
        final boolean apiControlled;
        final UUID id, nonce = UUID.randomUUID(); final CompoundTag data;
        final Map<String, Transmitter> transmitters = new LinkedHashMap<>();
        BlockPos hub; Set<String> wired = Set.of(), actions = Set.of(), effective = Set.of(), pressed = Set.of();
        Set<String> outputs = Set.of(), held = Set.of();
        final Map<String,String> chords;
        final Map<String,Integer> signalStrengths = new HashMap<>();
        final ControllerPulseQueue wheelPulses=new ControllerPulseQueue();
        long lastInput; boolean alive = true,started;
        Session(ServerPlayer player, InteractionHand hand, CompoundTag data) {
            this(player,hand,data,null);
        }
        Session(ServerPlayer player, InteractionHand hand, CompoundTag data,BindingRef lectern) {
            this(player,hand,data,lectern,false);
        }
        Session(ServerPlayer player, InteractionHand hand, CompoundTag data,BindingRef lectern,boolean apiControlled) {
            this.player = player; this.level = player.serverLevel(); this.hand = hand;
            this.lectern=lectern;this.apiControlled=apiControlled;
            this.slot = player.getInventory().selected; this.data = data.copy(); this.id = data.getUUID("Id");
            this.lastInput = level.getGameTime(); this.hub = resolveHub(level, this.data);
            this.chords = ControllerSettings.chords(data);
            for (ControllerSettings.Binding binding : ControllerSettings.bindings(data, player.registryAccess())) {
                signalStrengths.put(binding.channel(), binding.signalStrength());
                if (binding.wireless() && !binding.channel().equals(ControllerSettings.exitChannel(data)))
                    transmitters.put(binding.channel(), new Transmitter(this, binding));
            }
            refresh();
        }
        boolean valid() {
            if (!player.isAlive() || player.hasDisconnected() || player.isSpectator() || player.serverLevel()!=level) return false;
            if (lectern!=null) {
                var mounted=mounted();
                return mounted!=null && id.equals(ControllerSettings.id(mounted.controller()))
                        && WirelessLecternControllerBlockEntity.playerInRange(player,level,lectern.pos());
            }
            return (hand != InteractionHand.MAIN_HAND || player.getInventory().selected == slot)
                    && player.getItemInHand(hand).is(ModItems.WIRELESS_CONTROLLER.get())
                    && id.equals(ControllerSettings.id(player.getItemInHand(hand)));
        }
        WirelessLecternControllerBlockEntity mounted() {
            return lectern!=null&&lectern.resolve(level) instanceof WirelessLecternControllerBlockEntity mounted?mounted:null;
        }
        BlockPos origin() { return lectern==null?player.blockPosition():lectern.pos(); }
        void refresh() {
            BlockPos newHub = resolveHub(level, data);
            boolean hubChanged = !Objects.equals(hub, newHub);
            if (hubChanged) {
                if (hub != null) {
                    ControllerActionRegistry.update(level,hub,Set.of());
                    ControllerWireBridge.clear(level, hub);
                }
                hub = newHub;
                if(valid()) {
                    if(mounted()!=null)mounted().updateControllerData(data);
                    else ControllerSettings.write(player.getItemInHand(hand),data);
                }
            }
            Set<String> nextWired = hub == null ? Set.of() : ControllerWireBridge.connected(level, hub);
            Set<String> nextActions = hub == null ? Set.of() : ControllerActionRegistry.channels(level, hub);
            Set<String> nextOutputs = ControllerInput.effective(transmitters.keySet(), nextWired, nextActions, ControllerSettings.exitChannel(data));
            Set<String> nextEffective = ControllerChords.physicalUses(nextOutputs, chords, ControllerSettings.exitChannel(data));
            if (hubChanged || !nextWired.equals(wired) || !nextActions.equals(actions) || !nextEffective.equals(effective) || !nextOutputs.equals(outputs)) {
                // Release removed uses before replacing their active-use sets.
                if (hub != null) for (String channel : pressed) {
                    if (wired.contains(channel) && !nextWired.contains(channel)) ControllerWireBridge.set(level, hub, channel, 0);
                }
                Set<String> previousWired = wired;
                wired = nextWired; actions = nextActions; effective = nextEffective; outputs = nextOutputs;
                Set<String> retained = new HashSet<>(held); retained.retainAll(effective); apply(retained);
                // A held key has no new edge when its terminal moves. Reapply its configured
                // strength to the new source after clearing the old source above.
                if (hubChanged && hub != null) for (String channel : pressed)
                    if (wired.contains(channel))
                        ControllerWireBridge.set(level, hub, channel,
                                signalStrengths.getOrDefault(channel, ControllerSettings.DEFAULT_SIGNAL_STRENGTH));
                if (hub != null) for (String channel : pressed)
                    if (nextWired.contains(channel) && !previousWired.contains(channel))
                        ControllerWireBridge.set(level, hub, channel,
                                signalStrengths.getOrDefault(channel, ControllerSettings.DEFAULT_SIGNAL_STRENGTH));
                if (SESSIONS.get(player.getUUID()) == this) sync(WirelessControllerStatePayload.UPDATE);
            }
        }
        boolean scroll(String channel,int steps) {
            ControllerInput input=ControllerInput.fromChannel(channel);
            if(input==null||!input.scroll()||steps<1||steps>16)return false;
            if(channel.equals(ControllerSettings.exitChannel(data))) {stop(player,true);return false;}
            if(!effective.contains(channel))return false;
            Set<String> snapshot=new HashSet<>(held);snapshot.add(channel);
            Set<String> triggered=new HashSet<>(ControllerChords.outputs(snapshot,outputs,chords,ControllerSettings.exitChannel(data)));
            triggered.removeIf(output->!output.equals(channel)&&!channel.equals(chords.get(output)));
            wheelPulses.add(triggered,steps);lastInput=level.getGameTime();apply(held);return true;
        }
        void apply(Set<String> physical) {
            held = Set.copyOf(physical);
            Set<String> next=new HashSet<>(ControllerChords.outputs(held,outputs,chords,ControllerSettings.exitChannel(data)));
            next.addAll(wheelPulses.active(level.getGameTime()));next.retainAll(outputs);
            Set<String> changed = new HashSet<>(pressed); changed.addAll(next);
            Set<String> previous = pressed;
            // Commit the native state before invoking either optional wire output or extension callbacks.
            pressed = Set.copyOf(next);
            for (String channel : changed) {
                boolean value = next.contains(channel);
                if (value == previous.contains(channel)) continue;
                int strength = value ? signalStrengths.getOrDefault(channel, ControllerSettings.DEFAULT_SIGNAL_STRENGTH) : 0;
                Transmitter transmitter = transmitters.get(channel);
                if (transmitter != null) { transmitter.value = strength;
                    Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, transmitter); }
                if (hub != null && wired.contains(channel))
                    ControllerWireBridge.set(level, hub, channel, strength);
            }
            if (started && hub!=null) ControllerActionRegistry.update(level,hub,pressed);
        }
        void sync(int mode) {
            if (apiControlled) return;
            CompoundTag state = ControllerSessions.state(player, hand, data);
            state.putUUID("Session", nonce); state.put("Effective", strings(effective));
            state.put("EffectiveOutputs", strings(outputs));
            if(lectern!=null)state.put("Lectern",lectern.save());
            PacketDistributor.sendToPlayer(player, new WirelessControllerStatePayload(mode, state));
        }
        void close() {
            com.hooya.stabilizedturret.content.controller.RemoteControlTracker.clear(player);
            wheelPulses.clear();
            apply(Set.of());
            transmitters.values().forEach(transmitter -> Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, transmitter));
            if (hub != null) ControllerWireBridge.clear(level, hub);
            if (!apiControlled && mounted()!=null)mounted().endUsing(player.getUUID());
            alive = false;
        }
    }
    private static final class Transmitter implements IRedstoneLinkable {
        final Session session; final Couple<RedstoneLinkNetworkHandler.Frequency> frequencies;
        BlockPos pos; int value;
        Transmitter(Session session, ControllerSettings.Binding binding) {
            this.session = session; pos = session.origin();
            frequencies = Couple.create(RedstoneLinkNetworkHandler.Frequency.of(binding.first()),
                    RedstoneLinkNetworkHandler.Frequency.of(binding.second()));
        }
        @Override public int getTransmittedStrength() { return value; }
        @Override public void setReceivedStrength(int value) {}
        @Override public boolean isListening() { return false; }
        @Override public boolean isAlive() { return session.alive && session.valid(); }
        @Override public Couple<RedstoneLinkNetworkHandler.Frequency> getNetworkKey() { return frequencies; }
        @Override public BlockPos getLocation() { return pos; }
    }
    private ControllerSessions() {}
}
