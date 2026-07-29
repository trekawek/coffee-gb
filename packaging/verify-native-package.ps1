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
$InstalledRoot = Join-Path $BuildRoot "installed-package"
$SmokeHome = Join-Path $BuildRoot "unpacked-smoke-home"

if (Test-Path -LiteralPath $InstalledRoot) {
    throw "Installation output already exists: $InstalledRoot"
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
    throw "Expected exactly one Windows EXE package, found $($Packages.Count)"
}

function Invoke-Package {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("install", "uninstall")]
        [string] $Action,
        [Parameter(Mandatory = $true)]
        [string] $Log
    )
    $Arguments = @()
    if ($Action -eq "uninstall") {
        $Arguments += "uninstall"
    }
    $Arguments += @(
        "/qn",
        "/norestart",
        "/L*V",
        "`"$Log`""
    )
    if ($Action -eq "install") {
        $Arguments += "INSTALLDIR=`"$InstalledRoot`""
    }
    $Process = Start-Process `
        -FilePath $Packages[0].FullName `
        -ArgumentList $Arguments `
        -Wait `
        -PassThru
    if ($Process.ExitCode -in @(0, 3010)) {
        return
    }
    try {
        if (Test-Path -LiteralPath $Log -PathType Leaf) {
            Write-Host "Windows EXE package $Action log tail:"
            Get-Content -LiteralPath $Log -Tail 250
        } else {
            Write-Warning "Windows EXE package $Action log was not created: $Log"
        }
    } catch {
        Write-Warning "Unable to read Windows EXE package $Action log ${Log}: $($_.Exception.Message)"
    }
    throw "Windows EXE package $Action failed with exit code $($Process.ExitCode); see $Log"
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
                    throw "The installed EXE registered $Application for $Extension"
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
                $Command -like "*$InstalledRoot*") {
                throw "The installed EXE registered $Extension through $ProgId`: $Command"
            }
        }
    }
}

$Installed = $false
try {
    Invoke-Package -Action "install" -Log (Join-Path $BuildRoot "exe-install.log")
    $Installed = $true
    Assert-NoRomAssociations
    $Arguments = @(
        "-cp", $AppJars[0].FullName,
        "eu.rekawek.coffeegb.swing.packaging.NativePackageVerifier",
        "verify",
        "--target", $Target,
        "--type", "exe",
        "--root", $InstalledRoot,
        "--source-app-jar", $AppJars[0].FullName,
        "--source-sbom", $Sboms[0].FullName,
        "--source-legal", (Join-Path $RepositoryRoot "packaging/resources/legal"),
        "--dist", $Dist,
        "--run-smoke",
        "--smoke-home", $SmokeHome
    )
    & java @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Installed EXE verification failed with exit code $LASTEXITCODE"
    }
} finally {
    if ($Installed) {
        Invoke-Package -Action "uninstall" -Log (Join-Path $BuildRoot "exe-uninstall.log")
    }
}
