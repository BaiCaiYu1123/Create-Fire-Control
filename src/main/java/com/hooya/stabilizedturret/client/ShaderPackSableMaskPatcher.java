package com.hooya.stabilizedturret.client;

/**
 * Converts Sable's sky-light-scale uniform into a stable albedo mask in the
 * final Iris ExtendedShader fragment source. Veil exposes the uniform only in
 * the generated vertex stage, so the matching fragment declaration must be
 * added before it can be read there. Uniforms are shared at program link time;
 * no vertex/fragment interface is changed. RGB alone is modified, leaving
 * texture alpha, discard, depth and gbuffer packing to the shader pack.
 */
public final class ShaderPackSableMaskPatcher {
    private static final String MARKER =
            "create_fire_control_shader_pack_sable_mask_v1";
    private static final String BASE_COLOR_ASSIGNMENT = "vec4 base_color =";
    private static final String SABLE_UNIFORM =
            "uniform float SableSkyLightScale;";
    private static final String SABLE_FRAGMENT_TOKEN = "_veil_fragColor";
    private static final String SUBLEVEL_TOKEN = "uniform int onSublevel;";
    private static final String SOLID_MASK = """

                // create_fire_control_shader_pack_sable_mask_v1
                float createFireControlSableSolidMask =
                    step(1.5, SableSkyLightScale);
                float createFireControlSableTextureDetail = clamp(
                    dot(base_color.rgb, vec3(0.299, 0.587, 0.114)),
                    0.0,
                    1.0
                );
                float createFireControlSableMaskValue = mix(
                    0.72,
                    1.0,
                    smoothstep(0.0, 1.0, createFireControlSableTextureDetail)
                );
                base_color.rgb = mix(
                    base_color.rgb,
                    vec3(
                        createFireControlSableMaskValue,
                        0.0,
                        createFireControlSableMaskValue
                    ),
                    createFireControlSableSolidMask
                );
            """;

    public static String patch(String source) {
        if (source == null
                || source.contains(MARKER)
                || !source.contains(BASE_COLOR_ASSIGNMENT)
                || !source.contains(SABLE_FRAGMENT_TOKEN)
                || !source.contains(SUBLEVEL_TOKEN)) {
            return source;
        }
        String declared = source.contains(SABLE_UNIFORM)
                ? source
                : insertAfterVersion(source, SABLE_UNIFORM + "\n");
        return insertAfterEachStatement(
                declared,
                BASE_COLOR_ASSIGNMENT,
                SOLID_MASK
        );
    }

    private static String insertAfterVersion(String source, String insertion) {
        int version = source.indexOf("#version");
        if (version < 0) {
            return insertion + source;
        }
        int lineEnd = source.indexOf('\n', version);
        if (lineEnd < 0) {
            return source + "\n" + insertion;
        }
        int insertionPoint = lineEnd + 1;
        return source.substring(0, insertionPoint)
                + insertion
                + source.substring(insertionPoint);
    }

    private static String insertAfterEachStatement(
            String source,
            String statementStart,
            String insertion
    ) {
        StringBuilder result = new StringBuilder(source.length() + insertion.length());
        int copyFrom = 0;
        int searchFrom = 0;
        boolean matched = false;
        while (true) {
            int start = source.indexOf(statementStart, searchFrom);
            if (start < 0) {
                break;
            }
            int end = source.indexOf(';', start);
            if (end < 0) {
                break;
            }
            int insertionPoint = end + 1;
            result.append(source, copyFrom, insertionPoint);
            result.append(insertion);
            copyFrom = insertionPoint;
            searchFrom = insertionPoint;
            matched = true;
        }
        if (!matched) {
            return source;
        }
        return result.append(source, copyFrom, source.length()).toString();
    }

    private ShaderPackSableMaskPatcher() {
    }
}
