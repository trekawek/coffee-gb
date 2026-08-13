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
$Extracted = Join-Path $BuildRoot "extracted-installer"
$InstalledRoot = Join-Path $BuildRoot "installed-msi"
$SmokeHome = Join-Path $BuildRoot "unpacked-smoke-home"

foreach ($Path in @($Extracted, $InstalledRoot)) {
    if (Test-Path -LiteralPath $Path) {
        throw "MSI verification output already exists: $Path"
    }
}

$AppJars = @(Get-ChildItem -Path $SwingTarget -Filter "coffee-gb-*-app.jar" -File)
$Sboms = @(Get-ChildItem -Path $SwingTarget -Filter "coffee-gb-*-sbom.cdx.json" -File)
$Packages = @(Get-ChildItem -Path $Dist -Filter "*.msi" -File)
if ($AppJars.Count -ne 1) {
    throw "Expected exactly one Maven -app.jar, found $($AppJars.Count)"
}
if ($Sboms.Count -ne 1) {
    throw "Expected exactly one Maven SBOM, found $($Sboms.Count)"
}
if ($Packages.Count -ne 1) {
    throw "Expected exactly one MSI installer, found $($Packages.Count)"
}

function Show-MsiLogTail([string] $Log, [string] $Operation) {
    try {
        if (Test-Path -LiteralPath $Log -PathType Leaf) {
            Write-Host "$Operation log tail:"
            Get-Content -LiteralPath $Log -Tail 250
        } else {
            Write-Warning "$Operation log was not created: $Log"
        }
    } catch {
        Write-Warning "Unable to read $Operation log ${Log}: $($_.Exception.Message)"
    }
}

function Invoke-Msi([string] $Operation, [string[]] $Arguments, [string] $Log) {
    $Msi = Start-Process `
        -FilePath "msiexec.exe" `
        -ArgumentList $Arguments `
        -Wait `
        -PassThru
    if ($Msi.ExitCode -notin @(0, 3010)) {
        Show-MsiLogTail $Log $Operation
        throw "$Operation failed with exit code $($Msi.ExitCode); see $Log"
    }
}

function Assert-NoRomAssociations([string] $InstallationRoot) {
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
                    throw "The MSI registered $Application for $Extension"
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
                $Command -like "*$InstallationRoot*") {
                throw "The MSI registered $Extension through $ProgId`: $Command"
            }
        }
    }
}

function Assert-OnlyCoffeeGbShortcuts([string] $InstallationRoot) {
    $Desktop = Join-Path $env:PUBLIC "Desktop"
    $StartMenuGroup = Join-Path `
        $env:ProgramData `
        "Microsoft\Windows\Start Menu\Programs\Coffee GB"
    $ExpectedShortcuts = @(
        Join-Path $Desktop "Coffee GB.lnk",
        Join-Path $StartMenuGroup "Coffee GB.lnk"
    )
    $PrimaryLauncher = [System.IO.Path]::GetFullPath(
        (Join-Path $InstallationRoot "Coffee GB.exe"))
    $ConsoleLauncher = [System.IO.Path]::GetFullPath(
        (Join-Path $InstallationRoot "Coffee GB Console.exe"))
    $InstallationPrefix = [System.IO.Path]::GetFullPath($InstallationRoot).TrimEnd('\') + '\'
    $Shell = New-Object -ComObject WScript.Shell

    foreach ($ShortcutPath in $ExpectedShortcuts) {
        if (-not (Test-Path -LiteralPath $ShortcutPath -PathType Leaf)) {
            throw "MSI did not create the Coffee GB shortcut: $ShortcutPath"
        }
        $TargetPath = $Shell.CreateShortcut($ShortcutPath).TargetPath
        if ([System.IO.Path]::GetFullPath($TargetPath) -ine $PrimaryLauncher) {
            throw "Coffee GB shortcut has an unexpected target: $ShortcutPath -> $TargetPath"
        }
    }

    foreach ($Directory in @($Desktop, $StartMenuGroup)) {
        if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
            continue
        }
        foreach ($Shortcut in (Get-ChildItem -LiteralPath $Directory -Filter "*.lnk" -File -Recurse)) {
            $TargetPath = $Shell.CreateShortcut($Shortcut.FullName).TargetPath
            if ([string]::IsNullOrWhiteSpace($TargetPath)) {
                continue
            }
            $ResolvedTarget = [System.IO.Path]::GetFullPath($TargetPath)
            if ($ResolvedTarget -ieq $ConsoleLauncher) {
                throw "MSI installed a misleading console shortcut: $($Shortcut.FullName)"
            }
            if ($ResolvedTarget.StartsWith(
                    $InstallationPrefix,
                    [System.StringComparison]::OrdinalIgnoreCase) -and
                    $ResolvedTarget -ine $PrimaryLauncher) {
                throw "MSI installed an unexpected launcher shortcut: $($Shortcut.FullName)"
            }
        }
    }
}

New-Item -ItemType Directory -Path $Extracted | Out-Null
$ExtractLog = Join-Path $BuildRoot "msi-administrative-extract.log"
Invoke-Msi -Operation "MSI administrative extraction" -Arguments @(
    "/a",
    "`"$($Packages[0].FullName)`"",
    "/qn",
    "TARGETDIR=`"$Extracted`"",
    "/L*V",
    "`"$ExtractLog`""
) -Log $ExtractLog

$Arguments = @(
    "-cp", $AppJars[0].FullName,
    "eu.rekawek.coffeegb.swing.packaging.NativePackageVerifier",
    "verify",
    "--target", $Target,
    "--type", "msi",
    "--root", $Extracted,
    "--source-app-jar", $AppJars[0].FullName,
    "--source-sbom", $Sboms[0].FullName,
    "--source-legal", (Join-Path $RepositoryRoot "packaging/resources/legal"),
    "--dist", $Dist,
    "--run-smoke",
    "--smoke-home", $SmokeHome
)
& java @Arguments
if ($LASTEXITCODE -ne 0) {
    throw "Extracted MSI verification failed with exit code $LASTEXITCODE"
}

$InstallLog = Join-Path $BuildRoot "msi-install.log"
$UninstallLog = Join-Path $BuildRoot "msi-uninstall.log"
$Installed = $false
try {
    Invoke-Msi -Operation "MSI installation" -Arguments @(
        "/i",
        "`"$($Packages[0].FullName)`"",
        "/qn",
        "/norestart",
        "INSTALLDIR=`"$InstalledRoot`"",
        "/L*V",
        "`"$InstallLog`""
    ) -Log $InstallLog
    $Installed = $true

    if (-not (Test-Path -LiteralPath $InstalledRoot -PathType Container)) {
        throw "MSI did not install to the requested directory: $InstalledRoot"
    }
    Assert-NoRomAssociations $InstalledRoot
    Assert-OnlyCoffeeGbShortcuts $InstalledRoot
} finally {
    if ($Installed) {
        Invoke-Msi -Operation "MSI uninstall" -Arguments @(
            "/x",
            "`"$($Packages[0].FullName)`"",
            "/qn",
            "/norestart",
            "/L*V",
            "`"$UninstallLog`""
        ) -Log $UninstallLog
    }
}
