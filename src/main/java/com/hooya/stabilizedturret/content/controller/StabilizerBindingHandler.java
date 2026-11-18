package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlockEntity;
import com.hooya.stabilizedturret.client.ClientBindingState;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.hooya.stabilizedturret.content.loader.MzLoaderAutomation;
import com.hooya.stabilizedturret.content.loader.CannonAutoLoader;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Highest-priority wrench interception. While a binding session exists the
 * click is always consumed, including invalid targets, so Create never rotates
 * the bearing/chair/sight underneath the operation.
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class StabilizerBindingHandler {
    private static final long BINDING_TIMEOUT_TICKS = 20L * 60L * 10L;
    private static final Map<UUID, PendingBinding> PENDING = new HashMap<>();
    private static final ThreadLocal<Vec3> BINDING_OBSERVER=new ThreadLocal<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !PENDING.containsKey(player.getUUID())) return;
        PendingBinding pending = PENDING.get(player.getUUID());
        if (pending == null) return;
        if (pending.expiresAt() < player.level().getGameTime()
                || !pending.source().isInDimension(player.level())
                || !AllItems.WRENCH.isIn(player.getMainHandItem())
                && !AllItems.WRENCH.isIn(player.getOffhandItem())) {
            cancelBinding(player, false);
            return;
        }
        if(player.tickCount%40==0) {
            Object source=pending.source().resolve(player.level());
            if(source instanceof StabilizerControllerBlockEntity || source instanceof CannonAutoLoader)
                sendCandidates(player,bindingPayloadFor(player,source));
            else cancelBinding(player,false);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PendingBinding pending=PENDING.remove(player.getUUID());if(pending!=null)pending.lease().close();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide) {
            ControllerTracker.invalidateBindingsAt(level, event.getPos());
            com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity
                    .invalidateBindingsAt(level, event.getPos());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos clickedPos = event.getPos();
        boolean wrench = AllItems.WRENCH.isIn(event.getItemStack());
        if (level.isClientSide) {
            // Client-side cancellation must NOT call protectedBearing() ->
            // findBoundAssembledBearing() -> Sable.HELPER.getContaining().
            // That call resolves Sable sublevel bindings on the render thread
            // and blocks indefinitely when a sublevel is mid-physics-sync
            // (e.g. immediately after CBC-AT projectile impacts), causing the
            // game to freeze when right-clicking any bearing part while holding
            // a Create linked controller or Create Tweaked Controllers advanced
            // remote.
            //
            // Use the Sable-API-free fast check to classify the click. Block
            // items must always pass through: cancelling this event prevents
            // Minecraft from even attempting placement (and also suppresses
            // the normal block-placement sound). Only empty-hand and wrench
            // interactions require bearing protection.
            if (wrench && (level.getBlockEntity(clickedPos) instanceof StabilizerControllerBlockEntity
                    || level.getBlockEntity(clickedPos) instanceof CannonAutoLoader
                    || ClientBindingState.isActive())) {
                consume(event);
                return;
            }
            if (isBearingPart(level, clickedPos)) {
                if (event.getItemStack().getItem() instanceof BlockItem) {
                    return;
                }
                boolean boundBearing = ControllerTracker.isBoundBearingFast(level, clickedPos);
                boolean emptyHand = event.getItemStack().isEmpty();
                boolean sneak = event.getEntity() != null && event.getEntity().isShiftKeyDown();
                if (!emptyHand && !wrench) {
                    return;
                }
                if (boundBearing) {
                    // Bound+assembled dual-stabilizer bearing: only wrench+sneak
                    // passes through to the server, which calls
                    // bearing.disassemble() (cancel assembly) instead of Sable's
                    // default wrench dismantle. Everything else is cancelled so
                    // Sable never rotates the angle from a stray click.
                    if (!(wrench && sneak)) {
                        consume(event);
                    }
                }
            }
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        PendingBinding pending = PENDING.get(player.getUUID());

        if (wrench && level.getBlockEntity(clickedPos) instanceof StabilizerControllerBlockEntity controller) {
            AutoDefenseDisplayBindingHandler.cancel(player);
            BindingRef controllerRef = BindingRef.capture(level, clickedPos);
            if (pending != null && pending.kind() == BindingKind.FIRE_CONTROL
                    && pending.source().equals(controllerRef)) {
                cancelBinding(player, false);
                player.displayClientMessage(
                        Component.translatable("message.create_fire_control.binding.cancelled"),
                        true
                );
            } else {
                if(pending!=null)pending.lease().close();
                PENDING.put(player.getUUID(), new PendingBinding(
                        BindingKind.FIRE_CONTROL, controllerRef,
                        level.getGameTime() + BINDING_TIMEOUT_TICKS,new BindingSourceLease(player.serverLevel(),controllerRef)));
                sendCandidates(player,bindingPayloadFor(player,controller));
                player.displayClientMessage(
                        Component.translatable("message.create_fire_control.binding.select_module"),
                        true
                );
            }
            consume(event);
            return;
        }

        if (wrench && level.getBlockEntity(clickedPos) instanceof CannonAutoLoader loader) {
            AutoDefenseDisplayBindingHandler.cancel(player);
            BindingRef loaderRef = BindingRef.capture(level, clickedPos);
            if (pending != null && pending.kind() == BindingKind.CANNON_LOADER
                    && pending.source().equals(loaderRef)) {
                cancelBinding(player, false);
                player.displayClientMessage(
                        Component.translatable("message.create_fire_control.binding.cancelled"), true);
            } else {
                if(pending!=null)pending.lease().close();
                PENDING.put(player.getUUID(), new PendingBinding(
                        BindingKind.CANNON_LOADER, loaderRef,
                        level.getGameTime() + BINDING_TIMEOUT_TICKS,new BindingSourceLease(player.serverLevel(),loaderRef)));
                sendCandidates(player,bindingPayloadFor(player,loader));
                player.displayClientMessage(Component.translatable(
                        "message.create_fire_control.loader_binding.select_mount"), true);
            }
            consume(event);
            return;
        }

        if (wrench && pending != null) {
            // A binding click must never fall through to Create/Aeronautics.
            consume(event);
            if (pending.expiresAt() < level.getGameTime()) {
                cancelBinding(player, false);
                player.displayClientMessage(Component.translatable(
                        "message.create_fire_control.binding.cancelled"), true);
                return;
            }
            if (pending.kind() == BindingKind.CANNON_LOADER) {
                if (!(pending.source().resolve(level) instanceof CannonAutoLoader loader)) {
                    cancelBinding(player, false);
                    player.displayClientMessage(Component.translatable(
                            "message.create_fire_control.binding.cancelled"), true);
                    return;
                }
                if (!MzLoaderAutomation.isSupportedMount(level, clickedPos)) {
                    sendCandidates(player,bindingPayloadFor(player,loader));
                    player.displayClientMessage(Component.translatable(
                            "message.create_fire_control.loader_binding.invalid_mount"), true);
                    return;
                }
                loader.bindCannonMount(BindingRef.capture(level, clickedPos));
                PENDING.put(player.getUUID(),new PendingBinding(pending.kind(),pending.source(),level.getGameTime()+BINDING_TIMEOUT_TICKS,pending.lease()));
                sendCandidates(player,bindingPayloadFor(player,loader));
                player.displayClientMessage(Component.translatable(
                        "message.create_fire_control.loader_binding.success"), true);
                return;
            }
            if (!(pending.source().resolve(level) instanceof StabilizerControllerBlockEntity controller)) {
                cancelBinding(player, false);
                player.displayClientMessage(
                        Component.translatable("message.create_fire_control.binding.cancelled"),
                        true
                );
                return;
            }

            BindingSlot detected = detectBindingSlot(level, clickedPos);
            if (detected == null) {
                sendCandidates(player,bindingPayloadFor(player,controller));
                player.displayClientMessage(
                        Component.translatable("message.create_fire_control.binding.invalid_auto"),
                        true
                );
                return;
            }

            BindingRef target = BindingRef.capture(level, clickedPos);
            if (detected == BindingSlot.BEARING
                    && level.getBlockState(clickedPos).getBlock() instanceof SwivelBearingBlock bearingBlock) {
                BindingSlot defaultRole = bearingBlock.getRotationAxis(level.getBlockState(clickedPos))
                        == net.minecraft.core.Direction.Axis.Y ? BindingSlot.YAW : BindingSlot.PITCH;
                // Every newly bound swivel bearing is assigned from its
                // physical rotation axis.  The controller keeps both roles
                // as lists, while the device page still permits moving an
                // individual bearing to the other role manually.
                detected = defaultRole;
            }
            controller.bind(detected, target);
            sendCandidates(player,bindingPayloadFor(player,controller));
            PENDING.put(player.getUUID(),new PendingBinding(pending.kind(),pending.source(),level.getGameTime()+BINDING_TIMEOUT_TICKS,pending.lease()));
            player.displayClientMessage(
                    Component.translatable("message.create_fire_control.binding.success", detected.displayName()),
                    true
            );
            return;
        }
        // Do not resolve every loaded controller for an ordinary block click.
        // In particular, controller items such as Create Tweaked Controllers'
        // advanced remote emit RightClickBlock before their own use action.
        // Resolving Sable sublevel bindings here can stall both logical sides
        // while a freshly placed physical structure is still synchronizing.
        if (!isBearingPart(level, clickedPos)) {
            return;
        }

        // Placement belongs to Minecraft/Create. Never consume it merely
        // because the clicked bearing is a dual-stabilizer bearing.
        if (event.getItemStack().getItem() instanceof BlockItem
                || (!event.getItemStack().isEmpty() && !wrench)) {
            return;
        }

        SwivelBearingBlockEntity bearing = protectedBearing(level, clickedPos);
        if (bearing != null) {
            consume(event);
            if (wrench && player.isShiftKeyDown()) {
                bearing.disassemble();
                player.displayClientMessage(
                        Component.translatable("message.create_fire_control.bearing.disassembled"),
                        true
                );
            } else {
                player.displayClientMessage(
                        Component.translatable("message.create_fire_control.bearing.disassemble_requires_wrench"),
                        true
                );
            }
        }
    }

    private static SwivelBearingBlockEntity protectedBearing(Level level, BlockPos pos) {
        return ControllerTracker.findBoundAssembledBearing(level, pos);
    }

    private static boolean isBearingPart(Level level, BlockPos pos) {
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof SwivelBearingBlockEntity
                || blockEntity instanceof SwivelBearingPlateBlockEntity
                || level.getBlockState(pos).getBlock() instanceof SwivelBearingPlateBlock;
    }

    static BindingSlot detectBindingSlot(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof SwivelBearingBlockEntity) {
            return BindingSlot.BEARING;
        }
        if (ElectroOpticalPodCompat.isPod(level, pos)) {
            return BindingSlot.POD;
        }

        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof SeatBlock
                || "interiors".equals(state.getBlockHolder().unwrapKey()
                .map(key -> key.location().getNamespace()).orElse(""))) {
            return BindingSlot.SEAT;
        }

        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null && CannonMountCompat.isCannonMount(blockEntity)) {
            return BindingSlot.CANNON_MOUNT;
        }
        ResourceLocation id = level.registryAccess()
                .registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)
                .getKey(state.getBlock());
        if (id != null) {
            String namespace = id.getNamespace();
            String path = id.getPath();
            if ("create_flight_control".equals(namespace)
                    && "forward_indicator".equals(path)) {
                return BindingSlot.FORWARD_INDICATOR;
            }
            if ("createbigcannons".equals(namespace) && path.contains("mount")) {
                return BindingSlot.CANNON_MOUNT;
            }
        }

        if (MianbaoBallisticMissileCompat.isBindingTarget(state)) {
            return BindingSlot.BALLISTIC_MISSILE_LAUNCHER;
        }
        if (MianbaoMissileCompat.isLauncher(state)) return BindingSlot.MISSILE_LAUNCHER;
        if (blockEntity instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity) {
            return BindingSlot.RADAR_BEARING;
        }
        if (MianbaoAirDefenseCompat.isLauncher(state)) return BindingSlot.AA_MISSILE_LAUNCHER;
        if (MianbaoAircraftCompat.isRack(state)) return BindingSlot.AIRCRAFT_MISSILE_RACK;
        if (MianbaoRocketPodCompat.isPod(state)) return BindingSlot.AIRCRAFT_ROCKET_POD;
        if (MianbaoCannonTurretPodCompat.isCannonTurret(level, pos)) {
            return BindingSlot.CANNON_TURRET_POD;
        }
        if (MianbaoCountermeasureCompat.isLauncher(state)) {
            return BindingSlot.COUNTERMEASURE_LAUNCHER;
        }
        if (MianbaoRemoteWeaponStationCompat.isStation(level, pos)) {
            return BindingSlot.REMOTE_WEAPON_STATION;
        }
        if (MianbaoAircraftCompat.isRadar(state)) return BindingSlot.AIRCRAFT_RADAR;
        if (blockEntity instanceof com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity) {
            return BindingSlot.IFF_TRANSPONDER;
        }
        if (RemoteControllerCompat.isTerminal(level, pos, RemoteControllerCompat.Type.WIRELESS)) {
            return BindingSlot.WIRELESS_CONTROLLER_HUB;
        }
        if (RemoteControllerCompat.isTerminal(level, pos, RemoteControllerCompat.Type.NORMAL)) {
            return BindingSlot.REMOTE_CONTROLLER_HUB;
        }
        if (RemoteControllerCompat.isTerminal(level, pos, RemoteControllerCompat.Type.ADVANCED)) {
            return BindingSlot.TWEAKED_CONTROLLER_HUB;
        }
        return null;
    }
    private static BindingSessionPayload bindingPayload(
            StabilizerControllerBlockEntity controller
    ) {
        return new BindingSessionPayload(true, findBindingCandidates(controller));
    }
    private static BindingSessionPayload bindingPayloadFor(ServerPlayer player,Object source) {
        BINDING_OBSERVER.set(Sable.HELPER.projectOutOfSubLevel(player.level(),player.position()));
        try {return source instanceof StabilizerControllerBlockEntity controller?bindingPayload(controller):bindingPayload((CannonAutoLoader)source);}
        finally {BINDING_OBSERVER.remove();}
    }
    private static void sendCandidates(ServerPlayer player,BindingSessionPayload payload){BindingSessionPayload.send(player,payload);}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        PENDING.values().forEach(pending->pending.lease().close());PENDING.clear();
    }

    private static BindingSessionPayload bindingPayload(CannonAutoLoader loader) {
        return new BindingSessionPayload(true, findBindingCandidates(loader));
    }

    private static java.util.List<BindingSessionPayload.Candidate> findBindingCandidates(
            StabilizerControllerBlockEntity controller
    ) {
        Level level = controller.getLevel();
        if (level == null) return java.util.List.of();
        java.util.Set<BoundTarget> boundTargets = new java.util.HashSet<>();
        for (StabilizerControllerBlockEntity.BoundDevice device : controller.getBoundDevices()) {
            BindingRef ref = device.ref();
            boundTargets.add(new BoundTarget(ref.pos(), ref.subLevelId()));
        }
        return findBindingCandidates(level, controller.getBlockPos(), boundTargets, false);
    }

    private static java.util.List<BindingSessionPayload.Candidate> findBindingCandidates(
            CannonAutoLoader loader
    ) {
        Level level = loader.getLevel();
        if (level == null) return java.util.List.of();
        java.util.Set<BoundTarget> boundTargets = new java.util.HashSet<>();
        BindingRef binding = loader.getCannonMountBinding();
        if (binding != null) {
            boundTargets.add(new BoundTarget(binding.pos(), binding.subLevelId()));
        }
        return findBindingCandidates(level, loader.getBlockPos(), boundTargets, true);
    }

    private static java.util.List<BindingSessionPayload.Candidate> findBindingCandidates(
            Level level, BlockPos sourcePos, java.util.Set<BoundTarget> boundTargets, boolean cannonMountsOnly) {
        java.util.LinkedHashSet<BindingSessionPayload.Candidate> candidates=new java.util.LinkedHashSet<>();
        for(BoundTarget bound:boundTargets)
            candidates.add(new BindingSessionPayload.Candidate(bound.pos(),bound.subLevelId(),true));
        Vec3 observer=BINDING_OBSERVER.get();
        if(observer==null)observer=Sable.HELPER.projectOutOfSubLevel(level,Vec3.atCenterOf(sourcePos));
        BindingCandidateCollector.collect(level,sourcePos,observer,(pos,body)->{
            if(cannonMountsOnly?!MzLoaderAutomation.isSupportedMount(level,pos):detectBindingSlot(level,pos)==null)return;
            candidates.add(new BindingSessionPayload.Candidate(pos,body,boundTargets.contains(new BoundTarget(pos,body))));
        });
        return java.util.List.copyOf(candidates);
    }
    private static void consume(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    static void cancelBinding(ServerPlayer player, boolean notify) {
        PendingBinding pending=PENDING.remove(player.getUUID());
        if(pending==null)return;
        pending.lease().close();
        PacketDistributor.sendToPlayer(player, BindingSessionPayload.inactive());
        if (notify) {
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.binding.cancelled"), true);
        }
    }

    private record BoundTarget(BlockPos pos, UUID subLevelId) {
        private BoundTarget {
            pos = pos.immutable();
        }
    }

    private enum BindingKind {
        FIRE_CONTROL,
        CANNON_LOADER
    }

    private record PendingBinding(BindingKind kind, BindingRef source, long expiresAt,BindingSourceLease lease) {
    }

    private StabilizerBindingHandler() {
    }
}
