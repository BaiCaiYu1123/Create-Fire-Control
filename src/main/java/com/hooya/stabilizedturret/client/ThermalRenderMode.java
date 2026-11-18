package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;

import java.lang.reflect.Method;

/**
 * Selects the thermal implementation without changing Iris configuration or
 * rebuilding either render pipeline.
 */
public final class ThermalRenderMode {
    private static Method irisGetInstance;
    private static Method irisIsShaderPackInUse;
    private static boolean irisLookupComplete;
    private static boolean irisUnavailable;
    private static boolean irisFailureLogged;
    private static boolean shaderPackActive;
    private static boolean photonShaderPackActive;
    private static Method irisGetCurrentPackName;
    private static boolean photonLookupFailed;

    /** Refresh once at the start of every rendered world frame. */
    public static void refresh() {
        boolean next = queryShaderPackActive();
        boolean nextPhoton = next && queryPhotonPack();
        if (next != shaderPackActive || nextPhoton != photonShaderPackActive) {
            shaderPackActive = next;
            photonShaderPackActive = nextPhoton;
            StabilizedTurret.LOGGER.info(
                    "Thermal renderer selected: {}",
                    nextPhoton ? "Photon direct-color Voxy thermal route"
                            : next ? "fixed-light Iris bypass" : "native fixed-light pipeline"
            );
        }
    }

    public static boolean isShaderPackActive() {
        return shaderPackActive;
    }

    /**
     * Reads Iris directly while render programs are being compiled. Program
     * compilation can happen before the next world-frame refresh, so relying
     * on the cached value here can briefly classify an Iris terrain program
     * as native Sodium and corrupt the shader-pack chunk pipeline.
     */
    public static boolean isShaderPackInUseNow() {
        boolean active = queryShaderPackActive();
        shaderPackActive = active;
        photonShaderPackActive = active && queryPhotonPack();
        return active;
    }

    public static boolean isPhotonShaderPackInUseNow() {
        return isShaderPackInUseNow() && photonShaderPackActive;
    }

    public static boolean isPhotonThermal() {
        return isShaderPackThermal() && photonShaderPackActive;
    }

    public static boolean isPhotonShaderPackActive() {
        return shaderPackActive && photonShaderPackActive;
    }

    public static boolean isNativeThermal() {
        return ClientAimState.isThermal() && !shaderPackActive;
    }

    public static boolean isShaderPackThermal() {
        return ClientAimState.isThermal() && shaderPackActive;
    }

    /**
     * True for the fixed-light sensor path regardless of whether Iris remains
     * loaded. A shader pack can stay selected, but its per-frame lighting,
     * composite and final stages are bypassed while this value is true.
     */
    public static boolean isFixedLightThermal() {
        return ClientAimState.isThermal();
    }

    private static boolean queryShaderPackActive() {
        try {
            if (irisUnavailable) {
                return false;
            }
            if (!irisLookupComplete) {
                Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisGetInstance = irisApi.getMethod("getInstance");
                irisIsShaderPackInUse = irisApi.getMethod("isShaderPackInUse");
                irisLookupComplete = true;
            }
            Object api = irisGetInstance.invoke(null);
            return Boolean.TRUE.equals(irisIsShaderPackInUse.invoke(api));
        } catch (ClassNotFoundException ignored) {
            irisLookupComplete = true;
            irisUnavailable = true;
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!irisFailureLogged) {
                irisFailureLogged = true;
                StabilizedTurret.LOGGER.warn(
                        "Unable to read Iris shader-pack state; using native thermal rendering",
                        failure
                );
            }
            return false;
        }
    }

    private static boolean queryPhotonPack() {
        if (photonLookupFailed) {
            return false;
        }
        try {
            if (irisGetCurrentPackName == null) {
                irisGetCurrentPackName = Class.forName("net.irisshaders.iris.Iris")
                        .getMethod("getCurrentPackName");
            }
            return PhotonShaderPackProfile.matches((String) irisGetCurrentPackName.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            photonLookupFailed = true;
            StabilizedTurret.LOGGER.warn(
                    "Unable to identify Photon; retaining the generic Iris thermal route", failure);
            return false;
        }
    }

    private ThermalRenderMode() {
    }
}
