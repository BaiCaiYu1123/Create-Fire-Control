package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.AimSessionPayload;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.hooya.stabilizedturret.network.MissileExplosionPayload;
import com.hooya.stabilizedturret.network.MissileRemoteStatePayload;
import com.hooya.stabilizedturret.network.OpenAutoDefenseDisplayPayload;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import com.hooya.stabilizedturret.network.OpenGasTurbinePayload;
import com.hooya.stabilizedturret.network.RadarSessionPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientPayloadHandlers {
    public static void handleAimSession(AimSessionPayload payload) {
        ClientAimState.accept(payload);
    }

    public static void handleRadarSession(RadarSessionPayload payload) {
        ClientRadarState.accept(payload);
    }

    public static void handleMissileExplosion(MissileExplosionPayload payload) {
        MissileLongRangeEffects.renderExplosion(payload);
    }

    public static void handleMissileRemoteState(MissileRemoteStatePayload payload) {
        MissileLongRangeEffects.acceptRemoteState(payload);
    }

    public static void handleBindingSession(BindingSessionPayload payload) {
        ClientBindingState.accept(payload.active(), payload.candidates());
    }

    public static void openController(OpenControllerPayload payload) {
        Minecraft.getInstance().setScreen(new StabilizerControllerScreen(payload));
    }

    public static void openAutoDefenseDisplay(OpenAutoDefenseDisplayPayload payload) {
        Minecraft.getInstance().setScreen(new AutoDefenseDisplayScreen(payload));
    }

    public static void openGasTurbine(OpenGasTurbinePayload payload) {
        Minecraft.getInstance().setScreen(new GasTurbineScreen(payload));
    }

    private ClientPayloadHandlers() {
    }
}
