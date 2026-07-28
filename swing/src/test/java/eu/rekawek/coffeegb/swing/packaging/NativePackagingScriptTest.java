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
        String sh = Files.readString(shell);
        String ps = Files.readString(powerShell);

        assertTrue(Files.isExecutable(shell));
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
        assertTrue(sh.contains("-pl swing -am clean package"));
        assertTrue(ps.contains("-pl swing -am clean package"));
        assertTrue(sh.contains("COFFEE_GB_MAVEN_COMMAND:-mvn"));
        assertTrue(ps.contains("COFFEE_GB_MAVEN_COMMAND"));
        assertFalse(sh.contains("/opt/maven"));
    }
}
