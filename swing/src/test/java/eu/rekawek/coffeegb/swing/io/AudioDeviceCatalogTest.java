package eu.rekawek.coffeegb.swing.io;

import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AudioDeviceCatalogTest {

    @Test
    public void javaSoundIdentityIsCanonicalAndUsesEveryDescriptorField() {
        String one = JavaSoundAudioBackend.stableId(
                "Output", "Vendor", "Description", "1.0");

        assertTrue(one.matches("java-sound-[0-9a-f]{64}"));
        assertEquals(
                one,
                JavaSoundAudioBackend.stableId(
                        "Output", "Vendor", "Description", "1.0"));
        assertNotEquals(
                one,
                JavaSoundAudioBackend.stableId(
                        "Other", "Vendor", "Description", "1.0"));
        assertNotEquals(
                one,
                JavaSoundAudioBackend.stableId(
                        "Output", "Other", "Description", "1.0"));
        assertNotEquals(
                one,
                JavaSoundAudioBackend.stableId(
                        "Output", "Vendor", "Other", "1.0"));
        assertNotEquals(
                one,
                JavaSoundAudioBackend.stableId(
                        "Output", "Vendor", "Description", "2.0"));
    }

    @Test
    public void catalogReturnsImmutablePointInTimeSnapshot() {
        String id = "java-sound-" + "b".repeat(64);
        MutableCatalogBackend backend = new MutableCatalogBackend();
        backend.devices.add(AudioDeviceSnapshot.systemDefaultDevice());
        AudioDeviceCatalog catalog = new AudioDeviceCatalog(backend);

        List<AudioDeviceSnapshot> first = catalog.snapshot();
        backend.devices.add(new AudioDeviceSnapshot(id, "USB Audio", false));
        List<AudioDeviceSnapshot> second = catalog.snapshot();

        assertEquals(List.of(AudioDeviceSnapshot.systemDefaultDevice()), first);
        assertEquals(2, second.size());
        assertEquals(id, second.get(1).stableId());
        assertFalse(second.get(1).systemDefault());
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.add(AudioDeviceSnapshot.systemDefaultDevice()));
    }

    @Test
    public void symbolicDefaultAndExplicitIdsAreValidated() {
        assertEquals(
                "default",
                AudioDeviceSnapshot.systemDefaultDevice().stableId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioDeviceSnapshot("default", "Not default", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioDeviceSnapshot(
                        "java-sound-" + "A".repeat(64), "Uppercase", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AudioDeviceSnapshot(
                        "java-sound-" + "c".repeat(64), " ", false));
    }

    private static final class MutableCatalogBackend implements AudioBackend {
        private final List<AudioDeviceSnapshot> devices = new ArrayList<>();

        @Override
        public List<AudioDeviceSnapshot> devices() {
            return devices;
        }

        @Override
        public AudioLine open(String stableId, AudioFormat format, int bufferBytes)
                throws LineUnavailableException {
            throw new LineUnavailableException("not used");
        }
    }
}
