package com.hooya.stabilizedturret.content.turbine;

import com.hooya.stabilizedturret.content.diesel.Ztz99aDieselEngineBlock;
import com.hooya.stabilizedturret.content.diesel.Ztz99aDieselEngineBlockEntity;
import com.hooya.stabilizedturret.network.OpenZtz99aDieselEnginePayload;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientBindingState;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.hooya.stabilizedturret.network.OpenGasTurbinePayload;
import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class GasTurbineBindingHandler {
    private static final long BINDING_TIMEOUT_TICKS = 20L * 60L;
    private static final Map<UUID, PendingBinding> PENDING = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PendingBinding pending = PENDING.get(player.getUUID());
        if (pending == null) return;
        boolean hasWrench = AllItems.WRENCH.isIn(event.getEntity().getMainHandItem())
                || AllItems.WRENCH.isIn(event.getEntity().getOffhandItem());
        if (!hasWrench || pending.expiresAt() < player.level().getGameTime()
                || !pending.source().isInDimension(player.level())) cancel(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos clickedPos = event.getPos();
        BlockPos turbinePos = findTurbineBlockEntityPos(level, clickedPos);
        boolean wrench = AllItems.WRENCH.isIn(event.getItemStack());

        if (!wrench) {
            if (turbinePos == null
                    || !event.getItemStack().isEmpty()
                    || !event.getEntity().isShiftKeyDown()) return;
            consume(event);
            if (!level.isClientSide
                    && event.getEntity() instanceof ServerPlayer player
                    && level.getBlockEntity(turbinePos) instanceof GasTurbineBlockEntity turbine) {
                OpenGasTurbinePayload settings = new OpenGasTurbinePayload(
                        turbinePos,
                        turbine.hasValidAdvancedHub(),
                        turbine.isEngineEnabled(),
                        turbine.getControlSettings(),turbine.getTerminalType()==RemoteControllerCompat.Type.WIRELESS
                );
                PacketDistributor.sendToPlayer(player, turbine instanceof com.hooya.stabilizedturret.content.tiger.TigerIEngineBlockEntity
                        ? com.hooya.stabilizedturret.network.OpenTigerIEnginePayload.from(settings, BindingRef.capture(level, turbinePos).subLevelId())
                        : turbine instanceof Ztz99aDieselEngineBlockEntity
                        ? OpenZtz99aDieselEnginePayload.from(settings, BindingRef.capture(level, turbinePos).subLevelId()) : settings);
            }
            return;
        }

        if (level.isClientSide) {
            if (turbinePos != null) {
                consume(event);
                return;
            }
            if (ClientBindingState.isActive()) {
                consume(event);
            }
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        PendingBinding pending = PENDING.get(player.getUUID());
        if (turbinePos != null) {
            BindingRef turbineRef = BindingRef.capture(level, turbinePos);
            if (pending != null && turbineRef.equals(pending.source())) {
                cancel(player);
                player.displayClientMessage(Component.translatable(
                        "message.create_fire_control.gas_turbine.binding_cancelled"), true);
            } else {
                PENDING.put(player.getUUID(), new PendingBinding(
                        turbineRef, level.getGameTime() + BINDING_TIMEOUT_TICKS));
                if (level.getBlockEntity(turbinePos) instanceof GasTurbineBlockEntity turbine) {
                    BindingSessionPayload.send(player, bindingPayload(turbine));
                }
                player.displayClientMessage(Component.translatable(
                        "message.create_fire_control.gas_turbine.select_advanced_hub"), true);
            }
            consume(event);
            return;
        }

        if (pending == null) return;
        consume(event);
        if (pending.expiresAt() < level.getGameTime()
                || !(pending.source().resolve(level) instanceof GasTurbineBlockEntity turbine)) {
            cancel(player);
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.gas_turbine.binding_cancelled"), true);
            return;
        }
        if (!RemoteControllerCompat.isTerminal(
                level, clickedPos, RemoteControllerCompat.Type.ADVANCED) && !RemoteControllerCompat.isTerminal(level,clickedPos,RemoteControllerCompat.Type.WIRELESS)) {
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.gas_turbine.invalid_hub"), true);
            return;
        }
        if (turbine.bindAdvancedHub(BindingRef.capture(level, clickedPos))) {
            cancel(player);
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.gas_turbine.binding_success"), true);
        }
    }

    private static BindingSessionPayload bindingPayload(GasTurbineBlockEntity turbine) {
        Level level = turbine.getLevel();
        if (level == null) return new BindingSessionPayload(true, java.util.List.of());

        BindingRef current = turbine.getAdvancedHub();
        Set<Target> bound = new java.util.HashSet<>();
        if (current != null) bound.add(new Target(current.pos(), current.subLevelId()));
        LinkedHashSet<BindingSessionPayload.Candidate> candidates = new LinkedHashSet<>();
        Vec3 sourceWorld = Sable.HELPER.projectOutOfSubLevel(
                level, Vec3.atCenterOf(turbine.getBlockPos()));
        BlockPos rootCenter = BlockPos.containing(sourceWorld);
        scan(level, rootCenter.offset(-24, -24, -24), rootCenter.offset(24, 24, 24),
                sourceWorld, null, bound, candidates, 180000);

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel == null || subLevel.isRemoved() || candidates.size() >= 1024) {
                    continue;
                }
                var worldBounds = subLevel.boundingBox();
                double closestX = Math.max(worldBounds.minX(),
                        Math.min(worldBounds.maxX(), sourceWorld.x));
                double closestY = Math.max(worldBounds.minY(),
                        Math.min(worldBounds.maxY(), sourceWorld.y));
                double closestZ = Math.max(worldBounds.minZ(),
                        Math.min(worldBounds.maxZ(), sourceWorld.z));
                if (sourceWorld.distanceToSqr(closestX, closestY, closestZ) > 96.0 * 96.0) {
                    continue;
                }

                org.joml.Vector3d localMin = new org.joml.Vector3d(
                        Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY);
                org.joml.Vector3d localMax = new org.joml.Vector3d(
                        Double.NEGATIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        Double.NEGATIVE_INFINITY);
                for (int corner = 0; corner < 8; corner++) {
                    org.joml.Vector3d point = new org.joml.Vector3d(
                            (corner & 1) == 0 ? worldBounds.minX() : worldBounds.maxX(),
                            (corner & 2) == 0 ? worldBounds.minY() : worldBounds.maxY(),
                            (corner & 4) == 0 ? worldBounds.minZ() : worldBounds.maxZ());
                    subLevel.logicalPose().transformPositionInverse(point);
                    localMin.min(point);
                    localMax.max(point);
                }
                scan(subLevel.getLevel(),
                        BlockPos.containing(Math.floor(localMin.x) - 1.0,
                                Math.floor(localMin.y) - 1.0,
                                Math.floor(localMin.z) - 1.0),
                        BlockPos.containing(Math.ceil(localMax.x) + 1.0,
                                Math.ceil(localMax.y) + 1.0,
                                Math.ceil(localMax.z) + 1.0),
                        sourceWorld, subLevel.getUniqueId(), bound, candidates, 300000);
            }
        }
        return new BindingSessionPayload(true, java.util.List.copyOf(candidates));
    }

    private static void scan(
            Level level,
            BlockPos min,
            BlockPos max,
            Vec3 sourceWorld,
            UUID subLevelId,
            Set<Target> bound,
            Set<BindingSessionPayload.Candidate> candidates,
            int maximumChecks
    ) {
        long sizeX = (long) max.getX() - min.getX() + 1L;
        long sizeY = (long) max.getY() - min.getY() + 1L;
        long sizeZ = (long) max.getZ() - min.getZ() + 1L;
        if (sizeX <= 0L || sizeY <= 0L || sizeZ <= 0L
                || sizeX * sizeY * sizeZ > maximumChecks) return;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = min.getY(); y <= max.getY() && candidates.size() < 1024; y++) {
            for (int z = min.getZ(); z <= max.getZ() && candidates.size() < 1024; z++) {
                for (int x = min.getX(); x <= max.getX() && candidates.size() < 1024; x++) {
                    cursor.set(x, y, z);
                    if (!RemoteControllerCompat.isTerminal(
                            level, cursor, RemoteControllerCompat.Type.ADVANCED) && !RemoteControllerCompat.isTerminal(level,cursor,RemoteControllerCompat.Type.WIRELESS)) continue;
                    Vec3 world = Sable.HELPER.projectOutOfSubLevel(
                            level, Vec3.atCenterOf(cursor));
                    if (world.distanceToSqr(sourceWorld) > 96.0 * 96.0) continue;
                    BlockPos pos = cursor.immutable();
                    candidates.add(new BindingSessionPayload.Candidate(
                            pos, subLevelId, bound.contains(new Target(pos, subLevelId))));
                }
            }
        }
    }

    public static BlockPos findTurbineBlockEntityPos(Level level, BlockPos clickedPos) {
        BlockState state = level.getBlockState(clickedPos);
        if (state.getBlock() instanceof com.hooya.stabilizedturret.content.tiger.TigerIEngineBlock)
            return com.hooya.stabilizedturret.content.tiger.TigerIEngineBlock.controllerPos(clickedPos, state);
        if (state.getBlock() instanceof Ztz99aDieselEngineBlock)
            return Ztz99aDieselEngineBlock.controllerPos(clickedPos, state);
        if (!(state.getBlock() instanceof GasTurbineBlock)) return null;
        net.minecraft.core.Direction facing = state.getValue(GasTurbineBlock.FACING);
        BlockPos center = switch (state.getValue(GasTurbineBlock.PART)) {
            case NEGATIVE -> clickedPos.relative(facing);
            case CENTER -> clickedPos;
            case POSITIVE -> clickedPos.relative(facing.getOpposite());
        };
        return center.relative(facing.getOpposite());
    }

    private static void consume(PlayerInteractEvent.RightClickBlock event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void cancel(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        PacketDistributor.sendToPlayer(player, BindingSessionPayload.inactive());
    }

    private record Target(BlockPos pos, UUID subLevelId) {
        private Target {
            pos = pos.immutable();
        }
    }

    private record PendingBinding(BindingRef source, long expiresAt) {
    }

    private GasTurbineBindingHandler() {
    }
}
