param(
    [Parameter(Mandatory=$true)][string]$Jar,
    [Parameter(Mandatory=$true)][string]$Report
)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$mainRoot=Join-Path $workspaceRoot 'build/classes/java/main'
$archive=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Jar).Path)
function Get-EntryText($name) {
    $entry=$archive.GetEntry($name)
    if($null -eq $entry){throw "Missing archive entry: $name"}
    $reader=[IO.StreamReader]::new($entry.Open())
    try{return $reader.ReadToEnd()}finally{$reader.Dispose()}
}
try {
    $names=@($archive.Entries | ForEach-Object {$_.FullName})
    foreach($name in $names){
        if($name -match '\.(dll|so|dylib)$' -or $name.StartsWith('org/jcodec/') -or $name.StartsWith('net/sourceforge/jaad/')){
            throw "Forbidden native runtime or unrelocated codec: $name"
        }
    }
    $checked=0
    $productionRoot=Join-Path $mainRoot 'com/hooya/stabilizedturret'
    foreach($class in Get-ChildItem -LiteralPath $productionRoot -Recurse -File -Filter '*.class') {
        $name=$class.FullName.Substring($mainRoot.Length+1).Replace('\','/')
        if($name -notmatch '/StabilizedTurret\$?[^/]*\.class$|/content/bearing/|/content/controller/(CannonBallisticsCompat|CannonSightDirection|TurretAimConvergence|StabilizerControllerBlockEntity|StabilizerControllerBlock\$?[^/]*\.class)|/compat/simulated/SimulatedPhysicsStaffCompat|/compat/synaxis/SynaxisWeldCompat|/client/(ClientAimState|CannonRenderFrame|CannonMarkerSurface|ClientSableMotionHistory|ThirdPersonAimHudRenderer|ScopeHudRenderer|StabilizerControllerScreen)|/network/(AimDirectionPayload|AimGroupSessionPayload|ModNetworking|OpenControllerPayload|ControllerSettingsPayload)|/mixin/(RapierServo|SwivelBearingMotor|PitchStructureCollision|PhysicsStaff|client/(SableSnapshotMotion|CameraPosition|CameraMixin))'){continue}
        $entry=$archive.GetEntry($name)
        if($null -eq $entry){throw "Production class missing: $name"}
        $stream=$entry.Open()
        try{$packedHash=(Get-FileHash -InputStream $stream -Algorithm SHA256).Hash}finally{$stream.Dispose()}
        if($packedHash -ne (Get-FileHash -LiteralPath $class.FullName -Algorithm SHA256).Hash){throw "Stale production class: $name"}
        $checked++
    }
    foreach($sourceSet in @('test','nativeBearing','bearingIntegration','pitchCollisionIntegration')){
        $testRoot=Join-Path $workspaceRoot "build/classes/java/$sourceSet"
        if(Test-Path -LiteralPath $testRoot){
            foreach($class in Get-ChildItem -LiteralPath $testRoot -Recurse -File -Filter '*.class'){
                $name=$class.FullName.Substring($testRoot.Length+1).Replace('\','/')
                if($null -ne $archive.GetEntry($name)){throw "Test class leaked into release: $name"}
            }
        }
    }
    foreach($name in @('NativeBearingJoint','ArticulatedServoPredictor','ArticulatedServoSolver','FiniteAimTarget',
            'LegacyBearingStabilizer','LegacyNestedBearingConstraintFollower','LegacyFiniteAimSolver',
            'PitchStructureCollision')){
        if($null -eq $archive.GetEntry("com/hooya/stabilizedturret/content/bearing/$name.class")){throw "Required servo class missing: $name"}
    }
    $mixins=(Get-EntryText 'create_fire_control.mixins.json') | ConvertFrom-Json
    foreach($name in @('RapierServoConstraintMixin','RapierServoFrameMixin','RapierServoPlanningMixin','SwivelBearingMotorMixin',
            'PhysicsStaffServerHandlerAccessor','PhysicsStaffDragSessionAccessor','PitchStructureCollisionMixin')){
        if($mixins.mixins -notcontains $name){throw "Required mixin not registered: $name"}
    }
    if($mixins.client -notcontains 'client.SableSnapshotMotionMixin'){throw 'Client Sable velocity hook not registered'}
    foreach($name in @('client.CameraMixin','client.CameraPositionMixin')){
        if($mixins.client -notcontains $name){throw "Client camera hook not registered: $name"}
    }
    $chinese=(Get-EntryText 'assets/create_fire_control/lang/zh_cn.json') | ConvertFrom-Json
    if($chinese.'screen.create_fire_control.stabilizer_implementation' -ne '是否启用反作用力:%s'){
        throw 'Stabilizer switch label missing'
    }
    $sourceChinese=Get-Content -LiteralPath (Join-Path $workspaceRoot 'src/main/resources/assets/create_fire_control/lang/zh_cn.json') -Raw | ConvertFrom-Json
    foreach($key in @('screen.create_fire_control.mobile_shot_compensation',
            'screen.create_fire_control.mobile_shot_compensation.tooltip')){
        if([string]::IsNullOrWhiteSpace($chinese.$key) -or $chinese.$key -ne $sourceChinese.$key){
            throw "Stale or missing muzzle compensation resource: $key"
        }
    }
    if($chinese.'screen.create_fire_control.stabilizer_implementation.reaction.tooltip' -ne '炮塔以及炮管都会传递反作用力 稳定性待商榷 如果出现问题请切换到无反作用力模式' -or
            $chinese.'screen.create_fire_control.stabilizer_implementation.no_reaction.tooltip' -ne '炮塔以及炮管无任何反作用力 运行稳定 在有反作用力模式情况下出现bug请切换此模式'){
        throw 'Stabilizer switch tooltips differ from requested wording'
    }
    if(-not ($names | Where-Object {$_.StartsWith('com/hooya/stabilizedturret/internal/jcodec/')})){throw 'Relocated codec missing'}
    $hash=(Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash
    $result="PASS: $checked production servo/controller/marker classes match final Gradle output byte for byte. Required classes and mixins registered; test classes and native runtimes excluded; codecs relocated.`nSHA256: $hash`n"
    Set-Content -LiteralPath $Report -Value $result -Encoding UTF8
    Write-Output $result
} finally {$archive.Dispose()}
