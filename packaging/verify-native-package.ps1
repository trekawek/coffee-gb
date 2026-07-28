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
$SmokeHome = Join-Path $BuildRoot "unpacked-smoke-home"

if (Test-Path -LiteralPath $Extracted) {
    throw "Extraction output already exists: $Extracted"
}

$AppJars = @(Get-ChildItem -Path $SwingTarget -Filter "coffee-gb-*-app.jar" -File)
$Sboms = @(Get-ChildItem -Path $SwingTarget -Filter "coffee-gb-*-sbom.cdx.json" -File)
$Installers = @(Get-ChildItem -Path $Dist -Filter "*.msi" -File)
if ($AppJars.Count -ne 1) {
    throw "Expected exactly one Maven -app.jar, found $($AppJars.Count)"
}
if ($Sboms.Count -ne 1) {
    throw "Expected exactly one Maven SBOM, found $($Sboms.Count)"
}
if ($Installers.Count -ne 1) {
    throw "Expected exactly one MSI installer, found $($Installers.Count)"
}

New-Item -ItemType Directory -Path $Extracted | Out-Null
$Log = Join-Path $BuildRoot "msi-administrative-extract.log"
$MsiArguments = @(
    "/a",
    "`"$($Installers[0].FullName)`"",
    "/qn",
    "TARGETDIR=`"$Extracted`"",
    "/L*V",
    "`"$Log`""
)
$Msi = Start-Process `
    -FilePath "msiexec.exe" `
    -ArgumentList $MsiArguments `
    -Wait `
    -PassThru
if ($Msi.ExitCode -ne 0) {
    try {
        if (Test-Path -LiteralPath $Log -PathType Leaf) {
            Write-Host "MSI administrative extraction log tail:"
            Get-Content -LiteralPath $Log -Tail 250
        } else {
            Write-Warning "MSI administrative extraction log was not created: $Log"
        }
    } catch {
        Write-Warning "Unable to read MSI administrative extraction log ${Log}: $($_.Exception.Message)"
    }
    throw "MSI administrative extraction failed with exit code $($Msi.ExitCode); see $Log"
}

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
