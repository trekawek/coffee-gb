package eu.rekawek.coffeegb.android;

import android.content.ContentResolver;
import android.net.Uri;
import eu.rekawek.coffeegb.controller.state.StateCodec;
import eu.rekawek.coffeegb.controller.state.StateRef;
import eu.rekawek.coffeegb.controller.state.StateRepository;
import eu.rekawek.coffeegb.controller.state.StateSaveMetadata;
import eu.rekawek.coffeegb.controller.state.StateStorageLayout;
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Bounded raw-battery and portable-StateFile transfer through SAF streams.
 *
 * <p>Import replacement and destination overwrite choices are deliberately explicit. UI code
 * should ask for confirmation, then pass {@link CollisionDecision#REPLACE}; this class never
 * silently destroys an app-private save or a caller-selected document.
 */
public final class SafPersistenceExchange {

    // Matches the controller's battery headroom and portable StateFile envelope limit.
    private static final int MAX_BATTERY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STATE_BYTES = 128 * 1024 * 1024 + 128 * 1024;

    private SafPersistenceExchange() {}

    public enum CollisionDecision {
        CANCEL,
        REPLACE
    }

    public static void importBattery(
            ContentResolver resolver,
            Uri source,
            StateStorageLayout layout,
            CollisionDecision decision) throws IOException {
        Path target = layout.getBatteryFile();
        requireReplacementDecision(target, decision);
        byte[] bytes = readBounded(resolver, source, MAX_BATTERY_BYTES);
        AtomicFileWriter.system().write(target, bytes);
    }

    public static void exportBattery(
            ContentResolver resolver,
            Uri destination,
            StateStorageLayout layout,
            boolean destinationConfirmed) throws IOException {
        requireDestinationConfirmation(destinationConfirmed);
        byte[] bytes = AtomicFileWriter.system().read(layout.getBatteryFile(), path -> {
            if (!Files.exists(path)) {
                throw new FileNotFoundException("No battery save is available");
            }
            return Files.readAllBytes(path);
        });
        writeDocument(resolver, destination, bytes);
    }

    public static void importState(
            ContentResolver resolver,
            Uri source,
            StateRepository repository,
            StateRef ref,
            CollisionDecision decision) throws IOException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(ref, "ref");
        requireStateReplacementDecision(repository, ref, decision);
        byte[] bytes = readBounded(resolver, source, MAX_STATE_BYTES);
        // Decode before replacing the destination. This rejects malformed or unsupported #314
        // StateFiles while preserving the current app-private state.
        StateCodec.INSTANCE.decode(bytes);
        repository.save(ref, bytes, new StateSaveMetadata(null, Instant.now(), null, null));
    }

    public static void exportState(
            ContentResolver resolver,
            Uri destination,
            StateRepository repository,
            StateRef ref,
            boolean destinationConfirmed) throws IOException {
        requireDestinationConfirmation(destinationConfirmed);
        writeDocument(resolver, destination, repository.exportBytes(ref));
    }

    private static void requireReplacementDecision(Path target, CollisionDecision decision)
            throws IOException {
        Objects.requireNonNull(decision, "decision");
        if (Files.exists(target) && decision != CollisionDecision.REPLACE) {
            throw new FileAlreadyExistsException(
                    target.getFileName().toString(),
                    null,
                    "Existing save requires explicit replacement confirmation");
        }
    }

    private static void requireStateReplacementDecision(
            StateRepository repository,
            StateRef ref,
            CollisionDecision decision) throws IOException {
        Objects.requireNonNull(decision, "decision");
        // Catalog preserves a corrupt or unreadable entry as occupied, so an import cannot
        // silently overwrite something the user may still be able to recover.
        boolean exists = repository.catalog(null).getEntries().stream()
                .anyMatch(entry -> entry.getRef().equals(ref));
        if (exists && decision != CollisionDecision.REPLACE) {
            throw new FileAlreadyExistsException(
                    ref.storageKey(),
                    null,
                    "Existing state requires explicit replacement confirmation");
        }
    }

    private static void requireDestinationConfirmation(boolean destinationConfirmed) {
        if (!destinationConfirmed) {
            throw new IllegalStateException(
                    "SAF destination replacement requires explicit user confirmation");
        }
    }

    private static byte[] readBounded(ContentResolver resolver, Uri source, int maximum)
            throws IOException {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(source, "source");
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) {
                throw new FileNotFoundException("Selected document is no longer available");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 32 * 1024));
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read == 0) {
                    continue;
                }
                if (read > maximum - total) {
                    throw new IOException("Selected document exceeds its import safety limit");
                }
                output.write(buffer, 0, read);
                total += read;
            }
        }
    }

    private static void writeDocument(ContentResolver resolver, Uri destination, byte[] bytes)
            throws IOException {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(destination, "destination");
        try (OutputStream output = resolver.openOutputStream(destination, "wt")) {
            if (output == null) {
                throw new FileNotFoundException("Selected destination is no longer available");
            }
            output.write(bytes);
            output.flush();
        }
    }
}
