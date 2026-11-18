package com.hooya.stabilizedturret.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carries the fixed thermal light-map marker to the fragment stage. The
 * resulting white-hot floor changes RGB only; alpha, discard and depth stay
 * owned by the original shader.
 */
public final class ThermalLightmapShaderPatcher {
    private static final Pattern MAIN_PATTERN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*\\)"
    );
    private static final String MARKER = "create_fire_control_thermal_heat_v1";
    private static final String VANILLA_TERRAIN_LIGHT =
            "vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);";
    private static final String VANILLA_ENTITY_LIGHT =
            "lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);";
    private static final String SODIUM_LIGHT =
            "v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);";
    private static final String VANILLA_TERRAIN_COLOR =
            "vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;";
    private static final String SODIUM_COLOR = "color *= v_Color;";
    private static final String IRIS_LIGHT_LEVELS_OUT = "out vec2 light_levels;";
    private static final String IRIS_LIGHT_LEVELS_IN = "in vec2 light_levels;";
    private static final String IRIS_LIGHT_LEVEL_ASSIGNMENT =
            "light_levels = clamp(";
    private static final String IRIS_BASE_COLOR = "vec4 base_color =";

    private static final String VARYING_OUT = """
            // create_fire_control_thermal_heat_v1
            flat out vec2 createFireControlThermalHeat;

            """;
    private static final String VARYING_IN = """
            // create_fire_control_thermal_heat_v1
            flat in vec2 createFireControlThermalHeat;

            """;
    private static final String SODIUM_VARYING_IN = VARYING_IN + """
            uniform float createFireControlThermalEnabled;

            """;
    private static final String VANILLA_DECODE = """
                createFireControlThermalHeat = vec2(
                    step(247.5, float(UV2.x)),
                    clamp((float(UV2.y) - 8.0) / 240.0, 0.0, 1.0)
                );
            """;
    /**
     * Vanilla entity shaders already carry lightMapColor from vertex to
     * fragment. Encode the thermal marker in that existing RGB varying instead
     * of adding another interface variable. Some OpenGL ES translation layers
     * (notably MobileGlues on Adreno) link Veil's entity programs incorrectly
     * after an extra flat varying is introduced, leaving Sampler0 and every
     * standard uniform inactive and rendering items solid white.
     */
    private static final String VANILLA_ENTITY_SIGNAL = """
                // create_fire_control_thermal_heat_v1
                float createFireControlEntityHeatMarker =
                    step(247.5, float(UV2.x));
                float createFireControlEntityHeatLevel =
                    clamp((float(UV2.y) - 8.0) / 240.0, 0.0, 1.0);
                float createFireControlEntityHeatSignal =
                    2.0 + createFireControlEntityHeatLevel;
                lightMapColor.rgb = mix(
                    lightMapColor.rgb,
                    vec3(createFireControlEntityHeatSignal),
                    createFireControlEntityHeatMarker
                );
            """;
    private static final String SODIUM_DECODE = """
                vec2 createFireControlThermalCoordinate =
                    _vert_tex_light_coord * 256.0;
                createFireControlThermalHeat = vec2(
                    step(247.5, createFireControlThermalCoordinate.x),
                    clamp(
                        (createFireControlThermalCoordinate.y - 8.0) / 240.0,
                        0.0,
                        1.0
                    )
                );
            """;
    private static final String WHITE_HOT_FLOOR = """
                float createFireControlThermalFloor = mix(
                    0.62,
                    1.0,
                    createFireControlThermalHeat.y
                );
                float createFireControlFullHeat = smoothstep(
                    0.97,
                    0.999,
                    createFireControlThermalHeat.y
                );
                createFireControlThermalFloor = mix(
                    createFireControlThermalFloor,
                    4.0,
                    createFireControlFullHeat
                );
                color.rgb = mix(
                    color.rgb,
                    max(color.rgb, vec3(createFireControlThermalFloor)),
                    createFireControlThermalHeat.x
                );
            """;
    private static final String SODIUM_WHITE_HOT_FLOOR =
            WHITE_HOT_FLOOR.replace(
                    "createFireControlThermalHeat.x\n",
                    "createFireControlThermalHeat.x"
                            + " * createFireControlThermalEnabled\n"
            );
    private static final String VANILLA_ENTITY_WHITE_HOT_FLOOR = """
                // create_fire_control_thermal_heat_v1
                float createFireControlEntityHeatSignal = max(
                    max(lightMapColor.r, lightMapColor.g),
                    lightMapColor.b
                );
                float createFireControlEntityHeatMarker =
                    step(1.5, createFireControlEntityHeatSignal);
                float createFireControlEntityHeatLevel = clamp(
                    createFireControlEntityHeatSignal - 2.0,
                    0.0,
                    1.0
                );
                color.rgb = mix(
                    color.rgb,
                    color.rgb / max(createFireControlEntityHeatSignal, 1.0),
                    createFireControlEntityHeatMarker
                );
                float createFireControlEntityThermalFloor = mix(
                    0.62,
                    1.0,
                    createFireControlEntityHeatLevel
                );
                float createFireControlEntityFullHeat = smoothstep(
                    0.97,
                    0.999,
                    createFireControlEntityHeatLevel
                );
                createFireControlEntityThermalFloor = mix(
                    createFireControlEntityThermalFloor,
                    4.0,
                    createFireControlEntityFullHeat
                );
                color.rgb = mix(
                    color.rgb,
                    max(color.rgb, vec3(createFireControlEntityThermalFloor)),
                    createFireControlEntityHeatMarker
                );
            """;
    private static final String IRIS_COORDINATE_DECODE = """
                createFireControlThermalHeat = vec2(
                    step(247.5, float(gl_MultiTexCoord1.x)),
                    clamp(
                        (float(gl_MultiTexCoord1.y) - 8.0) / 240.0,
                        0.0,
                        1.0
                    )
                );
            """;
    private static final String WHITE_HOT_BASE_COLOR = """
                float createFireControlThermalFloor = mix(
                    0.62,
                    1.0,
                    createFireControlThermalHeat.y
                );
                float createFireControlFullHeat = smoothstep(
                    0.97,
                    0.999,
                    createFireControlThermalHeat.y
                );
                createFireControlThermalFloor = mix(
                    createFireControlThermalFloor,
                    4.0,
                    createFireControlFullHeat
                );
                base_color.rgb = mix(
                    base_color.rgb,
                    max(base_color.rgb, vec3(createFireControlThermalFloor)),
                    createFireControlThermalHeat.x
                );
            """;

    public static String patch(String source) {
        if (source == null || source.contains(MARKER)) {
            return source;
        }
        if (source.contains(SODIUM_LIGHT)) {
            String body = source.replace(
                    SODIUM_LIGHT,
                    "vec4 createFireControlThermalLight = texture("
                            + "u_LightTex, _vert_tex_light_coord);\n"
                            + "    v_Color = _vert_color * createFireControlThermalLight;\n"
                            + SODIUM_DECODE
            );
            return insertBeforeContainingMain(
                    body,
                    "vec2 createFireControlThermalCoordinate",
                    VARYING_OUT
            );
        }
        if (source.contains(VANILLA_TERRAIN_LIGHT)) {
            String body = source.replace(
                    VANILLA_TERRAIN_LIGHT,
                    "vec4 createFireControlThermalLight = "
                            + "minecraft_sample_lightmap(Sampler2, UV2);\n"
                            + "    vertexColor = Color * createFireControlThermalLight;\n"
                            + VANILLA_DECODE
            );
            return insertBeforeContainingMain(
                    body,
                    "vec4 createFireControlThermalLight",
                    VARYING_OUT
            );
        }
        if (source.contains(VANILLA_ENTITY_LIGHT)
                && source.contains("out vec4 lightMapColor;")) {
            return source.replace(
                    VANILLA_ENTITY_LIGHT,
                    VANILLA_ENTITY_LIGHT + "\n" + VANILLA_ENTITY_SIGNAL
            );
        }
        if (source.contains(SODIUM_COLOR)
                && source.contains("in vec4 v_Color;")
                && source.contains("out vec4 fragColor")) {
            String body = source.replace(
                    SODIUM_COLOR,
                    SODIUM_COLOR + "\n" + SODIUM_WHITE_HOT_FLOOR
            );
            return insertBeforeContainingMain(
                    body,
                    "float createFireControlThermalFloor",
                    SODIUM_VARYING_IN
            );
        }
        if (source.contains(VANILLA_TERRAIN_COLOR)
                && source.contains("in vec4 vertexColor;")
                && source.contains("out vec4 fragColor")) {
            String body = source.replace(
                    VANILLA_TERRAIN_COLOR,
                    VANILLA_TERRAIN_COLOR + "\n" + WHITE_HOT_FLOOR
            );
            return insertBeforeContainingMain(
                    body,
                    "float createFireControlThermalFloor",
                    VARYING_IN
            );
        }
        if (source.contains("in vec4 lightMapColor;")
                && source.contains("out vec4 fragColor")
                && source.contains("vec4 color = texture(Sampler0, texCoord0)")) {
            int finalColor = source.lastIndexOf("fragColor =");
            if (finalColor < 0) {
                return source;
            }
            String body = source.substring(0, finalColor)
                    + VANILLA_ENTITY_WHITE_HOT_FLOOR
                    + source.substring(finalColor);
            return body;
        }
        // Veil's GLSL processor reformats Sodium expressions, so exact source
        // strings are no longer stable. Locate the v_Color statement inside
        // main instead and inject after it, where _vert_init() and the light
        // coordinate are already valid.
        if (source.contains("gl_Position")
                && source.contains("v_Color")
                && source.contains("_vert_tex_light_coord")) {
            String body = insertAfterStatementInMain(
                    source,
                    "v_Color",
                    SODIUM_DECODE
            );
            if (body.equals(source)) {
                return source;
            }
            return insertBeforeContainingMain(
                    body,
                    "vec2 createFireControlThermalCoordinate",
                    VARYING_OUT
            );
        }
        if (source.contains("fragColor")
                && source.contains("v_Color")
                && source.contains("vec4 color")) {
            String body = insertAfterStatementInMain(
                    source,
                    "v_Color",
                    SODIUM_WHITE_HOT_FLOOR
            );
            if (body.equals(source)) {
                return source;
            }
            return insertBeforeContainingMain(
                    body,
                    "float createFireControlThermalFloor",
                    SODIUM_VARYING_IN
            );
        }
        // Iris/Veil can replace Sodium's normal v_Color program with a final
        // gbuffer program. Its raw light attribute is still available as
        // gl_MultiTexCoord1, while base_color is the last RGB value before the
        // gbuffer pack. Patch that actual final structure without touching
        // alpha, discard, depth or attachment writes.
        if (source.contains(IRIS_LIGHT_LEVELS_OUT)
                && source.contains(IRIS_LIGHT_LEVEL_ASSIGNMENT)
                && source.contains("gl_MultiTexCoord1")) {
            String body = insertAfterStatement(
                    source,
                    IRIS_LIGHT_LEVEL_ASSIGNMENT,
                    IRIS_COORDINATE_DECODE
            );
            if (body.equals(source)) {
                return source;
            }
            return insertBeforeContainingMain(
                    body,
                    "createFireControlThermalHeat = vec2",
                    VARYING_OUT
            );
        }
        if (source.contains(IRIS_LIGHT_LEVELS_IN)
                && source.contains(IRIS_BASE_COLOR)) {
            String body = insertAfterStatement(
                    source,
                    IRIS_BASE_COLOR,
                    WHITE_HOT_BASE_COLOR
            );
            if (body.equals(source)) {
                return source;
            }
            return insertBeforeContainingMain(
                    body,
                    "float createFireControlThermalFloor",
                    VARYING_IN
            );
        }
        return source;
    }

    private static String insertAfterStatement(
            String source,
            String statementStart,
            String insertion
    ) {
        int start = source.indexOf(statementStart);
        if (start < 0) {
            return source;
        }
        int end = source.indexOf(';', start);
        if (end < 0) {
            return source;
        }
        int insertionPoint = end + 1;
        return source.substring(0, insertionPoint)
                + "\n"
                + insertion
                + source.substring(insertionPoint);
    }

    private static String insertAfterStatementInMain(
            String source,
            String token,
            String insertion
    ) {
        int main = findMain(source);
        if (main < 0) {
            return source;
        }
        int tokenIndex = source.indexOf(token, main);
        if (tokenIndex < 0) {
            return source;
        }
        int end = source.indexOf(';', tokenIndex);
        if (end < 0) {
            return source;
        }
        int insertionPoint = end + 1;
        return source.substring(0, insertionPoint)
                + "\n"
                + insertion
                + source.substring(insertionPoint);
    }

    private static String insertBeforeContainingMain(
            String source,
            String bodyToken,
            String declaration
    ) {
        int token = source.indexOf(bodyToken);
        if (token < 0) {
            return source;
        }
        int main = findContainingMain(source, token);
        if (main < 0) {
            return source;
        }
        return source.substring(0, main) + declaration + source.substring(main);
    }

    /**
     * Iris can concatenate several preprocessor-selected main functions into
     * one source. A declaration placed before the first textual main may land
     * in a disabled branch while the injected RGB code is compiled in a later
     * branch. Keep each varying/uniform beside the main that owns its use.
     */
    private static int findContainingMain(String source, int bodyPosition) {
        Matcher matcher = MAIN_PATTERN.matcher(source);
        int candidate = -1;
        while (matcher.find() && matcher.start() < bodyPosition) {
            candidate = matcher.start();
        }
        if (candidate < 0) {
            return -1;
        }
        int openBrace = source.indexOf('{', candidate);
        if (openBrace < 0 || openBrace > bodyPosition) {
            return -1;
        }
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return bodyPosition < index ? candidate : -1;
            }
        }
        return -1;
    }

    private static int findMain(String source) {
        Matcher matcher = MAIN_PATTERN.matcher(source);
        return matcher.find() ? matcher.start() : -1;
    }

    private ThermalLightmapShaderPatcher() {
    }
}
