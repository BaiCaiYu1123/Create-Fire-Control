package com.hooya.stabilizedturret.client;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a runtime direct-color branch for an Iris-patched Voxy fragment shader. */
public final class VoxyThermalShaderPatcher {
    private static final Pattern COLOR_OUTPUT = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ANY_OUTPUT = Pattern.compile(
            "(?:layout\\s*\\([^;]*?\\)\\s*)?out\\s+vec4\\s+(\\w+)\\s*;",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EMIT_FUNCTION = Pattern.compile(
            "void\\s+voxy_emitFragment\\s*\\(\\s*VoxyFragmentParameters\\s+parameters\\s*\\)\\s*\\{",
            Pattern.CASE_INSENSITIVE
    );
    private static final String UNIFORM = "uniform int createFireControlThermalEnabled;";

    public static String patch(String source, boolean opaque) {
        if (source == null || source.contains(UNIFORM)) {
            return source;
        }
        Matcher emit = EMIT_FUNCTION.matcher(source);
        if (!emit.find()) {
            return source;
        }

        // Voxy's base source retains an outColour declaration inside a
        // disabled #ifndef PATCHED_SHADER branch. Shader-pack declarations
        // are appended later. Select the last location-0 output before the
        // actual emit function so the injected branch targets the compiled
        // gbuffer_data_0/fragment_color variable, never disabled outColour.
        Output output = findLastOutputBefore(COLOR_OUTPUT, source, emit.start());
        if (output == null) {
            output = findLastOutputBefore(ANY_OUTPUT, source, emit.start());
        }
        if (output == null) {
            return source;
        }

        String withUniform = source.substring(0, output.start())
                + UNIFORM + "\n"
                + source.substring(output.start());
        int insertion = emit.end() + UNIFORM.length() + 1;
        String direct = "\n    if (createFireControlThermalEnabled != 0) {"
                + "\n        vec4 thermal_base = parameters.sampledColour * parameters.tinting;"
                + "\n        if (thermal_base.a < 0.1) discard;"
                + "\n        float thermal_gray = dot(max(thermal_base.rgb, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));"
                + "\n        thermal_base.rgb = vec3(clamp(thermal_gray * 0.40, 0.0, 1.0));"
                + "\n        float thermal_source = step(0.90, parameters.lightMap.x);"
                + "\n        thermal_base.rgb = mix(thermal_base.rgb, vec3(1.0), thermal_source);"
                + "\n        " + output.name()
                + " = vec4(thermal_base.rgb, " + (opaque ? "1.0" : "thermal_base.a") + ");"
                + "\n        return;"
                + "\n    }";
        return withUniform.substring(0, insertion)
                + direct
                + withUniform.substring(insertion);
    }

    /** Keeps Voxy geometry and occlusion unjittered when Photon TAA is bypassed. */
    public static String patchTaa(String source, String functionName) {
        if (source == null || source.contains(UNIFORM)) {
            return source;
        }
        Matcher function = Pattern.compile("vec2\\s+" + Pattern.quote(functionName)
                + "\\s*\\(\\s*\\)\\s*\\{").matcher(source);
        if (!function.find()) {
            return source;
        }
        return UNIFORM + "\n" + source.substring(0, function.end())
                + "\n    if (createFireControlThermalEnabled != 0) return vec2(0.0);\n"
                + source.substring(function.end());
    }

    private static Output findLastOutputBefore(
            Pattern pattern,
            String source,
            int limit
    ) {
        Matcher matcher = pattern.matcher(source);
        Output result = null;
        while (matcher.find() && matcher.start() < limit) {
            result = new Output(matcher.start(), matcher.group(1));
        }
        return result;
    }

    private record Output(int start, String name) {
    }

    private VoxyThermalShaderPatcher() {
    }
}
