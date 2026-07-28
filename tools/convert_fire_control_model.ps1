param(
    [Parameter(Mandatory = $true)]
    [string] $SourceModel
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelPath = Join-Path $projectRoot 'src/main/resources/assets/stabilized_turret/models/block/stabilizer_controller.json'
$textureDirectory = Join-Path $projectRoot 'src/main/resources/assets/stabilized_turret/textures/block'
$source = Get-Content -Raw -Encoding UTF8 -LiteralPath $SourceModel | ConvertFrom-Json

if ($source.meta.model_format -ne 'java_block') {
    throw "Expected a Blockbench java_block model, got '$($source.meta.model_format)'"
}

[System.IO.Directory]::CreateDirectory($textureDirectory) | Out-Null

$textureTable = [ordered]@{}
for ($index = 0; $index -lt $source.textures.Count; $index++) {
    $texture = $source.textures[$index]
    if ($texture.source -notmatch '^data:image/png;base64,(.+)$') {
        throw "Texture $index does not contain an embedded PNG"
    }
    $textureName = 'fire_control_computer_{0:d2}' -f $index
    $texturePath = Join-Path $textureDirectory ($textureName + '.png')
    [System.IO.File]::WriteAllBytes($texturePath, [Convert]::FromBase64String($Matches[1]))
    $textureTable[[string]$index] = "stabilized_turret:block/$textureName"
}
$textureTable['particle'] = $textureTable['0']

$elements = [System.Collections.Generic.List[object]]::new()
foreach ($sourceElement in $source.elements) {
    if ($sourceElement.type -ne 'cube' -or -not $sourceElement.export) {
        continue
    }

    $faces = [ordered]@{}
    foreach ($faceProperty in $sourceElement.faces.PSObject.Properties) {
        $sourceFace = $faceProperty.Value
        $textureIndex = [int]$sourceFace.texture
        $texture = $source.textures[$textureIndex]
        $uvWidth = if ($texture.uv_width) { [double]$texture.uv_width } else { [double]$texture.width }
        $uvHeight = if ($texture.uv_height) { [double]$texture.uv_height } else { [double]$texture.height }
        $uv = @(
            [Math]::Round([double]$sourceFace.uv[0] * 16.0 / $uvWidth, 5),
            [Math]::Round([double]$sourceFace.uv[1] * 16.0 / $uvHeight, 5),
            [Math]::Round([double]$sourceFace.uv[2] * 16.0 / $uvWidth, 5),
            [Math]::Round([double]$sourceFace.uv[3] * 16.0 / $uvHeight, 5)
        )
        $face = [ordered]@{
            uv = $uv
            texture = "#$textureIndex"
        }
        if ($null -ne $sourceFace.rotation -and [int]$sourceFace.rotation -ne 0) {
            $face['rotation'] = [int]$sourceFace.rotation
        }
        if ($sourceFace.cullface) {
            $face['cullface'] = [string]$sourceFace.cullface
        }
        if ($null -ne $sourceFace.tintindex) {
            $face['tintindex'] = [int]$sourceFace.tintindex
        }
        $faces[$faceProperty.Name] = $face
    }

    $element = [ordered]@{
        from = @($sourceElement.from | ForEach-Object { [double]$_ })
        to = @($sourceElement.to | ForEach-Object { [double]$_ })
        faces = $faces
    }
    if ($sourceElement.shade -eq $false) {
        $element['shade'] = $false
    }

    if ($sourceElement.rotation) {
        $axisNames = @('x', 'y', 'z')
        for ($axisIndex = 0; $axisIndex -lt 3; $axisIndex++) {
            $angle = [double]$sourceElement.rotation[$axisIndex]
            if ([Math]::Abs($angle) -gt 0.00001) {
                $element['rotation'] = [ordered]@{
                    origin = @($sourceElement.origin | ForEach-Object { [double]$_ })
                    axis = $axisNames[$axisIndex]
                    angle = $angle
                    rescale = [bool]$sourceElement.rescale
                }
                break
            }
        }
    }
    $elements.Add($element)
}

$minecraftModel = [ordered]@{
    parent = 'minecraft:block/block'
    ambientocclusion = [bool]$source.ambientocclusion
    gui_light = if ($source.front_gui_light) { 'front' } else { 'side' }
    render_type = 'minecraft:cutout'
    textures = $textureTable
    elements = $elements
}

$json = $minecraftModel | ConvertTo-Json -Depth 20
[System.IO.File]::WriteAllText($modelPath, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
Write-Output "Converted $($elements.Count) elements and $($source.textures.Count) textures."
