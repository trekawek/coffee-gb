package eu.rekawek.coffeegb;

import java.io.File;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Collections;

public class GameboyOptions {

    private final File romFile;

    private final boolean forceDmg;

    private final boolean forceCgb;

    private final boolean useBootstrap;

    private final boolean disableBatterySaves;

    private final boolean debug;

    private final boolean headless;

    public GameboyOptions(File romFile) {
        this(romFile, Collections.emptyList(), Collections.emptyList());
    }

    public GameboyOptions(File romFile, Collection<String> params, Collection<String> shortParams) {
        this.romFile = romFile;
        this.forceDmg = params.contains("force-dmg") || shortParams.contains("d");
        this.forceCgb = params.contains("force-cgb") || shortParams.contains("c");
        if (forceDmg && forceCgb) {
            throw new IllegalArgumentException("force-dmg and force-cgb options are can't be used together");
        }
        this.useBootstrap = params.contains("use-bootstrap") || shortParams.contains("b");
        this.disableBatterySaves = params.contains("disable-battery-saves") || shortParams.contains("db");
        this.debug = params.contains("debug");
        this.headless = params.contains("headless");
    }

    public File getRomFile() {
        return romFile;
    }

    public boolean isForceDmg() {
        return forceDmg;
    }

    public boolean isForceCgb() {
        return forceCgb;
    }

    public boolean isUsingBootstrap() {
        return useBootstrap;
    }

    public boolean isSupportBatterySaves() {
        return !disableBatterySaves;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isHeadless() {
        return headless;
    }

    public static void printUsage(PrintStream stream) {
        stream.println("Usage:");
        stream.println("java -jar coffee-gb.jar [OPTIONS] ROM_FILE");
        stream.println();
        stream.println("Available options:");
        stream.println("  -d  --force-dmg                Emulate classic GB (DMG) for universal ROMs");
        stream.println("  -c  --force-cgb                Emulate color GB (CGB) for all ROMs");
        stream.println("  -b  --use-bootstrap            Start with the GB bootstrap");
        stream.println("  -db --disable-battery-saves    Disable battery saves");
        stream.println("      --debug                    Enable debug console");
        stream.println("      --headless                 Start in the headless mode");
    }

}
