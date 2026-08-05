param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("windows-x86-64")]
    [string] $Target,

    [Parameter(Mandatory = $true)]
    [string] $BuildRoot
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$SwingTarget = Join-Path $RepositoryRoot "swing/target"
$BuildRoot = if ([System.IO.Path]::IsPathRooted($BuildRoot)) {
    [System.IO.Path]::GetFullPath($BuildRoot)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot $BuildRoot))
}
$Dist = Join-Path $BuildRoot "dist"
$UnpackedRoot = Join-Path $BuildRoot "unpacked-portable-exe"
$SmokeHome = Join-Path $BuildRoot "unpacked-smoke-home"

if (Test-Path -LiteralPath $UnpackedRoot) {
    throw "Portable EXE extraction output already exists: $UnpackedRoot"
}

$AppJars = @(Get-ChildItem -Path $SwingTarget -Filter "coffee-gb-*-app.jar" -File)
$Sboms = @(Get-ChildItem -Path $SwingTarget -Filter "coffee-gb-*-sbom.cdx.json" -File)
$Packages = @(Get-ChildItem -Path $Dist -Filter "*.exe" -File)
if ($AppJars.Count -ne 1) {
    throw "Expected exactly one Maven -app.jar, found $($AppJars.Count)"
}
if ($Sboms.Count -ne 1) {
    throw "Expected exactly one Maven SBOM, found $($Sboms.Count)"
}
if ($Packages.Count -ne 1) {
    throw "Expected exactly one Windows portable EXE, found $($Packages.Count)"
}

function Assert-NoRomAssociations {
    foreach ($Extension in @(".gb", ".gbc", ".rom")) {
        $ExtensionKey = "Registry::HKEY_CLASSES_ROOT\$Extension"
        if (-not (Test-Path -LiteralPath $ExtensionKey)) {
            continue
        }
        $Key = Get-Item -LiteralPath $ExtensionKey
        $ProgIds = @($Key.GetValue(""))
        $OpenWithProgIds = Join-Path $ExtensionKey "OpenWithProgids"
        if (Test-Path -LiteralPath $OpenWithProgIds) {
            $ProgIds += (Get-Item -LiteralPath $OpenWithProgIds).GetValueNames()
        }
        $OpenWithList = Join-Path $ExtensionKey "OpenWithList"
        if (Test-Path -LiteralPath $OpenWithList) {
            foreach ($Application in (Get-Item -LiteralPath $OpenWithList).GetValueNames()) {
                if ($Application -match '(?i)Coffee GB(?: Console)?\.exe') {
                    throw "The portable EXE registered $Application for $Extension"
                }
            }
        }
        foreach ($ProgId in ($ProgIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
            $CommandKey = "Registry::HKEY_CLASSES_ROOT\$ProgId\shell\open\command"
            if (-not (Test-Path -LiteralPath $CommandKey)) {
                continue
            }
            $Command = (Get-Item -LiteralPath $CommandKey).GetValue("")
            if ($Command -match '(?i)Coffee GB(?: Console)?\.exe' -or
                $Command -like "*$UnpackedRoot*") {
                throw "The portable EXE registered $Extension through $ProgId`: $Command"
            }
        }
    }
}

$SevenZip = Get-Command "7z.exe" -ErrorAction SilentlyContinue
if (-not $SevenZip) {
    throw "7z.exe is required to inspect the Windows portable EXE"
}

$PortableMarker = Join-Path $BuildRoot "portable-exe-ready.marker"
$PreviousMarker = $env:COFFEE_GB_DESKTOP_SMOKE_MARKER
try {
    $env:COFFEE_GB_DESKTOP_SMOKE_MARKER = $PortableMarker
    $Process = Start-Process -FilePath $Packages[0].FullName -PassThru
    $Deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (-not (Test-Path -LiteralPath $PortableMarker -PathType Leaf) -and
            [DateTime]::UtcNow -lt $Deadline) {
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $PortableMarker -PathType Leaf)) {
        throw "Windows portable EXE did not start Coffee GB"
    }
    $Evidence = Get-Content -LiteralPath $PortableMarker -Raw
    if ($Evidence -notmatch "Coffee GB desktop ready OK:") {
        throw "Windows portable EXE produced invalid startup evidence: $Evidence"
    }
    $Process.WaitForExit(45000) | Out-Null
    if (-not $Process.HasExited) {
        $Process.Kill()
        throw "Windows portable EXE did not exit after the desktop smoke"
    }
} finally {
    $env:COFFEE_GB_DESKTOP_SMOKE_MARKER = $PreviousMarker
}

& $SevenZip.Source x "-o$UnpackedRoot" "-y" $Packages[0].FullName
if ($LASTEXITCODE -ne 0) {
    throw "Unable to unpack Windows portable EXE with exit code $LASTEXITCODE"
}
Assert-NoRomAssociations
$Arguments = @(
    "-cp", $AppJars[0].FullName,
    "eu.rekawek.coffeegb.swing.packaging.NativePackageVerifier",
    "verify",
    "--target", $Target,
    "--type", "exe",
    "--root", $UnpackedRoot,
    "--source-app-jar", $AppJars[0].FullName,
    "--source-sbom", $Sboms[0].FullName,
    "--source-legal", (Join-Path $RepositoryRoot "packaging/resources/legal"),
    "--dist", $Dist,
    "--run-smoke",
    "--smoke-home", $SmokeHome
)
& java @Arguments
if ($LASTEXITCODE -ne 0) {
    throw "Portable EXE verification failed with exit code $LASTEXITCODE"
}
