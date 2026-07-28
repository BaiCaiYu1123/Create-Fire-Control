package com.hooya.stabilizedturret.content.turbine;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.network.OpenGasTurbinePayload;
import com.simibubi.create.AllItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class GasTurbineBindingHandler {
    private static final Map<UUID, BindingRef> PENDING = new HashMap<>();
    private static boolean clientPending;

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        boolean hasWrench = AllItems.WRENCH.isIn(event.getEntity().getMainHandItem())
                || AllItems.WRENCH.isIn(event.getEntity().getOffhandItem());
        if (event.getEntity().level().isClientSide) {
            if (!hasWrench) clientPending = false;
        } else if (event.getEntity() instanceof ServerPlayer player && !hasWrench) {
            PENDING.remove(player.getUUID());
        }
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
                PacketDistributor.sendToPlayer(player, new OpenGasTurbinePayload(
                        turbinePos,
                        turbine.hasValidAdvancedHub(),
                        turbine.isEngineEnabled(),
                        turbine.getForwardSpeed(),
                        turbine.getReverseSpeed(),
                        turbine.getTurnSpeed(),
                        turbine.getAcceleration(),
                        turbine.getSoundVolume(),
                        turbine.getAdvancedRemoteKeys()
                ));
            }
            return;
        }

        if (level.isClientSide) {
            if (turbinePos != null) {
                clientPending = !clientPending;
                consume(event);
                return;
            }
            if (clientPending) {
                consume(event);
                if (RemoteControllerCompat.isTerminal(
                        level, clickedPos, RemoteControllerCompat.Type.ADVANCED)) {
                    clientPending = false;
                }
            }
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        BindingRef pending = PENDING.get(player.getUUID());
        if (turbinePos != null) {
            BindingRef turbineRef = BindingRef.capture(level, turbinePos);
            if (turbineRef.equals(pending)) {
                PENDING.remove(player.getUUID());
                player.displayClientMessage(Component.translatable(
                        "message.stabilized_turret.gas_turbine.binding_cancelled"), true);
            } else {
                PENDING.put(player.getUUID(), turbineRef);
                player.displayClientMessage(Component.translatable(
                        "message.stabilized_turret.gas_turbine.select_advanced_hub"), true);
            }
            consume(event);
            return;
        }

        if (pending == null) return;
        consume(event);
        if (!(pending.resolve(level) instanceof GasTurbineBlockEntity turbine)) {
            PENDING.remove(player.getUUID());
            player.displayClientMessage(Component.translatable(
                    "message.stabilized_turret.gas_turbine.binding_cancelled"), true);
            return;
        }
        if (!RemoteControllerCompat.isTerminal(
                level, clickedPos, RemoteControllerCompat.Type.ADVANCED)) {
            player.displayClientMessage(Component.translatable(
                    "message.stabilized_turret.gas_turbine.invalid_hub"), true);
            return;
        }
        if (turbine.bindAdvancedHub(BindingRef.capture(level, clickedPos))) {
            PENDING.remove(player.getUUID());
            player.displayClientMessage(Component.translatable(
                    "message.stabilized_turret.gas_turbine.binding_success"), true);
        }
    }

    public static BlockPos findTurbineBlockEntityPos(Level level, BlockPos clickedPos) {
        BlockState state = level.getBlockState(clickedPos);
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

    private GasTurbineBindingHandler() {
    }
}
