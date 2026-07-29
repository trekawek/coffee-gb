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
        Path reactorPom = packaging.getParent().resolve("pom.xml");
        Path gitAttributes = packaging.getParent().resolve(".gitattributes");
        String sh = Files.readString(shell);
        String ps = Files.readString(powerShell);
        String verifySh = Files.readString(verifyShell);
        String verifyPs = Files.readString(verifyPowerShell);
        String pom = Files.readString(reactorPom);
        String attributes = Files.readString(gitAttributes);

        assertTrue(Files.isExecutable(shell));
        assertTrue(Files.isExecutable(verifyShell));
        assertFalse(Files.exists(packaging.resolve("verify-native-association.sh")));
        assertFalse(Files.exists(packaging.resolve("verify-native-association.ps1")));
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
        assertTrue(pom.contains(
                "<coffee-gb.test.tmpdir>${project.build.directory}</coffee-gb.test.tmpdir>"));
        assertTrue(pom.contains("-Djava.io.tmpdir=\"${coffee-gb.test.tmpdir}\""));
        assertTrue(pom.contains(
                "<artifactId>maven-jar-plugin</artifactId>\n"
                        + "                <version>3.5.1</version>"));
        assertTrue(attributes.lines().anyMatch("* text=auto eol=lf"::equals));
        assertTrue(sh.contains("COFFEE_GB_MAVEN_COMMAND:-mvn"));
        assertTrue(ps.contains("COFFEE_GB_MAVEN_COMMAND"));
        assertFalse(sh.contains("/opt/maven"));

        for (String contents :
                new String[] {
                    verifySh, verifyPs
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
        assertTrue(verifyPs.contains("-Filter \"*.exe\""));
        assertTrue(verifyPs.contains("-FilePath $Packages[0].FullName"));
        assertTrue(verifyPs.contains("[ValidateSet(\"install\", \"uninstall\")]"));
        assertTrue(verifyPs.contains("$Arguments += \"uninstall\""));
        assertTrue(verifyPs.contains("INSTALLDIR=`\"$InstalledRoot`\""));
        assertTrue(verifyPs.contains("\"--type\", \"exe\""));
        assertTrue(verifyPs.contains("-Wait"));
        assertTrue(verifyPs.contains("-PassThru"));
        assertTrue(verifyPs.contains("$Process.ExitCode"));
        assertFalse(verifyPs.contains("msiexec.exe"));

        int normalization = verifyPs.indexOf(
                "$BuildRoot = if ([System.IO.Path]::IsPathRooted($BuildRoot)) {");
        int firstDerivedPath = verifyPs.indexOf("$Dist = Join-Path $BuildRoot \"dist\"");
        assertTrue(normalization >= 0);
        assertTrue(firstDerivedPath > normalization);
        String normalizationBlock = verifyPs.substring(normalization, firstDerivedPath);
        assertTrue(normalizationBlock.contains("[System.IO.Path]::GetFullPath($BuildRoot)"));
        assertTrue(normalizationBlock.contains(
                "[System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot $BuildRoot))"));

        int failureStart = verifyPs.indexOf("function Invoke-Package {");
        int failureEnd = verifyPs.indexOf("$Installed = $false", failureStart);
        assertTrue(failureStart >= 0);
        assertTrue(failureEnd > failureStart);
        String failureBlock = verifyPs.substring(failureStart, failureEnd);
        int logGuard = failureBlock.indexOf("Test-Path -LiteralPath $Log -PathType Leaf");
        int logTail = failureBlock.indexOf("Get-Content -LiteralPath $Log -Tail 250");
        int failureThrow = failureBlock.indexOf(
                "throw \"Windows EXE package $Action failed");
        assertTrue(logGuard >= 0);
        assertTrue(logTail > logGuard);
        assertTrue(failureThrow > logTail);
        assertTrue(failureBlock.contains("$($Process.ExitCode)"));
        assertTrue(failureBlock.contains("see $Log"));
        assertTrue(verifySh.contains("grep -Eq '^MimeType='"));
        assertTrue(verifySh.contains("CFBundleDocumentTypes"));
        assertTrue(verifySh.contains("UTExportedTypeDeclarations"));
        assertTrue(verifySh.contains("UTImportedTypeDeclarations"));
        assertTrue(verifyPs.contains("function Assert-NoRomAssociations"));
        assertTrue(verifyPs.contains("Registry::HKEY_CLASSES_ROOT"));
        assertTrue(verifyPs.contains("OpenWithProgids"));
        assertTrue(verifyPs.contains("OpenWithList"));
        for (String extension : new String[] {".gb", ".gbc", ".rom"}) {
            assertTrue(verifyPs.contains("\"" + extension + "\""));
        }
    }
}
