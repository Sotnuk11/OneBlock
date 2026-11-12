param([switch]$Apply)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
function Get-RelativePath([string]$base,[string]$path){$path.Substring($base.TrimEnd('\','/').Length+1)}
$project=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$build=[IO.Path]::GetFullPath((Join-Path $project 'build'))
if(!(Test-Path -LiteralPath $build -PathType Container)){throw 'Build folder missing'}
function Assert-BuildPath([string]$path){
    $full=[IO.Path]::GetFullPath($path)
    if(!$full.StartsWith($build+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw "Outside build: $full"}
    $at=$full
    while($at.Length -ge $build.Length){
        if((Test-Path -LiteralPath $at) -and ((Get-Item -LiteralPath $at -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Reparse point: $at"}
        $at=Split-Path -Parent $at
    }
    return $full
}
$keepDirs=@('archive','backups','classes','combat-stage2-install','folia-smoke-artifacts','kotlin','libs','reports','release10','resource-pack','resources','test-results','tmp')
$keepJars=@('one-block-1.0.0-rc.4-test.1.jar','one-block-1.0.0-rc.3-activities.1.jar','one-block-1.0.0-rc.3-rewards.1.jar','one-block-1.0.0-rc.3-endless.1.jar','one-block-1.0.0-rc.3-config-recovery.1.jar','one-block-1.0.0-rc.3-combat.2.jar','one-block-1.0.0-rc.3-stability.1.jar','one-block-1.0.0-rc.3-polish.1.jar')
# Найновіша збірка лишається завжди, навіть якщо її ще немає в списку вище.
$keepJars+=@(Get-ChildItem -LiteralPath (Join-Path $build 'libs') -File -Filter '*.jar' | Where-Object {$_.Name -notlike '*-plain.jar'} | Sort-Object LastWriteTime -Descending | Select-Object -First 1 | ForEach-Object {$_.Name})
$keepReports=@('release10-full-suite','activities-full-suite','rewards-full-suite','endless-full-suite','config-recovery-full-suite','combat-stage2-full-suite','tests')
$runGroups=@('folia-release-smoke','folia-activities-smoke','folia-rewards-smoke','folia-endless-smoke','folia-combat-smoke','folia-hub-smoke','folia-items-smoke','folia-mine-smoke','folia-storage-smoke','startup-native-regression')
$discardDirs=@(Get-ChildItem -LiteralPath $build -Directory -Force | Where-Object {$_.Name -notin $keepDirs})
$discardFiles=@(Get-ChildItem -LiteralPath $build -File -Force | Where-Object {$_.Name -notmatch '^cleanup-|^README\.md$|^release10-'})
$discardJars=@(Get-ChildItem -LiteralPath (Join-Path $build 'libs') -File | Where-Object {$_.Name -notin $keepJars})
$discardReports=@(Get-ChildItem -LiteralPath (Join-Path $build 'reports') -Force | Where-Object {
    if($_.PSIsContainer){$_.Name -notin $keepReports}else{$_.Name -notmatch 'verification\.json$|^release10-|^cleanup-|^ARCHIVED\.md$'}
})
$targets=@($discardDirs)+@($discardFiles)+@($discardJars)+@($discardReports)
foreach($t in $targets){$null=Assert-BuildPath $t.FullName}
if(!$Apply){[pscustomobject]@{Targets=$targets.Count;Directories=$discardDirs.Count;OldJars=$discardJars.Count;OldReportEntries=$discardReports.Count;Scope=$build};return}
# Read only process metadata; never print command lines or credentials.
$usingBuild=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -and $_.CommandLine.Contains($build)})
if($usingBuild.Count){throw "Java processes reference build; preserve active work. PIDs: $($usingBuild.ProcessId -join ', ')"}
foreach($dir in $discardDirs+@($discardReports | Where-Object PSIsContainer)){
    $links=@(Get-ChildItem -LiteralPath $dir.FullName -Recurse -Force | Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint})
    if($links.Count){throw "Reparse points inside deletion target: $($dir.FullName)"}
}
$beforeFiles=Get-ChildItem -LiteralPath $build -Recurse -File -Force | Measure-Object Length -Sum
$archiveDir=Join-Path $build 'archive';[IO.Directory]::CreateDirectory($archiveDir)|Out-Null
$archivePath=Join-Path $archiveDir ('verification-history-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.zip')
$null=Assert-BuildPath $archivePath
$records=[Collections.Generic.List[object]]::new()
$entries=@{};$knownJars=@{}
foreach($name in $keepJars){$p=Join-Path $build "libs/$name";if(Test-Path -LiteralPath $p){$knownJars[(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash]=('kept:build/libs/'+$name)}}
$stream=[IO.File]::Open($archivePath,[IO.FileMode]::CreateNew)
$zip=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create)
function Add-Evidence([IO.FileInfo]$file){
    $null=Assert-BuildPath $file.FullName
    if($file.Name -eq '.env' -or $file.Name -like '.env.*' -or $file.Name -eq 'client.args' -or $file.Name -match '^(launcher_accounts|accounts)\.json$'){return}
    $relative=(Get-RelativePath $project $file.FullName).Replace('\','/')
    $hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if($file.Extension -eq '.jar'){
        if($knownJars.ContainsKey($hash)){$entry=$knownJars[$hash]}else{$entry="jars/$hash.jar";$knownJars[$hash]=$entry}
    }else{$entry=$relative}
    if(!$entry.StartsWith('kept:') -and !$entries.ContainsKey($entry)){
        $null=[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$entry,[IO.Compression.CompressionLevel]::Fastest)
        $entries[$entry]=$hash
    }
    $records.Add([pscustomobject]@{Original=$relative;Entry=$entry;Bytes=$file.Length;Sha256=$hash})
}
try {
    foreach($dir in $discardDirs){
        if($dir.Name -match '^gradle-(taskhome|user-home|temp)$'){continue}
        foreach($file in Get-ChildItem -LiteralPath $dir.FullName -File -Recurse -Force){
            if($dir.Name -in $runGroups){
                $parts=(Get-RelativePath $dir.FullName $file.FullName).Split([IO.Path]::DirectorySeparatorChar)
                # run-ID/top-level metadata, logs, configs and all plugin journals; omit reusable runtime/world binaries.
                if($parts.Length -eq 2){if($file.Extension -in @('.jar','.hprof','.dmp')){continue}}
                elseif($parts[1] -notin @('logs','plugins','config','crash-reports')){continue}
                if($parts -contains '.paper-remapped' -or $file.Extension -eq '.mca'){continue}
                if($file.Extension -eq '.jar' -and $file.Name -ne 'one-block.jar'){continue}
            }elseif($dir.Name -in @('visual-client','combat-visual-client')){
                $parts=(Get-RelativePath $dir.FullName $file.FullName).Split([IO.Path]::DirectorySeparatorChar)
                if($parts[0] -notin @('logs','screenshots')){continue}
            }
            Add-Evidence $file
        }
    }
    foreach($file in $discardFiles){Add-Evidence $file}
    foreach($file in $discardJars){if($file.Name -notlike '*-plain.jar'){Add-Evidence $file}}
    foreach($r in $discardReports){if($r.PSIsContainer){Get-ChildItem -LiteralPath $r.FullName -File -Recurse -Force | ForEach-Object {Add-Evidence $_}}else{Add-Evidence $r}}
    $manifest=$zip.CreateEntry('MANIFEST.json')
    $writer=[IO.StreamWriter]::new($manifest.Open(),[Text.UTF8Encoding]::new($false))
    try{$writer.Write(($records | ConvertTo-Json -Depth 4))}finally{$writer.Dispose()}
}finally{$zip.Dispose();$stream.Dispose()}
# Verify every archived payload before any deletion.
$verify=[IO.Compression.ZipFile]::OpenRead($archivePath)
try{foreach($name in $entries.Keys){
    $entryStream=$verify.GetEntry($name).Open();$hasher=[Security.Cryptography.SHA256]::Create()
    try{$actual=[BitConverter]::ToString($hasher.ComputeHash($entryStream)).Replace('-','');if($actual -ne $entries[$name]){throw "Archive hash mismatch: $name"}}finally{$hasher.Dispose();$entryStream.Dispose()}
}}finally{$verify.Dispose()}
foreach($t in $targets){
    $path=Assert-BuildPath $t.FullName
    Remove-Item -LiteralPath $path -Recurse -Force
}
$afterFiles=Get-ChildItem -LiteralPath $build -File -Recurse -Force | Measure-Object Length -Sum
$result=[ordered]@{Project=$project;Archive=$archivePath;ArchiveSha256=(Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash;ArchivedFiles=$records.Count;UniqueArchivePayloads=$entries.Count;BeforeBytes=[long]$beforeFiles.Sum;AfterBytes=[long]$afterFiles.Sum;FreedBytes=([long]$beforeFiles.Sum-[long]$afterFiles.Sum);BeforeFiles=$beforeFiles.Count;AfterFiles=$afterFiles.Count;KeptJars=$keepJars;Preserved='Source, tests, source assets, .env, backups, current reports and dungeon test journals';Removed='Regenerable isolated server runtimes/worlds, duplicate Gradle caches, obsolete plain JARs and archived temporary files';ServerFolderModified=$false}
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $build 'reports/cleanup-verification.json')
$result | ConvertTo-Json -Depth 4
