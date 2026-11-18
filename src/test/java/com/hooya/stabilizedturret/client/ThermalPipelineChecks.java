package com.hooya.stabilizedturret.client;

import net.minecraft.client.renderer.LightTexture;

public final class ThermalPipelineChecks {
    public static void main(String[] args) {
        checkLightEncoding();
        checkVanillaTerrainPair();
        checkVanillaEntityPair();
        checkSodiumPair();
        checkFormattedSodiumPair();
        checkIrisFinalPair();
        checkSableTextureIndependentMask();
        checkShaderPackSableMask();
        checkVoxyActiveOutputSelection();
        checkPhotonProfileAndTaa();
    }

    private static void checkPhotonProfileAndTaa() {
        require(PhotonShaderPackProfile.matches("Photon-Shaders_System.Object[]_zh_CN.zip"),
                "The supplied translated Photon pack must select its dedicated route");
        require(PhotonShaderPackProfile.matches("Photon_v1.2.zip"), "Versioned Photon must match");
        for (String name : new String[]{"ComplementaryReimagined.zip", "BSL.zip",
                "Not-Photon.zip", "Photonish.zip", "(off)", ""}) {
            require(!PhotonShaderPackProfile.matches(name), "Unrelated pack matched: " + name);
        }
        require(!PhotonShaderPackProfile.matches(null), "Absent Iris must not select Photon");
        for (String function : new String[]{"taaShift", "getTAA"}) {
            String source = "vec2 " + function + "() { return taa_offset * 0.66; }";
            String patched = VoxyThermalShaderPatcher.patchTaa(source, function);
            require(patched.contains("if (createFireControlThermalEnabled != 0) return vec2(0.0)"),
                    "Photon thermal geometry AND culling must disable jitter");
            require(patched.contains("return taa_offset * 0.66"),
                    "Ordinary Photon must retain its exact jitter expression");
            require(VoxyThermalShaderPatcher.patchTaa(patched, function).equals(patched),
                    "TAA patch must be idempotent");
        }
        require(VoxyThermalShaderPatcher.patchTaa(null, "taaShift") == null,
                "Photon with TAA disabled must stay supported");
    }

    private static void checkShaderPackSableMask() {
        String photonFragment = """
                #version 330 core
                in vec2 uv;
                uniform sampler2D gtexture;
                uniform int onSublevel;
                vec4 _veil_fragColor;
                layout(location = 0) out vec4 gbuffer_data_0;
                void main() {
                    vec4 base_color = vec4(_veil_fragColor.rgb, 1.0);
                    if (base_color.a < 0.1) discard;
                    gbuffer_data_0 = base_color;
                }
                """;
        String patched = ShaderPackSableMaskPatcher.patch(photonFragment);
        require(patched.contains("uniform float SableSkyLightScale;"),
                "Iris Sable fragment must declare the shared vertex uniform");
        require(patched.indexOf("uniform float SableSkyLightScale")
                        == patched.lastIndexOf("uniform float SableSkyLightScale"),
                "Iris Sable mask must declare the shared uniform exactly once");
        require(patched.contains("createFireControlSableSolidMask"),
                "Iris Sable fragment must decode the stable Sable uniform");
        require(patched.contains("step(1.5, SableSkyLightScale)"),
                "Iris Sable mask must use the distance-independent uniform");
        require(patched.contains("createFireControlSableMaskValue"),
                "Shader-pack Sable must preserve restrained texture detail");
        require(patched.contains("0.72"),
                "Shader-pack Sable marker must retain a bright minimum");
        require(patched.contains("base_color.rgb = mix"),
                "Iris Sable mask must replace albedo before gbuffer packing");
        require(patched.indexOf("base_color.rgb = mix")
                        < patched.indexOf("gbuffer_data_0 = base_color"),
                "Iris Sable mask must be applied before gbuffer output");
        require(!patched.contains("base_color.a ="),
                "Iris Sable mask must preserve texture alpha");
        require(ShaderPackSableMaskPatcher.patch(patched).equals(patched),
                "Iris Sable mask must be idempotent");

        String ordinaryTerrain = """
                #version 330 core
                in vec2 light_levels;
                void main() {
                    vec4 base_color = vec4(1.0);
                }
                """;
        require(ShaderPackSableMaskPatcher.patch(ordinaryTerrain)
                        .equals(ordinaryTerrain),
                "Ordinary Iris/Sodium player terrain must remain byte-for-byte intact");
    }

    private static void checkSableTextureIndependentMask() {
        String fragment = """
                #version 150
                uniform sampler2D Sampler0;
                uniform vec4 ColorModulator;
                in vec2 texCoord0;
                out vec4 fragColor;
                void main() {
                    vec4 color = texture(Sampler0, texCoord0);
                    if (color.a < 0.1) discard;
                    fragColor = color * ColorModulator;
                }
                """;
        String patched = ThermalHighlightShaderPatcher.patch(fragment);
        require(patched.contains("createFireControlShaderPackMarker"),
                "Sable shader-pack marker must be decoded before output");
        require(patched.contains("createFireControlShaderPackMaskValue"),
                "Shader-pack Sable geometry must retain restrained texture detail");
        require(patched.contains("createFireControlNativeHighlight"),
                "Native Sable must retain its pre-existing highlight path");
        require(patched.contains("createFireControlHighlight * 0.72"),
                "Native Sable must retain its original 72 percent blend");
        require(patched.contains("vec3(0.88)"),
                "Native Sable must retain its original white-hot floor");
        require(patched.contains("texture(Sampler0, texCoord0)"),
                "Sable patch must preserve texture alpha/discard sampling");
        require(ThermalHighlightShaderPatcher.patch(patched).equals(patched),
                "Sable texture-independent mask patch must be idempotent");
    }

    private static void checkVoxyActiveOutputSelection() {
        String photonOpaque = """
                #version 460 core
                #ifndef PATCHED_SHADER
                layout(location = 0) out vec4 outColour;
                #else
                #endif
                layout(location = 0) out vec4 gbuffer_data_0;
                void voxy_emitFragment(VoxyFragmentParameters parameters) {
                    gbuffer_data_0 = vec4(0.0);
                }
                """;
        String patched = VoxyThermalShaderPatcher.patch(photonOpaque, true);
        require(patched.contains("gbuffer_data_0 = vec4(thermal_base.rgb, 1.0)"),
                "Voxy thermal branch must target Photon active opaque output");
        require(!patched.contains("outColour = vec4(thermal_base.rgb"),
                "Voxy thermal branch must not target disabled native output");
        require(VoxyThermalShaderPatcher.patch(patched, true).equals(patched),
                "Voxy thermal shader patch must be idempotent");

        String photonTranslucent = photonOpaque
                .replace("gbuffer_data_0", "fragment_color");
        String translucent = VoxyThermalShaderPatcher.patch(
                photonTranslucent,
                false
        );
        require(translucent.contains(
                        "fragment_color = vec4(thermal_base.rgb, thermal_base.a)"),
                "Voxy translucent thermal branch must preserve texture alpha");
    }

    private static void checkLightEncoding() {
        int source = ThermalEmissionLightmap.encodeBlock(
                LightTexture.pack(7, 12), 5);
        require(LightTexture.block(source) == 15, "source must use marker column");
        require(LightTexture.sky(source) == 5, "source level must be preserved");
        require((source & 0xFF) == 248,
                "vanilla marker must be distinct from ordinary full-bright 240");
        require((source >> 16 & 0xFF) == 88,
                "vanilla emission must use coordinate low-bit marker data");

        int neighbour = ThermalEmissionLightmap.encodeBlock(
                LightTexture.pack(15, 9), 0);
        require(LightTexture.block(neighbour) == 14,
                "world propagated full light must not sample the GUI white cell");

        int ordinary = LightTexture.pack(8, 11);
        require(ThermalEmissionLightmap.encodeBlock(ordinary, 0) == ordinary,
                "ordinary packed light must remain byte-for-byte unchanged");

        int sodiumSource = ThermalEmissionLightmap.encodeSodium(
                LightTexture.pack(7, 12), 5);
        require((sodiumSource & 0xFF) == 248,
                "Sodium source marker must use the centre of texel 15");
        require((sodiumSource >> 16 & 0xFF) == 88,
                "Sodium emission 5 must use the centre of texel 5");

        int sodiumNeighbour = ThermalEmissionLightmap.encodeSodium(
                LightTexture.pack(15, 9), 0);
        require((sodiumNeighbour & 0xFF) == 232,
                "Sodium propagated full light must move to texel 14 centre");
    }

    private static void checkVanillaTerrainPair() {
        String vertex = """
                #version 150
                in ivec2 UV2;
                out vec4 vertexColor;
                void main() {
                    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
                }
                """;
        String fragment = """
                #version 150
                in vec4 vertexColor;
                out vec4 fragColor;
                void main() {
                    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
                    if (color.a < 0.1) discard;
                    fragColor = color;
                }
                """;
        assertPair(vertex, fragment);
    }

    private static void checkVanillaEntityPair() {
        String vertex = """
                #version 150
                in ivec2 UV2;
                out vec4 lightMapColor;
                void main() {
                    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
                }
                """;
        String fragment = """
                #version 150
                in vec4 lightMapColor;
                out vec4 fragColor;
                void main() {
                    vec4 color = texture(Sampler0, texCoord0);
                    color *= lightMapColor;
                    fragColor = color;
                }
                """;
        String patchedVertex = ThermalLightmapShaderPatcher.patch(vertex);
        String patchedFragment = ThermalLightmapShaderPatcher.patch(fragment);
        require(patchedVertex.contains("createFireControlEntityHeatSignal"),
                "Vanilla entity vertex must encode heat in lightMapColor");
        require(patchedVertex.contains("lightMapColor.rgb = mix"),
                "Vanilla entity heat signal must reuse the existing varying");
        require(patchedFragment.contains("createFireControlEntityHeatSignal"),
                "Vanilla entity fragment must decode the embedded heat signal");
        require(patchedFragment.contains("createFireControlEntityThermalFloor"),
                "Vanilla entity heat source must retain its white-hot floor");
        require(patchedFragment.contains("createFireControlEntityFullHeat"),
                "Vanilla entity full emission must retain the physical heat tier");
        require(!patchedVertex.contains("flat out vec2 createFireControlThermalHeat"),
                "Vanilla entity vertex must not add a MobileGlues-unsafe varying");
        require(!patchedFragment.contains("flat in vec2 createFireControlThermalHeat"),
                "Vanilla entity fragment must not add a MobileGlues-unsafe varying");
        require(patchedFragment.contains("texture(Sampler0, texCoord0)"),
                "Vanilla entity patch must preserve item texture sampling");
        require(!patchedFragment.contains("color.a ="),
                "Vanilla entity heat patch must preserve texture alpha");
        require(ThermalLightmapShaderPatcher.patch(patchedVertex)
                        .equals(patchedVertex),
                "Vanilla entity vertex patch must be idempotent");
        require(ThermalLightmapShaderPatcher.patch(patchedFragment)
                        .equals(patchedFragment),
                "Vanilla entity fragment patch must be idempotent");

        String veilPatchedFragment = ThermalHighlightShaderPatcher.patch(fragment);
        require(!veilPatchedFragment.contains(
                        "flat in vec2 createFireControlThermalHeat"),
                "Veil entity patch must keep the original shader interface");
        require(veilPatchedFragment.contains("texture(Sampler0, texCoord0)"),
                "Veil entity patch must keep Sampler0 active");
    }

    private static void checkSodiumPair() {
        String vertex = """
                #version 330 core
                out vec4 v_Color;
                void main() {
                    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
                }
                """;
        String fragment = """
                #version 330 core
                in vec4 v_Color;
                out vec4 fragColor;
                void main() {
                    vec4 color = texture(u_BlockTex, v_TexCoord, lodBias);
                    color *= v_Color;
                    fragColor = color;
                }
                """;
        assertPair(vertex, fragment);
    }

    private static void checkIrisFinalPair() {
        String vertex = """
                #version 330 core
                out vec2 light_levels;
                void main() {
                    light_levels = clamp(gl_MultiTexCoord1.xy
                            * (1.0 / 240.0), 0.0, 1.0);
                }
                """;
        String fragment = """
                #version 330 core
                in vec2 light_levels;
                layout(location = 0) out vec4 gbuffer_data_0;
                void main() {
                    vec4 base_color = texture(gtexture, uv) * tint;
                    if (base_color.a < 0.1) discard;
                    gbuffer_data_0 = base_color;
                }
                """;
        String patchedVertex = ThermalLightmapShaderPatcher.patch(vertex);
        String patchedFragment = ThermalLightmapShaderPatcher.patch(fragment);
        require(patchedVertex.contains("gl_MultiTexCoord1.x"),
                "Iris final vertex source must decode raw marker coordinate");
        require(patchedFragment.contains("base_color.rgb = mix"),
                "Iris final fragment source must apply white-hot RGB");
        require(!patchedFragment.contains("base_color.a ="),
                "Iris final thermal patch must not modify alpha");
    }

    private static void checkFormattedSodiumPair() {
        String vertex = """
                #version 330 core
                out vec4 v_Color ;
                #if 0
                void main ( ) {
                    gl_Position = vec4 ( 0.0 ) ;
                }
                #else
                void main ( ) {
                    _vert_init ( ) ;
                    gl_Position = u_ProjectionMatrix * vec4 ( 1.0 ) ;
                    v_Color = _vert_color * texture ( u_LightTex,
                            _vert_tex_light_coord ) ;
                }
                #endif
                """;
        String fragment = """
                #version 330 core
                in vec4 v_Color ;
                out vec4 fragColor ;
                #if 0
                void main ( ) {
                    fragColor = vec4 ( 0.0 ) ;
                }
                #else
                void main ( ) {
                    vec4 color = texture ( u_BlockTex, v_TexCoord ) ;
                    color *= v_Color ;
                    fragColor = color ;
                }
                #endif
                """;
        assertPair(vertex, fragment);
        String patchedVertex = ThermalLightmapShaderPatcher.patch(vertex);
        String patchedFragment = ThermalLightmapShaderPatcher.patch(fragment);
        require(declarationIsInActiveBranch(
                        patchedVertex,
                        "flat out vec2 createFireControlThermalHeat"),
                "formatted Sodium vertex varying must share the active main branch");
        require(declarationIsInActiveBranch(
                        patchedFragment,
                        "flat in vec2 createFireControlThermalHeat"),
                "formatted Sodium fragment varying must share the active main branch");
        require(patchedFragment.contains(
                        "uniform float createFireControlThermalEnabled"),
                "formatted Sodium fragment must declare thermal state uniform");
        require(patchedFragment.contains(
                        "createFireControlThermalHeat.x * createFireControlThermalEnabled"),
                "Sodium white-hot output must be disabled outside thermal mode");
    }

    private static boolean declarationIsInActiveBranch(
            String source,
            String declaration
    ) {
        int branch = source.indexOf("#else");
        int declarationIndex = source.indexOf(declaration);
        int activeMain = source.indexOf("void main", branch);
        return branch >= 0
                && declarationIndex > branch
                && activeMain > declarationIndex;
    }

    private static void assertPair(String vertex, String fragment) {
        String patchedVertex = ThermalLightmapShaderPatcher.patch(vertex);
        String patchedFragment = ThermalLightmapShaderPatcher.patch(fragment);
        require(patchedVertex.contains("flat out vec2 createFireControlThermalHeat"),
                "vertex heat varying was not added");
        require(patchedFragment.contains("flat in vec2 createFireControlThermalHeat"),
                "fragment heat varying was not added");
        require(patchedFragment.contains("color.rgb = mix"),
                "fragment RGB white-hot floor was not added");
        require(patchedFragment.contains("createFireControlFullHeat"),
                "full-emission white-hot tier was not added");
        require(patchedFragment.contains("4.0"),
                "full emission must match the physical-structure heat tier");
        require(!patchedFragment.contains("color.a ="),
                "thermal patch must never write alpha");
        require(patchedVertex.contains("247.5"),
                "marker must be decoded from coordinate 248, not light-map color");
        require(!patchedVertex.contains("createFireControlThermalLight.r\n"
                        + "                            - createFireControlThermalLight.b"),
                "light-map RGB must not be used as a heat marker");
        require(ThermalLightmapShaderPatcher.patch(patchedVertex)
                        .equals(patchedVertex),
                "vertex patch must be idempotent");
        require(ThermalLightmapShaderPatcher.patch(patchedFragment)
                        .equals(patchedFragment),
                "fragment patch must be idempotent");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private ThermalPipelineChecks() {
    }
}
