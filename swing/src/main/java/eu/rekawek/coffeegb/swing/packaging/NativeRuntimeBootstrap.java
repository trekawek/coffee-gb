package eu.rekawek.coffeegb.swing.packaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Early desktop-launch bootstrap for an explicitly selected target-native bundle.
 *
 * <p>With no target property, Coffee GB retains its existing portable-JAR loading behavior.
 * Invalid, missing, or corrupt target resources produce a typed fallback and do not prevent
 * keyboard-only startup.
 */
public final class NativeRuntimeBootstrap {

    public static final String TARGET_PROPERTY = "coffee-gb.native.target";
    public static final String SOURCE_PROPERTY = "coffee-gb.native.source";
    public static final String CACHE_PROPERTY = "coffee-gb.native.cache";
    public static final String LIBRARY_DIRECTORY_PROPERTY = "coffee-gb.native.library-directory";
    public static final String OPENCV_LIBRARY_PROPERTY = "coffee-gb.native.opencv-library";

    private static final Logger LOG = LoggerFactory.getLogger(NativeRuntimeBootstrap.class);

    private NativeRuntimeBootstrap() {
    }

    public static NativeRuntimeSelection bootstrapFromSystem() {
        Properties properties = System.getProperties();
        String home = properties.getProperty("user.home", ".");
        Path defaultCache;
        try {
            defaultCache = Path.of(home, ".coffee-gb", "native-cache");
        } catch (InvalidPathException invalid) {
            defaultCache = Path.of(".", ".coffee-gb-native-cache");
        }
        return bootstrapFromProperties(
                properties, defaultCache, NativeRuntimeBootstrap.class.getClassLoader());
    }

    static NativeRuntimeSelection bootstrapFromProperties(
            Properties properties,
            Path defaultCache,
            ClassLoader classLoader) {
        Optional<String> requested =
                Optional.ofNullable(properties.getProperty(TARGET_PROPERTY));
        if (requested.isEmpty()) {
            return bootstrap(
                    requested,
                    NativeResourceSource.classpath(classLoader),
                    configuredCache(properties, defaultCache));
        }

        Optional<NativeTarget> target = NativeTarget.fromId(requested.orElseThrow());
        if (target.isEmpty()) {
            return bootstrap(
                    requested,
                    NativeResourceSource.classpath(classLoader),
                    configuredCache(properties, defaultCache));
        }

        String configuredSource = properties.getProperty(SOURCE_PROPERTY);
        if (configuredSource == null || configuredSource.isBlank()) {
            return applySelection(NativeRuntimeSelection.Portable.after(
                    new NativeBundleFailure.NativeSourceNotConfigured(target.orElseThrow())));
        }
        try {
            Path sourcePath = Path.of(configuredSource);
            NativeResourceSource source = Files.isRegularFile(
                                    sourcePath, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(sourcePath)
                    ? NativeResourceSource.archive(
                            sourcePath, NativeBundleManifest.locked(target.orElseThrow()))
                    : NativeResourceSource.directory(sourcePath);
            return bootstrap(
                    requested,
                    source,
                    configuredCache(properties, defaultCache));
        } catch (InvalidPathException invalid) {
            return applySelection(NativeRuntimeSelection.Portable.after(
                    new NativeBundleFailure.NativeSourceNotConfigured(target.orElseThrow())));
        } catch (IOException invalidArchive) {
            return applySelection(NativeRuntimeSelection.Portable.after(
                    new NativeBundleFailure.IoFailure(
                            target.orElseThrow(), "open packaged native archive")));
        }
    }

    public static NativeRuntimeSelection bootstrap(
            Optional<String> requestedTarget,
            NativeResourceSource source,
            Path cacheRoot) {
        NativeRuntimeSelection selection =
                new NativeBundleResolver().select(requestedTarget, source, cacheRoot);
        return applySelection(selection);
    }

    private static NativeRuntimeSelection applySelection(NativeRuntimeSelection selection) {
        if (selection instanceof NativeRuntimeSelection.TargetBundle selected) {
            configure(selected.bundle());
            LOG.info(
                    "Using verified native bundle {} ({})",
                    selected.bundle().target().id(),
                    selected.bundle().root());
        } else {
            NativeRuntimeSelection.Portable portable =
                    (NativeRuntimeSelection.Portable) selection;
            portable.fallbackCause().ifPresent(failure ->
                    LOG.warn(
                            "Native target bundle unavailable ({}); using portable fallback",
                            failure.getClass().getSimpleName()));
        }
        return selection;
    }

    private static Path configuredCache(Properties properties, Path defaultCache) {
        String configured = properties.getProperty(CACHE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return defaultCache;
        }
        try {
            return Path.of(configured);
        } catch (InvalidPathException invalid) {
            return defaultCache;
        }
    }

    static void configure(NativeRuntimeBundle bundle) {
        Path dispatchDirectory = bundle.library(NativeComponent.JNA_DISPATCH).getParent();
        System.setProperty("jna.boot.library.path", dispatchDirectory.toString());
        System.setProperty(LIBRARY_DIRECTORY_PROPERTY, dispatchDirectory.toString());
        System.setProperty(
                OPENCV_LIBRARY_PROPERTY,
                bundle.library(NativeComponent.OPENCV).toString());
    }
}
