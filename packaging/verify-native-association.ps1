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
$SmokeRoot = Join-Path $BuildRoot "installed-association-smoke"
if (Test-Path -LiteralPath $SmokeRoot) {
    throw "Association smoke output already exists: $SmokeRoot"
}
New-Item -ItemType Directory -Path $SmokeRoot | Out-Null
$HomeRoot = New-Item -ItemType Directory -Path (Join-Path $SmokeRoot "home")
$NativeCache = New-Item -ItemType Directory -Path (Join-Path $SmokeRoot "native-cache")

$AppJars = @(Get-ChildItem -Path $SwingTarget -Filter "coffee-gb-*-app.jar" -File)
$Installers = @(Get-ChildItem -Path (Join-Path $BuildRoot "dist") -Filter "*.msi" -File)
if ($AppJars.Count -ne 1) {
    throw "Expected exactly one Maven -app.jar, found $($AppJars.Count)"
}
if ($Installers.Count -ne 1) {
    throw "Expected exactly one MSI installer, found $($Installers.Count)"
}

$Fixtures = @(".gb", ".gbc", ".rom") | ForEach-Object {
    $Fixture = Join-Path $SmokeRoot "Coffee GB association smoke$_"
    & java `
        -cp $AppJars[0].FullName `
        "eu.rekawek.coffeegb.swing.PackageAssociationFixture" `
        $Fixture
    if ($LASTEXITCODE -ne 0) {
        throw "$_ association fixture generation failed with exit code $LASTEXITCODE"
    }
    if ((Get-Item -LiteralPath $Fixture).Length -ne 32768) {
        throw "Generated $_ association fixture has the wrong size"
    }
    [PSCustomObject]@{
        Extension = $_
        Path = $Fixture
        Marker = Join-Path $SmokeRoot "association-opened-$($_.Substring(1)).marker"
        ShutdownMarker = Join-Path $SmokeRoot "association-opened-$($_.Substring(1)).marker.shutdown"
    }
}

function Invoke-Msi {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("/i", "/x")]
        [string] $Action,
        [Parameter(Mandatory = $true)]
        [string] $Log
    )
    $MsiArguments = @(
        $Action,
        "`"$($Installers[0].FullName)`"",
        "/qn",
        "/norestart",
        "/L*V",
        "`"$Log`""
    )
    $Msi = Start-Process `
        -FilePath "msiexec.exe" `
        -ArgumentList $MsiArguments `
        -Wait `
        -PassThru
    if ($Msi.ExitCode -notin @(0, 3010)) {
        throw "MSI $Action failed with exit code $($Msi.ExitCode); see $Log"
    }
}

function Get-Association {
    param([Parameter(Mandatory = $true)][string] $Extension)
    $Key = "Registry::HKEY_CLASSES_ROOT\$Extension"
    if (-not (Test-Path -LiteralPath $Key)) {
        throw "Installed MSI did not register $Extension"
    }
    $ProgId = (Get-Item -LiteralPath $Key).GetValue("")
    if ([string]::IsNullOrWhiteSpace($ProgId)) {
        throw "Installed MSI registered an empty ProgID for $Extension"
    }
    $CommandKey = "Registry::HKEY_CLASSES_ROOT\$ProgId\shell\open\command"
    if (-not (Test-Path -LiteralPath $CommandKey)) {
        throw "Installed MSI did not register an open command for $Extension ($ProgId)"
    }
    $Command = (Get-Item -LiteralPath $CommandKey).GetValue("")
    if ($Command -notmatch '(?i)Coffee GB\.exe' -or $Command -notmatch '"%1"') {
        throw "Unsafe or incomplete $Extension open command: $Command"
    }
    [PSCustomObject]@{
        Extension = $Extension
        ProgId = $ProgId
        Command = $Command
    }
}

function Get-CoffeeGbProcesses {
    if (-not $Launcher) {
        return @()
    }
    @(
        Get-Process -ErrorAction SilentlyContinue | Where-Object {
            try {
                $_.Path -eq $Launcher
            } catch {
                $false
            }
        }
    )
}

$Installed = $false
$Associations = @()
$Launcher = $null
try {
    Invoke-Msi -Action "/i" -Log (Join-Path $SmokeRoot "install.log")
    $Installed = $true

    $Associations = @(".gb", ".gbc", ".rom") | ForEach-Object {
        Get-Association -Extension $_
    }
    $LauncherMatch = [regex]::Match($Associations[0].Command, '^"([^"]+Coffee GB\.exe)"')
    if (-not $LauncherMatch.Success) {
        throw "Unable to resolve the installed launcher from the association command"
    }
    $Launcher = $LauncherMatch.Groups[1].Value
    if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) {
        throw "Registered Coffee GB launcher does not exist: $Launcher"
    }
    $ConsoleLauncher = Join-Path `
        (Split-Path -Parent $Launcher) `
        "Coffee GB Console.exe"
    if (-not (Test-Path -LiteralPath $ConsoleLauncher -PathType Leaf)) {
        throw "Installed Coffee GB console launcher does not exist: $ConsoleLauncher"
    }
    if ($env:COFFEE_GB_RELEASE_SIGNING -eq "true") {
        if (-not (Get-Command signtool.exe -ErrorAction SilentlyContinue)) {
            throw "signtool.exe is required to validate the installed signed payload"
        }
        $InstalledRoot = Split-Path -Parent $Launcher
        $SignableFiles = @(
            Get-ChildItem `
                -LiteralPath $InstalledRoot `
                -Recurse `
                -File | Where-Object {
                    $_.Extension -in @(".exe", ".dll")
                }
        )
        if ($SignableFiles.Count -eq 0) {
            throw "Installed signed payload contains no executable files"
        }
        foreach ($SignableFile in $SignableFiles) {
            & signtool.exe `
                verify `
                /pa `
                /all `
                /tw `
                /v `
                $SignableFile.FullName
            if ($LASTEXITCODE -ne 0) {
                throw "Installed executable signature verification failed: $($SignableFile.FullName)"
            }
        }
    }

    $StartMenu = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\Coffee GB"
    if (-not (Get-ChildItem -LiteralPath $StartMenu -Filter "*.lnk" -File)) {
        throw "Installed MSI did not create its Start menu shortcut"
    }

    $env:_JAVA_OPTIONS = "-Djava.awt.headless=false -Duser.home=$($HomeRoot.FullName) -Dcoffee-gb.native.cache=$($NativeCache.FullName)"
    foreach ($Fixture in $Fixtures) {
        $env:COFFEE_GB_ASSOCIATION_SMOKE_MARKER = $Fixture.Marker
        $env:COFFEE_GB_ASSOCIATION_SMOKE_ROM = $Fixture.Path
        # Start-Process delegates to ShellExecute for a document path. This must select the
        # installed default handler; invoking Coffee GB.exe directly would not prove association.
        Start-Process -FilePath $Fixture.Path

        $Deadline = [DateTime]::UtcNow.AddSeconds(60)
        $Evidence = @()
        $PidEvidence = @()
        while ($PidEvidence.Count -ne 1 -and [DateTime]::UtcNow -lt $Deadline) {
            if (Test-Path -LiteralPath $Fixture.Marker -PathType Leaf) {
                $Evidence = @(Get-Content -LiteralPath $Fixture.Marker -ErrorAction SilentlyContinue)
                $PidEvidence = @(
                    $Evidence | Where-Object { $_ -cmatch '^pid=[1-9][0-9]*$' }
                )
            }
            Start-Sleep -Milliseconds 100
        }
        if ($PidEvidence.Count -ne 1) {
            throw "Timed out waiting for complete installed Windows $($Fixture.Extension) association evidence"
        }
        foreach ($Expected in @(
            "Coffee GB association open OK",
            "source=INITIAL_ARGUMENT",
            "rom=$($Fixture.Path)",
            "origin=$($Fixture.Path)",
            "title=COFFEE-CI-SMOKE"
        )) {
            if ($Evidence -cnotcontains $Expected) {
                throw "Association evidence is missing '$Expected': $($Evidence -join '; ')"
            }
        }
        [long] $AssociationPid = $PidEvidence[0].Substring(4)
        $AssociationProcess = Get-Process -Id $AssociationPid -ErrorAction SilentlyContinue
        if ($AssociationProcess) {
            try {
                if ($AssociationProcess.Path -ne $Launcher) {
                    throw "Association evidence belongs to another process: $($AssociationProcess.Path)"
                }
            } finally {
                $AssociationProcess.Dispose()
            }
        }

        $Deadline = [DateTime]::UtcNow.AddSeconds(60)
        $ShutdownEvidence = @()
        $ShutdownPidEvidence = @()
        while ($ShutdownPidEvidence.Count -ne 1 -and
               [DateTime]::UtcNow -lt $Deadline) {
            if (Test-Path -LiteralPath $Fixture.ShutdownMarker -PathType Leaf) {
                $ShutdownEvidence = @(
                    Get-Content `
                        -LiteralPath $Fixture.ShutdownMarker `
                        -ErrorAction SilentlyContinue
                )
                $ShutdownPidEvidence = @(
                    $ShutdownEvidence | Where-Object { $_ -cmatch '^pid=[1-9][0-9]*$' }
                )
            }
            Start-Sleep -Milliseconds 100
        }
        if ($ShutdownPidEvidence.Count -ne 1) {
            throw "Timed out waiting for complete normal Windows $($Fixture.Extension) shutdown evidence"
        }
        foreach ($Expected in @(
            "Coffee GB association shutdown OK",
            "pid=$AssociationPid"
        )) {
            if ($ShutdownEvidence -cnotcontains $Expected) {
                throw "Shutdown evidence is missing '$Expected': $($ShutdownEvidence -join '; ')"
            }
        }

        while ((Get-Process -Id $AssociationPid -ErrorAction SilentlyContinue) -and
               [DateTime]::UtcNow -lt $Deadline) {
            Start-Sleep -Milliseconds 100
        }
        if (Get-Process -Id $AssociationPid -ErrorAction SilentlyContinue) {
            throw "The exact Windows $($Fixture.Extension) association process did not exit"
        }
        if ((Get-CoffeeGbProcesses).Count -gt 0) {
            throw "Another Coffee GB process remained before the next association fixture"
        }
    }

    Invoke-Msi -Action "/x" -Log (Join-Path $SmokeRoot "uninstall.log")
    $Installed = $false
    if (Test-Path -LiteralPath $Launcher) {
        throw "The Windows launcher remained after MSI uninstall: $Launcher"
    }
    if (Test-Path -LiteralPath $ConsoleLauncher) {
        throw "The Windows console launcher remained after MSI uninstall: $ConsoleLauncher"
    }
    foreach ($Association in $Associations) {
        $Key = "Registry::HKEY_CLASSES_ROOT\$($Association.Extension)"
        if ((Test-Path -LiteralPath $Key) -and
            (Get-Item -LiteralPath $Key).GetValue("") -eq $Association.ProgId) {
            throw "The $($Association.Extension) association remained after MSI uninstall"
        }
    }
} finally {
    if ($Installed) {
        Get-CoffeeGbProcesses | Stop-Process -Force -ErrorAction SilentlyContinue
        try {
            Invoke-Msi -Action "/x" -Log (Join-Path $SmokeRoot "cleanup-uninstall.log")
        } catch {
            Write-Warning $_
        }
    }
}

Write-Host "Installed file association smoke passed for $Target."
