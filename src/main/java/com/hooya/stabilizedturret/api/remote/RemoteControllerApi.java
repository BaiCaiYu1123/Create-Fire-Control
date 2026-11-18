package com.hooya.stabilizedturret.api.remote;

import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.remote.*;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.hooya.stabilizedturret.registry.ModItems;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public server-side integration surface for the full-keyboard wireless controller.
 * World operations require the server thread. Logical sessions do not send animation,
 * keycap or sound packets. A session expires after 30 ticks without input/keepAlive.
 * See docs/REMOTE_CONTROLLER_API.md for dependency setup and complete examples.
 */
public final class RemoteControllerApi {
    public static final int API_LEVEL = 1;

    /** GLFW key codes and zero-based mouse buttons; no client GLFW dependency is needed. */
    public record Key(boolean mouse,int code) {
        public Key {
            if (ControllerInput.channel(mouse,code).isEmpty()) throw new IllegalArgumentException("Unsupported controller key: "+mouse+":"+code);
        }
        public static Key keyboard(int code) { return new Key(false,code); }
        public static Key mouse(int button) { return new Key(true,button); }
        public static Key scroll(boolean up) { return new Key(true,up?ControllerInput.SCROLL_UP:ControllerInput.SCROLL_DOWN); }
        public static Key parse(String channel) {
            var input=ControllerInput.fromChannel(Objects.requireNonNull(channel));
            if (input==null) throw new IllegalArgumentException("Unknown controller input: "+channel);
            return new Key(input.mouse(),input.code());
        }
        public String channel() { return ControllerInput.channel(mouse,code); }
    }
    public record Chord(List<Key> keys) {
        public Chord {
            keys=List.copyOf(keys);
            if (keys.isEmpty() || keys.size()>2 || new HashSet<>(keys).size()!=keys.size())
                throw new IllegalArgumentException("A controller chord needs one or two distinct keys");
        }
        public static Chord of(Key first,Key... second) {
            List<Key> keys=new ArrayList<>();keys.add(first);keys.addAll(List.of(second));return new Chord(keys);
        }
        public Key primary() { return keys.getFirst(); }
        public Optional<Key> secondary() { return keys.size()==2?Optional.of(keys.get(1)):Optional.empty(); }
        public String channel() { return String.join("+",keys.stream().map(Key::channel).toList()); }
    }
    public enum Color {
        ORIGINAL("default"),RED_YELLOW("red"),PURPLE_GREEN("green"),BLUE_RED("blue");
        private final String id;
        Color(String id) { this.id=id; }
        public String id() { return id; }
        public Color next() { return values()[(ordinal()+1)%values().length]; }
        private static Color from(String id) { return Arrays.stream(values()).filter(c->c.id.equals(id)).findFirst().orElse(ORIGINAL); }
    }
    /** Defensive copies prevent a caller from changing frequencies through a configuration snapshot. */
    public record Binding(Chord input,String label,ItemStack first,ItemStack second,boolean enabled,int signalStrength) {
        public Binding(Chord input,String label,ItemStack first,ItemStack second,boolean enabled) {
            this(input,label,first,second,enabled,ControllerSettings.DEFAULT_SIGNAL_STRENGTH);
        }
        public Binding {
            Objects.requireNonNull(input);Objects.requireNonNull(label);
            if (label.length()>48) throw new IllegalArgumentException("Controller labels are limited to 48 characters");
            if (signalStrength<0 || signalStrength>ControllerSettings.MAX_SIGNAL_STRENGTH)
                throw new IllegalArgumentException("Controller signal strength must be between 0 and 16");
            first=ControllerSettings.normalize(Objects.requireNonNull(first));second=ControllerSettings.normalize(Objects.requireNonNull(second));
        }
        @Override public ItemStack first() { return first.copy(); }
        @Override public ItemStack second() { return second.copy(); }
        @Override public boolean equals(Object value) {
            return value instanceof Binding other && enabled==other.enabled && signalStrength==other.signalStrength && input.equals(other.input)
                    && label.equals(other.label) && ItemStack.matches(first,other.first) && ItemStack.matches(second,other.second);
        }
        @Override public int hashCode() { return Objects.hash(input,label,first.getItem(),second.getItem(),enabled,signalStrength); }
    }
    public record Configuration(Key exit,Color color,List<Binding> bindings) {
        public Configuration {
            Objects.requireNonNull(exit);Objects.requireNonNull(color);bindings=List.copyOf(bindings);
            Set<Key> keys=new HashSet<>();
            for (Binding binding:bindings) if (!keys.add(binding.input().primary()))
                throw new IllegalArgumentException("Two bindings cannot have the same primary key");
        }
        public static Configuration defaults() { return new Configuration(Key.parse(ControllerInput.DEFAULT_EXIT),Color.ORIGINAL,List.of()); }
    }
    public record Terminal(ResourceLocation dimension,BlockPos position,UUID identity) {
        public Terminal { Objects.requireNonNull(dimension);position=position.immutable();Objects.requireNonNull(identity); }
    }
    public record ControlHandle(UUID player,UUID token) {
        public ControlHandle { Objects.requireNonNull(player);Objects.requireNonNull(token); }
    }
    public record ControlState(ControlHandle handle,UUID controller,Optional<BlockPos> lectern,
                               Optional<Terminal> terminal,Set<Key> effectiveInputs,Set<Key> heldInputs,
                               Set<Key> activeOutputs,boolean apiControlled) {
        public ControlState {
            lectern=lectern.map(BlockPos::immutable);effectiveInputs=Set.copyOf(effectiveInputs);
            heldInputs=Set.copyOf(heldInputs);activeOutputs=Set.copyOf(activeOutputs);
        }
    }
    public enum FireControlAction {
        SCOPE(0),THERMAL(1),OPTICAL_LOCK(2),ANTI_TANK_MISSILE(3),ANTI_AIR_MISSILE(4),RADAR_LOCK(5),
        AIRCRAFT_WEAPON(6),CYCLE_AIRCRAFT_WEAPON(7),CYCLE_AIRCRAFT_RADAR_TARGET(8),POD_VIEW(9),
        AIR_TO_GROUND_LOCK(10),COUNTERMEASURE(11),FREE_LOOK(12),TELEVISION_GUIDANCE(13),MACHINE_GUN(14),LASER_RANGE(15),
        CYCLE_AIRCRAFT_WEAPON_UP(16),CYCLE_GROUND_RADAR_TARGET(43),WEAPON_VIEW(45);
        private final int index;
        FireControlAction(int index) { this.index=index; }
    }
    public interface ActionProvider {
        /** Return currently available uses on this terminal, not all physically supported keys. */
        Set<Key> inputs(ServerLevel level,BlockPos terminal);
        /** Called on state changes; release is also delivered when a use or provider is removed. */
        void input(ServerLevel level,BlockPos terminal,Key key,boolean pressed);
    }
    public interface Registration extends AutoCloseable {
        ResourceLocation id();
        /** Dispose on the server thread while a server is running. Closing twice is harmless. */
        @Override void close();
    }
    private static final Map<ResourceLocation,ControllerActionRegistry.Provider> PROVIDERS=new ConcurrentHashMap<>();

    public static List<Key> supportedInputs() { return ControllerInput.ALL.stream().map(k->new Key(k.mouse(),k.code())).toList(); }
    public static boolean isController(ItemStack stack) { return stack!=null && stack.is(ModItems.WIRELESS_CONTROLLER.get()); }
    public static ItemStack createController() {
        ItemStack stack=ModItems.WIRELESS_CONTROLLER.get().getDefaultInstance();ControllerSettings.ensureId(stack);return stack;
    }
    public static Optional<UUID> identity(ItemStack stack) { requireController(stack);return Optional.ofNullable(ControllerSettings.id(stack)); }
    public static Configuration configuration(ItemStack stack,HolderLookup.Provider registries) {
        requireController(stack);CompoundTag data=ControllerSettings.data(stack);
        List<Binding> bindings=ControllerSettings.bindings(data,registries).stream().map(b->{
            Key first=Key.parse(b.channel());Chord chord=b.secondInput().isEmpty()?Chord.of(first):Chord.of(first,Key.parse(b.secondInput()));
            return new Binding(chord,b.label(),b.first(),b.second(),b.enabled(),b.signalStrength());
        }).toList();
        return new Configuration(Key.parse(ControllerSettings.exitChannel(data)),Color.from(ControllerSettings.color(data)),bindings);
    }
    /** Changes settings and releases any old session for this identity. Pairing and custom data survive. */
    public static void configure(ServerLevel level,ItemStack stack,Configuration config) {
        thread(level);requireController(stack);Objects.requireNonNull(config);
        UUID id=ControllerSettings.ensureId(stack);ControllerSessions.apiConfigurationChanged(level,id);
        CompoundTag data=ControllerSettings.data(stack);data.putString("ExitChannel",config.exit().channel());data.putString("Color",config.color().id());
        List<ControllerSettings.Binding> bindings=config.bindings().stream().map(b->new ControllerSettings.Binding(
                b.input().primary().channel(),b.label(),b.first(),b.second(),b.enabled(),b.input().secondary().map(Key::channel).orElse(""),b.signalStrength())).toList();
        ControllerSettings.putBindings(data,bindings,level.registryAccess());ControllerSettings.write(stack,data);
    }
    public static boolean configureHeld(ServerPlayer player,InteractionHand hand,Configuration config) {
        thread(player.serverLevel());ItemStack stack=player.getItemInHand(hand);if (!isController(stack)) return false;
        configure(player.serverLevel(),stack,config);player.containerMenu.broadcastChanges();return true;
    }
    public static boolean configureLectern(ServerLevel level,BlockPos pos,Configuration config) {
        thread(level);var mounted=lectern(level,pos);if (mounted==null || !isController(mounted.controller())) return false;
        configure(level,mounted.controller(),config);mounted.updateControllerData(ControllerSettings.data(mounted.controller()));return true;
    }
    /** Full item serialization preserves controller identity, pairing, frequencies, chords and color. */
    public static CompoundTag saveController(ItemStack stack,HolderLookup.Provider registries) {
        requireController(stack);return (CompoundTag)stack.saveOptional(registries);
    }
    public static ItemStack loadController(CompoundTag saved,HolderLookup.Provider registries) {
        ItemStack stack=ItemStack.parseOptional(registries,saved.copy());requireController(stack);ControllerSettings.ensureId(stack);return stack;
    }
    public static Optional<Terminal> terminal(ServerLevel level,BlockPos pos) {
        thread(level);
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof WirelessSignalProcessorBlockEntity processor)) return Optional.empty();
        return Optional.of(new Terminal(level.dimension().location(),pos,processor.identity()));
    }
    /** Resolves the persisted identity; a replacement terminal at the same position never inherits it. */
    public static Optional<Terminal> boundTerminal(ServerLevel level,ItemStack stack) {
        thread(level);requireController(stack);
        return ControllerSessions.apiResolveTerminal(level,ControllerSettings.data(stack)).flatMap(pos->terminal(level,pos));
    }
    public static boolean bindTerminal(ServerLevel level,ItemStack stack,BlockPos pos) {
        thread(level);requireController(stack);Optional<Terminal> target=terminal(level,pos);if (target.isEmpty()) return false;
        ControllerSessions.apiConfigurationChanged(level,ControllerSettings.ensureId(stack));
        CompoundTag data=ControllerSettings.data(stack);data.put("Hub",BindingRef.capture(level,pos).save());
        data.putUUID("Processor",target.get().identity());data.remove("Pairing");ControllerSettings.write(stack,data);return true;
    }
    public static void unbindTerminal(ServerLevel level,ItemStack stack) {
        thread(level);requireController(stack);ControllerSessions.apiConfigurationChanged(level,ControllerSettings.id(stack));
        CompoundTag data=ControllerSettings.data(stack);data.remove("Hub");data.remove("Processor");data.remove("Pairing");ControllerSettings.write(stack,data);
    }
    public static boolean bindHeldTerminal(ServerPlayer player,InteractionHand hand,BlockPos pos) {
        thread(player.serverLevel());ItemStack stack=player.getItemInHand(hand);
        if (!isController(stack) || !bindTerminal(player.serverLevel(),stack,pos)) return false;
        player.containerMenu.broadcastChanges();return true;
    }
    public static boolean unbindHeldTerminal(ServerPlayer player,InteractionHand hand) {
        thread(player.serverLevel());ItemStack stack=player.getItemInHand(hand);if (!isController(stack)) return false;
        unbindTerminal(player.serverLevel(),stack);player.containerMenu.broadcastChanges();return true;
    }
    public static boolean bindLecternTerminal(ServerLevel level,BlockPos lectern,BlockPos terminal) {
        thread(level);var mounted=lectern(level,lectern);
        if (mounted==null || !isController(mounted.controller()) || !bindTerminal(level,mounted.controller(),terminal)) return false;
        mounted.updateControllerData(ControllerSettings.data(mounted.controller()));return true;
    }
    public static boolean unbindLecternTerminal(ServerLevel level,BlockPos pos) {
        thread(level);var mounted=lectern(level,pos);if (mounted==null || !isController(mounted.controller())) return false;
        unbindTerminal(level,mounted.controller());mounted.updateControllerData(ControllerSettings.data(mounted.controller()));return true;
    }
    public static Set<Key> wiredInputs(ServerLevel level,BlockPos pos) {
        thread(level);return terminal(level,pos).isEmpty()?Set.of():keys(ControllerWireBridge.connected(level,pos));
    }
    public static Set<Key> actionInputs(ServerLevel level,BlockPos pos) {
        thread(level);return terminal(level,pos).isEmpty()?Set.of():keys(ControllerActionRegistry.channels(level,pos));
    }
    public static boolean wiresAvailable() { return ControllerWireBridge.available(); }
    public static boolean connectWire(ServerLevel level,BlockPos terminal,BlockPos device,Direction side,Key key) {
        thread(level);Objects.requireNonNull(side);Objects.requireNonNull(key);
        if (terminal(level,terminal).isEmpty() || !level.hasChunkAt(device) || level.getBlockState(device).isAir() || terminal.equals(device)) return false;
        return ControllerWireBridge.connect(level,terminal,device,side,key.channel());
    }
    public static boolean disconnectWire(ServerLevel level,BlockPos terminal,BlockPos device,Direction side,Key key) {
        thread(level);Objects.requireNonNull(side);Objects.requireNonNull(key);
        if (terminal(level,terminal).isEmpty()) return false;
        return ControllerWireBridge.disconnect(level,terminal,device,side,key.channel());
    }
    public static boolean bindFireControl(ServerLevel level,BlockPos computer,BlockPos terminal) {
        thread(level);var fcc=computer(level,computer);if (fcc==null || terminal(level,terminal).isEmpty()) return false;
        fcc.bind(BindingSlot.WIRELESS_CONTROLLER_HUB,BindingRef.capture(level,terminal));return true;
    }
    public static boolean unbindFireControl(ServerLevel level,BlockPos computer) {
        thread(level);var fcc=computer(level,computer);if (fcc==null) return false;
        var ref=fcc.getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB);
        return ref!=null && fcc.unbindDevice(BindingSlot.WIRELESS_CONTROLLER_HUB,0,ref.pos());
    }
    public static Optional<Terminal> fireControlTerminal(ServerLevel level,BlockPos computer) {
        thread(level);var fcc=computer(level,computer);if (fcc==null) return Optional.empty();
        var ref=fcc.getBinding(BindingSlot.WIRELESS_CONTROLLER_HUB);
        return ref!=null && ref.isSameTarget(level)?terminal(level,ref.pos()):Optional.empty();
    }
    public static boolean mapFireControl(ServerLevel level,BlockPos computer,FireControlAction action,Chord input) {
        thread(level);Objects.requireNonNull(action);Objects.requireNonNull(input);var fcc=computer(level,computer);
        return fcc!=null && fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS,action.index,input.channel());
    }
    public static boolean clearFireControlMapping(ServerLevel level,BlockPos computer,FireControlAction action) {
        thread(level);Objects.requireNonNull(action);var fcc=computer(level,computer);
        return fcc!=null && fcc.setRemoteKey(RemoteControllerCompat.Type.WIRELESS,action.index,"");
    }
    public static Map<FireControlAction,Chord> fireControlMappings(ServerLevel level,BlockPos computer) {
        thread(level);var fcc=computer(level,computer);if (fcc==null) return Map.of();
        var values=fcc.getRemoteKeys(RemoteControllerCompat.Type.WIRELESS);var result=new EnumMap<FireControlAction,Chord>(FireControlAction.class);
        for (FireControlAction action:FireControlAction.values()) {
            var inputs=ControllerInput.chordInputs(values.get(action.index));
            if (!inputs.isEmpty()) result.put(action,new Chord(inputs.stream().map(Key::parse).toList()));
        }
        return Map.copyOf(result);
    }
    /** Includes gas turbines, ZTZ99A engines and eight-cylinder engines derived from the turbine. */
    public static boolean bindEngine(ServerLevel level,BlockPos engine,BlockPos terminal) {
        thread(level);
        return terminal(level,terminal).isPresent() && level.hasChunkAt(engine)
                && level.getBlockEntity(engine) instanceof GasTurbineBlockEntity be && be.bindAdvancedHub(BindingRef.capture(level,terminal));
    }
    public static Optional<Terminal> engineTerminal(ServerLevel level,BlockPos engine) {
        thread(level);
        if (!level.hasChunkAt(engine) || !(level.getBlockEntity(engine) instanceof GasTurbineBlockEntity be)
                || be.getTerminalType()!=RemoteControllerCompat.Type.WIRELESS || !be.hasValidAdvancedHub()) return Optional.empty();
        return terminal(level,be.getAdvancedHub().pos());
    }
    public static boolean unbindEngine(ServerLevel level,BlockPos engine) {
        thread(level);
        if (!level.hasChunkAt(engine) || !(level.getBlockEntity(engine) instanceof GasTurbineBlockEntity be)
                || be.getTerminalType()!=RemoteControllerCompat.Type.WIRELESS) return false;
        be.unbindAdvancedHub();return true;
    }
    /** Place without sound. The placed item remains folded until ordinary player control starts. */
    public static boolean mount(ServerPlayer player,InteractionHand hand,BlockPos pos) {
        thread(player.serverLevel());
        if (player.isSpectator() || !player.serverLevel().hasChunkAt(pos) || !WirelessLecternControllerBlockEntity.playerInRange(player,player.level(),pos)) return false;
        return WirelessLecternControllerBlock.placeController(player,hand,pos,false);
    }
    public static Optional<ItemStack> lecternController(ServerLevel level,BlockPos pos) {
        thread(level);var mounted=lectern(level,pos);
        return mounted==null || !isController(mounted.controller())?Optional.empty():Optional.of(mounted.controller().copy());
    }
    /** Returns the configured item to the caller; neither duplicates nor spawns an extra drop. */
    public static Optional<ItemStack> retrieve(ServerPlayer player,BlockPos pos) {
        thread(player.serverLevel());var mounted=lectern(player.serverLevel(),pos);
        if (player.isSpectator() || !player.mayBuild() || mounted==null || !isController(mounted.controller())
                || !WirelessLecternControllerBlockEntity.playerInRange(player,player.level(),pos)) return Optional.empty();
        ItemStack item=mounted.controller().copy();var previous=mounted.getBlockState();mounted.setController(ItemStack.EMPTY);
        if (!player.serverLevel().setBlockAndUpdate(pos,Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING,
                previous.getValue(WirelessLecternControllerBlock.FACING)))) { mounted.setController(item);return Optional.empty(); }
        return Optional.of(item);
    }
    public static boolean swapLectern(ServerPlayer player,InteractionHand hand,BlockPos pos) {
        thread(player.serverLevel());var mounted=lectern(player.serverLevel(),pos);var held=player.getItemInHand(hand);
        if (player.isSpectator() || !player.mayBuild() || mounted==null || !isController(held) || !isController(mounted.controller())
                || !WirelessLecternControllerBlockEntity.playerInRange(player,player.level(),pos)) return false;
        var previous=mounted.controller().copy();mounted.setController(held);player.setItemInHand(hand,previous);player.containerMenu.broadcastChanges();return true;
    }
    public static Optional<ControlHandle> startControl(ServerPlayer player,InteractionHand hand) {
        thread(player.serverLevel());return ControllerSessions.apiStart(player,hand,null).map(token->new ControlHandle(player.getUUID(),token));
    }
    public static Optional<ControlHandle> startLecternControl(ServerPlayer player,BlockPos pos) {
        thread(player.serverLevel());return ControllerSessions.apiStart(player,InteractionHand.MAIN_HAND,pos).map(token->new ControlHandle(player.getUUID(),token));
    }
    public static Optional<ControlState> controlState(ServerPlayer player) {
        thread(player.serverLevel());
        return ControllerSessions.apiState(player).map(s->new ControlState(new ControlHandle(player.getUUID(),s.token()),s.controller(),
                Optional.ofNullable(s.lectern()),Optional.ofNullable(s.terminal()).flatMap(p->terminal(player.serverLevel(),p)),
                keys(s.effective()),keys(s.held()),keys(s.outputs()),s.apiControlled()));
    }
    /** Queue wheel notches; each notch produces its own press/release pulse. */
    public static boolean scroll(ServerPlayer player,ControlHandle handle,boolean up,int steps) {
        thread(player.serverLevel());Objects.requireNonNull(handle);
        if(steps<1||steps>16)throw new IllegalArgumentException("Scroll steps must be between 1 and 16");
        return handle.player().equals(player.getUUID())&&ControllerSessions.apiScroll(player,handle.token(),Key.scroll(up).channel(),steps);
    }
    /** Supply physical held keys; the existing routing calculates combos and all functional outputs. */
    public static boolean setInputs(ServerPlayer player,ControlHandle handle,Collection<Key> held) {
        thread(player.serverLevel());Objects.requireNonNull(handle);Objects.requireNonNull(held);
        if (!handle.player().equals(player.getUUID())) return false;
        if (held.size()>ControllerInput.ALL.size()) throw new IllegalArgumentException("Too many controller inputs");
        Set<String> channels=new HashSet<>();for (Key key:held) channels.add(Objects.requireNonNull(key).channel());
        return ControllerSessions.apiInput(player,handle.token(),channels);
    }
    public static boolean press(ServerPlayer player,ControlHandle handle,Key key) { return changeInput(player,handle,key,true); }
    public static boolean release(ServerPlayer player,ControlHandle handle,Key key) { return changeInput(player,handle,key,false); }
    public static boolean keepAlive(ServerPlayer player,ControlHandle handle) {
        var state=controlState(player).filter(s->s.handle().equals(handle)&&s.apiControlled());
        return state.isPresent() && setInputs(player,handle,state.get().heldInputs());
    }
    public static boolean stopControl(ServerPlayer player,ControlHandle handle) {
        thread(player.serverLevel());Objects.requireNonNull(handle);
        return handle.player().equals(player.getUUID()) && ControllerSessions.apiStop(player,handle.token());
    }
    /** Explicit native settings UI; this does not activate control. */
    public static boolean openConfiguration(ServerPlayer player,InteractionHand hand) {
        thread(player.serverLevel());return ControllerSessions.apiOpenConfiguration(player,hand);
    }
    public static Registration registerActions(ResourceLocation id,ActionProvider provider) {
        Objects.requireNonNull(id);Objects.requireNonNull(provider);
        ControllerActionRegistry.Provider adapter=new ControllerActionRegistry.Provider() {
            @Override public Set<String> channels(ServerLevel level,BlockPos pos) {
                var channels=new HashSet<String>();for (Key key:Objects.requireNonNull(provider.inputs(level,pos))) channels.add(key.channel());return channels;
            }
            @Override public void input(ServerLevel level,BlockPos pos,String key,boolean pressed) { provider.input(level,pos,Key.parse(key),pressed); }
        };
        if (PROVIDERS.putIfAbsent(id,adapter)!=null) throw new IllegalArgumentException("Controller provider already registered: "+id);
        ControllerActionRegistry.register(adapter);
        return new Registration() {
            @Override public ResourceLocation id() { return id; }
            @Override public void close() {
                var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server!=null && !server.isSameThread()) throw new IllegalStateException("Close a controller registration on the server thread");
                if (PROVIDERS.remove(id,adapter)) ControllerActionRegistry.unregister(adapter);
            }
        };
    }
    private static boolean changeInput(ServerPlayer player,ControlHandle handle,Key key,boolean down) {
        Objects.requireNonNull(key);var state=controlState(player).filter(s->s.handle().equals(handle)&&s.apiControlled());
        if (state.isEmpty()) return false;Set<Key> inputs=new HashSet<>(state.get().heldInputs());
        if (down) inputs.add(key);else inputs.remove(key);return setInputs(player,handle,inputs);
    }
    private static Set<Key> keys(Collection<String> channels) {
        var result=new HashSet<Key>();for (String channel:channels) result.add(Key.parse(channel));return Set.copyOf(result);
    }
    private static void requireController(ItemStack stack) {
        if (!isController(stack)) throw new IllegalArgumentException("Expected a create_fire_control wireless controller");
    }
    private static void thread(ServerLevel level) {
        Objects.requireNonNull(level);if (!level.getServer().isSameThread()) throw new IllegalStateException("Call the controller API on the server thread");
    }
    private static WirelessLecternControllerBlockEntity lectern(ServerLevel level,BlockPos pos) {
        return level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof WirelessLecternControllerBlockEntity be?be:null;
    }
    private static StabilizerControllerBlockEntity computer(ServerLevel level,BlockPos pos) {
        return level.hasChunkAt(pos)&&level.getBlockEntity(pos) instanceof StabilizerControllerBlockEntity be?be:null;
    }
    private RemoteControllerApi() {}
}
