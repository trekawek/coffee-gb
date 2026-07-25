package eu.rekawek.coffeegb.core.genie;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PatchFactoryTest {

    @Test
    public void testGameGenieCode() {
        var patch = PatchFactory.createPatch("00A-17B-C49");

        assertTrue(patch instanceof GameGenieCheat);
        assertEquals(0x4a17, ((GameGenieCheat) patch).address());
        assertEquals(0x00, ((GameGenieCheat) patch).newData());
        assertEquals(0xc8, ((GameGenieCheat) patch).oldData());
    }

    @Test
    public void testGameSharkCode() {
        var patch = PatchFactory.createPatch("010238CD");

        assertTrue(patch instanceof GameSharkCheat);
        assertEquals(0x01, ((GameSharkCheat) patch).bank());
        assertEquals(0x02, ((GameSharkCheat) patch).data());
        assertEquals(0xCD38, ((GameSharkCheat) patch).address());
    }
}
