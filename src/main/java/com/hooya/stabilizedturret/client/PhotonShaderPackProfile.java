package com.hooya.stabilizedturret.client;

import java.util.Locale;

/** Name gate for Photon's optional thermal LOD route, including translated packs. */
public final class PhotonShaderPackProfile {
    public static boolean matches(String packName) {
        if (packName == null) {
            return false;
        }
        String name = packName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("photon") || name.startsWith("photon-")
                || name.startsWith("photon_") || name.startsWith("photon ")
                || name.equals("photon.zip");
    }

    private PhotonShaderPackProfile() {
    }
}
