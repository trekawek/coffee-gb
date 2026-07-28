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
        assertTrue(verifyPs.contains("& msiexec.exe"));
        assertFalse(verifyPs.contains("Start-Process"));

        String associationSh = Files.readString(associationShell);
        String associationPs = Files.readString(associationPowerShell);
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
        assertTrue(associationSh.contains("xdg-open"));
        assertFalse(associationSh.contains("open -b eu.rekawek.coffeegb"));
        assertTrue(associationSh.contains("DESKTOP_OPEN_FILE"));
        assertTrue(associationSh.contains("dpkg --remove coffee-gb"));
        assertTrue(associationSh.contains("! kill -0 \"$pid\""));
        assertTrue(associationSh.contains("lsregister"));
        assertTrue(associationSh.contains("codesign --verify --deep --strict"));
        assertTrue(associationSh.contains(
                "com.apple.security.cs.disable-library-validation"));
        assertTrue(associationPs.contains("Start-Process -FilePath $Fixture"));
        assertTrue(associationPs.contains("Registry::HKEY_CLASSES_ROOT"));
        assertTrue(associationPs.contains("Invoke-Msi -Action \"/x\""));
        assertTrue(associationPs.contains("Get-Process -Id $AssociationPid"));
    }
}
