package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.state.StateImage;
import eu.rekawek.coffeegb.controller.state.StatePngCodec;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PortabilityArtifactTest {

    @Test
    public void consumesThePortableControllerRuntimeFromTheMavenArtifact() throws Exception {
        StateImage image = new StateImage(2, 1, new int[]{0x112233, 0xaabbcc});
        assertEquals(image, StatePngCodec.INSTANCE.decode(StatePngCodec.INSTANCE.encode(image)));
    }
}
