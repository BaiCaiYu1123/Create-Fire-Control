package com.hooya.stabilizedturret.client;

/** Replaces marked Sable RGB with a texture-independent thermal mask. */
public final class ThermalHighlightShaderPatcher {
    private static final String MARKER = "create_fire_control_sable_white_hot_v1";
    private static final String WHITE_HOT_FLOOR = """

                // create_fire_control_sable_white_hot_v1
                float createFireControlHighlightControl = max(
                    max(ColorModulator.r, ColorModulator.g),
                    ColorModulator.b
                );
                float createFireControlHighlight = smoothstep(
                    1.5,
                    3.5,
                    createFireControlHighlightControl
                );
                float createFireControlShaderPackMarker = smoothstep(
                    1.0,
                    3.0,
                    min(ColorModulator.r, ColorModulator.b)
                        - ColorModulator.g
                );
                vec3 createFireControlNativeHighlight = mix(
                    fragColor.rgb,
                    vec3(0.88),
                    createFireControlHighlight * 0.72
                );
                float createFireControlShaderPackDetail = clamp(
                    dot(fragColor.rgb, vec3(0.299, 0.587, 0.114)),
                    0.0,
                    1.0
                );
                float createFireControlShaderPackMaskValue = mix(
                    0.72,
                    1.0,
                    smoothstep(0.0, 1.0, createFireControlShaderPackDetail)
                );
                fragColor.rgb = mix(
                    createFireControlNativeHighlight,
                    vec3(
                        createFireControlShaderPackMaskValue,
                        0.0,
                        createFireControlShaderPackMaskValue
                    ),
                    createFireControlShaderPackMarker
                        * createFireControlHighlight
                );
            """;

    public static String patch(String source) {
        String patched = ThermalLightmapShaderPatcher.patch(source);
        if (patched == null
                || patched.contains(MARKER)
                || !patched.contains("ColorModulator")
                || !patched.contains("out vec4 fragColor")
                || !patched.contains("fragColor")) {
            return patched;
        }
        int finalBrace = patched.lastIndexOf('}');
        if (finalBrace < 0) {
            return patched;
        }
        return patched.substring(0, finalBrace)
                + WHITE_HOT_FLOOR
                + patched.substring(finalBrace);
    }

    private ThermalHighlightShaderPatcher() {
    }
}
