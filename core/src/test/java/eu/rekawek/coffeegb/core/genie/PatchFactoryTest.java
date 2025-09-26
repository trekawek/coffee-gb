package eu.rekawek.coffeegb.core.genie;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PatchFactoryTest {

    @Test
    public void testGameGenieCode() {
        var patch = PatchFactory.createPatch("00A-17B-C49");

        assertTrue(patch instanceof GameGeniePatch);
        assertEquals(0x4a17, ((GameGeniePatch) patch).address());
        assertEquals(0x00, ((GameGeniePatch) patch).newData());
        assertEquals(0xc8, ((GameGeniePatch) patch).oldData());
    }

    @Test
    public void testGameSharkCode() {
        var patch = PatchFactory.createPatch("010238CD");

        assertTrue(patch instanceof GameSharkPatch);
        assertEquals(0x01, ((GameSharkPatch) patch).bank());
        assertEquals(0x02, ((GameSharkPatch) patch).data());
        assertEquals(0xD120, ((GameSharkPatch) patch).address());
    }
}
