param(
    [Parameter(Mandatory = $true)]
    [string] $SourceModel,

    [string] $BodyModel,

    [string] $OpenModel
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$assetRoot = Join-Path $projectRoot 'src/main/resources/assets/create_fire_control'
$blockModelDirectory = Join-Path $assetRoot 'models/block/medium_cannon_breech'
$textureDirectory = Join-Path $assetRoot 'textures/block'
$source = Get-Content -Raw -Encoding UTF8 -LiteralPath $SourceModel | ConvertFrom-Json
$bodySource = if ($BodyModel) {
    Get-Content -Raw -Encoding UTF8 -LiteralPath $BodyModel | ConvertFrom-Json
} else {
    $null
}
$openSource = if ($OpenModel) {
    Get-Content -Raw -Encoding UTF8 -LiteralPath $OpenModel | ConvertFrom-Json
} else {
    $null
}

if ($source.meta.model_format -ne 'java_block') {
    throw "Expected a Blockbench java_block model, got '$($source.meta.model_format)'"
}
if ($bodySource -and $bodySource.meta.model_format -ne 'java_block') {
    throw "Expected a Blockbench java_block body model, got '$($bodySource.meta.model_format)'"
}
if ($openSource -and $openSource.meta.model_format -ne 'java_block') {
    throw "Expected a Blockbench java_block open model, got '$($openSource.meta.model_format)'"
}

[System.IO.Directory]::CreateDirectory($blockModelDirectory) | Out-Null
[System.IO.Directory]::CreateDirectory($textureDirectory) | Out-Null

$textureTable = [ordered]@{}
for ($index = 0; $index -lt $source.textures.Count; $index++) {
    $texture = $source.textures[$index]
    if ($texture.source -notmatch '^data:image/png;base64,(.+)$') {
        throw "Texture $index does not contain an embedded PNG"
    }
    $textureName = if ($index -eq 0) { 'medium_cannon_breech' } else { "medium_cannon_breech_$index" }
    $texturePath = Join-Path $textureDirectory ($textureName + '.png')
    [System.IO.File]::WriteAllBytes($texturePath, [Convert]::FromBase64String($Matches[1]))
    $textureTable[[string]$index] = "create_fire_control:block/$textureName"
}
$textureTable['particle'] = $textureTable['0']

$sourceElementsById = @{}
foreach ($element in $source.elements) {
    $sourceElementsById[$element.uuid] = $element
}

function Get-RootElementIds([object] $model) {
    $ids = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $model.outliner) {
        if ($entry -is [string]) {
            $ids.Add($entry)
            continue
        }
        foreach ($child in $entry.children) {
            if ($child -isnot [string]) {
                throw "Nested groups are not supported by this converter"
            }
            $ids.Add($child)
        }
    }
    return $ids
}

$sourceIds = Get-RootElementIds $source
$bodyIds = [System.Collections.Generic.List[string]]::new()
$recoilIds = [System.Collections.Generic.List[string]]::new()
$movingIds = [System.Collections.Generic.List[string]]::new()
$groupsById = @{}
foreach ($group in $source.groups) {
    $groupsById[$group.uuid] = $group
}
$recoilGroupName = -join @([char]0x70AE, [char]0x95E9, [char]0x5236, [char]0x9000, [char]0x90E8, [char]0x5206)
$movingGroupName = -join @([char]0x70AE, [char]0x95E9, [char]0x79FB, [char]0x52A8, [char]0x90E8, [char]0x5206)
foreach ($entry in $source.outliner) {
    if ($entry -is [string] -or -not $groupsById.ContainsKey($entry.uuid)) {
        throw "Expected every breech cube to be inside a named root group"
    }
    $groupName = $groupsById[$entry.uuid].name
    if ($groupName -eq 'group') {
        $target = $bodyIds
    } elseif ($groupName -eq $recoilGroupName) {
        $target = $recoilIds
    } elseif ($groupName -eq $movingGroupName) {
        $target = $movingIds
    } else {
        throw "Unexpected breech root group '$groupName'"
    }
    foreach ($child in $entry.children) {
        if ($child -isnot [string]) {
            throw "Nested groups are not supported by this converter"
        }
        $target.Add($child)
    }
}
$bodyElementsById = $sourceElementsById
if ($sourceIds.Count -ne 43 -or $bodyIds.Count -ne 7 -or
    $recoilIds.Count -ne 27 -or $movingIds.Count -ne 9) {
    throw "Unexpected breech model layout: $($sourceIds.Count) total cubes, $($bodyIds.Count) fixed cubes, $($recoilIds.Count) recoil cubes, $($movingIds.Count) opening cubes"
}

function Convert-Element([object] $sourceElement, [object] $modelSource) {
    $faces = [ordered]@{}
    foreach ($faceProperty in $sourceElement.faces.PSObject.Properties) {
        $sourceFace = $faceProperty.Value
        $textureIndex = [int]$sourceFace.texture
        $uvWidth = [double]$modelSource.resolution.width
        $uvHeight = [double]$modelSource.resolution.height
        $face = [ordered]@{
            uv = @(
                [Math]::Round([double]$sourceFace.uv[0] * 16.0 / $uvWidth, 5),
                [Math]::Round([double]$sourceFace.uv[1] * 16.0 / $uvHeight, 5),
                [Math]::Round([double]$sourceFace.uv[2] * 16.0 / $uvWidth, 5),
                [Math]::Round([double]$sourceFace.uv[3] * 16.0 / $uvHeight, 5)
            )
            texture = "#$textureIndex"
        }
        if ($null -ne $sourceFace.rotation -and [int]$sourceFace.rotation -ne 0) {
            $face['rotation'] = [int]$sourceFace.rotation
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
    return $element
}

function Write-Model(
    [string] $path,
    [System.Collections.Generic.List[string]] $ids,
    [hashtable] $elementsById,
    [object] $modelSource
) {
    $elements = [System.Collections.Generic.List[object]]::new()
    foreach ($id in $ids) {
        $sourceElement = $elementsById[$id]
        if ($sourceElement.type -eq 'cube' -and $sourceElement.export) {
            $elements.Add((Convert-Element $sourceElement $modelSource))
        }
    }
    $model = [ordered]@{
        parent = 'minecraft:block/block'
        ambientocclusion = [bool]$modelSource.ambientocclusion
        gui_light = if ($modelSource.front_gui_light) { 'front' } else { 'side' }
        render_type = 'minecraft:cutout'
        textures = $textureTable
        elements = $elements
    }
    $json = $model | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($path, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

$closedIds = $sourceIds

if ($openSource) {
    $openElementsById = @{}
    foreach ($element in $openSource.elements) {
        $openElementsById[$element.uuid] = $element
    }
    $travel = $null
    foreach ($id in $movingIds) {
        if (-not $openElementsById.ContainsKey($id)) {
            throw "Moving cube $id is not present in the open model"
        }
        $closedElement = $sourceElementsById[$id]
        $openElement = $openElementsById[$id]
        $delta = @(
            ([double]$openElement.from[0] - [double]$closedElement.from[0])
            ([double]$openElement.from[1] - [double]$closedElement.from[1])
            ([double]$openElement.from[2] - [double]$closedElement.from[2])
        )
        for ($axis = 0; $axis -lt 3; $axis++) {
            $toDelta = [double]$openElement.to[$axis] - [double]$closedElement.to[$axis]
            $originDelta = [double]$openElement.origin[$axis] - [double]$closedElement.origin[$axis]
            if ([Math]::Abs($delta[$axis] - $toDelta) -gt 0.00001 -or
                [Math]::Abs($delta[$axis] - $originDelta) -gt 0.00001) {
                throw "Open model deforms moving cube $id instead of translating it"
            }
        }
        if ($null -eq $travel) {
            $travel = $delta
        } elseif ((0..2 | Where-Object { [Math]::Abs($travel[$_] - $delta[$_]) -gt 0.00001 }).Count -gt 0) {
            throw "Moving cubes do not share one open-position translation"
        }
    }
    if ([Math]::Abs($travel[0]) -gt 0.00001 -or [Math]::Abs($travel[2]) -gt 0.00001 -or $travel[1] -ge 0) {
        throw "Expected the open model to move straight down, got $($travel -join ', ')"
    }
    Write-Output "Open travel: $(-$travel[1]) model units ($(-$travel[1] / 16.0) blocks)."
}

Write-Model (Join-Path $blockModelDirectory 'closed.json') $closedIds $sourceElementsById $source
Write-Model (Join-Path $blockModelDirectory 'body.json') $bodyIds $bodyElementsById $(if ($bodySource) { $bodySource } else { $source })
Write-Model (Join-Path $blockModelDirectory 'recoil.json') $recoilIds $sourceElementsById $source
Write-Model (Join-Path $blockModelDirectory 'moving.json') $movingIds $sourceElementsById $source
Write-Output "Converted $($closedIds.Count) cubes: $($bodyIds.Count) fixed, $($recoilIds.Count) recoil, and $($movingIds.Count) opening."
