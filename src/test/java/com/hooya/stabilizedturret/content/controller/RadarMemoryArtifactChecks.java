package com.hooya.stabilizedturret.content.controller;

/** Link the mixed delivery, including private renderer methods and their new nested field. */
public final class RadarMemoryArtifactChecks {
    public static void main(String[] args) throws Exception {
        RadarContactMemoryChecks.main(args);
        RadarPackagedArtifactChecks.main(args);
        for (String name : new String[]{"client.ClientRadarState", "client.ClientRadarMemory", "client.RadarHudRenderer",
                "client.ConnectedDisplayRenderer", "client.ConnectedDisplayRenderer$GeometryBatch",
                "content.display.ConnectedDisplayBlockEntity", "network.RadarSessionPayload", "content.controller.RadarLockMemory"}) {
            Class<?> c = Class.forName("com.hooya.stabilizedturret." + name, false, RadarMemoryArtifactChecks.class.getClassLoader());
            c.getDeclaredMethods();
        }
        Class.forName("com.hooya.stabilizedturret.client.ConnectedDisplayRenderer$GeometryBatch",false,
                RadarMemoryArtifactChecks.class.getClassLoader()).getDeclaredField("opacity");
        System.out.println("PASS: actual packaged memory helpers, payload, controller, display and HUD renderer methods verify/link");
    }
}
