param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent), [switch]$Offline, [string]$PackagedJar)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
if ($PackagedJar) { $taskPackagedJar = (Resolve-Path -LiteralPath $PackagedJar).Path }
$taskCheckDir = Join-Path $taskRoot '.diagnostics\abrams-checks'
$taskBaselineDir = Join-Path $taskCheckDir 'baseline\com\hooya\stabilizedturret\client'
New-Item -ItemType Directory -Path $taskBaselineDir -Force | Out-Null
$taskBaselineFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'docs\abrams-sound\baseline\GasTurbineSoundManager.java'
$taskBaselineText = (Get-Content -LiteralPath $taskBaselineFile -Raw).Replace('GasTurbineSoundManager','AbramsLegacySoundBaseline')
Set-Content -LiteralPath (Join-Path $taskBaselineDir 'AbramsLegacySoundBaseline.java') -Value $taskBaselineText -Encoding utf8
Push-Location $taskRoot
try {
    foreach ($taskMode in @('default','legacy','fmod_reference')) {
        $taskExpectedMode = if ($taskMode -eq 'default') { 'fmod_reference' } else { $taskMode }
        $taskArgs = @('checkAbramsMix','-I',(Join-Path $PSScriptRoot 'abrams-sound-checks.init.gradle'),
            '--no-configuration-cache',('-PabramsExpectedMix=' + $taskExpectedMode),
            ('-PabramsBaselineSource=' + (Join-Path $taskCheckDir 'baseline')),
            ('-PabramsMixOutput=' + (Join-Path $taskCheckDir ($taskMode + '-mix.csv'))))
        if ($taskMode -ne 'default') { $taskArgs += '-PabramsMix=' + $taskMode }
        if ($PackagedJar) { $taskArgs += '-PabramsPackagedJar=' + $taskPackagedJar }
        if ($Offline) { $taskArgs += '--offline' }
        & (Join-Path $taskRoot 'gradlew.bat') @taskArgs
        if ($LASTEXITCODE -ne 0) { throw ('Abrams ' + $taskMode + ' checks failed with exit ' + $LASTEXITCODE) }
    }
} finally { Pop-Location }
