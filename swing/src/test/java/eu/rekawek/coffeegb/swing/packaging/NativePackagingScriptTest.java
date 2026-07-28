package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativePackagingScriptTest {

    @Test
    public void shellAndPowerShellWrappersConsumeOnlyMavenOutputs() throws Exception {
        Path packaging = Path.of("../packaging").toAbsolutePath().normalize();
        Path shell = packaging.resolve("package-native.sh");
        Path powerShell = packaging.resolve("package-native.ps1");
        Path verifyShell = packaging.resolve("verify-native-package.sh");
        Path verifyPowerShell = packaging.resolve("verify-native-package.ps1");
        Path associationShell = packaging.resolve("verify-native-association.sh");
        Path associationPowerShell = packaging.resolve("verify-native-association.ps1");
        Path reactorPom = packaging.getParent().resolve("pom.xml");
        String sh = Files.readString(shell);
        String ps = Files.readString(powerShell);
        String verifyPs = Files.readString(verifyPowerShell);
        String pom = Files.readString(reactorPom);

        assertTrue(Files.isExecutable(shell));
        assertTrue(Files.isExecutable(verifyShell));
        assertTrue(Files.isExecutable(associationShell));
        for (String contents : new String[] {sh, ps}) {
            assertTrue(contents.contains("-app.jar"));
            assertTrue(contents.contains("-sbom.cdx.json"));
            assertTrue(contents.contains("NativePackageTool"));
            assertTrue(contents.contains("--native-source-jar"));
            assertTrue(contents.contains("packaging/resources"));
            assertTrue(contents.contains("pom.xml"));
            assertTrue(contents.contains("--release-sign"));
            assertFalse(contents.contains("curl "));
            assertFalse(contents.contains("Invoke-WebRequest"));
            assertFalse(contents.contains("wget "));
            assertFalse(contents.contains("git clone"));
        }
        assertTrue(sh.contains("-pl swing -am clean verify"));
        assertTrue(ps.contains("-pl swing -am clean verify"));
        assertTrue(sh.contains("-Dcoffee-gb.test.tmpdir=$maven_temp_dir"));
        assertTrue(sh.contains("pwd -P"));
        assertTrue(ps.contains("-Dcoffee-gb.test.tmpdir=$MavenTemp"));
        assertTrue(ps.contains("Resolve-Path -LiteralPath $MavenTemp"));
        assertTrue(pom.contains("-Djava.io.tmpdir=\"${coffee-gb.test.tmpdir}\""));
        assertTrue(sh.contains("COFFEE_GB_MAVEN_COMMAND:-mvn"));
        assertTrue(ps.contains("COFFEE_GB_MAVEN_COMMAND"));
        assertFalse(sh.contains("/opt/maven"));

        for (String contents :
                new String[] {
                    Files.readString(verifyShell), verifyPs
                }) {
            assertTrue(contents.contains("NativePackageVerifier"));
            assertTrue(contents.contains("--run-smoke"));
            assertTrue(contents.contains("--source-app-jar"));
            assertTrue(contents.contains("--source-sbom"));
            assertTrue(contents.contains("--source-legal"));
            assertFalse(contents.contains("curl "));
            assertFalse(contents.contains("Invoke-WebRequest"));
            assertFalse(contents.contains("wget "));
        }
        assertTrue(verifyPs.contains("Start-Process"));
        assertTrue(verifyPs.contains("-FilePath \"msiexec.exe\""));
        assertTrue(verifyPs.contains("-Wait"));
        assertTrue(verifyPs.contains("-PassThru"));
        assertTrue(verifyPs.contains("$Msi.ExitCode"));
        assertFalse(verifyPs.contains("& msiexec.exe"));

        int normalization = verifyPs.indexOf(
                "$BuildRoot = if ([System.IO.Path]::IsPathRooted($BuildRoot)) {");
        int firstDerivedPath = verifyPs.indexOf("$Dist = Join-Path $BuildRoot \"dist\"");
        assertTrue(normalization >= 0);
        assertTrue(firstDerivedPath > normalization);
        String normalizationBlock = verifyPs.substring(normalization, firstDerivedPath);
        assertTrue(normalizationBlock.contains("[System.IO.Path]::GetFullPath($BuildRoot)"));
        assertTrue(normalizationBlock.contains(
                "[System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot $BuildRoot))"));

        int failureStart = verifyPs.indexOf("if ($Msi.ExitCode -ne 0) {");
        int failureEnd = verifyPs.indexOf("$Arguments = @(", failureStart);
        assertTrue(failureStart >= 0);
        assertTrue(failureEnd > failureStart);
        String failureBlock = verifyPs.substring(failureStart, failureEnd);
        int logGuard = failureBlock.indexOf("Test-Path -LiteralPath $Log -PathType Leaf");
        int logTail = failureBlock.indexOf("Get-Content -LiteralPath $Log -Tail 250");
        int failureThrow = failureBlock.indexOf(
                "throw \"MSI administrative extraction failed");
        assertTrue(logGuard >= 0);
        assertTrue(logTail > logGuard);
        assertTrue(failureThrow > logTail);
        assertTrue(failureBlock.contains("$($Msi.ExitCode)"));
        assertTrue(failureBlock.contains("see $Log"));

        String associationSh = Files.readString(associationShell);
        String associationPs = Files.readString(associationPowerShell)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        for (String contents : new String[] {associationSh, associationPs}) {
            assertTrue(contents.contains("PackageAssociationFixture"));
            assertTrue(contents.contains("COFFEE_GB_ASSOCIATION_SMOKE_MARKER"));
            assertTrue(contents.contains("COFFEE_GB_ASSOCIATION_SMOKE_ROM"));
            assertTrue(contents.contains("association-opened-"));
            assertTrue(contents.contains("INITIAL_ARGUMENT"));
            assertTrue(contents.contains("Coffee GB association open OK"));
            assertTrue(contents.contains("Coffee GB association shutdown OK"));
            assertTrue(contents.contains("origin="));
            assertTrue(contents.contains("pid="));
            assertFalse(contents.contains("curl "));
            assertFalse(contents.contains("Invoke-WebRequest"));
            assertFalse(contents.contains("wget "));
        }
        assertTrue(associationSh.contains("gio open \"$fixture\""));
        assertFalse(associationSh.contains("xdg-open \"$fixture\""));
        assertFalse(associationSh.contains("open -b eu.rekawek.coffeegb"));
        assertTrue(associationSh.contains("DESKTOP_OPEN_FILE"));
        assertTrue(associationSh.contains("dpkg --remove coffee-gb"));
        assertTrue(associationSh.contains("/usr/local/share/applications"));
        assertTrue(associationSh.contains("/usr/share/applications"));
        assertTrue(associationSh.contains("-print0"));
        assertTrue(associationSh.contains("! -L \"${desktop_files[0]}\""));
        assertTrue(associationSh.contains("! kill -0 \"$pid\""));
        assertTrue(associationSh.contains("lsregister"));
        assertTrue(associationSh.contains("codesign --verify --deep --strict"));
        assertTrue(associationSh.contains(
                "com.apple.security.cs.disable-library-validation"));
        assertTrue(associationPs.contains("Start-Process -FilePath $Fixture"));
        assertTrue(associationPs.contains("-FilePath \"msiexec.exe\""));
        assertTrue(associationPs.contains("-ArgumentList $MsiArguments"));
        assertTrue(associationPs.contains("-Wait"));
        assertTrue(associationPs.contains("-PassThru"));
        assertTrue(associationPs.contains("$Msi.ExitCode"));
        assertTrue(associationPs.contains("Get-Content -LiteralPath $Log -Tail 250"));
        assertTrue(associationPs.contains("Registry::HKEY_CLASSES_ROOT"));
        assertTrue(associationPs.contains("Invoke-Msi -Action \"/x\""));
        assertTrue(associationPs.contains("Get-Process -Id $AssociationPid"));
        assertTrue(associationPs.contains("$_.Path -eq $Launcher"));
        int shutdownEvidence = associationPs.indexOf(
                "if ($ShutdownEvidence -cnotcontains $Expected)");
        int exitDeadline = associationPs.indexOf(
                "$ExitDeadline = [DateTime]::UtcNow.AddSeconds(60)", shutdownEvidence);
        int exactProcessWait = associationPs.indexOf(
                "while ((Get-Process -Id $AssociationPid", exitDeadline);
        int exactProcessLoopEnd = associationPs.indexOf("\n        }\n", exactProcessWait);
        int exactProcessFailure = associationPs.indexOf(
                "association process did not exit", exactProcessWait);
        int launcherExitDeadline = associationPs.indexOf(
                "$LauncherExitDeadline = [DateTime]::UtcNow.AddSeconds(60)",
                exactProcessFailure);
        int initialProcessSnapshot = associationPs.indexOf(
                "$RemainingProcesses = @(Get-CoffeeGbProcesses)", launcherExitDeadline);
        int launcherProcessWait = associationPs.indexOf(
                "while ($RemainingProcesses.Count -gt 0 -and", initialProcessSnapshot);
        int launcherProcessLoopEnd = associationPs.indexOf(
                "\n        }\n", launcherProcessWait);
        int refreshedProcessSnapshot = associationPs.indexOf(
                "$RemainingProcesses = @(Get-CoffeeGbProcesses)",
                initialProcessSnapshot + 1);
        int launcherProcessFailure = associationPs.indexOf(
                "if ($RemainingProcesses.Count -gt 0)", refreshedProcessSnapshot);
        assertTrue(shutdownEvidence >= 0);
        assertTrue(exitDeadline > shutdownEvidence);
        assertTrue(exactProcessWait > exitDeadline);
        assertTrue(exactProcessLoopEnd > exactProcessWait);
        assertTrue(associationPs.substring(exactProcessWait, exactProcessLoopEnd)
                .contains("[DateTime]::UtcNow -lt $ExitDeadline"));
        assertTrue(exactProcessFailure > exactProcessLoopEnd);
        assertTrue(launcherExitDeadline > exactProcessFailure);
        assertTrue(initialProcessSnapshot > launcherExitDeadline);
        assertTrue(launcherProcessWait > initialProcessSnapshot);
        assertTrue(launcherProcessLoopEnd > launcherProcessWait);
        assertTrue(associationPs.substring(launcherProcessWait, launcherProcessLoopEnd)
                .contains("[DateTime]::UtcNow -lt $LauncherExitDeadline"));
        assertTrue(refreshedProcessSnapshot > launcherProcessWait);
        assertTrue(refreshedProcessSnapshot < launcherProcessLoopEnd);
        assertTrue(launcherProcessFailure > launcherProcessLoopEnd);
        assertTrue(associationPs.contains(
                "remaining pids=$RemainingPids; launcher=$Launcher"));
        assertTrue(associationPs.contains(
                "Installed Windows $($Fixture.Extension) association lifecycle passed"));
    }
}
