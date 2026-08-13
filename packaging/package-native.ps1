param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("linux-x86-64", "windows-x86-64", "macos-x86-64", "macos-aarch64")]
    [string] $Target,

    [ValidateSet("", "app-image", "deb", "rpm", "msi", "exe", "dmg", "pkg")]
    [string] $Type = "",

    [switch] $ReleaseSign
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$PomFile = Join-Path $RepositoryRoot "pom.xml"
$MavenCommand = if ($env:COFFEE_GB_MAVEN_COMMAND) {
    $env:COFFEE_GB_MAVEN_COMMAND
} else {
    "mvn"
}

$MavenTemp = Join-Path `
    ([System.IO.Path]::GetTempPath()) `
    ("coffee-gb-maven-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $MavenTemp -ErrorAction Stop | Out-Null
$MavenTemp = (Resolve-Path -LiteralPath $MavenTemp -ErrorAction Stop).ProviderPath
try {
    & $MavenCommand -B --no-transfer-progress -f $PomFile -pl swing -am clean verify `
        "-Dcoffee-gb.test.tmpdir=$MavenTemp"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE"
    }
} finally {
    Remove-Item -LiteralPath $MavenTemp -Recurse -Force -ErrorAction SilentlyContinue
}

$AppJars = @(Get-ChildItem `
    -Path (Join-Path $RepositoryRoot "swing/target") `
    -Filter "coffee-gb-*-app.jar" `
    -File)
if ($AppJars.Count -ne 1) {
    throw "Expected exactly one Maven -app.jar, found $($AppJars.Count)"
}

$AppJar = $AppJars[0].FullName
$ArtifactPrefix = $AppJar.Substring(0, $AppJar.Length - "-app.jar".Length)
$NativeSourceJar = "$ArtifactPrefix.jar"
$Sbom = "$ArtifactPrefix-sbom.cdx.json"
if (-not (Test-Path -LiteralPath $NativeSourceJar -PathType Leaf)) {
    throw "Universal Maven artifact is missing: $NativeSourceJar"
}
if (-not (Test-Path -LiteralPath $Sbom -PathType Leaf)) {
    throw "CycloneDX SBOM is missing: $Sbom"
}

if ($Target -eq "windows-x86-64" -and $Type -eq "exe") {
    $SevenZip = Get-Command "7z.exe" -ErrorAction SilentlyContinue
    if (-not $SevenZip) {
        throw "Windows portable EXE packaging requires 7z.exe"
    }
    $SevenZipSfx = Join-Path (Split-Path -Parent $SevenZip.Source) "7z.sfx"
    if (-not (Test-Path -LiteralPath $SevenZipSfx -PathType Leaf)) {
        throw "Windows portable EXE packaging requires the 7-Zip SFX module: $SevenZipSfx"
    }
    $env:COFFEE_GB_7ZIP_COMMAND = $SevenZip.Source
}

$OutputSuffix = if ($Type) { "-$Type" } else { "" }
$Output = Join-Path `
    $RepositoryRoot `
    "swing/target/native-package-$Target$OutputSuffix"
$Arguments = @(
    "-cp", $AppJar,
    "eu.rekawek.coffeegb.swing.packaging.NativePackageTool",
    "build",
    "--target", $Target,
    "--app-jar", $AppJar,
    "--native-source-jar", $NativeSourceJar,
    "--sbom", $Sbom,
    "--resources", (Join-Path $RepositoryRoot "packaging/resources"),
    "--output", $Output
)
if ($Type) {
    $Arguments += @("--type", $Type)
}
if ($ReleaseSign) {
    $Arguments += "--release-sign"
}

& java @Arguments
if ($LASTEXITCODE -ne 0) {
    throw "Native package build failed with exit code $LASTEXITCODE"
}
