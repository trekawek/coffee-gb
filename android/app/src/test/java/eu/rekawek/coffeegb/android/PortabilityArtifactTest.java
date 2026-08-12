package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.state.StateImage;
import eu.rekawek.coffeegb.controller.state.StatePngCodec;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PortabilityArtifactTest {

    @Test
    public void consumesThePortableControllerAndUiRuntimeFromMavenArtifacts() throws Exception {
        StateImage image = new StateImage(2, 1, new int[]{0x112233, 0xaabbcc});
        assertEquals(image, StatePngCodec.INSTANCE.decode(StatePngCodec.INSTANCE.encode(image)));
        assertEquals("PAUSE CONSOLE", MenuRoute.PAUSE_CONSOLE.label());
    }
}
