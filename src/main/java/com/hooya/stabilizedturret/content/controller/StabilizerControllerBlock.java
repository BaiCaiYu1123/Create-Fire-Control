package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.network.PacketDistributor;

public class StabilizerControllerBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<StabilizerControllerBlock> CODEC = simpleCodec(StabilizerControllerBlock::new);

    public StabilizerControllerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StabilizerControllerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide
                    && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && level.getBlockEntity(pos) instanceof StabilizerControllerBlockEntity controller) {
                BindingInspectionHandler.toggle(serverPlayer, controller);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof StabilizerControllerBlockEntity controller) {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                boolean remoteTerminalAvailable = controller.hasRemoteTerminal();
                PacketDistributor.sendToPlayer(serverPlayer, new OpenControllerPayload(
                        pos,
                        ControllerTracker.controllerSubLevelId(controller),
                        controller.getDepressionLimitDegrees(),
                        controller.getElevationLimitDegrees(),
                        controller.getYawSpeedDegreesPerSecond(),
                        controller.getYawLimits(),
                        controller.getPitchSpeedDegreesPerSecond(),
                        controller.isYawReversed(),
                        controller.isPitchReversed(),
                        controller.isYawBearingHeadHidden(),
                        controller.isPitchBearingHeadHidden(),
                        controller.getThirdPersonHeight(),
                        controller.getViewAimOffsetX(),
                        controller.getViewAimOffsetY(),
                        controller.getYawOffset().x,
                        controller.getYawOffset().y,
                        controller.getYawOffset().z,
                        controller.getPitchOffset().x,
                        controller.getPitchOffset().y,
                        controller.getPitchOffset().z,
                        controller.getScopeOffset().x,
                        controller.getScopeOffset().y,
                        controller.getScopeOffset().z,
                        controller.getScopePreset().ordinal(),
                        controller.isStabilizerEnabled(),
                        controller.isStabilizerReactionEnabled(),
                        controller.getCannonRecoilMagnitude(),
                        controller.isMobileShotCompensationEnabled(),
                        controller.isHeadAimEnabled(),
                        controller.isRemoteHeadAimEnabled() && remoteTerminalAvailable,
                        remoteTerminalAvailable,
                        controller.getPodCannonDepressionLimitDegrees(),
                        controller.getPodCannonElevationLimitDegrees(),
                        controller.getRadarRange(),
                        controller.getRadarDepression(),
                        controller.getRadarElevation(),
                        controller.getAaProximityFuseRange(),
                        controller.getRadarHudScale(),
                        controller.getRadarHudX(),
                        controller.getRadarHudY(),
                        controller.getAircraftRadarRange(),
                        controller.getAircraftRadarHorizontalHalfAngle(),
                        controller.getAircraftRadarVerticalAngle(),
                        controller.getAircraftProximityFuseRange(),
                        controller.getAircraftFuseSafetySeconds(),
                        controller.getAircraftRadarMinimumTargetSpeed(),
                        controller.getAircraftRadarHudScale(),
                        controller.getAircraftRadarHudX(),
                        controller.getAircraftRadarHudY(),
                        controller.getRwrRange(),
                        controller.getRwrScale(),
                        controller.isAircraftRocketSalvoMode(),
                        controller.getFrequencyName(0, true),
                        controller.getFrequencyName(0, false),
                        controller.getFrequencyName(1, true),
                        controller.getFrequencyName(1, false),
                        controller.getFrequencyName(2, true),
                        controller.getFrequencyName(2, false),
                        controller.getFrequencyName(3, true),
                        controller.getFrequencyName(3, false),
                        controller.getFrequencyName(4, true),
                        controller.getFrequencyName(4, false),
                        controller.getFrequencyName(5, true),
                        controller.getFrequencyName(5, false),
                        controller.getFrequencyName(6, true),
                        controller.getFrequencyName(6, false),
                        controller.getFrequencyName(7, true),
                        controller.getFrequencyName(7, false),
                        controller.getFrequencyName(8, true),
                        controller.getFrequencyName(8, false),
                        controller.getFrequencyName(9, true),
                        controller.getFrequencyName(9, false),
                        controller.getFrequencyName(10, true),
                        controller.getFrequencyName(10, false),
                        controller.getFrequencyName(11, true),
                        controller.getFrequencyName(11, false),
                        controller.getFrequencyName(12, true),
                        controller.getFrequencyName(12, false),
                        controller.getFrequencyName(13, true),
                        controller.getFrequencyName(13, false),
                        controller.getFrequencyName(StabilizerControllerBlockEntity.INPUT_ACTION_LASER_RANGE, true),
                        controller.getFrequencyName(StabilizerControllerBlockEntity.INPUT_ACTION_LASER_RANGE, false),
                        controller.getRemoteKeys(RemoteControllerCompat.Type.NORMAL),
                        controller.getRemoteKeys(RemoteControllerCompat.Type.ADVANCED),
                        controller.getBoundDevices().stream()
                                .map(device -> new OpenControllerPayload.BoundDevice(
                                        device.slot().name(),
                                        device.index(),
                                        level.getBlockState(device.ref().pos())
                                                .getBlock().getDescriptionId(),
                                        device.ref().pos(),
                                        device.ref().subLevelId() != null,
                                        device.podMode(),
                                        device.podOrder(),
                                        device.podReversed(),
                                        controller.getBearingGroupIndex(
                                                device.slot(), device.index()),
                                        device.slot() == BindingSlot.YAW || device.slot() == BindingSlot.PITCH
                                                ? controller.getBearingSettings(device.slot(), device.ref()).depression() : Double.NaN,
                                        device.slot() == BindingSlot.YAW || device.slot() == BindingSlot.PITCH
                                                ? controller.getBearingSettings(device.slot(), device.ref()).elevation() : Double.NaN,
                                        device.slot() == BindingSlot.YAW || device.slot() == BindingSlot.PITCH
                                                ? controller.getBearingSettings(device.slot(), device.ref()).speed() : Double.NaN,
                                        (device.slot() == BindingSlot.YAW || device.slot() == BindingSlot.PITCH)
                                                && controller.getBearingSettings(device.slot(), device.ref()).reversed(),
                                        (device.slot() == BindingSlot.YAW || device.slot() == BindingSlot.PITCH)
                                                && controller.getBearingSettings(device.slot(), device.ref()).headHidden(),
                                        device.slot() == BindingSlot.YAW || device.slot() == BindingSlot.PITCH
                                                ? controller.getBearingSettings(device.slot(), device.ref()).offset().x : 0.0,
                                        device.slot() == BindingSlot.YAW || device.slot() == BindingSlot.PITCH
                                                ? controller.getBearingSettings(device.slot(), device.ref()).offset().y : 0.0,
                                        device.slot() == BindingSlot.YAW || device.slot() == BindingSlot.PITCH
                                                ? controller.getBearingSettings(device.slot(), device.ref()).offset().z : 0.0,
                                        device.slot() == BindingSlot.YAW
                                                ? controller.getBearingSettings(device.slot(), device.ref()).yawLimits()
                                                : com.hooya.stabilizedturret.content.bearing.YawLimits.UNLIMITED
                                ))
                                .toList(), controller.inputUiState(),
                        controller.isVirtualScopeEnabled(), controller.virtualScopeUnavailableReason()
                ));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (player.isShiftKeyDown() && stack.isEmpty()) {
            if (!level.isClientSide
                    && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && level.getBlockEntity(pos) instanceof StabilizerControllerBlockEntity controller) {
                BindingInspectionHandler.toggle(serverPlayer, controller);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!player.isShiftKeyDown()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof StabilizerControllerBlockEntity controller) {
            int slot = controller.assignNextFrequency(stack);
            player.displayClientMessage(
                    Component.translatable("message.create_fire_control.frequency.assigned." + slot, stack.getHoverName()),
                    true
            );
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        return createTickerHelper(type, ModBlockEntities.STABILIZER_CONTROLLER.get(), StabilizerControllerBlockEntity::tick);
    }
}
