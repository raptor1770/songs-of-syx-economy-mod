# Economy Overview - Steam Workshop Publisher
#
# Prerequisites:
#   1. .\build.ps1 'clean install -Pmods-uploader'  (stages mod content)
#   2. steamcmd.exe installed: https://developer.valvesoftware.com/wiki/SteamCMD
#
# First upload (creates new Workshop item, private):
#   .\publish.ps1
#
# Go public after reviewing:
#   .\publish.ps1 -Visibility 0
#
# Override steamcmd path:
#   .\publish.ps1 -SteamCmdPath C:\tools\steamcmd\steamcmd.exe

param(
    [string]$SteamUser = "",
    [ValidateSet(0, 1, 2)]
    [int]$Visibility = 2,           # 0=Public  1=Friends Only  2=Private
    [string]$SteamCmdPath = "C:\steamcmd\steamcmd.exe"
)

$ErrorActionPreference = "Stop"

$appId       = "1162750"
$contentDir  = "$env:APPDATA\songsofsyx\mods-uploader\WorkshopContent\Economy Overview"
$previewFile = "$PSScriptRoot\src\main\resources\mod-files\thumbnail.png"
$workshopDir = "$PSScriptRoot\workshop"
$vdfPath     = "$workshopDir\upload.vdf"
$idFile      = "$workshopDir\published_id.txt"

# --- Pre-flight checks ---
if (-not (Test-Path $SteamCmdPath)) {
    Write-Error @"
steamcmd.exe not found at: $SteamCmdPath

Download and extract steamcmd from:
  https://developer.valvesoftware.com/wiki/SteamCMD

Then re-run with: .\publish.ps1 -SteamCmdPath <path\to\steamcmd.exe>
"@
    exit 1
}

if (-not (Test-Path $contentDir)) {
    Write-Error "Mod content not staged. Run first:`n  .\build.ps1 'clean install -Pmods-uploader'"
    exit 1
}

if (-not (Test-Path $previewFile)) {
    Write-Error "Thumbnail not found at: $previewFile"
    exit 1
}

# --- Published file ID (blank = create new item) ---
$publishedId = ""
if (Test-Path $idFile) {
    $publishedId = (Get-Content $idFile -Raw).Trim()
    Write-Host "Updating existing Workshop item: $publishedId" -ForegroundColor Cyan
} else {
    Write-Host "Creating new Workshop item..." -ForegroundColor Cyan
}

# --- Generate VDF with forward slashes (steamcmd handles both) ---
$contentFwd = $contentDir  -replace '\\', '/'
$previewFwd = $previewFile -replace '\\', '/'

New-Item -ItemType Directory -Force -Path $workshopDir | Out-Null

$vdf = @"
"workshopitem"
{
	"appid"			"$appId"
	"publishedfileid"	"$publishedId"
	"contentfolder"		"$contentFwd"
	"previewfile"		"$previewFwd"
	"visibility"		"$Visibility"
	"title"			"Economy Overview"
	"description"		"Adds an ECON button to the settlement top bar that opens a spreadsheet-style resource economy overview. Sort and compare every resource by stored amount, fill%, net flow, runway, spoilage, trade prices, and more - all in one table the vanilla UI cannot show."
	"changenote"		""
}
"@

Set-Content -Path $vdfPath -Value $vdf -Encoding utf8

$visLabel = @("Public", "Friends Only", "Private")[$Visibility]
Write-Host "Content:    $contentDir"
Write-Host "Preview:    $previewFile"
Write-Host "Visibility: $visLabel"
Write-Host ""

if ($SteamUser -eq "") {
    $SteamUser = Read-Host "Steam username"
}

# --- Run steamcmd interactively ---
Write-Host "Running steamcmd..." -ForegroundColor Cyan
& $SteamCmdPath +login $SteamUser +workshop_build_item $vdfPath +quit

# --- On first upload, prompt user to enter the ID printed by steamcmd ---
if ($publishedId -eq "") {
    Write-Host ""
    $newId = Read-Host "Enter the Workshop item ID shown above (or press Enter to skip)"
    if ($newId -match "^\d+$") {
        Set-Content -Path $idFile -Value $newId -Encoding utf8
        Write-Host "Saved to workshop\published_id.txt" -ForegroundColor Green
        Write-Host "View your item: https://steamcommunity.com/sharedfiles/filedetails/?id=$newId"
    }
}
