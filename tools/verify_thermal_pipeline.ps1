$ErrorActionPreference = 'Stop'

$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$postChain = Join-Path $workspace 'src\main\resources\assets\create_fire_control\shaders\post\thermal.json'
$thermalShader = Join-Path $workspace 'src\main\resources\assets\create_fire_control\shaders\program\thermal.fsh'
$thermalProgram = Join-Path $workspace 'src\main\resources\assets\create_fire_control\shaders\program\thermal.json'
$shaderPackPostChain = Join-Path $workspace 'src\main\resources\assets\create_fire_control\shaders\post\thermal_shader_pack.json'
$shaderPackThermalShader = Join-Path $workspace 'src\main\resources\assets\create_fire_control\shaders\program\thermal_shader_pack.fsh'
$shaderPackThermalProgram = Join-Path $workspace 'src\main\resources\assets\create_fire_control\shaders\program\thermal_shader_pack.json'
$postProcessor = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\ThermalPostProcessor.java'
$thermalRenderMode = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\ThermalRenderMode.java'
$lightTextureMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\LightTextureMixin.java'
$livingMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\LivingEntityRendererMixin.java'
$sableMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\SubLevelThermalHighlightMixin.java'
$fancySableMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\FancySubLevelThermalHighlightMixin.java'
$sableShadowMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\SableSkyLightShadowsThermalMixin.java'
$highlightPatcher = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\ThermalHighlightShaderPatcher.java'
$shaderPackSablePatcher = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\ShaderPackSableMaskPatcher.java'
$lightmapPatcher = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\ThermalLightmapShaderPatcher.java'
$emissionLightmap = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\ThermalEmissionLightmap.java'
$modelBlockMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\ModelBlockRendererThermalMixin.java'
$sodiumEmissionMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\SodiumThermalEmissionMixin.java'
$sodiumFinalShaderMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\SodiumFinalThermalShaderMixin.java'
$veilShaderMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\VeilThermalHighlightShaderMixin.java'
$vanillaFluidMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\LiquidBlockRendererThermalMixin.java'
$sodiumFluidMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\SodiumThermalFluidEmissionMixin.java'
$sodiumUniformMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\SodiumThermalUniformMixin.java'
$itemEntityMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\ItemEntityRendererThermalMixin.java'
$irisCompositeBypass = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\IrisThermalCompositeBypassMixin.java'
$irisFinalShaderMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\IrisFinalThermalShaderMixin.java'
$irisPipelineBypass = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\IrisThermalPipelineBypassMixin.java'
$irisSodiumBypass = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\IrisThermalSodiumProgramsMixin.java'
$irisFramebufferBridge = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\compat\IrisThermalFramebufferBridge.java'
$voxyFramebufferBridge = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\compat\VoxyThermalFramebufferBridge.java'
$voxyShaderPatcher = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\VoxyThermalShaderPatcher.java'
$voxyShaderMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\VoxyThermalShaderMixin.java'
$voxyDrawMixin = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\VoxyThermalDrawMixin.java'
$mixinConfig = Join-Path $workspace 'src\main\resources\create_fire_control.mixins.json'
$missileEffects = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\MissileLongRangeEffects.java'

foreach ($required in @(
    $postChain,
    $thermalShader,
    $thermalProgram,
    $shaderPackPostChain,
    $shaderPackThermalShader,
    $shaderPackThermalProgram,
    $postProcessor,
    $thermalRenderMode,
    $lightTextureMixin,
    $livingMixin,
    $sableMixin,
    $fancySableMixin,
    $sableShadowMixin,
    $highlightPatcher,
    $shaderPackSablePatcher,
    $lightmapPatcher,
    $emissionLightmap,
    $modelBlockMixin,
    $sodiumEmissionMixin,
    $sodiumFinalShaderMixin,
    $veilShaderMixin,
    $vanillaFluidMixin,
    $sodiumFluidMixin,
    $sodiumUniformMixin,
    $itemEntityMixin,
    $irisCompositeBypass,
    $irisFinalShaderMixin,
    $irisPipelineBypass,
    $irisSodiumBypass,
    $irisFramebufferBridge,
    $voxyFramebufferBridge,
    $voxyShaderPatcher,
    $voxyShaderMixin,
    $voxyDrawMixin,
    $mixinConfig,
    $missileEffects
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required fixed-light thermal file is missing: $required"
    }
}

$chain = Get-Content -LiteralPath $postChain -Raw | ConvertFrom-Json
$passes = @($chain.passes)
$scenePass = @($passes | Where-Object {
    $_.name -eq 'create_fire_control:thermal' -and
    $_.intarget -eq 'minecraft:main'
})
if ($scenePass.Count -ne 1) {
    throw 'Thermal grayscale must read the completed minecraft:main scene exactly once.'
}
foreach ($target in @($chain.targets)) {
    if ($target -isnot [string] -and ($null -ne $target.width -or $null -ne $target.height)) {
        throw 'Thermal targets must retain native main-render-target dimensions.'
    }
}

$shaderPackChain = Get-Content -LiteralPath $shaderPackPostChain -Raw | ConvertFrom-Json
$shaderPackPass = @($shaderPackChain.passes | Where-Object {
    $_.name -eq 'create_fire_control:thermal_shader_pack' -and
    $_.intarget -eq 'minecraft:main'
})
if ($shaderPackPass.Count -ne 1) {
    throw 'Shader-pack fallback must process the completed Iris/Voxy scene exactly once.'
}
$shaderPackText = Get-Content -LiteralPath $shaderPackThermalShader -Raw
if ($shaderPackText -notmatch 'texture\s*\(\s*DiffuseSampler' -or
    $shaderPackText -notmatch 'dot\s*\(.*0\.2126.*0\.7152.*0\.0722' -or
    $shaderPackText -notmatch 'pow\s*\(\s*clamp\(gray' -or
    $shaderPackText -match 'localLuminance|exposure\s*=|heatScore|sableMarkerScore') {
    throw 'Shader-pack safety fallback must be monotonic and contain no inverse local exposure.'
}

$shaderText = Get-Content -LiteralPath $thermalShader -Raw
if ($shaderText -notmatch 'texture\s*\(\s*DiffuseSampler' -or
    $shaderText -notmatch 'dot\s*\(.*0\.2126.*0\.7152.*0\.0722') {
    throw 'Thermal shader is not a direct monochrome conversion of the completed scene.'
}
if ($shaderText -match 'DepthSampler|imageStore|imageLoad|gl_FragDepth|texture\s*\(\s*DiffuseSampler\s*,[^\)]*[\+\-]') {
    throw 'Thermal shader must not alter geometry, depth, alpha ordering, or sample displaced scene pixels.'
}

$lightText = Get-Content -LiteralPath $lightTextureMixin -Raw
foreach ($contract in @('for \(int sky = 0; sky < 16', 'fullBrightWhite', 'block == 15 && sky == 15', '0\.40F \* 255\.0F')) {
    if ($lightText -notmatch $contract) {
        throw "Fixed thermal light-map contract is missing: $contract"
    }
}
if ($lightText -match 'green =|marker =') {
    throw 'Light-map texels must stay neutral; heat markers belong in coordinates.'
}

$postText = Get-Content -LiteralPath $postProcessor -Raw
if ($postText -notmatch 'shaders/post/thermal\.json' -or
    $postText -match 'thermal_shader_pack|isShaderPackThermal' -or
    $postText -match 'glBindImageTexture|glMemoryBarrier') {
    throw 'Thermal post processor must use one fixed-light monotonic pass for both Iris states.'
}
$modeText = Get-Content -LiteralPath $thermalRenderMode -Raw
if ($modeText -notmatch 'isShaderPackInUse' -or
    $modeText -notmatch 'isShaderPackInUseNow' -or
    $modeText -notmatch 'isNativeThermal' -or
    $modeText -notmatch 'isShaderPackThermal' -or
    $modeText -notmatch 'isFixedLightThermal' -or
    $modeText -match 'setShadersEnabled|setShaderPack|reload|destroy') {
    throw 'Dual thermal selection must read Iris state without mutating or reloading it.'
}

$mixins = Get-Content -LiteralPath $mixinConfig -Raw
foreach ($requiredMixin in @(
    'client.LightTextureMixin',
    'client.LivingEntityRendererMixin',
    'client.ModelBlockRendererThermalMixin',
    'client.SodiumThermalEmissionMixin',
    'client.SodiumFinalThermalShaderMixin',
    'client.LiquidBlockRendererThermalMixin',
    'client.SodiumThermalFluidEmissionMixin',
    'client.SodiumThermalUniformMixin',
    'client.ItemEntityRendererThermalMixin',
    'client.IrisThermalCompositeBypassMixin',
    'client.IrisFinalThermalShaderMixin',
    'client.IrisThermalPipelineBypassMixin',
    'client.IrisThermalSodiumProgramsMixin',
    'client.GameRendererThermalModeMixin',
    'client.SubLevelThermalHighlightMixin',
    'client.FancySubLevelThermalHighlightMixin',
    'client.SableSkyLightShadowsThermalMixin',
    'client.VeilThermalHighlightShaderMixin',
    'client.VoxyThermalDrawMixin',
    'client.VoxyThermalShaderMixin'
    'client.VoxyPhotonThermalPipelineMixin'
)) {
    if (-not $mixins.Contains($requiredMixin)) {
        throw "Required fixed-light/highlight mixin is missing: $requiredMixin"
    }
}

foreach ($unsafeVoxyMixin in @(
    'client.VoxyIrisDisableGuardMixin',
    'client.VoxyPipelineDataResetMixin'
)) {
    if ($mixins.Contains($unsafeVoxyMixin)) {
        throw "Unsafe Voxy pipeline mutation is enabled: $unsafeVoxyMixin"
    }
}
$voxyPatcherText = Get-Content -LiteralPath $voxyShaderPatcher -Raw
if ($voxyPatcherText -notmatch 'findLastOutputBefore' -or
    $voxyPatcherText -notmatch 'gbuffer_data_0/fragment_color' -or
    $voxyPatcherText -notmatch 'thermal_gray \* 0\.40' -or
    $voxyPatcherText -match 'getMethod\("free"|free0') {
    throw 'Voxy thermal patch must select the active output without mutating pipeline lifetime.'
}
$voxyDrawText = Get-Content -LiteralPath $voxyDrawMixin -Raw
if ($voxyDrawText -notmatch 'WrapOperation' -or
    $voxyDrawText -notmatch 'method = "renderTerrain"' -or
    $voxyDrawText -notmatch 'method = "renderTranslucent"' -or
    $voxyDrawText -notmatch 'glMultiDrawElementsIndirectCountARB' -or
    $voxyDrawText -notmatch 'glUniform1i') {
    throw 'Voxy thermal draw routing/uniform hooks are missing.'
}
$voxyShaderMixinText = Get-Content -LiteralPath $voxyShaderMixin -Raw
if ($voxyShaderMixinText -notmatch 'patchOpaqueShader' -or
    $voxyShaderMixinText -notmatch 'patchTranslucentShader' -or
    $voxyShaderMixinText -notmatch 'cancellable\s*=\s*true') {
    throw 'Voxy thermal shader patch must safely replace both returned shader sources.'
}
if ($mixins -match 'client\.thermal\.(Flywheel|Sable|Veil|Voxy|PostChain)') {
    throw 'Rejected world-shader material injection is still enabled.'
}
if ($mixins -match 'client\.ParticleEngineThermalMixin') {
    throw 'Thermal mode must not intercept or hide particle rendering.'
}

$irisCompositeText = Get-Content -LiteralPath $irisCompositeBypass -Raw
$irisPipelineText = Get-Content -LiteralPath $irisPipelineBypass -Raw
$irisSodiumText = Get-Content -LiteralPath $irisSodiumBypass -Raw
foreach ($contract in @(
    'renderAll',
    'renderShadows',
    'shouldOverrideShaders',
    'getPhase',
    'finalizeLevelRendering',
    'finalizeGameRendering',
    'depthSourceFb',
    'glBlitFramebuffer'
)) {
    if (($irisCompositeText + $irisPipelineText) -notmatch $contract) {
        throw "Iris zero-reload thermal bypass contract is missing: $contract"
    }
}
if ($irisSodiumText -notmatch 'getProgram' -or
    $irisSodiumText -notmatch 'getFramebuffer' -or
    $irisSodiumText -notmatch 'IrisThermalFramebufferBridge' -or
    $irisSodiumText -notmatch 'setReturnValue\(null\)') {
    throw 'Iris Sodium terrain programs are not bypassed during thermal frames.'
}
$irisBypassText = $irisCompositeText + $irisPipelineText + $irisSodiumText
if ($irisBypassText -notmatch 'ThermalRenderMode\.isShaderPackThermal' -or
    $irisBypassText -match 'ThermalRenderMode\.isNativeThermal|ClientAimState\.isThermal') {
    throw 'Iris bypasses must run only while shader-pack thermal imaging is active.'
}
if ($irisBypassText -match '(setShadersEnabled|setShaderPack|reload|destroy)\s*\(') {
    throw 'Thermal toggling must not change Iris configuration, reload, or destroy pipelines.'
}

$missileEffectsText = Get-Content -LiteralPath $missileEffects -Raw
if ($missileEffectsText -match 'ClientAimState\.isThermal\(\)\s*\|\|\s*trailParticlesRemaining') {
    throw 'Thermal mode must not suppress long-range missile particles.'
}

foreach ($removedParticleFilter in @(
    (Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\ThermalSmokeFilter.java'),
    (Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\mixin\client\ParticleEngineThermalMixin.java')
)) {
    if (Test-Path -LiteralPath $removedParticleFilter) {
        throw "Obsolete thermal particle suppression source still exists: $removedParticleFilter"
    }
}

$highlightText = Get-Content -LiteralPath $highlightPatcher -Raw
foreach ($safeColorContract in @(
    'fragColor\.rgb',
    'createFireControlShaderPackMarker',
    'createFireControlShaderPackMaskValue',
    'createFireControlNativeHighlight',
    'vec3\(0\.88\)',
    'createFireControlHighlight \* 0\.72'
)) {
    if ($highlightText -notmatch $safeColorContract) {
        throw "Sable white-hot floor contract is missing: $safeColorContract"
    }
}
$shaderPackSableText = Get-Content -LiteralPath $shaderPackSablePatcher -Raw
foreach ($shaderPackMaskContract in @(
    'uniform float SableSkyLightScale;',
    '_veil_fragColor',
    'uniform int onSublevel;',
    'insertAfterVersion',
    'createFireControlSableSolidMask',
    'step\(1\.5, SableSkyLightScale\)',
    'createFireControlSableTextureDetail',
    'createFireControlSableMaskValue',
    '0\.72',
    'base_color\.rgb = mix'
)) {
    if ($shaderPackSableText -notmatch $shaderPackMaskContract) {
        throw "Iris shader-pack Sable-mask contract is missing: $shaderPackMaskContract"
    }
}
$sableText = Get-Content -LiteralPath $sableMixin -Raw
if ($sableText -notmatch 'isFixedLightThermal' -or
    $sableText -match '2\.0F|6\.0F, 0\.25F, 6\.0F') {
    throw 'Sable must use the same fixed white-hot path with and without Iris.'
}
$fancySableText = Get-Content -LiteralPath $fancySableMixin -Raw
if ($fancySableText -match 'createDynamicProgram|SableDynamicShaderPatcher|return 30' -or
    $fancySableText -notmatch 'return ThermalRenderMode\.isFixedLightThermal\(\) \? 15' -or
    $fancySableText -match '6\.0F, 0\.25F, 6\.0F') {
    throw 'Sable Fancy rendering must use fixed light and neutral white-hot color.'
}

$lightmapPatchText = Get-Content -LiteralPath $lightmapPatcher -Raw
foreach ($emissionContract in @(
    'flat out vec2 createFireControlThermalHeat',
    'flat in vec2 createFireControlThermalHeat',
    'color\.rgb = mix',
    '0\.62',
    '1\.0',
    'createFireControlFullHeat',
    'step\(247\.5',
    'UV2\.x',
    'IRIS_LIGHT_LEVELS_OUT',
    'gl_MultiTexCoord1\.x',
    'base_color\.rgb = mix',
    'insertAfterStatementInMain',
    'MAIN_PATTERN',
    'createFireControlThermalEnabled',
    '0\.999',
    '4\.0'
)) {
    if ($lightmapPatchText -notmatch $emissionContract) {
        throw "Source-emission white-hot contract is missing: $emissionContract"
    }
}
foreach ($mobileEntityContract in @(
    'VANILLA_ENTITY_SIGNAL',
    'createFireControlEntityHeatSignal',
    'lightMapColor\.rgb = mix',
    'VANILLA_ENTITY_WHITE_HOT_FLOOR',
    'createFireControlEntityThermalFloor'
)) {
    if ($lightmapPatchText -notmatch $mobileEntityContract) {
        throw "Mobile-safe entity thermal contract is missing: $mobileEntityContract"
    }
}
$entityBranchPattern = '(?s)if \(source\.contains\(VANILLA_ENTITY_LIGHT\).*?' +
    'if \(source\.contains\(SODIUM_COLOR\)'
$entityBranch = [regex]::Match($lightmapPatchText, $entityBranchPattern)
if (-not $entityBranch.Success -or $entityBranch.Value -match 'VARYING_(OUT|IN)') {
    throw 'Vanilla entity shaders must reuse lightMapColor instead of adding a varying.'
}
if ($lightmapPatchText -match 'color\.a\s*=|fragColor\.a\s*=|gl_FragDepth|\bdiscard\s*;') {
    throw 'Source-emission white-hot patch must modify RGB only.'
}

$emissionText = Get-Content -LiteralPath $emissionLightmap -Raw
if ($emissionText -notmatch 'MARKER_COORDINATE = 248' -or
    $emissionText -notmatch 'sodiumCoordinate\(emission\)' -or
    $emissionText -notmatch 'LightTexture\.pack\(14') {
    throw 'Source emission must use coordinate low bits without altering ordinary light.'
}

$sodiumEmissionText = Get-Content -LiteralPath $sodiumEmissionMixin -Raw
if ($sodiumEmissionText -notmatch 'encodeSodium') {
    throw 'Sodium block emission must use texel-centre light coordinates.'
}
$sodiumFinalShaderText = Get-Content -LiteralPath $sodiumFinalShaderMixin -Raw
if ($sodiumFinalShaderText -notmatch 'ShaderParser\$ParsedShader;src\(\)' -or
    $sodiumFinalShaderText -notmatch 'ThermalHighlightShaderPatcher\.patch' -or
    $sodiumFinalShaderText -notmatch 'ThermalRenderMode\.isShaderPackInUseNow' -or
    $sodiumFinalShaderText -notmatch 'createFireControl\$isExternalIrisProgram' -or
    $sodiumFinalShaderText -notmatch 'Applied final Sodium thermal' -or
    $sodiumFinalShaderText -notmatch '"iris"\.equals\(name\.getNamespace\(\)\)') {
    throw 'Sodium final compiler source is not guarded by the thermal patch.'
}
$veilShaderText = Get-Content -LiteralPath $veilShaderMixin -Raw
if ($veilShaderText -match 'SodiumShaderProcessor') {
    throw 'Veil must not pre-patch Iris/Sodium shader-pack programs.'
}
foreach ($veilShaderContract in @(
    'VanillaShaderProcessor',
    'ThermalHighlightShaderPatcher\.patch',
    'ThermalRenderMode\.isShaderPackInUseNow'
)) {
    if ($veilShaderText -notmatch $veilShaderContract) {
        throw "Iris ExtendedShader Sable-mask hook is missing: $veilShaderContract"
    }
}
if ($veilShaderText -match 'ShaderPackSableMaskPatcher') {
    throw 'Veil preprocessing must not patch pre-Iris shader-pack source.'
}
if ($sodiumFinalShaderText -match 'ShaderPackSableMaskPatcher') {
    throw 'Sodium GlShader must not patch Iris ExtendedShader source.'
}
$irisFinalShaderText = Get-Content -LiteralPath $irisFinalShaderMixin -Raw
foreach ($irisFinalContract in @(
    'net\.irisshaders\.iris\.gl\.shader\.GlShader',
    'safeShaderSource\(ILjava/lang/CharSequence;\)V',
    'ShaderPackSableMaskPatcher\.patch',
    'final Iris GlShader'
)) {
    if ($irisFinalShaderText -notmatch $irisFinalContract) {
        throw "Final Iris Sable-mask compiler hook is missing: $irisFinalContract"
    }
}
$vanillaFluidText = Get-Content -LiteralPath $vanillaFluidMixin -Raw
$sodiumFluidText = Get-Content -LiteralPath $sodiumFluidMixin -Raw
$sodiumUniformText = Get-Content -LiteralPath $sodiumUniformMixin -Raw
if ($vanillaFluidText -notmatch 'createLegacyBlock\(\)\.getLightEmission' -or
    $vanillaFluidText -notmatch 'encodeBlock') {
    throw 'Vanilla emissive fluids are not connected to thermal source markers.'
}
if ($sodiumFluidText -notmatch 'createLegacyBlock\(\)\.getLightEmission' -or
    $sodiumFluidText -notmatch 'QuadLightData;lm:\[I' -or
    $sodiumFluidText -notmatch 'encodeSodium') {
    throw 'Sodium emissive fluids are not connected to thermal source markers.'
}
if ($sodiumFluidText -match 'ClientAimState\.isThermal') {
    throw 'Sodium fluid markers must survive thermal-mode mesh rebuild timing.'
}
if ($sodiumUniformText -notmatch 'setupState' -or
    $sodiumUniformText -notmatch 'glGetUniformLocation' -or
    $sodiumUniformText -notmatch 'ThermalRenderMode\.isFixedLightThermal') {
    throw 'Sodium thermal shader state uniform is not uploaded at draw time.'
}
$itemEntityText = Get-Content -LiteralPath $itemEntityMixin -Raw
if ($itemEntityText -notmatch 'getItemEmission' -or
    $itemEntityText -notmatch 'encodeBlock') {
    throw 'Emissive dropped items are not connected to thermal source markers.'
}
if ($highlightText -match 'imageStore|imageLoad|gl_FragDepth|discard|\.a\s*=') {
    throw 'Sable white-hot patch must modify RGB only.'
}

$obsoletePatcher = Join-Path $workspace 'src\main\java\com\hooya\stabilizedturret\client\thermal\ThermalShaderPatcher.java'
if (Test-Path -LiteralPath $obsoletePatcher) {
    throw 'Rejected ThermalShaderPatcher source still exists.'
}

Write-Host 'Fixed-light, native-resolution monochrome thermal pipeline checks passed.'
