# Builds and installs the Songs of Syx mod using the correct JDK 21 + Maven.
# Usage:
#   .\build.ps1                          # mvn clean install (build + deploy to game mods folder)
#   .\build.ps1 validate                 # (re)install the game jar into the local maven repo
#   .\build.ps1 clean                    # remove build output + uninstall the mod from the game
#   .\build.ps1 'install -DskipTests'
param([string]$Goals = 'clean install')

$ErrorActionPreference = 'Stop'

# Toolchain locations (edit if you move/upgrade these)
$env:JAVA_HOME = 'C:\Users\tgonzalez\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.11+10'
$mavenBin      = 'C:\Projects\tools\apache-maven-3.9.6\bin'

if (-not (Test-Path $env:JAVA_HOME)) { throw "JDK 21 not found at $env:JAVA_HOME" }
if (-not (Test-Path $mavenBin))      { throw "Maven not found at $mavenBin" }

$env:Path = "$env:JAVA_HOME\bin;$mavenBin;$env:Path"

Set-Location -Path $PSScriptRoot
Write-Host "JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Cyan
Write-Host "Running:  mvn $Goals" -ForegroundColor Cyan
& mvn $Goals.Split(' ')
exit $LASTEXITCODE
