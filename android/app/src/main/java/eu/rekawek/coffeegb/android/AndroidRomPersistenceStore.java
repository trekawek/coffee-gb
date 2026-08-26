package eu.rekawek.coffeegb.android;

import android.content.Context;
import eu.rekawek.coffeegb.controller.state.BatteryStore;
import eu.rekawek.coffeegb.controller.state.FileStateStore;
import eu.rekawek.coffeegb.controller.state.RomPersistenceStore;
import eu.rekawek.coffeegb.controller.state.SessionPersistence;
import eu.rekawek.coffeegb.controller.state.StateRomHashes;
import eu.rekawek.coffeegb.controller.state.StateStorageLayout;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * App-private persistence for SAF-loaded ROMs.
 *
 * <p>Only the exact normalized ROM hash becomes a directory name. Document display names and
 * content URIs are never used as storage paths, so duplicate filenames cannot collide and a
 * revoked grant cannot make an app-private save escape its namespace.
 */
public final class AndroidRomPersistenceStore implements RomPersistenceStore {

    private static final String ROOT_DIRECTORY = "coffee-gb";

    private final Path root;

    public AndroidRomPersistenceStore(Context context) throws IOException {
        Objects.requireNonNull(context, "context");
        root = context.getNoBackupFilesDir().toPath().resolve(ROOT_DIRECTORY)
                .toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public SessionPersistence resolve(
            Gameboy.GameboyConfiguration configuration,
            StateRomHashes hashes) {
        StateStorageLayout primaryLayout = layout(hashes.getPrimaryRom().hex());
        BatteryStore primary = () -> battery(primaryLayout);
        BatteryStore slot = hashes.getSlotRom() == null
                ? null
                : () -> battery(layout(hashes.getSlotRom().hex()));
        return new SessionPersistence(new FileStateStore(primaryLayout), primary, slot);
    }

    StateStorageLayout layout(String romSha256) {
        if (romSha256 == null || !romSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("ROM storage identity must be a SHA-256 digest");
        }
        return new StateStorageLayout(root.resolve("games").resolve(romSha256));
    }

    Path root() {
        return root;
    }

    private BatteryStorage battery(StateStorageLayout layout) {
        return new BatteryStorage(
                BatteryStorage.Source.appPrivate(layout.getBatteryFile(), root),
                java.util.List.of());
    }
}
