package eu.rekawek.coffeegb.controller.properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class ApplicationSettingsTranslationJavaCompatibilityTest {

    @Test
    public void legacyCopyPreservesConfiguredTranslationWhileUpdatingOtherSettings() {
        ApplicationSettings defaults = new ApplicationSettings();
        ApplicationSettings configured = defaults.copy(
                defaults.getSchemaVersion(),
                defaults.getGeneral(),
                defaults.getDisplay(),
                defaults.getAudio(),
                defaults.getInput(),
                defaults.getPeripherals(),
                defaults.getSaves(),
                defaults.getAdvanced(),
                defaults.getDesktop(),
                new ApplicationSettings.Translation("sk-proj-placeholder-test-key"));
        ApplicationSettings.Peripherals peripherals = new ApplicationSettings.Peripherals(1);

        ApplicationSettings updated = configured.copy(
                configured.getSchemaVersion(),
                configured.getGeneral(),
                configured.getDisplay(),
                configured.getAudio(),
                configured.getInput(),
                peripherals,
                configured.getSaves(),
                configured.getAdvanced(),
                configured.getDesktop());

        assertEquals(1, updated.getPeripherals().getCameraDeviceIndex());
        assertSame(configured.getTranslation(), updated.getTranslation());
        assertEquals(configured, updated.copy(
                updated.getSchemaVersion(),
                updated.getGeneral(),
                updated.getDisplay(),
                updated.getAudio(),
                updated.getInput(),
                configured.getPeripherals(),
                updated.getSaves(),
                updated.getAdvanced(),
                updated.getDesktop()));
    }
}
