param(
    [Parameter(Mandatory = $true)]
    [string] $SourceModel,
    [string] $TargetModel = 'stabilizer_controller',
    [string] $TexturePrefix = 'fire_control_computer',
    [switch] $UseProjectResolution
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$projectRoot = Split-Path -Parent $PSScriptRoot
$modelPath = Join-Path $projectRoot "src/main/resources/assets/create_fire_control/models/block/$TargetModel.json"
$textureDirectory = Join-Path $projectRoot 'src/main/resources/assets/create_fire_control/textures/block'
$source = Get-Content -Raw -Encoding UTF8 -LiteralPath $SourceModel | ConvertFrom-Json

if ($source.meta.model_format -ne 'java_block') {
    throw "Expected a Blockbench java_block model, got '$($source.meta.model_format)'"
}

[System.IO.Directory]::CreateDirectory($textureDirectory) | Out-Null

$textureUvBounds = @{}
for ($index = 0; $index -lt $source.textures.Count; $index++) {
    $textureUvBounds[[string]$index] = [ordered]@{
        minX = [double]::PositiveInfinity
        maxX = [double]::NegativeInfinity
        minY = [double]::PositiveInfinity
        maxY = [double]::NegativeInfinity
    }
}

foreach ($sourceElement in $source.elements) {
    if ($sourceElement.type -ne 'cube' -or -not $sourceElement.export) {
        continue
    }
    foreach ($faceProperty in $sourceElement.faces.PSObject.Properties) {
        $sourceFace = $faceProperty.Value
        $bounds = $textureUvBounds[[string][int]$sourceFace.texture]
        $bounds.minX = [Math]::Min($bounds.minX, [Math]::Min([double]$sourceFace.uv[0], [double]$sourceFace.uv[2]))
        $bounds.maxX = [Math]::Max($bounds.maxX, [Math]::Max([double]$sourceFace.uv[0], [double]$sourceFace.uv[2]))
        $bounds.minY = [Math]::Min($bounds.minY, [Math]::Min([double]$sourceFace.uv[1], [double]$sourceFace.uv[3]))
        $bounds.maxY = [Math]::Max($bounds.maxY, [Math]::Max([double]$sourceFace.uv[1], [double]$sourceFace.uv[3]))
    }
}

$textureTable = [ordered]@{}
$textureMappings = @{}
for ($index = 0; $index -lt $source.textures.Count; $index++) {
    $texture = $source.textures[$index]
    if ($texture.source -notmatch '^data:image/png;base64,(.+)$') {
        throw "Texture $index does not contain an embedded PNG"
    }

    $uvWidth = if ($UseProjectResolution) { [double]$source.resolution.width }
        elseif ($texture.uv_width) { [double]$texture.uv_width }
        else { [double]$texture.width }
    $uvHeight = if ($UseProjectResolution) { [double]$source.resolution.height }
        elseif ($texture.uv_height) { [double]$texture.uv_height }
        else { [double]$texture.height }
    $bounds = $textureUvBounds[[string]$index]
    $minX = [Math]::Floor($bounds.minX / $uvWidth) * $uvWidth
    $minY = [Math]::Floor($bounds.minY / $uvHeight) * $uvHeight
    $tilesX = 1
    $tilesY = 1
    while ($tilesX * $uvWidth -lt ($bounds.maxX - $minX)) { $tilesX *= 2 }
    while ($tilesY * $uvHeight -lt ($bounds.maxY - $minY)) { $tilesY *= 2 }

    $textureName = ($TexturePrefix + '_{0:d2}') -f $index
    $texturePath = Join-Path $textureDirectory ($textureName + '.png')
    $textureBytes = [Convert]::FromBase64String($Matches[1])
    $stream = [System.IO.MemoryStream]::new([byte[]]$textureBytes)
    $tile = [System.Drawing.Bitmap]::FromStream($stream)
    $targetWidth = [int][Math]::Round($uvWidth * $tilesX)
    $targetHeight = [int][Math]::Round($uvHeight * $tilesY)
    if (($tilesX -eq 1) -and ($tilesY -eq 1) -and
            ($tile.Width -eq $targetWidth) -and ($tile.Height -eq $targetHeight)) {
        $tile.Dispose()
        $stream.Dispose()
        [System.IO.File]::WriteAllBytes($texturePath, $textureBytes)
    } else {
        $tiled = [System.Drawing.Bitmap]::new($targetWidth, $targetHeight)
        $graphics = [System.Drawing.Graphics]::FromImage($tiled)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        for ($tileY = 0; $tileY -lt $tilesY; $tileY++) {
            for ($tileX = 0; $tileX -lt $tilesX; $tileX++) {
                $graphics.DrawImage($tile,
                    [int]($tileX * $uvWidth), [int]($tileY * $uvHeight),
                    [int]$uvWidth, [int]$uvHeight)
            }
        }
        $tiled.Save($texturePath, [System.Drawing.Imaging.ImageFormat]::Png)
        $graphics.Dispose()
        $tiled.Dispose()
        $tile.Dispose()
        $stream.Dispose()
    }

    $textureMappings[[string]$index] = [ordered]@{
        minX = $minX
        minY = $minY
        width = $uvWidth * $tilesX
        height = $uvHeight * $tilesY
    }
    $textureTable[[string]$index] = "create_fire_control:block/$textureName"
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
        $mapping = $textureMappings[[string]$textureIndex]
        $uv = @(
            [Math]::Round(([double]$sourceFace.uv[0] - $mapping.minX) * 16.0 / $mapping.width, 5),
            [Math]::Round(([double]$sourceFace.uv[1] - $mapping.minY) * 16.0 / $mapping.height, 5),
            [Math]::Round(([double]$sourceFace.uv[2] - $mapping.minX) * 16.0 / $mapping.width, 5),
            [Math]::Round(([double]$sourceFace.uv[3] - $mapping.minY) * 16.0 / $mapping.height, 5)
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
