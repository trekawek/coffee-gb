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
        String sh = Files.readString(shell);
        String ps = Files.readString(powerShell);
        String verifyPs = Files.readString(verifyPowerShell);

        assertTrue(Files.isExecutable(shell));
        assertTrue(Files.isExecutable(verifyShell));
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
    }
}
