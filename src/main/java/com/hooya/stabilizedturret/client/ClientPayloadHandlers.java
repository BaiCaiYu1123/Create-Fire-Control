package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.AimSessionPayload;
import com.hooya.stabilizedturret.network.AimGroupSessionPayload;
import com.hooya.stabilizedturret.network.AzLoaderAnimationPayload;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.hooya.stabilizedturret.network.BindingInspectionPayload;
import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import com.hooya.stabilizedturret.network.MianbaoDetonationVisualPayload;
import com.hooya.stabilizedturret.network.MissileImpactRegionPayload;
import com.hooya.stabilizedturret.network.MissileRemoteStatePayload;
import com.hooya.stabilizedturret.network.MountedHeavyMachineGunSyncPayload;
import com.hooya.stabilizedturret.network.MaidLoadingAnimationPayload;
import com.hooya.stabilizedturret.network.MzLoaderAnimationPayload;
import com.hooya.stabilizedturret.network.NuclearShockwavePayload;
import com.hooya.stabilizedturret.network.OpenAutoDefenseDisplayPayload;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import com.hooya.stabilizedturret.network.OpenConnectedDisplayConsolePayload;
import com.hooya.stabilizedturret.network.OpenGasTurbinePayload;
import com.hooya.stabilizedturret.network.OpenIffTransponderPayload;
import com.hooya.stabilizedturret.network.RadarSessionPayload;
import com.hooya.stabilizedturret.network.RemoteKeyBindingResultPayload;
import com.hooya.stabilizedturret.network.TelevisionGuidanceStatePayload;
import net.minecraft.client.Minecraft;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientPayloadHandlers {
    public static void handleWeaponView(com.hooya.stabilizedturret.network.WeaponViewPayload payload) {
        ClientWeaponViewState.accept(payload);
    }
    public static void handleInfrared(com.hooya.stabilizedturret.network.InfraredStatePayload payload) {
        InfraredHudRenderer.accept(payload);
    }
    public static void handleAntiRadiation(com.hooya.stabilizedturret.network.AntiRadiationStatePayload payload) {
        AntiRadiationHudRenderer.accept(payload);
    }
    public static void handleCannonLockLead(com.hooya.stabilizedturret.network.CannonLockLeadPayload payload) {
        ClientCannonLockLead.accept(payload);
    }
    public static void handleBombSight(com.hooya.stabilizedturret.network.BombSightPayload payload) {
        ClientBombSight.accept(payload);
    }
    public static void handleFireControlInputs(com.hooya.stabilizedturret.network.FireControlInputsStatePayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof StabilizerControllerScreen screen) screen.acceptInputState(payload);
    }
    public static void handleAimSession(AimSessionPayload payload) {
        ClientAimState.accept(payload);
    }

    public static void handleScopeOrientation(com.hooya.stabilizedturret.network.ScopeOrientationPayload payload) {
        ClientScopeOrientation.accept(payload);
    }

    public static void handleScopeAmmo(com.hooya.stabilizedturret.network.ScopeAmmoPayload payload) {
        ScopeAmmoHud.accept(payload);
    }

    public static void handleAimGroups(AimGroupSessionPayload payload) {
        ClientAimState.acceptAimGroups(payload);
    }

    public static void handleRadarSession(RadarSessionPayload payload) {
        ClientRadarState.accept(payload);
    }

    public static void handleTelevisionGuidanceState(
            TelevisionGuidanceStatePayload payload
    ) {
        ClientTelevisionGuidanceState.accept(
                payload.active(), payload.missileId(), payload.remainingTicks());
    }

    public static void handleMissileExplosion(MissileExplosionPayload payload) {
        MissileLongRangeEffects.renderExplosion(payload);
    }

    public static void handleMianbaoDetonationVisual(
            MianbaoDetonationVisualPayload payload) {
        MissileLongRangeEffects.renderMianbaoDetonation(payload);
    }

    public static void handleNuclearShockwave(NuclearShockwavePayload payload) {
        MissileLongRangeEffects.renderNuclearShockwave(payload);
    }

    public static void handleMissileImpactRegion(MissileImpactRegionPayload payload) {
        RemoteImpactRenderManager.handleRegion(payload);
    }

    public static void handleMissileRemoteState(MissileRemoteStatePayload payload) {
        MissileLongRangeEffects.acceptRemoteState(payload);
    }

    public static void handleMissileRemoteRemoval(com.hooya.stabilizedturret.network.MissileRemoteRemovalPayload payload) {
        MissileLongRangeEffects.removeRemote(payload);
    }

    public static void handleMzLoaderAnimation(MzLoaderAnimationPayload payload) {
        MzLoaderClientSync.accept(payload);
    }

    public static void handleAzLoaderAnimation(AzLoaderAnimationPayload payload) {
        AzLoaderClientSync.accept(payload);
    }

    public static void handleType10LoaderAnimation(
            com.hooya.stabilizedturret.network.Type10LoaderAnimationPayload payload) {
        Type10LoaderClientSync.accept(payload);
    }

    public static void handleMaidLoadingAnimation(MaidLoadingAnimationPayload payload) {
        MaidLoadingClientState.accept(payload);
    }

    public static void handleBindingSession(BindingSessionPayload payload) {
        ClientBindingState.accept(payload.active(), payload.append(), payload.candidates());
    }

    public static void handleBindingInspection(BindingInspectionPayload payload) {
        ClientBindingInspectionState.accept(payload);
    }

    public static void openController(OpenControllerPayload payload) {
        Minecraft.getInstance().setScreen(new StabilizerControllerScreen(payload));
    }

    public static void handleRemoteKeyBindingResult(RemoteKeyBindingResultPayload payload) {
        if (Minecraft.getInstance().screen instanceof StabilizerControllerScreen screen) {
            screen.acceptRemoteKeyBindingResult(payload);
        }
    }

    public static void handleMountedHeavyMachineGunSync(
            MountedHeavyMachineGunSyncPayload payload
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (minecraft.level.getEntity(payload.entityId())
                instanceof PitchOrientedContraptionEntity entity) {
            com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCompat
                    .applyClientSync(entity, payload.left(), payload.right());
        }
    }

    public static void openAutoDefenseDisplay(OpenAutoDefenseDisplayPayload payload) {
        Minecraft.getInstance().setScreen(new AutoDefenseDisplayScreen(payload));
    }

    public static void openConnectedDisplayConsole(
            OpenConnectedDisplayConsolePayload payload) {
        if (Minecraft.getInstance().screen instanceof ConnectedDisplayConsoleScreen screen
                && screen.matches(payload)) {
            screen.acceptState(payload);
        } else {
            Minecraft.getInstance().setScreen(
                    new ConnectedDisplayConsoleScreen(payload));
        }
    }

    public static void openZtz99aDieselEngine(com.hooya.stabilizedturret.network.OpenZtz99aDieselEnginePayload payload) {
        Minecraft.getInstance().setScreen(new GasTurbineScreen(payload.settings(),
                net.minecraft.network.chat.Component.translatable("screen.create_fire_control.ztz99a_diesel_engine.title"),
                settings -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        com.hooya.stabilizedturret.network.SetZtz99aDieselEngineSettingsPayload.from(settings, payload.subLevelId()))));
    }

    public static void openTigerIEngine(com.hooya.stabilizedturret.network.OpenTigerIEnginePayload payload) {
        Minecraft.getInstance().setScreen(new GasTurbineScreen(payload.settings(),
                net.minecraft.network.chat.Component.translatable("screen.create_fire_control.tiger_i_engine.title"),
                settings -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        com.hooya.stabilizedturret.network.SetTigerIEngineSettingsPayload.from(settings, payload.subLevelId()))));
    }

    public static void openGasTurbine(OpenGasTurbinePayload payload) {
        Minecraft.getInstance().setScreen(new GasTurbineScreen(payload));
    }

    public static void openIffTransponder(OpenIffTransponderPayload payload) {
        Minecraft.getInstance().setScreen(new IffTransponderScreen(payload));
    }

    private ClientPayloadHandlers() {
    }
}
