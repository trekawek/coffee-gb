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

    @Test
    public void lifecycleEventsRetainReleasedConstructors() {
        File rom = new File("compatibility.gb");

        Controller.EmulationStartedEvent started =
                new Controller.EmulationStartedEvent("COMPATIBILITY");
        Controller.RomLoadingEvent loading = new Controller.RomLoadingEvent(rom);
        Controller.RomLoadingCancelledEvent cancelled =
                new Controller.RomLoadingCancelledEvent(rom);
        Controller.LoadRomFailedEvent failed =
                new Controller.LoadRomFailedEvent(rom, "failure");
        Controller.RomReplacementPersistenceFailedEvent persistence =
                new Controller.RomReplacementPersistenceFailedEvent(
                        4,
                        "compatibility.sav",
                        "failure",
                        Controller.PersistenceBarrierOperation.ROM_REPLACEMENT);

        assertEquals("COMPATIBILITY", started.getRomName());
        assertEquals(rom, loading.getRom());
        assertEquals(rom, cancelled.getRom());
        assertEquals("failure", failed.getMessage());
        assertNull(failed.getOpenRequestId());
        assertNull(persistence.getOpenRequestId());
    }
}
