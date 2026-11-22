<#
  Downloads the latest uBlock Origin Firefox build from its official GitHub releases
  (github.com/gorhill/uBlock) and unpacks it into app/src/main/assets/extensions/ublock/,
  where the app loads it as a built-in extension.

  Run from the project root:   powershell -ExecutionPolicy Bypass -File scripts\update-ublock.ps1
  Then rebuild the app. (uBO's filter lists update themselves at runtime; this script only
  updates the extension code itself.)
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root 'app\src\main\assets\extensions\ublock'

$release = Invoke-RestMethod 'https://api.github.com/repos/gorhill/uBlock/releases/latest' -Headers @{ 'User-Agent' = 'update-ublock-script' }
$asset = $release.assets | Where-Object { $_.name -like '*.firefox.signed.xpi' } | Select-Object -First 1
if (-not $asset) { $asset = $release.assets | Where-Object { $_.name -like '*firefox*.xpi' } | Select-Object -First 1 }
if (-not $asset) { throw "No Firefox build found in release $($release.tag_name)" }

# Download into a fresh, empty temp folder (never into the project directly).
$work = Join-Path ([IO.Path]::GetTempPath()) ("ublock-" + [guid]::NewGuid())
New-Item -ItemType Directory $work | Out-Null
try {
    $zip = Join-Path $work 'ublock.zip'   # an .xpi is a zip file
    Invoke-WebRequest -UseBasicParsing $asset.browser_download_url -OutFile $zip
    $unpacked = Join-Path $work 'unpacked'
    Expand-Archive $zip -DestinationPath $unpacked

    $manifest = Get-Content (Join-Path $unpacked 'manifest.json') -Raw | ConvertFrom-Json
    $id = $manifest.browser_specific_settings.gecko.id
    if ($id -ne 'uBlock0@raymondhill.net') { throw "Unexpected extension id '$id' - aborting." }

    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force -Confirm:$false }
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Move-Item $unpacked $dest
    "Installed uBlock Origin $($manifest.version) ($($release.tag_name)) into $dest"
}
finally {
    Remove-Item $work -Recurse -Force -Confirm:$false -ErrorAction SilentlyContinue
}
