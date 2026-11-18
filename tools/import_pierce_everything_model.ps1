param(
    [string] $SourceModel = (Join-Path $PSScriptRoot '../reference/pierce_everything/source.bbmodel'),
    [string] $NodeExecutable = 'node'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$converter = Join-Path $PSScriptRoot 'convert_item_bbmodel_to_obj.js'
& $NodeExecutable $converter $SourceModel annihilation_medium_cannon_cartridge annihilation_cartridge vertical
if ($LASTEXITCODE -ne 0) { throw 'Cartridge conversion failed' }
& $NodeExecutable $converter $SourceModel annihilation_medium_cannon_round annihilation_cartridge projectile
if ($LASTEXITCODE -ne 0) { throw 'Round conversion failed' }

# Keep the supplied Blockbench hand/head transforms on the complete cartridge.
# Its source has no GUI transform; the converter fits that view automatically.
$source = Get-Content -LiteralPath $SourceModel -Raw -Encoding UTF8 | ConvertFrom-Json
$modelPath = Join-Path $projectRoot 'src/main/resources/assets/create_fire_control/models/item/annihilation_medium_cannon_cartridge.json'
$model = Get-Content -LiteralPath $modelPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($context in $source.display.PSObject.Properties) {
    $model.display | Add-Member -MemberType NoteProperty -Name $context.Name -Value $context.Value -Force
}
[IO.File]::WriteAllText($modelPath, ($model | ConvertTo-Json -Depth 12) + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
