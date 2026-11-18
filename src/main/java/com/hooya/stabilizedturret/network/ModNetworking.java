package com.hooya.stabilizedturret.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.fml.ModList;

public final class ModNetworking {
    public static void register(RegisterPayloadHandlersEvent event) {
        // Binding candidates support continuation pages without silently dropping devices.
        PayloadRegistrar registrar = event.registrar("66");
        registrar.playToClient(ArmorMeshSnapshotPayload.TYPE,ArmorMeshSnapshotPayload.STREAM_CODEC,ArmorMeshSnapshotPayload::handle);
        registrar.playToServer(ApplyArmorMeshPayload.TYPE,ApplyArmorMeshPayload.STREAM_CODEC,ApplyArmorMeshPayload::handle);
        registrar.playToServer(ApplyArmorGroupPayload.TYPE,ApplyArmorGroupPayload.CODEC,ApplyArmorGroupPayload::handle);
        registrar.playToClient(ArmorMeshResultPayload.TYPE,ArmorMeshResultPayload.STREAM_CODEC,ArmorMeshResultPayload::handle);
        registrar.playToClient(FireControlConfigStatePayload.TYPE, FireControlConfigStatePayload.STREAM_CODEC,
                FireControlConfigStatePayload::handle);
        registrar.playToServer(SetFireControlConfigPayload.TYPE, SetFireControlConfigPayload.STREAM_CODEC,
                SetFireControlConfigPayload::handle);
        registrar.playToServer(RequestStructurePreviewPayload.TYPE, RequestStructurePreviewPayload.STREAM_CODEC,
                RequestStructurePreviewPayload::handle);
        registrar.playToClient(StructurePreviewStatePayload.TYPE, StructurePreviewStatePayload.STREAM_CODEC,
                StructurePreviewStatePayload::handle);
        registrar.playToServer(StructurePreviewSettingsPayload.TYPE, StructurePreviewSettingsPayload.STREAM_CODEC,
                StructurePreviewSettingsPayload::handle);
        registrar.playToClient(ScopeAmmoPayload.TYPE, ScopeAmmoPayload.STREAM_CODEC, ScopeAmmoPayload::handle);
        registrar.playToClient(ScopeOrientationPayload.TYPE, ScopeOrientationPayload.STREAM_CODEC,
                ScopeOrientationPayload::handle);
        registrar.playToClient(WeaponViewPayload.TYPE, WeaponViewPayload.STREAM_CODEC, WeaponViewPayload::handle);
        registrar.playToClient(InfraredStatePayload.TYPE, InfraredStatePayload.STREAM_CODEC, InfraredStatePayload::handle);
        registrar.playToServer(InfraredFramePayload.TYPE, InfraredFramePayload.STREAM_CODEC, InfraredFramePayload::handle);
        registrar.playToClient(AntiRadiationStatePayload.TYPE, AntiRadiationStatePayload.STREAM_CODEC,
                AntiRadiationStatePayload::handle);
        registrar.playToServer(AntiRadiationFramePayload.TYPE, AntiRadiationFramePayload.STREAM_CODEC,
                AntiRadiationFramePayload::handle);
        registrar.playToClient(CannonLockLeadPayload.TYPE, CannonLockLeadPayload.STREAM_CODEC,
                CannonLockLeadPayload::handle);
        registrar.playToClient(MathematicalMissilePayload.TYPE, MathematicalMissilePayload.STREAM_CODEC,
                MathematicalMissilePayload::handle);
        registrar.playToClient(BombFlightPayload.TYPE, BombFlightPayload.STREAM_CODEC, BombFlightPayload::handle);
        registrar.playToClient(BombSightPayload.TYPE, BombSightPayload.STREAM_CODEC, BombSightPayload::handle);
        registrar.playToClient(CountermeasureSalvoPayload.TYPE, CountermeasureSalvoPayload.STREAM_CODEC,
                CountermeasureSalvoPayload::handle);
        registrar.playToClient(RocketSalvoPayload.TYPE, RocketSalvoPayload.STREAM_CODEC, RocketSalvoPayload::handle);
        registrar.playToServer(SetFireControlInputPayload.TYPE,SetFireControlInputPayload.STREAM_CODEC,SetFireControlInputPayload::handle);
        registrar.playToClient(FireControlInputsStatePayload.TYPE,FireControlInputsStatePayload.STREAM_CODEC,FireControlInputsStatePayload::handle);
        registrar.playToServer(WirelessControllerActionPayload.TYPE, WirelessControllerActionPayload.STREAM_CODEC,
                WirelessControllerActionPayload::handle);
        registrar.playToClient(WirelessControllerStatePayload.TYPE, WirelessControllerStatePayload.STREAM_CODEC,
                WirelessControllerStatePayload::handle);
        registrar.playToServer(AimDirectionPayload.TYPE, AimDirectionPayload.STREAM_CODEC, AimDirectionPayload::handle);
        registrar.playToServer(RemoteControlStatePayload.TYPE, RemoteControlStatePayload.STREAM_CODEC, RemoteControlStatePayload::handle);
        registrar.playToServer(RemoteInputStatePayload.TYPE, RemoteInputStatePayload.STREAM_CODEC, RemoteInputStatePayload::handle);
        registrar.playToServer(RemoteWeaponStationAimPayload.TYPE,
                RemoteWeaponStationAimPayload.STREAM_CODEC,
                RemoteWeaponStationAimPayload::handle);
        registrar.playToClient(AimSessionPayload.TYPE, AimSessionPayload.STREAM_CODEC, AimSessionPayload::handle);
        registrar.playToClient(AimGroupSessionPayload.TYPE, AimGroupSessionPayload.STREAM_CODEC,
                AimGroupSessionPayload::handle);
        registrar.playToClient(RadarSessionPayload.TYPE, RadarSessionPayload.STREAM_CODEC, RadarSessionPayload::handle);
        registrar.playToClient(MissileExplosionPayload.TYPE, MissileExplosionPayload.STREAM_CODEC, MissileExplosionPayload::handle);
        registrar.playToClient(MianbaoDetonationVisualPayload.TYPE,
                MianbaoDetonationVisualPayload.STREAM_CODEC,
                MianbaoDetonationVisualPayload::handle);
        registrar.playToClient(NuclearShockwavePayload.TYPE,
                NuclearShockwavePayload.STREAM_CODEC, NuclearShockwavePayload::handle);
        registrar.playToClient(MissileImpactRegionPayload.TYPE,
                MissileImpactRegionPayload.STREAM_CODEC, MissileImpactRegionPayload::handle);
        // This visual-only sync was added after protocol 6 had already shipped.
        // Keep it optional so 0.3.2 and newer protocol-6 peers can still connect.
        registrar.optional().playToClient(MzLoaderAnimationPayload.TYPE,
                MzLoaderAnimationPayload.STREAM_CODEC, MzLoaderAnimationPayload::handle);
        registrar.optional().playToClient(AzLoaderAnimationPayload.TYPE,
                AzLoaderAnimationPayload.STREAM_CODEC, AzLoaderAnimationPayload::handle);
        registrar.optional().playToClient(Type10LoaderAnimationPayload.TYPE,
                Type10LoaderAnimationPayload.STREAM_CODEC, Type10LoaderAnimationPayload::handle);
        if (ModList.get().isLoaded("touhou_little_maid")) {
            registrar.optional().playToClient(MaidLoadingAnimationPayload.TYPE,
                    MaidLoadingAnimationPayload.STREAM_CODEC,
                    MaidLoadingAnimationPayload::handle);
        }
        registrar.playToClient(MissileRemoteStatePayload.TYPE, MissileRemoteStatePayload.STREAM_CODEC, MissileRemoteStatePayload::handle);
        registrar.playToClient(MissileRemoteRemovalPayload.TYPE, MissileRemoteRemovalPayload.STREAM_CODEC, MissileRemoteRemovalPayload::handle);
        registrar.playToClient(VirtualMissileStatePayload.TYPE, VirtualMissileStatePayload.STREAM_CODEC, VirtualMissileStatePayload::handle);
        registrar.playToClient(OpenControllerPayload.TYPE, OpenControllerPayload.STREAM_CODEC, OpenControllerPayload::handle);
        registrar.playToServer(ControllerSettingsPayload.TYPE, ControllerSettingsPayload.STREAM_CODEC, ControllerSettingsPayload::handle);
        registrar.playToServer(ControllerBearingSettingsPayload.TYPE,
                ControllerBearingSettingsPayload.STREAM_CODEC,
                ControllerBearingSettingsPayload::handle);
        registrar.playToServer(ControllerGlobalBearingSettingsPayload.TYPE,
                ControllerGlobalBearingSettingsPayload.STREAM_CODEC,
                ControllerGlobalBearingSettingsPayload::handle);
        registrar.playToServer(SetWirelessFrequencyPayload.TYPE, SetWirelessFrequencyPayload.STREAM_CODEC, SetWirelessFrequencyPayload::handle);
        registrar.playToServer(SetRemoteKeyBindingPayload.TYPE, SetRemoteKeyBindingPayload.STREAM_CODEC, SetRemoteKeyBindingPayload::handle);
        registrar.playToClient(RemoteKeyBindingResultPayload.TYPE, RemoteKeyBindingResultPayload.STREAM_CODEC,
                RemoteKeyBindingResultPayload::handle);
        registrar.playToServer(UnbindDevicePayload.TYPE, UnbindDevicePayload.STREAM_CODEC, UnbindDevicePayload::handle);
        registrar.playToServer(AssignBearingRolePayload.TYPE, AssignBearingRolePayload.STREAM_CODEC, AssignBearingRolePayload::handle);
        registrar.playToServer(SetCannonMountModePayload.TYPE, SetCannonMountModePayload.STREAM_CODEC, SetCannonMountModePayload::handle);
        registrar.playToServer(SetPodCannonLimitsPayload.TYPE, SetPodCannonLimitsPayload.STREAM_CODEC, SetPodCannonLimitsPayload::handle);
        registrar.playToServer(RadarLockPayload.TYPE, RadarLockPayload.STREAM_CODEC, RadarLockPayload::handle);
        registrar.playToClient(TelevisionGuidanceStatePayload.TYPE,
                TelevisionGuidanceStatePayload.STREAM_CODEC,
                TelevisionGuidanceStatePayload::handle);
        registrar.playToServer(TelevisionGuidanceInputPayload.TYPE,
                TelevisionGuidanceInputPayload.STREAM_CODEC,
                TelevisionGuidanceInputPayload::handle);
        registrar.playToServer(SetYuushyaCannonTypePayload.TYPE, SetYuushyaCannonTypePayload.STREAM_CODEC,
                SetYuushyaCannonTypePayload::handle);
        registrar.playToClient(BindingSessionPayload.TYPE, BindingSessionPayload.STREAM_CODEC, BindingSessionPayload::handle);
        registrar.playToClient(BindingInspectionPayload.TYPE, BindingInspectionPayload.STREAM_CODEC,
                BindingInspectionPayload::handle);
        registrar.playToClient(OpenAutoDefenseDisplayPayload.TYPE, OpenAutoDefenseDisplayPayload.STREAM_CODEC,
                OpenAutoDefenseDisplayPayload::handle);
        registrar.playToServer(AutoDefenseSettingsPayload.TYPE, AutoDefenseSettingsPayload.STREAM_CODEC,
                AutoDefenseSettingsPayload::handle);
        registrar.playToServer(ConnectedDisplayPitchOffsetPayload.TYPE,
                ConnectedDisplayPitchOffsetPayload.STREAM_CODEC,
                ConnectedDisplayPitchOffsetPayload::handle);
        registrar.playToClient(OpenConnectedDisplayConsolePayload.TYPE,
                OpenConnectedDisplayConsolePayload.STREAM_CODEC,
                OpenConnectedDisplayConsolePayload::handle);
        registrar.playToServer(RequestConnectedDisplayConsoleStatePayload.TYPE,
                RequestConnectedDisplayConsoleStatePayload.STREAM_CODEC,
                RequestConnectedDisplayConsoleStatePayload::handle);
        registrar.playToServer(ConnectedDisplayConsoleActionPayload.TYPE,
                ConnectedDisplayConsoleActionPayload.STREAM_CODEC,
                ConnectedDisplayConsoleActionPayload::handle);
        registrar.playToClient(OpenTigerIEnginePayload.TYPE, OpenTigerIEnginePayload.STREAM_CODEC, OpenTigerIEnginePayload::handle);
        registrar.playToServer(SetTigerIEngineSettingsPayload.TYPE, SetTigerIEngineSettingsPayload.STREAM_CODEC, SetTigerIEngineSettingsPayload::handle);
        registrar.playToClient(OpenZtz99aDieselEnginePayload.TYPE, OpenZtz99aDieselEnginePayload.STREAM_CODEC,
                OpenZtz99aDieselEnginePayload::handle);
        registrar.playToServer(SetZtz99aDieselEngineSettingsPayload.TYPE, SetZtz99aDieselEngineSettingsPayload.STREAM_CODEC,
                SetZtz99aDieselEngineSettingsPayload::handle);
        registrar.playToClient(OpenGasTurbinePayload.TYPE, OpenGasTurbinePayload.STREAM_CODEC,
                OpenGasTurbinePayload::handle);
        registrar.playToServer(SetGasTurbineSettingsPayload.TYPE, SetGasTurbineSettingsPayload.STREAM_CODEC,
                SetGasTurbineSettingsPayload::handle);
        registrar.playToClient(OpenIffTransponderPayload.TYPE,
                OpenIffTransponderPayload.STREAM_CODEC,
                OpenIffTransponderPayload::handle);
        registrar.playToClient(MountedHeavyMachineGunSyncPayload.TYPE,
                MountedHeavyMachineGunSyncPayload.STREAM_CODEC,
                MountedHeavyMachineGunSyncPayload::handle);
        registrar.playToServer(SetIffChannelPayload.TYPE,
                SetIffChannelPayload.STREAM_CODEC,
                SetIffChannelPayload::handle);
    }

    private ModNetworking() {
    }
}
