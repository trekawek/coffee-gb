package eu.rekawek.coffeegb.swing.packaging;

import java.util.List;
import java.util.Locale;

/** Shared policy describing binary-native entries forbidden in the target-neutral app JAR. */
public final class NativeArtifactPolicy {

    public static final List<String> ASSEMBLY_EXCLUDES = List.of(
            "**/*.dll",
            "**/*.dylib",
            "**/*.jnilib",
            "**/*.so",
            "**/*.so.*",
            "**/*.a",
            "**/*.bundle",
            "**/*.node");

    private NativeArtifactPolicy() {
    }

    public static boolean isNativeResource(String entryName) {
        String normalized = entryName.toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        String filename = slash == -1 ? normalized : normalized.substring(slash + 1);
        return filename.endsWith(".dll")
                || filename.endsWith(".dylib")
                || filename.endsWith(".jnilib")
                || filename.endsWith(".so")
                || filename.contains(".so.")
                || filename.endsWith(".a")
                || filename.endsWith(".bundle")
                || filename.endsWith(".node");
    }
}
