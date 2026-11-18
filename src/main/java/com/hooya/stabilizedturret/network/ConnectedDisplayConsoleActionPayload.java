package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayConsoleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record ConnectedDisplayConsoleActionPayload(
        BlockPos consolePos,
        UUID consoleSubLevelId,
        Action action,
        double first,
        double second,
        UUID targetId,
        boolean targetLiving
) implements CustomPacketPayload {
    public static final Type<ConnectedDisplayConsoleActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    StabilizedTurret.MOD_ID, "connected_display_console_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            ConnectedDisplayConsoleActionPayload> STREAM_CODEC = StreamCodec.of(
            ConnectedDisplayConsoleActionPayload::write,
            ConnectedDisplayConsoleActionPayload::read);

    public static ConnectedDisplayConsoleActionPayload simple(
            BlockPos pos, UUID subLevelId, Action action) {
        return new ConnectedDisplayConsoleActionPayload(
                pos, subLevelId, action, 0.0, 0.0, null, false);
    }

    private static void write(RegistryFriendlyByteBuf buffer,
                              ConnectedDisplayConsoleActionPayload payload) {
        buffer.writeBlockPos(payload.consolePos);
        buffer.writeBoolean(payload.consoleSubLevelId != null);
        if (payload.consoleSubLevelId != null) buffer.writeUUID(payload.consoleSubLevelId);
        buffer.writeVarInt(payload.action.ordinal());
        buffer.writeDouble(payload.first);
        buffer.writeDouble(payload.second);
        buffer.writeBoolean(payload.targetId != null);
        if (payload.targetId != null) buffer.writeUUID(payload.targetId);
        buffer.writeBoolean(payload.targetLiving);
    }

    private static ConnectedDisplayConsoleActionPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        UUID subLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        int ordinal = buffer.readVarInt();
        if (ordinal < 0 || ordinal >= Action.values().length) {
            throw new IllegalArgumentException("Invalid connected display console action");
        }
        double first = buffer.readDouble();
        double second = buffer.readDouble();
        UUID targetId = buffer.readBoolean() ? buffer.readUUID() : null;
        boolean living = buffer.readBoolean();
        return new ConnectedDisplayConsoleActionPayload(
                pos, subLevelId, Action.values()[ordinal], first, second,
                targetId, living);
    }

    public static void handle(ConnectedDisplayConsoleActionPayload payload,
                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !Double.isFinite(payload.first)
                    || !Double.isFinite(payload.second)) return;
            ConnectedDisplayConsoleBlockEntity console =
                    RequestConnectedDisplayConsoleStatePayload.resolveConsole(
                            player, payload.consolePos, payload.consoleSubLevelId);
            if (console == null) return;
            if (payload.action == Action.RELEASE_CONTROL) {
                console.releaseControl(player);
                return;
            }
            if (!player.mayBuild() || !console.refreshControl(player)) return;
            ConnectedDisplayBlockEntity display = console.resolveDisplay();
            if (display == null) return;
            switch (payload.action) {
                case SELECT_CANNON -> display.selectWeapon(
                        ConnectedDisplayBlockEntity.WeaponCategory.CANNON);
                case SELECT_AIR_DEFENSE -> display.selectWeapon(
                        ConnectedDisplayBlockEntity.WeaponCategory.AIR_DEFENSE);
                case SELECT_CRUISE -> display.selectWeapon(
                        ConnectedDisplayBlockEntity.WeaponCategory.CRUISE);
                case SELECT_BALLISTIC -> display.selectWeapon(
                        ConnectedDisplayBlockEntity.WeaponCategory.BALLISTIC);
                case CYCLE_CRUISE_TYPE -> display.cycleCruiseMissileType();
                case CYCLE_BALLISTIC_TYPE -> display.cycleBallisticMissileType();
                case TOGGLE_CRUISE_MULTI -> display.toggleCruiseMultiSelect();
                case SET_RADAR_RANGE -> display.setRadarRange(payload.first);
                case SET_DEFENSE_RANGE -> display.setAutomaticMissileDefenseRange(payload.first);
                case SET_PROXIMITY_RANGE -> display.setMissileProximityRange(payload.first);
                case TOGGLE_AUTOMATIC_ATTACK -> display.toggleAutomaticAttack();
                case TOGGLE_AUTOMATIC_DEFENSE -> display.toggleAutomaticMissileDefense();
                case FIRE -> display.requestContextFire();
                case CLEAR_TARGET -> display.clearSelectedTarget();
                case MAP_POINT -> display.handleConsoleMapInteraction(
                        payload.targetId, payload.targetLiving,
                        payload.first, payload.second);
                case CANCEL_MAP_POINT -> display.handleConsoleMapCancel(
                        payload.targetId, payload.targetLiving,
                        payload.first, payload.second);
                case RELEASE_CONTROL -> { }
            }
            PacketDistributor.sendToPlayer(player,
                    OpenConnectedDisplayConsolePayload.from(console, display));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Action {
        SELECT_CANNON,
        SELECT_AIR_DEFENSE,
        SELECT_CRUISE,
        CYCLE_CRUISE_TYPE,
        TOGGLE_CRUISE_MULTI,
        SET_RADAR_RANGE,
        SET_DEFENSE_RANGE,
        SET_PROXIMITY_RANGE,
        TOGGLE_AUTOMATIC_ATTACK,
        TOGGLE_AUTOMATIC_DEFENSE,
        FIRE,
        CLEAR_TARGET,
        MAP_POINT,
        CANCEL_MAP_POINT,
        RELEASE_CONTROL,
        SELECT_BALLISTIC,
        CYCLE_BALLISTIC_TYPE
    }
}
