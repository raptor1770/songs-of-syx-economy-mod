# Starts Songs of Syx through its normal launcher with JDWP debug port open on :5005.
#
# The launcher (init.Main "launcher") reads jvmargs-launcher.txt and spawns the actual
# game as a child JVM. This script temporarily injects the JDWP flag into that file so
# the CHILD process (the real game) opens the debug port — not the launcher itself.
#
# Workflow:
#   1. Run this script in a terminal.
#   2. The launcher window will appear and start the game normally.
#   3. Once the game is running, use "Attach to Game (port 5005)" in VS Code to connect.
#
# jvmargs-launcher.txt is restored to its original state when the launcher exits.

param([int]$Port = 5005)

$ErrorActionPreference = 'Stop'

$gameDir     = 'C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx'
$javaExe     = 'C:\Users\tgonzalez\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.11+10\bin\java.exe'
$modJar      = "$PSScriptRoot\target\Economy Overview.jar"
$jvmArgsFile = "$gameDir\jvmargs-launcher.txt"
$jdwpFlag    = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$Port"

if (-not (Test-Path $javaExe))     { throw "JDK not found: $javaExe" }
if (-not (Test-Path $modJar))      { throw "Mod JAR not found — run '.\build.ps1 install -DskipTests' first" }
if (-not (Test-Path $jvmArgsFile)) { throw "jvmargs-launcher.txt not found at $jvmArgsFile" }

$originalArgs = Get-Content $jvmArgsFile -Raw

# Inject JDWP flag if not already present
if ($originalArgs -notmatch 'jdwp') {
    $patched = $originalArgs.TrimEnd() + "`n$jdwpFlag`n"
    Set-Content $jvmArgsFile $patched -NoNewline
    Write-Host "Injected JDWP flag into jvmargs-launcher.txt" -ForegroundColor Cyan
}

$cp = @(
    "$gameDir\SongsOfSyx.jar",
    "$gameDir\base\script\000_Tutorial.jar",
    "$gameDir\base\script\001_Tutorial.jar",
    $modJar
) -join ';'

Write-Host "Starting Songs of Syx via launcher with JDWP debug on port $Port..." -ForegroundColor Cyan
Write-Host "After the game opens, attach VS Code with: Run > Attach to Game (port $Port)" -ForegroundColor Yellow
Write-Host "Listening for transport dt_socket at port $Port" -ForegroundColor DarkGray

try {
    Set-Location $gameDir
    & $javaExe `
        -Xms512m -Xmx4096m -Dfile.encoding=UTF-8 -server -XX:+UseSerialGC `
        -cp $cp `
        init.MainLaunchLauncher
} finally {
    # Always restore jvmargs-launcher.txt
    Set-Content $jvmArgsFile $originalArgs -NoNewline
    Write-Host "Restored jvmargs-launcher.txt" -ForegroundColor Green
}
