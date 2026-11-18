package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.RadarContactMemory;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.network.TargetLockTelemetry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** UI history comes only from server contacts that also pass the local Voxy visibility filter. */
public final class ClientRadarMemory {
    private static final RadarContactMemory<UUID, ClientRadarState.Contact> CONTROLLER = new RadarContactMemory<>(128);
    private static final Map<ConnectedDisplayBlockEntity, RadarContactMemory<ConnectedDisplayBlockEntity.TargetKey, ConnectedDisplayBlockEntity.Contact>> DISPLAYS = new WeakHashMap<>();
    private static java.lang.ref.WeakReference<ClientLevel> world = new java.lang.ref.WeakReference<>(null);
    private static Boolean aircraft;

    private static ClientLevel level() {
        ClientLevel level = Minecraft.getInstance().level;
        if (world.get() != level) { world = new java.lang.ref.WeakReference<>(level); clear(); DISPLAYS.clear(); }
        return level;
    }
    public static void clear() { CONTROLLER.clear(); aircraft = null; }
    public static List<ClientRadarState.Contact> controller(Vec3 origin, List<ClientRadarState.Contact> confirmed) {
        var level = level();
        if (level == null || !ClientRadarState.isActive()) { clear(); return List.of(); }
        boolean mode = ClientRadarState.isAircraftMode();
        if (aircraft != null && aircraft != mode) clear();
        aircraft = mode;
        return CONTROLLER.update(level.getGameTime(), mode ? 60 : 40, confirmed, ClientRadarState.Contact::id,
                (old, ticks) -> {
                    Vec3 offset = old.velocity().scale(ticks), center = old.center().add(offset);
                    var t = old.telemetry();
                    var telemetry = new TargetLockTelemetry(t.velocity(), origin.distanceTo(center), t.closingRate(), t.heightAboveGround() + offset.y);
                    return new ClientRadarState.Contact(old.id(), center, old.velocity(), old.bounds().move(offset), old.missile(), telemetry);
                });
    }
    public static boolean remembered(UUID id) { return CONTROLLER.isRemembered(id); }
    public static List<ConnectedDisplayBlockEntity.Contact> display(ConnectedDisplayBlockEntity owner, List<ConnectedDisplayBlockEntity.Contact> confirmed) {
        var level = level();
        if (level == null || owner.isRemoved() || !owner.isRadarMemoryActive()) { DISPLAYS.remove(owner); return List.of(); }
        var memory = DISPLAYS.computeIfAbsent(owner, ignored -> new RadarContactMemory<>(128));
        Vec3 origin = owner.getRadarWorldOrigin();
        return memory.update(level.getGameTime(), 40, confirmed, ConnectedDisplayBlockEntity.Contact::key,
                (old, ticks) -> {
                    Vec3 offset = old.velocity().scale(ticks), center = old.center().add(offset);
                    return new ConnectedDisplayBlockEntity.Contact(old.key(), old.members(), center, old.velocity(), old.bounds().move(offset), origin.distanceTo(center), old.closingRate(), old.missile());
                });
    }
    public static int controllerColor(UUID id, int color) { return remembered(id) ? blink(color) : color; }
    public static int displayColor(ConnectedDisplayBlockEntity owner, ConnectedDisplayBlockEntity.Contact contact, int color) {
        var memory = DISPLAYS.get(owner);
        return memory != null && memory.isRemembered(contact.key()) ? blink(color) : color;
    }
    private static int blink(int color) {
        var mc = Minecraft.getInstance();
        double time = mc.level == null ? 0 : mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(true);
        return RadarContactMemory.blinkColor(color, time);
    }
    private ClientRadarMemory() {}
}
