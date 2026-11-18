param(
    [ValidateSet('legacy','fmod_reference')][string]$Mix = 'fmod_reference',
    [Nullable[float]]$Load,
    [Nullable[float]]$Rpm,
    [switch]$NoDebug,
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent),
    [switch]$DryRun,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if ($null -ne $Load -and ([float]::IsNaN($Load) -or [float]::IsInfinity($Load) -or $Load -lt 0 -or $Load -gt 1)) {
    throw 'Load must be a finite number from 0 to 1.'
}
$taskRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
if ($null -ne $Rpm -and ([float]::IsNaN($Rpm) -or [float]::IsInfinity($Rpm) -or $Rpm -lt 0 -or $Rpm -gt 100)) {
    throw 'Rpm must be a finite percent from 0 to 100; only sound parameters are overridden.'
}
$taskArgs = @('runClient','-I',(Join-Path $PSScriptRoot 'abrams-sound-ab.init.gradle'),
    '--no-configuration-cache',('-PabramsMix=' + $Mix),'-PrunRoot=run_abrams_ab')
if ($null -ne $Load) { $taskArgs += '-PabramsDebugLoad=' + ([float]$Load).ToString([Globalization.CultureInfo]::InvariantCulture) }
if ($null -ne $Rpm) { $taskArgs += '-PabramsDebugRpm=' + ([float]$Rpm).ToString([Globalization.CultureInfo]::InvariantCulture) }
if ($NoDebug) { $taskArgs += '-PabramsSoundDebug=false' }
if ($DryRun) { $taskArgs += '--dry-run' }
if ($Offline) { $taskArgs += '--offline' }
Push-Location $taskRoot
try {
    & (Join-Path $taskRoot 'gradlew.bat') @taskArgs
    if ($LASTEXITCODE -ne 0) { throw ('Abrams client launch failed with exit ' + $LASTEXITCODE) }
} finally { Pop-Location }
