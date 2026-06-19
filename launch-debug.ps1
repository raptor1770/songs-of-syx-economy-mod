# Starts Songs of Syx with the Economy Mod loaded and the JDWP debug port open on :5005.
# Usage: run this script in a terminal, then in VS Code use "Attach to Game (port 5005)".
#
# The game will NOT pause on startup (suspend=n) — it runs immediately.
# Set your breakpoints in VS Code before attaching, or use conditional breakpoints.

param([int]$Port = 5005)

$ErrorActionPreference = 'Stop'

$gameDir  = 'C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx'
$javaExe  = 'C:\Users\tgonzalez\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.11+10\bin\java.exe'
$modJar   = "$PSScriptRoot\target\Economy Overview.jar"

if (-not (Test-Path $javaExe))  { throw "JDK not found: $javaExe" }
if (-not (Test-Path $modJar))   { throw "Mod JAR not found — run '.\build.ps1 install -DskipTests' first" }

$cp = @(
    "$gameDir\SongsOfSyx.jar",
    "$gameDir\base\script\000_Tutorial.jar",
    "$gameDir\base\script\001_Tutorial.jar",
    $modJar
) -join ';'

$jdwp = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$Port"

Write-Host "Starting Songs of Syx (Economy Mod) with JDWP debug on port $Port..." -ForegroundColor Cyan
Write-Host "Attach VS Code debugger using: Run > Attach to Game (port $Port)" -ForegroundColor Yellow

Set-Location $gameDir
& $javaExe `
    $jdwp `
    -Xms512m -Xmx4096m -Dfile.encoding=UTF-8 -server -XX:+UseSerialGC `
    -cp $cp `
    init.MainProcess
