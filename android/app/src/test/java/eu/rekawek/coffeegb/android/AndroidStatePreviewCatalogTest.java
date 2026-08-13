package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.state.StateCatalogEntry;
import eu.rekawek.coffeegb.controller.state.StateCatalogStatus;
import eu.rekawek.coffeegb.controller.state.StateCodec;
import eu.rekawek.coffeegb.controller.state.StateCompression;
import eu.rekawek.coffeegb.controller.state.StateDiagnosticMetadata;
import eu.rekawek.coffeegb.controller.state.StateImage;
import eu.rekawek.coffeegb.controller.state.MachineIdentity;
import eu.rekawek.coffeegb.controller.state.StatePngCodec;
import eu.rekawek.coffeegb.controller.state.StateRef;
import eu.rekawek.coffeegb.controller.state.StateRepository;
import eu.rekawek.coffeegb.controller.state.StateSaveMetadata;
import eu.rekawek.coffeegb.controller.state.StateStorageLayout;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Covers the persisted-thumbnail handoff used by AndroidEmulationRuntime.listStateSlots. */
public class AndroidStatePreviewCatalogTest {

    @Test
    public void catalogPreviewReadsPersistedThumbnailAsDetachedArgbPixels() throws Exception {
        StateRef.Slot ref = new StateRef.Slot(1);
        StateImage image = thumbnailFixture();
        byte[] encoded = StatePngCodec.INSTANCE.encode(image);
        Path root = Files.createTempDirectory("android-state-preview");
        StateStorageLayout layout = new StateStorageLayout(root);
        StateRepository repository = new StateRepository(layout, AtomicFileWriter.system());
        EventBusImpl eventBus = new EventBusImpl();
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(
                new Rom(testRom())).setGameboyType(GameboyType.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false);
        Gameboy gameboy = configuration.build();
        try {
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            for (int tick = 0; tick < 512; tick++) {
                gameboy.tick();
            }
            byte[] state = StateCodec.INSTANCE.encode(
                    StateCodec.INSTANCE.capture(configuration, gameboy,
                            new StateDiagnosticMetadata("android-test", "thumbnail-round-trip")),
                    StateCompression.NONE);
            repository.saveWithThumbnail(ref, state,
                    new StateSaveMetadata("saved", Instant.parse("2026-08-13T00:00:00Z"), null, null),
                    encoded);

            StateCatalogEntry entry = repository.catalog((MachineIdentity) null).getEntries().stream()
                    .filter(candidate -> candidate.getRef().equals(ref))
                    .findFirst().orElseThrow();
            assertEquals(StateCatalogStatus.AVAILABLE, entry.getStatus());
            byte[] persisted = repository.readThumbnail(ref, entry.getStateSha256(),
                    entry.getMetadata().getThumbnailSha256()).copyBytes();
            StateImage decoded = StatePngCodec.INSTANCE.decode(persisted);
            int[] argb = Arrays.stream(decoded.copyRgb())
                    .map(pixel -> 0xff000000 | pixel)
                    .toArray();
            AndroidStateSlot slot = AndroidStateSlot.from(1, entry,
                    MenuPreview.ready(decoded.getWidth(), decoded.getHeight(), argb));
            MenuPreview preview = slot.preview();
            argb[1] ^= 0x00ffffff;
            assertPreviewPixels(image, preview);
        } finally {
            gameboy.stop();
            gameboy.close();
            eventBus.close();
        }
    }

    private static void assertPreviewPixels(StateImage image, MenuPreview preview) {
        int[] expected = Arrays.stream(image.copyRgb())
                .map(pixel -> 0xff000000 | pixel)
                .toArray();
        assertEquals(image.getWidth(), preview.width());
        assertEquals(image.getHeight(), preview.height());
        assertArrayEquals(expected, preview.copyPixels());
    }

    private static byte[] testRom() {
        byte[] bytes = new byte[0x8000];
        bytes[0x100] = 0x18;
        bytes[0x101] = (byte) 0xfe;
        bytes[0x147] = 0;
        bytes[0x148] = 0;
        bytes[0x149] = 0;
        return bytes;
    }

    private static StateImage thumbnailFixture() {
        int[] pixels = new int[StateImage.THUMBNAIL_WIDTH * StateImage.THUMBNAIL_HEIGHT];
        for (int index = 0; index < pixels.length; index++) {
            int x = index % StateImage.THUMBNAIL_WIDTH;
            int y = index / StateImage.THUMBNAIL_WIDTH;
            pixels[index] = (x * 31 << 16) | (y * 17 << 8) | (x ^ y);
        }
        return new StateImage(StateImage.THUMBNAIL_WIDTH, StateImage.THUMBNAIL_HEIGHT, pixels);
    }

}
