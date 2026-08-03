package eu.rekawek.coffeegb.androidportable;

import java.util.Objects;

/**
 * A deliberately narrow, portable Maven artifact consumed by the Phase 0 Android build.
 *
 * <p>This is not an emulator API. It ensures D8/R8 process the Java language features already
 * used by Coffee GB while Phase 1 separates the real core and controller boundaries.</p>
 */
public record AndroidPortabilityProbe(String moduleName, BytecodeFlavor flavor) {

    public AndroidPortabilityProbe {
        moduleName = Objects.requireNonNull(moduleName, "moduleName");
        flavor = Objects.requireNonNull(flavor, "flavor");
    }

    public String description() {
        return switch (flavor) {
            case JAVA_RECORD_AND_SWITCH -> moduleName + " uses Java records and switch expressions";
            case KOTLIN_METADATA -> moduleName + " includes Kotlin metadata";
        };
    }

    public enum BytecodeFlavor {
        JAVA_RECORD_AND_SWITCH,
        KOTLIN_METADATA
    }
}
