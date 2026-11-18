param([Parameter(Mandatory)][string]$CompiledJar,
      [Parameter(Mandatory)][string]$TemplateJar,
      [Parameter(Mandatory)][string]$OutputJar)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$taskCompiled = (Resolve-Path -LiteralPath $CompiledJar).Path
$taskTemplate = (Resolve-Path -LiteralPath $TemplateJar).Path
$taskOutput = [IO.Path]::GetFullPath($OutputJar)
if (Test-Path -LiteralPath $taskOutput) { throw 'Output already exists; choose a new artifact path.' }
$taskAllowed = '^com/hooya/stabilizedturret/client/(GasTurbineSoundManager(?:\$[^/]+)?|AbramsFmodReference)\.class$'
function Copy-AbramsZipEntry($archive, $entry) {
    $target = $archive.CreateEntry($entry.FullName, [IO.Compression.CompressionLevel]::Optimal)
    $target.LastWriteTime = $entry.LastWriteTime
    $target.ExternalAttributes = $entry.ExternalAttributes
    $inputStream = $entry.Open(); $outputStream = $target.Open()
    try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
}
function Get-AbramsEntryHash($entry) {
    $stream = $entry.Open(); $sha = [Security.Cryptography.SHA256]::Create()
    try { [Convert]::ToHexString($sha.ComputeHash($stream)) } finally { $sha.Dispose(); $stream.Dispose() }
}
$taskOld = [IO.Compression.ZipFile]::OpenRead($taskTemplate)
$taskNew = [IO.Compression.ZipFile]::OpenRead($taskCompiled)
try {
    if (@($taskOld.Entries | Where-Object { $_.FullName -match '^META-INF/.*\.(SF|RSA|DSA)$' }).Count) {
        throw 'Signed template JAR requires a separate signing workflow.'
    }
    $taskReplacements = @($taskNew.Entries | Where-Object { $_.FullName -match $taskAllowed })
    if ($taskReplacements.Count -lt 6) { throw 'Compiled Abrams class bank is incomplete.' }
    $taskWriter = [IO.Compression.ZipFile]::Open($taskOutput, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach($taskEntry in $taskOld.Entries) {
            if ($taskEntry.FullName -notmatch $taskAllowed) { Copy-AbramsZipEntry $taskWriter $taskEntry }
        }
        foreach($taskEntry in $taskReplacements) { Copy-AbramsZipEntry $taskWriter $taskEntry }
    } finally { $taskWriter.Dispose() }
    $taskCheck = [IO.Compression.ZipFile]::OpenRead($taskOutput)
    try {
        $taskUnchanged = 0
        foreach($taskEntry in $taskOld.Entries) {
            if ($taskEntry.FullName -notmatch $taskAllowed) {
                $taskDelivered = $taskCheck.GetEntry($taskEntry.FullName)
                if ($null -eq $taskDelivered -or (Get-AbramsEntryHash $taskEntry) -ne (Get-AbramsEntryHash $taskDelivered)) {
                    throw ('Unrelated entry changed: ' + $taskEntry.FullName)
                }
                $taskUnchanged++
            }
        }
        foreach($taskEntry in $taskReplacements) {
            if ((Get-AbramsEntryHash $taskEntry) -ne (Get-AbramsEntryHash $taskCheck.GetEntry($taskEntry.FullName))) {
                throw ('Compiled class mismatch: '+$taskEntry.FullName)
            }
        }
    } finally { $taskCheck.Dispose() }
    $taskResult = [pscustomobject]@{Template=$taskTemplate;TemplateSha256=(Get-FileHash -LiteralPath $taskTemplate).Hash;
        Output=$taskOutput;OutputSha256=(Get-FileHash -LiteralPath $taskOutput).Hash;
        ReplacedClasses=@($taskReplacements.FullName);UnchangedEntries=$taskUnchanged}
    $taskResult | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath ($taskOutput+'.verification.json') -Encoding utf8
    $taskResult | ConvertTo-Json -Depth 3
} finally { $taskOld.Dispose(); $taskNew.Dispose() }
