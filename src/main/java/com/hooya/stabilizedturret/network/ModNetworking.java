package com.hooya.stabilizedturret.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetworking {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(AimDirectionPayload.TYPE, AimDirectionPayload.STREAM_CODEC, AimDirectionPayload::handle);
        registrar.playToServer(RemoteControlStatePayload.TYPE, RemoteControlStatePayload.STREAM_CODEC, RemoteControlStatePayload::handle);
        registrar.playToClient(AimSessionPayload.TYPE, AimSessionPayload.STREAM_CODEC, AimSessionPayload::handle);
        registrar.playToClient(RadarSessionPayload.TYPE, RadarSessionPayload.STREAM_CODEC, RadarSessionPayload::handle);
        registrar.playToClient(MissileExplosionPayload.TYPE, MissileExplosionPayload.STREAM_CODEC, MissileExplosionPayload::handle);
        registrar.playToClient(MissileRemoteStatePayload.TYPE, MissileRemoteStatePayload.STREAM_CODEC, MissileRemoteStatePayload::handle);
        registrar.playToClient(OpenControllerPayload.TYPE, OpenControllerPayload.STREAM_CODEC, OpenControllerPayload::handle);
        registrar.playToServer(ControllerSettingsPayload.TYPE, ControllerSettingsPayload.STREAM_CODEC, ControllerSettingsPayload::handle);
        registrar.playToServer(SetWirelessFrequencyPayload.TYPE, SetWirelessFrequencyPayload.STREAM_CODEC, SetWirelessFrequencyPayload::handle);
        registrar.playToServer(SetRemoteKeyBindingPayload.TYPE, SetRemoteKeyBindingPayload.STREAM_CODEC, SetRemoteKeyBindingPayload::handle);
        registrar.playToServer(UnbindDevicePayload.TYPE, UnbindDevicePayload.STREAM_CODEC, UnbindDevicePayload::handle);
        registrar.playToServer(AssignBearingRolePayload.TYPE, AssignBearingRolePayload.STREAM_CODEC, AssignBearingRolePayload::handle);
        registrar.playToServer(SetCannonMountModePayload.TYPE, SetCannonMountModePayload.STREAM_CODEC, SetCannonMountModePayload::handle);
        registrar.playToServer(SetPodCannonLimitsPayload.TYPE, SetPodCannonLimitsPayload.STREAM_CODEC, SetPodCannonLimitsPayload::handle);
        registrar.playToServer(RadarLockPayload.TYPE, RadarLockPayload.STREAM_CODEC, RadarLockPayload::handle);
        registrar.playToServer(SetYuushyaCannonTypePayload.TYPE, SetYuushyaCannonTypePayload.STREAM_CODEC,
                SetYuushyaCannonTypePayload::handle);
        registrar.playToClient(BindingSessionPayload.TYPE, BindingSessionPayload.STREAM_CODEC, BindingSessionPayload::handle);
        registrar.playToClient(OpenAutoDefenseDisplayPayload.TYPE, OpenAutoDefenseDisplayPayload.STREAM_CODEC,
                OpenAutoDefenseDisplayPayload::handle);
        registrar.playToServer(AutoDefenseSettingsPayload.TYPE, AutoDefenseSettingsPayload.STREAM_CODEC,
                AutoDefenseSettingsPayload::handle);
        registrar.playToClient(OpenGasTurbinePayload.TYPE, OpenGasTurbinePayload.STREAM_CODEC,
                OpenGasTurbinePayload::handle);
        registrar.playToServer(SetGasTurbineSettingsPayload.TYPE, SetGasTurbineSettingsPayload.STREAM_CODEC,
                SetGasTurbineSettingsPayload::handle);
    }

    private ModNetworking() {
    }
}
