package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlockEntity;
import com.hooya.stabilizedturret.client.ClientBindingState;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
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
    private static final Map<UUID, BindingRef> PENDING = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !PENDING.containsKey(player.getUUID())) return;
        if (!AllItems.WRENCH.isIn(player.getMainHandItem())
                && !AllItems.WRENCH.isIn(player.getOffhandItem())) {
            cancelBinding(player, false);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide) {
            ControllerTracker.invalidateBindingsAt(level, event.getPos());
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

        BindingRef pending = PENDING.get(player.getUUID());

        if (wrench && level.getBlockEntity(clickedPos) instanceof StabilizerControllerBlockEntity controller) {
            BindingRef controllerRef = BindingRef.capture(level, clickedPos);
            if (pending != null && pending.equals(controllerRef)) {
                cancelBinding(player, false);
                player.displayClientMessage(
                        Component.translatable("message.stabilized_turret.binding.cancelled"),
                        true
                );
            } else {
                PENDING.put(player.getUUID(), controllerRef);
                PacketDistributor.sendToPlayer(player, bindingPayload(controller));
                player.displayClientMessage(
                        Component.translatable("message.stabilized_turret.binding.select_module"),
                        true
                );
            }
            consume(event);
            return;
        }

        if (wrench && pending != null) {
            // A binding click must never fall through to Create/Aeronautics.
            consume(event);
            if (!(pending.resolve(level) instanceof StabilizerControllerBlockEntity controller)) {
                cancelBinding(player, false);
                player.displayClientMessage(
                        Component.translatable("message.stabilized_turret.binding.cancelled"),
                        true
                );
                return;
            }

            BindingSlot detected = detectBindingSlot(level, clickedPos);
            if (detected == null) {
                PacketDistributor.sendToPlayer(player, bindingPayload(controller));
                player.displayClientMessage(
                        Component.translatable("message.stabilized_turret.binding.invalid_auto"),
                        true
                );
                return;
            }

            BindingRef target = BindingRef.capture(level, clickedPos);
            if (detected == BindingSlot.BEARING
                    && level.getBlockState(clickedPos).getBlock() instanceof SwivelBearingBlock bearingBlock) {
                BindingSlot defaultRole = bearingBlock.getRotationAxis(level.getBlockState(clickedPos))
                        == net.minecraft.core.Direction.Axis.Y ? BindingSlot.YAW : BindingSlot.PITCH;
                detected = controller.getBinding(defaultRole) == null
                        ? defaultRole : BindingSlot.BEARING;
            }
            controller.bind(detected, target);
            PacketDistributor.sendToPlayer(player, bindingPayload(controller));
            player.displayClientMessage(
                    Component.translatable("message.stabilized_turret.binding.success", detected.displayName()),
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
                        Component.translatable("message.stabilized_turret.bearing.disassembled"),
                        true
                );
            } else {
                player.displayClientMessage(
                        Component.translatable("message.stabilized_turret.bearing.disassemble_requires_wrench"),
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
            if (("createbigcannons".equals(namespace) && path.contains("mount"))
                    || ("cbcmodernwarfare".equals(namespace) && path.contains("mount"))) {
                return BindingSlot.CANNON_MOUNT;
            }
        }

        if (MianbaoMissileCompat.isLauncher(state)) return BindingSlot.MISSILE_LAUNCHER;
        if (MianbaoMissileCompat.isDesignator(state)) return BindingSlot.LASER_DESIGNATOR;
        if (blockEntity instanceof com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity) {
            return BindingSlot.RADAR_BEARING;
        }
        if (MianbaoAirDefenseCompat.isLauncher(state)) return BindingSlot.AA_MISSILE_LAUNCHER;
        if (MianbaoAircraftCompat.isRack(state)) return BindingSlot.AIRCRAFT_MISSILE_RACK;
        if (MianbaoAircraftCompat.isRadar(state)) return BindingSlot.AIRCRAFT_RADAR;
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

    private static java.util.List<BindingSessionPayload.Candidate> findBindingCandidates(
            StabilizerControllerBlockEntity controller
    ) {
        Level level = controller.getLevel();
        if (level == null) return java.util.List.of();

        java.util.LinkedHashSet<BindingSessionPayload.Candidate> candidates =
                new java.util.LinkedHashSet<>();
        java.util.Set<BoundTarget> boundTargets = new java.util.HashSet<>();
        for (StabilizerControllerBlockEntity.BoundDevice device : controller.getBoundDevices()) {
            BindingRef ref = device.ref();
            boundTargets.add(new BoundTarget(ref.pos(), ref.subLevelId()));
        }
        Vec3 controllerWorld = Sable.HELPER.projectOutOfSubLevel(
                level, Vec3.atCenterOf(controller.getBlockPos())
        );

        BlockPos center = BlockPos.containing(controllerWorld);
        scanBox(level, center.offset(-24, -24, -24), center.offset(24, 24, 24),
                controllerWorld, null, boundTargets, candidates, 180000);

        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container != null) {
            for (dev.ryanhcode.sable.sublevel.SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved() || candidates.size() >= 1024) continue;
                dev.ryanhcode.sable.companion.math.BoundingBox3dc worldBounds =
                        subLevel.boundingBox();
                double closestX = Math.max(worldBounds.minX(),
                        Math.min(worldBounds.maxX(), controllerWorld.x));
                double closestY = Math.max(worldBounds.minY(),
                        Math.min(worldBounds.maxY(), controllerWorld.y));
                double closestZ = Math.max(worldBounds.minZ(),
                        Math.min(worldBounds.maxZ(), controllerWorld.z));
                if (controllerWorld.distanceToSqr(closestX, closestY, closestZ) > 96.0 * 96.0) {
                    continue;
                }

                org.joml.Vector3d localMin = new org.joml.Vector3d(
                        Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY
                );
                org.joml.Vector3d localMax = new org.joml.Vector3d(
                        Double.NEGATIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        Double.NEGATIVE_INFINITY
                );
                for (int corner = 0; corner < 8; corner++) {
                    org.joml.Vector3d point = new org.joml.Vector3d(
                            (corner & 1) == 0 ? worldBounds.minX() : worldBounds.maxX(),
                            (corner & 2) == 0 ? worldBounds.minY() : worldBounds.maxY(),
                            (corner & 4) == 0 ? worldBounds.minZ() : worldBounds.maxZ()
                    );
                    subLevel.logicalPose().transformPositionInverse(point);
                    localMin.min(point);
                    localMax.max(point);
                }
                BlockPos min = BlockPos.containing(
                        Math.floor(localMin.x) - 1.0,
                        Math.floor(localMin.y) - 1.0,
                        Math.floor(localMin.z) - 1.0
                );
                BlockPos max = BlockPos.containing(
                        Math.ceil(localMax.x) + 1.0,
                        Math.ceil(localMax.y) + 1.0,
                        Math.ceil(localMax.z) + 1.0
                );
                scanBox(subLevel.getLevel(), min, max, controllerWorld,
                        subLevel.getUniqueId(), boundTargets, candidates, 300000);
            }
        }
        return java.util.List.copyOf(candidates);
    }

    private static void scanBox(
            Level level,
            BlockPos min,
            BlockPos max,
            Vec3 controllerWorld,
            UUID subLevelId,
            java.util.Set<BoundTarget> boundTargets,
            java.util.Set<BindingSessionPayload.Candidate> candidates,
            int maximumChecks
    ) {
        long sizeX = (long) max.getX() - min.getX() + 1L;
        long sizeY = (long) max.getY() - min.getY() + 1L;
        long sizeZ = (long) max.getZ() - min.getZ() + 1L;
        if (sizeX <= 0L || sizeY <= 0L || sizeZ <= 0L
                || sizeX * sizeY * sizeZ > maximumChecks) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = min.getY(); y <= max.getY() && candidates.size() < 1024; y++) {
            for (int z = min.getZ(); z <= max.getZ() && candidates.size() < 1024; z++) {
                for (int x = min.getX(); x <= max.getX() && candidates.size() < 1024; x++) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).isAir()
                            || detectBindingSlot(level, cursor) == null) {
                        continue;
                    }
                    Vec3 world = Sable.HELPER.projectOutOfSubLevel(
                            level, Vec3.atCenterOf(cursor)
                    );
                    if (world.distanceToSqr(controllerWorld) <= 96.0 * 96.0) {
                        candidates.add(new BindingSessionPayload.Candidate(
                                cursor.immutable(),
                                subLevelId,
                                boundTargets.contains(new BoundTarget(cursor.immutable(), subLevelId))
                        ));
                    }
                }
            }
        }
    }
    private static void consume(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void cancelBinding(ServerPlayer player, boolean notify) {
        PENDING.remove(player.getUUID());
        PacketDistributor.sendToPlayer(player, BindingSessionPayload.inactive());
        if (notify) {
            player.displayClientMessage(Component.translatable(
                    "message.stabilized_turret.binding.cancelled"), true);
        }
    }

    private record BoundTarget(BlockPos pos, UUID subLevelId) {
        private BoundTarget {
            pos = pos.immutable();
        }
    }

    private StabilizerBindingHandler() {
    }
}
