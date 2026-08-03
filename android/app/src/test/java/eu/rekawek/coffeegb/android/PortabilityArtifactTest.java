package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.androidportable.AndroidPortabilityProbe;
import eu.rekawek.coffeegb.androidportable.KotlinPortabilityProbe;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PortabilityArtifactTest {

    @Test
    public void consumesJavaRecordSwitchAndKotlinBytecodeFromTheMavenArtifact() {
        AndroidPortabilityProbe javaProbe = new AndroidPortabilityProbe(
                "Coffee GB",
                AndroidPortabilityProbe.BytecodeFlavor.JAVA_RECORD_AND_SWITCH
        );

        assertEquals("Coffee GB uses Java records and switch expressions", javaProbe.description());
        assertEquals("Coffee GB includes Kotlin metadata", new KotlinPortabilityProbe("Coffee GB").description());
    }
}
