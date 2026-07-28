package eu.rekawek.coffeegb.controller;

import eu.rekawek.coffeegb.controller.state.MachineState;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ControllerJvmCompatibilityTest {

    @Test
    public void loadRomEventRetainsReleasedFileAndMachineStateConstructor() {
        File rom = new File("compatibility.gb");

        Controller.LoadRomEvent event = new Controller.LoadRomEvent(rom, (MachineState) null);

        assertEquals(rom, event.getRom());
        assertNull(event.getState());
        assertNull(event.getImage());
    }
}
