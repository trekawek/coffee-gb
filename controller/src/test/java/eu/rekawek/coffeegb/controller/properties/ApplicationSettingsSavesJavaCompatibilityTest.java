package eu.rekawek.coffeegb.controller.properties;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class ApplicationSettingsSavesJavaCompatibilityTest {

    @Test
    public void sevenArgumentConstructorKeepsItsSourceCompatibleDefaultBudget() {
        ApplicationSettings.Saves saves = new ApplicationSettings.Saves(
                Path.of("/active"),
                List.of(Path.of("/old")),
                true,
                true,
                30,
                ApplicationSettings.AutosavePolicy.DISABLED,
                ApplicationSettings.ResumePolicy.ASK);

        assertEquals(ApplicationSettings.DEFAULT_REWIND_MEMORY_MIB, saves.getRewindMemoryMiB());
    }
}
