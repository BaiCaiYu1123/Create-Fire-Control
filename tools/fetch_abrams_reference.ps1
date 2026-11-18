param([string]$OutputDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) '.diagnostics\abrams-reference'))
$ErrorActionPreference = 'Stop'
$taskCommit = '07fd12efffbf20b2053d630ed5cf7ca3ddfa54a6'
$taskBase = 'https://raw.githubusercontent.com/GaijinEntertainment/fmod_studio_warthunder_for_modders/' + $taskCommit + '/'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$taskFiles = @{'abrams-engine.xml' = 'Metadata/Event/%7B2503c204-9bba-4a87-ab7a-8c04fc67ac34%7D.xml'}
foreach ($taskName in @('engine_00','engine_00_load','engine_33','engine_33_load','engine_66','engine_66_load','engine_99','engine_99_load','engine_start_load','engine_stop_load')) {
    $taskFiles[$taskName + '.wav'] = 'Assets/tanks/engines/abrams/update/' + $taskName + '.wav'
}
foreach ($taskId in @('{8c139238-1a32-4f98-b84c-ea8a8ef6058e}','{a701e516-548f-0913-3867-fabe0a3702d7}','{bebc3e0c-debc-4351-b1cb-ce72fb6141bf}','{d06e714d-b261-4f81-a57b-b42d7461620c}')) {
    $taskFiles['parameter-' + $taskId + '.xml'] = 'Metadata/ParameterPreset/' + [Uri]::EscapeDataString($taskId) + '.xml'
}
foreach ($taskFile in $taskFiles.GetEnumerator()) {
    Invoke-WebRequest -Uri ($taskBase + $taskFile.Value) -OutFile (Join-Path $OutputDirectory $taskFile.Key)
}
Set-Content -LiteralPath (Join-Path $OutputDirectory 'upstream-commit.txt') -Value $taskCommit -Encoding utf8
Write-Output ('Saved read-only reference downloads to ' + $OutputDirectory)
