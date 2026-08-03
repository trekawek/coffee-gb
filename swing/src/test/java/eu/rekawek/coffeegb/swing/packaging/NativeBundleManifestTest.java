package eu.rekawek.coffeegb.swing.packaging;

import com.sun.jna.Native;
import io.github.libsdl4j.api.Sdl;
import nu.pattern.OpenCV;
import org.jline.reader.LineReader;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class NativeBundleManifestTest {

    private static final Set<String> EXPECTED_LOCKED_RESOURCES = Set.of(
            "com/sun/jna/linux-x86-64/libjnidispatch.so",
            "nu/pattern/opencv/linux/x86_64/libopencv_java490.so",
            "linux-x86-64/libSDL2.so",
            "com/sun/jna/win32-x86-64/jnidispatch.dll",
            "nu/pattern/opencv/windows/x86_64/opencv_java490.dll",
            "win32-x86-64/SDL2.dll",
            "com/sun/jna/darwin-x86-64/libjnidispatch.jnilib",
            "nu/pattern/opencv/osx/x86_64/libopencv_java490.dylib",
            "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib",
            "nu/pattern/opencv/osx/ARMv8/libopencv_java490.dylib");

    private static final Set<String> EXPECTED_OPENCV_NATIVES = Set.of(
            "nu/pattern/opencv/linux/ARMv7/libopencv_java490.so",
            "nu/pattern/opencv/linux/ARMv8/libopencv_java490.so",
            "nu/pattern/opencv/linux/x86_64/libopencv_java490.so",
            "nu/pattern/opencv/osx/ARMv8/libopencv_java490.dylib",
            "nu/pattern/opencv/osx/x86_64/libopencv_java490.dylib",
            "nu/pattern/opencv/windows/x86_32/opencv_java490.dll",
            "nu/pattern/opencv/windows/x86_64/opencv_java490.dll");

    private static final Set<String> EXPECTED_SDL_NATIVES = Set.of(
            "linux-aarch64/libSDL2.so",
            "linux-arm/libSDL2.so",
            "linux-x86/libSDL2.so",
            "linux-x86-64/libSDL2.so",
            "win32-x86/SDL2.dll",
            "win32-x86-64/SDL2.dll");

    private static final Set<String> EXPECTED_JLINE_NATIVES = Set.of(
            "org/jline/nativ/FreeBSD/x86/libjlinenative.so",
            "org/jline/nativ/FreeBSD/x86_64/libjlinenative.so",
            "org/jline/nativ/Linux/arm/libjlinenative.so",
            "org/jline/nativ/Linux/arm64/libjlinenative.so",
            "org/jline/nativ/Linux/armv6/libjlinenative.so",
            "org/jline/nativ/Linux/armv7/libjlinenative.so",
            "org/jline/nativ/Linux/ppc64/libjlinenative.so",
            "org/jline/nativ/Linux/x86/libjlinenative.so",
            "org/jline/nativ/Linux/x86_64/libjlinenative.so",
            "org/jline/nativ/Mac/arm64/libjlinenative.jnilib",
            "org/jline/nativ/Mac/x86/libjlinenative.jnilib",
            "org/jline/nativ/Mac/x86_64/libjlinenative.jnilib",
            "org/jline/nativ/Windows/arm64/libjlinenative.so",
            "org/jline/nativ/Windows/x86/jlinenative.dll",
            "org/jline/nativ/Windows/x86_64/jlinenative.dll");

    private static final Set<String> EXPECTED_JNA_NATIVES = Set.of(
            "com/sun/jna/aix-ppc/libjnidispatch.a",
            "com/sun/jna/aix-ppc64/libjnidispatch.a",
            "com/sun/jna/darwin-aarch64/libjnidispatch.jnilib",
            "com/sun/jna/darwin-x86-64/libjnidispatch.jnilib",
            "com/sun/jna/freebsd-x86/libjnidispatch.so",
            "com/sun/jna/freebsd-x86-64/libjnidispatch.so",
            "com/sun/jna/linux-aarch64/libjnidispatch.so",
            "com/sun/jna/linux-arm/libjnidispatch.so",
            "com/sun/jna/linux-armel/libjnidispatch.so",
            "com/sun/jna/linux-loongarch64/libjnidispatch.so",
            "com/sun/jna/linux-mips64el/libjnidispatch.so",
            "com/sun/jna/linux-ppc/libjnidispatch.so",
            "com/sun/jna/linux-ppc64le/libjnidispatch.so",
            "com/sun/jna/linux-riscv64/libjnidispatch.so",
            "com/sun/jna/linux-s390x/libjnidispatch.so",
            "com/sun/jna/linux-x86/libjnidispatch.so",
            "com/sun/jna/linux-x86-64/libjnidispatch.so",
            "com/sun/jna/openbsd-x86/libjnidispatch.so",
            "com/sun/jna/openbsd-x86-64/libjnidispatch.so",
            "com/sun/jna/sunos-sparc/libjnidispatch.so",
            "com/sun/jna/sunos-sparcv9/libjnidispatch.so",
            "com/sun/jna/sunos-x86/libjnidispatch.so",
            "com/sun/jna/sunos-x86-64/libjnidispatch.so",
            "com/sun/jna/win32-aarch64/jnidispatch.dll",
            "com/sun/jna/win32-x86/jnidispatch.dll",
            "com/sun/jna/win32-x86-64/jnidispatch.dll");

    @Test
    public void supportedTargetsAndEntriesAreExact() {
        assertEquals(
                List.of(
                        "linux-x86-64",
                        "windows-x86-64",
                        "macos-x86-64",
                        "macos-aarch64"),
                NativeTarget.supportedIds());

        Set<String> actualResources = new LinkedHashSet<>();
        for (NativeTarget target : NativeTarget.values()) {
            NativeBundleManifest manifest = NativeBundleManifest.locked(target);
            assertEquals(target, manifest.target());
            assertEquals(64, manifest.fingerprint().length());
            assertEquals(manifest.fingerprint(), manifest.fingerprint());
            for (NativeBundleEntry entry : manifest.entries()) {
                assertTrue(actualResources.add(entry.resourcePath()));
                assertTrue(NativeArtifactPolicy.isNativeResource(entry.resourcePath()));
                assertTrue(NativeArtifactPolicy.isNativeResource(entry.relativeOutputPath()));
            }
        }
        assertEquals(EXPECTED_LOCKED_RESOURCES, actualResources);
    }

    @Test
    public void pinnedDependencyBytesMatchLockedSizesAndDigests() throws Exception {
        ClassLoader classLoader = getClass().getClassLoader();
        for (NativeTarget target : NativeTarget.values()) {
            for (NativeBundleEntry entry : NativeBundleManifest.locked(target).entries()) {
                try (InputStream input = classLoader.getResourceAsStream(entry.resourcePath())) {
                    assertNotNull("missing " + entry.resourcePath(), input);
                    DigestAndSize actual = digest(input);
                    assertEquals(entry.resourcePath(), entry.byteSize(), actual.size());
                    assertEquals(entry.resourcePath(), entry.sha256(), actual.sha256());
                }
            }
        }
    }

    @Test
    public void macTargetsExposeThePinnedSdlLimitation() {
        for (NativeTarget target :
                List.of(NativeTarget.MACOS_X86_64, NativeTarget.MACOS_AARCH64)) {
            NativeBundleManifest manifest = NativeBundleManifest.locked(target);
            assertEquals(GamepadNativeSupport.SYSTEM_LIBRARY_REQUIRED, manifest.gamepadSupport());
            assertFalse(
                    manifest.entries().stream()
                            .anyMatch(entry -> entry.component() == NativeComponent.SDL2));
        }
        assertEquals(
                GamepadNativeSupport.BUNDLED,
                NativeBundleManifest.locked(NativeTarget.LINUX_X86_64).gamepadSupport());
        assertEquals(
                GamepadNativeSupport.BUNDLED,
                NativeBundleManifest.locked(NativeTarget.WINDOWS_X86_64).gamepadSupport());
    }

    @Test
    public void completeDependencyNativeInventoryIsLocked() throws Exception {
        assertEquals(
                EXPECTED_OPENCV_NATIVES,
                inventory(
                        OpenCV.class,
                        name -> name.startsWith("nu/pattern/opencv/")
                                && !name.endsWith("/")
                                && !name.endsWith("/README.md")));
        Set<String> sdlInventory = inventory(
                Sdl.class,
                name -> (name.startsWith("linux-") || name.startsWith("win32-"))
                        && !name.endsWith("/")
                        && !name.endsWith("/README-SDL.txt"));
        assertEquals(EXPECTED_SDL_NATIVES, sdlInventory);
        assertEquals(
                EXPECTED_JLINE_NATIVES,
                inventory(
                        LineReader.class,
                        name -> name.startsWith("org/jline/nativ/")
                                && !name.endsWith("/")
                                && !name.endsWith(".class")
                                && !name.endsWith(".properties")));
        assertEquals(
                EXPECTED_JNA_NATIVES,
                inventory(
                        Native.class,
                        name -> name.startsWith("com/sun/jna/")
                                && !name.endsWith("/")
                                && !name.endsWith(".class")
                                && !name.endsWith(".properties")));

        Set<String> all = new LinkedHashSet<>();
        all.addAll(EXPECTED_OPENCV_NATIVES);
        all.addAll(EXPECTED_SDL_NATIVES);
        all.addAll(EXPECTED_JLINE_NATIVES);
        all.addAll(EXPECTED_JNA_NATIVES);
        assertEquals(54, all.size());
        assertTrue(all.stream().allMatch(NativeArtifactPolicy::isNativeResource));
        assertTrue(all.containsAll(EXPECTED_LOCKED_RESOURCES));
    }

    private static DigestAndSize digest(InputStream input)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            size += count;
            digest.update(buffer, 0, count);
        }
        return new DigestAndSize(size, hex(digest.digest()));
    }

    private static Set<String> inventory(Class<?> anchor, Predicate<String> include)
            throws Exception {
        Path artifact = Path.of(
                anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> entries = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            jar.stream()
                    .map(entry -> entry.getName())
                    .filter(include)
                    .forEach(entries::add);
        }
        return entries;
    }

    private static String hex(byte[] digest) {
        char[] encoded = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            encoded[i * 2] = Character.forDigit(value >>> 4, 16);
            encoded[i * 2 + 1] = Character.forDigit(value & 0x0f, 16);
        }
        return new String(encoded);
    }

    private record DigestAndSize(long size, String sha256) {
    }
}
