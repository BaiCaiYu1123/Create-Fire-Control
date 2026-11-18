package com.hooya.stabilizedturret.content.remote;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Live fire-control uses plus extension providers. Providers must return only live uses. */
public final class ControllerActionRegistry {
    public interface Provider {
        Set<String> channels(ServerLevel level, BlockPos processor);
        void input(ServerLevel level, BlockPos processor, String channel, boolean pressed);
    }
    private static final List<Provider> PROVIDERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private record Target(ServerLevel level,BlockPos processor) {}
    private static final Map<Provider,Map<Target,Set<String>>> HELD = new IdentityHashMap<>();
    private static final Map<Target,Long> UPDATES = new HashMap<>();
    private static long revision;
    public static void register(Provider provider) { PROVIDERS.add(Objects.requireNonNull(provider)); }
    public static void unregister(Provider provider) {
        var held=HELD.getOrDefault(provider,Map.of());
        for (Target target:held.keySet()) if (!target.level().getServer().isSameThread())
            throw new IllegalStateException("Unregister an active controller provider on the server thread");
        PROVIDERS.remove(provider);HELD.remove(provider);
        for (var entry:held.entrySet()) for (String key:entry.getValue())
            provider.input(entry.getKey().level(),entry.getKey().processor(),key,false);
    }
    public static Set<String> channels(ServerLevel level, BlockPos pos) {
        Set<String> channels = new HashSet<>();
        for (Provider provider : PROVIDERS) channels.addAll(provider.channels(level, pos));
        channels.addAll(KeyboardControllerInputs.channels(level, pos));
        channels.removeIf(channel -> ControllerInput.fromChannel(channel) == null);
        return Set.copyOf(channels);
    }
    static void update(ServerLevel level,BlockPos pos,Set<String> pressed) {
        Target target=new Target(level,pos.immutable());
        long current=++revision;UPDATES.put(target,current);
        for (Provider provider:List.copyOf(PROVIDERS)) {
            if (!Objects.equals(UPDATES.get(target),current)) break;
            if (!PROVIDERS.contains(provider)) continue;
            Set<String> previous=HELD.getOrDefault(provider,Map.of()).getOrDefault(target,Set.of());
            Set<String> next=new HashSet<>(provider.channels(level,pos));next.retainAll(pressed);
            Set<String> released=new HashSet<>(previous);released.removeAll(next);
            Set<String> activated=new HashSet<>(next);activated.removeAll(previous);
            for (String key:released) if (Objects.equals(UPDATES.get(target),current)
                    && mark(provider,target,key,false)) provider.input(level,pos,key,false);
            for (String key:activated) if (Objects.equals(UPDATES.get(target),current)
                    && PROVIDERS.contains(provider) && mark(provider,target,key,true)) provider.input(level,pos,key,true);
        }
        UPDATES.remove(target,current);
    }
    private static boolean mark(Provider provider,Target target,String key,boolean pressed) {
        Map<Target,Set<String>> targets=HELD.computeIfAbsent(provider,p->new HashMap<>());
        Set<String> values=new HashSet<>(targets.getOrDefault(target,Set.of()));
        boolean changed=pressed?values.add(key):values.remove(key);
        if (values.isEmpty()) targets.remove(target);else targets.put(target,Set.copyOf(values));
        if (targets.isEmpty()) HELD.remove(provider);
        return changed;
    }
    static void clearInputs() {
        UPDATES.clear();
        for (Provider provider:List.copyOf(HELD.keySet())) {
            var targets=HELD.remove(provider);
            if (targets==null) continue;
            for (var entry:targets.entrySet()) for (String key:entry.getValue())
                provider.input(entry.getKey().level(),entry.getKey().processor(),key,false);
        }
    }
    private ControllerActionRegistry() {}
}
