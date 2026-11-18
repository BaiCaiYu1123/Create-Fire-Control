package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.WeaponViewPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import java.util.function.Supplier;

/** Observes existing flights without adding entities, tickets, or a server camera. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AirborneWeaponViewHandler {
    public record Pose(ResourceLocation type, Vec3 position, Vec3 velocity) {}
    private record Shot(UUID id, Supplier<Pose> pose) {}
    private static final class State {
        final WeaponViewSelection<Shot> selection = new WeaponViewSelection<>();
        ServerPlayer viewer;
        UUID sentId;
    }
    private static final Map<StabilizerControllerBlockEntity, State> STATES = new IdentityHashMap<>();
    private static final ThreadLocal<StabilizerControllerBlockEntity> LAUNCH = new ThreadLocal<>();

    public static boolean launch(StabilizerControllerBlockEntity controller, Supplier<Boolean> fire) {
        var previous = LAUNCH.get();
        LAUNCH.set(controller);
        try { return fire.get(); }
        finally { if (previous == null) LAUNCH.remove(); else LAUNCH.set(previous); }
    }

    static StabilizerControllerBlockEntity launchingController() { return LAUNCH.get(); }

    /** Called by the controller after its inputs tick, including a hold before the first shot. */
    static void observeControllerInput(StabilizerControllerBlockEntity controller) {
        if (!STATES.containsKey(controller) && controller.getWeaponViewOperator() != null)
            STATES.put(controller, new State());
    }

    static void launched(StabilizerControllerBlockEntity controller, UUID id, Supplier<Pose> pose) {
        if (controller == null || controller.getLevel() == null || controller.getLevel().isClientSide) return;
        STATES.computeIfAbsent(controller, ignored -> new State()).selection.launched(new Shot(id, pose));
    }

    static void launched(StabilizerControllerBlockEntity controller, Entity missile) {
        launched(controller, missile.getUUID(), () -> missile.isRemoved() ? null : new Pose(
                BuiltInRegistries.ENTITY_TYPE.getKey(missile.getType()),
                MianbaoMissileCompat.getWorldPosition(missile), MianbaoMissileCompat.getWorldVelocity(missile)));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (STATES.isEmpty()) return;
        var iterator = STATES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var controller = entry.getKey();
            State state = entry.getValue();
            Shot latest = state.selection.latest();
            if (latest != null && latest.pose.get() == null) state.selection.removed(latest);
            ServerPlayer viewer = controller.isRemoved() ? null : controller.getWeaponViewOperator();
            if (viewer != state.viewer) {
                leave(state);
                state.selection.hold(false);
                state.viewer = viewer;
            }
            state.selection.hold(viewer != null);
            Shot shot = state.selection.selected();
            Pose pose = shot == null ? null : shot.pose.get();
            if (shot != null && pose == null) state.selection.removed(shot);
            if (viewer != null && pose != null) {
                PacketDistributor.sendToPlayer(viewer, new WeaponViewPayload(viewer.level().dimension().location(),
                        shot.id, pose.type, pose.position, pose.velocity, viewer.level().getGameTime()));
                state.sentId = shot.id;
            } else if (state.sentId != null) leave(state);
            if (controller.isRemoved() || controller.getLevel() == null
                    || (viewer == null && state.selection.latest() == null)) iterator.remove();
        }
    }

    private static void leave(State state) {
        if (state.viewer != null && state.sentId != null) PacketDistributor.sendToPlayer(state.viewer,
                new WeaponViewPayload(state.viewer.level().dimension().location(), null, null, Vec3.ZERO, Vec3.ZERO, 0));
        state.sentId = null;
    }

    @SubscribeEvent public static void stop(ServerStoppingEvent event) { STATES.clear(); LAUNCH.remove(); }
    private AirborneWeaponViewHandler() {}
}
